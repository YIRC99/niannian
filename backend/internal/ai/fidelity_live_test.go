package ai

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
	"time"

	"niannian/internal/config"
	"niannian/internal/schedule"
)

// Live semantic regression gates use the unchanged benchmark inputs and gold
// rules. They deliberately exercise the real model, not a mocked desired reply.
func TestLiveReminderFidelity(t *testing.T) {
	if os.Getenv("NIANNIAN_LIVE_AI") != "1" {
		t.Skip("opt-in real model semantic regression")
	}
	cfg, err := config.Load(config.EmbeddedDefaults())
	if err != nil {
		t.Fatal(err)
	}
	b, err := os.ReadFile(filepath.Join("..", "..", "..", "docs", "benchmarks", "reminder-creation-v1.json"))
	if err != nil {
		t.Fatal(err)
	}
	var suite struct {
		Cases []struct {
			ID       string         `json:"id"`
			Now      string         `json:"now"`
			Input    string         `json:"input"`
			Expected map[string]any `json:"expected_rule_subset"`
		} `json:"cases"`
	}
	if err := json.Unmarshal(b, &suite); err != nil {
		t.Fatal(err)
	}
	for _, tc := range suite.Cases {
		t.Run(tc.ID, func(t *testing.T) {
			now, err := time.Parse(time.RFC3339, tc.Now)
			if err != nil {
				t.Fatal(err)
			}
			d, err := New(cfg).Parse(context.Background(), tc.Input, now)
			if err != nil {
				t.Fatal(err)
			}
			t.Logf("%s; notes=%s; feedback=%v", d.Summary, d.Description, d.Warnings)
			if tc.ID == "RC-10" {
				assertFidelityRule(t, d.Rule, map[string]any{"kind": "date_range", "mode": "until_done", "start_date": "2026-10-25", "end_date": "2026-11-05", "times": []any{"09:00"}, "max_reminders": float64(4)})
			} else {
				assertFidelityRule(t, d.Rule, tc.Expected)
			}
			feedback := strings.Join(d.Warnings, " ")
			if tc.ID == "RC-07" {
				first, e := time.Parse(time.RFC3339, d.Rule.FirstAt)
				if e != nil || first.Before(now.Add(5*time.Minute)) || first.After(now.Add(15*time.Minute)) || first.Second() != now.Second() {
					t.Error("vague delay lost its range or seconds")
				}
				if !strings.Contains(feedback, "调整") && !strings.Contains(feedback, "修改") {
					t.Error("default cadence must explicitly remain adjustable")
				}
			}
			if tc.ID == "RC-09" || tc.ID == "RC-10" {
				// Description is a task memo, not a second, unexecutable schedule.
				for _, phrase := range []string{"每周", "每月", "重新开始", "再按天补", "手动跳过"} {
					if strings.Contains(d.Description, phrase) {
						t.Errorf("description promises unsupported scheduling: %q", phrase)
					}
				}
				for _, phrase := range []string{"我再按天补", "草稿里手动跳过", "窗口已过，只提醒余下"} {
					if strings.Contains(feedback, phrase) {
						t.Errorf("unsupported or contradictory feedback: %q", phrase)
					}
				}
				if len(d.Warnings) == 0 {
					t.Error("approximation must explain its limits")
				}
			}
		})
	}
}

func assertFidelityRule(t *testing.T, rule schedule.Rule, expected map[string]any) {
	t.Helper()
	b, _ := json.Marshal(rule)
	var actual map[string]any
	if err := json.Unmarshal(b, &actual); err != nil {
		t.Fatal(err)
	}
	for field, want := range expected {
		got, ok := actual[field]
		if !ok && (want == "" || want == float64(0)) {
			continue
		}
		if !reflect.DeepEqual(got, want) {
			t.Errorf("%s: got %v, want %v", field, got, want)
		}
	}
}

// Variations are not in the ten-question report or production examples. They
// check the same decisions with different dates, cadence and active periods.
func TestLiveReminderFidelityVariations(t *testing.T) {
	if os.Getenv("NIANNIAN_LIVE_AI") != "1" {
		t.Skip("opt-in real model semantic variations")
	}
	cfg, err := config.Load(config.EmbeddedDefaults())
	if err != nil {
		t.Fatal(err)
	}
	cases := []struct {
		name, now, input string
		want             map[string]any
	}{
		{"active_window", "2026-11-12T10:23:41+08:00", "今天上午十点到十一点半，每13分钟催我打扫桌面，做完就停。我已经错过前面一些时间，只继续剩下的，别重算间隔。", map[string]any{"kind": "minute_interval", "first_at": "2026-11-12T10:00:00+08:00", "end_at": "2026-11-12T11:30:00+08:00", "repeat_minutes": float64(13)}},
		{"active_cross_month", "2026-10-02T10:10:11+08:00", "每月26日到次月4日，每天19点催我整理这个账期的收据，整个账期做完一回就停，最多5次。当前账期还没做完，不要切成两个任务。", map[string]any{"kind": "date_range", "mode": "until_done", "start_date": "2026-09-26", "end_date": "2026-10-04", "times": []any{"19:00"}, "max_reminders": float64(5)}},
		{"cross_year", "2026-12-24T11:00:00+08:00", "每月27日到下月6日，每天18点提醒核对账单，这一轮做完一次就停。", map[string]any{"kind": "date_range", "mode": "until_done", "start_date": "2026-12-27", "end_date": "2027-01-06", "times": []any{"18:00"}}},
		{"explicit_london_zone", "2026-10-08T20:00:00+08:00", "我人在上海，会议是2026年10月12日伦敦当地14点。提醒按Europe/London时区保存，提前20分钟提醒检查话筒，只通知一次。", map[string]any{"kind": "once", "mode": "notify_once", "timezone": "Europe/London", "start_date": "2026-10-12", "times": []any{"13:40"}}},
		{"cross_month_daily_completion", "2026-10-02T10:10:11+08:00", "每月26日到次月4日，每天19点记录阳台温度，每天必须分别完成，不能哪天做过一次就把整个账期结束。每天最多提醒两次。", map[string]any{"kind": "date_range", "mode": "every_day", "start_date": "2026-09-26", "end_date": "2026-10-04", "times": []any{"19:00"}, "max_reminders": float64(2)}},
		{"cross_month_notify_once", "2026-12-24T11:00:00+08:00", "每个月27日到下个月6日是对账期，在这个账期里早上9点只通知我一次开始对账，不需要点完成，也不要持续催。", map[string]any{"kind": "date_range", "mode": "notify_once", "start_date": "2026-12-27", "end_date": "2027-01-06", "times": []any{"09:00"}}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			now, err := time.Parse(time.RFC3339, tc.now)
			if err != nil {
				t.Fatal(err)
			}
			d, err := New(cfg).Parse(context.Background(), tc.input, now)
			if err != nil {
				t.Fatal(err)
			}
			assertFidelityRule(t, d.Rule, tc.want)
			t.Logf("%s; feedback=%v", d.Summary, d.Warnings)
		})
	}
}
