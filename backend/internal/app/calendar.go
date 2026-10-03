package app

import (
	"encoding/json"
	"net/http"
	"net/url"
	"niannian/internal/schedule"
	"sort"
	"time"
)

const calendarEntryLimit = 20000
const calendarByteLimit = 6 * 1024 * 1024

type calendarEntry struct {
	ID          string   `json:"id"`
	Type        string   `json:"type"`
	TaskID      string   `json:"task_id"`
	TaskTitle   string   `json:"task_title"`
	At          string   `json:"at"`
	State       string   `json:"state"`
	CycleKey    string   `json:"cycle_key"`
	CycleEnd    string   `json:"cycle_end"`
	ScheduledAt string   `json:"scheduled_at,omitempty"`
	SentAt      string   `json:"sent_at,omitempty"`
	Status      string   `json:"status,omitempty"`
	Error       string   `json:"error,omitempty"`
	Text        string   `json:"text,omitempty"`
	MediaIDs    []string `json:"media_ids"`
	CreatedAt   string   `json:"created_at,omitempty"`
}

// Internally retain instants, not timestamp text: old records with different
// offsets must still replace the same projected notification slot.
type calendarNotification struct {
	entry calendarEntry
	rule  schedule.Rule
	at    time.Time
}
type calendarCycleID struct{ task, cycle string }
type calendarCycleData struct {
	completedAt           time.Time
	sent, sentBeforeMonth int
	sentInMonth           []time.Time
	end                   time.Time
}

func calendarCycleEnd(end time.Time) string {
	if end.IsZero() || end.Year() > 9999 {
		return ""
	}
	return stamp(end)
}

func calendarState(at time.Time, cycle schedule.Cycle, data *calendarCycleData, mode string, now time.Time) string {
	if data != nil && !data.completedAt.IsZero() {
		return "completed"
	}
	if mode == "notify_once" && data != nil && data.sent > 0 {
		return "notified"
	}
	if at.After(now) {
		return "upcoming"
	}
	if !cycle.End.IsZero() && !cycle.End.After(now) {
		return "expired"
	}
	return "pending"
}

func calendarLimit(rule schedule.Rule) int {
	if rule.Mode == "notify_once" {
		return 1
	}
	return rule.MaxReminders
}

func calendarEffective(t task) time.Time {
	created, _ := time.Parse(time.RFC3339Nano, t.CreatedAt)
	updated, _ := time.Parse(time.RFC3339Nano, t.UpdatedAt)
	if updated.After(created) {
		return updated
	}
	return created
}

func calendarSnooze(t task) (schedule.Occurrence, bool) {
	at, err := time.Parse(time.RFC3339Nano, t.SnoozeUntil)
	if err != nil {
		return schedule.Occurrence{}, false
	}
	cycle, ok := schedule.CycleAt(t.Rule, at)
	return schedule.Occurrence{At: at, CycleKey: t.SnoozeCycle}, ok && cycle.Key == t.SnoozeCycle
}

// Enumerate at most the remaining budget (<=100) from the current instant.
// This also handles an ongoing cycle when the requested month is many years
// away, without resetting its budget at the display month's boundary.
func calendarBudgetSlots(t task, cycle schedule.Cycle, data *calendarCycleData, now time.Time) map[string]bool {
	out := map[string]bool{}
	remaining := calendarLimit(t.Rule)
	if data != nil {
		if !data.completedAt.IsZero() {
			return out
		}
		remaining -= data.sent
	}
	if remaining <= 0 {
		return out
	}
	cursor := now.Add(-time.Nanosecond)
	if effective := calendarEffective(t); effective.After(cursor) {
		cursor = effective
	}
	if start := cycle.Start.Add(-time.Nanosecond); start.After(cursor) {
		cursor = start
	}
	snooze, hasSnooze := calendarSnooze(t)
	for i := 0; i < remaining; i++ {
		next, ok := schedule.Next(t.Rule, cursor)
		if hasSnooze && snooze.CycleKey == cycle.Key && snooze.At.After(cursor) {
			next, ok = snooze.At, true
		}
		if !ok || !next.Before(cycle.End) {
			break
		}
		out[stamp(next)] = true
		cursor = next
	}
	return out
}

func (a *App) calendar(w http.ResponseWriter, r *http.Request) {
	q, err := parseCalendarQuery(r)
	if err != "" {
		writeError(w, 400, err)
		return
	}
	loc, _ := time.LoadLocation(q.zone)
	start, _ := time.ParseInLocation("2006-01", q.month, loc)
	end := start.AddDate(0, 1, 0)
	a.mu.Lock()
	defer a.mu.Unlock()
	now := a.now()
	userID := currentUser(r).ID
	tasks, dbErr := a.allTasks(userID, false)
	if dbErr != nil {
		dbFailure(w)
		return
	}
	entries := []calendarEntry{}
	notifications := []calendarNotification{}
	cycles := map[calendarCycleID]*calendarCycleData{}
	needCycle := func(taskID, key string) *calendarCycleData {
		id := calendarCycleID{taskID, key}
		if cycles[id] == nil {
			cycles[id] = &calendarCycleData{}
		}
		return cycles[id]
	}
	actualSlots := map[string]bool{}
	truncated := false
	// Keep records in their original rule/cycle, even if the task was edited or
	// archived. SQL bounds plus the limit prevent an unbounded response.
	rows, dbErr := a.db.QueryContext(r.Context(), `SELECT id,task_id,task_title,cycle_key,scheduled_at,sent_at,status,error,rule_snapshot FROM notifications WHERE user_id=? AND unixepoch(scheduled_at)>=? AND unixepoch(scheduled_at)<? ORDER BY unixepoch(scheduled_at),id LIMIT ?`, userID, start.Unix(), end.Unix(), calendarEntryLimit+1)
	if dbErr != nil {
		dbFailure(w)
		return
	}
	for rows.Next() {
		var n calendarNotification
		var raw string
		if dbErr = rows.Scan(&n.entry.ID, &n.entry.TaskID, &n.entry.TaskTitle, &n.entry.CycleKey, &n.entry.ScheduledAt, &n.entry.SentAt, &n.entry.Status, &n.entry.Error, &raw); dbErr != nil {
			break
		}
		n.at, dbErr = time.Parse(time.RFC3339Nano, n.entry.ScheduledAt)
		if dbErr != nil {
			break
		}
		if n.at.Before(start) || !n.at.Before(end) {
			continue
		}
		n.entry.Type = "notification"
		n.entry.At = stamp(n.at)
		n.entry.ScheduledAt = n.entry.At
		if n.entry.Status == "sending" {
			n.entry.Status = "pending"
		}
		_ = json.Unmarshal([]byte(raw), &n.rule)
		data := needCycle(n.entry.TaskID, n.entry.CycleKey)
		if c, ok := schedule.CycleAt(n.rule, n.at); ok && c.Key == n.entry.CycleKey {
			n.entry.CycleEnd = calendarCycleEnd(c.End)
			data.end = c.End
		}
		actualSlots[n.entry.TaskID+"/"+n.entry.At] = true
		notifications = append(notifications, n)
	}
	if dbErr == nil {
		dbErr = rows.Err()
	}
	rows.Close()
	if dbErr != nil {
		dbFailure(w)
		return
	}
	if len(notifications) > calendarEntryLimit {
		truncated = true
	}
	rows, dbErr = a.db.QueryContext(r.Context(), `SELECT `+completionColumns+` FROM completions WHERE user_id=? AND unixepoch(created_at)>=? AND unixepoch(created_at)<? ORDER BY unixepoch(created_at),id LIMIT ?`, userID, start.Unix(), end.Unix(), calendarEntryLimit+1)
	if dbErr != nil {
		dbFailure(w)
		return
	}
	for rows.Next() {
		var c Completion
		c, dbErr = scanCompletion(rows)
		if dbErr != nil {
			break
		}
		at, e := time.Parse(time.RFC3339Nano, c.CreatedAt)
		if e != nil {
			dbErr = e
			break
		}
		entries = append(entries, calendarEntry{ID: c.ID, Type: "completion", TaskID: c.TaskID, TaskTitle: c.TaskTitle, At: stamp(at), State: "completed", CycleKey: c.CycleKey, Text: c.Text, MediaIDs: c.MediaIDs, CreatedAt: c.CreatedAt})
		needCycle(c.TaskID, c.CycleKey)
	}
	if dbErr == nil {
		dbErr = rows.Err()
	}
	rows.Close()
	if dbErr != nil {
		dbFailure(w)
		return
	}
	if len(entries) > calendarEntryLimit {
		truncated = true
	}
	type projection struct {
		t      task
		slots  []schedule.Occurrence
		cycles map[string]schedule.Cycle
	}
	plans := []projection{}
	for _, t := range tasks {
		if t.Paused || t.Archived {
			continue
		}
		from := start.Add(-time.Nanosecond)
		if effective := calendarEffective(t); effective.After(from) {
			from = effective
		}
		p := projection{t: t, cycles: map[string]schedule.Cycle{}}
		snooze, hasSnooze := calendarSnooze(t)
		for _, slot := range schedule.Due(t.Rule, from, end.Add(-time.Nanosecond)) {
			if hasSnooze && slot.CycleKey == snooze.CycleKey && !slot.At.After(snooze.At) {
				continue
			}
			p.slots = append(p.slots, slot)
		}
		if hasSnooze && snooze.At.After(from) && snooze.At.Before(end) {
			p.slots = append(p.slots, snooze)
		}
		sort.Slice(p.slots, func(i, j int) bool { return p.slots[i].At.Before(p.slots[j].At) })
		for _, slot := range p.slots {
			if c, ok := schedule.CycleAt(t.Rule, slot.At); ok {
				p.cycles[c.Key] = c
				needCycle(t.ID, c.Key)
			}
		}
		plans = append(plans, p)
	}
	// These compact ledger queries never read note/photo bodies outside the
	// requested month. Only cycles used by this response are retained in memory.
	rows, dbErr = a.db.QueryContext(r.Context(), `SELECT task_id,cycle_key,created_at FROM completions WHERE user_id=?`, userID)
	if dbErr != nil {
		dbFailure(w)
		return
	}
	for rows.Next() {
		var id, key, at string
		if dbErr = rows.Scan(&id, &key, &at); dbErr != nil {
			break
		}
		if data := cycles[calendarCycleID{id, key}]; data != nil {
			data.completedAt, _ = time.Parse(time.RFC3339Nano, at)
		}
	}
	if dbErr == nil {
		dbErr = rows.Err()
	}
	rows.Close()
	if dbErr != nil {
		dbFailure(w)
		return
	}
	rows, dbErr = a.db.QueryContext(r.Context(), `SELECT task_id,cycle_key,COUNT(*),SUM(CASE WHEN unixepoch(scheduled_at)<? THEN 1 ELSE 0 END) FROM notifications WHERE user_id=? AND status='sent' GROUP BY task_id,cycle_key`, start.Unix(), userID)
	if dbErr != nil {
		dbFailure(w)
		return
	}
	for rows.Next() {
		var id, key string
		var count, before int
		if dbErr = rows.Scan(&id, &key, &count, &before); dbErr != nil {
			break
		}
		if data := cycles[calendarCycleID{id, key}]; data != nil {
			data.sent = count
			data.sentBeforeMonth = before
		}
	}
	if dbErr == nil {
		dbErr = rows.Err()
	}
	rows.Close()
	if dbErr != nil {
		dbFailure(w)
		return
	}
	for _, n := range notifications {
		if n.entry.Status == "sent" {
			data := cycles[calendarCycleID{n.entry.TaskID, n.entry.CycleKey}]
			data.sentInMonth = append(data.sentInMonth, n.at)
		}
	}
	for i := range entries {
		entries[i].CycleEnd = calendarCycleEnd(cycles[calendarCycleID{entries[i].TaskID, entries[i].CycleKey}].end)
	}
	for _, n := range notifications {
		data := cycles[calendarCycleID{n.entry.TaskID, n.entry.CycleKey}]
		cycle, ok := schedule.CycleAt(n.rule, n.at)
		if !ok || cycle.Key != n.entry.CycleKey {
			cycle = schedule.Cycle{}
		}
		n.entry.State = calendarState(n.at, cycle, data, n.rule.Mode, now)
		entries = append(entries, n.entry)
	}
	for _, p := range plans {
		budget := map[string]map[string]bool{}
		if calendarLimit(p.t.Rule) > 0 {
			for key, c := range p.cycles {
				budget[key] = calendarBudgetSlots(p.t, c, cycles[calendarCycleID{p.t.ID, key}], now)
			}
		}
		for _, slot := range p.slots {
			at := stamp(slot.At)
			if actualSlots[p.t.ID+"/"+at] {
				continue
			}
			data := cycles[calendarCycleID{p.t.ID, slot.CycleKey}]
			if !data.completedAt.IsZero() && !slot.At.Before(data.completedAt) {
				continue
			}
			if limit := calendarLimit(p.t.Rule); limit > 0 {
				if !slot.At.Before(now) {
					if !budget[slot.CycleKey][at] {
						continue
					}
				} else {
					sent := data.sentBeforeMonth
					for _, sentAt := range data.sentInMonth {
						if !sentAt.After(slot.At) {
							sent++
						}
					}
					if sent >= limit {
						continue
					}
				}
			}
			c := p.cycles[slot.CycleKey]
			entries = append(entries, calendarEntry{ID: "planned:" + p.t.ID + ":" + at, Type: "planned", TaskID: p.t.ID, TaskTitle: p.t.Title, At: at, ScheduledAt: at, State: calendarState(slot.At, c, data, p.t.Rule.Mode, now), CycleKey: slot.CycleKey, CycleEnd: calendarCycleEnd(c.End)})
		}
	}
	sort.Slice(entries, func(i, j int) bool {
		if entries[i].At == entries[j].At {
			return entries[i].ID < entries[j].ID
		}
		return entries[i].At < entries[j].At
	})
	if len(entries) > calendarEntryLimit {
		entries = entries[:calendarEntryLimit]
		truncated = true
	}
	// Android accepts at most 8 MiB. Reserve room for the envelope and enforce
	// a smaller byte budget as notes can contain thousands of escaped chars.
	bytes := 1024
	for i, entry := range entries {
		encoded, encodeErr := json.Marshal(entry)
		if encodeErr != nil {
			dbFailure(w)
			return
		}
		bytes += len(encoded) + 1
		if bytes > calendarByteLimit {
			entries = entries[:i]
			truncated = true
			break
		}
	}
	writeJSON(w, 200, map[string]any{"month": q.month, "timezone": q.zone, "generated_at": stamp(now), "entries": entries, "truncated": truncated})
}

type calendarQuery struct{ month, zone string }

func parseCalendarQuery(r *http.Request) (calendarQuery, string) {
	q, queryErr := url.ParseQuery(r.URL.RawQuery)
	in := calendarQuery{q.Get("month"), q.Get("timezone")}
	if queryErr != nil || len(q) != 2 || len(q["month"]) != 1 || len(q["timezone"]) != 1 {
		return in, "请提供一个 month 和一个 timezone 参数"
	}
	month, err := time.Parse("2006-01", in.month)
	if err != nil || month.Year() < 1 || month.Year() > 9999 || month.Format("2006-01") != in.month {
		return in, "月份需为有效的 YYYY-MM 格式"
	}
	if in.zone == "" || in.zone == "Local" || len(in.zone) > 100 {
		return in, "请提供明确的显示时区"
	}
	if _, err = time.LoadLocation(in.zone); err != nil {
		return in, "显示时区无效"
	}
	return in, ""
}
