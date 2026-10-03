package app

import (
	"context"
	"strings"
	"testing"
	"time"
)

func minuteWindowBody(first, end time.Time, repeat int) map[string]any {
	body := minuteTaskBody(first, repeat)
	body["rule"].(map[string]any)["end_at"] = end.Format(time.RFC3339Nano)
	return body
}

func createMinuteWindow(t *testing.T, a *App, token string, first, end time.Time, repeat int) string {
	t.Helper()
	code, out := request(t, a, "POST", "/api/v1/tasks", token, minuteWindowBody(first, end, repeat))
	if code != 201 {
		t.Fatalf("create precise window: %d %+v", code, out)
	}
	return out["id"].(string)
}

func TestMinuteEndAtSendsInclusiveDeadlineAndRejectsSnoozePastIt(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	first := now.Add(15 * time.Minute)
	end := first.Add(10 * time.Minute)
	id := createMinuteWindow(t, a, token, first, end, 5)
	m.messages = nil
	*now = first
	tickMinutes(t, a, m, 1)
	if code, out := request(t, a, "POST", "/api/v1/tasks/"+id+"/snooze", token, map[string]int{"minutes": 11}); code != 409 {
		t.Fatalf("snooze beyond precise deadline accepted: %d %+v", code, out)
	}
	if code, out := request(t, a, "POST", "/api/v1/tasks/"+id+"/snooze", token, map[string]int{"minutes": 10}); code != 200 {
		t.Fatalf("snooze to inclusive deadline rejected: %d %+v", code, out)
	}
	*now = end.Add(-time.Nanosecond)
	tickMinutes(t, a, m, 1)
	*now = end
	tickMinutes(t, a, m, 2)
	*now = end.Add(time.Nanosecond)
	tickMinutes(t, a, m, 2)
	if code, out := request(t, a, "GET", "/api/v1/tasks/"+id, token, nil); code != 200 || out["status"] != "expired" || out["next_reminder"] != "" {
		t.Fatalf("precise window did not end: %d %+v", code, out)
	}
}

func TestMinuteEndAtEarlyCompletionAndEditsNeverReopen(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	first := now.Add(15 * time.Minute)
	end := first.Add(3 * time.Hour)
	id := createMinuteWindow(t, a, token, first, end, 30)
	m.messages = nil
	if code, out := request(t, a, "POST", "/api/v1/tasks/"+id+"/complete", token, map[string]any{}); code != 200 {
		t.Fatalf("early completion: %d %+v", code, out)
	}
	first = first.AddDate(0, 0, 1)
	end = end.AddDate(0, 0, 1)
	if code, out := request(t, a, "PUT", "/api/v1/tasks/"+id, token, minuteWindowBody(first, end, 10)); code != 200 || out["status"] != "done" {
		t.Fatalf("edited window reopened completed task: %d %+v", code, out)
	}
	*now = first
	tickMinutes(t, a, m, 0)
	*now = end
	tickMinutes(t, a, m, 0)
	if code, out := request(t, a, "GET", "/api/v1/tasks/"+id, token, nil); code != 200 || out["status"] != "done" || out["next_reminder"] != "" {
		t.Fatalf("completion did not stop window: %d %+v", code, out)
	}
}

func TestMinuteEndAtNoCatchupOrRetryAfterDeadline(t *testing.T) {
	for _, queued := range []bool{false, true} {
		name := "outage"
		if queued {
			name = "queued_retry"
		}
		t.Run(name, func(t *testing.T) {
			a, m, now := setup(t)
			token := login(t, a, m, "one@example.com")
			first := now.Add(15 * time.Minute)
			end := first.Add(30 * time.Second)
			id := createMinuteWindow(t, a, token, first, end, 5)
			m.messages = nil
			if queued {
				*now = first
				m.fail = true
				if err := a.tick(context.Background()); err != nil {
					t.Fatal(err)
				}
				var attempts int
				if err := a.db.QueryRow(`SELECT attempts FROM notifications WHERE task_id=?`, id).Scan(&attempts); err != nil || attempts != 1 {
					t.Fatalf("retry setup: attempts=%d err=%v", attempts, err)
				}
			}
			*now = end.Add(time.Minute)
			m.fail = false
			tickMinutes(t, a, m, 0)
			if queued {
				var attempts int
				var reason string
				if err := a.db.QueryRow(`SELECT attempts,error FROM notifications WHERE task_id=?`, id).Scan(&attempts, &reason); err != nil || attempts != maxSendAttempts || !strings.Contains(reason, "时段已过") {
					t.Fatalf("expired queue not cancelled: attempts=%d reason=%s err=%v", attempts, reason, err)
				}
			}
		})
	}
}

func TestMinuteEndAtCalendarExcludesPlansAfterPreciseDeadline(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	*now = time.Date(2026, 10, 31, 23, 43, 37, 0, now.Location())
	first := now.Add(15 * time.Minute)
	end := first.Add(10 * time.Minute)
	createMinuteWindow(t, a, token, first, end, 5)
	entries := calendarEntries(t, a, token, "2026-11", "Asia/Shanghai")
	if len(entries) != 2 || entries[0]["at"] != stamp(first.Add(5*time.Minute)) || entries[1]["at"] != stamp(end) {
		t.Fatalf("calendar escaped precise window: %+v", entries)
	}
}

func TestMinuteEndAtCreateActiveWindowKeepsOnlyRemainingReminders(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	*now = time.Date(2026, 10, 3, 16, 0, 37, 123000000, now.Location())
	first := time.Date(2026, 10, 3, 14, 0, 0, 0, now.Location())
	end := time.Date(2026, 10, 3, 17, 0, 0, 0, now.Location())
	id := createMinuteWindow(t, a, token, first, end, 15)
	next := time.Date(2026, 10, 3, 16, 15, 0, 0, now.Location())
	if code, out := request(t, a, "GET", "/api/v1/tasks/"+id, token, nil); code != 200 || out["next_reminder"] != stamp(next) {
		t.Fatalf("active window did not retain its cadence: %d %+v", code, out)
	}
	m.messages = nil
	tickMinutes(t, a, m, 0) // Creation must not catch up the elapsed part of the window.
	*now = next
	tickMinutes(t, a, m, 1)
	if code, out := request(t, a, "POST", "/api/v1/tasks/"+id+"/complete", token, map[string]any{}); code != 200 {
		t.Fatalf("complete active window: %d %+v", code, out)
	}
	*now = end
	tickMinutes(t, a, m, 1)
}

func TestMinuteEndAtCreationStillRequiresFutureCandidate(t *testing.T) {
	for _, tc := range []struct {
		name       string
		now        string
		first      string
		end        string
		repeat     int
		wantStatus int
	}{
		{"delayed confirmation before deadline", "2026-10-03T16:59:59.999999999+08:00", "2026-10-03T14:00:00+08:00", "2026-10-03T17:00:00+08:00", 30, 201},
		{"confirmation at deadline", "2026-10-03T17:00:00+08:00", "2026-10-03T14:00:00+08:00", "2026-10-03T17:00:00+08:00", 30, 400},
		{"confirmation beyond deadline", "2026-10-03T17:00:00.000000001+08:00", "2026-10-03T14:00:00+08:00", "2026-10-03T17:00:00+08:00", 30, 400},
		{"active but no candidate left", "2026-10-03T16:45:00.123+08:00", "2026-10-03T14:00:00+08:00", "2026-10-03T16:59:59+08:00", 30, 400},
		{"one instant confirmed before it", "2026-10-03T16:59:59.999999999+08:00", "2026-10-03T17:00:00+08:00", "2026-10-03T17:00:00+08:00", 5, 201},
		{"one instant confirmed at it", "2026-10-03T17:00:00+08:00", "2026-10-03T17:00:00+08:00", "2026-10-03T17:00:00+08:00", 5, 400},
		{"no precise deadline remains strict", "2026-10-03T16:00:37.123+08:00", "2026-10-03T14:00:00+08:00", "", 30, 400},
	} {
		t.Run(tc.name, func(t *testing.T) {
			a, m, now := setup(t)
			token := login(t, a, m, "one@example.com")
			var err error
			*now, err = time.Parse(time.RFC3339Nano, tc.now)
			if err != nil {
				t.Fatal(err)
			}
			first, err := time.Parse(time.RFC3339Nano, tc.first)
			if err != nil {
				t.Fatal(err)
			}
			body := minuteTaskBody(first, tc.repeat)
			body["rule"].(map[string]any)["end_at"] = tc.end
			code, out := request(t, a, "POST", "/api/v1/tasks", token, body)
			if code != tc.wantStatus {
				t.Fatalf("create status=%d want=%d: %+v", code, tc.wantStatus, out)
			}
			if code == 400 && tc.end != "" && !strings.Contains(out["error"].(string), "没有未来提醒时间") {
				t.Fatalf("precise window should explain its missing future candidate: %+v", out)
			}
			var count int
			if err := a.db.QueryRow(`SELECT COUNT(*) FROM tasks`).Scan(&count); err != nil || count != map[bool]int{true: 1, false: 0}[code == 201] {
				t.Fatalf("rejected rule persisted: count=%d err=%v", count, err)
			}
		})
	}
}
