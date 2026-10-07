package app

import (
	"context"
	"encoding/json"
	"testing"
	"time"
)

func TestRetiredQuietSettingsAreAlwaysEmpty(t *testing.T) {
	a, m, _ := setup(t)
	token := login(t, a, m, "one@example.com")
	_, out := request(t, a, "GET", "/api/v1/reminders/settings", token, nil)
	if out["quiet_start"] != "" || out["quiet_end"] != "" {
		t.Fatalf("default quiet hours remain: %+v", out)
	}
	for _, start := range []string{"22:00", "invalid legacy value"} {
		code, out := request(t, a, "PUT", "/api/v1/reminders/settings", token, map[string]any{"enabled": true, "channels": []string{"notification"}, "min_interval_minutes": 5, "quiet_start": start, "quiet_end": ""})
		if code != 200 || out["quiet_start"] != "" || out["quiet_end"] != "" {
			t.Fatalf("old client fields were not ignored: %d %+v", code, out)
		}
		var raw string
		if err := a.db.QueryRow(`SELECT settings FROM reminder_settings`).Scan(&raw); err != nil {
			t.Fatal(err)
		}
		var saved reminderSettings
		if err := json.Unmarshal([]byte(raw), &saved); err != nil || saved.QuietStart != "" || saved.QuietEnd != "" {
			t.Fatalf("quiet preferences persisted: %s %v", raw, err)
		}
	}
}

// Model the persisted settings/generation written by 2.3, without depending on
// the new settings endpoint accepting an effective quiet-time preference.
func seedLegacyQuietSettings(t *testing.T, a *App, taskID string, caps reminderCapabilities) (string, string) {
	t.Helper()
	var user string
	if err := a.db.QueryRow(`SELECT user_id FROM tasks WHERE id=?`, taskID).Scan(&user); err != nil {
		t.Fatal(err)
	}
	s, device, err := a.reminderSettingsFor(user)
	if err != nil {
		t.Fatal(err)
	}
	s.QuietStart, s.QuietEnd = "22:00", "08:00"
	raw, _ := json.Marshal(s)
	if _, err := a.db.Exec(`UPDATE reminder_settings SET settings=? WHERE user_id=?`, string(raw), user); err != nil {
		t.Fatal(err)
	}
	task, err := a.findTask(taskID, user)
	if err != nil {
		t.Fatal(err)
	}
	// This is the frozen 2.3 generation input. The fields were effective then.
	legacy, _ := json.Marshal([]any{"sequential-v1", task.ID, task.Title, task.Description, task.Rule, task.UpdatedAt, task.SnoozeUntil, task.SnoozeCycle, s, s.Revision, device, availableReminderChannels(s, caps)})
	generation := digest(string(legacy))
	if _, err := a.db.Exec(`UPDATE reminder_strategies SET generation=? WHERE task_id=?`, generation, taskID); err != nil {
		t.Fatal(err)
	}
	return user, generation
}

func TestRetiredQuietLegacyMorningPlanIsReplacedOnce(t *testing.T) {
	a, m, now := setup(t)
	*now = time.Date(2026, 10, 3, 0, 10, 0, 0, now.Location())
	token := login(t, a, m, "one@example.com")
	id := createDenseReminder(t, a, token, *now)
	enablePhone(t, a, token, []string{"notification"})
	out := phoneSync(t, a, token, "phone", nil)
	old := out["plans"].([]any)[0].(map[string]any)
	_, generation := seedLegacyQuietSettings(t, a, id, reminderCapabilities{Notification: true, Calendar: true, Alarm: true, ExactAlarm: true})
	morning := time.Date(2026, 10, 3, 8, 0, 0, 0, now.Location())
	if _, err := a.db.Exec(`UPDATE reminder_plans SET generation=?,scheduled_at=?,expires_at=?,status='scheduled' WHERE id=?`, generation, stamp(morning), stamp(morning.Add(5*time.Minute)), old["id"]); err != nil {
		t.Fatal(err)
	}
	out = phoneSync(t, a, token, "phone", nil)
	plans := out["plans"].([]any)
	if len(plans) != 1 {
		t.Fatalf("expected one replacement: %+v", out)
	}
	p := plans[0].(map[string]any)
	at, _ := time.Parse(time.RFC3339Nano, p["scheduled_at"].(string))
	if p["id"] == old["id"] || !at.Equal(now.Add(time.Minute)) {
		t.Fatalf("quiet-delayed plan survived: %+v", p)
	}
	var cancelled string
	if err := a.db.QueryRow(`SELECT status FROM reminder_plans WHERE id=?`, old["id"]).Scan(&cancelled); err != nil || cancelled != "cancelled" {
		t.Fatalf("old OS schedule not revoked: %s %v", cancelled, err)
	}
	out = phoneSync(t, a, token, "phone", nil)
	if len(out["plans"].([]any)) != 1 || out["plans"].([]any)[0].(map[string]any)["id"] != p["id"] {
		t.Fatal("repeat sync created another replacement")
	}
	*now = at
	out = phoneSync(t, a, token, "phone", []map[string]string{{"plan_id": p["id"].(string), "status": "delivered"}})
	next, _ := time.Parse(time.RFC3339Nano, out["plans"].([]any)[0].(map[string]any)["scheduled_at"].(string))
	if next.Before(now.Add(5*time.Minute)) || !next.Before(morning) {
		t.Fatalf("follow-up violated minimum wait or retained quiet hours: %s", next)
	}
	var successes int
	if err := a.db.QueryRow(`SELECT COUNT(*) FROM reminder_plans WHERE task_id=? AND status='delivered'`, id).Scan(&successes); err != nil || successes != 1 {
		t.Fatalf("history duplicated: %d %v", successes, err)
	}
}

func TestRetiredQuietFlowWaitsForOwnerCancellationBeforeReplanning(t *testing.T) {
	a, m, now := setup(t)
	*now = time.Date(2026, 10, 3, 0, 10, 0, 0, now.Location())
	token := login(t, a, m, "one@example.com")
	id := createDenseReminder(t, a, token, *now)
	enablePhone(t, a, token, []string{"notification", "alarm", "email"})
	out := localFlowSync(t, a, token, "phone", nil, nil)
	oldID := out["local_flows"].([]any)[0].(map[string]any)["id"].(string)
	_, generation := seedLegacyQuietSettings(t, a, id, reminderCapabilities{Notification: true, Alarm: true, ExactAlarm: true, LocalFlowV1: true})
	var raw string
	if err := a.db.QueryRow(`SELECT blueprint FROM reminder_local_flows WHERE id=?`, oldID).Scan(&raw); err != nil {
		t.Fatal(err)
	}
	var f localFlow
	if err := json.Unmarshal([]byte(raw), &f); err != nil {
		t.Fatal(err)
	}
	f.Generation, f.QuietStart, f.QuietEnd = generation, "22:00", "08:00"
	morning := time.Date(2026, 10, 3, 8, 0, 0, 0, now.Location())
	first, _ := time.Parse(time.RFC3339Nano, f.Steps[0].ScheduledAt)
	shift := morning.Sub(first)
	for i := range f.Steps {
		at, _ := time.Parse(time.RFC3339Nano, f.Steps[i].ScheduledAt)
		expires, _ := time.Parse(time.RFC3339Nano, f.Steps[i].ExpiresAt)
		f.Steps[i].ScheduledAt, f.Steps[i].ExpiresAt = localFlowStamp(at.Add(shift)), localFlowStamp(expires.Add(shift))
	}
	f.ValidUntil = f.Steps[len(f.Steps)-1].ExpiresAt
	blueprint, _ := json.Marshal(f)
	if _, err := a.db.Exec(`UPDATE reminder_local_flows SET generation=?,valid_until=?,blueprint=? WHERE id=?`, generation, f.ValidUntil, string(blueprint), oldID); err != nil {
		t.Fatal(err)
	}
	if _, err := a.db.Exec(`UPDATE reminder_plans SET generation=?,scheduled_at=?,expires_at=?,status='scheduled' WHERE id=?`, generation, stamp(morning), stamp(morning.Add(5*time.Minute)), f.RootPlanID); err != nil {
		t.Fatal(err)
	}
	out = localFlowSync(t, a, token, "phone", nil, nil)
	if len(out["local_flows"].([]any)) != 0 || len(out["revoked_flow_ids"].([]any)) != 1 || out["revoked_flow_ids"].([]any)[0] != oldID {
		t.Fatalf("old flow not safely revoked: %+v", out)
	}
	if err := a.tickReminderAgent(context.Background()); err != nil {
		t.Fatal(err)
	}
	var pending int
	if err := a.db.QueryRow(`SELECT COUNT(*) FROM reminder_plans WHERE task_id=? AND status IN ('pending','sending','scheduled')`, id).Scan(&pending); err != nil || pending != 0 {
		t.Fatalf("server overlapped unacknowledged old device: %d %v", pending, err)
	}
	out = localFlowSync(t, a, token, "phone", nil, []string{oldID})
	flows := out["local_flows"].([]any)
	if len(flows) != 1 {
		t.Fatalf("flow did not resume after owner cancellation: %+v", out)
	}
	newFlow := flows[0].(map[string]any)
	at, _ := time.Parse(time.RFC3339Nano, newFlow["steps"].([]any)[0].(map[string]any)["scheduled_at"].(string))
	if newFlow["id"] == oldID || newFlow["quiet_start"] != "" || newFlow["quiet_end"] != "" || !at.Equal(now.Add(time.Minute)) {
		t.Fatalf("quiet-delayed authorization survived: %+v", newFlow)
	}
}

func TestRetiredQuietSnapshotCannotRejectActualFlowReceipt(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	createDenseReminder(t, a, token, *now)
	enablePhone(t, a, token, []string{"notification", "alarm"})
	out := localFlowSync(t, a, token, "phone", nil, nil)
	f := out["local_flows"].([]any)[0].(map[string]any)
	first := f["steps"].([]any)[0].(map[string]any)
	f["quiet_start"], f["quiet_end"] = "09:00", "10:00"
	raw, _ := json.Marshal(f)
	if _, err := a.db.Exec(`UPDATE reminder_local_flows SET blueprint=? WHERE id=?`, string(raw), f["id"]); err != nil {
		t.Fatal(err)
	}
	*now, _ = time.Parse(time.RFC3339Nano, first["scheduled_at"].(string))
	out = localFlowSync(t, a, token, "phone", []map[string]any{{"flow_id": f["id"], "step_id": first["id"], "status": "delivered", "occurred_at": first["scheduled_at"]}}, nil)
	if out["flow_result_acks"].([]any)[0].(map[string]any)["accepted"] != true {
		t.Fatalf("retired quiet policy rejected actual delivery: %+v", out["flow_result_acks"])
	}
}

func TestRetiredQuietDoesNotBlockNightEmail(t *testing.T) {
	a, m, now := setup(t)
	*now = time.Date(2026, 10, 3, 0, 10, 0, 0, now.Location())
	token := login(t, a, m, "one@example.com")
	id := createDenseReminder(t, a, token, *now)
	code, out := request(t, a, "PUT", "/api/v1/reminders/settings", token, map[string]any{"enabled": true, "channels": []string{"email"}, "min_interval_minutes": 5, "quiet_start": "22:00", "quiet_end": "08:00"})
	if code != 200 {
		t.Fatal(out)
	}
	phoneSync(t, a, token, "phone", nil)
	var scheduled string
	if err := a.db.QueryRow(`SELECT scheduled_at FROM reminder_plans WHERE task_id=? AND status='pending'`, id).Scan(&scheduled); err != nil {
		t.Fatal(err)
	}
	at, _ := time.Parse(time.RFC3339Nano, scheduled)
	if !at.Equal(now.Add(time.Minute)) {
		t.Fatalf("night email deferred: %s", scheduled)
	}
	before := len(m.messages)
	*now = at
	if err := a.tickReminderAgent(context.Background()); err != nil {
		t.Fatal(err)
	}
	if len(m.messages) != before+1 {
		t.Fatal("night email blocked at execution")
	}
	if err := a.tickReminderAgent(context.Background()); err != nil {
		t.Fatal(err)
	}
	if len(m.messages) != before+1 {
		t.Fatal("same night email executed twice")
	}
}

func TestRetiredQuietMigrationKeepsDueLegacyReceiptWindow(t *testing.T) {
	for _, mode := range []string{"notify_once", "until_done"} {
		for _, status := range []string{"pending", "scheduled"} {
			t.Run(mode+"/"+status, func(t *testing.T) {
				a, m, now := setup(t)
				token := login(t, a, m, "one@example.com")
				body := taskBody(mode)
				body["rule"] = map[string]any{"kind": "minute_interval", "mode": mode, "timezone": "Asia/Shanghai", "first_at": stamp(now.Add(time.Minute)), "repeat_minutes": 5, "max_reminders": 1, "times": []string{}}
				if mode == "notify_once" {
					body["rule"] = map[string]any{"kind": "once", "mode": mode, "timezone": "Asia/Shanghai", "start_date": "2026-10-03", "times": []string{"09:15"}}
				}
				code, created := request(t, a, "POST", "/api/v1/tasks", token, body)
				if code != 201 {
					t.Fatal(created)
				}
				id := created["id"].(string)
				enablePhone(t, a, token, []string{"notification"})
				out := phoneSync(t, a, token, "phone", nil)
				old := out["plans"].([]any)[0].(map[string]any)
				_, generation := seedLegacyQuietSettings(t, a, id, reminderCapabilities{Notification: true, Calendar: true, Alarm: true, ExactAlarm: true})
				if _, err := a.db.Exec(`UPDATE reminder_plans SET generation=?,status=? WHERE id=?`, generation, status, old["id"]); err != nil {
					t.Fatal(err)
				}
				at, _ := time.Parse(time.RFC3339Nano, old["scheduled_at"].(string))
				*now = at.Add(time.Minute)
				// The old phone already fired while offline. Migration must not
				// cancel this live receipt window and schedule a second reminder.
				if err := a.tickReminderAgent(context.Background()); err != nil {
					t.Fatal(err)
				}
				var current string
				if err := a.db.QueryRow(`SELECT status FROM reminder_plans WHERE id=?`, old["id"]).Scan(&current); err != nil || current != status {
					t.Fatalf("migration erased live receipt window: %s %v", current, err)
				}
				var count int
				if err := a.db.QueryRow(`SELECT COUNT(*) FROM reminder_plans WHERE task_id=?`, id).Scan(&count); err != nil || count != 1 {
					t.Fatalf("migration scheduled duplicate: %d %v", count, err)
				}
				*now = now.Add(time.Minute)
				out = phoneSync(t, a, token, "phone", []map[string]string{{"plan_id": old["id"].(string), "status": "delivered"}})
				if err := a.db.QueryRow(`SELECT status FROM reminder_plans WHERE id=?`, old["id"]).Scan(&current); err != nil || current != "delivered" {
					t.Fatalf("late actual delivery was lost: %s %v", current, err)
				}
				if len(out["plans"].([]any)) != 0 {
					t.Fatalf("migration repeated capped reminder: %+v", out["plans"])
				}
				if err := a.tickReminderAgent(context.Background()); err != nil {
					t.Fatal(err)
				}
				if err := a.db.QueryRow(`SELECT COUNT(*) FROM reminder_plans WHERE task_id=?`, id).Scan(&count); err != nil || count != 1 {
					t.Fatalf("late receipt created another execution: %d %v", count, err)
				}
			})
		}
	}
}

func TestRetiredQuietMigrationDoesNotProtectChangedTask(t *testing.T) {
	for _, action := range []string{"edit", "pause"} {
		t.Run(action, func(t *testing.T) {
			a, m, now := setup(t)
			token := login(t, a, m, "one@example.com")
			id := createDenseReminder(t, a, token, *now)
			enablePhone(t, a, token, []string{"notification"})
			out := phoneSync(t, a, token, "phone", nil)
			old := out["plans"].([]any)[0].(map[string]any)
			_, generation := seedLegacyQuietSettings(t, a, id, reminderCapabilities{Notification: true, Calendar: true, Alarm: true, ExactAlarm: true})
			if _, err := a.db.Exec(`UPDATE reminder_plans SET generation=?,status='scheduled' WHERE id=?`, generation, old["id"]); err != nil {
				t.Fatal(err)
			}
			at, _ := time.Parse(time.RFC3339Nano, old["scheduled_at"].(string))
			*now = at.Add(time.Minute)
			statement := `UPDATE tasks SET title='edited task',updated_at=? WHERE id=?`
			if action == "pause" {
				statement = `UPDATE tasks SET paused=1,updated_at=? WHERE id=?`
			}
			if _, err := a.db.Exec(statement, stamp(*now), id); err != nil {
				t.Fatal(err)
			}
			if err := a.tickReminderAgent(context.Background()); err != nil {
				t.Fatal(err)
			}
			var status string
			if err := a.db.QueryRow(`SELECT status FROM reminder_plans WHERE id=?`, old["id"]).Scan(&status); err != nil || status != "cancelled" {
				t.Fatalf("migration kept changed task's old plan: %s %v", status, err)
			}
		})
	}
}
