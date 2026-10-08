package ai

import (
	"context"
	"errors"
	"os"
	"strings"
	"testing"
	"time"

	"niannian/internal/config"
)

// Opt-in real-model checks for suggestions and the clarification contract.
func TestLiveColloquialPromptQuality(t *testing.T) {
	if os.Getenv("NIANNIAN_LIVE_PROMPT") != "1" {
		t.Skip("opt-in real model prompt checks")
	}
	cfg, err := config.Load(config.EmbeddedDefaults())
	if err != nil {
		t.Fatal(err)
	}
	client := New(cfg)
	for _, tc := range []struct{ name, input, kind string }{{"hard_deadline", "材料五点就截止了，别让我忘了交。", "minute_interval"}, {"single_return_obligation", "后面每天中午记得提醒我还相机。", "ongoing"}} {
		t.Run(tc.name, func(t *testing.T) {
			now, _ := time.Parse(time.RFC3339, "2026-10-08T14:37:20+08:00")
			if tc.kind == "ongoing" {
				now, _ = time.Parse(time.RFC3339, "2026-10-08T11:20:00+08:00")
			}
			d, err := client.Parse(context.Background(), tc.input, now)
			if err != nil || d.Rule.Kind != tc.kind {
				t.Fatalf("kind=%s draft=%+v err=%v", tc.kind, d, err)
			}
			if tc.kind == "minute_interval" && d.Rule.EndAt != "2026-10-08T17:00:00+08:00" {
				t.Fatalf("hard deadline not enforced: %+v", d)
			}
		})
	}
	t.Run("afternoon_window", func(t *testing.T) {
		now, _ := time.Parse(time.RFC3339, "2026-10-08T13:10:00+08:00")
		d, err := client.Parse(context.Background(), "上班的下午隔会儿叫我起来走走。", now)
		if err != nil {
			t.Fatal(err)
		}
		end, err := time.Parse(time.RFC3339, d.Rule.EndAt)
		if err != nil || end.Day() != 8 || end.Hour() > 18 || d.Rule.Kind != "minute_interval" || !strings.Contains(strings.Join(d.Warnings, " "), "不会自动生成下一轮") {
			t.Fatalf("working afternoon lost its finite window or recurrence boundary: %+v", d)
		}
		t.Logf("%s | %s | %v", d.Summary, d.Description, d.Warnings)
	})
	t.Run("range_assumption", func(t *testing.T) {
		now, _ := time.Parse(time.RFC3339, "2026-12-30T22:00:00+08:00")
		d, err := client.Parse(context.Background(), "跨年那几天，每天提醒我量一下阳台温度。", now)
		if err != nil {
			t.Fatal(err)
		}
		if d.Rule.Mode != "every_day" || d.Rule.Kind != "date_range" || !strings.Contains(strings.Join(d.Warnings, " "), "日期范围先按") {
			t.Fatalf("date range needs a visible provisional explanation: %+v", d)
		}
		t.Logf("%s | %s | %v", d.Summary, d.Description, d.Warnings)
	})
	t.Run("pickup_obligation", func(t *testing.T) {
		now, _ := time.Parse(time.RFC3339, "2026-10-08T18:12:09+08:00")
		d, err := client.Parse(context.Background(), "明早叫我拿快递，算了后天下午吧。", now)
		if err != nil {
			t.Fatal(err)
		}
		if d.Rule.Mode != "until_done" {
			t.Fatalf("pickup obligation became notification-only: %+v", d)
		}
		t.Logf("%s | %s | %v", d.Summary, d.Description, d.Warnings)
	})
	for _, tc := range []struct{ name, input string }{
		{"missing_interview_anchor", "纽约那个面试，提前半小时叫我。"},
		{"holdout_missing_flight_anchor", "飞机起飞前俩小时叫我出发。"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			now, _ := time.Parse(time.RFC3339, "2026-10-08T20:00:00+08:00")
			d, err := client.Parse(context.Background(), tc.input, now)
			var question *ClarificationError
			if !errors.As(err, &question) || d.Title != "" {
				t.Fatalf("missing anchor became draft=%+v err=%v", d, err)
			}
			t.Logf("clarification: %+v", question.Clarification)
		})
	}
}
