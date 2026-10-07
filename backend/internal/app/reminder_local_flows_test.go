package app

import (
	"context"
	"testing"
	"time"
)

func localFlowSync(t *testing.T, a *App, token, device string, results []map[string]any, closed []string) map[string]any {
	t.Helper()
	code, out := request(t, a, "POST", "/api/v1/reminders/sync", token, map[string]any{
		"device_id": device, "capabilities": map[string]bool{"notification": true, "alarm": true, "exact_alarm": true, "local_flow_v1": true},
		"flow_results": results, "closed_flow_ids": closed, "timezone": "Asia/Shanghai",
	})
	if code != 200 {
		t.Fatalf("local sync %d: %+v", code, out)
	}
	return out
}

func TestLocalFlowBlueprintIsStableWithoutPrecreatingExecutionRows(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	id := createDenseReminder(t, a, token, *now)
	enablePhone(t, a, token, []string{"notification", "alarm"})
	first := localFlowSync(t, a, token, "phone", nil, nil)
	flows, ok := first["local_flows"].([]any)
	if !ok || len(flows) != 1 {
		t.Fatalf("missing finite flow: %+v", first)
	}
	flow := flows[0].(map[string]any)
	steps := flow["steps"].([]any)
	if len(steps) != 3 {
		t.Fatalf("expected only original three strategy steps: %+v", steps)
	}
	if steps[0].(map[string]any)["id"] != flow["root_plan_id"] {
		t.Fatal("root plan identity changed")
	}
	second := localFlowSync(t, a, token, "phone", nil, nil)
	if second["local_flows"].([]any)[0].(map[string]any)["id"] != flow["id"] {
		t.Fatal("unchanged sync recreated flow")
	}
	var count int
	if err := a.db.QueryRow(`SELECT COUNT(*) FROM reminder_plans WHERE task_id=?`, id).Scan(&count); err != nil || count != 1 {
		t.Fatalf("blueprint materialized %d rows: %v", count, err)
	}
}

func TestLocalFlowLeaseSuppressesServerFollowupsUntilClosed(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	id := createDenseReminder(t, a, token, *now)
	enablePhone(t, a, token, []string{"notification", "alarm", "email"})
	out := localFlowSync(t, a, token, "phone", nil, nil)
	flow := out["local_flows"].([]any)[0].(map[string]any)
	steps := flow["steps"].([]any)
	if len(steps) != 2 {
		t.Fatalf("flow crossed email boundary: %+v", steps)
	}
	*now, _ = time.Parse(time.RFC3339Nano, steps[0].(map[string]any)["expires_at"].(string))
	*now = now.Add(time.Second)
	if err := a.tickReminderAgent(context.Background()); err != nil {
		t.Fatal(err)
	}
	var count int
	a.db.QueryRow(`SELECT COUNT(*) FROM reminder_plans WHERE task_id=?`, id).Scan(&count)
	if count != 1 {
		t.Fatalf("server advanced during offline lease: %d", count)
	}
}

func TestLocalFlowOfflineReceiptsAreIdempotentAndAccountScoped(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	id := createDenseReminder(t, a, token, *now)
	enablePhone(t, a, token, []string{"notification", "alarm"})
	out := localFlowSync(t, a, token, "phone", nil, nil)
	flow := out["local_flows"].([]any)[0].(map[string]any)
	steps := flow["steps"].([]any)
	results := []map[string]any{}
	for _, raw := range steps {
		step := raw.(map[string]any)
		results = append(results, map[string]any{"flow_id": flow["id"], "step_id": step["id"], "status": "delivered", "occurred_at": step["scheduled_at"]})
	}
	*now, _ = time.Parse(time.RFC3339Nano, steps[len(steps)-1].(map[string]any)["scheduled_at"].(string))
	other := login(t, a, m, "other@example.com")
	forged := localFlowSync(t, a, other, "phone", results, nil)
	for _, raw := range forged["flow_result_acks"].([]any) {
		if raw.(map[string]any)["accepted"] == true {
			t.Fatal("other account forged flow receipt")
		}
	}
	for i := 0; i < 2; i++ {
		out = localFlowSync(t, a, token, "phone", results, []string{flow["id"].(string)})
		for _, raw := range out["flow_result_acks"].([]any) {
			if raw.(map[string]any)["accepted"] != true {
				t.Fatalf("valid receipt rejected: %+v", raw)
			}
		}
	}
	var count int
	a.db.QueryRow(`SELECT COUNT(*) FROM reminder_plans WHERE task_id=? AND status='delivered'`, id).Scan(&count)
	if count != len(steps) {
		t.Fatalf("duplicate receipt count=%d, want %d", count, len(steps))
	}
	_, detail := request(t, a, "GET", "/api/v1/tasks/"+id, token, nil)
	if detail["status"] == "done" {
		t.Fatal("notification receipt completed task")
	}
}

func TestLocalFlowTaskChangesRevokeFiniteAuthorization(t *testing.T) {
	for _, change := range []string{"pause", "complete", "edit", "claim"} {
		t.Run(change, func(t *testing.T) {
			a, m, now := setup(t)
			token := login(t, a, m, "one@example.com")
			id := createDenseReminder(t, a, token, *now)
			enablePhone(t, a, token, []string{"notification", "alarm"})
			out := localFlowSync(t, a, token, "phone", nil, nil)
			flow := out["local_flows"].([]any)[0].(map[string]any)
			switch change {
			case "pause":
				if c, o := request(t, a, "PUT", "/api/v1/tasks/"+id+"/paused", token, map[string]any{"paused": true}); c != 200 {
					t.Fatalf("pause %d %+v", c, o)
				}
			case "complete":
				if c, o := request(t, a, "POST", "/api/v1/tasks/"+id+"/complete", token, map[string]any{}); c != 200 {
					t.Fatalf("complete %d %+v", c, o)
				}
			case "edit":
				body := taskBody("until_done")
				body["rule"] = map[string]any{"kind": "minute_interval", "mode": "until_done", "timezone": "Asia/Shanghai", "first_at": stamp(now.Add(time.Hour)), "repeat_minutes": 5, "times": []string{}}
				if c, o := request(t, a, "PUT", "/api/v1/tasks/"+id, token, body); c != 200 {
					t.Fatalf("edit %d %+v", c, o)
				}
			case "claim":
				if c, o := request(t, a, "POST", "/api/v1/reminders/sync", token, map[string]any{"device_id": "new-phone", "claim": true, "capabilities": map[string]bool{"notification": true, "alarm": true, "exact_alarm": true, "local_flow_v1": true}}); c != 200 {
					t.Fatalf("claim %d %+v", c, o)
				}
			}
			out = localFlowSync(t, a, token, "phone", nil, nil)
			if len(out["local_flows"].([]any)) != 0 {
				t.Fatal("changed task retained local authority")
			}
			var state string
			a.db.QueryRow(`SELECT state FROM reminder_local_flows WHERE id=?`, flow["id"]).Scan(&state)
			if state != "revoked" {
				t.Fatalf("state=%s", state)
			}
			if change == "edit" || change == "claim" {
				var count int
				a.db.QueryRow(`SELECT COUNT(*) FROM reminder_plans WHERE task_id=? AND status IN ('pending','scheduled')`, id).Scan(&count)
				if count != 0 {
					t.Fatal("replacement ran before old offline device released its authorization")
				}
			}
		})
	}
}

func TestLocalFlowExpiresThenServerCanResume(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	id := createDenseReminder(t, a, token, *now)
	enablePhone(t, a, token, []string{"notification", "alarm"})
	out := localFlowSync(t, a, token, "phone", nil, nil)
	flow := out["local_flows"].([]any)[0].(map[string]any)
	*now, _ = time.Parse(time.RFC3339Nano, flow["valid_until"].(string))
	*now = now.Add(time.Second)
	if err := a.tickReminderAgent(context.Background()); err != nil {
		t.Fatal(err)
	}
	var pending int
	a.db.QueryRow(`SELECT COUNT(*) FROM reminder_plans WHERE task_id=? AND status='pending'`, id).Scan(&pending)
	if pending != 1 {
		t.Fatalf("expiry did not resume one server plan: %d", pending)
	}
}

func TestLocalFlowRejectsEarlyOutOfOrderAndExpiredDelivery(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	createDenseReminder(t, a, token, *now)
	enablePhone(t, a, token, []string{"notification", "alarm"})
	out := localFlowSync(t, a, token, "phone", nil, nil)
	flow := out["local_flows"].([]any)[0].(map[string]any)
	steps := flow["steps"].([]any)
	first := steps[0].(map[string]any)
	second := steps[1].(map[string]any)
	*now, _ = time.Parse(time.RFC3339Nano, second["scheduled_at"].(string))
	for _, test := range []struct {
		step map[string]any
		at   string
	}{{second, second["scheduled_at"].(string)}, {first, flow["issued_at"].(string)}, {first, first["expires_at"].(string)}} {
		out = localFlowSync(t, a, token, "phone", []map[string]any{{"flow_id": flow["id"], "step_id": test.step["id"], "status": "delivered", "occurred_at": test.at}}, nil)
		if out["flow_result_acks"].([]any)[0].(map[string]any)["accepted"] == true {
			t.Fatal("invalid delivery accepted")
		}
	}
	out = localFlowSync(t, a, token, "phone", []map[string]any{{"flow_id": flow["id"], "step_id": first["id"], "status": "failed", "occurred_at": localFlowStamp(*now), "error": "错过授权时段，本次未提醒"}}, nil)
	if out["flow_result_acks"].([]any)[0].(map[string]any)["accepted"] != true {
		t.Fatal("honest expired failure cannot drain outbox")
	}
}

func TestLocalFlowHonorsDeadlineNotifyOnceAndLegacyClients(t *testing.T) {
	t.Run("legacy", func(t *testing.T) {
		a, m, now := setup(t)
		token := login(t, a, m, "one@example.com")
		createDenseReminder(t, a, token, *now)
		enablePhone(t, a, token, []string{"notification", "alarm"})
		out := phoneSync(t, a, token, "phone", nil)
		if len(out["plans"].([]any)) != 1 || len(out["local_flows"].([]any)) != 0 {
			t.Fatal("legacy client was delegated offline work")
		}
	})
	t.Run("notify_once", func(t *testing.T) {
		a, m, _ := setup(t)
		token := login(t, a, m, "one@example.com")
		createTask(t, a, token, "notify_once")
		enablePhone(t, a, token, []string{"notification", "alarm"})
		out := localFlowSync(t, a, token, "phone", nil, nil)
		f := out["local_flows"].([]any)[0].(map[string]any)
		if len(f["steps"].([]any)) != 1 || f["stop_after_success"] != true || f["remaining_reminders"] != float64(1) {
			t.Fatal("notify_once received repeated offline authority")
		}
	})
	t.Run("obsolete_quiet_ignored_deadline_kept", func(t *testing.T) {
		a, m, now := setup(t)
		token := login(t, a, m, "one@example.com")
		body := taskBody("until_done")
		deadline := now.Add(11 * time.Minute)
		body["rule"] = map[string]any{"kind": "minute_interval", "mode": "until_done", "timezone": "Asia/Shanghai", "first_at": stamp(now.Add(time.Minute)), "end_at": stamp(deadline), "repeat_minutes": 1, "times": []string{}}
		if c, o := request(t, a, "POST", "/api/v1/tasks", token, body); c != 201 {
			t.Fatal(o)
		}
		if c, o := request(t, a, "PUT", "/api/v1/reminders/settings", token, map[string]any{"enabled": true, "channels": []string{"notification", "alarm"}, "quiet_start": "09:14", "quiet_end": "09:18", "min_interval_minutes": 1, "timezone": "Asia/Shanghai"}); c != 200 {
			t.Fatal(o)
		}
		out := localFlowSync(t, a, token, "phone", nil, nil)
		f := out["local_flows"].([]any)[0].(map[string]any)
		if f["quiet_start"] != "" || f["quiet_end"] != "" {
			t.Fatal("obsolete quiet hours in new authorization")
		}
		for _, raw := range f["steps"].([]any) {
			s := raw.(map[string]any)
			at, _ := time.Parse(time.RFC3339Nano, s["scheduled_at"].(string))
			if at.After(deadline) {
				t.Fatalf("invalid authorized time: %+v", s)
			}
		}
	})
}

func TestLocalFlowLegacySentMailStillConsumesCycleBudget(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	body := taskBody("until_done")
	body["rule"] = map[string]any{"kind": "minute_interval", "mode": "until_done", "timezone": "Asia/Shanghai", "first_at": stamp(now.Add(time.Minute)), "repeat_minutes": 5, "max_reminders": 1, "times": []string{}}
	c, created := request(t, a, "POST", "/api/v1/tasks", token, body)
	if c != 201 {
		t.Fatal(created)
	}
	id := created["id"].(string)
	enablePhone(t, a, token, []string{"notification", "alarm"})
	out := localFlowSync(t, a, token, "phone", nil, nil)
	f := out["local_flows"].([]any)[0].(map[string]any)
	s := f["steps"].([]any)[0].(map[string]any)
	*now, _ = time.Parse(time.RFC3339Nano, s["scheduled_at"].(string))
	if _, err := a.db.Exec(`INSERT INTO notifications(id,user_id,task_id,task_title,cycle_key,scheduled_at,sent_at,status,rule_snapshot) SELECT 'old-sent',user_id,id,title,'minute_interval',?,?,'sent',rule FROM tasks WHERE id=?`, stamp(now.Add(-time.Hour)), stamp(now.Add(-time.Hour)), id); err != nil {
		t.Fatal(err)
	}
	out = localFlowSync(t, a, token, "phone", []map[string]any{{"flow_id": f["id"], "step_id": s["id"], "status": "delivered", "occurred_at": s["scheduled_at"]}}, nil)
	if out["flow_result_acks"].([]any)[0].(map[string]any)["accepted"] == true {
		t.Fatal("old mail budget bypassed")
	}
}

func TestLocalFlowLateSuccessAfterDeviceClaimOrDetachRemainsHistory(t *testing.T) {
	for _, action := range []string{"claim", "detach"} {
		t.Run(action, func(t *testing.T) {
			a, m, now := setup(t)
			token := login(t, a, m, "one@example.com")
			id := createDenseReminder(t, a, token, *now)
			enablePhone(t, a, token, []string{"notification", "alarm"})
			out := localFlowSync(t, a, token, "phone", nil, nil)
			f := out["local_flows"].([]any)[0].(map[string]any)
			s := f["steps"].([]any)[0].(map[string]any)
			*now, _ = time.Parse(time.RFC3339Nano, s["scheduled_at"].(string))
			results := []map[string]any{{"flow_id": f["id"], "step_id": s["id"], "status": "delivered", "occurred_at": s["scheduled_at"]}}
			if action == "claim" {
				if c, o := request(t, a, "POST", "/api/v1/reminders/sync", token, map[string]any{"device_id": "new-phone", "claim": true, "capabilities": map[string]bool{"notification": true, "local_flow_v1": true}}); c != 200 {
					t.Fatal(o)
				}
				out = localFlowSync(t, a, token, "phone", results, nil)
			} else {
				var c int
				c, out = request(t, a, "POST", "/api/v1/reminders/sync", token, map[string]any{"device_id": "phone", "detach": true, "capabilities": map[string]bool{}, "flow_results": results})
				if c != 200 {
					t.Fatal(out)
				}
			}
			if out["flow_result_acks"].([]any)[0].(map[string]any)["accepted"] != true {
				t.Fatalf("honest late receipt lost: %+v", out)
			}
			if len(out["local_flows"].([]any)) != 0 {
				t.Fatal("old device flow reactivated")
			}
			var status string
			a.db.QueryRow(`SELECT status FROM reminder_plans WHERE id=?`, s["id"]).Scan(&status)
			if status != "delivered" {
				t.Fatal("actual history missing")
			}
			var complete int
			a.db.QueryRow(`SELECT COUNT(*) FROM completions WHERE task_id=?`, id).Scan(&complete)
			if complete != 0 {
				t.Fatal("receipt completed task")
			}
		})
	}
}

func TestLocalFlowTaskShowsNextStepWithoutMaterializingIt(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	id := createDenseReminder(t, a, token, *now)
	enablePhone(t, a, token, []string{"notification", "alarm"})
	out := localFlowSync(t, a, token, "phone", nil, nil)
	f := out["local_flows"].([]any)[0].(map[string]any)
	steps := f["steps"].([]any)
	first := steps[0].(map[string]any)
	*now, _ = time.Parse(time.RFC3339Nano, first["scheduled_at"].(string))
	out = localFlowSync(t, a, token, "phone", []map[string]any{{"flow_id": f["id"], "step_id": first["id"], "status": "delivered", "occurred_at": first["scheduled_at"]}}, nil)
	returned := out["local_flows"].([]any)[0].(map[string]any)["steps"].([]any)[0].(map[string]any)
	if returned["occurred_at"] != first["scheduled_at"] {
		t.Fatal("actual restore time missing")
	}
	_, detail := request(t, a, "GET", "/api/v1/tasks/"+id, token, nil)
	if detail["next_reminder"] != steps[1].(map[string]any)["scheduled_at"] {
		t.Fatalf("offline next step hidden: %+v", detail)
	}
	var count int
	a.db.QueryRow(`SELECT COUNT(*) FROM reminder_plans WHERE task_id=?`, id).Scan(&count)
	if count != 1 {
		t.Fatal("reading next time created future history")
	}
}

func TestLocalFlowWaitStartsAtActualPreviousResult(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	createDenseReminder(t, a, token, *now)
	enablePhone(t, a, token, []string{"notification", "alarm"})
	out := localFlowSync(t, a, token, "phone", nil, nil)
	f := out["local_flows"].([]any)[0].(map[string]any)
	steps := f["steps"].([]any)
	first, second := steps[0].(map[string]any), steps[1].(map[string]any)
	firstAt, _ := time.Parse(time.RFC3339Nano, first["scheduled_at"].(string))
	actual := firstAt.Add(30 * time.Second)
	*now = actual
	localFlowSync(t, a, token, "phone", []map[string]any{{"flow_id": f["id"], "step_id": first["id"], "status": "delivered", "occurred_at": localFlowStamp(actual)}}, nil)
	*now, _ = time.Parse(time.RFC3339Nano, second["scheduled_at"].(string))
	result := map[string]any{"flow_id": f["id"], "step_id": second["id"], "status": "delivered", "occurred_at": localFlowStamp(*now)}
	out = localFlowSync(t, a, token, "phone", []map[string]any{result}, nil)
	if out["flow_result_acks"].([]any)[0].(map[string]any)["accepted"] == true {
		t.Fatal("planned time bypassed real predecessor wait")
	}
	*now = now.Add(30 * time.Second)
	result["occurred_at"] = localFlowStamp(*now)
	out = localFlowSync(t, a, token, "phone", []map[string]any{result}, nil)
	if out["flow_result_acks"].([]any)[0].(map[string]any)["accepted"] != true {
		t.Fatal("valid deferred step rejected")
	}
}

func TestLocalFlowDeviceAuthorizationDoesNotExceed128Steps(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	enablePhone(t, a, token, []string{"notification", "alarm"})
	for i := 0; i < 43; i++ {
		createDenseReminder(t, a, token, *now)
	}
	out := localFlowSync(t, a, token, "phone", nil, nil)
	total := 0
	for _, raw := range out["local_flows"].([]any) {
		total += len(raw.(map[string]any)["steps"].([]any))
	}
	if total != 128 {
		t.Fatalf("authorized steps=%d", total)
	}
	out = localFlowSync(t, a, token, "phone", nil, nil)
	total = 0
	for _, raw := range out["local_flows"].([]any) {
		total += len(raw.(map[string]any)["steps"].([]any))
	}
	if total != 128 {
		t.Fatalf("repeat sync overcommitted device: %d", total)
	}
}

func TestLocalFlowCancelledAlarmAdvancesOnlyAfterActualReceipt(t *testing.T) {
	for _, received := range []bool{true, false} {
		name := "server_cancelled_without_receipt"
		if received {
			name = "received_user_cancelled"
		}
		t.Run(name, func(t *testing.T) {
			a, m, now := setup(t)
			token := login(t, a, m, "one@example.com")
			id := createDenseReminder(t, a, token, *now)
			enablePhone(t, a, token, []string{"notification", "alarm", "email"})
			out := localFlowSync(t, a, token, "phone", nil, nil)
			f := out["local_flows"].([]any)[0].(map[string]any)
			steps := f["steps"].([]any)
			first, alarm := steps[0].(map[string]any), steps[1].(map[string]any)
			*now, _ = time.Parse(time.RFC3339Nano, first["scheduled_at"].(string))
			localFlowSync(t, a, token, "phone", []map[string]any{{"flow_id": f["id"], "step_id": first["id"], "status": "delivered", "occurred_at": first["scheduled_at"]}}, nil)
			*now, _ = time.Parse(time.RFC3339Nano, alarm["scheduled_at"].(string))
			*now = now.Add(30 * time.Second)
			var results []map[string]any
			if received {
				results = []map[string]any{{"flow_id": f["id"], "step_id": alarm["id"], "status": "cancelled", "occurred_at": localFlowStamp(*now)}}
			} else {
				// A server-revoked row may have the same authorized ID and a
				// reported_at, but no actual execution receipt from the phone.
				_, err := a.db.Exec(`INSERT INTO reminder_plans(id,user_id,task_id,device_id,title,body,cycle_key,channel,scheduled_at,expires_at,status,source,reason,error,generation,sent_at,reported_at,rule_snapshot) SELECT ?,user_id,task_id,device_id,title,body,cycle_key,'alarm',?,?,'cancelled',source,reason,'server revoked',generation,'',?,rule_snapshot FROM reminder_plans WHERE id=?`, alarm["id"], alarm["scheduled_at"], alarm["expires_at"], stamp(*now), first["id"])
				if err != nil {
					t.Fatal(err)
				}
			}
			out = localFlowSync(t, a, token, "phone", results, []string{f["id"].(string)})
			wantChannel, wantPrevious := "alarm", first["id"]
			if received {
				if out["flow_result_acks"].([]any)[0].(map[string]any)["accepted"] != true {
					t.Fatal("actual cancellation receipt rejected")
				}
				wantChannel, wantPrevious = "email", alarm["id"]
			}
			previous, err := a.latestReminderResult(id)
			if err != nil || previous.ID != wantPrevious {
				t.Fatalf("previous=%+v, want %v: %v", previous, wantPrevious, err)
			}
			var channel, scheduled string
			if err := a.db.QueryRow(`SELECT channel,scheduled_at FROM reminder_plans WHERE task_id=? AND status='pending'`, id).Scan(&channel, &scheduled); err != nil || channel != wantChannel {
				t.Fatalf("next channel=%s, want %s: %v", channel, wantChannel, err)
			}
			if received {
				at, _ := time.Parse(time.RFC3339Nano, scheduled)
				if !at.Equal(now.Add(10 * time.Minute)) {
					t.Fatalf("email wait did not start at actual cancellation: %s", scheduled)
				}
			}
			complete, sent, err := a.cycleCounts(id, f["cycle_key"].(string))
			if err != nil || complete || sent != 1 {
				t.Fatalf("cancellation changed success/completion counts: %v %d %v", complete, sent, err)
			}
		})
	}
}
