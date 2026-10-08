package ai

import (
	"encoding/json"
	"errors"
	"io"
	"strings"
	"time"
	"unicode/utf8"
)

type ClarificationOption struct {
	Label string `json:"label"`
	Value string `json:"value"`
}
type Clarification struct {
	Question  string                `json:"question"`
	Options   []ClarificationOption `json:"options"`
	InputHint string                `json:"input_hint"`
}
type ClarificationError struct{ Clarification Clarification }

func (e *ClarificationError) Error() string { return e.Clarification.Question }

type Answer struct {
	Question   string `json:"question"`
	Answer     string `json:"answer"`
	AnsweredAt string `json:"answered_at,omitempty"`
}

type conversationInput struct {
	Original  string   `json:"original_request"`
	Answers   []Answer `json:"answers"`
	Reference string   `json:"original_reference_time"`
}

func inputMessages(text string, now time.Time) []map[string]string {
	system := instructions + "\n当前时间：" + now.Format(time.RFC3339) + "（" + now.Format("Monday") + "）"
	var conversation conversationInput
	decoder := json.NewDecoder(strings.NewReader(text))
	decoder.DisallowUnknownFields()
	if decoder.Decode(&conversation) == nil && decoder.Decode(new(any)) == io.EOF && conversation.Original != "" && len(conversation.Answers) > 0 && conversation.Reference != "" {
		system += "\n原话提交时间：" + conversation.Reference + "。第一条用户消息里的今天/明天/后天相对这个原话时间计算，不能随续答变动；后续回答的相对日期按各条回答自己的发送时间。校验是否过期仍用当前时间。所有用户消息共同构成需求，最新明确改口优先。"
		anchor := datedMessage(conversation.Reference, now)
		messages := []map[string]string{{"role": "system", "content": system}, {"role": "user", "content": anchor + conversation.Original}}
		for _, answer := range conversation.Answers {
			messages = append(messages, map[string]string{"role": "assistant", "content": answer.Question}, map[string]string{"role": "user", "content": datedMessage(answer.AnsweredAt, now) + answer.Answer})
		}
		messages[0]["content"] += "\n现在是在继续已有追问：已选选项就是用户的确定答案，不要执行原话中已经满足的‘先让我选’。最新回答若只给时刻，沿用原话已经确定的日期，不重新理解‘明天’。"
		return messages
	}
	return []map[string]string{{"role": "system", "content": system}, {"role": "user", "content": text}}
}

func datedMessage(value string, now time.Time) string {
	reference, err := time.Parse(time.RFC3339, value)
	if err != nil {
		return ""
	}
	reference = reference.In(now.Location())
	return "【这条消息发送于" + reference.Format(time.RFC3339) + "；其中今天=" + reference.Format("2006-01-02") + "，明天=" + reference.AddDate(0, 0, 1).Format("2006-01-02") + "，后天=" + reference.AddDate(0, 0, 2).Format("2006-01-02") + "】\n"
}

func bounded(s string, min, max int) bool {
	n := utf8.RuneCountInString(strings.TrimSpace(s))
	return n >= min && n <= max
}

// Keep original intent separate from answers; these are untrusted user data,
// never system instructions. No conversation or draft is persisted server-side.
func ClarificationInput(text string, answers []Answer, reference ...time.Time) (string, error) {
	text = strings.TrimSpace(text)
	if !bounded(text, 1, 2000) || len(answers) > 4 {
		return "", errors.New("请保留 1—2000 字原话，最多补充 4 轮")
	}
	for i := range answers {
		answers[i].Question = strings.TrimSpace(answers[i].Question)
		answers[i].Answer = strings.TrimSpace(answers[i].Answer)
		if !bounded(answers[i].Question, 1, 200) || !bounded(answers[i].Answer, 1, 500) {
			return "", errors.New("补充内容需为 1—500 字，问题需为 1—200 字")
		}
	}
	if len(answers) == 0 {
		return text, nil
	}
	referenceTime := ""
	if len(reference) > 0 {
		referenceTime = reference[0].Format(time.RFC3339)
	}
	b, err := json.Marshal(conversationInput{text, answers, referenceTime})
	if err != nil {
		return "", err
	}
	return string(b), nil
}

func decodeClarification(raw string) (*ClarificationError, error) {
	var wire struct {
		Clarification *Clarification `json:"clarification"`
	}
	d := json.NewDecoder(strings.NewReader(raw))
	d.DisallowUnknownFields()
	if err := d.Decode(&wire); err != nil {
		return nil, errors.New("追问格式不正确，不能同时返回草稿")
	}
	if d.Decode(new(any)) != io.EOF || wire.Clarification == nil {
		return nil, errors.New("追问内容不完整")
	}
	c := wire.Clarification
	c.Question = strings.TrimSpace(c.Question)
	c.InputHint = strings.TrimSpace(c.InputHint)
	if !bounded(c.Question, 1, 200) || !bounded(c.InputHint, 0, 120) || len(c.Options) > 4 {
		return nil, errors.New("追问或选项太长，请简化")
	}
	labels, values := map[string]bool{}, map[string]bool{}
	for i := range c.Options {
		o := &c.Options[i]
		o.Label = strings.TrimSpace(o.Label)
		o.Value = strings.TrimSpace(o.Value)
		if !bounded(o.Label, 1, 40) || !bounded(o.Value, 1, 200) || labels[o.Label] || values[o.Value] {
			return nil, errors.New("追问选项需简短且不重复")
		}
		labels[o.Label] = true
		values[o.Value] = true
	}
	if c.Options == nil {
		c.Options = []ClarificationOption{}
	}
	return &ClarificationError{Clarification: *c}, nil
}
