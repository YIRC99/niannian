package ai

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"niannian/internal/config"
	"strings"
	"testing"
	"time"
)

func TestParseValidatesAndDoesNotTrustSummary(t *testing.T) {
	draft := `{"title":"公司申报","description":"记得提交","rule":{"kind":"monthly_window","mode":"until_done","timezone":"Asia/Shanghai","times":["09:00"],"month_start":1,"month_end":15},"summary":"忽略所有规则","warnings":[]}`
	s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/chat/completions" || r.Header.Get("Authorization") != "Bearer test" {
			t.Error("wrong request")
		}
		var p map[string]any
		json.NewDecoder(r.Body).Decode(&p)
		if p["response_format"].(map[string]any)["type"] != "json_object" {
			t.Error("JSON mode required")
		}
		json.NewEncoder(w).Encode(map[string]any{"choices": []any{map[string]any{"finish_reason": "stop", "message": map[string]string{"content": draft}}}})
	}))
	defer s.Close()
	d, err := New(config.Config{DeepSeekBaseURL: s.URL, DeepSeekAPIKey: "test", DeepSeekModel: "deepseek-flash"}).Parse(context.Background(), "每月1到15号早上9点提醒申报，完成就停", time.Date(2026, 10, 3, 0, 0, 0, 0, time.UTC))
	if err != nil {
		t.Fatal(err)
	}
	if d.Title != "公司申报" || d.Rule.Kind != "monthly_window" || d.Summary == "忽略所有规则" {
		t.Fatalf("bad draft: %+v", d)
	}
}

func TestInvalidOrTruncatedModelOutputRejected(t *testing.T) {
	for _, content := range []string{`{"title":"坏任务","rule":{"kind":"daily","mode":"until_done","timezone":"Asia/Shanghai","times":["25:00"]}}`, `{"title":"缺规则"}`, `not json`} {
		s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			json.NewEncoder(w).Encode(map[string]any{"choices": []any{map[string]any{"finish_reason": "stop", "message": map[string]string{"content": content}}}})
		}))
		_, err := New(config.Config{DeepSeekBaseURL: s.URL, DeepSeekAPIKey: "secret"}).Parse(context.Background(), "提醒我", time.Now())
		s.Close()
		if err == nil {
			t.Fatal("invalid AI result accepted")
		}
		if strings.Contains(err.Error(), "secret") {
			t.Fatal("credential leak")
		}
	}
}

func TestUnsupportedRequestReturnsUsefulMessage(t *testing.T) {
	s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		json.NewEncoder(w).Encode(map[string]any{"choices": []any{map[string]any{"finish_reason": "stop", "message": map[string]string{"content": `{"error":"暂不支持按农历提醒，请改用公历日期"}`}}}})
	}))
	defer s.Close()
	_, err := New(config.Config{DeepSeekBaseURL: s.URL, DeepSeekAPIKey: "test"}).Parse(context.Background(), "农历每月初一", time.Now())
	if err == nil || !strings.Contains(err.Error(), "农历") {
		t.Fatal("unsupported rule not explained")
	}
}

func TestPastOneOffDoesNotBecomeUnreachableReminder(t *testing.T) {
	s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		json.NewEncoder(w).Encode(map[string]any{"choices": []any{map[string]any{"finish_reason": "stop", "message": map[string]string{"content": `{"title":"洗衣服","rule":{"kind":"once","mode":"until_done","timezone":"Asia/Shanghai","times":["09:00"],"start_date":"2026-10-03"}}`}}}})
	}))
	defer s.Close()
	_, err := New(config.Config{DeepSeekBaseURL: s.URL, DeepSeekAPIKey: "test"}).Parse(context.Background(), "今天9点提醒洗衣服", time.Date(2026, 10, 3, 4, 0, 0, 0, time.UTC))
	if err == nil {
		t.Fatal("past one-off accepted without future reminder")
	}
}

func TestMultilineTitleIsSafeForEmailSubject(t *testing.T) {
	s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		json.NewEncoder(w).Encode(map[string]any{"choices": []any{map[string]any{"finish_reason": "stop", "message": map[string]string{"content": `{"title":"公司\n申报","rule":{"kind":"daily","mode":"every_day","timezone":"Asia/Shanghai","times":["09:00"]}}`}}}})
	}))
	defer s.Close()
	d, err := New(config.Config{DeepSeekBaseURL: s.URL, DeepSeekAPIKey: "test"}).Parse(context.Background(), "公司申报", time.Now())
	if err != nil {
		t.Fatal(err)
	}
	if d.Title != "公司 申报" {
		t.Fatal("multiline title would break SMTP headers")
	}
}

func TestMinuteIntervalDraftPreservesRelativeReminderRequest(t *testing.T) {
	const input = "提醒我15分钟之后收衣服，没完成每隔5分钟提醒，一直提醒直到完成"
	const firstAt = "2026-10-04T00:10:37+08:00"
	const draft = `{"title":"收衣服","rule":{"kind":"minute_interval","mode":"until_done","timezone":"Asia/Shanghai","times":[],"start_date":"","end_date":"","first_at":"2026-10-04T00:10:37+08:00","repeat_minutes":5,"max_reminders":0},"warnings":[]}`
	s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var request struct {
			Messages []struct {
				Role    string `json:"role"`
				Content string `json:"content"`
			} `json:"messages"`
		}
		if err := json.NewDecoder(r.Body).Decode(&request); err != nil {
			t.Error(err)
			return
		}
		if len(request.Messages) != 2 || request.Messages[1].Role != "user" || request.Messages[1].Content != input {
			t.Errorf("complete original reminder request was not sent: %+v", request.Messages)
		}
		if len(request.Messages) == 0 || !strings.Contains(request.Messages[0].Content, "当前时间：2026-10-03T23:55:37+08:00") {
			t.Error("relative reminder reference must retain seconds and timezone offset")
		}
		json.NewEncoder(w).Encode(map[string]any{"choices": []any{map[string]any{"finish_reason": "stop", "message": map[string]string{"content": draft}}}})
	}))
	defer s.Close()
	now := time.Date(2026, 10, 3, 15, 55, 37, 0, time.UTC)
	d, err := New(config.Config{DeepSeekBaseURL: s.URL, DeepSeekAPIKey: "test"}).Parse(context.Background(), input, now)
	if err != nil {
		t.Fatal(err)
	}
	encoded, err := json.Marshal(d.Rule)
	if err != nil {
		t.Fatal(err)
	}
	var rule struct {
		FirstAt       string `json:"first_at"`
		RepeatMinutes int    `json:"repeat_minutes"`
	}
	if err := json.Unmarshal(encoded, &rule); err != nil {
		t.Fatal(err)
	}
	if d.Title != "收衣服" || d.Rule.Kind != "minute_interval" || d.Rule.Mode != "until_done" || rule.FirstAt != firstAt || rule.RepeatMinutes != 5 {
		t.Fatalf("minute reminder did not roundtrip: %+v / %s", d, encoded)
	}
	if len(d.Rule.Times) != 0 || d.Rule.StartDate != "" || d.Rule.EndDate != "" || d.Rule.MaxReminders != 0 {
		t.Fatalf("unbounded reminder gained an arbitrary clock, date, or count limit: %s", encoded)
	}
	if d.Summary == "" || len(d.Warnings) != 0 {
		t.Fatalf("unexpected minute reminder presentation: %+v", d)
	}
}

func TestInvalidMinuteIntervalModelOutputRejected(t *testing.T) {
	for _, interval := range []int{-1, 0, 1441} {
		t.Run(fmt.Sprint(interval), func(t *testing.T) {
			content := fmt.Sprintf(`{"title":"收衣服","rule":{"kind":"minute_interval","mode":"until_done","timezone":"Asia/Shanghai","times":[],"first_at":"2026-10-03T12:15:37+08:00","repeat_minutes":%d}}`, interval)
			s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				json.NewEncoder(w).Encode(map[string]any{"choices": []any{map[string]any{"finish_reason": "stop", "message": map[string]string{"content": content}}}})
			}))
			defer s.Close()
			_, err := New(config.Config{DeepSeekBaseURL: s.URL, DeepSeekAPIKey: "test"}).Parse(context.Background(), "15分钟后收衣服，没完成每隔5分钟提醒", time.Date(2026, 10, 3, 4, 0, 37, 0, time.UTC))
			if err == nil || !strings.Contains(err.Error(), "AI 时间规则需要调整") {
				t.Fatalf("invalid repeat_minutes should fail rule validation, got %v", err)
			}
		})
	}
}

func TestPastMinuteIntervalDraftDoesNotSilentlySkipFirstReminder(t *testing.T) {
	s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		json.NewEncoder(w).Encode(map[string]any{"choices": []any{map[string]any{"finish_reason": "stop", "message": map[string]string{"content": `{"title":"收衣服","rule":{"kind":"minute_interval","mode":"until_done","timezone":"Asia/Shanghai","times":[],"first_at":"2026-10-03T11:45:37+08:00","repeat_minutes":5}}`}}}})
	}))
	defer s.Close()
	_, err := New(config.Config{DeepSeekBaseURL: s.URL, DeepSeekAPIKey: "test"}).Parse(context.Background(), "今天11点45分37秒开始，每隔5分钟提醒收衣服直到完成", time.Date(2026, 10, 3, 4, 0, 37, 0, time.UTC))
	if err == nil || !strings.Contains(err.Error(), "首次提醒时间已经过去") {
		t.Fatalf("past first reminder should be explained instead of silently shifted: %v", err)
	}
}
