package app

import (
	"testing"
	"time"
)

func TestCalendarSingleTaskDoesNotFillFutureDates(t *testing.T) {
	for _, kind := range []string{"minute_interval", "ongoing"} {
		for _, bounded := range []bool{false, true} {
			t.Run(kind+map[bool]string{false: "/unbounded", true: "/bounded"}[bounded], func(t *testing.T) {
				a, m, now := setup(t)
				token := login(t, a, m, "one@example.com")
				first := now.Add(15 * time.Minute)
				var id string
				if kind == "minute_interval" {
					body := minuteTaskBody(first, 1)
					if bounded {
						body["rule"].(map[string]any)["end_at"] = stamp(first.AddDate(0, 0, 4))
					}
					code, created := request(t, a, "POST", "/api/v1/tasks", token, body)
					if code != 201 {
						t.Fatalf("create: %d %+v", code, created)
					}
					id = created["id"].(string)
				} else {
					rule := dailyCalendarRule()
					rule.Kind, rule.Mode, rule.StartDate = "ongoing", "until_done", "2026-10-03"
					if bounded {
						rule.EndDate = "2026-10-07"
					}
					id = calendarTask(t, a, token, rule)
				}
				entries := calendarEntries(t, a, token, "2026-10", "Asia/Shanghai")
				if len(entries) != 1 || entries[0]["task_id"] != id || entries[0]["local_date"] != "2026-10-03" {
					t.Fatalf("one task became daily occurrences: count=%d", len(entries))
				}
				if entries = calendarEntries(t, a, token, "2026-11", "Asia/Shanghai"); len(entries) != 0 {
					t.Fatalf("future month invented a task: count=%d", len(entries))
				}
				*now = now.AddDate(0, 0, 1)
				entries = calendarEntries(t, a, token, "2026-10", "Asia/Shanghai")
				if len(entries) != 1 || entries[0]["local_date"] != "2026-10-04" || entries[0]["state"] != "pending" {
					t.Fatalf("unfinished task did not move only to today: %+v", entries)
				}
			})
		}
	}
}

func TestCalendarSingleTaskNextRealPlanAndSnoozeChooseOneMonth(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	*now = time.Date(2026, 10, 31, 20, 0, 0, 0, now.Location())
	id := createMinuteTask(t, a, token, now.Add(15*time.Minute), 1)
	calendarDailyPlan(t, a, id, "next-real", "2026-11-01T09:00:00+08:00", "pending", "notification")
	calendarDailyPlan(t, a, id, "duplicate-later", "2026-11-02T09:00:00+08:00", "pending", "alarm")
	if _, err := a.db.Exec(`UPDATE reminder_plans SET expires_at='2026-11-03T00:00:00+08:00' WHERE task_id=?`, id); err != nil {
		t.Fatal(err)
	}
	if _, err := a.db.Exec(`UPDATE tasks SET snooze_until='2026-11-02T08:00:00+08:00',snooze_cycle='minute_interval' WHERE id=?`, id); err != nil {
		t.Fatal(err)
	}
	if entries := calendarEntries(t, a, token, "2026-10", "Asia/Shanghai"); len(entries) != 0 {
		t.Fatalf("rule placeholder duplicated the real November plan: %+v", entries)
	}
	entries := calendarEntries(t, a, token, "2026-11", "Asia/Shanghai")
	if len(entries) != 1 || entries[0]["id"] != "next-real" || entries[0]["local_date"] != "2026-11-01" {
		t.Fatalf("real pending plan must win once: %+v", entries)
	}
	if _, err := a.db.Exec(`UPDATE reminder_plans SET status='cancelled' WHERE task_id=?`, id); err != nil {
		t.Fatal(err)
	}
	entries = calendarEntries(t, a, token, "2026-11", "Asia/Shanghai")
	if len(entries) != 1 || entries[0]["local_date"] != "2026-11-02" || entries[0]["scheduled_at"] != "2026-11-02T00:00:00.000000000Z" {
		t.Fatalf("explicit snooze must choose one date: %+v", entries)
	}
}

func TestCalendarSingleTaskCompletionAndPauseKeepOnlyRealHistory(t *testing.T) {
	for _, complete := range []bool{false, true} {
		a, m, now := setup(t)
		token := login(t, a, m, "one@example.com")
		id := createMinuteTask(t, a, token, now.Add(15*time.Minute), 1)
		calendarDailyPlan(t, a, id, "actual", stamp(now.Add(time.Hour)), "delivered", "notification")
		*now = now.AddDate(0, 0, 2)
		if complete {
			if code, out := request(t, a, "POST", "/api/v1/tasks/"+id+"/complete", token, map[string]any{"text": "done"}); code != 200 {
				t.Fatalf("complete: %d %+v", code, out)
			}
		} else if code, out := request(t, a, "PUT", "/api/v1/tasks/"+id+"/paused", token, map[string]bool{"paused": true}); code != 200 {
			t.Fatalf("pause: %d %+v", code, out)
		}
		entries := calendarEntries(t, a, token, "2026-10", "Asia/Shanghai")
		want := 1
		if complete {
			want = 2
		}
		if len(entries) != want || entries[0]["id"] != "actual" || entries[0]["reminder_count"] != float64(1) {
			t.Fatalf("real history lost or synthetic history invented: %+v", entries)
		}
		for _, entry := range entries {
			if entry["type"] == "planned" {
				t.Fatalf("inactive single task retained a pending placeholder: %+v", entry)
			}
		}
	}
}

func TestCalendarSingleTaskDeadlineAndExplicitDateRange(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	*now = time.Date(2026, 10, 1, 9, 0, 0, 0, now.Location())
	first, deadline := now.Add(time.Hour), now.AddDate(0, 0, 3)
	id := createMinuteWindow(t, a, token, first, deadline, 5)
	entries := calendarEntries(t, a, token, "2026-10", "Asia/Shanghai")
	if len(entries) != 1 || entries[0]["task_id"] != id {
		t.Fatalf("deadline created repeated tasks: count=%d", len(entries))
	}
	*now = deadline.Add(time.Nanosecond)
	if entries = calendarEntries(t, a, token, "2026-10", "Asia/Shanghai"); len(entries) != 0 {
		t.Fatalf("expired task kept rolling forward: %+v", entries)
	}
	*now = time.Date(2026, 10, 1, 9, 0, 0, 0, now.Location())
	rule := dailyCalendarRule()
	rule.Kind, rule.Mode, rule.StartDate, rule.EndDate = "date_range", "until_done", "2026-10-01", "2026-10-04"
	rangeID := calendarTask(t, a, token, rule)
	entries = calendarEntries(t, a, token, "2026-10", "Asia/Shanghai")
	count := 0
	for _, entry := range entries {
		if entry["task_id"] == rangeID {
			count++
		}
	}
	if count != 4 {
		t.Fatalf("explicit four-day range changed: count=%d", count)
	}
}

func TestCalendarSingleTaskFutureFirstUsesQueryTimezone(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	*now = time.Date(2026, 10, 31, 20, 0, 0, 0, now.Location())
	first := time.Date(2026, 11, 1, 0, 15, 0, 0, now.Location())
	id := createMinuteTask(t, a, token, first, 1)
	if entries := calendarEntries(t, a, token, "2026-10", "Asia/Shanghai"); len(entries) != 0 {
		t.Fatalf("future first was pulled into today: %+v", entries)
	}
	entries := calendarEntries(t, a, token, "2026-11", "Asia/Shanghai")
	if len(entries) != 1 || entries[0]["at"] != stamp(first) {
		t.Fatalf("future first lost: %+v", entries)
	}
	entries = calendarEntries(t, a, token, "2026-10", "UTC")
	if len(entries) != 1 || entries[0]["local_date"] != "2026-10-31" {
		t.Fatalf("future first ignored display timezone: %+v", entries)
	}
	// A user can explicitly snooze into an earlier time within this same
	// permanent completion cycle; its real pending plan still wins.
	calendarDailyPlan(t, a, id, "explicit-earlier", stamp(now.Add(15*time.Minute)), "pending", "notification")
	entries = calendarEntries(t, a, token, "2026-10", "Asia/Shanghai")
	if len(entries) != 1 || entries[0]["id"] != "explicit-earlier" {
		t.Fatalf("real earlier pending plan lost to original first_at: %+v", entries)
	}
}

func TestCalendarSingleTaskExpiredPlanIgnoredAndOverduePlanRollsForward(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	id := createMinuteTask(t, a, token, now.Add(15*time.Minute), 1)
	calendarDailyPlan(t, a, id, "expired", "2026-10-03T12:00:00+08:00", "pending", "notification")
	calendarDailyPlan(t, a, id, "overdue", "2026-10-03T23:59:00+08:00", "pending", "notification")
	if _, err := a.db.Exec(`UPDATE reminder_plans SET expires_at='2026-10-03T13:00:00+08:00' WHERE id='expired'`); err != nil {
		t.Fatal(err)
	}
	*now = time.Date(2026, 10, 4, 1, 0, 0, 0, now.Location())
	entries := calendarEntries(t, a, token, "2026-10", "Asia/Shanghai")
	if len(entries) != 1 || entries[0]["id"] != "overdue" || entries[0]["local_date"] != "2026-10-04" || entries[0]["at"] != stamp(*now) || entries[0]["scheduled_at"] != "2026-10-03T15:59:00.000000000Z" {
		t.Fatalf("overdue pending task must roll forward without changing its real schedule: %+v", entries)
	}
}
