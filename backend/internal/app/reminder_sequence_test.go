package app

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func createDenseReminder(t *testing.T, a *App, token string, now time.Time) string {
	t.Helper()
	body := taskBody("until_done")
	body["rule"] = map[string]any{"kind": "minute_interval", "mode": "until_done", "timezone": "Asia/Shanghai", "first_at": stamp(now.Add(time.Minute)), "repeat_minutes": 5, "times": []string{}}
	code, out := request(t, a, "POST", "/api/v1/tasks", token, body)
	if code != 201 {
		t.Fatalf("create: %d %+v", code, out)
	}
	return out["id"].(string)
}

func TestSequentialReminderQueuesOnlyOneAndWaitsForReceipt(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	id := createDenseReminder(t, a, token, *now)
	enablePhone(t, a, token, []string{"notification", "alarm"})
	out := phoneSync(t, a, token, "phone", nil)
	if got := len(out["plans"].([]any)); got != 1 {
		t.Fatalf("queued %d reminders; want exactly the next one", got)
	}
	first := out["plans"].([]any)[0].(map[string]any)
	phoneSync(t, a, token, "phone", []map[string]string{{"plan_id": first["id"].(string), "status": "scheduled"}})
	*now = now.Add(2 * time.Minute)
	out = phoneSync(t, a, token, "phone", nil)
	if len(out["plans"].([]any)) != 1 || out["plans"].([]any)[0].(map[string]any)["id"] != first["id"] {
		t.Fatal("scheduling receipt advanced before delivery or timeout")
	}
	var count int
	if err := a.db.QueryRow(`SELECT COUNT(*) FROM reminder_plans WHERE task_id=?`, id).Scan(&count); err != nil || count != 1 {
		t.Fatalf("unchanged sync grew ledger: %d %v", count, err)
	}
}

func TestSequentialStrategyCreatedOnceAndUsesPlannedWait(t *testing.T) {
	var calls atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		decision := `{"reason":"先通知，留出做事时间再用闹钟","steps":[{"channel":"notification","wait_minutes":0,"reason":"先轻提醒"},{"channel":"alarm","wait_minutes":23,"reason":"仍未完成再升级"}]}`
		_ = json.NewEncoder(w).Encode(map[string]any{"choices": []any{map[string]any{"finish_reason": "stop", "message": map[string]any{"content": decision}}}})
	}))
	defer server.Close()
	a, m, now := setup(t)
	a.cfg.DeepSeekAPIKey, a.cfg.DeepSeekBaseURL = "fake-key", server.URL
	token := login(t, a, m, "one@example.com")
	enablePhone(t, a, token, []string{"notification", "alarm"})
	phoneSync(t, a, token, "phone", nil)
	id := createDenseReminder(t, a, token, *now)
	if calls.Load() != 1 {
		t.Fatalf("strategy not prepared during task creation: calls=%d", calls.Load())
	}
	_, detail := request(t, a, "GET", "/api/v1/tasks/"+id, token, nil)
	strategy, ok := detail["reminder_strategy"].(map[string]any)
	if !ok || strategy["source"] != "ai" || len(strategy["steps"].([]any)) != 2 {
		t.Fatalf("missing persisted strategy: %+v", detail)
	}
	out := phoneSync(t, a, token, "phone", nil)
	first := out["plans"].([]any)[0].(map[string]any)
	*now, _ = time.Parse(time.RFC3339Nano, first["scheduled_at"].(string))
	out = phoneSync(t, a, token, "phone", []map[string]string{{"plan_id": first["id"].(string), "status": "delivered"}})
	if len(out["plans"].([]any)) != 1 {
		t.Fatalf("expected one next step: %+v", out)
	}
	next := out["plans"].([]any)[0].(map[string]any)
	at, _ := time.Parse(time.RFC3339Nano, next["scheduled_at"].(string))
	if next["channel"] != "alarm" || !at.Equal(now.Add(23*time.Minute)) {
		t.Fatalf("strategy wait ignored: %+v", next)
	}
	phoneSync(t, a, token, "phone", nil)
	if calls.Load() != 1 {
		t.Fatalf("strategy regenerated after receipt: %d", calls.Load())
	}
	cfg := a.cfg
	a.Close()
	restarted, err := New(cfg)
	if err != nil {
		t.Fatal(err)
	}
	defer restarted.Close()
	restarted.now = a.now
	phoneSync(t, restarted, token, "phone", nil)
	if calls.Load() != 1 {
		t.Fatalf("persisted strategy regenerated on restart: %d", calls.Load())
	}
}

func TestSequentialUpgradeCancelsBacklogAndPreservesHistory(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	id := createDenseReminder(t, a, token, *now)
	enablePhone(t, a, token, []string{"notification"})
	out := phoneSync(t, a, token, "phone", nil)
	first := out["plans"].([]any)[0].(map[string]any)["id"].(string)
	for i := 0; i < 70; i++ {
		_, err := a.db.Exec(`INSERT INTO reminder_plans(id,user_id,task_id,device_id,title,body,cycle_key,channel,scheduled_at,expires_at,status,source,reason,generation,rule_snapshot) SELECT ?,user_id,task_id,device_id,title,body,cycle_key,channel,?,?,'scheduled',source,reason,'legacy-batch',rule_snapshot FROM reminder_plans WHERE id=?`, fmt.Sprintf("old-%d", i), stamp(now.Add(time.Duration(i+10)*time.Minute)), stamp(now.Add(24*time.Hour)), first)
		if err != nil {
			t.Fatal(err)
		}
	}
	_, err := a.db.Exec(`INSERT INTO reminder_plans(id,user_id,task_id,device_id,title,body,cycle_key,channel,scheduled_at,expires_at,status,source,reason,generation,sent_at) SELECT 'real-history',user_id,task_id,device_id,title,body,cycle_key,channel,?,?,'delivered',source,reason,'history',? FROM reminder_plans WHERE id=?`, stamp(now.Add(-time.Hour)), stamp(*now), stamp(now.Add(-time.Hour)), first)
	if err != nil {
		t.Fatal(err)
	}
	out = phoneSync(t, a, token, "phone", nil)
	var pending, delivered int
	a.db.QueryRow(`SELECT COUNT(*) FROM reminder_plans WHERE task_id=? AND status IN ('pending','scheduled')`, id).Scan(&pending)
	a.db.QueryRow(`SELECT COUNT(*) FROM reminder_plans WHERE id='real-history' AND status='delivered'`).Scan(&delivered)
	if pending != 1 || delivered != 1 || len(out["cancel_ids"].([]any)) < 70 {
		t.Fatalf("cleanup pending=%d history=%d response=%+v", pending, delivered, out)
	}
}

func TestSequentialConcurrentBuildersKeepSinglePlan(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	id := createDenseReminder(t, a, token, *now)
	enablePhone(t, a, token, []string{"notification"})
	phoneSync(t, a, token, "phone", nil)
	var user string
	a.db.QueryRow(`SELECT user_id FROM tasks WHERE id=?`, id).Scan(&user)
	var wg sync.WaitGroup
	errors := make(chan error, 8)
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			errors <- a.buildReminderPlans(context.Background(), user, "phone", reminderCapabilities{Notification: true})
		}()
	}
	wg.Wait()
	close(errors)
	for err := range errors {
		if err != nil {
			t.Fatal(err)
		}
	}
	var n int
	a.db.QueryRow(`SELECT COUNT(*) FROM reminder_plans WHERE task_id=? AND status IN ('pending','scheduled','sending')`, id).Scan(&n)
	if n != 1 {
		t.Fatalf("concurrent builders queued %d", n)
	}
}

func TestSequentialNotifyOnceStrategyHasNoRepeatingSteps(t *testing.T) {
	a, m, _ := setup(t)
	token := login(t, a, m, "one@example.com")
	enablePhone(t, a, token, []string{"notification", "alarm"})
	phoneSync(t, a, token, "phone", nil)
	id := createTask(t, a, token, "notify_once")
	_, out := request(t, a, "GET", "/api/v1/tasks/"+id, token, nil)
	strategy := out["reminder_strategy"].(map[string]any)
	if len(strategy["steps"].([]any)) != 1 || strategy["repeat_policy"] != "none" {
		t.Fatalf("notify_once has repeating strategy: %+v", strategy)
	}
}

func TestSequentialDailyCycleRestartsAtFirstStep(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	body := taskBody("every_day")
	body["rule"] = map[string]any{"kind": "daily", "mode": "every_day", "timezone": "Asia/Shanghai", "times": []string{"23:59"}}
	code, out := request(t, a, "POST", "/api/v1/tasks", token, body)
	if code != 201 {
		t.Fatal(out)
	}
	enablePhone(t, a, token, []string{"notification", "alarm"})
	out = phoneSync(t, a, token, "phone", nil)
	first := out["plans"].([]any)[0].(map[string]any)
	*now = time.Date(2026, 10, 3, 23, 59, 0, 0, now.Location())
	phoneSync(t, a, token, "phone", []map[string]string{{"plan_id": first["id"].(string), "status": "delivered"}})
	*now = time.Date(2026, 10, 4, 23, 54, 0, 0, now.Location())
	out = phoneSync(t, a, token, "phone", nil)
	if len(out["plans"].([]any)) != 1 {
		t.Fatalf("old cycle blocked next day: %+v", out)
	}
	next := out["plans"].([]any)[0].(map[string]any)
	at, _ := time.Parse(time.RFC3339Nano, next["scheduled_at"].(string))
	if next["channel"] != "notification" || next["id"] == first["id"] || !at.Equal(now.Add(5*time.Minute)) {
		t.Fatalf("new cycle did not restart initial step: %+v", next)
	}
}

func TestSequentialLateDeliveryCancelsNotifyOnceRetry(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	body := taskBody("notify_once")
	body["rule"] = map[string]any{"kind": "once", "mode": "notify_once", "timezone": "Asia/Shanghai", "start_date": "2026-10-03", "times": []string{"09:15"}}
	if c, out := request(t, a, "POST", "/api/v1/tasks", token, body); c != 201 {
		t.Fatal(out)
	}
	enablePhone(t, a, token, []string{"notification", "alarm"})
	out := phoneSync(t, a, token, "phone", nil)
	first := out["plans"].([]any)[0].(map[string]any)
	*now = now.Add(11 * time.Minute)
	out = phoneSync(t, a, token, "phone", nil)
	if len(out["plans"].([]any)) != 1 {
		t.Fatal("missing timeout retry")
	}
	out = phoneSync(t, a, token, "phone", []map[string]string{{"plan_id": first["id"].(string), "status": "delivered"}})
	if len(out["plans"].([]any)) != 0 {
		t.Fatalf("late confirmed delivery kept unnecessary retry: %+v", out)
	}
}

func TestSequentialEditedFirstTimeCannotUseOldReceipt(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	id := createDenseReminder(t, a, token, *now)
	enablePhone(t, a, token, []string{"notification", "alarm"})
	out := phoneSync(t, a, token, "phone", nil)
	first := out["plans"].([]any)[0].(map[string]any)
	*now = now.Add(time.Minute)
	phoneSync(t, a, token, "phone", []map[string]string{{"plan_id": first["id"].(string), "status": "delivered"}})
	newFirst := now.Add(12 * time.Hour)
	body := taskBody("until_done")
	body["rule"] = map[string]any{"kind": "minute_interval", "mode": "until_done", "timezone": "Asia/Shanghai", "first_at": stamp(newFirst), "repeat_minutes": 5, "times": []string{}}
	if c, out := request(t, a, "PUT", "/api/v1/tasks/"+id, token, body); c != 200 {
		t.Fatal(out)
	}
	_, edited := request(t, a, "GET", "/api/v1/tasks/"+id, token, nil)
	if edited["reminder_strategy"].(map[string]any)["state"] != "awaiting_sync" {
		t.Fatal("edited strategy incorrectly asks for authorization again")
	}
	out = phoneSync(t, a, token, "phone", nil)
	if len(out["plans"].([]any)) != 1 {
		t.Fatal("missing new initial reminder")
	}
	next := out["plans"].([]any)[0].(map[string]any)
	at, _ := time.Parse(time.RFC3339Nano, next["scheduled_at"].(string))
	if !at.Equal(newFirst) || next["channel"] != "notification" {
		t.Fatalf("old receipt bypassed edited first time: %+v", next)
	}
}

func TestSequentialCalendarAtTriggerIsHistoryNotReturnedAgain(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	createDenseReminder(t, a, token, *now)
	enablePhone(t, a, token, []string{"calendar", "notification"})
	out := phoneSync(t, a, token, "phone", nil)
	first := out["plans"].([]any)[0].(map[string]any)
	*now, _ = time.Parse(time.RFC3339Nano, first["scheduled_at"].(string))
	out = phoneSync(t, a, token, "phone", []map[string]string{{"plan_id": first["id"].(string), "status": "scheduled"}})
	if len(out["plans"].([]any)) != 0 {
		t.Fatal("past calendar entry returned as executable")
	}
	var status string
	a.db.QueryRow(`SELECT status FROM reminder_plans WHERE id=?`, first["id"]).Scan(&status)
	if status != "scheduled" {
		t.Fatal("calendar execution history changed")
	}
}

func TestSequentialUnknownDeviceDoesNotInventAuthorizedChannels(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	enablePhone(t, a, token, []string{"notification", "alarm"})
	id := createDenseReminder(t, a, token, *now)
	_, out := request(t, a, "GET", "/api/v1/tasks/"+id, token, nil)
	strategy := out["reminder_strategy"].(map[string]any)
	if strategy["state"] != "awaiting_authorization" || len(strategy["steps"].([]any)) != 0 {
		t.Fatalf("invented device permissions: %+v", strategy)
	}
}

func TestSequentialCalendarLateFailurePreservesScheduledHistory(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	createDenseReminder(t, a, token, *now)
	enablePhone(t, a, token, []string{"calendar", "notification"})
	out := phoneSync(t, a, token, "phone", nil)
	first := out["plans"].([]any)[0].(map[string]any)
	phoneSync(t, a, token, "phone", []map[string]string{{"plan_id": first["id"].(string), "status": "scheduled"}})
	*now = now.Add(7 * time.Minute)
	phoneSync(t, a, token, "phone", []map[string]string{{"plan_id": first["id"].(string), "status": "failed", "error": "old client expired"}})
	var status string
	a.db.QueryRow(`SELECT status FROM reminder_plans WHERE id=?`, first["id"]).Scan(&status)
	if status != "scheduled" {
		t.Fatalf("late client cleanup erased real scheduling: %s", status)
	}
}

func TestSequentialMaximumRemindersStopsAfterRealDeliveries(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	body := taskBody("until_done")
	body["rule"] = map[string]any{"kind": "minute_interval", "mode": "until_done", "timezone": "Asia/Shanghai", "first_at": stamp(now.Add(time.Minute)), "repeat_minutes": 1, "max_reminders": 2, "times": []string{}}
	if c, out := request(t, a, "POST", "/api/v1/tasks", token, body); c != 201 {
		t.Fatal(out)
	}
	if c, out := request(t, a, "PUT", "/api/v1/reminders/settings", token, map[string]any{"enabled": true, "channels": []string{"notification", "alarm"}, "quiet_start": "", "quiet_end": "", "min_interval_minutes": 1}); c != 200 {
		t.Fatal(out)
	}
	out := phoneSync(t, a, token, "phone", nil)
	for i := 0; i < 2; i++ {
		if len(out["plans"].([]any)) != 1 {
			t.Fatalf("step %d missing single reminder", i)
		}
		plan := out["plans"].([]any)[0].(map[string]any)
		*now, _ = time.Parse(time.RFC3339Nano, plan["scheduled_at"].(string))
		out = phoneSync(t, a, token, "phone", []map[string]string{{"plan_id": plan["id"].(string), "status": "delivered"}})
	}
	if len(out["plans"].([]any)) != 0 {
		t.Fatal("exceeded real delivery cap")
	}
}

func TestSequentialWaitPastDeadlineDoesNotFallBackToEarlierRuleSlot(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	body := taskBody("until_done")
	body["rule"] = map[string]any{"kind": "minute_interval", "mode": "until_done", "timezone": "Asia/Shanghai", "first_at": stamp(now.Add(time.Minute)), "end_at": stamp(now.Add(11 * time.Minute)), "repeat_minutes": 1, "times": []string{}}
	if c, out := request(t, a, "POST", "/api/v1/tasks", token, body); c != 201 {
		t.Fatal(out)
	}
	enablePhone(t, a, token, []string{"notification", "alarm"})
	out := phoneSync(t, a, token, "phone", nil)
	for i := 0; i < 2; i++ {
		plan := out["plans"].([]any)[0].(map[string]any)
		*now, _ = time.Parse(time.RFC3339Nano, plan["scheduled_at"].(string))
		out = phoneSync(t, a, token, "phone", []map[string]string{{"plan_id": plan["id"].(string), "status": "delivered"}})
	}
	if len(out["plans"].([]any)) != 0 {
		t.Fatalf("wait crossed deadline but an earlier rule slot bypassed it: %+v", out["plans"])
	}
}

func TestSequentialEditedFirstTimeStillHonorsMinimumAfterDelivery(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	id := createDenseReminder(t, a, token, *now)
	enablePhone(t, a, token, []string{"notification"})
	out := phoneSync(t, a, token, "phone", nil)
	first := out["plans"].([]any)[0].(map[string]any)
	*now = now.Add(time.Minute)
	phoneSync(t, a, token, "phone", []map[string]string{{"plan_id": first["id"].(string), "status": "delivered"}})
	body := taskBody("until_done")
	body["rule"] = map[string]any{"kind": "minute_interval", "mode": "until_done", "timezone": "Asia/Shanghai", "first_at": stamp(now.Add(time.Minute)), "repeat_minutes": 1, "times": []string{}}
	if c, out := request(t, a, "PUT", "/api/v1/tasks/"+id, token, body); c != 200 {
		t.Fatal(out)
	}
	out = phoneSync(t, a, token, "phone", nil)
	next := out["plans"].([]any)[0].(map[string]any)
	at, _ := time.Parse(time.RFC3339Nano, next["scheduled_at"].(string))
	if at.Before(now.Add(5 * time.Minute)) {
		t.Fatalf("editing bypassed minimum interval: %+v", next)
	}
}

func TestSequentialExactDeadlineHasRepresentableDeviceWindow(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	at := stamp(now.Add(time.Minute))
	body := taskBody("until_done")
	body["rule"] = map[string]any{"kind": "minute_interval", "mode": "until_done", "timezone": "Asia/Shanghai", "first_at": at, "end_at": at, "repeat_minutes": 1, "times": []string{}}
	if c, out := request(t, a, "POST", "/api/v1/tasks", token, body); c != 201 {
		t.Fatal(out)
	}
	enablePhone(t, a, token, []string{"notification"})
	out := phoneSync(t, a, token, "phone", nil)
	plan := out["plans"].([]any)[0].(map[string]any)
	scheduled, _ := time.Parse(time.RFC3339Nano, plan["scheduled_at"].(string))
	expires, _ := time.Parse(time.RFC3339Nano, plan["expires_at"].(string))
	if expires.UnixMilli() <= scheduled.UnixMilli() {
		t.Fatal("inclusive deadline has zero milliseconds for device execution")
	}
}

func TestSequentialLateReceiptDoesNotRegressOrForkQueue(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	id := createDenseReminder(t, a, token, *now)
	enablePhone(t, a, token, []string{"notification", "alarm"})
	out := phoneSync(t, a, token, "phone", nil)
	first := out["plans"].([]any)[0].(map[string]any)
	*now = now.Add(7 * time.Minute)
	out = phoneSync(t, a, token, "phone", nil)
	next := out["plans"].([]any)[0].(map[string]any)
	out = phoneSync(t, a, token, "phone", []map[string]string{{"plan_id": first["id"].(string), "status": "scheduled"}})
	if len(out["plans"].([]any)) != 1 || out["plans"].([]any)[0].(map[string]any)["id"] != next["id"] {
		t.Fatal("late scheduling ack revived previous queue")
	}
	phoneSync(t, a, token, "phone", []map[string]string{{"plan_id": first["id"].(string), "status": "delivered"}})
	var count int
	a.db.QueryRow(`SELECT COUNT(*) FROM reminder_plans WHERE task_id=? AND status IN ('pending','scheduled')`, id).Scan(&count)
	if count != 1 {
		t.Fatalf("late delivery forked queue: %d", count)
	}
}
