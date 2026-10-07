package app

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"net/http"
	"sort"
	"strings"
	"time"
	"unicode/utf8"

	"niannian/internal/ai"
	"niannian/internal/schedule"
)

const reminderSchema = `
CREATE TABLE IF NOT EXISTS reminder_settings(user_id TEXT PRIMARY KEY REFERENCES users(id),settings TEXT NOT NULL,active_device TEXT NOT NULL DEFAULT '',revision TEXT NOT NULL DEFAULT '');
CREATE TABLE IF NOT EXISTS reminder_devices(user_id TEXT NOT NULL REFERENCES users(id),device_id TEXT NOT NULL,capabilities TEXT NOT NULL,updated_at TEXT NOT NULL,PRIMARY KEY(user_id,device_id));
CREATE TABLE IF NOT EXISTS reminder_plans(id TEXT PRIMARY KEY,user_id TEXT NOT NULL REFERENCES users(id),task_id TEXT NOT NULL REFERENCES tasks(id),device_id TEXT NOT NULL,title TEXT NOT NULL,body TEXT NOT NULL,cycle_key TEXT NOT NULL,channel TEXT NOT NULL,scheduled_at TEXT NOT NULL,expires_at TEXT NOT NULL,status TEXT NOT NULL DEFAULT 'pending',source TEXT NOT NULL,reason TEXT NOT NULL,error TEXT NOT NULL DEFAULT '',generation TEXT NOT NULL,sent_at TEXT NOT NULL DEFAULT '',reported_at TEXT NOT NULL DEFAULT '',rule_snapshot TEXT NOT NULL DEFAULT '{}',UNIQUE(task_id,scheduled_at,channel,generation));
CREATE INDEX IF NOT EXISTS reminder_plan_user ON reminder_plans(user_id,device_id,status,scheduled_at);
CREATE TABLE IF NOT EXISTS reminder_decisions(task_id TEXT PRIMARY KEY REFERENCES tasks(id),generation TEXT NOT NULL,channel TEXT NOT NULL,source TEXT NOT NULL,reason TEXT NOT NULL);
`

type reminderSettings struct {
	Revision          string   `json:"-"`
	Timezone          string   `json:"timezone"`
	Enabled           bool     `json:"enabled"`
	Channels          []string `json:"channels"`
	QuietStart        string   `json:"quiet_start"`
	QuietEnd          string   `json:"quiet_end"`
	MinInterval       int      `json:"min_interval_minutes"`
	Configured        bool     `json:"configured"`
	LegacyEmailActive bool     `json:"legacy_email_active"`
}
type reminderCapabilities struct {
	Notification bool `json:"notification"`
	Calendar     bool `json:"calendar"`
	Alarm        bool `json:"alarm"`
	ExactAlarm   bool `json:"exact_alarm"`
}
type reminderResult struct {
	PlanID string `json:"plan_id"`
	Status string `json:"status"`
	Error  string `json:"error"`
}
type reminderSyncInput struct {
	DeviceID     string               `json:"device_id"`
	Capabilities reminderCapabilities `json:"capabilities"`
	Timezone     string               `json:"timezone"`
	Results      []reminderResult     `json:"results"`
	Claim        bool                 `json:"claim"`
	Detach       bool                 `json:"detach"`
}
type reminderPlan struct {
	ID          string `json:"id"`
	TaskID      string `json:"task_id"`
	Title       string `json:"title"`
	Body        string `json:"body"`
	CycleKey    string `json:"cycle_key"`
	Channel     string `json:"channel"`
	ScheduledAt string `json:"scheduled_at"`
	ExpiresAt   string `json:"expires_at"`
	Status      string `json:"status"`
	Source      string `json:"source"`
	Reason      string `json:"reason"`
	Error       string `json:"error"`
	Generation  string `json:"-"`
	SentAt      string `json:"sent_at"`
}

const reminderPlanColumns = `id,task_id,title,body,cycle_key,channel,scheduled_at,expires_at,status,source,reason,error,generation,sent_at`

func scanReminderPlan(s scanner) (p reminderPlan, err error) {
	err = s.Scan(&p.ID, &p.TaskID, &p.Title, &p.Body, &p.CycleKey, &p.Channel, &p.ScheduledAt, &p.ExpiresAt, &p.Status, &p.Source, &p.Reason, &p.Error, &p.Generation, &p.SentAt)
	return
}
func (a *App) reminderSettingsFor(user string) (reminderSettings, string, error) {
	s := reminderSettings{Enabled: true, Channels: []string{"notification", "calendar", "alarm"}, QuietStart: "22:00", QuietEnd: "08:00", MinInterval: 5, LegacyEmailActive: true, Timezone: "Asia/Shanghai"}
	var raw, device string
	err := a.db.QueryRow(`SELECT settings,active_device,revision FROM reminder_settings WHERE user_id=?`, user).Scan(&raw, &device, &s.Revision)
	if err == sql.ErrNoRows {
		return s, "", nil
	}
	if err != nil {
		return s, "", err
	}
	err = json.Unmarshal([]byte(raw), &s)
	s.Configured = true
	s.LegacyEmailActive = false
	return s, device, err
}
func (a *App) getReminderSettings(w http.ResponseWriter, r *http.Request) {
	s, _, err := a.reminderSettingsFor(currentUser(r).ID)
	if err != nil {
		dbFailure(w)
		return
	}
	writeJSON(w, 200, s)
}
func (a *App) putReminderSettings(w http.ResponseWriter, r *http.Request) {
	var s reminderSettings
	if !decode(w, r, &s) {
		return
	}
	if s.Timezone == "" {
		s.Timezone = "Asia/Shanghai"
	}
	if _, err := time.LoadLocation(s.Timezone); err != nil {
		writeError(w, 400, "提醒时区不正确")
		return
	}
	if s.MinInterval < 5 || s.MinInterval > 1440 {
		writeError(w, 400, "提醒间隔需为5–1440分钟")
		return
	}
	if (s.QuietStart == "") != (s.QuietEnd == "") {
		writeError(w, 400, "请同时填写静默开始和结束时间，或同时留空")
		return
	}
	if s.QuietStart != "" {
		for _, v := range []string{s.QuietStart, s.QuietEnd} {
			if _, err := time.Parse("15:04", v); err != nil {
				writeError(w, 400, "静默时间格式应为HH:mm")
				return
			}
		}
		if s.QuietStart == s.QuietEnd {
			writeError(w, 400, "静默开始和结束时间不能相同")
			return
		}
	}
	allowed := map[string]bool{"notification": true, "calendar": true, "alarm": true, "email": true}
	seen := map[string]bool{}
	for _, ch := range s.Channels {
		if !allowed[ch] || seen[ch] {
			writeError(w, 400, "提醒渠道不正确或重复")
			return
		}
		seen[ch] = true
	}
	if len(s.Channels) == 0 && s.Enabled {
		writeError(w, 400, "请至少选择一种提醒渠道，或关闭提醒")
		return
	}
	s.Configured = true
	s.LegacyEmailActive = false
	raw, _ := json.Marshal(s)
	a.mu.Lock()
	defer a.mu.Unlock()
	if _, err := a.db.Exec(`INSERT INTO reminder_settings(user_id,settings,revision) VALUES(?,?,?) ON CONFLICT(user_id) DO UPDATE SET settings=excluded.settings,revision=excluded.revision`, currentUser(r).ID, string(raw), randomID()); err != nil {
		dbFailure(w)
		return
	}
	// A settings update invalidates every outstanding device schedule immediately
	// on the next sync, including schedules created by older app versions.
	if _, err := a.db.Exec(`UPDATE reminder_plans SET status='cancelled',error='提醒偏好已更新' WHERE user_id=? AND status IN ('pending','scheduled') AND NOT(channel='calendar' AND status='scheduled' AND scheduled_at<=?)`, currentUser(r).ID, stamp(a.now())); err != nil {
		dbFailure(w)
		return
	}
	if _, err := a.db.Exec(`DELETE FROM reminder_decisions WHERE task_id IN (SELECT id FROM tasks WHERE user_id=?)`, currentUser(r).ID); err != nil {
		dbFailure(w)
		return
	}
	writeJSON(w, 200, s)
}
func validReminderDevice(id string) bool {
	return len(id) >= 1 && len(id) <= 200 && strings.TrimSpace(id) == id
}
func (a *App) syncReminders(w http.ResponseWriter, r *http.Request) {
	var in reminderSyncInput
	if !decode(w, r, &in) {
		return
	}
	if !validReminderDevice(in.DeviceID) || len(in.Results) > 2000 {
		writeError(w, 400, "设备标识或执行反馈不正确")
		return
	}
	if in.Timezone != "" {
		if _, err := time.LoadLocation(in.Timezone); err != nil {
			writeError(w, 400, "设备时区不正确")
			return
		}
	}
	for _, result := range in.Results {
		if result.Status != "scheduled" && result.Status != "delivered" && result.Status != "failed" && result.Status != "cancelled" {
			writeError(w, 400, "不支持的执行状态")
			return
		}
		if utf8.RuneCountInString(result.Error) > 300 {
			writeError(w, 400, "执行说明过长")
			return
		}
	}
	user := currentUser(r).ID
	caps, _ := json.Marshal(in.Capabilities)
	a.mu.Lock()
	s, active, err := a.reminderSettingsFor(user)
	if err == nil {
		_, err = a.db.Exec(`INSERT INTO reminder_devices(user_id,device_id,capabilities,updated_at) VALUES(?,?,?,?) ON CONFLICT(user_id,device_id) DO UPDATE SET capabilities=excluded.capabilities,updated_at=excluded.updated_at`, user, in.DeviceID, string(caps), stamp(a.now()))
	}
	if err == nil && s.Configured && (active == "" || in.Claim) && !in.Detach {
		active = in.DeviceID
		_, err = a.db.Exec(`UPDATE reminder_settings SET active_device=? WHERE user_id=?`, active, user)
	}
	if err == nil && in.Detach {
		_, err = a.db.Exec(`UPDATE reminder_plans SET status='cancelled',error='设备已退出' WHERE user_id=? AND device_id=? AND channel<>'email' AND status IN ('pending','scheduled') AND NOT(channel='calendar' AND status='scheduled' AND scheduled_at<=?)`, user, in.DeviceID, stamp(a.now()))
		if err == nil && active == in.DeviceID {
			_, err = a.db.Exec(`UPDATE reminder_settings SET active_device='' WHERE user_id=?`, user)
			active = ""
		}
	}
	if err == nil && !in.Detach && active == in.DeviceID {
		for _, result := range in.Results {
			// Reporting is scoped to both authenticated owner and assigned device. A
			// duplicate/late event cannot regress a delivered or cancelled plan.
			status := result.Status
			sentAt := ""
			if status == "delivered" {
				sentAt = stamp(a.now())
			}
			_, err = a.db.Exec(`UPDATE reminder_plans SET status=?,error=?,sent_at=?,reported_at=? WHERE id=? AND user_id=? AND device_id=? AND channel<>'email' AND (status IN ('pending','scheduled') OR (status='unconfirmed' AND ? IN ('scheduled','delivered'))) AND (?<>'delivered' OR scheduled_at<=?)`, status, result.Error, sentAt, stamp(a.now()), result.PlanID, user, in.DeviceID, status, status, stamp(a.now().Add(time.Minute)))
			if err != nil {
				break
			}
		}
	}
	a.mu.Unlock()
	if err != nil {
		dbFailure(w)
		return
	}
	if s.Configured && s.Enabled && active == in.DeviceID && !in.Detach {
		if err = a.buildReminderPlans(r.Context(), user, in.DeviceID, in.Capabilities); err != nil {
			dbFailure(w)
			return
		}
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	s, active, err = a.reminderSettingsFor(user)
	if err != nil {
		dbFailure(w)
		return
	}
	if err = a.cancelInvalidReminderPlans(user, active, s); err != nil {
		dbFailure(w)
		return
	}
	plans := []reminderPlan{}
	cancelIDs := []string{}
	truncated := false
	rows, err := a.db.Query(`SELECT `+reminderPlanColumns+` FROM reminder_plans WHERE user_id=? AND device_id=? AND scheduled_at>=? ORDER BY scheduled_at,id`, user, in.DeviceID, stamp(a.now().Add(-7*24*time.Hour)))
	if err != nil {
		dbFailure(w)
		return
	}
	for rows.Next() {
		p, e := scanReminderPlan(rows)
		if e != nil {
			rows.Close()
			dbFailure(w)
			return
		}
		if p.Status == "cancelled" || p.Status == "failed" || p.Status == "unconfirmed" || !s.Enabled || active != in.DeviceID {
			if len(cancelIDs) < 2000 {
				cancelIDs = append(cancelIDs, p.ID)
			}
		} else if (p.Status == "pending" || p.Status == "scheduled") && p.Channel != "email" && p.ExpiresAt > stamp(a.now()) {
			if len(plans) < 256 {
				plans = append(plans, p)
			} else {
				truncated = true
			}
		}
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		dbFailure(w)
		return
	}
	agentStatus := "ready"
	if !s.Configured {
		agentStatus = "legacy_email"
	} else if !s.Enabled {
		agentStatus = "disabled"
	} else if active != in.DeviceID {
		agentStatus = "inactive_device"
	} else if len(availableReminderChannels(s, in.Capabilities)) == 0 {
		agentStatus = "no_authorized_channel"
	}
	agentMessage := "按已授权渠道安排；排程成功不代表用户已看到"
	if len(plans) == 0 && agentStatus == "ready" {
		agentMessage = "当前没有可执行的手机提醒；已遵守完成状态、静默时段、最短间隔和截止时间"
	}
	if agentStatus == "no_authorized_channel" {
		agentMessage = "当前没有已授权的可用渠道，请在手机设置中开启提醒权限"
	}
	if agentStatus == "legacy_email" {
		agentMessage = "尚未启用手机提醒，原邮件提醒继续执行"
	}
	writeJSON(w, 200, map[string]any{"plans": plans, "cancel_ids": cancelIDs, "settings": s, "active_device": active == in.DeviceID && !in.Detach, "agent_status": agentStatus, "agent_message": agentMessage, "sync_after_seconds": 900, "horizon_hours": 24, "truncated": truncated})
}
func availableReminderChannels(s reminderSettings, c reminderCapabilities) []string {
	out := []string{}
	if !s.Enabled {
		return out
	}
	for _, ch := range s.Channels {
		if (ch == "notification" && c.Notification) || (ch == "calendar" && c.Calendar) || (ch == "alarm" && c.Alarm && c.ExactAlarm) || ch == "email" {
			out = append(out, ch)
		}
	}
	return out
}
func containsReminderChannel(channels []string, ch string) bool {
	for _, c := range channels {
		if ch == c {
			return true
		}
	}
	return false
}
func quietReminder(at time.Time, s reminderSettings, zone string) bool {
	if s.QuietStart == "" {
		return false
	}
	if s.Timezone != "" {
		zone = s.Timezone
	}
	loc, err := time.LoadLocation(zone)
	if err != nil {
		return true
	}
	clock := at.In(loc).Format("15:04")
	if s.QuietStart < s.QuietEnd {
		return clock >= s.QuietStart && clock < s.QuietEnd
	}
	return clock >= s.QuietStart || clock < s.QuietEnd
}
func afterReminderQuiet(at time.Time, s reminderSettings, zone string) time.Time {
	if !quietReminder(at, s, zone) {
		return at
	}
	if s.Timezone != "" {
		zone = s.Timezone
	}
	loc, err := time.LoadLocation(zone)
	if err != nil {
		return at
	}
	end, err := time.Parse("15:04", s.QuietEnd)
	if err != nil {
		return at
	}
	local := at.In(loc)
	day := local
	if s.QuietStart > s.QuietEnd && local.Format("15:04") >= s.QuietStart {
		day = day.AddDate(0, 0, 1)
	}
	return time.Date(day.Year(), day.Month(), day.Day(), end.Hour(), end.Minute(), 0, 0, loc)
}
func reminderGeneration(t task, s reminderSettings, device string, channels []string, previous reminderPlan) string {
	raw, _ := json.Marshal([]any{t.ID, t.Title, t.Description, t.Rule, t.UpdatedAt, t.SnoozeUntil, t.SnoozeCycle, s, s.Revision, device, channels, previous.ID, previous.Status})
	return digest(string(raw))
}
func ruleReminderDecision(channels []string, previous reminderPlan) (string, string) {
	ch := channels[0]
	if previous.Channel != "" && (previous.Status == "failed" || previous.Status == "unconfirmed" || previous.Status == "delivered" || previous.Status == "accepted" || (previous.Status == "scheduled" && previous.Channel == "calendar")) {
		for i, c := range channels {
			if c == previous.Channel && len(channels) > 1 {
				ch = channels[(i+1)%len(channels)]
				break
			}
		}
	}
	reason := "按授权渠道先使用轻提醒"
	if ch != previous.Channel && previous.Channel != "" {
		reason = "上次渠道失败或提醒后仍未完成，切换另一种已授权渠道"
	}
	return ch, reason
}
func (a *App) latestReminderResult(taskID string) (reminderPlan, error) {
	p, err := scanReminderPlan(a.db.QueryRow(`SELECT `+reminderPlanColumns+` FROM reminder_plans WHERE task_id=? AND (status IN ('delivered','accepted','failed','unconfirmed') OR (status='scheduled' AND channel='calendar' AND scheduled_at<=?)) ORDER BY reported_at DESC,scheduled_at DESC,id DESC LIMIT 1`, taskID, stamp(a.now())))
	if err == sql.ErrNoRows {
		return reminderPlan{}, nil
	}
	return p, err
}
func (a *App) buildReminderPlans(ctx context.Context, user, device string, caps reminderCapabilities) error {
	s, active, err := a.reminderSettingsFor(user)
	if err != nil {
		return err
	}
	if !s.Configured || !s.Enabled || active != device {
		return nil
	}
	// A queued local plan is not proof that Android ever displayed it. Keep
	// missing receipts explicit and let the agent try another permitted route.
	if _, err = a.db.Exec(`UPDATE reminder_plans SET status='unconfirmed',reported_at=?,error='触发窗口已过，未收到设备回执，无法确认是否展示' WHERE user_id=? AND channel<>'email' AND expires_at<=? AND (status='pending' OR (status='scheduled' AND channel<>'calendar'))`, stamp(a.now()), user, stamp(a.now())); err != nil {
		return err
	}
	channels := availableReminderChannels(s, caps)
	if len(channels) == 0 {
		_, err = a.db.Exec(`UPDATE reminder_plans SET status='cancelled',error='没有已授权的可用渠道' WHERE user_id=? AND status IN ('pending','scheduled') AND NOT(channel='calendar' AND status='scheduled' AND scheduled_at<=?)`, user, stamp(a.now()))
		return err
	}
	tasks, err := a.allTasks(user, false)
	if err != nil {
		return err
	}
	modelBudget := 2
	for _, t := range tasks {
		if err = ctx.Err(); err != nil {
			return err
		}
		if t.Paused {
			continue
		}
		failureRows, failureErr := a.db.Query(`SELECT DISTINCT channel FROM reminder_plans WHERE task_id=? AND status IN ('failed','unconfirmed') AND reported_at>?`, t.ID, stamp(a.now().Add(-15*time.Minute)))
		if failureErr != nil {
			return failureErr
		}
		failedChannels := map[string]bool{}
		for failureRows.Next() {
			var failedChannel string
			if failureErr = failureRows.Scan(&failedChannel); failureErr != nil {
				failureRows.Close()
				return failureErr
			}
			failedChannels[failedChannel] = true
		}
		failureErr = failureRows.Err()
		failureRows.Close()
		if failureErr != nil {
			return failureErr
		}
		taskChannels := []string{}
		for _, ch := range channels {
			if !failedChannels[ch] {
				taskChannels = append(taskChannels, ch)
			}
		}
		if len(taskChannels) == 0 {
			if _, err = a.db.Exec(`UPDATE reminder_plans SET status='cancelled',error='渠道连续失败，暂停15分钟后重试；请检查设备授权' WHERE task_id=? AND status IN ('pending','scheduled') AND NOT(channel='calendar' AND status='scheduled' AND scheduled_at<=?)`, t.ID, stamp(a.now())); err != nil {
				return err
			}
			continue
		}
		previous, e := a.latestReminderResult(t.ID)
		if e != nil {
			return e
		}
		generation := reminderGeneration(t, s, device, taskChannels, previous)
		var channel, source, reason, storedGeneration string
		e = a.db.QueryRow(`SELECT generation,channel,source,reason FROM reminder_decisions WHERE task_id=?`, t.ID).Scan(&storedGeneration, &channel, &source, &reason)
		if e != nil && e != sql.ErrNoRows {
			return e
		}
		if storedGeneration != generation || (source == "rules" && strings.Contains(reason, "本轮决策额度") && modelBudget > 0) {
			channel, reason = ruleReminderDecision(taskChannels, previous)
			source = "rules"
			input := ai.ReminderContext{Title: t.Title, Description: t.Description, Schedule: schedule.Summary(t.Rule), AllowedChannels: taskChannels, PreviousChannel: previous.Channel, PreviousStatus: previous.Status, PreviousAt: previous.ScheduledAt, Now: stamp(a.now())}
			// Model I/O runs without the mutation mutex. Every constraint and task
			// revision is checked again before committing its decision.
			if modelBudget > 0 {
				if a.cfg.DeepSeekAPIKey != "" {
					modelBudget--
				}
				decision, decisionErr := ai.New(a.cfg).DecideReminder(ctx, input)
				if decisionErr == nil {
					channel = decision.Channel
					reason = decision.Reason
					source = "ai"
				} else {
					reason = "规则保底（AI 未配置或暂不可用）：" + reason
				}
			} else {
				reason = "规则保底（本轮决策额度已用完，下轮再尝试AI）：" + reason
			}
		}
		a.mu.Lock()
		latest, findErr := a.findTask(t.ID, user)
		freshSettings, freshDevice, settingsErr := a.reminderSettingsFor(user)
		freshPrevious, previousErr := a.latestReminderResult(t.ID)
		if findErr == sql.ErrNoRows || latest.Paused || freshDevice != device || !freshSettings.Enabled || settingsErr != nil || previousErr != nil || reminderGeneration(latest, freshSettings, device, taskChannels, freshPrevious) != generation {
			a.mu.Unlock()
			continue
		}
		if findErr != nil {
			a.mu.Unlock()
			return findErr
		}
		if _, err = a.db.Exec(`INSERT INTO reminder_decisions(task_id,generation,channel,source,reason) VALUES(?,?,?,?,?) ON CONFLICT(task_id) DO UPDATE SET generation=excluded.generation,channel=excluded.channel,source=excluded.source,reason=excluded.reason`, t.ID, generation, channel, source, reason); err != nil {
			a.mu.Unlock()
			return err
		}
		if _, err = a.db.Exec(`UPDATE reminder_plans SET status='cancelled',error='安排已更新' WHERE task_id=? AND status IN ('pending','scheduled') AND generation<>? AND NOT(channel='calendar' AND status='scheduled' AND scheduled_at<=?)`, t.ID, generation, stamp(a.now())); err != nil {
			a.mu.Unlock()
			return err
		}
		err = a.saveReminderSlots(latest, s, device, generation, channel, source, reason, previous)
		a.mu.Unlock()
		if err != nil {
			return err
		}
	}
	return a.cancelInvalidReminderPlans(user, device, s)
}
func (a *App) saveReminderSlots(t task, s reminderSettings, device, generation, channel, source, reason string, previous reminderPlan) error {
	now := a.now()
	start := now
	until := now.Add(24 * time.Hour)
	var futureCount int
	if err := a.db.QueryRow(`SELECT COUNT(*) FROM reminder_plans WHERE task_id=? AND generation=? AND status IN ('pending','scheduled') AND scheduled_at>?`, t.ID, generation, stamp(now)).Scan(&futureCount); err != nil {
		return err
	}
	if futureCount > 0 {
		return nil
	}
	// Re-evaluate all future slots; the unique generation/slot key makes sync
	// idempotent. A snooze suppresses every earlier occurrence, across channels.
	var snooze time.Time
	if t.SnoozeUntil != "" {
		snooze, _ = time.Parse(time.RFC3339Nano, t.SnoozeUntil)
		if snooze.After(start) {
			start = snooze.Add(-time.Nanosecond)
		}
	}
	slots := schedule.DueLimited(t.Rule, start, until, 1500)
	if snooze.After(now) && !snooze.After(until) {
		if cycle, ok := schedule.CycleAt(t.Rule, snooze); ok && cycle.Key == t.SnoozeCycle {
			slots = append([]schedule.Occurrence{{At: snooze, CycleKey: cycle.Key}}, slots...)
		}
	}
	// A failed channel can be replaced within the same original reminder slot.
	// The retry is never moved past its precise deadline or into quiet hours.
	if previous.Status == "failed" || previous.Status == "unconfirmed" {
		failedAt, _ := time.Parse(time.RFC3339Nano, previous.ScheduledAt)
		retryAt := failedAt
		if retryAt.Before(now) {
			retryAt = now.Add(time.Minute)
		}
		if c, ok := schedule.CycleAt(t.Rule, retryAt); ok && !retryAt.After(until) && (!snooze.After(now) || !retryAt.Before(snooze)) {
			slots = append(slots, schedule.Occurrence{At: retryAt, CycleKey: c.Key})
		}
	}
	// A task that requires completion remains the agent's responsibility after
	// its final explicit slot. Continue within that same completion cycle only;
	// notify_once never acquires extra reminders or a new completion meaning.
	if t.Rule.Mode != "notify_once" && previous.ID != "" && (previous.Status == "delivered" || previous.Status == "accepted" || previous.Status == "scheduled") {
		hasSameCycleSlot := false
		for _, slot := range slots {
			if slot.CycleKey == previous.CycleKey {
				hasSameCycleSlot = true
				break
			}
		}
		if !hasSameCycleSlot {
			last, _ := time.Parse(time.RFC3339Nano, previous.ScheduledAt)
			next := last.Add(time.Duration(s.MinInterval) * time.Minute)
			if !next.After(now) {
				next = now.Add(time.Minute)
			}
			for i := 0; i < 1500 && len(slots) < 1500 && !next.After(until); i++ {
				cycle, ok := schedule.CycleAt(t.Rule, next)
				if !ok || cycle.Key != previous.CycleKey {
					break
				}
				slots = append(slots, schedule.Occurrence{At: next, CycleKey: cycle.Key})
				next = next.Add(time.Duration(s.MinInterval) * time.Minute)
			}
		}
	}
	sort.Slice(slots, func(i, j int) bool { return slots[i].At.Before(slots[j].At) })
	lastAt := time.Time{}
	if previous.Status != "failed" && previous.Status != "unconfirmed" {
		lastAt, _ = time.Parse(time.RFC3339Nano, previous.ScheduledAt)
	}
	used := map[string]int{}
	created := 0
	for _, slot := range slots {
		if created >= 32 {
			break
		}
		if quietReminder(slot.At, s, t.Rule.Timezone) {
			deferred := afterReminderQuiet(slot.At, s, t.Rule.Timezone)
			cycle, ok := schedule.CycleAt(t.Rule, deferred)
			if !ok || cycle.Key != slot.CycleKey || deferred.After(until) {
				continue
			}
			slot.At = deferred
		}
		if !lastAt.IsZero() && slot.At.Sub(lastAt) < time.Duration(s.MinInterval)*time.Minute {
			continue
		}
		complete, sent, err := a.cycleCounts(t.ID, slot.CycleKey)
		if err != nil {
			return err
		}
		if stopForRule(t.Rule, complete, sent+used[slot.CycleKey]) {
			continue
		}
		cycle, ok := schedule.CycleAt(t.Rule, slot.At)
		if !ok {
			continue
		}
		expires := cycle.End
		// A local occurrence expires before the next allowed reminder, preventing
		// a resumed phone from replaying a stale queue of missed alarms.
		if expiry := slot.At.Add(time.Duration(s.MinInterval) * time.Minute); expiry.Before(expires) {
			expires = expiry
		}
		if t.Rule.EndAt != "" {
			end, _ := time.Parse(time.RFC3339Nano, t.Rule.EndAt)
			if !expires.Before(end) {
				expires = end.Add(time.Second)
			}
		}
		explanation := reason + fmt.Sprintf("；遵守%s–%s静默与至少%d分钟间隔，精确截止后停止", s.QuietStart, s.QuietEnd, s.MinInterval)
		if s.QuietStart == "" {
			explanation = reason + fmt.Sprintf("；至少间隔%d分钟，完成、暂停或截止后停止", s.MinInterval)
		}
		ruleSnapshot, _ := json.Marshal(t.Rule)
		_, err = a.db.Exec(`INSERT INTO reminder_plans(id,user_id,task_id,device_id,title,body,cycle_key,channel,scheduled_at,expires_at,source,reason,generation,rule_snapshot) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(task_id,scheduled_at,channel,generation) DO UPDATE SET status='pending',error='' WHERE reminder_plans.status='cancelled'`, randomID(), t.UserID, t.ID, device, t.Title, t.Description, slot.CycleKey, channel, stamp(slot.At), stamp(expires), source, explanation, generation, string(ruleSnapshot))
		if err != nil {
			return err
		}
		used[slot.CycleKey]++
		created++
		lastAt = slot.At
	}
	return nil
}
func (a *App) cancelInvalidReminderPlans(user, device string, s reminderSettings) error {
	if !s.Enabled || !s.Configured {
		_, err := a.db.Exec(`UPDATE reminder_plans SET status='cancelled',error='提醒已停用' WHERE user_id=? AND status IN ('pending','scheduled') AND NOT(channel='calendar' AND status='scheduled' AND scheduled_at<=?)`, user, stamp(a.now()))
		return err
	}
	_, err := a.db.Exec(`UPDATE reminder_plans SET status='cancelled',error='任务或设备安排已改变' WHERE user_id=? AND status IN ('pending','scheduled') AND NOT(channel='calendar' AND status='scheduled' AND scheduled_at<=?) AND ((device_id<>? AND channel<>'email') OR expires_at<=? OR task_id IN (SELECT id FROM tasks WHERE paused=1 OR archived=1) OR EXISTS(SELECT 1 FROM completions c WHERE c.task_id=reminder_plans.task_id AND c.cycle_key=reminder_plans.cycle_key))`, user, stamp(a.now()), device, stamp(a.now()))
	return err
}

// Explicit settings opt in to the new executor. Existing accounts retain
// their original mail reminders until that explicit action occurs.
func (a *App) usesReminderAgent(user string) (bool, error) {
	var n int
	err := a.db.QueryRow(`SELECT COUNT(*) FROM reminder_settings WHERE user_id=?`, user).Scan(&n)
	return n > 0, err
}

func (a *App) tickReminderAgent(ctx context.Context) error {
	rows, err := a.db.QueryContext(ctx, `SELECT s.user_id,s.active_device,COALESCE(d.capabilities,'{}') FROM reminder_settings s LEFT JOIN reminder_devices d ON s.user_id=d.user_id AND s.active_device=d.device_id`)
	if err != nil {
		return err
	}
	type device struct{ user, id, caps string }
	devices := []device{}
	for rows.Next() {
		var d device
		if err = rows.Scan(&d.user, &d.id, &d.caps); err != nil {
			rows.Close()
			return err
		}
		devices = append(devices, d)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return err
	}
	for _, d := range devices {
		var caps reminderCapabilities
		if err = json.Unmarshal([]byte(d.caps), &caps); err != nil {
			return err
		}
		if err = a.buildReminderPlans(ctx, d.user, d.id, caps); err != nil {
			return err
		}
	}
	rows, err = a.db.QueryContext(ctx, `SELECT id,user_id FROM reminder_plans WHERE channel='email' AND status='pending' AND scheduled_at<=? AND expires_at>? ORDER BY scheduled_at`, stamp(a.now()), stamp(a.now()))
	if err != nil {
		return err
	}
	type queued struct{ id, user string }
	queue := []queued{}
	for rows.Next() {
		var q queued
		if err = rows.Scan(&q.id, &q.user); err != nil {
			rows.Close()
			return err
		}
		queue = append(queue, q)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return err
	}
	for _, q := range queue {
		a.mu.Lock()
		s, device, e := a.reminderSettingsFor(q.user)
		if e == nil {
			e = a.cancelInvalidReminderPlans(q.user, device, s)
		}
		p, planErr := scanReminderPlan(a.db.QueryRow(`SELECT `+reminderPlanColumns+` FROM reminder_plans WHERE id=?`, q.id))
		if e != nil || planErr != nil {
			a.mu.Unlock()
			if e != nil {
				return e
			}
			return planErr
		}
		if p.Status != "pending" || !s.Enabled || !containsReminderChannel(s.Channels, "email") {
			a.mu.Unlock()
			continue
		}
		complete, sent, e := a.cycleCounts(p.TaskID, p.CycleKey)
		if e != nil {
			a.mu.Unlock()
			return e
		}
		t, e := a.findTask(p.TaskID, q.user)
		invalidWindow := false
		if e == nil {
			raw, _ := json.Marshal(t.Rule)
			var plannedRule string
			if ruleErr := a.db.QueryRow(`SELECT rule_snapshot FROM reminder_plans WHERE id=?`, p.ID).Scan(&plannedRule); ruleErr != nil {
				a.mu.Unlock()
				return ruleErr
			}
			cycle, active := schedule.CycleAt(t.Rule, a.now())
			snooze, _ := time.Parse(time.RFC3339Nano, t.SnoozeUntil)
			invalidWindow = plannedRule != string(raw) || !active || cycle.Key != p.CycleKey || snooze.After(a.now()) || t.Paused || quietReminder(a.now(), s, t.Rule.Timezone)
		}
		if e != nil || invalidWindow || stopForRule(t.Rule, complete, sent) {
			_, err = a.db.Exec(`UPDATE reminder_plans SET status='cancelled',error='任务已完成或已停止' WHERE id=?`, p.ID)
			a.mu.Unlock()
			if err != nil {
				return err
			}
			continue
		}
		var email string
		if e = a.db.QueryRow(`SELECT email FROM users WHERE id=?`, q.user).Scan(&email); e != nil {
			a.mu.Unlock()
			return e
		}
		claim, e := a.db.Exec(`UPDATE reminder_plans SET status='sending' WHERE id=? AND status='pending'`, p.ID)
		if e != nil {
			a.mu.Unlock()
			return e
		}
		claimed, _ := claim.RowsAffected()
		a.mu.Unlock()
		if claimed == 0 {
			continue
		}
		sendCtx, cancel := context.WithTimeout(ctx, 35*time.Second)
		sendErr := a.mailer.Send(sendCtx, email, "念念提醒 · "+p.Title, "该做这件事啦："+p.Title+"\n\n"+p.Body+"\n\n完成后请打开念念点击完成。")
		cancel()
		status, message, sentAt := "accepted", "", stamp(a.now())
		if sendErr != nil {
			status = "failed"
			message = "邮件发送失败，Agent 将尝试其他已授权渠道"
			sentAt = ""
		}
		if _, err = a.db.Exec(`UPDATE reminder_plans SET status=?,error=?,sent_at=?,reported_at=? WHERE id=? AND status='sending'`, status, message, sentAt, stamp(a.now()), p.ID); err != nil {
			return err
		}
	}
	return nil
}
