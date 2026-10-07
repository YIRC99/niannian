package app

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"niannian/internal/schedule"
	"sort"
	"strings"
	"time"
	"unicode/utf8"
)

// One finite authorization, not a queue of execution rows. Keep old grants so
// delayed offline receipts can still be checked after an edit or device switch.
const reminderLocalFlowSchema = `
CREATE TABLE IF NOT EXISTS reminder_local_flows(id TEXT PRIMARY KEY,root_plan_id TEXT NOT NULL UNIQUE REFERENCES reminder_plans(id),user_id TEXT NOT NULL REFERENCES users(id),task_id TEXT NOT NULL REFERENCES tasks(id),device_id TEXT NOT NULL,generation TEXT NOT NULL,cycle_key TEXT NOT NULL,valid_until TEXT NOT NULL,state TEXT NOT NULL,blueprint TEXT NOT NULL,receipts TEXT NOT NULL DEFAULT '{}');
CREATE INDEX IF NOT EXISTS reminder_local_flow_task ON reminder_local_flows(task_id,state,valid_until);
`

// Only a received phone cancellation is a finished attempt. Server revocation
// also cancels plan rows (possibly with reported_at), but never writes receipts.
const receivedLocalFlowCancellationSQL = `(status='cancelled' AND EXISTS (
	SELECT 1 FROM reminder_local_flows f, json_each(f.receipts) receipt
	WHERE f.user_id=reminder_plans.user_id AND f.task_id=reminder_plans.task_id
	AND f.device_id=reminder_plans.device_id AND f.generation=reminder_plans.generation
	AND f.cycle_key=reminder_plans.cycle_key
	AND receipt.key=reminder_plans.id AND receipt.value='cancelled'))`

type localFlowStep struct {
	ID          string `json:"id"`
	Index       int    `json:"index"`
	Channel     string `json:"channel"`
	Title       string `json:"title"`
	Body        string `json:"body"`
	Reason      string `json:"reason"`
	ScheduledAt string `json:"scheduled_at"`
	ExpiresAt   string `json:"expires_at"`
	WaitMinutes int    `json:"wait_minutes"`
	Status      string `json:"status"`
	OccurredAt  string `json:"occurred_at,omitempty"`
}
type localFlow struct {
	ID                 string          `json:"id"`
	RootPlanID         string          `json:"root_plan_id"`
	TaskID             string          `json:"task_id"`
	DeviceID           string          `json:"device_id"`
	Generation         string          `json:"generation"`
	Source             string          `json:"source"`
	CycleKey           string          `json:"cycle_key"`
	IssuedAt           string          `json:"issued_at"`
	ValidUntil         string          `json:"valid_until"`
	Timezone           string          `json:"timezone"`
	QuietStart         string          `json:"quiet_start"`
	QuietEnd           string          `json:"quiet_end"`
	MinInterval        int             `json:"min_interval_minutes"`
	StopAfterSuccess   bool            `json:"stop_after_success"`
	RemainingReminders int             `json:"remaining_reminders"`
	Steps              []localFlowStep `json:"steps"`
	Rule               schedule.Rule   `json:"rule"`
}
type localFlowResult struct {
	FlowID     string `json:"flow_id"`
	StepID     string `json:"step_id"`
	Status     string `json:"status"`
	OccurredAt string `json:"occurred_at"`
	Error      string `json:"error"`
}
type localFlowAck struct {
	FlowID   string `json:"flow_id"`
	StepID   string `json:"step_id"`
	Accepted bool   `json:"accepted"`
	Error    string `json:"error,omitempty"`
}

func localFlowStamp(t time.Time) string { return t.UTC().Format("2006-01-02T15:04:05.000Z") }
func phoneFlowChannel(ch string) bool   { return ch == "notification" || ch == "alarm" }
func (a *App) localFlowReserved(taskID string) (bool, error) {
	var n int
	err := a.db.QueryRow(`SELECT COUNT(*) FROM reminder_local_flows WHERE task_id=? AND state IN ('active','revoked') AND julianday(valid_until)>julianday(?)`, taskID, stamp(a.now())).Scan(&n)
	return n > 0, err
}

// Call under a.mu. A revoked offline device cannot learn about revocation until
// its next sync; reserve the old window until cancellation is acknowledged.
func (a *App) reconcileLocalFlows(user string) error {
	rows, err := a.db.Query(`SELECT id,task_id,device_id,generation,cycle_key,valid_until FROM reminder_local_flows WHERE user_id=? AND state IN ('active','revoked')`, user)
	if err != nil {
		return err
	}
	type grant struct{ id, task, device, generation, cycle, until string }
	grants := []grant{}
	for rows.Next() {
		var g grant
		if err = rows.Scan(&g.id, &g.task, &g.device, &g.generation, &g.cycle, &g.until); err != nil {
			rows.Close()
			return err
		}
		grants = append(grants, g)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return err
	}
	s, active, err := a.reminderSettingsFor(user)
	if err != nil {
		return err
	}
	caps, err := a.reminderDeviceCapabilities(user, active)
	if err != nil {
		return err
	}
	channels := availableReminderChannels(s, caps)
	for _, g := range grants {
		until, e := time.Parse(time.RFC3339Nano, g.until)
		state := ""
		if e != nil || !a.now().Before(until) {
			state = "expired"
		} else {
			t, e := a.findTask(g.task, user)
			if e != nil && e != sql.ErrNoRows {
				return e
			}
			if e == sql.ErrNoRows || t.Paused || !s.Enabled || active != g.device || reminderGeneration(t, s, active, channels) != g.generation {
				state = "revoked"
			} else {
				complete, sent, e := a.cycleCounts(g.task, g.cycle)
				if e != nil {
					return e
				}
				if stopForRule(t.Rule, complete, sent) {
					state = "revoked"
				}
			}
		}
		if state != "" {
			if _, err = a.db.Exec(`UPDATE reminder_local_flows SET state=? WHERE id=?`, state, g.id); err != nil {
				return err
			}
			if state == "revoked" {
				if _, err = a.db.Exec(`UPDATE reminder_plans SET status='cancelled',error='离线流程授权已撤销' WHERE id=(SELECT root_plan_id FROM reminder_local_flows WHERE id=?) AND status IN ('pending','scheduled')`, g.id); err != nil {
					return err
				}
			}
		}
	}
	return nil
}

// Existing plans encode the chosen strategy index in their server-generated
// reason. Using that index preserves edits/permission changes without guessing
// from a channel that can legitimately occur in several strategy steps.
func localFlowStartIndex(p reminderPlan, strategy reminderStrategy) int {
	index := 0
	if pos := strings.LastIndex(p.Reason, "；流程第"); pos >= 0 {
		var ordinal int
		if _, err := fmt.Sscanf(p.Reason[pos:], "；流程第%d步", &ordinal); err == nil && ordinal > 0 {
			index = ordinal - 1
		}
	}
	return min(index, len(strategy.Steps)-1)
}
func (a *App) issueLocalFlow(p reminderPlan, user, device string, availableSteps int) (*localFlow, error) {
	if availableSteps <= 0 {
		return nil, nil
	}
	if !phoneFlowChannel(p.Channel) {
		return nil, nil
	}
	if reserved, e := a.localFlowReserved(p.TaskID); e != nil || reserved {
		return nil, e
	}
	var existing int
	if e := a.db.QueryRow(`SELECT COUNT(*) FROM reminder_local_flows WHERE root_plan_id=?`, p.ID).Scan(&existing); e != nil {
		return nil, e
	}
	if existing > 0 {
		return nil, nil
	}
	t, err := a.findTask(p.TaskID, user)
	if err != nil {
		return nil, err
	}
	s, active, err := a.reminderSettingsFor(user)
	if err != nil {
		return nil, err
	}
	caps, err := a.reminderDeviceCapabilities(user, active)
	if err != nil {
		return nil, err
	}
	channels := availableReminderChannels(s, caps)
	if active != device || !caps.LocalFlowV1 || !s.Enabled || t.Paused || p.Generation != reminderGeneration(t, s, device, channels) {
		return nil, nil
	}
	strategy, err := a.storedReminderStrategy(t.ID)
	if err != nil {
		return nil, err
	}
	if len(strategy.Steps) == 0 || strategy.Generation != p.Generation {
		return nil, nil
	}
	first, err := time.Parse(time.RFC3339Nano, p.ScheduledAt)
	if err != nil {
		return nil, err
	}
	if !first.Equal(first.Truncate(time.Millisecond)) {
		first = first.Truncate(time.Millisecond).Add(time.Millisecond)
	}
	cycle, ok := schedule.CycleAt(t.Rule, first)
	if !ok || cycle.Key != p.CycleKey {
		return nil, nil
	}
	complete, sent, err := a.cycleCounts(t.ID, p.CycleKey)
	if err != nil {
		return nil, err
	}
	if stopForRule(t.Rule, complete, sent) {
		return nil, nil
	}
	limit := a.now().Add(24 * time.Hour)
	if cycle.End.Before(limit) {
		limit = cycle.End
	}
	// Keep exact millisecond deadline points inclusive. Other fractional limits
	// truncate conservatively instead of granting time beyond the true boundary.
	if end, e := time.Parse(time.RFC3339Nano, t.Rule.EndAt); e == nil && end.Equal(end.Truncate(time.Millisecond)) && limit.After(end) && limit.Sub(end) < time.Millisecond {
		limit = end.Add(time.Millisecond)
	} else {
		limit = limit.Truncate(time.Millisecond)
	}
	budget := min(6, availableSteps)
	if t.Rule.MaxReminders > 0 {
		budget = min(budget, t.Rule.MaxReminders-sent)
	}
	if t.Rule.Mode == "notify_once" {
		budget = min(budget, 1-sent)
	}
	if budget <= 0 {
		return nil, nil
	}
	f := &localFlow{ID: randomID(), RootPlanID: p.ID, TaskID: t.ID, DeviceID: device, Generation: p.Generation, CycleKey: p.CycleKey, IssuedAt: localFlowStamp(a.now()), Timezone: s.Timezone, QuietStart: s.QuietStart, QuietEnd: s.QuietEnd, MinInterval: max(1, s.MinInterval), StopAfterSuccess: t.Rule.Mode == "notify_once", RemainingReminders: budget, Steps: []localFlowStep{}, Rule: t.Rule}
	f.Source = p.Source
	start := localFlowStartIndex(p, strategy)
	for i := start; i < len(strategy.Steps) && len(f.Steps) < min(6, availableSteps); i++ {
		step := strategy.Steps[i]
		if !phoneFlowChannel(step.Channel) || !containsReminderChannel(channels, step.Channel) {
			break
		}
		at := first
		wait := 0
		if len(f.Steps) > 0 {
			prior, _ := time.Parse(time.RFC3339Nano, f.Steps[len(f.Steps)-1].ScheduledAt)
			wait = max(s.MinInterval, step.WaitMinutes)
			if t.Rule.Kind == "minute_interval" {
				wait = max(wait, t.Rule.RepeatMinutes)
			}
			at = prior.Add(time.Duration(wait) * time.Minute)
		}
		if quietReminder(at, s, t.Rule.Timezone) {
			at = afterReminderQuiet(at, s, t.Rule.Timezone)
		}
		c, valid := schedule.CycleAt(t.Rule, at)
		if !valid || c.Key != cycle.Key || !at.Before(limit) {
			break
		}
		expires := at.Add(time.Duration(max(1, s.MinInterval)) * time.Minute)
		if expires.After(limit) {
			expires = limit
		}
		id := randomID()
		status := "pending"
		if len(f.Steps) == 0 {
			id = p.ID
			status = p.Status
			storedExpiry, _ := time.Parse(time.RFC3339Nano, p.ExpiresAt)
			if storedExpiry.Before(expires) {
				expires = storedExpiry
			}
		}
		if !expires.After(a.now()) || !expires.After(at) {
			break
		}
		f.Steps = append(f.Steps, localFlowStep{ID: id, Index: len(f.Steps), Channel: step.Channel, Title: p.Title, Body: p.Body, Reason: step.Reason, ScheduledAt: localFlowStamp(at), ExpiresAt: localFlowStamp(expires), WaitMinutes: wait, Status: status})
	}
	if len(f.Steps) == 0 {
		return nil, nil
	}
	f.RemainingReminders = min(f.RemainingReminders, len(f.Steps))
	f.ValidUntil = f.Steps[len(f.Steps)-1].ExpiresAt
	raw, _ := json.Marshal(f)
	_, err = a.db.Exec(`INSERT INTO reminder_local_flows(id,root_plan_id,user_id,task_id,device_id,generation,cycle_key,valid_until,state,blueprint) VALUES(?,?,?,?,?,?,?,?,'active',?)`, f.ID, p.ID, user, t.ID, device, p.Generation, p.CycleKey, f.ValidUntil, string(raw))
	return f, err
}

func (a *App) localFlowsForSync(user, device string, issue bool) ([]localFlow, []string, error) {
	flows := []localFlow{}
	revoked := []string{}
	if err := a.reconcileLocalFlows(user); err != nil {
		return flows, revoked, err
	}
	if issue {
		availableSteps := 128
		grants, e := a.db.Query(`SELECT blueprint FROM reminder_local_flows WHERE user_id=? AND device_id=? AND state='active'`, user, device)
		if e != nil {
			return flows, revoked, e
		}
		for grants.Next() {
			var raw string
			var f localFlow
			if e = grants.Scan(&raw); e != nil {
				grants.Close()
				return flows, revoked, e
			}
			if e = json.Unmarshal([]byte(raw), &f); e != nil {
				grants.Close()
				return flows, revoked, e
			}
			availableSteps -= len(f.Steps)
		}
		e = grants.Err()
		grants.Close()
		if e != nil {
			return flows, revoked, e
		}
		rows, err := a.db.Query(`SELECT `+reminderPlanColumns+` FROM reminder_plans WHERE user_id=? AND device_id=? AND status IN ('pending','scheduled') AND channel IN ('notification','alarm') AND expires_at>? ORDER BY scheduled_at,id`, user, device, stamp(a.now()))
		if err != nil {
			return flows, revoked, err
		}
		plans := []reminderPlan{}
		for rows.Next() {
			p, e := scanReminderPlan(rows)
			if e != nil {
				rows.Close()
				return flows, revoked, e
			}
			plans = append(plans, p)
		}
		err = rows.Err()
		rows.Close()
		if err != nil {
			return flows, revoked, err
		}
		for _, p := range plans {
			f, e := a.issueLocalFlow(p, user, device, availableSteps)
			if e != nil {
				return flows, revoked, e
			}
			if f != nil {
				availableSteps -= len(f.Steps)
			}
		}
	}
	rows, err := a.db.Query(`SELECT id,state,blueprint FROM reminder_local_flows WHERE user_id=? AND device_id=? AND state IN ('active','revoked')`, user, device)
	if err != nil {
		return flows, revoked, err
	}
	for rows.Next() {
		var id, state, raw string
		if err = rows.Scan(&id, &state, &raw); err != nil {
			rows.Close()
			return flows, revoked, err
		}
		if state == "revoked" {
			revoked = append(revoked, id)
			continue
		}
		var f localFlow
		if err = json.Unmarshal([]byte(raw), &f); err != nil {
			rows.Close()
			return flows, revoked, err
		}
		if issue {
			flows = append(flows, f)
		}
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return flows, revoked, err
	}
	for i := range flows {
		for j := range flows[i].Steps {
			var status, occurred string
			e := a.db.QueryRow(`SELECT status,CASE WHEN sent_at<>'' THEN sent_at ELSE reported_at END FROM reminder_plans WHERE id=?`, flows[i].Steps[j].ID).Scan(&status, &occurred)
			if e == nil {
				flows[i].Steps[j].Status = status
				if at, e := time.Parse(time.RFC3339Nano, occurred); e == nil && (status == "delivered" || status == "failed" || status == "cancelled" || status == "unconfirmed") {
					flows[i].Steps[j].OccurredAt = localFlowStamp(at)
				}
			} else if e != sql.ErrNoRows {
				return flows, revoked, e
			}
		}
	}
	return flows, revoked, nil
}

func (a *App) receiveLocalFlowResults(user, device string, results []localFlowResult, closed []string, detach bool) ([]localFlowAck, []string, error) {
	acks := []localFlowAck{}
	closedAcks := []string{}
	// Process each flow in its authorized step order, even after an offline batch
	// was reordered in transport. The caller holds a.mu for the complete batch.
	type receipt struct {
		result localFlowResult
		flow   localFlow
		valid  bool
		index  int
	}
	items := []receipt{}
	for _, r := range results {
		var raw string
		f := localFlow{}
		valid := false
		index := 0
		err := a.db.QueryRow(`SELECT blueprint FROM reminder_local_flows WHERE id=? AND user_id=? AND device_id=?`, r.FlowID, user, device).Scan(&raw)
		if err != nil && err != sql.ErrNoRows {
			return acks, closedAcks, err
		}
		if err == nil {
			if err = json.Unmarshal([]byte(raw), &f); err != nil {
				return acks, closedAcks, err
			}
			for i, s := range f.Steps {
				if s.ID == r.StepID {
					valid = true
					index = i
					break
				}
			}
		}
		items = append(items, receipt{r, f, valid, index})
	}
	sort.SliceStable(items, func(i, j int) bool {
		if items[i].result.FlowID == items[j].result.FlowID {
			return items[i].index < items[j].index
		}
		return items[i].result.FlowID < items[j].result.FlowID
	})
	for _, item := range items {
		ack := localFlowAck{FlowID: item.result.FlowID, StepID: item.result.StepID}
		if !item.valid {
			ack.Error = "离线授权不存在或不属于当前账号设备"
		} else {
			message, err := a.recordLocalFlowResult(user, device, item.flow, item.index, item.result)
			if err != nil {
				return acks, closedAcks, err
			}
			ack.Error = message
			ack.Accepted = message == ""
		}
		acks = append(acks, ack)
	}
	for _, id := range closed {
		var root string
		err := a.db.QueryRow(`SELECT root_plan_id FROM reminder_local_flows WHERE id=? AND user_id=? AND device_id=?`, id, user, device).Scan(&root)
		if err == sql.ErrNoRows {
			continue
		}
		if err != nil {
			return acks, closedAcks, err
		}
		if _, err = a.db.Exec(`UPDATE reminder_local_flows SET state='closed' WHERE id=?`, id); err != nil {
			return acks, closedAcks, err
		}
		if _, err = a.db.Exec(`UPDATE reminder_plans SET status='cancelled',error='本机已关闭离线流程' WHERE id=? AND status IN ('pending','scheduled')`, root); err != nil {
			return acks, closedAcks, err
		}
		closedAcks = append(closedAcks, id)
	}
	if detach {
		if _, err := a.db.Exec(`UPDATE reminder_local_flows SET state='closed' WHERE user_id=? AND device_id=? AND state IN ('active','revoked')`, user, device); err != nil {
			return acks, closedAcks, err
		}
	}
	return acks, closedAcks, nil
}

func (a *App) recordLocalFlowResult(user, device string, f localFlow, index int, r localFlowResult) (string, error) {
	step := f.Steps[index]
	if r.Status != "delivered" && r.Status != "failed" && r.Status != "cancelled" {
		return "不支持的离线执行状态", nil
	}
	if utf8.RuneCountInString(r.Error) > 300 {
		return "执行说明过长", nil
	}
	at, err := time.Parse(time.RFC3339Nano, r.OccurredAt)
	if err != nil || at.After(a.now().Add(time.Minute)) {
		return "执行时间不正确", nil
	}
	issued, _ := time.Parse(time.RFC3339Nano, f.IssuedAt)
	scheduled, _ := time.Parse(time.RFC3339Nano, step.ScheduledAt)
	expires, _ := time.Parse(time.RFC3339Nano, step.ExpiresAt)
	if at.Before(issued.Add(-time.Minute)) {
		return "执行时间早于授权", nil
	}
	if !phoneFlowChannel(step.Channel) || f.DeviceID != device {
		return "执行渠道或设备不符合授权", nil
	}
	var receiptJSON string
	if err = a.db.QueryRow(`SELECT receipts FROM reminder_local_flows WHERE id=?`, f.ID).Scan(&receiptJSON); err != nil {
		return "", err
	}
	receipts := map[string]string{}
	if err = json.Unmarshal([]byte(receiptJSON), &receipts); err != nil {
		return "", err
	}
	if received := receipts[step.ID]; received != "" {
		if received == r.Status {
			return "", nil
		}
		return "执行回执已终结，不能改写", nil
	}
	var oldStatus string
	err = a.db.QueryRow(`SELECT status FROM reminder_plans WHERE id=? AND user_id=? AND device_id=? AND generation=? AND cycle_key=?`, step.ID, user, device, f.Generation, f.CycleKey).Scan(&oldStatus)
	if err != nil && err != sql.ErrNoRows {
		return "", err
	}
	if oldStatus == r.Status {
		receipts[step.ID] = r.Status
		raw, _ := json.Marshal(receipts)
		_, err = a.db.Exec(`UPDATE reminder_local_flows SET receipts=? WHERE id=?`, string(raw), f.ID)
		return "", err
	}
	if oldStatus == "delivered" || oldStatus == "failed" {
		return "执行记录已终结，不能改写", nil
	}
	if r.Status == "delivered" {
		if at.Before(scheduled) || !at.Before(expires) {
			return "执行时间超出授权窗口", nil
		}
		s := reminderSettings{Timezone: f.Timezone, QuietStart: f.QuietStart, QuietEnd: f.QuietEnd}
		if quietReminder(at, s, f.Rule.Timezone) {
			return "执行时间位于静默时段", nil
		}
		cycle, ok := schedule.CycleAt(f.Rule, at)
		if !ok || cycle.Key != f.CycleKey {
			return "执行已超出任务周期或截止", nil
		}
		for priorIndex := 0; priorIndex < index; priorIndex++ {
			var predecessor, priorAt string
			priorID := f.Steps[priorIndex].ID
			err = a.db.QueryRow(`SELECT status,CASE WHEN status='delivered' THEN sent_at ELSE reported_at END FROM reminder_plans WHERE id=?`, priorID).Scan(&predecessor, &priorAt)
			if err == sql.ErrNoRows || (predecessor != "delivered" && predecessor != "failed" && predecessor != "cancelled") || (predecessor == "cancelled" && receipts[priorID] == "") {
				return "请先回传前一步执行结果", nil
			}
			if err != nil {
				return "", err
			}
			ended, e := time.Parse(time.RFC3339Nano, priorAt)
			if e != nil || (priorIndex == index-1 && at.Before(ended.Add(time.Duration(max(f.MinInterval, step.WaitMinutes))*time.Minute))) {
				return "尚未达到前一步后的等待时间", nil
			}
		}
		_, success, countErr := a.cycleCounts(f.TaskID, f.CycleKey)
		if countErr != nil {
			return "", countErr
		}
		limit := f.Rule.MaxReminders
		if f.StopAfterSuccess {
			limit = 1
		}
		if limit > 0 && success >= limit {
			return "已达到本周期提醒次数", nil
		}
		if message, e := a.validateLocalExecutionLimits(user, f, at); e != nil || message != "" {
			return message, e
		}
	}
	raw, _ := json.Marshal(f.Rule)
	sentAt := ""
	if r.Status == "delivered" {
		sentAt = stamp(at)
	}
	tx, err := a.db.Begin()
	if err != nil {
		return "", err
	}
	defer tx.Rollback()
	if oldStatus != "" {
		_, err = tx.Exec(`UPDATE reminder_plans SET status=?,error=?,sent_at=?,reported_at=? WHERE id=?`, r.Status, r.Error, sentAt, stamp(at), step.ID)
	} else {
		_, err = tx.Exec(`INSERT INTO reminder_plans(id,user_id,task_id,device_id,title,body,cycle_key,channel,scheduled_at,expires_at,status,source,reason,error,generation,sent_at,reported_at,rule_snapshot) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`, step.ID, user, f.TaskID, device, step.Title, step.Body, f.CycleKey, step.Channel, stamp(scheduled), stamp(expires), r.Status, f.Source, step.Reason, r.Error, f.Generation, sentAt, stamp(at), string(raw))
	}
	if err != nil {
		return "", err
	}
	receipts[step.ID] = r.Status
	if len(receipts) == len(f.Steps) || (r.Status == "delivered" && f.StopAfterSuccess) {
		_, err = tx.Exec(`UPDATE reminder_local_flows SET state='closed' WHERE id=?`, f.ID)
	}
	if err != nil {
		return "", err
	}
	receiptBytes, _ := json.Marshal(receipts)
	if _, err = tx.Exec(`UPDATE reminder_local_flows SET receipts=? WHERE id=?`, string(receiptBytes), f.ID); err != nil {
		return "", err
	}
	return "", tx.Commit()
}

// The next offline step is an authorized promise, not an execution-history row.
func (a *App) nextLocalFlowReminder(t task, device string) string {
	if t.Paused || t.Archived {
		return ""
	}
	rows, err := a.db.Query(`SELECT blueprint FROM reminder_local_flows WHERE task_id=? AND user_id=? AND device_id=? AND state='active'`, t.ID, t.UserID, device)
	if err != nil {
		return ""
	}
	flows := []localFlow{}
	for rows.Next() {
		var raw string
		var f localFlow
		if rows.Scan(&raw) != nil || json.Unmarshal([]byte(raw), &f) != nil {
			rows.Close()
			return ""
		}
		flows = append(flows, f)
	}
	rows.Close()
	s, active, err := a.reminderSettingsFor(t.UserID)
	if err != nil || !s.Enabled || active != device {
		return ""
	}
	caps, err := a.reminderDeviceCapabilities(t.UserID, device)
	if err != nil {
		return ""
	}
	for _, f := range flows {
		until, e := time.Parse(time.RFC3339Nano, f.ValidUntil)
		if e != nil || !a.now().Before(until) || f.Generation != reminderGeneration(t, s, device, availableReminderChannels(s, caps)) {
			continue
		}
		complete, sent, e := a.cycleCounts(t.ID, f.CycleKey)
		if e != nil || stopForRule(t.Rule, complete, sent) {
			continue
		}
		for _, step := range f.Steps {
			expires, e := time.Parse(time.RFC3339Nano, step.ExpiresAt)
			if e != nil || !a.now().Before(expires) {
				continue
			}
			var status string
			e = a.db.QueryRow(`SELECT status FROM reminder_plans WHERE id=?`, step.ID).Scan(&status)
			if e != nil && e != sql.ErrNoRows {
				return ""
			}
			if e == sql.ErrNoRows || status == "pending" || status == "scheduled" {
				return step.ScheduledAt
			}
		}
	}
	return ""
}

// Parse timestamps before comparing them: historical records can use offsets
// and different fractional precision. Include old mail in the per-task gap;
// the phone's 32/128 daily limits cover actual notification/alarm presentations.
func (a *App) validateLocalExecutionLimits(user string, f localFlow, at time.Time) (string, error) {
	rows, err := a.db.Query(`SELECT task_id,channel,CASE WHEN sent_at<>'' THEN sent_at ELSE scheduled_at END FROM reminder_plans WHERE user_id=? AND (status IN ('delivered','accepted') OR (status='scheduled' AND channel='calendar')) UNION ALL SELECT task_id,'email',sent_at FROM notifications WHERE user_id=? AND status='sent'`, user, user)
	if err != nil {
		return "", err
	}
	defer rows.Close()
	zone, e := time.LoadLocation(f.Timezone)
	if e != nil {
		return "授权时区无效", nil
	}
	today := at.In(zone).Format("2006-01-02")
	last := time.Time{}
	taskCount, total := 0, 0
	for rows.Next() {
		var taskID, channel, raw string
		if err = rows.Scan(&taskID, &channel, &raw); err != nil {
			return "", err
		}
		occurred, e := time.Parse(time.RFC3339Nano, raw)
		if e != nil || occurred.After(at) {
			continue
		}
		if taskID == f.TaskID && occurred.After(last) {
			last = occurred
		}
		if phoneFlowChannel(channel) && occurred.In(zone).Format("2006-01-02") == today {
			total++
			if taskID == f.TaskID {
				taskCount++
			}
		}
	}
	if err = rows.Err(); err != nil {
		return "", err
	}
	if !last.IsZero() && at.Sub(last) < time.Duration(f.MinInterval)*time.Minute {
		return "未达到最短提醒间隔", nil
	}
	if taskCount >= 32 || total >= 128 {
		return "已达到本机每日提醒上限", nil
	}
	return "", nil
}
