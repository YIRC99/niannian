package app

import (
	"context"
	"testing"
	"time"
)

func minuteTaskBody(first time.Time, minutes int) map[string]any {
	return map[string]any{"title": "收衣服", "rule": map[string]any{
		"kind": "minute_interval", "mode": "until_done", "timezone": "Asia/Shanghai",
		"first_at": first.Format(time.RFC3339), "repeat_minutes": minutes,
		"times": []string{}, "max_reminders": 0,
	}}
}

func createMinuteTask(t *testing.T, a *App, token string, first time.Time, minutes int) string {
	t.Helper()
	code, out := request(t, a, "POST", "/api/v1/tasks", token, minuteTaskBody(first, minutes))
	if code != 201 {
		t.Fatalf("create minute task: %d %+v", code, out)
	}
	if out["next_reminder"] != stamp(first) {
		t.Fatalf("first reminder shifted: %+v", out)
	}
	return out["id"].(string)
}

func tickMinutes(t *testing.T, a *App, m *fakeMailer, want int) {
	t.Helper()
	if err := a.tick(context.Background()); err != nil {
		t.Fatal(err)
	}
	if len(m.messages) != want {
		t.Fatalf("sent %d reminders, want %d", len(m.messages), want)
	}
}

func TestMinuteReminderDelayRepeatCompleteAndRestart(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	*now = time.Date(2026, 10, 31, 23, 43, 27, 0, now.Location())
	first := now.Add(15 * time.Minute)
	id := createMinuteTask(t, a, token, first, 5)
	m.messages = nil
	*now = first.Add(-time.Second)
	tickMinutes(t, a, m, 0)
	*now = first
	tickMinutes(t, a, m, 1)
	tickMinutes(t, a, m, 1)
	*now = first.Add(5 * time.Minute)
	tickMinutes(t, a, m, 2)
	*now = now.Add(24 * time.Hour)
	tickMinutes(t, a, m, 3) // A long outage catches up only the latest interval.
	if code, out := request(t, a, "POST", "/api/v1/tasks/"+id+"/complete", token, map[string]any{}); code != 200 {
		t.Fatalf("complete: %d %+v", code, out)
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
	*now = now.AddDate(0, 1, 0)
	tickMinutes(t, again, m, 3)
	code, out := request(t, again, "GET", "/api/v1/tasks/"+id, token, nil)
	if code != 200 || out["status"] != "done" || out["next_reminder"] != "" {
		t.Fatalf("completion must survive days/months/restart: %d %+v", code, out)
	}
}

func TestMinuteReminderCatchupAcrossMidnightAndRestartDedup(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	*now = time.Date(2026, 10, 31, 23, 43, 0, 0, now.Location())
	first := now.Add(15 * time.Minute)
	createMinuteTask(t, a, token, first, 5)
	m.messages = nil
	*now = first.Add(3 * time.Minute) // 00:01, most recent interval was 23:58.
	tickMinutes(t, a, m, 1)
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
	tickMinutes(t, again, m, 1)
	*now = first.Add(5 * time.Minute)
	tickMinutes(t, again, m, 2)
}

func TestMinuteReminderEarlyCompletionAndEditsNeverReopen(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	*now = time.Date(2026, 10, 3, 23, 55, 0, 0, now.Location())
	first := now.Add(15 * time.Minute)
	id := createMinuteTask(t, a, token, first, 5)
	m.messages = nil
	if code, out := request(t, a, "POST", "/api/v1/tasks/"+id+"/complete", token, map[string]any{}); code != 200 {
		t.Fatalf("finish before tomorrow's first reminder: %d %+v", code, out)
	}
	if code, out := request(t, a, "PUT", "/api/v1/tasks/"+id, token, minuteTaskBody(first.Add(48*time.Hour), 10)); code != 200 || out["status"] != "done" {
		t.Fatalf("edit reopened completed task: %d %+v", code, out)
	}
	*now = first.Add(72 * time.Hour)
	tickMinutes(t, a, m, 0)
	if entries := calendarEntries(t, a, token, "2026-10", "Asia/Shanghai"); len(entries) != 1 || entries[0]["type"] != "completion" {
		t.Fatalf("completed reminder still projected: %+v", entries)
	}
}

func TestMinuteReminderPauseSnoozeAndCap(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	first := now.Add(15 * time.Minute)
	body := minuteTaskBody(first, 5)
	body["rule"].(map[string]any)["max_reminders"] = 2
	code, out := request(t, a, "POST", "/api/v1/tasks", token, body)
	if code != 201 {
		t.Fatalf("create: %d %+v", code, out)
	}
	id := out["id"].(string)
	m.messages = nil
	if code, out := request(t, a, "PUT", "/api/v1/tasks/"+id+"/paused", token, map[string]bool{"paused": true}); code != 200 {
		t.Fatalf("pause: %d %+v", code, out)
	}
	*now = first
	tickMinutes(t, a, m, 0)
	request(t, a, "PUT", "/api/v1/tasks/"+id+"/paused", token, map[string]bool{"paused": false})
	if code, out := request(t, a, "POST", "/api/v1/tasks/"+id+"/snooze", token, map[string]int{"minutes": 7}); code != 200 {
		t.Fatalf("snooze: %d %+v", code, out)
	}
	*now = first.Add(5 * time.Minute)
	tickMinutes(t, a, m, 0)
	*now = first.Add(7 * time.Minute)
	tickMinutes(t, a, m, 1)
	*now = first.Add(10 * time.Minute)
	tickMinutes(t, a, m, 2)
	*now = first.Add(24 * time.Hour)
	tickMinutes(t, a, m, 2)
}

func TestMinuteCalendarCadenceAndPermanentCompletion(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	*now = time.Date(2026, 10, 31, 23, 35, 27, 0, now.Location())
	first := now.Add(15 * time.Minute)
	id := createMinuteTask(t, a, token, first, 5)
	entries := calendarEntries(t, a, token, "2026-10", "Asia/Shanghai")
	if len(entries) != 2 || entries[0]["at"] != stamp(first) || entries[1]["at"] != stamp(first.Add(5*time.Minute)) {
		t.Fatalf("wrong minute calendar: %+v", entries)
	}
	m.messages = nil
	*now = first
	tickMinutes(t, a, m, 1)
	*now = first.Add(time.Minute)
	request(t, a, "POST", "/api/v1/tasks/"+id+"/complete", token, map[string]any{})
	entries = calendarEntries(t, a, token, "2026-10", "Asia/Shanghai")
	if len(entries) != 2 {
		t.Fatalf("history must keep actual reminder and completion: %+v", entries)
	}
	for _, entry := range entries {
		if entry["state"] != "completed" {
			t.Fatalf("wrong completed state: %+v", entry)
		}
	}
	if entries := calendarEntries(t, a, token, "2026-11", "Asia/Shanghai"); len(entries) != 0 {
		t.Fatalf("future plans after permanent completion: %d", len(entries))
	}
}

func TestMinuteReminderRejectsStaleDraftOnCreation(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	code, out := request(t, a, "POST", "/api/v1/tasks", token, minuteTaskBody(now.Add(-time.Minute), 5))
	if code != 400 {
		t.Fatalf("stale first reminder silently replaced: %d %+v", code, out)
	}
}

func TestMinuteCalendarBoundsDenseProjection(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	*now = time.Date(2026, 10, 1, 0, 0, 0, 0, now.Location())
	createMinuteTask(t, a, token, now.Add(15*time.Minute), 1)
	code, out := request(t, a, "GET", "/api/v1/calendar?month=2026-10&timezone=Asia%2FShanghai", token, nil)
	if code != 200 || out["truncated"] != true {
		t.Fatalf("dense calendar must report truncation: %d truncated=%v", code, out["truncated"])
	}
	if entries := out["entries"].([]any); len(entries) == 0 || len(entries) > calendarEntryLimit {
		t.Fatalf("unbounded/empty calendar: %d", len(entries))
	}
}
