package app

import (
	"container/heap"
	"encoding/json"
	"net/http"
	"net/url"
	"niannian/internal/schedule"
	"sort"
	"strings"
	"time"
)

const calendarEntryLimit = 20000
const calendarByteLimit = 6 * 1024 * 1024

type calendarEntry struct {
	ID                 string          `json:"id"`
	Type               string          `json:"type"`
	TaskID             string          `json:"task_id"`
	TaskTitle          string          `json:"task_title"`
	At                 string          `json:"at"`
	State              string          `json:"state"`
	CycleKey           string          `json:"cycle_key"`
	CycleEnd           string          `json:"cycle_end"`
	ScheduledAt        string          `json:"scheduled_at,omitempty"`
	SentAt             string          `json:"sent_at,omitempty"`
	Status             string          `json:"status,omitempty"`
	Error              string          `json:"error,omitempty"`
	Channel            string          `json:"channel,omitempty"`
	Source             string          `json:"source,omitempty"`
	Reason             string          `json:"reason,omitempty"`
	Text               string          `json:"text,omitempty"`
	MediaIDs           []string        `json:"media_ids"`
	CreatedAt          string          `json:"created_at,omitempty"`
	LocalDate          string          `json:"local_date"`
	ReminderCount      int             `json:"reminder_count"`
	LastReminderAt     string          `json:"last_reminder_at,omitempty"`
	LastReminderStatus string          `json:"last_reminder_status,omitempty"`
	CompletionCount    int             `json:"completion_count,omitempty"`
	CompletionRecords  []calendarEntry `json:"completion_records,omitempty"`
}

type calendarDayID struct{ task, day string }

func calendarPrefers(candidate, current calendarEntry, now time.Time) bool {
	rank := func(e calendarEntry) int {
		switch e.Type {
		case "completion":
			return 3
		case "notification":
			return 2
		default:
			return 1
		}
	}
	if rank(candidate) != rank(current) {
		return rank(candidate) > rank(current)
	}
	if candidate.Type == "planned" {
		// An actual pending plan keeps its identity and channel instead of being
		// replaced by a rule projection, which has no execution guarantee.
		if (candidate.Status != "") != (current.Status != "") {
			return candidate.Status != ""
		}
		futureCandidate, futureCurrent := candidate.At >= stamp(now), current.At >= stamp(now)
		if futureCandidate != futureCurrent {
			return futureCandidate
		}
		if candidate.At != current.At && futureCandidate {
			return candidate.At < current.At
		}
	}
	if candidate.At != current.At {
		return candidate.At > current.At
	}
	return candidate.ID < current.ID
}

func calendarCompletionRecords(entry calendarEntry) []calendarEntry {
	if entry.Type != "completion" {
		return nil
	}
	if len(entry.CompletionRecords) > 0 {
		return entry.CompletionRecords
	}
	entry.ReminderCount, entry.CompletionCount = 0, 0
	entry.LastReminderAt, entry.LastReminderStatus = "", ""
	return []calendarEntry{entry}
}

// Keep one display item per task/day without discarding the underlying notes,
// photos or successful reminder count. The representative retains its real ID.
func calendarMerge(current, incoming calendarEntry, now time.Time) calendarEntry {
	out := current
	if calendarPrefers(incoming, current, now) {
		out = incoming
	}
	out.ReminderCount = current.ReminderCount + incoming.ReminderCount
	out.LastReminderAt, out.LastReminderStatus = current.LastReminderAt, current.LastReminderStatus
	if incoming.LastReminderAt > out.LastReminderAt {
		out.LastReminderAt, out.LastReminderStatus = incoming.LastReminderAt, incoming.LastReminderStatus
	}
	records := append(calendarCompletionRecords(current), calendarCompletionRecords(incoming)...)
	out.CompletionCount = len(records)
	if len(records) > 1 {
		sort.Slice(records, func(i, j int) bool {
			if records[i].At == records[j].At {
				return records[i].ID < records[j].ID
			}
			return records[i].At < records[j].At
		})
		out.CompletionRecords = records
		out.MediaIDs = []string{}
		seen := map[string]bool{}
		texts := []string{}
		for _, record := range records {
			if record.Text != "" {
				texts = append(texts, record.Text)
			}
			for _, id := range record.MediaIDs {
				if !seen[id] {
					out.MediaIDs = append(out.MediaIDs, id)
					seen[id] = true
				}
			}
		}
		out.Text = strings.Join(texts, "\n\n")
	}
	return out
}

func calendarDelivered(entry calendarEntry) bool {
	return entry.Status == "sent" || entry.Status == "accepted" || (entry.Status == "delivered" && entry.Channel != "calendar")
}

// Retain the original scheduled instant and rule while aggregating daily
// display rows, including old records written with different UTC offsets.
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

type calendarSingleProjection struct {
	t     task
	first time.Time
	cycle schedule.Cycle
}

func calendarSingleRule(rule schedule.Rule) bool {
	return rule.Kind == "minute_interval" || rule.Kind == "ongoing"
}

func calendarSingle(t task) (calendarSingleProjection, bool) {
	p := calendarSingleProjection{t: t}
	var ok bool
	if t.Rule.Kind == "minute_interval" {
		var err error
		p.first, err = time.Parse(time.RFC3339Nano, t.Rule.FirstAt)
		if err != nil {
			return p, false
		}
	} else {
		loc, err := time.LoadLocation(t.Rule.Timezone)
		if err != nil {
			return p, false
		}
		start, err := time.ParseInLocation("2006-01-02", t.Rule.StartDate, loc)
		if err != nil {
			return p, false
		}
		p.first, ok = schedule.Next(t.Rule, start.Add(-time.Nanosecond))
		if !ok {
			return p, false
		}
	}
	p.cycle, ok = schedule.CycleAt(t.Rule, p.first)
	return p, ok
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
	notifications := map[calendarDayID]calendarNotification{}
	cycles := map[calendarCycleID]*calendarCycleData{}
	needCycle := func(taskID, key string) *calendarCycleData {
		id := calendarCycleID{taskID, key}
		if cycles[id] == nil {
			cycles[id] = &calendarCycleData{}
		}
		return cycles[id]
	}
	singleTasks := map[string]calendarSingleProjection{}
	singlePending := map[string]calendarNotification{}
	for _, t := range tasks {
		if calendarSingleRule(t.Rule) {
			if p, ok := calendarSingle(t); ok {
				singleTasks[t.ID] = p
				needCycle(t.ID, p.cycle.Key).end = p.cycle.End
			}
		}
	}
	truncated := false
	// Keep records in their original rule/cycle, even if the task was edited or
	// archived. Read both scheduled and actual execution months: an execution
	// after midnight belongs to its actual date, while its original scheduled
	// instant still consumes the original cycle/month's reminder budget.
	rows, dbErr := a.db.QueryContext(r.Context(), `SELECT id,task_id,task_title,cycle_key,scheduled_at,sent_at,status,error,rule_snapshot,channel,source,reason,expires_at FROM (SELECT id,user_id,task_id,task_title,cycle_key,scheduled_at,sent_at,status,error,rule_snapshot,'email' AS channel,'legacy' AS source,'' AS reason,'' AS expires_at FROM notifications UNION ALL SELECT id,user_id,task_id,title,cycle_key,scheduled_at,sent_at,status,error,rule_snapshot,channel,source,reason,expires_at FROM reminder_plans) WHERE user_id=? AND status<>'cancelled' AND ((unixepoch(scheduled_at)>=? AND unixepoch(scheduled_at)<?) OR ((status IN ('sent','accepted') OR (status='delivered' AND channel<>'calendar')) AND unixepoch(sent_at)>=? AND unixepoch(sent_at)<?) OR (status IN ('pending','scheduled','sending') AND (expires_at='' OR unixepoch(expires_at)>?))) ORDER BY unixepoch(scheduled_at),id`, userID, start.Unix(), end.Unix(), start.Unix(), end.Unix(), now.Unix())
	if dbErr != nil {
		dbFailure(w)
		return
	}
	for rows.Next() {
		var n calendarNotification
		var raw, expiresAt string
		if dbErr = rows.Scan(&n.entry.ID, &n.entry.TaskID, &n.entry.TaskTitle, &n.entry.CycleKey, &n.entry.ScheduledAt, &n.entry.SentAt, &n.entry.Status, &n.entry.Error, &raw, &n.entry.Channel, &n.entry.Source, &n.entry.Reason, &expiresAt); dbErr != nil {
			break
		}
		n.at, dbErr = time.Parse(time.RFC3339Nano, n.entry.ScheduledAt)
		if dbErr != nil {
			break
		}
		scheduledInMonth := !n.at.Before(start) && n.at.Before(end)
		displayAt := n.at
		if calendarDelivered(n.entry) {
			if sentAt, err := time.Parse(time.RFC3339Nano, n.entry.SentAt); err == nil {
				displayAt = sentAt
			}
		}
		n.entry.Type = "notification"
		n.entry.At = stamp(displayAt)
		n.entry.ScheduledAt = stamp(n.at)
		if n.entry.Status == "sending" {
			n.entry.Status = "pending"
		}
		if n.entry.Status == "pending" || n.entry.Status == "scheduled" || (n.entry.Status == "delivered" && n.entry.Channel == "calendar") {
			n.entry.Type = "planned"
		}
		if calendarDelivered(n.entry) {
			n.entry.ReminderCount = 1
		}
		if n.entry.Type == "notification" {
			n.entry.LastReminderAt = n.entry.At
			if sentAt, err := time.Parse(time.RFC3339Nano, n.entry.SentAt); err == nil {
				n.entry.LastReminderAt = stamp(sentAt)
			}
			n.entry.LastReminderStatus = n.entry.Status
		}
		_ = json.Unmarshal([]byte(raw), &n.rule)
		data := needCycle(n.entry.TaskID, n.entry.CycleKey)
		if c, ok := schedule.CycleAt(n.rule, n.at); ok && c.Key == n.entry.CycleKey {
			n.entry.CycleEnd = calendarCycleEnd(c.End)
			data.end = c.End
		}
		// Calendar insertion consumes its existing execution budget but does
		// not prove an actual reminder was presented to the user.
		if scheduledInMonth && (calendarDelivered(n.entry) || n.entry.Status == "delivered" || (n.entry.Status == "scheduled" && n.entry.Channel == "calendar" && !n.at.After(now))) {
			data.sentInMonth = append(data.sentInMonth, n.at)
		}
		p, single := singleTasks[n.entry.TaskID]
		waiting := n.entry.Status == "pending" || n.entry.Status == "scheduled"
		calendarHistory := n.entry.Status == "scheduled" && n.entry.Channel == "calendar" && !n.at.After(now)
		if waiting && !calendarHistory && (single || calendarSingleRule(n.rule)) {
			// Resolve a single task's next pending plan globally, not separately
			// for each queried month. Expired/stale plans are not task history.
			valid := single && !p.t.Paused && !p.t.Archived && n.entry.CycleKey == p.cycle.Key && !n.at.Before(p.cycle.Start) && n.at.Before(p.cycle.End)
			if expiresAt != "" {
				expires, err := time.Parse(time.RFC3339Nano, expiresAt)
				valid = valid && err == nil && expires.After(now)
			} else {
				valid = valid && p.cycle.End.After(now)
			}
			if valid {
				previous, exists := singlePending[n.entry.TaskID]
				if !exists || n.at.Before(previous.at) || (n.at.Equal(previous.at) && n.entry.ID < previous.entry.ID) {
					singlePending[n.entry.TaskID] = n
				}
			}
			continue
		}
		if displayAt.Before(start) || !displayAt.Before(end) {
			continue
		}
		day := calendarDayID{n.entry.TaskID, displayAt.In(loc).Format("2006-01-02")}
		n.entry.LocalDate = day.day
		if previous, ok := notifications[day]; ok {
			merged := calendarMerge(previous.entry, n.entry, now)
			if !calendarPrefers(n.entry, previous.entry, now) {
				n = previous
			}
			n.entry = merged
		}
		notifications[day] = n
	}
	if dbErr == nil {
		dbErr = rows.Err()
	}
	rows.Close()
	if dbErr != nil {
		dbFailure(w)
		return
	}
	rows, dbErr = a.db.QueryContext(r.Context(), `SELECT `+completionColumns+` FROM completions WHERE user_id=? AND unixepoch(created_at)>=? AND unixepoch(created_at)<? ORDER BY unixepoch(created_at),id`, userID, start.Unix(), end.Unix())
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
		if calendarSingleRule(t.Rule) {
			continue
		}
		from := start.Add(-time.Nanosecond)
		if effective := calendarEffective(t); effective.After(from) {
			from = effective
		}
		p := projection{t: t, cycles: map[string]schedule.Cycle{}}
		// Daily rule overviews remain visible beyond the agent's single pending
		// plan. They describe intended task dates, never an executed reminder.
		snooze, hasSnooze := calendarSnooze(t)
		slots := schedule.Due(t.Rule, from, end.Add(-time.Nanosecond))
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
		mode := ""
		if calendarDelivered(n.entry) {
			mode = n.rule.Mode
		}
		n.entry.State = calendarState(n.at, cycle, data, mode, now)
		entries = append(entries, n.entry)
	}
	for id, p := range singleTasks {
		data := cycles[calendarCycleID{id, p.cycle.Key}]
		if p.t.Paused || p.t.Archived || !p.cycle.End.After(now) || stopForRule(p.t.Rule, !data.completedAt.IsZero(), data.sent) {
			continue
		}
		at := p.first
		if !at.After(now) {
			at = now
		}
		entry := calendarEntry{ID: "planned:" + id + ":pending", Type: "planned", TaskID: id, TaskTitle: p.t.Title, ScheduledAt: stamp(p.first), CycleKey: p.cycle.Key, CycleEnd: calendarCycleEnd(p.cycle.End)}
		if snooze, ok := calendarSnooze(p.t); ok && snooze.At.After(now) {
			at, entry.ScheduledAt = snooze.At, stamp(snooze.At)
		}
		if pending, ok := singlePending[id]; ok {
			entry, at = pending.entry, pending.at
			// An overdue but still executable plan belongs to today's one task;
			// its original scheduled_at remains available in the details.
			if at.In(loc).Format("2006-01-02") < now.In(loc).Format("2006-01-02") {
				at = now
			}
		}
		if at.Before(start) || !at.Before(end) {
			continue
		}
		entry.At = stamp(at)
		entry.State = calendarState(at, p.cycle, data, "", now)
		entries = append(entries, entry)
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
		stream := &calendarPlanStream{t: p.t, cycles: p.cycles}
		stream.next = func() (schedule.Occurrence, bool) {
			for {
				slot, ok := candidate()
				if !ok {
					return schedule.Occurrence{}, false
				}
				at := stamp(slot.At)
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
	// Merge actual history, the unique pending single task, and recurring
	// date occurrences before applying display limits.
	daily := map[calendarDayID]calendarEntry{}
	addDaily := func(entry calendarEntry) {
		at, _ := time.Parse(time.RFC3339Nano, entry.At)
		entry.LocalDate = at.In(loc).Format("2006-01-02")
		if entry.Type == "completion" {
			entry.CompletionCount = 1
		}
		key := calendarDayID{entry.TaskID, entry.LocalDate}
		if previous, ok := daily[key]; ok {
			entry = calendarMerge(previous, entry, now)
		}
		daily[key] = entry
	}
	for _, entry := range entries {
		addDaily(entry)
	}
	for streams.Len() > 0 {
		stream := heap.Pop(&streams).(*calendarPlanStream)
		slot := stream.slot
		at := stamp(slot.At)
		c := stream.cycles[slot.CycleKey]
		data := cycles[calendarCycleID{stream.t.ID, slot.CycleKey}]
		addDaily(calendarEntry{ID: "planned:" + stream.t.ID + ":" + at, Type: "planned", TaskID: stream.t.ID, TaskTitle: stream.t.Title, At: at, ScheduledAt: at, State: calendarState(slot.At, c, data, "", now), CycleKey: slot.CycleKey, CycleEnd: calendarCycleEnd(c.End)})
		if next, ok := stream.next(); ok {
			stream.slot = next
			heap.Push(&streams, stream)
		}
	}
	entries = make([]calendarEntry, 0, len(daily))
	for _, entry := range daily {
		entries = append(entries, entry)
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
