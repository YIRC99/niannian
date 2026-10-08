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
	if text == "" || utf8.RuneCountInString(text) > 6000 {
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
	messages := inputMessages(text, now.In(loc))
	var lastIssue error
	for attempt := 0; attempt < 2; attempt++ {
		raw, requestErr := c.request(ctx, messages)
		if requestErr != nil {
			return Draft{}, requestErr
		}
		d, validationErr := decodeDraft(raw, now)
		var clarification *ClarificationError
		if errors.As(validationErr, &clarification) {
			return Draft{}, clarification
		}
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
			messages = append(messages, map[string]string{"role": "assistant", "content": raw}, map[string]string{"role": "user", "content": repairInstructions + validationErr.Error()})
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
	return reviewInstructions + string(b) + "\n最后依据用户原始窗口核对first_at，而不是抄上面的下次候选：窗口进行中保留原起点，不改成now或下一个提醒点。生活待办等会儿且没截止用minute_interval，无end_date/end_at。原话明确几分钟后的生活待办必须minute_interval，first_at保留秒，不用once丢秒；只输出纯英文explanation标签，不附解释。"
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
		Error         string          `json:"error"`
		Clarification json.RawMessage `json:"clarification"`
	}
	if err := json.Unmarshal([]byte(raw), &draftError); err != nil {
		return d, errors.New("AI 返回格式不正确，请重试")
	}
	if len(draftError.Clarification) > 0 {
		question, err := decodeClarification(raw)
		if err != nil {
			return d, err
		}
		return d, question
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
