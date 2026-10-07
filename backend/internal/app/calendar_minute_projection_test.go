package app

import (
	"context"
	"testing"
	"time"
)

func TestMinuteCalendarDailyAggregationKeepsOtherTasks(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	*now = time.Date(2026, 10, 1, 0, 0, 0, 0, now.Location())
	dailyID := calendarTask(t, a, token, dailyCalendarRule())
	*now = now.Add(time.Second)
	createMinuteTask(t, a, token, now.Add(15*time.Minute), 1)
	code, out := request(t, a, "GET", "/api/v1/calendar?month=2026-10&timezone=Asia%2FShanghai", token, nil)
	if code != 200 || out["truncated"] != false || len(out["entries"].([]any)) != 32 {
		t.Fatalf("dense calendar: %d truncated=%v", code, out["truncated"])
	}
	want := stamp(time.Date(2026, 10, 1, 12, 0, 0, 0, now.Location()))
	found := false
	previous := ""
	for _, raw := range out["entries"].([]any) {
		entry := raw.(map[string]any)
		at := entry["at"].(string)
		if at < previous {
			t.Fatalf("calendar is not chronological: %s before %s", at, previous)
		}
		previous = at
		if entry["task_id"] == dailyID && at == want {
			found = true
		}
	}
	if !found {
		t.Fatal("later minute plans displaced the older task's earlier noon plan")
	}
}

func TestMinuteCalendarPastCapPreservesUnsentHistoryAndOtherTasks(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	*now = time.Date(2026, 10, 1, 8, 0, 37, 0, now.Location())
	dailyID := calendarTask(t, a, token, dailyCalendarRule())
	*now = now.Add(time.Second)
	first := now.Add(15 * time.Minute)
	body := minuteTaskBody(first, 1)
	body["rule"].(map[string]any)["max_reminders"] = 2
	code, out := request(t, a, "POST", "/api/v1/tasks", token, body)
	if code != 201 {
		t.Fatalf("create capped minute task: %d %+v", code, out)
	}
	minuteID := out["id"].(string)
	for _, at := range []time.Time{first, first.Add(3 * time.Minute)} {
		*now = at
		if err := a.tick(context.Background()); err != nil {
			t.Fatal(err)
		}
	}
	*now = time.Date(2026, 11, 1, 8, 0, 0, 0, now.Location())
	entries := calendarEntries(t, a, token, "2026-10", "Asia/Shanghai")
	daily, actual, reminders := 0, 0, 0
	for _, entry := range entries {
		if entry["task_id"] == dailyID && entry["type"] == "planned" {
			daily++
		}
		if entry["task_id"] == minuteID {
			if entry["type"] == "notification" {
				actual++
				reminders += int(entry["reminder_count"].(float64))
				if entry["last_reminder_at"] != stamp(first.Add(3*time.Minute)) {
					t.Fatalf("lost latest actual reminder: %+v", entry)
				}
			}
		}
	}
	if daily != 31 || actual != 1 || reminders != 2 || len(entries) != 32 {
		t.Fatalf("past cap lost or invented daily history: daily=%d actual=%d reminders=%d", daily, actual, reminders)
	}
}
