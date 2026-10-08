package ai

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"niannian/internal/config"
)

func TestParentCancellationDuringReviewDiscardsCandidate(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	var calls atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if calls.Add(1) == 2 {
			cancel()
			return
		}
		result := `{"explanation":{"assumptions":[],"limitations":[]},"title":"整理桌面","rule":{"kind":"daily","mode":"every_day","timezone":"Asia/Shanghai","times":["19:00"]}}`
		json.NewEncoder(w).Encode(map[string]any{"choices": []any{map[string]any{"finish_reason": "stop", "message": map[string]string{"content": result}}}})
	}))
	defer server.Close()
	d, err := New(config.Config{DeepSeekAPIKey: "test", DeepSeekBaseURL: server.URL}).Parse(ctx, "每天19点整理桌面", time.Date(2026, 10, 8, 8, 0, 0, 0, time.UTC))
	if calls.Load() != 2 || err == nil || d.Title != "" {
		t.Fatalf("canceled review must discard first candidate: calls=%d draft=%+v err=%v", calls.Load(), d, err)
	}
}

func TestValidDraftReceivesGroundedReviewBeforeReturn(t *testing.T) {
	first := `{"explanation":{"assumptions":[],"limitations":[]},"title":"提交材料","description":"明天自动继续","rule":{"kind":"minute_interval","mode":"until_done","timezone":"Asia/Shanghai","first_at":"2026-10-08T14:37:20+08:00","end_at":"2026-10-08T17:00:00+08:00","repeat_minutes":20},"warnings":["现在重新开始"]}`
	reviewed := `{"explanation":{"assumptions":[],"limitations":[]},"title":"提交材料","description":"","rule":{"kind":"minute_interval","mode":"until_done","timezone":"Asia/Shanghai","first_at":"2026-10-08T14:00:00+08:00","end_at":"2026-10-08T17:00:00+08:00","repeat_minutes":20},"warnings":["今天14:00至17:00的单次窗口，沿原起点每20分钟提醒，完成一次就停，不自动重复；确认后创建。"]}`
	input := "今天14点到17点每20分钟提醒提交材料，只安排余下时间，完成一次就停"
	calls := 0
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		var p struct {
			Messages []struct{ Role, Content string }
		}
		if err := json.NewDecoder(r.Body).Decode(&p); err != nil {
			t.Error(err)
		}
		result := first
		if calls == 2 {
			if len(p.Messages) != 4 || p.Messages[1].Content != input || p.Messages[2].Content != first {
				t.Error("review lost original input or candidate")
			}
			for _, fact := range []string{"2026-10-08T14:37:20+08:00", `"window_status":"active"`, "复核"} {
				if !strings.Contains(p.Messages[3].Content, fact) {
					t.Errorf("missing grounded review fact: %s", fact)
				}
			}
			result = reviewed
		}
		json.NewEncoder(w).Encode(map[string]any{"choices": []any{map[string]any{"finish_reason": "stop", "message": map[string]string{"content": result}}}})
	}))
	defer server.Close()
	now, _ := time.Parse(time.RFC3339, "2026-10-08T14:37:20+08:00")
	d, err := New(config.Config{DeepSeekAPIKey: "test", DeepSeekBaseURL: server.URL}).Parse(context.Background(), input, now)
	if err != nil || calls != 2 || d.Rule.FirstAt != "2026-10-08T14:00:00+08:00" || d.Description != "" {
		t.Fatalf("review not applied: calls=%d draft=%+v err=%v", calls, d, err)
	}
}

func TestUnusableReviewDoesNotReturnUncheckedCandidate(t *testing.T) {
	for _, second := range []string{`{"error":"不确定"}`, `{"explanation":{"assumptions":[],"limitations":[]},"title":"坏结果","rule":{"kind":"daily","mode":"every_day","timezone":"Asia/Shanghai","times":["27:00"]}}`} {
		t.Run(second, func(t *testing.T) {
			calls := 0
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				calls++
				result := `{"explanation":{"assumptions":[],"limitations":[]},"title":"整理桌面","rule":{"kind":"daily","mode":"every_day","timezone":"Asia/Shanghai","times":["19:00"]}}`
				if calls > 1 {
					result = second
				}
				json.NewEncoder(w).Encode(map[string]any{"choices": []any{map[string]any{"finish_reason": "stop", "message": map[string]string{"content": result}}}})
			}))
			defer server.Close()
			d, err := New(config.Config{DeepSeekAPIKey: "test", DeepSeekBaseURL: server.URL}).Parse(context.Background(), "每天19点整理桌面", time.Date(2026, 10, 8, 8, 0, 0, 0, time.UTC))
			if calls != 2 || err == nil || d.Title != "" {
				t.Fatalf("must fail closed after one review: calls=%d draft=%+v err=%v", calls, d, err)
			}
		})
	}
}
