package ai

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
	"unicode/utf8"

	"niannian/internal/config"
	"niannian/internal/schedule"
)

type Draft struct {
	Title       string        `json:"title"`
	Description string        `json:"description"`
	Rule        schedule.Rule `json:"rule"`
	Summary     string        `json:"summary"`
	Warnings    []string      `json:"warnings"`
}

type Client struct {
	cfg  config.Config
	http *http.Client
}

func New(cfg config.Config) *Client {
	return &Client{cfg: cfg, http: &http.Client{Timeout: 60 * time.Second}}
}

func (c *Client) Parse(ctx context.Context, text string, now time.Time) (Draft, error) {
	var d Draft
	// The prompt carries RFC3339 seconds; validation must use the same reference precision.
	now = now.Truncate(time.Second)
	text = strings.TrimSpace(text)
	if text == "" || utf8.RuneCountInString(text) > 2000 {
		return d, errors.New("请用 1—2000 字描述需要提醒的事情")
	}
	if c.cfg.DeepSeekAPIKey == "" {
		return d, errors.New("AI 服务尚未配置，可以先手动创建")
	}
	loc, err := time.LoadLocation("Asia/Shanghai")
	if err != nil {
		return d, errors.New("时区不可用")
	}
	// Generation plus one review (or schema repair) share the Android timeout.
	ctx, cancel := context.WithTimeout(ctx, 65*time.Second)
	defer cancel()
	messages := []map[string]string{{"role": "system", "content": instructions + "\n当前时间：" + now.In(loc).Format(time.RFC3339) + "（" + now.In(loc).Format("Monday") + "）"}, {"role": "user", "content": text}}
	var lastIssue error
	for attempt := 0; attempt < 2; attempt++ {
		raw, requestErr := c.request(ctx, messages)
		if requestErr != nil {
			return Draft{}, requestErr
		}
		d, validationErr := decodeDraft(raw, now)
		if validationErr == nil {
			if attempt == 0 {
				messages = append(messages,
					map[string]string{"role": "assistant", "content": raw},
					map[string]string{"role": "user", "content": draftReviewPrompt(d, now)})
				continue
			}
			return d, nil
		}
		lastIssue = validationErr
		if attempt == 0 {
			messages = append(messages, map[string]string{"role": "assistant", "content": raw}, map[string]string{"role": "user", "content": "刚才未得到可用草稿，校验反馈：" + validationErr.Error() + "。请结合原始用户请求和同一个当前时间重新安排，返回完整的合规JSON。用户会审核后再确认创建；时间、频率或事项模糊时请主动作合理建议，用explanation标签标明补充假设与未实现条件，不写自由文本warnings或summary，不要追问具体时间。保留可表达的明确条件；确实无法原样表达的条件，请给可执行的替代草稿，不能悄悄删除条件，也不要只返回error。"})
		}
	}
	if lastIssue != nil && (strings.HasPrefix(lastIssue.Error(), "首次提醒时间已经过去") || strings.HasPrefix(lastIssue.Error(), "这条规则没有未来的提醒时间")) {
		return Draft{}, lastIssue
	}
	return Draft{}, errors.New("这次没能整理好提醒安排，请补充时间或重试，也可以手动填写")
}

// Ground the one permitted review in the same clock and the real scheduling
// engine. A structurally valid candidate can still change the user's intent or
// describe behavior its rule cannot execute. No natural-language rules are
// inferred by local keyword/number matching, and no unreviewed fallback is saved.
func draftReviewPrompt(d Draft, now time.Time) string {
	loc, _ := time.LoadLocation(d.Rule.Timezone) // Already validated by decodeDraft.
	facts := map[string]any{
		"current_time": now.In(loc).Format(time.RFC3339),
		"rule_summary": schedule.Summary(d.Rule),
		"timezone":     d.Rule.Timezone,
	}
	if d.Rule.Kind == "minute_interval" && d.Rule.EndAt != "" {
		first, _ := time.Parse(time.RFC3339, d.Rule.FirstAt)
		end, _ := time.Parse(time.RFC3339, d.Rule.EndAt)
		switch {
		case now.Before(first):
			facts["window_status"] = "not_started"
		case now.After(end):
			facts["window_status"] = "ended"
		default:
			facts["window_status"] = "active"
		}
	}
	b, _ := json.Marshal(facts)
	return `请复核并返回最终完整JSON草稿，不增加字段，不解释审核过程。上一条只是候选，还未向用户展示或创建。
重新对照最初的用户原话与同一个当前时间，逐项检查最终改口、时区、原始窗口锚点、完整跨月日期、间隔、完成方式、次数上限。即使JSON合法，仍要修正丢失的要求。跨月重复不可表达时优先保留完整单次date_range，不要删掉次月日期。保留已经正确的规则，不为优化措辞随意改时间或频率。
下面是调度器对当前候选计算的事实（只对应当前候选，不是原需求的正确答案；如果你改了规则，应据新规则重新理解，不能盲目套用旧候选时间）：` + string(b) + `
not_started表示尚未开始，active表示正在进行，ended才表示已结束。不能把窗口已开始说成已过，也不能把未来时间说成已过。若不需要讨论时态，就只写实际的绝对日期时间安排。minute_interval的原始first_at可能早于当前时间，它是节奏锚点，不是必须改为现在的下次时间；严禁从当前时刻重算既定窗口。
description必须只留下地点、携带物品、原会议时间等真实事项备注。单纯的提醒频率、账期循环、完成条件不是备注，应删除；没有事情备注就填空字符串。尤其不要把不能实现的原始循环写入description。
最终JSON包含explanation的assumptions和limitations数组，不写自由文本warnings或summary，也不要口算或描述下一候选时间。后端将按最终rule计算所有日期、时态、下一候选和完成说明。自行补全首次时间/间隔/年份时分别标first_time/repeat_interval/year；单次替代不自动重复标recurrence；仅当用户要求节假日/调休例外才标holiday_calendar。
完成方式按原话重新核对，不能只看“每天”两字：“每天催，但整个账期/这一轮做完一次就停”必须until_done；只有“每天必须分别完成”才是every_day；明确无需完成且只通知一次才是notify_once。跨月替代同样遵守这个区分，两个方向都不能改错。同月28—31等短月截断是支持的monthly_window，不得误当跨月或改成date_range。用户明确给出的频率与上限不是假设。不承诺自动补排、任意日期排除或用聊天完成任务。`
}

func (c *Client) request(ctx context.Context, messages []map[string]string) (string, error) {
	payload := map[string]any{"model": config.DeepSeekFlashModel, "messages": messages, "response_format": map[string]string{"type": "json_object"}, "max_tokens": 2048, "temperature": 0.1, "thinking": map[string]string{"type": "disabled"}}
	b, _ := json.Marshal(payload)
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, strings.TrimRight(c.cfg.DeepSeekBaseURL, "/")+"/chat/completions", bytes.NewReader(b))
	if err != nil {
		return "", errors.New("AI 地址配置错误")
	}
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Authorization", "Bearer "+c.cfg.DeepSeekAPIKey)
	response, err := c.http.Do(request)
	if err != nil {
		return "", errors.New("AI 暂时连接不上，请稍后重试或手动创建")
	}
	defer response.Body.Close()
	if response.StatusCode != 200 {
		switch response.StatusCode {
		case 401, 403:
			return "", errors.New("AI 服务认证失败，请检查后端密钥")
		case 402:
			return "", errors.New("AI 账户余额不足，可以先手动创建")
		case 429:
			return "", errors.New("AI 请求繁忙，请稍后重试")
		default:
			return "", fmt.Errorf("AI 服务暂时不可用（%d），可以先手动创建", response.StatusCode)
		}
	}
	var envelope struct {
		Choices []struct {
			FinishReason string `json:"finish_reason"`
			Message      struct {
				Content string `json:"content"`
			} `json:"message"`
		} `json:"choices"`
	}
	if err = json.NewDecoder(io.LimitReader(response.Body, 256*1024)).Decode(&envelope); err != nil || len(envelope.Choices) == 0 {
		return "", errors.New("AI 返回内容不完整，请重新描述或手动创建")
	}
	choice := envelope.Choices[0]
	if choice.FinishReason != "stop" {
		return "", errors.New("AI 未能完整生成规则，请缩短描述重试")
	}
	return strings.TrimSpace(choice.Message.Content), nil
}

type unsupportedRule struct{ message string }

func (e *unsupportedRule) Error() string { return e.message }

func decodeDraft(raw string, now time.Time) (Draft, error) {
	var d Draft
	var draftError struct {
		Error string `json:"error"`
	}
	if err := json.Unmarshal([]byte(raw), &draftError); err != nil {
		return d, errors.New("AI 返回格式不正确，请重试")
	}
	if draftError.Error != "" {
		if utf8.RuneCountInString(draftError.Error) > 300 {
			return d, errors.New("这条规则暂不支持，请调整描述或手动创建")
		}
		return d, &unsupportedRule{message: draftError.Error}
	}
	var wire struct {
		Draft
		Explanation *draftExplanation `json:"explanation"`
	}
	decoder := json.NewDecoder(strings.NewReader(raw))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&wire); err != nil {
		return Draft{}, fmt.Errorf("JSON字段或类型不符合约定：%w", err)
	}
	d = wire.Draft
	d.Title = strings.Join(strings.Fields(d.Title), " ")
	d.Description = strings.TrimSpace(d.Description)
	if d.Title == "" || utf8.RuneCountInString(d.Title) > 100 || utf8.RuneCountInString(d.Description) > 2000 {
		return Draft{}, errors.New("AI 生成的标题或备注不符合要求，请重新描述")
	}
	if err := schedule.Validate(d.Rule); err != nil {
		return Draft{}, fmt.Errorf("AI 时间规则需要调整：%w", err)
	}
	if d.Rule.Kind == "minute_interval" {
		firstAt, _ := time.Parse(time.RFC3339, d.Rule.FirstAt)
		if firstAt.Before(now) && d.Rule.EndAt == "" {
			return Draft{}, errors.New("首次提醒时间已经过去，请调整时间后重试")
		}
	}
	if _, ok := schedule.Next(d.Rule, now); !ok {
		return Draft{}, errors.New("这条规则没有未来的提醒时间，请调整日期或时间后重试")
	}
	if err := wire.Explanation.validate(d.Rule); err != nil {
		return Draft{}, err
	}
	d.Warnings = wire.Explanation.warnings(d.Rule, now)
	d.Summary = schedule.Summary(d.Rule)
	return d, nil
}
