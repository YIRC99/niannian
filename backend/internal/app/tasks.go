package app

import (
	"database/sql"
	"encoding/json"
	"errors"
	"net/http"
	"niannian/internal/schedule"
	"strings"
	"time"
	"unicode/utf8"
)

const taskColumns = `id,user_id,title,description,rule,paused,archived,created_at,updated_at,snooze_until,snooze_cycle`

type scanner interface{ Scan(...any) error }

func scanTask(row scanner) (task, error) {
	var t task
	var rule string
	err := row.Scan(&t.ID, &t.UserID, &t.Title, &t.Description, &rule, &t.Paused, &t.Archived, &t.CreatedAt, &t.UpdatedAt, &t.SnoozeUntil, &t.SnoozeCycle)
	if err != nil {
		return t, err
	}
	err = json.Unmarshal([]byte(rule), &t.Rule)
	return t, err
}
func (a *App) findTask(id, userID string) (task, error) {
	return scanTask(a.db.QueryRow(`SELECT `+taskColumns+` FROM tasks WHERE id=? AND user_id=? AND archived=0`, id, userID))
}
func (a *App) allTasks(userID string, includeArchived bool) ([]task, error) {
	query := `SELECT ` + taskColumns + ` FROM tasks WHERE user_id=?`
	if !includeArchived {
		query += ` AND archived=0`
	}
	query += ` ORDER BY created_at DESC,id`
	rows, err := a.db.Query(query, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []task{}
	for rows.Next() {
		t, e := scanTask(rows)
		if e != nil {
			return nil, e
		}
		out = append(out, t)
	}
	return out, rows.Err()
}
func (a *App) cycleCounts(id, key string) (complete bool, sent int, err error) {
	var n int
	err = a.db.QueryRow(`SELECT (SELECT COUNT(*) FROM completions WHERE task_id=? AND cycle_key=?),(SELECT COUNT(*) FROM notifications WHERE task_id=? AND cycle_key=? AND status='sent')+(SELECT COUNT(*) FROM reminder_plans WHERE task_id=? AND cycle_key=? AND (status IN ('delivered','accepted') OR (status='scheduled' AND channel='calendar' AND scheduled_at<=?)))`, id, key, id, key, id, key, stamp(a.now())).Scan(&n, &sent)
	return n > 0, sent, err
}
func stopForRule(rule schedule.Rule, complete bool, sent int) bool {
	return complete || (rule.Mode == "notify_once" && sent >= 1) || (rule.MaxReminders > 0 && sent >= rule.MaxReminders)
}
func (a *App) view(t task) task {
	_ = a.db.QueryRow(`SELECT flower_type FROM garden_plants WHERE task_id=? AND user_id=?`, t.ID, t.UserID).Scan(&t.FlowerType)
	t.Summary = schedule.Summary(t.Rule)
	now := a.now()
	cycle, active := schedule.CycleAt(t.Rule, now)
	if active {
		t.CycleKey = cycle.Key
	}
	if t.Archived {
		t.Status = "expired"
		return t
	}
	if t.Paused {
		t.Status = "paused"
		return t
	}
	t.Status = "upcoming"
	if active {
		t.Status = "pending"
		complete, sent, e := a.cycleCounts(t.ID, cycle.Key)
		if e == nil && (complete || (t.Rule.Mode == "notify_once" && sent > 0)) {
			t.Status = "done"
		}
	}
	// Skip completed cycles while finding the next actual eligible reminder.
	cursor := now
	for i := 0; i < 600; i++ {
		next, ok := schedule.Next(t.Rule, cursor)
		if !ok {
			break
		}
		c, ok := schedule.CycleAt(t.Rule, next)
		if !ok {
			break
		}
		complete, sent, err := a.cycleCounts(t.ID, c.Key)
		if err != nil {
			break
		}
		if !stopForRule(t.Rule, complete, sent) {
			t.NextReminder = stamp(next)
			break
		}
		cursor = c.End.Add(-time.Nanosecond)
		if !cursor.After(next) {
			cursor = next
		}
	}
	if t.SnoozeUntil != "" {
		until, e := time.Parse(time.RFC3339Nano, t.SnoozeUntil)
		if e == nil && until.After(now) {
			c, ok := schedule.CycleAt(t.Rule, until)
			complete, sent, err := a.cycleCounts(t.ID, c.Key)
			if ok && c.Key == t.SnoozeCycle && err == nil && !stopForRule(t.Rule, complete, sent) {
				t.NextReminder = stamp(until)
			}
		}
	}
	if !active && t.NextReminder == "" {
		t.Status = "expired"
	}
	if settings, device, err := a.reminderSettingsFor(t.UserID); err == nil && settings.Configured {
		// Show an execution promise only when it exists in the actual agent
		// ledger. A rule slot suppressed by quiet hours is not a scheduled alert.
		t.NextReminder = ""
		if settings.Enabled {
			_ = a.db.QueryRow(`SELECT scheduled_at FROM reminder_plans WHERE task_id=? AND device_id=? AND status IN ('pending','scheduled') AND scheduled_at>? AND expires_at>? AND NOT EXISTS(SELECT 1 FROM completions c WHERE c.task_id=reminder_plans.task_id AND c.cycle_key=reminder_plans.cycle_key) ORDER BY scheduled_at LIMIT 1`, t.ID, device, stamp(now), stamp(now)).Scan(&t.NextReminder)
		}
	}
	return t
}

type taskInput struct {
	Title       string        `json:"title"`
	Description string        `json:"description"`
	FlowerType  string        `json:"flower_type"`
	Rule        schedule.Rule `json:"rule"`
	Paused      bool          `json:"paused"`
}

func validTask(in *taskInput) error {
	if in.FlowerType != "" && !validGardenFlower(in.FlowerType) {
		return errors.New("请选择郁金香、雏菊、羽扇豆或绣球")
	}
	in.Title = strings.Join(strings.Fields(in.Title), " ")
	in.Description = strings.TrimSpace(in.Description)
	if utf8.RuneCountInString(in.Title) < 1 || utf8.RuneCountInString(in.Title) > 100 {
		return errors.New("任务名称请填写 1–100 个字")
	}
	if utf8.RuneCountInString(in.Description) > 4000 {
		return errors.New("说明不能超过 4000 字")
	}
	return schedule.Validate(in.Rule)
}
func (a *App) listTasks(w http.ResponseWriter, r *http.Request) {
	tasks, err := a.allTasks(currentUser(r).ID, false)
	if err != nil {
		dbFailure(w)
		return
	}
	for i := range tasks {
		tasks[i] = a.view(tasks[i])
	}
	writeJSON(w, 200, map[string]any{"tasks": tasks})
}
func (a *App) createTask(w http.ResponseWriter, r *http.Request) {
	var in taskInput
	if !decode(w, r, &in) {
		return
	}
	if err := validTask(&in); err != nil {
		writeError(w, 400, err.Error())
		return
	}
	// Precise windows may already be in progress; Next below still requires
	// an unsent future slot before their deadline. Unbounded relative drafts
	// must retain their originally requested first reminder.
	if in.Rule.Kind == "minute_interval" && in.Rule.EndAt == "" {
		first, _ := time.Parse(time.RFC3339Nano, in.Rule.FirstAt)
		if !first.After(a.now()) {
			writeError(w, 400, "首次提醒时间已过，请调整首次时间后再创建")
			return
		}
	}
	if _, ok := schedule.Next(in.Rule, a.now()); !ok {
		writeError(w, 400, "没有未来提醒时间，请调整日期或时间")
		return
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	user := currentUser(r)
	var count int
	if err := a.db.QueryRow(`SELECT COUNT(*) FROM tasks WHERE user_id=? AND archived=0`, user.ID).Scan(&count); err != nil {
		dbFailure(w)
		return
	}
	if count >= 500 {
		writeError(w, 400, "最多保留 500 个有效任务，请先归档一些任务")
		return
	}
	id := randomID()
	now := stamp(a.now())
	rule, _ := json.Marshal(in.Rule)
	tx, err := a.db.BeginTx(r.Context(), nil)
	if err != nil {
		dbFailure(w)
		return
	}
	defer tx.Rollback()
	_, err = tx.Exec(`INSERT INTO tasks(id,user_id,title,description,rule,paused,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)`, id, user.ID, in.Title, in.Description, string(rule), in.Paused, now, now)
	if err != nil {
		dbFailure(w)
		return
	}
	if _, err = tx.Exec(`INSERT INTO garden_plants(id,user_id,task_id,title,flower_type,created_at) VALUES(?,?,?,?,?,?)`, id, user.ID, id, in.Title, in.FlowerType, now); err != nil {
		dbFailure(w)
		return
	}
	if err = tx.Commit(); err != nil {
		dbFailure(w)
		return
	}
	t, err := a.findTask(id, user.ID)
	if err != nil {
		dbFailure(w)
		return
	}
	writeJSON(w, 201, a.view(t))
}
func (a *App) getTask(w http.ResponseWriter, r *http.Request) {
	// Historical records keep linking to archived tasks, but mutations use findTask.
	t, err := scanTask(a.db.QueryRow(`SELECT `+taskColumns+` FROM tasks WHERE id=? AND user_id=?`, r.PathValue("id"), currentUser(r).ID))
	if err == sql.ErrNoRows {
		writeError(w, 404, "任务不存在")
		return
	}
	if err != nil {
		dbFailure(w)
		return
	}
	writeJSON(w, 200, a.view(t))
}
func (a *App) updateTask(w http.ResponseWriter, r *http.Request) {
	var in taskInput
	if !decode(w, r, &in) {
		return
	}
	if err := validTask(&in); err != nil {
		writeError(w, 400, err.Error())
		return
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	t, err := a.findTask(r.PathValue("id"), currentUser(r).ID)
	if err == sql.ErrNoRows {
		writeError(w, 404, "任务不存在")
		return
	}
	if err != nil {
		dbFailure(w)
		return
	}
	rule, _ := json.Marshal(in.Rule)
	previousRule, _ := json.Marshal(t.Rule)
	if string(rule) != string(previousRule) {
		if _, ok := schedule.Next(in.Rule, a.now()); !ok {
			writeError(w, 400, "没有未来提醒时间，请调整日期或时间")
			return
		}
	}
	tx, err := a.db.BeginTx(r.Context(), nil)
	if err != nil {
		dbFailure(w)
		return
	}
	defer tx.Rollback()
	_, err = tx.Exec(`UPDATE tasks SET title=?,description=?,rule=?,paused=?,updated_at=?,snooze_until='',snooze_cycle='' WHERE id=?`, in.Title, in.Description, string(rule), in.Paused, stamp(a.now()), t.ID)
	if err != nil {
		dbFailure(w)
		return
	}
	if in.FlowerType != "" {
		if err = syncGarden(tx, t.UserID); err != nil {
			dbFailure(w)
			return
		}
		if _, err = tx.Exec(`UPDATE garden_plants SET flower_type=? WHERE task_id=? AND user_id=?`, in.FlowerType, t.ID, t.UserID); err != nil {
			dbFailure(w)
			return
		}
	}
	if err = tx.Commit(); err != nil {
		dbFailure(w)
		return
	}
	t, err = a.findTask(t.ID, t.UserID)
	if err != nil {
		dbFailure(w)
		return
	}
	writeJSON(w, 200, a.view(t))
}
func (a *App) setTaskPaused(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Paused *bool `json:"paused"`
	}
	if !decode(w, r, &in) {
		return
	}
	if in.Paused == nil {
		writeError(w, 400, "请提供 paused 布尔值")
		return
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	t, err := a.findTask(r.PathValue("id"), currentUser(r).ID)
	if err == sql.ErrNoRows {
		writeError(w, 404, "任务不存在")
		return
	}
	if err != nil {
		dbFailure(w)
		return
	}
	// A switch changes only its own state: a stale editor cannot overwrite a
	// newer rule, description, or an already requested snooze.
	if t.Paused != *in.Paused {
		if _, err = a.db.Exec(`UPDATE tasks SET paused=?,updated_at=? WHERE id=? AND user_id=? AND archived=0`, *in.Paused, stamp(a.now()), t.ID, t.UserID); err != nil {
			dbFailure(w)
			return
		}
		t, err = a.findTask(t.ID, t.UserID)
		if err != nil {
			dbFailure(w)
			return
		}
	}
	writeJSON(w, 200, a.view(t))
}

func (a *App) archiveTask(w http.ResponseWriter, r *http.Request) {
	a.mu.Lock()
	defer a.mu.Unlock()
	res, err := a.db.Exec(`UPDATE tasks SET archived=1,updated_at=? WHERE id=? AND user_id=? AND archived=0`, stamp(a.now()), r.PathValue("id"), currentUser(r).ID)
	if err != nil {
		dbFailure(w)
		return
	}
	n, _ := res.RowsAffected()
	if n == 0 {
		writeError(w, 404, "任务不存在")
		return
	}
	writeJSON(w, 200, map[string]bool{"ok": true})
}
func scanCompletion(row scanner) (Completion, error) {
	var c Completion
	var media string
	err := row.Scan(&c.ID, &c.TaskID, &c.TaskTitle, &c.CycleKey, &c.Text, &media, &c.CreatedAt)
	if err == nil {
		err = json.Unmarshal([]byte(media), &c.MediaIDs)
	}
	return c, err
}

const completionColumns = `id,task_id,task_title,cycle_key,text,media_ids,created_at`

// Growth belongs to this mutation response, never to a saved completion record.
type completionResponse struct {
	Completion
	Growth completionGrowth `json:"growth"`
	Reward completionReward `json:"reward"`
}

type completionGrowth struct {
	FromCount int    `json:"from_count"`
	ToCount   int    `json:"to_count"`
	FromStage string `json:"from_stage"`
	ToStage   string `json:"to_stage"`
	Added     bool   `json:"added"`
}

func (a *App) completeTask(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Text     string   `json:"text"`
		MediaIDs []string `json:"media_ids"`
	}
	if !decode(w, r, &in) {
		return
	}
	in.Text = strings.TrimSpace(in.Text)
	if utf8.RuneCountInString(in.Text) > 4000 || len(in.MediaIDs) > 8 {
		writeError(w, 400, "完成说明最多 4000 字、8 张照片")
		return
	}
	if in.MediaIDs == nil {
		in.MediaIDs = []string{}
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	user := currentUser(r)
	t, err := a.findTask(r.PathValue("id"), user.ID)
	if err == sql.ErrNoRows {
		writeError(w, 404, "任务不存在")
		return
	}
	if err != nil {
		dbFailure(w)
		return
	}
	cycle, ok := schedule.CycleAt(t.Rule, a.now())
	if !ok {
		writeError(w, 409, "当前不在任务执行范围内，请在任务日期内完成")
		return
	}
	tx, err := a.db.BeginTx(r.Context(), nil)
	if err != nil {
		dbFailure(w)
		return
	}
	defer tx.Rollback()
	if err = syncGarden(tx, user.ID); err != nil {
		dbFailure(w)
		return
	}
	previous, err := scanCompletion(tx.QueryRow(`SELECT `+completionColumns+` FROM completions WHERE task_id=? AND cycle_key=? AND user_id=?`, t.ID, cycle.Key, user.ID))
	if err == nil {
		var count int
		if err := tx.QueryRow(`SELECT growth_count FROM garden_plants WHERE task_id=? AND user_id=?`, t.ID, user.ID).Scan(&count); err != nil {
			dbFailure(w)
			return
		}
		wallet, err := readGardenWallet(tx, user.ID)
		if err != nil {
			dbFailure(w)
			return
		}
		if err = tx.Commit(); err != nil {
			dbFailure(w)
			return
		}
		writeJSON(w, 200, completionResponse{Completion: previous, Reward: completionReward{Balance: wallet.Balance}, Growth: completionGrowth{
			FromCount: count, ToCount: count, FromStage: gardenStage(count), ToStage: gardenStage(count), Added: false,
		}})
		return
	}
	if err != sql.ErrNoRows {
		dbFailure(w)
		return
	}
	seen := map[string]bool{}
	for _, id := range in.MediaIDs {
		if seen[id] {
			writeError(w, 400, "照片不能重复")
			return
		}
		seen[id] = true
		var owner string
		if err := tx.QueryRow(`SELECT user_id FROM media WHERE id=?`, id).Scan(&owner); err != nil || owner != user.ID {
			writeError(w, 400, "照片不存在或不属于当前账号")
			return
		}
	}
	c := Completion{ID: randomID(), TaskID: t.ID, TaskTitle: t.Title, CycleKey: cycle.Key, Text: in.Text, MediaIDs: in.MediaIDs, CreatedAt: stamp(a.now())}
	media, _ := json.Marshal(c.MediaIDs)
	var fromCount int
	if err = tx.QueryRow(`SELECT growth_count FROM garden_plants WHERE task_id=? AND user_id=?`, t.ID, user.ID).Scan(&fromCount); err != nil {
		dbFailure(w)
		return
	}
	if _, err = tx.Exec(`INSERT INTO completions(id,user_id,task_id,task_title,cycle_key,text,media_ids,created_at) VALUES(?,?,?,?,?,?,?,?)`, c.ID, user.ID, c.TaskID, c.TaskTitle, c.CycleKey, c.Text, string(media), c.CreatedAt); err != nil {
		dbFailure(w)
		return
	}
	if _, err = tx.Exec(`UPDATE tasks SET snooze_until='',snooze_cycle='' WHERE id=?`, t.ID); err != nil {
		dbFailure(w)
		return
	}
	if _, err = tx.Exec(`INSERT INTO garden_rewards(completion_id,user_id,amount,created_at) VALUES(?,?,?,?)`, c.ID, user.ID, gardenRewardPerCompletion, c.CreatedAt); err != nil {
		dbFailure(w)
		return
	}
	if _, err = tx.Exec(`UPDATE garden_wallets SET balance=balance+?,total_earned=total_earned+? WHERE user_id=?`, gardenRewardPerCompletion, gardenRewardPerCompletion, user.ID); err != nil {
		dbFailure(w)
		return
	}
	wallet, err := readGardenWallet(tx, user.ID)
	if err != nil {
		dbFailure(w)
		return
	}
	if err = tx.Commit(); err != nil {
		dbFailure(w)
		return
	}
	writeJSON(w, 200, completionResponse{Completion: c, Reward: completionReward{WaterDrops: gardenRewardPerCompletion, Balance: wallet.Balance, Added: true}, Growth: completionGrowth{
		FromCount: fromCount, ToCount: fromCount, FromStage: gardenStage(fromCount), ToStage: gardenStage(fromCount), Added: false,
	}})
}
func (a *App) snoozeTask(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Minutes int `json:"minutes"`
	}
	if !decode(w, r, &in) {
		return
	}
	if in.Minutes < 1 || in.Minutes > 1440 {
		writeError(w, 400, "稍后提醒需为 1–1440 分钟")
		return
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	t, err := a.findTask(r.PathValue("id"), currentUser(r).ID)
	if err == sql.ErrNoRows {
		writeError(w, 404, "任务不存在")
		return
	}
	if err != nil {
		dbFailure(w)
		return
	}
	now := a.now()
	cycle, ok := schedule.CycleAt(t.Rule, now)
	until := now.Add(time.Duration(in.Minutes) * time.Minute)
	future, valid := schedule.CycleAt(t.Rule, until)
	if !ok || !valid || cycle.Key != future.Key || t.Paused {
		writeError(w, 409, "稍后提醒时间必须在当前有效周期内")
		return
	}
	complete, sent, err := a.cycleCounts(t.ID, cycle.Key)
	if err != nil {
		dbFailure(w)
		return
	}
	if stopForRule(t.Rule, complete, sent) {
		writeError(w, 409, "本期已完成或已达到提醒次数上限")
		return
	}
	if _, err = a.db.Exec(`UPDATE tasks SET snooze_until=?,snooze_cycle=? WHERE id=?`, stamp(until), cycle.Key, t.ID); err != nil {
		dbFailure(w)
		return
	}
	writeJSON(w, 200, map[string]bool{"ok": true})
}
func (a *App) allCompletions(userID string) ([]Completion, error) {
	rows, err := a.db.Query(`SELECT `+completionColumns+` FROM completions WHERE user_id=? ORDER BY created_at DESC,id`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Completion{}
	for rows.Next() {
		c, e := scanCompletion(rows)
		if e != nil {
			return nil, e
		}
		out = append(out, c)
	}
	return out, rows.Err()
}
func (a *App) allNotifications(userID string) ([]Notification, error) {
	rows, err := a.db.Query(`SELECT id,task_id,task_title,scheduled_at,sent_at,CASE WHEN status='sending' THEN 'pending' ELSE status END AS status,error,'email' AS channel,'legacy' AS source,'' AS reason FROM notifications WHERE user_id=? UNION ALL SELECT id,task_id,title,scheduled_at,sent_at,CASE WHEN status='sending' THEN 'pending' ELSE status END AS status,error,channel,source,reason FROM reminder_plans WHERE user_id=? ORDER BY scheduled_at DESC,id`, userID, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Notification{}
	for rows.Next() {
		var n Notification
		if err := rows.Scan(&n.ID, &n.TaskID, &n.TaskTitle, &n.ScheduledAt, &n.SentAt, &n.Status, &n.Error, &n.Channel, &n.Source, &n.Reason); err != nil {
			return nil, err
		}
		out = append(out, n)
	}
	return out, rows.Err()
}
func (a *App) history(w http.ResponseWriter, r *http.Request) {
	records, err := a.allCompletions(currentUser(r).ID)
	if err != nil {
		dbFailure(w)
		return
	}
	writeJSON(w, 200, map[string]any{"records": records})
}
func (a *App) notifications(w http.ResponseWriter, r *http.Request) {
	records, err := a.allNotifications(currentUser(r).ID)
	if err != nil {
		dbFailure(w)
		return
	}
	writeJSON(w, 200, map[string]any{"notifications": records})
}
func (a *App) export(w http.ResponseWriter, r *http.Request) {
	user := currentUser(r)
	tasks, err := a.allTasks(user.ID, true)
	if err != nil {
		dbFailure(w)
		return
	}
	for i := range tasks {
		tasks[i] = a.view(tasks[i])
	}
	records, err := a.allCompletions(user.ID)
	if err != nil {
		dbFailure(w)
		return
	}
	notifications, err := a.allNotifications(user.ID)
	if err != nil {
		dbFailure(w)
		return
	}
	w.Header().Set("Content-Disposition", `attachment; filename="niannian-export.json"`)
	writeJSON(w, 200, map[string]any{"tasks": tasks, "records": records, "notifications": notifications})
}
