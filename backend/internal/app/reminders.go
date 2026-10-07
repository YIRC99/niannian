package app

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"net/http"
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
CREATE TABLE IF NOT EXISTS reminder_strategies(task_id TEXT PRIMARY KEY REFERENCES tasks(id),generation TEXT NOT NULL,strategy TEXT NOT NULL);
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
	ReportedAt  string `json:"-"`
}

const reminderPlanColumns = `id,task_id,title,body,cycle_key,channel,scheduled_at,expires_at,status,source,reason,error,generation,sent_at,reported_at`

func scanReminderPlan(s scanner) (p reminderPlan, err error) {
	err = s.Scan(&p.ID, &p.TaskID, &p.Title, &p.Body, &p.CycleKey, &p.Channel, &p.ScheduledAt, &p.ExpiresAt, &p.Status, &p.Source, &p.Reason, &p.Error, &p.Generation, &p.SentAt, &p.ReportedAt)
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
	if s.MinInterval < 1 || s.MinInterval > 1440 {
		writeError(w, 400, "提醒间隔需为1–1440分钟")
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
			_, err = a.db.Exec(`UPDATE reminder_plans SET status=?,error=?,sent_at=?,reported_at=? WHERE id=? AND user_id=? AND device_id=? AND channel<>'email' AND (status IN ('pending','scheduled') OR (status='unconfirmed' AND ?='delivered')) AND (?<>'delivered' OR scheduled_at<=?) AND NOT(channel='calendar' AND status='scheduled' AND scheduled_at<=? AND ? IN ('failed','cancelled'))`, status, result.Error, sentAt, stamp(a.now()), result.PlanID, user, in.DeviceID, status, status, stamp(a.now().Add(time.Minute)), stamp(a.now()), status)
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
	rows, err := a.db.Query(`SELECT `+reminderPlanColumns+` FROM reminder_plans WHERE user_id=? AND device_id=? AND (scheduled_at>=? OR status='cancelled') ORDER BY scheduled_at,id`, user, in.DeviceID, stamp(a.now().Add(-7*24*time.Hour)))
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
		} else if (p.Status == "pending" || p.Status == "scheduled") && p.Channel != "email" && p.ExpiresAt > stamp(a.now()) && !(p.Channel == "calendar" && p.Status == "scheduled" && p.ScheduledAt <= stamp(a.now())) {
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
	agentMessage := "每件事只安排下一条；收到执行反馈或超时且仍未完成后，按已保存流程继续"
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
func reminderGeneration(t task, s reminderSettings, device string, channels []string) string {
	raw, _ := json.Marshal([]any{"sequential-v1", t.ID, t.Title, t.Description, t.Rule, t.UpdatedAt, t.SnoozeUntil, t.SnoozeCycle, s, s.Revision, device, channels})
	return digest(string(raw))
}

type reminderStrategy struct {
	TaskID       string            `json:"task_id"`
	State        string            `json:"state"`
	Source       string            `json:"source"`
	Reason       string            `json:"reason"`
	Steps        []ai.ReminderStep `json:"steps"`
	RepeatPolicy string            `json:"repeat_policy"`
	NextReminder string            `json:"next_reminder,omitempty"`
	Generation   string            `json:"-"`
}

// A strategy is a small policy, not a queue of future notification events.
func ruleReminderStrategy(t task, s reminderSettings, channels []string) reminderStrategy {
	steps := []ai.ReminderStep{{Channel: channels[0], WaitMinutes: 0, Reason: "按事项首次时间轻提醒"}}
	base := max(1, s.MinInterval)
	if t.Rule.Kind == "minute_interval" {
		base = max(base, t.Rule.RepeatMinutes)
	}
	if t.Rule.Mode != "notify_once" {
		for i := 1; i < 3; i++ {
			steps = append(steps, ai.ReminderStep{Channel: channels[i%len(channels)], WaitMinutes: min(1440, base*(1<<(i-1))), Reason: "留出处理时间，仍未完成再跟进"})
		}
	}
	return reminderStrategy{TaskID: t.ID, State: "ready", Source: "rules", Reason: "规则保底：按已授权渠道逐步跟进，持续未完成时延长等待", Steps: steps, RepeatPolicy: "last_step_backoff"}
}
func (a *App) storedReminderStrategy(taskID string) (reminderStrategy, error) {
	var p reminderStrategy
	var raw string
	err := a.db.QueryRow(`SELECT generation,strategy FROM reminder_strategies WHERE task_id=?`, taskID).Scan(&p.Generation, &raw)
	if err != nil {
		return p, err
	}
	err = json.Unmarshal([]byte(raw), &p)
	return p, err
}
func (a *App) reminderDeviceCapabilities(user, device string) (reminderCapabilities, error) {
	var caps reminderCapabilities
	if device == "" {
		return caps, nil
	}
	var raw string
	err := a.db.QueryRow(`SELECT capabilities FROM reminder_devices WHERE user_id=? AND device_id=?`, user, device).Scan(&raw)
	if err == sql.ErrNoRows {
		return caps, nil
	}
	if err != nil {
		return caps, err
	}
	err = json.Unmarshal([]byte(raw), &caps)
	return caps, err
}
func (a *App) taskReminderStrategy(t task) *reminderStrategy {
	s, device, err := a.reminderSettingsFor(t.UserID)
	if err != nil {
		return nil
	}
	waiting := &reminderStrategy{TaskID: t.ID, State: "awaiting_authorization", Source: "rules", Reason: "开启提醒并同步已授权的手机渠道后生成跟进流程", Steps: []ai.ReminderStep{}, RepeatPolicy: "last_step_backoff"}
	if !s.Configured || !s.Enabled {
		return waiting
	}
	caps, err := a.reminderDeviceCapabilities(t.UserID, device)
	if err != nil {
		return waiting
	}
	channels := availableReminderChannels(s, caps)
	if len(channels) == 0 {
		return waiting
	}
	p, err := a.storedReminderStrategy(t.ID)
	if err != nil || p.Generation != reminderGeneration(t, s, device, channels) {
		waiting.State = "awaiting_sync"
		waiting.Reason = "跟进流程正在更新，同步后即可查看"
		return waiting
	}
	p.NextReminder = t.NextReminder
	return &p
}
func (a *App) prepareTaskReminderStrategy(ctx context.Context, user, taskID string) error {
	s, device, err := a.reminderSettingsFor(user)
	if err != nil || !s.Configured || !s.Enabled {
		return err
	}
	caps, err := a.reminderDeviceCapabilities(user, device)
	if err != nil {
		return err
	}
	t, err := a.findTask(taskID, user)
	if err != nil {
		return err
	}
	budget := 1
	return a.buildTaskReminderPlan(ctx, t, s, device, availableReminderChannels(s, caps), &budget)
}
func (a *App) latestReminderResult(taskID string) (reminderPlan, error) {
	p, err := scanReminderPlan(a.db.QueryRow(`SELECT `+reminderPlanColumns+` FROM reminder_plans WHERE task_id=? AND (status IN ('delivered','accepted','failed','unconfirmed') OR (status='scheduled' AND channel='calendar' AND expires_at<=?)) ORDER BY scheduled_at DESC,reported_at DESC,id DESC LIMIT 1`, taskID, stamp(a.now())))
	if err == sql.ErrNoRows {
		return reminderPlan{}, nil
	}
	return p, err
}

// Expire missing receipts before advancing. Calendar retains its true scheduled
// status in history; after its trigger window it no longer blocks the next step.
func (a *App) expireReminderPlans(user string) error {
	_, err := a.db.Exec(`UPDATE reminder_plans SET status='unconfirmed',reported_at=?,error='触发窗口已过，未收到执行回执，无法确认是否展示' WHERE user_id=? AND expires_at<=? AND (status='pending' OR (status='scheduled' AND channel<>'calendar'))`, stamp(a.now()), user, stamp(a.now()))
	return err
}
func (a *App) buildReminderPlans(ctx context.Context, user, device string, caps reminderCapabilities) error {
	a.mu.Lock()
	s, active, err := a.reminderSettingsFor(user)
	if err == nil {
		err = a.expireReminderPlans(user)
	}
	if err == nil {
		err = a.cancelInvalidReminderPlans(user, active, s)
	}
	a.mu.Unlock()
	if err != nil {
		return err
	}
	if !s.Configured || !s.Enabled || active != device {
		return nil
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
	budget := 2
	for _, t := range tasks {
		if err = ctx.Err(); err != nil {
			return err
		}
		if err = a.buildTaskReminderPlan(ctx, t, s, device, channels, &budget); err != nil {
			return err
		}
	}
	return nil
}
func (a *App) buildTaskReminderPlan(ctx context.Context, t task, s reminderSettings, device string, channels []string, budget *int) error {
	if t.Paused || len(channels) == 0 {
		return nil
	}
	generation := reminderGeneration(t, s, device, channels)
	strategy, err := a.storedReminderStrategy(t.ID)
	if err != nil && err != sql.ErrNoRows {
		return err
	}
	if strategy.Generation != generation {
		strategy = ruleReminderStrategy(t, s, channels)
		minimumWait := max(1, s.MinInterval)
		if t.Rule.Kind == "minute_interval" {
			minimumWait = max(minimumWait, t.Rule.RepeatMinutes)
		}
		if *budget > 0 {
			if a.cfg.DeepSeekAPIKey != "" {
				*budget--
			}
			decision, decisionErr := ai.New(a.cfg).DecideReminder(ctx, ai.ReminderContext{Title: t.Title, Description: t.Description, Schedule: schedule.Summary(t.Rule), AllowedChannels: channels, Now: stamp(a.now()), MinInterval: minimumWait, Deadline: t.Rule.EndAt})
			if decisionErr == nil {
				strategy.Steps = decision.Steps
				strategy.Reason = decision.Reason
				strategy.Source = "ai"
			}
		}
		strategy.Generation = generation
		if t.Rule.Mode == "notify_once" {
			strategy.Steps = strategy.Steps[:1]
			strategy.RepeatPolicy = "none"
			if strategy.Source == "rules" {
				strategy.Reason = "规则保底：按已授权渠道提醒一次"
			}
		}
	}
	// Recheck owner, device, permissions and rule after model I/O without holding
	// the mutex. Competing sync/tick calls reuse the first committed strategy.
	a.mu.Lock()
	defer a.mu.Unlock()
	latest, err := a.findTask(t.ID, t.UserID)
	if err == sql.ErrNoRows {
		return nil
	}
	if err != nil {
		return err
	}
	fresh, active, err := a.reminderSettingsFor(t.UserID)
	if err != nil {
		return err
	}
	caps, err := a.reminderDeviceCapabilities(t.UserID, active)
	if err != nil {
		return err
	}
	freshChannels := availableReminderChannels(fresh, caps)
	if latest.Paused || active != device || !fresh.Enabled || reminderGeneration(latest, fresh, device, freshChannels) != generation {
		return nil
	}
	if existing, e := a.storedReminderStrategy(t.ID); e == nil && existing.Generation == generation {
		strategy = existing
	}
	raw, _ := json.Marshal(strategy)
	if _, err = a.db.Exec(`INSERT INTO reminder_strategies(task_id,generation,strategy) VALUES(?,?,?) ON CONFLICT(task_id) DO UPDATE SET generation=excluded.generation,strategy=excluded.strategy`, t.ID, generation, string(raw)); err != nil {
		return err
	}
	if err = a.expireReminderPlans(t.UserID); err != nil {
		return err
	}
	if err = a.cancelInvalidReminderPlans(t.UserID, device, fresh); err != nil {
		return err
	}
	if err = a.cancelStoppedReminderPlans(latest); err != nil {
		return err
	}
	if _, err = a.db.Exec(`UPDATE reminder_plans SET status='cancelled',error='跟进流程已更新，仅保留下一条安排' WHERE task_id=? AND generation<>? AND status IN ('pending','scheduled') AND NOT(channel='calendar' AND status='scheduled' AND scheduled_at<=?)`, t.ID, generation, stamp(a.now())); err != nil {
		return err
	}
	// Cancel legacy batches, retaining their rows and returning their IDs for
	// device cleanup. A sending email takes precedence over any queued work.
	if _, err = a.db.Exec(`UPDATE reminder_plans SET status='cancelled',error='已改为逐步跟进，撤销多余预排程' WHERE task_id=? AND status IN ('pending','scheduled') AND NOT(channel='calendar' AND status='scheduled' AND scheduled_at<=?) AND id<>(SELECT id FROM reminder_plans WHERE task_id=? AND (status IN ('pending','sending') OR (status='scheduled' AND expires_at>?)) ORDER BY CASE WHEN status='sending' THEN 0 ELSE 1 END,scheduled_at,id LIMIT 1)`, t.ID, stamp(a.now()), t.ID, stamp(a.now())); err != nil {
		return err
	}
	var outstanding int
	if err = a.db.QueryRow(`SELECT COUNT(*) FROM reminder_plans WHERE task_id=? AND (status IN ('pending','sending') OR (status='scheduled' AND expires_at>?))`, t.ID, stamp(a.now())).Scan(&outstanding); err != nil {
		return err
	}
	if outstanding > 0 {
		return nil
	}
	previous, err := a.latestReminderResult(t.ID)
	if err != nil {
		return err
	}
	return a.saveNextReminder(latest, fresh, device, strategy, previous)
}

// A late delivery receipt can use the final notify_once/max-reminders budget
// after a timeout retry was queued. Recheck the budget before reusing that row.
func (a *App) cancelStoppedReminderPlans(t task) error {
	rows, err := a.db.Query(`SELECT id,cycle_key FROM reminder_plans WHERE task_id=? AND status IN ('pending','scheduled') AND NOT(channel='calendar' AND status='scheduled' AND scheduled_at<=?)`, t.ID, stamp(a.now()))
	if err != nil {
		return err
	}
	type queuedCycle struct{ id, cycle string }
	queue := []queuedCycle{}
	for rows.Next() {
		var p queuedCycle
		if err = rows.Scan(&p.id, &p.cycle); err != nil {
			rows.Close()
			return err
		}
		queue = append(queue, p)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return err
	}
	for _, p := range queue {
		complete, sent, e := a.cycleCounts(t.ID, p.cycle)
		if e != nil {
			return e
		}
		if stopForRule(t.Rule, complete, sent) {
			if _, err = a.db.Exec(`UPDATE reminder_plans SET status='cancelled',error='任务已完成或已达到本周期提醒次数' WHERE id=? AND status IN ('pending','scheduled')`, p.id); err != nil {
				return err
			}
		}
	}
	return nil
}

// Exactly one candidate is derived, then bounded by the task's completion cycle.
// Waiting starts at actual delivery/acceptance/timeout, never at a sync request.
func (a *App) saveNextReminder(t task, s reminderSettings, device string, strategy reminderStrategy, previous reminderPlan) error {
	now := a.now()
	next, ok := schedule.Next(t.Rule, now)
	cycle := schedule.Cycle{}
	stepIndex := 0
	if ok {
		cycle, ok = schedule.CycleAt(t.Rule, next)
	}
	if previous.ID != "" {
		var priorRule string
		if err := a.db.QueryRow(`SELECT rule_snapshot FROM reminder_plans WHERE id=?`, previous.ID).Scan(&priorRule); err != nil {
			return err
		}
		currentRule, _ := json.Marshal(t.Rule)
		if priorRule != string(currentRule) {
			// An edited first time/deadline starts a new flow. Its predecessor
			// remains history and must never pull the new first reminder forward.
			previous = reminderPlan{}
		}
	}
	if previous.ID != "" {
		anchor, _ := time.Parse(time.RFC3339Nano, previous.ScheduledAt)
		if resultAt, e := time.Parse(time.RFC3339Nano, previous.ReportedAt); e == nil && resultAt.After(anchor) {
			anchor = resultAt
		}
		if previous.Channel == "calendar" && previous.Status == "scheduled" {
			if expiry, e := time.Parse(time.RFC3339Nano, previous.ExpiresAt); e == nil && expiry.After(anchor) {
				anchor = expiry
			}
		}
		priorCycle, active := schedule.CycleAt(t.Rule, anchor)
		if active && priorCycle.Key == previous.CycleKey {
			complete, sent, err := a.cycleCounts(t.ID, priorCycle.Key)
			if err != nil {
				return err
			}
			if !stopForRule(t.Rule, complete, sent) {
				if err = a.db.QueryRow(`SELECT COUNT(*) FROM reminder_plans WHERE task_id=? AND generation=? AND cycle_key=? AND (status IN ('delivered','accepted','failed','unconfirmed') OR (status='scheduled' AND channel='calendar' AND expires_at<=?))`, t.ID, strategy.Generation, priorCycle.Key, stamp(now)).Scan(&stepIndex); err != nil {
					return err
				}
				if stepIndex == 0 {
					stepIndex = 1
				}
				step := strategy.Steps[min(stepIndex, len(strategy.Steps)-1)]
				wait := max(s.MinInterval, step.WaitMinutes)
				if t.Rule.Kind == "minute_interval" {
					wait = max(wait, t.Rule.RepeatMinutes)
				}
				for i := len(strategy.Steps); i <= stepIndex && wait < 1440; i++ {
					wait = min(1440, wait*2)
				}
				follow := anchor.Add(time.Duration(max(1, wait)) * time.Minute)
				if !follow.After(now) {
					follow = now.Add(time.Minute)
				}
				followCycle, active := schedule.CycleAt(t.Rule, follow)
				if active && followCycle.Key == priorCycle.Key {
					next, cycle, ok = follow, followCycle, true
				} else {
					// The prior cycle ended. Keep the ordinary next-cycle slot and
					// restart its first step rather than carrying old follow-ups.
					// A precise deadline cannot fall back to an earlier same-cycle
					// slot, which would bypass the strategy wait/minimum interval.
					if !ok || cycle.Key == priorCycle.Key {
						return nil
					}
					stepIndex = 0
				}
			}
		}
	}
	snooze, _ := time.Parse(time.RFC3339Nano, t.SnoozeUntil)
	if snooze.After(now) && (!ok || next.Before(snooze)) {
		next = snooze
		cycle, ok = schedule.CycleAt(t.Rule, next)
		ok = ok && cycle.Key == t.SnoozeCycle
	}
	// Skip stopped cycles in one jump; no dense enumeration of minute slots.
	for i := 0; ok && i < 600; i++ {
		complete, sent, err := a.cycleCounts(t.ID, cycle.Key)
		if err != nil {
			return err
		}
		if !stopForRule(t.Rule, complete, sent) {
			break
		}
		next, ok = schedule.Next(t.Rule, cycle.End.Add(-time.Nanosecond))
		if ok {
			cycle, ok = schedule.CycleAt(t.Rule, next)
		}
		stepIndex = 0
	}
	if !ok || next.After(now.Add(24*time.Hour)) {
		return nil
	}
	// Editing or changing permissions resets the strategy, not the user's
	// global minimum gap after an already executed reminder for this task.
	var lastExecuted string
	if err := a.db.QueryRow(`SELECT COALESCE(MAX(CASE WHEN sent_at<>'' THEN sent_at ELSE scheduled_at END),'') FROM reminder_plans WHERE task_id=? AND (status IN ('delivered','accepted') OR (status='scheduled' AND channel='calendar' AND scheduled_at<=?))`, t.ID, stamp(now)).Scan(&lastExecuted); err != nil {
		return err
	}
	if last, e := time.Parse(time.RFC3339Nano, lastExecuted); e == nil {
		minimumAt := last.Add(time.Duration(s.MinInterval) * time.Minute)
		if next.Before(minimumAt) {
			adjusted, active := schedule.CycleAt(t.Rule, minimumAt)
			if !active || adjusted.Key != cycle.Key {
				return nil
			}
			next = minimumAt
		}
	}
	if quietReminder(next, s, t.Rule.Timezone) {
		deferred := afterReminderQuiet(next, s, t.Rule.Timezone)
		adjusted, active := schedule.CycleAt(t.Rule, deferred)
		if !active || adjusted.Key != cycle.Key {
			return nil
		}
		next = deferred
	}
	complete, sent, err := a.cycleCounts(t.ID, cycle.Key)
	if err != nil || stopForRule(t.Rule, complete, sent) {
		return err
	}
	step := strategy.Steps[min(stepIndex, len(strategy.Steps)-1)]
	expires := next.Add(time.Duration(max(1, s.MinInterval)) * time.Minute)
	if cycle.End.Before(expires) {
		expires = cycle.End
	}
	// Android stores epoch milliseconds. Preserve the inclusive deadline point
	// instead of truncating its one-nanosecond window to an already-expired plan.
	if expires.After(next) && expires.UnixMilli() <= next.UnixMilli() {
		expires = time.UnixMilli(next.UnixMilli() + 1)
	}
	explanation := step.Reason + fmt.Sprintf("；流程第%d步，仅安排下一条，至少间隔%d分钟，完成、暂停或截止后停止", min(stepIndex+1, len(strategy.Steps)), s.MinInterval)
	ruleSnapshot, _ := json.Marshal(t.Rule)
	_, err = a.db.Exec(`INSERT INTO reminder_plans(id,user_id,task_id,device_id,title,body,cycle_key,channel,scheduled_at,expires_at,source,reason,generation,rule_snapshot) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(task_id,scheduled_at,channel,generation) DO NOTHING`, randomID(), t.UserID, t.ID, device, t.Title, t.Description, cycle.Key, step.Channel, stamp(next), stamp(expires), strategy.Source, explanation, strategy.Generation, string(ruleSnapshot))
	return err
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
