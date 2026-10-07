package app

import (
	"container/heap"
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
	Channel     string   `json:"channel,omitempty"`
	Source      string   `json:"source,omitempty"`
	Reason      string   `json:"reason,omitempty"`
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

type calendarPlanStream struct {
	t      task
	cycles map[string]schedule.Cycle
	slot   schedule.Occurrence
	next   func() (schedule.Occurrence, bool)
}

type calendarPlanHeap []*calendarPlanStream

func (h calendarPlanHeap) Len() int { return len(h) }
func (h calendarPlanHeap) Less(i, j int) bool {
	if h[i].slot.At.Equal(h[j].slot.At) {
		return h[i].t.ID < h[j].t.ID
	}
	return h[i].slot.At.Before(h[j].slot.At)
}
func (h calendarPlanHeap) Swap(i, j int) { h[i], h[j] = h[j], h[i] }
func (h *calendarPlanHeap) Push(v any)   { *h = append(*h, v.(*calendarPlanStream)) }
func (h *calendarPlanHeap) Pop() any {
	last := len(*h) - 1
	v := (*h)[last]
	(*h)[last] = nil
	*h = (*h)[:last]
	return v
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

// Minute rules can have 44,640 slots in one month. Read their ledger before
// enumerating, then retain only a small chunk per task. Capped future slots
// come from the lifetime budget, even when they fall outside this month.
func calendarMinuteCandidates(t task, from, to, now time.Time, data *calendarCycleData, budget map[string]bool) func() (schedule.Occurrence, bool) {
	if !data.completedAt.IsZero() && !data.completedAt.After(to) {
		to = data.completedAt.Add(-time.Nanosecond)
	}
	regularTo := to
	future := []schedule.Occurrence{}
	if limit := calendarLimit(t.Rule); limit > 0 {
		if !regularTo.Before(now) {
			regularTo = now.Add(-time.Nanosecond)
		}
		if data.sentBeforeMonth >= limit {
			regularTo = from
		} else if remaining := limit - data.sentBeforeMonth; len(data.sentInMonth) >= remaining {
			cutoff := data.sentInMonth[remaining-1].Add(-time.Nanosecond)
			if cutoff.Before(regularTo) {
				regularTo = cutoff
			}
		}
		for at := range budget {
			instant, err := time.Parse(time.RFC3339Nano, at)
			if err == nil && instant.After(from) && !instant.After(to) {
				future = append(future, schedule.Occurrence{At: instant, CycleKey: "minute_interval"})
			}
		}
		sort.Slice(future, func(i, j int) bool { return future[i].At.Before(future[j].At) })
	}
	cursor := from
	snooze, hasSnooze := calendarSnooze(t)
	var chunk []schedule.Occurrence
	chunkIndex, futureIndex := 0, 0
	regularDone := false
	return func() (schedule.Occurrence, bool) {
		if !regularDone {
			if hasSnooze && snooze.At.After(cursor) {
				cursor = snooze.At
				if !cursor.After(regularTo) {
					return snooze, true
				}
			}
			if chunkIndex == len(chunk) {
				chunk = schedule.DueLimited(t.Rule, cursor, regularTo, 64)
				chunkIndex = 0
			}
			if chunkIndex < len(chunk) {
				slot := chunk[chunkIndex]
				chunkIndex++
				cursor = slot.At
				return slot, true
			}
			regularDone = true
		}
		if futureIndex < len(future) {
			slot := future[futureIndex]
			futureIndex++
			return slot, true
		}
		return schedule.Occurrence{}, false
	}
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
	managed, dbErr := a.usesReminderAgent(userID)
	if dbErr != nil {
		dbFailure(w)
		return
	}
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
	rows, dbErr := a.db.QueryContext(r.Context(), `SELECT id,task_id,task_title,cycle_key,scheduled_at,sent_at,status,error,rule_snapshot,channel,source,reason FROM (SELECT id,user_id,task_id,task_title,cycle_key,scheduled_at,sent_at,status,error,rule_snapshot,'email' AS channel,'legacy' AS source,'' AS reason FROM notifications UNION ALL SELECT id,user_id,task_id,title,cycle_key,scheduled_at,sent_at,status,error,rule_snapshot,channel,source,reason FROM reminder_plans) WHERE user_id=? AND unixepoch(scheduled_at)>=? AND unixepoch(scheduled_at)<? ORDER BY unixepoch(scheduled_at),id LIMIT ?`, userID, start.Unix(), end.Unix(), calendarEntryLimit+1)
	if dbErr != nil {
		dbFailure(w)
		return
	}
	for rows.Next() {
		var n calendarNotification
		var raw string
		if dbErr = rows.Scan(&n.entry.ID, &n.entry.TaskID, &n.entry.TaskTitle, &n.entry.CycleKey, &n.entry.ScheduledAt, &n.entry.SentAt, &n.entry.Status, &n.entry.Error, &raw, &n.entry.Channel, &n.entry.Source, &n.entry.Reason); dbErr != nil {
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
		t        task
		slots    []schedule.Occurrence
		cycles   map[string]schedule.Cycle
		from, to time.Time
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
		p := projection{t: t, cycles: map[string]schedule.Cycle{}, from: from, to: end.Add(-time.Nanosecond)}
		// Once the agent is explicitly enabled, future reminders come only from
		// its real permission/quiet-hour constrained plan ledger.
		if managed && p.to.After(now) {
			p.to = now.Add(-time.Nanosecond)
		}
		snooze, hasSnooze := calendarSnooze(t)
		if t.Rule.Kind == "minute_interval" {
			first, _ := time.Parse(time.RFC3339Nano, t.Rule.FirstAt)
			if c, ok := schedule.CycleAt(t.Rule, first); ok {
				p.cycles[c.Key] = c
				needCycle(t.ID, c.Key)
			}
			plans = append(plans, p)
			continue
		}
		slots := schedule.Due(t.Rule, from, p.to)
		for _, slot := range slots {
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
			if _, known := p.cycles[slot.CycleKey]; known {
				continue
			}
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
	rows, dbErr = a.db.QueryContext(r.Context(), `SELECT task_id,cycle_key,COUNT(*),SUM(CASE WHEN unixepoch(scheduled_at)<? THEN 1 ELSE 0 END) FROM (SELECT user_id,task_id,cycle_key,scheduled_at FROM notifications WHERE status='sent' UNION ALL SELECT user_id,task_id,cycle_key,scheduled_at FROM reminder_plans WHERE status IN ('delivered','accepted') OR (status='scheduled' AND channel='calendar' AND scheduled_at<=?)) WHERE user_id=? GROUP BY task_id,cycle_key`, start.Unix(), stamp(now), userID)
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
		if n.entry.Status == "sent" || n.entry.Status == "delivered" || n.entry.Status == "accepted" || (n.entry.Status == "scheduled" && n.entry.Channel == "calendar" && !n.at.After(now)) {
			data := cycles[calendarCycleID{n.entry.TaskID, n.entry.CycleKey}]
			data.sentInMonth = append(data.sentInMonth, n.at)
		}
	}
	for _, data := range cycles {
		// SQL unixepoch orders whole seconds; old records may differ only in
		// fractional seconds, so cap cutoffs must use the original instants.
		sort.Slice(data.sentInMonth, func(i, j int) bool { return data.sentInMonth[i].Before(data.sentInMonth[j]) })
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
	streams := calendarPlanHeap{}
	for _, p := range plans {
		budget := map[string]map[string]bool{}
		if calendarLimit(p.t.Rule) > 0 {
			for key, c := range p.cycles {
				budget[key] = calendarBudgetSlots(p.t, c, cycles[calendarCycleID{p.t.ID, key}], now)
			}
		}
		index := 0
		candidate := func() (schedule.Occurrence, bool) {
			if index == len(p.slots) {
				return schedule.Occurrence{}, false
			}
			slot := p.slots[index]
			index++
			return slot, true
		}
		if p.t.Rule.Kind == "minute_interval" {
			data := cycles[calendarCycleID{p.t.ID, "minute_interval"}]
			if data == nil {
				continue
			}
			candidate = calendarMinuteCandidates(p.t, p.from, p.to, now, data, budget["minute_interval"])
		}
		stream := &calendarPlanStream{t: p.t, cycles: p.cycles}
		stream.next = func() (schedule.Occurrence, bool) {
			for {
				slot, ok := candidate()
				if !ok {
					return schedule.Occurrence{}, false
				}
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
				return slot, true
			}
		}
		if slot, ok := stream.next(); ok {
			stream.slot = slot
			heap.Push(&streams, stream)
		}
	}
	// Merge eligible plans by instant so a newer high-density task cannot hide
	// earlier plans from another task. Suppressed slots never consume the cap.
	for projected := 0; streams.Len() > 0 && projected <= calendarEntryLimit; projected++ {
		stream := heap.Pop(&streams).(*calendarPlanStream)
		slot := stream.slot
		at := stamp(slot.At)
		c := stream.cycles[slot.CycleKey]
		data := cycles[calendarCycleID{stream.t.ID, slot.CycleKey}]
		entries = append(entries, calendarEntry{ID: "planned:" + stream.t.ID + ":" + at, Type: "planned", TaskID: stream.t.ID, TaskTitle: stream.t.Title, At: at, ScheduledAt: at, State: calendarState(slot.At, c, data, stream.t.Rule.Mode, now), CycleKey: slot.CycleKey, CycleEnd: calendarCycleEnd(c.End)})
		if next, ok := stream.next(); ok {
			stream.slot = next
			heap.Push(&streams, stream)
		}
	}
	if streams.Len() > 0 {
		truncated = true
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
