package ai

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"niannian/internal/config"
	"strings"
	"testing"
	"time"
)

const repairInput = "十分钟之后提醒我收衣服"
const validRepairedDraft = `{"explanation":{"assumptions":[],"limitations":[]},"title":"收衣服","rule":{"kind":"minute_interval","mode":"until_done","timezone":"Asia/Shanghai","times":[],"first_at":"2026-10-03T12:10:37+08:00","repeat_minutes":7},"warnings":["未指定间隔，安排每7分钟提醒直到完成，可在草稿中调整。"]}`

func TestModelClarificationGetsOneChanceToProposeDraft(t *testing.T) {
	const input = "等会儿提醒我搞卫生"
	const refused = `{"error":"请补充具体提醒时间，例如10分钟后提醒我搞卫生"}`
	const proposed = `{"explanation":{"assumptions":[],"limitations":[]},"title":"搞卫生","rule":{"kind":"minute_interval","mode":"until_done","timezone":"Asia/Shanghai","times":[],"first_at":"2026-10-03T20:35:37+08:00","repeat_minutes":15},"warnings":["我先把等会儿安排为10分钟后，之后每15分钟提醒，做完就停；你看一下，确认后才会创建。"]}`
	calls := 0
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		var p struct {
			Messages []struct{ Role, Content string } `json:"messages"`
		}
		if err := json.NewDecoder(r.Body).Decode(&p); err != nil {
			t.Error(err)
			return
		}
		content := refused
		if calls == 2 {
			content = proposed
			if len(p.Messages) != 4 || p.Messages[1].Content != input || p.Messages[2].Content != refused || !strings.Contains(p.Messages[0].Content, "2026-10-03T20:25:37+08:00") {
				t.Error("lost original intent, refusal or reference time during proposal")
			}
		}
		json.NewEncoder(w).Encode(map[string]any{"choices": []any{map[string]any{"finish_reason": "stop", "message": map[string]string{"content": content}}}})
	}))
	defer server.Close()
	d, err := New(config.Config{DeepSeekAPIKey: "test", DeepSeekBaseURL: server.URL}).Parse(context.Background(), input, time.Date(2026, 10, 3, 12, 25, 37, 0, time.UTC))
	if err != nil || calls != 2 || d.Title != "搞卫生" || d.Rule.RepeatMinutes != 15 || len(d.Warnings) != 1 {
		t.Fatalf("vague time should get an AI proposal with assumptions: calls=%d draft=%+v err=%v", calls, d, err)
	}
}

func TestRepeatedModelRefusalIsBounded(t *testing.T) {
	calls := 0
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		json.NewEncoder(w).Encode(map[string]any{"choices": []any{map[string]any{"finish_reason": "stop", "message": map[string]string{"content": `{"error":"请补充具体提醒时间"}`}}}})
	}))
	defer server.Close()
	d, err := New(config.Config{DeepSeekAPIKey: "test", DeepSeekBaseURL: server.URL}).Parse(context.Background(), "等会儿提醒我搞卫生", time.Now())
	if calls != 2 || err == nil || d.Title != "" || strings.Contains(err.Error(), "补充具体提醒时间") {
		t.Fatalf("must stop after one re-plan and report generation failure, not demand an exact time: calls=%d draft=%+v err=%v", calls, d, err)
	}
}

func TestInvalidDraftIsRepairedByFlashWithOriginalContext(t *testing.T) {
	for _, invalid := range []string{
		`{"explanation":{"assumptions":[],"limitations":[]},"title":"收衣服","rule":{"kind":"minute_interval","mode":"until_done","timezone":"Asia/Shanghai","first_at":"2026-10-03T12:10:37+08:00","repeat_minutes":0}}`,
		`{"explanation":{"assumptions":[],"limitations":[]},"title":"收衣服","rule":{"kind":"minute_interval","mode":"until_done","timezone":"Asia/Shanghai","first_at":"2026-10-03T12:10:37+08:00","repeat_minutes":"五"}}`,
		`{"explanation":{"assumptions":[],"limitations":[]},"title":"收衣服","rule":`,
	} {
		t.Run(invalid, func(t *testing.T) {
			calls := 0
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				calls++
				var p struct {
					Model    string `json:"model"`
					Thinking struct {
						Type string `json:"type"`
					} `json:"thinking"`
					Messages []struct{ Role, Content string } `json:"messages"`
				}
				if err := json.NewDecoder(r.Body).Decode(&p); err != nil {
					t.Fatal(err)
				}
				if p.Model != "deepseek-flash" || p.Thinking.Type != "disabled" {
					t.Errorf("must pin Flash without thinking: %s/%s", p.Model, p.Thinking.Type)
				}
				if len(p.Messages) < 2 || p.Messages[1].Content != repairInput || !strings.Contains(p.Messages[0].Content, "2026-10-03T12:00:37+08:00") {
					t.Error("lost original sentence or stable time")
				}
				content := invalid
				if calls > 1 {
					content = validRepairedDraft
					if len(p.Messages) != 4 || p.Messages[2].Role != "assistant" || p.Messages[2].Content != invalid || p.Messages[3].Role != "user" || !strings.Contains(p.Messages[3].Content, "校验") {
						t.Error("repair must include rejected draft and validation feedback")
					}
				}
				json.NewEncoder(w).Encode(map[string]any{"choices": []any{map[string]any{"finish_reason": "stop", "message": map[string]string{"content": content}}}})
			}))
			defer server.Close()
			d, err := New(config.Config{DeepSeekAPIKey: "test", DeepSeekBaseURL: server.URL, DeepSeekModel: "deepseek-reasoner"}).Parse(context.Background(), repairInput, time.Date(2026, 10, 3, 4, 0, 37, 0, time.UTC))
			if err != nil || calls != 2 || d.Rule.RepeatMinutes != 7 || len(d.Warnings) != 1 {
				t.Fatalf("must use AI's repaired interval, not a hardcoded fallback: calls=%d err=%v rule=%+v", calls, err, d.Rule)
			}
		})
	}
}

func TestInvalidDraftRepairIsBounded(t *testing.T) {
	calls := 0
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		json.NewEncoder(w).Encode(map[string]any{"choices": []any{map[string]any{"finish_reason": "stop", "message": map[string]string{"content": `{"explanation":{"assumptions":[],"limitations":[]},"title":"收衣服","rule":{"kind":"minute_interval","mode":"until_done","timezone":"Asia/Shanghai","repeat_minutes":0}}`}}}})
	}))
	defer server.Close()
	_, err := New(config.Config{DeepSeekAPIKey: "test", DeepSeekBaseURL: server.URL}).Parse(context.Background(), repairInput, time.Now())
	if calls != 2 || err == nil || strings.Contains(err.Error(), "1440") || strings.Contains(err.Error(), "repeat_minutes") {
		t.Fatalf("expected bounded repair and user-readable failure, calls=%d err=%v", calls, err)
	}
}

func TestProviderFailureDoesNotRetryOrInventDraft(t *testing.T) {
	for _, status := range []int{401, 402, 429, 503} {
		t.Run(http.StatusText(status), func(t *testing.T) {
			calls := 0
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { calls++; w.WriteHeader(status) }))
			defer server.Close()
			d, err := New(config.Config{DeepSeekAPIKey: "test", DeepSeekBaseURL: server.URL}).Parse(context.Background(), repairInput, time.Now())
			if err == nil || calls != 1 || d.Title != "" {
				t.Fatalf("upstream failure must stop: calls=%d err=%v", calls, err)
			}
		})
	}
}

func TestActiveWindowDraftKeepsItsOriginalWindow(t *testing.T) {
	const output = `{"explanation":{"assumptions":[],"limitations":[]},"title":"收衣服","rule":{"kind":"minute_interval","mode":"until_done","timezone":"Asia/Shanghai","times":[],"first_at":"2026-10-03T14:00:00+08:00","end_at":"2026-10-03T17:00:00+08:00","repeat_minutes":30},"warnings":["窗口已开始，只安排余下时间。"]}`
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		json.NewEncoder(w).Encode(map[string]any{"choices": []any{map[string]any{"finish_reason": "stop", "message": map[string]string{"content": output}}}})
	}))
	defer server.Close()
	d, err := New(config.Config{DeepSeekAPIKey: "test", DeepSeekBaseURL: server.URL}).Parse(context.Background(), "今天下午两点到五点提醒我收衣服", time.Date(2026, 10, 3, 8, 0, 37, 123000000, time.UTC))
	if err != nil || d.Rule.FirstAt != "2026-10-03T14:00:00+08:00" || d.Rule.EndAt != "2026-10-03T17:00:00+08:00" {
		t.Fatalf("active window must remain usable without shifting dates: %+v / %v", d, err)
	}
}

func TestCurrentSecondIsConsistentWithModelClock(t *testing.T) {
	const output = `{"explanation":{"assumptions":[],"limitations":[]},"title":"收衣服","rule":{"kind":"minute_interval","mode":"until_done","timezone":"Asia/Shanghai","times":[],"first_at":"2026-10-03T12:00:37+08:00","repeat_minutes":5}}`
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		json.NewEncoder(w).Encode(map[string]any{"choices": []any{map[string]any{"finish_reason": "stop", "message": map[string]string{"content": output}}}})
	}))
	defer server.Close()
	_, err := New(config.Config{DeepSeekAPIKey: "test", DeepSeekBaseURL: server.URL}).Parse(context.Background(), "现在开始提醒我收衣服，没完成每5分钟继续", time.Date(2026, 10, 3, 4, 0, 37, 123000000, time.UTC))
	if err != nil {
		t.Fatal(err)
	}
}
