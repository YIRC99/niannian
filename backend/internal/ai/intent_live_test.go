package ai

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"niannian/internal/config"
	"os"
	"testing"
	"time"
)

// This opt-in test uses only synthetic reminder sentences, never creates tasks or sends mail.
func TestLiveReminderIntent(t *testing.T) {
	if os.Getenv("NIANNIAN_LIVE_AI") != "1" {
		t.Skip("opt-in real Flash reminder parsing")
	}
	cfg, err := config.Load(config.EmbeddedDefaults())
	if err != nil {
		t.Fatal(err)
	}
	now := time.Date(2026, 10, 3, 4, 0, 37, 0, time.UTC)
	cases := []struct {
		name, text string
		delay      int
		window     bool
	}{
		{"chinese_ten", "十分钟之后提醒我收衣服", 10, false},
		{"arabic_ten", "10分钟之后提醒我收衣服", 10, false},
		{"chinese_fifteen", "十五分钟之后提醒我收衣服", 15, false},
		{"arabic_fifteen", "15分钟之后提醒我收衣服", 15, false},
		{"chinese_window", "提醒我今天下午两点到下午五点去收衣服，收一次就可以了", 0, true},
		{"arabic_window", "提醒我今天下午2点到下午5点去收衣服，收一次就可以了", 0, true},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			t.Parallel()
			client := New(cfg)
			client.http.Transport = intentTrace{base: http.DefaultTransport, t: t}
			ctx, cancel := context.WithTimeout(context.Background(), 65*time.Second)
			defer cancel()
			d, err := client.Parse(ctx, tc.text, now)
			if err != nil {
				t.Fatal(err)
			}
			if d.Rule.Mode != "until_done" {
				t.Fatalf("a real task must stop only after completion: mode=%s", d.Rule.Mode)
			}
			if tc.delay > 0 {
				first, parseErr := time.Parse(time.RFC3339, d.Rule.FirstAt)
				if d.Rule.Kind != "minute_interval" || parseErr != nil || !first.Equal(now.Add(time.Duration(tc.delay)*time.Minute)) || d.Rule.RepeatMinutes < 1 || d.Rule.RepeatMinutes > 1440 || d.Rule.MaxReminders != 0 || d.Rule.EndDate != "" {
					t.Fatalf("incorrect relative task: %+v", d.Rule)
				}
			} else if tc.window {
				var rule map[string]any
				b, _ := json.Marshal(d.Rule)
				_ = json.Unmarshal(b, &rule)
				if d.Rule.Kind != "minute_interval" || d.Rule.FirstAt != "2026-10-03T14:00:00+08:00" || rule["end_at"] != "2026-10-03T17:00:00+08:00" {
					t.Fatalf("incorrect exact window: %+v", d.Rule)
				}
			}
			t.Log(d.Summary)
		})
	}
}

type intentTrace struct {
	base http.RoundTripper
	t    *testing.T
}

func TestLiveCompletionSemantics(t *testing.T) {
	if os.Getenv("NIANNIAN_LIVE_AI") != "1" {
		t.Skip("opt-in real Flash reminder parsing")
	}
	cfg, err := config.Load(config.EmbeddedDefaults())
	if err != nil {
		t.Fatal(err)
	}
	cases := []struct{ text, kind, mode string }{
		{"明天早上9点只通知我提交材料一次，不用确认完成", "once", "notify_once"},
		{"未来一周每天晚上8点提醒我交材料，只需要交一次，交完这一周就不再提醒", "date_range", "until_done"},
		{"每周一和周五晚上8点提醒我浇花，这两天都需要各完成一次", "weekly", "every_day"},
		{"今天下午2点到5点，每隔5分钟提醒我收衣服，收完一次就停止", "minute_interval", "until_done"},
	}
	for _, tc := range cases {
		t.Run(tc.kind+"_"+tc.mode, func(t *testing.T) {
			t.Parallel()
			client := New(cfg)
			ctx, cancel := context.WithTimeout(context.Background(), 65*time.Second)
			defer cancel()
			d, err := client.Parse(ctx, tc.text, time.Date(2026, 10, 3, 4, 0, 37, 0, time.UTC))
			if err != nil {
				t.Fatal(err)
			}
			if d.Rule.Kind != tc.kind || d.Rule.Mode != tc.mode {
				t.Fatalf("completion semantics changed: %+v", d.Rule)
			}
			if tc.kind == "minute_interval" && (d.Rule.RepeatMinutes != 5 || d.Rule.EndAt != "2026-10-03T17:00:00+08:00") {
				t.Fatalf("explicit cadence/window lost: %+v", d.Rule)
			}
			t.Log(d.Summary)
		})
	}
}

func (trace intentTrace) RoundTrip(req *http.Request) (*http.Response, error) {
	body, _ := io.ReadAll(req.Body)
	_ = req.Body.Close()
	req.Body = io.NopCloser(bytes.NewReader(body))
	var payload struct {
		Model    string `json:"model"`
		Thinking struct {
			Type string `json:"type"`
		} `json:"thinking"`
	}
	_ = json.Unmarshal(body, &payload)
	if payload.Model != "deepseek-flash" || payload.Thinking.Type != "disabled" {
		trace.t.Errorf("wrong model settings: %s/%s", payload.Model, payload.Thinking.Type)
	}
	response, err := trace.base.RoundTrip(req)
	if err != nil {
		return nil, err
	}
	raw, _ := io.ReadAll(io.LimitReader(response.Body, 256*1024))
	_ = response.Body.Close()
	response.Body = io.NopCloser(bytes.NewReader(raw))
	var envelope struct {
		Model   string `json:"model"`
		Choices []struct {
			Message struct {
				Content string `json:"content"`
			} `json:"message"`
		} `json:"choices"`
	}
	if json.Unmarshal(raw, &envelope) == nil && len(envelope.Choices) > 0 {
		trace.t.Logf("provider=%s; thinking=%s; synthetic draft=%s", envelope.Model, payload.Thinking.Type, envelope.Choices[0].Message.Content)
	}
	return response, nil
}
