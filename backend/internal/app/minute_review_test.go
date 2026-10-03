package app

import (
	"context"
	"testing"
	"time"
)

func TestMinuteReviewCappedCalendarDoesNotHideOlderDailyTask(t *testing.T) {
	for _, alreadySent := range []bool{false, true} {
		name := "cap_available"
		if alreadySent {
			name = "cap_exhausted"
		}
		t.Run(name, func(t *testing.T) {
			a, m, now := setup(t)
			token := login(t, a, m, "one@example.com")
			code, daily := request(t, a, "POST", "/api/v1/tasks", token, map[string]any{
				"title": "每天喝水", "rule": map[string]any{
					"kind": "daily", "mode": "every_day", "timezone": "Asia/Shanghai", "times": []string{"09:00"},
				},
			})
			if code != 201 {
				t.Fatalf("create daily: %d %+v", code, daily)
			}
			*now = now.Add(time.Second) // Newer minute task sorts before the daily task.
			first := now.Add(15 * time.Minute)
			body := minuteTaskBody(first, 1)
			body["rule"].(map[string]any)["max_reminders"] = 1
			if code, out := request(t, a, "POST", "/api/v1/tasks", token, body); code != 201 {
				t.Fatalf("create capped minute task: %d %+v", code, out)
			}
			if alreadySent {
				*now = first
				if err := a.tick(context.Background()); err != nil {
					t.Fatal(err)
				}
			}
			code, out := request(t, a, "GET", "/api/v1/calendar?month=2026-11&timezone=Asia%2FShanghai", token, nil)
			if code != 200 {
				t.Fatalf("calendar: %d %+v", code, out)
			}
			count := 0
			for _, raw := range out["entries"].([]any) {
				entry := raw.(map[string]any)
				if entry["task_id"] == daily["id"] && entry["type"] == "planned" {
					count++
				}
			}
			if count != 30 || out["truncated"] != false {
				t.Fatalf("capped minute task hid daily plans: daily=%d want=30, total=%d, truncated=%v", count, len(out["entries"].([]any)), out["truncated"])
			}
		})
	}
}

func TestMinuteReviewExpiredTaskDoesNotCatchUpOrRetryAfterRestart(t *testing.T) {
	for _, failedBeforeExpiry := range []bool{false, true} {
		name := "outage"
		if failedBeforeExpiry {
			name = "failed_retry"
		}
		t.Run(name, func(t *testing.T) {
			a, m, now := setup(t)
			token := login(t, a, m, "one@example.com")
			*now = time.Date(2026, 10, 3, 23, 44, 27, 0, now.Location())
			first := now.Add(15 * time.Minute)
			body := minuteTaskBody(first, 5)
			body["rule"].(map[string]any)["end_date"] = "2026-10-03"
			code, out := request(t, a, "POST", "/api/v1/tasks", token, body)
			if code != 201 {
				t.Fatalf("create expiring task: %d %+v", code, out)
			}
			id := out["id"].(string)
			m.messages = nil
			if failedBeforeExpiry {
				*now = first
				m.fail = true
				if err := a.tick(context.Background()); err != nil {
					t.Fatal(err)
				}
				var attempts int
				if err := a.db.QueryRow(`SELECT attempts FROM notifications WHERE task_id=?`, id).Scan(&attempts); err != nil || attempts != 1 {
					t.Fatalf("failed-send setup: attempts=%d err=%v", attempts, err)
				}
			}
			cfg := a.cfg
			if err := a.Close(); err != nil {
				t.Fatal(err)
			}
			again, err := New(cfg)
			if err != nil {
				t.Fatal(err)
			}
			defer again.Close()
			again.now = func() time.Time { return *now }
			again.mailer = m
			m.fail = false
			*now = time.Date(2026, 10, 5, 0, 1, 0, 0, now.Location())
			if err := again.tick(context.Background()); err != nil {
				t.Fatal(err)
			}
			if len(m.messages) != 0 {
				t.Fatalf("expired minute task sent %d reminders after restart", len(m.messages))
			}
		})
	}
}
