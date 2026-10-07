package app

import (
	"encoding/json"
	"fmt"
	"net/url"
	"niannian/internal/schedule"
	"strings"
	"testing"
	"time"
)

func calendarTask(t *testing.T, a *App, token string, rule schedule.Rule) string {
	t.Helper()
	c, out := request(t, a, "POST", "/api/v1/tasks", token, map[string]any{"title": "测试提醒", "description": "保留说明", "rule": rule})
	if c != 201 {
		t.Fatalf("create %d %+v", c, out)
	}
	return out["id"].(string)
}

func calendarEntries(t *testing.T, a *App, token, month, zone string) []map[string]any {
	t.Helper()
	c, out := request(t, a, "GET", "/api/v1/calendar?month="+month+"&timezone="+url.QueryEscape(zone), token, nil)
	if c != 200 {
		t.Fatalf("calendar %d %+v", c, out)
	}
	if out["month"] != month || out["timezone"] != zone || out["generated_at"] == "" || out["truncated"] != false {
		t.Fatalf("calendar metadata %+v", out)
	}
	entries := []map[string]any{}
	for _, raw := range out["entries"].([]any) {
		entries = append(entries, raw.(map[string]any))
	}
	return entries
}

func dailyCalendarRule() schedule.Rule {
	return schedule.Rule{Kind: "daily", Mode: "every_day", Timezone: "Asia/Shanghai", Times: []string{"12:00"}}
}

func TestCalendarEveryThreeDaysAndReadOnly(t *testing.T) {
	a, m, _ := setup(t)
	token := login(t, a, m, "one@example.com")
	r := dailyCalendarRule()
	r.Kind = "interval"
	r.StartDate = "2026-10-03"
	r.EveryDays = 3
	id := calendarTask(t, a, token, r)
	entries := calendarEntries(t, a, token, "2026-10", "Asia/Shanghai")
	if len(entries) != 10 {
		t.Fatalf("every three days: %d %+v", len(entries), entries)
	}
	for i, e := range entries {
		want := time.Date(2026, 10, 3+i*3, 12, 0, 0, 0, time.FixedZone("CST", 8*3600))
		if e["task_id"] != id || e["type"] != "planned" || e["state"] != "upcoming" || e["at"] != stamp(want) || e["scheduled_at"] != e["at"] || e["cycle_key"] == "" || e["cycle_end"] == "" {
			t.Fatalf("slot %d %+v", i, e)
		}
	}
	var n int
	if err := a.db.QueryRow(`SELECT COUNT(*) FROM notifications`).Scan(&n); err != nil || n != 0 {
		t.Fatalf("calendar persisted notifications: %d %v", n, err)
	}
	if len(m.messages) != 1 {
		t.Fatal("calendar sent mail")
	}
}

func TestCalendarDisplayTimezoneCrossesMonth(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	*now = time.Date(2026, 10, 30, 0, 0, 0, 0, time.UTC)
	r := dailyCalendarRule()
	r.Kind = "once"
	r.StartDate = "2026-11-01"
	r.Times = []string{"00:30"}
	calendarTask(t, a, token, r)
	if got := calendarEntries(t, a, token, "2026-10", "UTC"); len(got) != 1 || got[0]["at"] != "2026-10-31T16:30:00.000000000Z" {
		t.Fatalf("UTC month %+v", got)
	}
	if got := calendarEntries(t, a, token, "2026-10", "Asia/Shanghai"); len(got) != 0 {
		t.Fatalf("Shanghai October %+v", got)
	}
	if got := calendarEntries(t, a, token, "2026-11", "Asia/Shanghai"); len(got) != 1 {
		t.Fatalf("Shanghai November %+v", got)
	}
}

func TestCalendarActualOffsetTimestampsCrossDisplayMonth(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	*now = time.Date(2026, 10, 30, 0, 0, 0, 0, time.UTC)
	r := dailyCalendarRule()
	r.Kind = "once"
	r.StartDate = "2026-11-01"
	r.Times = []string{"00:30"}
	id := calendarTask(t, a, token, r)
	raw, _ := json.Marshal(r)
	if _, err := a.db.Exec(`INSERT INTO notifications(id,user_id,task_id,task_title,cycle_key,scheduled_at,sent_at,status,rule_snapshot) SELECT 'offset-notification',user_id,id,title,'2026-11-01','2026-11-01T00:30:00+08:00','2026-11-01T00:30:00+08:00','sent',? FROM tasks WHERE id=?`, string(raw), id); err != nil {
		t.Fatal(err)
	}
	if _, err := a.db.Exec(`INSERT INTO completions(id,user_id,task_id,task_title,cycle_key,text,media_ids,created_at) SELECT 'offset-completion',user_id,id,title,'2026-11-01','保留照片','["photo-id"]','2026-11-01T00:30:00+08:00' FROM tasks WHERE id=?`, id); err != nil {
		t.Fatal(err)
	}
	entries := calendarEntries(t, a, token, "2026-10", "UTC")
	if len(entries) != 1 || entries[0]["type"] != "completion" || entries[0]["reminder_count"] != float64(1) {
		t.Fatalf("offset records lost %+v", entries)
	}
	for _, e := range entries {
		if e["type"] == "planned" || e["at"] != "2026-10-31T16:30:00.000000000Z" {
			t.Fatalf("offset instant mismatch %+v", e)
		}
		if e["type"] == "completion" && e["media_ids"].([]any)[0] != "photo-id" {
			t.Fatalf("photo reference lost %+v", e)
		}
	}
}

func TestCalendarCompletedMonthlyCycleAndIndependentDays(t *testing.T) {
	for _, mode := range []string{"until_done", "every_day"} {
		t.Run(mode, func(t *testing.T) {
			a, m, now := setup(t)
			token := login(t, a, m, "one@example.com")
			*now = now.Add(-time.Hour)
			id := createTask(t, a, token, mode)
			*now = now.Add(time.Hour)
			c, _ := request(t, a, "POST", "/api/v1/tasks/"+id+"/complete", token, map[string]any{"text": "完成备注"})
			if c != 200 {
				t.Fatal(c)
			}
			entries := calendarEntries(t, a, token, "2026-10", "Asia/Shanghai")
			var completion, future int
			for _, e := range entries {
				if e["type"] == "completion" {
					completion++
					if e["text"] != "完成备注" || e["state"] != "completed" || e["media_ids"] == nil {
						t.Fatalf("completion %+v", e)
					}
				}
				at, _ := time.Parse(time.RFC3339Nano, e["at"].(string))
				if e["type"] == "planned" && at.After(*now) {
					future++
					if mode == "every_day" && at.In(now.Location()).Day() == 3 {
						t.Fatalf("same day remains %+v", e)
					}
				}
			}
			if completion != 1 || (mode == "until_done" && future != 0) || (mode == "every_day" && future != 12) {
				t.Fatalf("completion=%d future=%d %+v", completion, future, entries)
			}
			if entries := calendarEntries(t, a, token, "2026-11", "Asia/Shanghai"); len(entries) != 15 {
				t.Fatalf("next month lost: %d", len(entries))
			}
		})
	}
}

func TestCalendarFourStatesAndNotifyOnceSnapshot(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	*now = time.Date(2026, 10, 1, 8, 0, 0, 0, now.Location())
	r := dailyCalendarRule()
	id := calendarTask(t, a, token, r)
	*now = time.Date(2026, 10, 3, 13, 0, 0, 0, now.Location())
	if c, _ := request(t, a, "POST", "/api/v1/tasks/"+id+"/complete", token, map[string]any{}); c != 200 {
		t.Fatal(c)
	}
	entries := calendarEntries(t, a, token, "2026-10", "Asia/Shanghai")
	states := map[string]bool{}
	for _, e := range entries {
		states[e["state"].(string)] = true
	}
	if !states["expired"] || !states["completed"] || !states["upcoming"] {
		t.Fatalf("states %+v", states)
	}
	*now = time.Date(2026, 10, 4, 13, 0, 0, 0, now.Location())
	entries = calendarEntries(t, a, token, "2026-10", "Asia/Shanghai")
	if entries[4]["state"] != "pending" { // Oct 1,2,3 planned + completion, Oct 4 planned.
		found := false
		for _, e := range entries {
			found = found || e["state"] == "pending"
		}
		if !found {
			t.Fatalf("missing pending %+v", entries)
		}
	}
	old := r
	old.Mode = "notify_once"
	raw, _ := json.Marshal(old)
	at := time.Date(2026, 10, 4, 12, 0, 0, 0, now.Location())
	if _, err := a.db.Exec(`INSERT INTO notifications(id,user_id,task_id,task_title,cycle_key,scheduled_at,sent_at,status,rule_snapshot) SELECT 'old-notification',user_id,id,title,'2026-10-04',?,?, 'sent',? FROM tasks WHERE id=?`, stamp(at), stamp(at), string(raw), id); err != nil {
		t.Fatal(err)
	}
	entries = calendarEntries(t, a, token, "2026-10", "Asia/Shanghai")
	n := 0
	for _, e := range entries {
		if e["at"] == stamp(at) {
			n++
			if e["type"] != "notification" || e["state"] != "notified" {
				t.Fatalf("old rule reinterpreted %+v", e)
			}
		}
	}
	if n != 1 {
		t.Fatalf("duplicate actual slot %d", n)
	}
}

func TestCalendarCapsAndSnooze(t *testing.T) {
	for _, mode := range []string{"until_done", "notify_once"} {
		t.Run(mode, func(t *testing.T) {
			a, m, now := setup(t)
			token := login(t, a, m, "one@example.com")
			r := dailyCalendarRule()
			r.Kind = "monthly_window"
			r.Mode = mode
			r.MonthStart = 1
			r.MonthEnd = 15
			r.Times = []string{"12:00", "18:00"}
			r.MaxReminders = 2
			id := calendarTask(t, a, token, r)
			if c, _ := request(t, a, "POST", "/api/v1/tasks/"+id+"/snooze", token, map[string]int{"minutes": 300}); c != 200 {
				t.Fatal(c)
			}
			entries := calendarEntries(t, a, token, "2026-10", "Asia/Shanghai")
			want := 1 // Two reminder slots on the same date are one daily task.
			if len(entries) != want || entries[0]["at"] != stamp(now.Add(300*time.Minute)) {
				t.Fatalf("caps snooze %+v", entries)
			}
			// The earlier projected slots consume the ongoing cycle's future budget even outside the requested month.
			r.Kind = "ongoing"
			r.Mode = "until_done"
			r.StartDate = "2026-10-03"
			r.MaxReminders = 2
			calendarTask(t, a, token, r)
			entries = calendarEntries(t, a, token, "2026-11", "Asia/Shanghai")
			if len(entries) != want {
				t.Fatalf("future month cap %+v", entries)
			}
		})
	}
}

func TestCalendarPausedArchivedIsolationAndPauseUpdate(t *testing.T) {
	a, m, now := setup(t)
	one := login(t, a, m, "one@example.com")
	two := login(t, a, m, "two@example.com")
	id := calendarTask(t, a, one, dailyCalendarRule())
	calendarTask(t, a, two, dailyCalendarRule())
	if c, _ := request(t, a, "POST", "/api/v1/tasks/"+id+"/snooze", one, map[string]int{"minutes": 300}); c != 200 {
		t.Fatal(c)
	}
	before, _ := a.findTask(id, func() string {
		var u string
		a.db.QueryRow(`SELECT user_id FROM tasks WHERE id=?`, id).Scan(&u)
		return u
	}())
	*now = now.Add(time.Minute)
	c, out := request(t, a, "PUT", "/api/v1/tasks/"+id+"/paused", one, map[string]bool{"paused": true})
	if c != 200 || out["paused"] != true {
		t.Fatalf("pause %d %+v", c, out)
	}
	after, _ := a.findTask(id, before.UserID)
	if after.Title != before.Title || after.Description != before.Description || after.SnoozeUntil != before.SnoozeUntil || after.UpdatedAt == before.UpdatedAt {
		t.Fatalf("partial pause overwrote data %+v", after)
	}
	if entries := calendarEntries(t, a, one, "2026-10", "Asia/Shanghai"); len(entries) != 0 {
		t.Fatalf("paused future %+v", entries)
	}
	if c, _ := request(t, a, "PUT", "/api/v1/tasks/"+id+"/paused", two, map[string]bool{"paused": false}); c != 404 {
		t.Fatalf("ownership %d", c)
	}
	if c, _ := request(t, a, "PUT", "/api/v1/tasks/"+id+"/paused", one, map[string]bool{"paused": false}); c != 200 {
		t.Fatal(c)
	}
	if c, _ := request(t, a, "POST", "/api/v1/tasks/"+id+"/complete", one, map[string]any{"text": "保留"}); c != 200 {
		t.Fatal(c)
	}
	request(t, a, "DELETE", "/api/v1/tasks/"+id, one, nil)
	entries := calendarEntries(t, a, one, "2026-10", "Asia/Shanghai")
	if len(entries) != 1 || entries[0]["type"] != "completion" {
		t.Fatalf("archived history %+v", entries)
	}
	if c, _ := request(t, a, "PUT", "/api/v1/tasks/"+id+"/paused", one, map[string]bool{"paused": false}); c != 404 {
		t.Fatalf("archived pause %d", c)
	}
}

func TestCalendarInputsAndPauseBody(t *testing.T) {
	a, m, _ := setup(t)
	token := login(t, a, m, "one@example.com")
	id := calendarTask(t, a, token, dailyCalendarRule())
	for _, query := range []string{"", "?month=2026-1&timezone=UTC", "?month=2026-13&timezone=UTC", "?month=0000-01&timezone=UTC", "?month=2026-10", "?month=2026-10&timezone=Local", "?month=2026-10&timezone=Nope", "?month=2026-10&month=2026-11&timezone=UTC", "?month=2026-10&timezone=UTC&from=2026-01", "?month=2026-10&timezone=UTC&bad=%ZZ"} {
		if c, out := request(t, a, "GET", "/api/v1/calendar"+query, token, nil); c != 400 {
			t.Fatalf("%s %d %+v", query, c, out)
		}
	}
	for _, body := range []any{map[string]any{}, map[string]any{"paused": nil}, map[string]any{"paused": "false"}, map[string]any{"paused": true, "title": "覆盖"}} {
		if c, out := request(t, a, "PUT", "/api/v1/tasks/"+id+"/paused", token, body); c != 400 {
			t.Fatalf("body %s %d %+v", fmt.Sprint(body), c, out)
		}
	}
}

func TestCalendarSentBudgetAndLongFutureCycle(t *testing.T) {
	for _, kind := range []string{"ongoing", "date_range"} {
		a, m, now := setup(t)
		token := login(t, a, m, "one@example.com")
		r := dailyCalendarRule()
		r.Kind = kind
		r.Mode = "until_done"
		r.StartDate = "2026-10-03"
		r.MaxReminders = 2
		if kind == "date_range" {
			r.EndDate = "2038-12-31"
		}
		id := calendarTask(t, a, token, r)
		// A successful send consumes one slot; a failed send does not.
		c, _ := schedule.CycleAt(r, *now)
		raw, _ := json.Marshal(r)
		for i, status := range []string{"sent", "failed"} {
			if _, err := a.db.Exec(`INSERT INTO notifications(id,user_id,task_id,task_title,cycle_key,scheduled_at,status,rule_snapshot) SELECT ?,user_id,id,title,?,?,?,? FROM tasks WHERE id=?`, fmt.Sprint("budget", i), c.Key, stamp(now.Add(time.Duration(i-2)*time.Minute)), status, string(raw), id); err != nil {
				t.Fatal(err)
			}
		}
		entries := calendarEntries(t, a, token, "2026-10", "Asia/Shanghai")
		// The remaining future slot shares the same day as both actual attempts.
		if len(entries) != 1 || entries[0]["reminder_count"] != float64(1) || entries[0]["last_reminder_status"] != "failed" {
			t.Fatalf("successful send budget %+v", entries)
		}
		if kind == "ongoing" && entries[0]["cycle_end"] != "" {
			t.Fatalf("sentinel date %+v", entries[0])
		}
		if entries := calendarEntries(t, a, token, "2037-11", "Asia/Shanghai"); len(entries) != 0 {
			t.Fatalf("long-range cycle resets cap %+v", entries)
		}
	}
}

func TestCalendarCappedSnoozeBeforeNextRegularSlot(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	r := dailyCalendarRule()
	r.Mode = "notify_once"
	id := calendarTask(t, a, token, r)
	if c, out := request(t, a, "POST", "/api/v1/tasks/"+id+"/snooze", token, map[string]int{"minutes": 10}); c != 200 {
		t.Fatalf("snooze %d %+v", c, out)
	}
	entries := calendarEntries(t, a, token, "2026-10", "Asia/Shanghai")
	if len(entries) == 0 || entries[0]["at"] != stamp(now.Add(10*time.Minute)) {
		t.Fatalf("early snooze lost %+v", entries)
	}
}

func TestCalendarEditDoesNotInventPastAndKeepsOriginalNotificationCycle(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	old := dailyCalendarRule()
	id := calendarTask(t, a, token, old)
	raw, _ := json.Marshal(old)
	at := time.Date(2026, 10, 3, 12, 0, 0, 0, now.Location())
	if _, err := a.db.Exec(`INSERT INTO notifications(id,user_id,task_id,task_title,cycle_key,scheduled_at,sent_at,status,rule_snapshot) SELECT 'historical',user_id,id,title,'2026-10-03',?,?,'sent',? FROM tasks WHERE id=?`, stamp(at), stamp(at), string(raw), id); err != nil {
		t.Fatal(err)
	}
	*now = time.Date(2026, 10, 4, 0, 0, 0, 0, now.Location())
	updated := old
	updated.Kind = "ongoing"
	updated.Mode = "until_done"
	updated.StartDate = "2026-10-01"
	if c, out := request(t, a, "PUT", "/api/v1/tasks/"+id, token, map[string]any{"title": "修改后的任务", "rule": updated}); c != 200 {
		t.Fatalf("update %d %+v", c, out)
	}
	entries := calendarEntries(t, a, token, "2026-10", "Asia/Shanghai")
	for _, e := range entries {
		if e["type"] == "notification" {
			if e["state"] != "expired" || e["cycle_key"] != "2026-10-03" || e["cycle_end"] != stamp(*now) {
				t.Fatalf("old cycle changed %+v", e)
			}
		} else if e["type"] == "planned" {
			slot, _ := time.Parse(time.RFC3339Nano, e["at"].(string))
			if slot.Before(*now) {
				t.Fatalf("invented old plan %+v", e)
			}
		}
	}
	if c, _ := request(t, a, "PUT", "/api/v1/tasks/"+id+"/paused", token, map[string]bool{"paused": true}); c != 200 {
		t.Fatal(c)
	}
	entries = calendarEntries(t, a, token, "2026-10", "Asia/Shanghai")
	if len(entries) != 1 || entries[0]["id"] != "historical" {
		t.Fatalf("pause lost actual %+v", entries)
	}
}

func TestCalendarLimitAndPayloadBudgetAreExplicit(t *testing.T) {
	for _, largeNotes := range []bool{false, true} {
		t.Run(fmt.Sprint(largeNotes), func(t *testing.T) {
			a, m, now := setup(t)
			token := login(t, a, m, "one@example.com")
			*now = time.Date(2026, 10, 1, 0, 0, 0, 0, now.Location())
			r := dailyCalendarRule()
			r.Times = []string{"01:00", "02:00", "03:00", "04:00", "05:00", "06:00", "07:00", "08:00"}
			id := calendarTask(t, a, token, r)
			base, err := a.findTask(id, func() string {
				var user string
				a.db.QueryRow(`SELECT user_id FROM tasks WHERE id=?`, id).Scan(&user)
				return user
			}())
			if err != nil {
				t.Fatal(err)
			}
			raw, _ := json.Marshal(r)
			tx, err := a.db.Begin()
			if err != nil {
				t.Fatal(err)
			}
			taskCount := 700 // The item cap applies to distinct task/date pairs.
			if largeNotes {
				taskCount = 100
			}
			for i := 0; i < taskCount; i++ {
				taskID := fmt.Sprint("large-", i)
				if _, err = tx.Exec(`INSERT INTO tasks(id,user_id,title,description,rule,created_at,updated_at) VALUES(?,?,?,?,?,?,?)`, taskID, base.UserID, "任务", "", string(raw), stamp(*now), stamp(*now)); err != nil {
					t.Fatal(err)
				}
				if largeNotes {
					for day := 1; day <= 3; day++ {
						if _, err = tx.Exec(`INSERT INTO completions(id,user_id,task_id,task_title,cycle_key,text,media_ids,created_at) VALUES(?,?,?,?,?,?,?,?)`, fmt.Sprintf("complete-%d-%d", i, day), base.UserID, taskID, "任务", fmt.Sprintf("2026-10-%02d", day), strings.Repeat("<", 4000), "[]", stamp(now.AddDate(0, 0, day-1))); err != nil {
							t.Fatal(err)
						}
					}
				}
			}
			if err = tx.Commit(); err != nil {
				t.Fatal(err)
			}
			code, out := request(t, a, "GET", "/api/v1/calendar?month=2026-10&timezone=Asia%2FShanghai", token, nil)
			if code != 200 || out["truncated"] != true || len(out["entries"].([]any)) > 20000 {
				t.Fatalf("limit metadata %d count=%v", code, len(out["entries"].([]any)))
			}
			encoded, _ := json.Marshal(out)
			if len(encoded) > 6*1024*1024 {
				t.Fatalf("calendar too large for Android: %d", len(encoded))
			}
		})
	}
}
