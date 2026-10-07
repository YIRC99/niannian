package app

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"
)

func enablePhone(t *testing.T, a *App, token string, channels []string) {
	t.Helper()
	c, out := request(t, a, "PUT", "/api/v1/reminders/settings", token, map[string]any{"enabled": true, "channels": channels, "quiet_start": "", "quiet_end": "", "min_interval_minutes": 5})
	if c != 200 {
		t.Fatalf("settings %d %+v", c, out)
	}
}

func TestReminderAgentUsesAndCachesRealModelDecision(t *testing.T) {
	var calls atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		if r.Header.Get("Authorization") != "Bearer test-secret" {
			t.Error("model request missing auth")
		}
		fmt.Fprint(w, `{"choices":[{"finish_reason":"stop","message":{"content":"{\"channel\":\"calendar\",\"reason\":\"申报事项适合先写入日程\"}"}}]}`)
	}))
	defer server.Close()
	a, m, _ := setup(t)
	a.cfg.DeepSeekAPIKey = "test-secret"
	a.cfg.DeepSeekBaseURL = server.URL
	token := login(t, a, m, "one@example.com")
	createTask(t, a, token, "until_done")
	enablePhone(t, a, token, []string{"notification", "calendar"})
	first := phoneSync(t, a, token, "phone", nil)
	phoneSync(t, a, token, "phone", nil)
	plan := first["plans"].([]any)[0].(map[string]any)
	if plan["source"] != "ai" || plan["channel"] != "calendar" || calls.Load() != 1 {
		t.Fatalf("model decision not used/cached: %+v calls=%d", plan, calls.Load())
	}
}

func TestReminderAgentRejectsUnauthorizedModelChannel(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprint(w, `{"choices":[{"finish_reason":"stop","message":{"content":"{\"channel\":\"email\",\"reason\":\"发邮件\"}"}}]}`)
	}))
	defer server.Close()
	a, m, _ := setup(t)
	a.cfg.DeepSeekAPIKey = "test-secret"
	a.cfg.DeepSeekBaseURL = server.URL
	token := login(t, a, m, "one@example.com")
	createTask(t, a, token, "until_done")
	enablePhone(t, a, token, []string{"notification"})
	out := phoneSync(t, a, token, "phone", nil)
	plan := out["plans"].([]any)[0].(map[string]any)
	if plan["source"] != "rules" || plan["channel"] != "notification" {
		t.Fatalf("unauthorized model action escaped: %+v", plan)
	}
}

func TestReminderPreferencesTimezoneAcceptedAndQuietHoursEnforced(t *testing.T) {
	a, m, _ := setup(t)
	token := login(t, a, m, "one@example.com")
	createTask(t, a, token, "until_done")
	c, out := request(t, a, "PUT", "/api/v1/reminders/settings", token, map[string]any{"enabled": true, "channels": []string{"notification"}, "quiet_start": "17:00", "quiet_end": "19:00", "min_interval_minutes": 5, "timezone": "Asia/Shanghai"})
	if c != 200 {
		t.Fatalf("timezone preference rejected %d %+v", c, out)
	}
	out = phoneSync(t, a, token, "phone", nil)
	for _, raw := range out["plans"].([]any) {
		p := raw.(map[string]any)
		at, _ := time.Parse(time.RFC3339Nano, p["scheduled_at"].(string))
		if at.In(time.FixedZone("CST", 8*3600)).Hour() == 18 {
			t.Fatal("quiet hour scheduled")
		}
	}
}

func TestSingleOccurrenceContinuesAnotherChannelUntilCompleted(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	body := taskBody("until_done")
	body["rule"] = map[string]any{"kind": "once", "mode": "until_done", "timezone": "Asia/Shanghai", "times": []string{"09:15"}, "start_date": "2026-10-03"}
	c, created := request(t, a, "POST", "/api/v1/tasks", token, body)
	if c != 201 {
		t.Fatal(created)
	}
	id := created["id"].(string)
	enablePhone(t, a, token, []string{"notification", "alarm"})
	out := phoneSync(t, a, token, "phone", nil)
	first := out["plans"].([]any)[0].(map[string]any)
	*now = time.Date(2026, 10, 3, 9, 15, 0, 0, now.Location())
	out = phoneSync(t, a, token, "phone", []map[string]string{{"plan_id": first["id"].(string), "status": "delivered"}})
	if len(out["plans"].([]any)) == 0 {
		t.Fatal("single occurrence stopped while task remained incomplete")
	}
	follow := out["plans"].([]any)[0].(map[string]any)
	at, _ := time.Parse(time.RFC3339Nano, follow["scheduled_at"].(string))
	if follow["channel"] != "alarm" || at.Before(now.Add(5*time.Minute)) {
		t.Fatalf("invalid follow-up %+v", follow)
	}
	request(t, a, "POST", "/api/v1/tasks/"+id+"/complete", token, map[string]any{})
	out = phoneSync(t, a, token, "phone", nil)
	if len(out["plans"].([]any)) != 0 {
		t.Fatal("completed single task kept following up")
	}
}

func TestManagedTaskNextReminderMatchesActualPlanAndDisabledState(t *testing.T) {
	a, m, _ := setup(t)
	token := login(t, a, m, "one@example.com")
	id := createTask(t, a, token, "until_done")
	enablePhone(t, a, token, []string{"notification"})
	phoneSync(t, a, token, "phone", nil)
	c, _ := request(t, a, "PUT", "/api/v1/reminders/settings", token, map[string]any{"enabled": false, "channels": []string{"notification"}, "quiet_start": "", "quiet_end": "", "min_interval_minutes": 5})
	if c != 200 {
		t.Fatal(c)
	}
	_, out := request(t, a, "GET", "/api/v1/tasks/"+id, token, nil)
	if out["next_reminder"] != "" {
		t.Fatalf("disabled agent still claims next reminder: %+v", out)
	}
}

func TestCalendarScheduleIsNotDeliveryAndKeepsNotifyOnceBudget(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	id := createTask(t, a, token, "notify_once")
	enablePhone(t, a, token, []string{"calendar"})
	out := phoneSync(t, a, token, "phone", nil)
	plan := out["plans"].([]any)[0].(map[string]any)
	at, _ := time.Parse(time.RFC3339Nano, plan["scheduled_at"].(string))
	*now = at
	phoneSync(t, a, token, "phone", []map[string]string{{"plan_id": plan["id"].(string), "status": "scheduled"}})
	*now = now.Add(30 * time.Minute)
	phoneSync(t, a, token, "phone", nil)
	_, task := request(t, a, "GET", "/api/v1/tasks/"+id, token, nil)
	if task["status"] != "done" {
		t.Fatal("calendar expiry erased notify_once budget")
	}
	var status, sent string
	a.db.QueryRow(`SELECT status,sent_at FROM reminder_plans WHERE id=?`, plan["id"]).Scan(&status, &sent)
	if status != "scheduled" || sent != "" {
		t.Fatal("calendar scheduling incorrectly became delivery")
	}
}

func TestInterruptedAgentEmailIsRecoveredOnRestart(t *testing.T) {
	a, m, _ := setup(t)
	token := login(t, a, m, "one@example.com")
	createTask(t, a, token, "until_done")
	enablePhone(t, a, token, []string{"email"})
	phoneSync(t, a, token, "phone", nil)
	if _, err := a.db.Exec(`UPDATE reminder_plans SET status='sending' WHERE channel='email'`); err != nil {
		t.Fatal(err)
	}
	cfg := a.cfg
	a.Close()
	restarted, err := New(cfg)
	if err != nil {
		t.Fatal(err)
	}
	defer restarted.Close()
	var stuck int
	if err = restarted.db.QueryRow(`SELECT COUNT(*) FROM reminder_plans WHERE status='sending'`).Scan(&stuck); err != nil || stuck != 0 {
		t.Fatalf("interrupted mail stuck %d %v", stuck, err)
	}
}

func TestDetachKeepsOnlyExplicitEmailRunning(t *testing.T) {
	for _, emailEnabled := range []bool{true, false} {
		t.Run(fmt.Sprint(emailEnabled), func(t *testing.T) {
			a, m, now := setup(t)
			token := login(t, a, m, "one@example.com")
			createTask(t, a, token, "until_done")
			channels := []string{"notification"}
			if emailEnabled {
				channels = append(channels, "email")
			}
			enablePhone(t, a, token, channels)
			phoneSync(t, a, token, "phone", nil)
			c, _ := request(t, a, "POST", "/api/v1/reminders/sync", token, map[string]any{"device_id": "phone", "detach": true, "capabilities": map[string]bool{}})
			if c != 200 {
				t.Fatal(c)
			}
			if err := a.tick(context.Background()); err != nil {
				t.Fatal(err)
			}
			m.messages = nil
			*now = time.Date(2026, 10, 3, 18, 0, 0, 0, now.Location())
			if err := a.tick(context.Background()); err != nil {
				t.Fatal(err)
			}
			if emailEnabled && len(m.messages) != 1 {
				t.Fatalf("explicit email stopped after detach: %d", len(m.messages))
			}
			if !emailEnabled && len(m.messages) != 0 {
				t.Fatal("email sent without permission")
			}
		})
	}
}

func TestBatchFailureOfOneChannelStillSwitchesOtherChannel(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	body := taskBody("until_done")
	body["rule"] = map[string]any{"kind": "minute_interval", "mode": "until_done", "timezone": "Asia/Shanghai", "first_at": stamp(now.Add(time.Minute)), "repeat_minutes": 5, "times": []string{}}
	if c, out := request(t, a, "POST", "/api/v1/tasks", token, body); c != 201 {
		t.Fatal(out)
	}
	enablePhone(t, a, token, []string{"notification", "calendar"})
	out := phoneSync(t, a, token, "phone", nil)
	results := []map[string]string{}
	for _, raw := range out["plans"].([]any) {
		p := raw.(map[string]any)
		results = append(results, map[string]string{"plan_id": p["id"].(string), "status": "failed"})
	}
	out = phoneSync(t, a, token, "phone", results)
	if len(out["plans"].([]any)) == 0 || out["plans"].([]any)[0].(map[string]any)["channel"] != "calendar" {
		t.Fatal("batch permission failure blocked other authorized channel")
	}
}

func TestQuietHoursDeferWithinCycleButNeverPastDeadline(t *testing.T) {
	a, m, _ := setup(t)
	token := login(t, a, m, "one@example.com")
	body := taskBody("until_done")
	body["rule"] = map[string]any{"kind": "once", "mode": "until_done", "timezone": "Asia/Shanghai", "times": []string{"09:15"}, "start_date": "2026-10-03"}
	if c, out := request(t, a, "POST", "/api/v1/tasks", token, body); c != 201 {
		t.Fatal(out)
	}
	c, out := request(t, a, "PUT", "/api/v1/reminders/settings", token, map[string]any{"enabled": true, "channels": []string{"notification"}, "quiet_start": "09:00", "quiet_end": "10:00", "min_interval_minutes": 5, "timezone": "Asia/Shanghai"})
	if c != 200 {
		t.Fatal(out)
	}
	out = phoneSync(t, a, token, "phone", nil)
	plans := out["plans"].([]any)
	if len(plans) != 1 {
		t.Fatalf("quiet slot was lost instead of deferred: %+v", out)
	}
	at, _ := time.Parse(time.RFC3339Nano, plans[0].(map[string]any)["scheduled_at"].(string))
	if at.UTC().Hour() != 2 {
		t.Fatal("did not defer until quiet end")
	}
}

func TestMissingDeviceReceiptRetriesAnotherChannelWithoutClaimingDelivery(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	body := taskBody("until_done")
	body["rule"] = map[string]any{"kind": "once", "mode": "until_done", "timezone": "Asia/Shanghai", "times": []string{"09:15"}, "start_date": "2026-10-03"}
	if c, out := request(t, a, "POST", "/api/v1/tasks", token, body); c != 201 {
		t.Fatal(out)
	}
	enablePhone(t, a, token, []string{"notification", "alarm"})
	out := phoneSync(t, a, token, "phone", nil)
	plan := out["plans"].([]any)[0].(map[string]any)
	phoneSync(t, a, token, "phone", []map[string]string{{"plan_id": plan["id"].(string), "status": "scheduled"}})
	*now = time.Date(2026, 10, 3, 9, 25, 0, 0, now.Location())
	out = phoneSync(t, a, token, "phone", nil)
	if len(out["plans"].([]any)) == 0 || out["plans"].([]any)[0].(map[string]any)["channel"] != "alarm" {
		t.Fatal("missing trigger receipt never retried")
	}
	var status, sent string
	a.db.QueryRow(`SELECT status,sent_at FROM reminder_plans WHERE id=?`, plan["id"]).Scan(&status, &sent)
	if status != "unconfirmed" || sent != "" {
		t.Fatalf("unknown result misrepresented: %s %s", status, sent)
	}
}
func phoneSync(t *testing.T, a *App, token, device string, results []map[string]string) map[string]any {
	t.Helper()
	c, out := request(t, a, "POST", "/api/v1/reminders/sync", token, map[string]any{"device_id": device, "capabilities": map[string]bool{"notification": true, "calendar": true, "alarm": true, "exact_alarm": true}, "results": results, "timezone": "Asia/Shanghai"})
	if c != 200 {
		t.Fatalf("sync %d %+v", c, out)
	}
	return out
}
func TestPhonePlansAreStableAndCompletionCancels(t *testing.T) {
	a, m, _ := setup(t)
	token := login(t, a, m, "one@example.com")
	id := createTask(t, a, token, "until_done")
	initial := phoneSync(t, a, token, "phone", nil)
	if len(initial["plans"].([]any)) != 0 {
		t.Fatal("unconfigured sync took over legacy reminders")
	}
	enablePhone(t, a, token, []string{"notification", "calendar", "alarm"})
	out := phoneSync(t, a, token, "phone", nil)
	plans := out["plans"].([]any)
	if len(plans) == 0 {
		t.Fatal("no future device plans")
	}
	plan := plans[0].(map[string]any)
	if plan["source"] != "rules" || plan["channel"] != "notification" {
		t.Fatalf("unexpected decision %+v", plan)
	}
	again := phoneSync(t, a, token, "phone", nil)
	if again["plans"].([]any)[0].(map[string]any)["id"] != plan["id"] {
		t.Fatal("sync recreated plan")
	}
	phoneSync(t, a, token, "phone", []map[string]string{{"plan_id": plan["id"].(string), "status": "scheduled"}})
	_, history := request(t, a, "GET", "/api/v1/notifications", token, nil)
	if history["notifications"].([]any)[0].(map[string]any)["status"] == "sent" {
		t.Fatal("scheduled plan claimed delivery")
	}
	request(t, a, "POST", "/api/v1/tasks/"+id+"/complete", token, map[string]any{})
	done := phoneSync(t, a, token, "phone", nil)
	if len(done["plans"].([]any)) != 0 || len(done["cancel_ids"].([]any)) == 0 {
		t.Fatalf("completion did not cancel %+v", done)
	}
}
func TestPhoneFailedChannelSwitchAndAccountIsolation(t *testing.T) {
	a, m, _ := setup(t)
	one := login(t, a, m, "one@example.com")
	two := login(t, a, m, "two@example.com")
	createTask(t, a, one, "until_done")
	enablePhone(t, a, one, []string{"notification", "calendar"})
	out := phoneSync(t, a, one, "phone", nil)
	first := out["plans"].([]any)[0].(map[string]any)
	switched := phoneSync(t, a, one, "phone", []map[string]string{{"plan_id": first["id"].(string), "status": "failed", "error": "permission revoked"}})
	if len(switched["plans"].([]any)) == 0 || switched["plans"].([]any)[0].(map[string]any)["channel"] != "calendar" {
		t.Fatalf("failed channel not switched %+v", switched)
	}
	other := phoneSync(t, a, two, "phone", []map[string]string{{"plan_id": first["id"].(string), "status": "delivered"}})
	if len(other["plans"].([]any)) != 0 {
		t.Fatal("cross-account plans")
	}
}
func TestPhonePreferencesRespectDeadlineAndStopLegacyEmail(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	body := taskBody("until_done")
	body["rule"] = map[string]any{"kind": "minute_interval", "mode": "until_done", "timezone": "Asia/Shanghai", "first_at": stamp(now.Add(time.Minute)), "end_at": stamp(now.Add(11 * time.Minute)), "repeat_minutes": 1, "times": []string{}}
	c, _ := request(t, a, "POST", "/api/v1/tasks", token, body)
	if c != 201 {
		t.Fatal(c)
	}
	enablePhone(t, a, token, []string{"notification"})
	out := phoneSync(t, a, token, "phone", nil)
	plans := out["plans"].([]any)
	if len(plans) != 3 {
		t.Fatalf("frequency preference ignored: %+v", plans)
	}
	for _, raw := range plans {
		p := raw.(map[string]any)
		at, _ := time.Parse(time.RFC3339Nano, p["scheduled_at"].(string))
		if at.After(now.Add(11 * time.Minute)) {
			t.Fatal("deadline exceeded")
		}
	}
	m.messages = nil
	*now = now.Add(2 * time.Minute)
	if err := a.tick(context.Background()); err != nil {
		t.Fatal(err)
	}
	if len(m.messages) != 0 {
		t.Fatal("legacy email still sent after explicit phone takeover")
	}
	request(t, a, "PUT", "/api/v1/reminders/settings", token, map[string]any{"enabled": false, "channels": []string{"notification"}, "quiet_start": "", "quiet_end": "", "min_interval_minutes": 5})
	if disabled := phoneSync(t, a, token, "phone", nil); len(disabled["plans"].([]any)) != 0 {
		t.Fatal("disabled reminders remained active")
	}
}
func TestBackgroundDeviceCannotStealPrimary(t *testing.T) {
	a, m, _ := setup(t)
	token := login(t, a, m, "one@example.com")
	createTask(t, a, token, "until_done")
	enablePhone(t, a, token, []string{"notification"})
	phoneSync(t, a, token, "first", nil)
	second := phoneSync(t, a, token, "second", nil)
	if second["active_device"] != false || len(second["plans"].([]any)) != 0 {
		t.Fatal("background sync stole primary device")
	}
}

func TestReminderSettingsCanBeSavedAgainWithoutLosingPlans(t *testing.T) {
	a, m, _ := setup(t)
	token := login(t, a, m, "one@example.com")
	createTask(t, a, token, "until_done")
	enablePhone(t, a, token, []string{"notification"})
	phoneSync(t, a, token, "phone", nil)
	enablePhone(t, a, token, []string{"notification"})
	if out := phoneSync(t, a, token, "phone", nil); len(out["plans"].([]any)) == 0 {
		t.Fatal("saving the same preferences permanently removed reminders")
	}
}
func TestRevokedAllCapabilitiesCancelsDevicePlans(t *testing.T) {
	a, m, _ := setup(t)
	token := login(t, a, m, "one@example.com")
	createTask(t, a, token, "until_done")
	enablePhone(t, a, token, []string{"notification"})
	phoneSync(t, a, token, "phone", nil)
	c, out := request(t, a, "POST", "/api/v1/reminders/sync", token, map[string]any{"device_id": "phone", "capabilities": map[string]bool{}})
	if c != 200 || len(out["plans"].([]any)) != 0 || len(out["cancel_ids"].([]any)) == 0 {
		t.Fatalf("revoked plans survived %+v", out)
	}
}

func TestAgentCalendarUsesActualPlansAndDeliveryCounts(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	id := createTask(t, a, token, "notify_once")
	enablePhone(t, a, token, []string{"notification"})
	sync := phoneSync(t, a, token, "phone", nil)
	plan := sync["plans"].([]any)[0].(map[string]any)
	c, calendar := request(t, a, "GET", "/api/v1/calendar?month=2026-10&timezone=Asia%2FShanghai", token, nil)
	if c != 200 {
		t.Fatal(calendar)
	}
	found := false
	for _, raw := range calendar["entries"].([]any) {
		entry := raw.(map[string]any)
		at, _ := time.Parse(time.RFC3339Nano, entry["at"].(string))
		if entry["id"] == plan["id"] {
			found = true
			if entry["channel"] != "notification" {
				t.Fatal("missing channel")
			}
		}
		if at.After(*now) && entry["type"] == "planned" {
			t.Fatal("unexecuted synthetic reminder shown after phone takeover")
		}
	}
	if !found {
		t.Fatal("device plan missing in calendar")
	}
	at, _ := time.Parse(time.RFC3339Nano, plan["scheduled_at"].(string))
	*now = at
	phoneSync(t, a, token, "phone", []map[string]string{{"plan_id": plan["id"].(string), "status": "delivered"}})
	_, task := request(t, a, "GET", "/api/v1/tasks/"+id, token, nil)
	if task["status"] != "done" {
		t.Fatalf("notify_once delivery was not counted: %+v", task)
	}
}
