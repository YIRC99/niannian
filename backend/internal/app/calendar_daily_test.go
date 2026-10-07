package app

import (
	"encoding/json"
	"fmt"
	"testing"
	"time"
)

func calendarDailyPlan(t *testing.T, a *App, taskID, id, at, status, channel string) {
	t.Helper()
	_, err := a.db.Exec(`INSERT INTO reminder_plans(id,user_id,task_id,device_id,title,body,cycle_key,channel,scheduled_at,expires_at,status,source,reason,generation,rule_snapshot,sent_at) SELECT ?,user_id,id,'phone',title,'','minute_interval',?,?,'2026-11-02T00:00:00Z',?,'rule','',?,rule,CASE WHEN ?='delivered' THEN ? ELSE '' END FROM tasks WHERE id=?`, id, channel, at, status, id, status, at, taskID)
	if err != nil {
		t.Fatal(err)
	}
}

func TestCalendarDailyDenseMinuteProjection(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	*now = time.Date(2026, 10, 1, 0, 0, 0, 0, now.Location())
	id := createMinuteTask(t, a, token, now.Add(15*time.Minute), 1)
	entries := calendarEntries(t, a, token, "2026-10", "Asia/Shanghai")
	if len(entries) != 31 {
		t.Fatalf("one minute task should occupy 31 daily entries, got %d", len(entries))
	}
	for i, entry := range entries {
		if entry["task_id"] != id || entry["local_date"] != fmt.Sprintf("2026-10-%02d", i+1) || entry["type"] != "planned" || entry["reminder_count"] != float64(0) {
			t.Fatalf("daily projection must not claim delivery: %+v", entry)
		}
	}
}

func TestCalendarDailyLedgerAggregationUsesDisplayTimezone(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	id := createMinuteTask(t, a, token, now.Add(15*time.Minute), 1)
	if _, err := a.db.Exec(`UPDATE tasks SET paused=1 WHERE id=?`, id); err != nil {
		t.Fatal(err)
	}
	calendarDailyPlan(t, a, id, "delivered-1", "2026-10-03T10:00:00Z", "delivered", "notification")
	calendarDailyPlan(t, a, id, "delivered-2", "2026-10-03T11:00:00Z", "delivered", "alarm")
	calendarDailyPlan(t, a, id, "cancelled", "2026-10-03T12:00:00Z", "cancelled", "notification")
	calendarDailyPlan(t, a, id, "pending", "2026-10-03T13:00:00Z", "pending", "notification")
	calendarDailyPlan(t, a, id, "scheduled", "2026-10-03T17:00:00Z", "scheduled", "calendar")
	*now = time.Date(2026, 10, 5, 0, 0, 0, 0, time.UTC)
	entries := calendarEntries(t, a, token, "2026-10", "Asia/Shanghai")
	if len(entries) != 2 || entries[0]["id"] != "delivered-2" || entries[0]["reminder_count"] != float64(2) || entries[0]["last_reminder_at"] != "2026-10-03T11:00:00.000000000Z" || entries[0]["last_reminder_status"] != "delivered" {
		t.Fatalf("actual deliveries must be one daily summary: %+v", entries)
	}
	if entries[1]["local_date"] != "2026-10-04" || entries[1]["type"] != "planned" || entries[1]["reminder_count"] != float64(0) || entries[1]["state"] == "notified" {
		t.Fatalf("calendar scheduling must not become actual delivery: %+v", entries[1])
	}
	if entries = calendarEntries(t, a, token, "2026-10", "UTC"); len(entries) != 1 || entries[0]["reminder_count"] != float64(2) {
		t.Fatalf("UTC should group all times into the same date: %+v", entries)
	}
}

func TestCalendarDailyKeepsEveryCompletionAndPhoto(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	id := createMinuteTask(t, a, token, now.Add(15*time.Minute), 1)
	calendarDailyPlan(t, a, id, "delivered", "2026-10-03T02:00:00Z", "delivered", "notification")
	for i, at := range []string{"2026-10-03T03:00:00Z", "2026-10-03T04:00:00Z"} {
		photos, _ := json.Marshal([]string{fmt.Sprintf("photo-%d", i)})
		if _, err := a.db.Exec(`INSERT INTO completions(id,user_id,task_id,task_title,cycle_key,text,media_ids,created_at) SELECT ?,user_id,id,title,?,?,?,? FROM tasks WHERE id=?`, fmt.Sprintf("complete-%d", i), fmt.Sprintf("old-cycle-%d", i), fmt.Sprintf("note-%d", i), string(photos), at, id); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := a.db.Exec(`UPDATE tasks SET archived=1 WHERE id=?`, id); err != nil {
		t.Fatal(err)
	}
	entries := calendarEntries(t, a, token, "2026-10", "Asia/Shanghai")
	if len(entries) != 1 || entries[0]["type"] != "completion" || entries[0]["id"] != "complete-1" || entries[0]["completion_count"] != float64(2) || entries[0]["reminder_count"] != float64(1) {
		t.Fatalf("completion should represent its daily task: %+v", entries)
	}
	records, ok := entries[0]["completion_records"].([]any)
	if !ok || len(records) != 2 || len(entries[0]["media_ids"].([]any)) != 2 {
		t.Fatalf("completion records or photos lost: %+v", entries[0])
	}
	for i, raw := range records {
		record := raw.(map[string]any)
		if record["id"] != fmt.Sprintf("complete-%d", i) || record["text"] != fmt.Sprintf("note-%d", i) || record["media_ids"].([]any)[0] != fmt.Sprintf("photo-%d", i) {
			t.Fatalf("original completion changed: %+v", record)
		}
	}
}

func TestCalendarDailyRawRowsDoNotConsumeDisplayLimit(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	id := createMinuteTask(t, a, token, now.Add(15*time.Minute), 1)
	if _, err := a.db.Exec(`UPDATE tasks SET paused=1 WHERE id=?`, id); err != nil {
		t.Fatal(err)
	}
	tx, err := a.db.Begin()
	if err != nil {
		t.Fatal(err)
	}
	for i := 0; i < calendarEntryLimit+10; i++ {
		if _, err = tx.Exec(`INSERT INTO reminder_plans(id,user_id,task_id,device_id,title,body,cycle_key,channel,scheduled_at,expires_at,status,source,reason,generation,rule_snapshot) SELECT ?,user_id,id,'phone',title,'','minute_interval','notification','2026-10-03T10:00:00Z','2026-10-04T00:00:00Z',?,'rule','',?,rule FROM tasks WHERE id=?`, fmt.Sprint("old-", i), []string{"pending", "cancelled"}[i%2], fmt.Sprint(i), id); err != nil {
			t.Fatal(err)
		}
	}
	if err = tx.Commit(); err != nil {
		t.Fatal(err)
	}
	calendarDailyPlan(t, a, id, "later-delivery", "2026-10-04T10:00:00Z", "delivered", "notification")
	entries := calendarEntries(t, a, token, "2026-10", "Asia/Shanghai")
	if len(entries) != 2 || entries[1]["id"] != "later-delivery" || entries[1]["reminder_count"] != float64(1) {
		t.Fatalf("raw duplicate rows hid later daily record: %+v", entries)
	}
}

func TestCalendarDailyCompletionDoesNotCompleteEarlierDays(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	id := createMinuteTask(t, a, token, now.Add(15*time.Minute), 1)
	*now = now.AddDate(0, 0, 2)
	if code, out := request(t, a, "POST", "/api/v1/tasks/"+id+"/complete", token, map[string]any{"text": "完成"}); code != 200 {
		t.Fatalf("complete: %d %+v", code, out)
	}
	entries := calendarEntries(t, a, token, "2026-10", "Asia/Shanghai")
	if len(entries) != 3 || entries[2]["type"] != "completion" || entries[2]["local_date"] != "2026-10-05" {
		t.Fatalf("completion should keep earlier daily plans: %+v", entries)
	}
	for _, entry := range entries[:2] {
		if entry["state"] == "completed" || entry["type"] != "planned" || entry["reminder_count"] != float64(0) {
			t.Fatalf("later completion rewrote earlier history: %+v", entry)
		}
	}
}

func TestCalendarDailyAgentShowsFutureDatesWithoutCreatingPlans(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	*now = time.Date(2026, 10, 1, 9, 0, 0, 0, now.Location())
	first := now.Add(15 * time.Minute)
	createMinuteWindow(t, a, token, first, first.AddDate(0, 0, 3), 15)
	enablePhone(t, a, token, []string{"notification"})
	synced := phoneSync(t, a, token, "phone", nil)
	plans := synced["plans"].([]any)
	if len(plans) != 1 {
		t.Fatalf("expected one real next plan, got %d", len(plans))
	}
	var before, after int
	if err := a.db.QueryRow(`SELECT COUNT(*) FROM reminder_plans`).Scan(&before); err != nil {
		t.Fatal(err)
	}
	entries := calendarEntries(t, a, token, "2026-10", "Asia/Shanghai")
	if len(entries) != 4 {
		t.Fatalf("four-day task should show every daily overview, got %d", len(entries))
	}
	if entries[0]["id"] != plans[0].(map[string]any)["id"] {
		t.Fatalf("real next plan must represent the first day: %+v", entries[0])
	}
	for i, entry := range entries {
		if entry["local_date"] != fmt.Sprintf("2026-10-%02d", i+1) || entry["type"] != "planned" || entry["reminder_count"] != float64(0) || entry["state"] != "upcoming" {
			t.Fatalf("daily overview claimed an actual execution: %+v", entry)
		}
	}
	if err := a.db.QueryRow(`SELECT COUNT(*) FROM reminder_plans`).Scan(&after); err != nil || after != before {
		t.Fatalf("calendar created execution plans: before=%d after=%d err=%v", before, after, err)
	}
}

func TestCalendarDailyCalendarInsertionPreservesBudgetWithoutDeliveryClaim(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	first := now.Add(15 * time.Minute)
	body := minuteTaskBody(first, 1)
	body["rule"].(map[string]any)["max_reminders"] = 1
	code, created := request(t, a, "POST", "/api/v1/tasks", token, body)
	if code != 201 {
		t.Fatalf("create: %d %+v", code, created)
	}
	calendarDailyPlan(t, a, created["id"].(string), "legacy-calendar-receipt", stamp(first), "delivered", "calendar")
	*now = time.Date(2026, 11, 1, 0, 0, 0, 0, now.Location())
	entries := calendarEntries(t, a, token, "2026-10", "Asia/Shanghai")
	if len(entries) != 1 || entries[0]["id"] != "legacy-calendar-receipt" || entries[0]["type"] != "planned" || entries[0]["reminder_count"] != float64(0) {
		t.Fatalf("calendar insertion should consume its original budget without claiming delivery: count=%d", len(entries))
	}
}

func TestCalendarDailySuccessfulReminderUsesExecutionDateAndMonth(t *testing.T) {
	for _, status := range []string{"sent", "accepted", "delivered"} {
		t.Run(status, func(t *testing.T) {
			a, m, now := setup(t)
			token := login(t, a, m, "one@example.com")
			id := createMinuteTask(t, a, token, now.Add(15*time.Minute), 1)
			scheduled, sent := "2026-10-31T23:59:00+08:00", "2026-11-01T00:01:00+08:00"
			if status == "sent" {
				if _, err := a.db.Exec(`INSERT INTO notifications(id,user_id,task_id,task_title,cycle_key,scheduled_at,sent_at,status,rule_snapshot) SELECT 'late-reminder',user_id,id,title,'minute_interval',?,?,'sent',rule FROM tasks WHERE id=?`, scheduled, sent, id); err != nil {
					t.Fatal(err)
				}
			} else {
				calendarDailyPlan(t, a, id, "late-reminder", scheduled, status, map[string]string{"accepted": "email", "delivered": "notification"}[status])
				if _, err := a.db.Exec(`UPDATE reminder_plans SET sent_at=? WHERE id='late-reminder'`, sent); err != nil {
					t.Fatal(err)
				}
			}
			if _, err := a.db.Exec(`UPDATE tasks SET paused=1 WHERE id=?`, id); err != nil {
				t.Fatal(err)
			}
			*now = time.Date(2026, 11, 2, 0, 0, 0, 0, now.Location())
			entries := calendarEntries(t, a, token, "2026-11", "Asia/Shanghai")
			if len(entries) != 1 {
				t.Fatalf("successful reminder missing from execution month: count=%d", len(entries))
			}
			e := entries[0]
			if e["local_date"] != "2026-11-01" || e["at"] != "2026-10-31T16:01:00.000000000Z" || e["scheduled_at"] != "2026-10-31T15:59:00.000000000Z" || e["reminder_count"] != float64(1) || e["last_reminder_at"] != e["at"] {
				t.Fatalf("success must use actual time while retaining original schedule: %+v", e)
			}
			if entries = calendarEntries(t, a, token, "2026-10", "Asia/Shanghai"); len(entries) != 0 {
				t.Fatalf("success counted in scheduled month: count=%d", len(entries))
			}
			entries = calendarEntries(t, a, token, "2026-10", "UTC")
			if len(entries) != 1 || entries[0]["local_date"] != "2026-10-31" || entries[0]["at"] != "2026-10-31T16:01:00.000000000Z" {
				t.Fatalf("execution date ignored display timezone: %+v", entries)
			}
		})
	}
}

func TestCalendarDailyLateExecutionPreservesScheduledBudget(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	*now = time.Date(2026, 10, 30, 9, 0, 0, 0, now.Location())
	first := time.Date(2026, 10, 30, 23, 59, 0, 0, now.Location())
	body := minuteTaskBody(first, 1)
	body["rule"].(map[string]any)["max_reminders"] = 1
	code, created := request(t, a, "POST", "/api/v1/tasks", token, body)
	if code != 201 {
		t.Fatalf("create: %d %+v", code, created)
	}
	calendarDailyPlan(t, a, created["id"].(string), "late-budget", stamp(first), "accepted", "email")
	if _, err := a.db.Exec(`UPDATE reminder_plans SET sent_at='2026-11-01T00:01:00+08:00' WHERE id='late-budget'`); err != nil {
		t.Fatal(err)
	}
	*now = time.Date(2026, 11, 2, 0, 0, 0, 0, now.Location())
	if entries := calendarEntries(t, a, token, "2026-10", "Asia/Shanghai"); len(entries) != 0 {
		t.Fatalf("late success must still stop October budget projections: count=%d", len(entries))
	}
	entries := calendarEntries(t, a, token, "2026-11", "Asia/Shanghai")
	if len(entries) != 1 || entries[0]["id"] != "late-budget" || entries[0]["reminder_count"] != float64(1) {
		t.Fatalf("late success reset or double-counted budget: %+v", entries)
	}
}
