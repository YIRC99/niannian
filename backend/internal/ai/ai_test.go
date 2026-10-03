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
