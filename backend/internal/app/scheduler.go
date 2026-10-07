package app

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"log"
	"niannian/internal/schedule"
	"time"
)

const maxSendAttempts = 5

func (a *App) RunScheduler(ctx context.Context) {
	ticker := time.NewTicker(30 * time.Second)
	defer ticker.Stop()
	for {
		if err := a.tick(ctx); err != nil && ctx.Err() == nil {
			log.Printf("提醒调度暂时失败，下次将重试")
		}
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
	}
}
func (a *App) candidate(t task, now time.Time) (schedule.Occurrence, bool) {
	if t.Paused || t.Archived {
		return schedule.Occurrence{}, false
	}
	if t.Rule.Kind == "minute_interval" {
		if _, active := schedule.CycleAt(t.Rule, now); !active {
			return schedule.Occurrence{}, false
		}
	}
	loc, err := time.LoadLocation(t.Rule.Timezone)
	if err != nil {
		return schedule.Occurrence{}, false
	}
	local := now.In(loc)
	dayStart := time.Date(local.Year(), local.Month(), local.Day(), 0, 0, 0, 0, loc)
	from := dayStart.Add(-time.Nanosecond)
	created, _ := time.Parse(time.RFC3339Nano, t.CreatedAt)
	if t.Rule.Kind == "minute_interval" || created.After(from) {
		from = created
	}
	// Minute intervals are continuous across midnight. Select only the latest
	// slot arithmetically so long outages never enumerate or send a backlog.
	candidate, found := schedule.LatestDue(t.Rule, from, now)
	if t.SnoozeUntil != "" {
		until, e := time.Parse(time.RFC3339Nano, t.SnoozeUntil)
		if e == nil {
			if until.After(now) {
				return schedule.Occurrence{}, false
			}
			if found && !candidate.At.After(until) {
				found = false
			}
			cycle, active := schedule.CycleAt(t.Rule, now)
			if (t.Rule.Kind == "minute_interval" || !until.Before(dayStart)) && active && cycle.Key == t.SnoozeCycle && (!found || until.After(candidate.At)) {
				candidate = schedule.Occurrence{At: until, CycleKey: t.SnoozeCycle}
				found = true
			}
		}
	}
	return candidate, found
}
func (a *App) tick(ctx context.Context) error {
	if err := a.tickReminderAgent(ctx); err != nil {
		return err
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	if err := ctx.Err(); err != nil {
		return err
	}
	now := a.now()
	rows, err := a.db.QueryContext(ctx, `SELECT `+taskColumns+` FROM tasks WHERE archived=0 AND paused=0`)
	if err != nil {
		return err
	}
	tasks := []task{}
	for rows.Next() {
		t, e := scanTask(rows)
		if e != nil {
			rows.Close()
			return e
		}
		tasks = append(tasks, t)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return err
	}
	for _, t := range tasks {
		managed, e := a.usesReminderAgent(t.UserID)
		if e != nil {
			return e
		}
		if managed {
			continue
		}
		occurrence, ok := a.candidate(t, now)
		if !ok {
			continue
		}
		complete, sent, e := a.cycleCounts(t.ID, occurrence.CycleKey)
		if e != nil {
			return e
		}
		if stopForRule(t.Rule, complete, sent) {
			continue
		}
		rule, _ := json.Marshal(t.Rule)
		if _, err = a.db.ExecContext(ctx, `INSERT INTO notifications(id,user_id,task_id,task_title,cycle_key,scheduled_at,rule_snapshot) VALUES(?,?,?,?,?,?,?) ON CONFLICT(task_id,scheduled_at) DO UPDATE SET task_title=excluded.task_title,cycle_key=excluded.cycle_key,rule_snapshot=excluded.rule_snapshot,status='pending',error='',attempts=0,next_attempt_at='' WHERE notifications.status NOT IN ('sent','sending') AND notifications.rule_snapshot<>excluded.rule_snapshot`, randomID(), t.UserID, t.ID, t.Title, occurrence.CycleKey, stamp(occurrence.At), string(rule)); err != nil {
			return err
		}
	}
	rows, err = a.db.QueryContext(ctx, `SELECT id,task_id,cycle_key,scheduled_at,attempts,rule_snapshot FROM notifications WHERE status IN ('pending','failed') AND attempts<? AND next_attempt_at<=? ORDER BY scheduled_at`, maxSendAttempts, stamp(now))
	if err != nil {
		return err
	}
	type queued struct {
		id, taskID, cycle, scheduled, rule string
		attempts                           int
	}
	pending := []queued{}
	for rows.Next() {
		var q queued
		if e := rows.Scan(&q.id, &q.taskID, &q.cycle, &q.scheduled, &q.attempts, &q.rule); e != nil {
			rows.Close()
			return e
		}
		pending = append(pending, q)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return err
	}
	cancel := func(id, reason string) error {
		_, e := a.db.ExecContext(ctx, `UPDATE notifications SET status='failed',attempts=?,error=? WHERE id=?`, maxSendAttempts, reason, id)
		return e
	}
	for _, q := range pending {
		if err := ctx.Err(); err != nil {
			return err
		}
		now = a.now()
		t, e := scanTask(a.db.QueryRowContext(ctx, `SELECT `+taskColumns+` FROM tasks WHERE id=?`, q.taskID))
		if e == sql.ErrNoRows {
			if e = cancel(q.id, "任务已不存在"); e != nil {
				return e
			}
			continue
		}
		if e != nil {
			return e
		}
		managed, e := a.usesReminderAgent(t.UserID)
		if e != nil {
			return e
		}
		if managed {
			if e = cancel(q.id, "已由多渠道提醒接管"); e != nil {
				return e
			}
			continue
		}
		occurrence, ok := a.candidate(t, now)
		rule, _ := json.Marshal(t.Rule)
		if t.Paused || t.Archived {
			if e = cancel(q.id, "任务已暂停或归档"); e != nil {
				return e
			}
			continue
		}
		if string(rule) != q.rule {
			if e = cancel(q.id, "时间规则已修改，旧提醒已取消"); e != nil {
				return e
			}
			continue
		}
		// Re-evaluate just before send, so completed or expired tasks never leave the queue.
		// Only the latest eligible slot is sent after downtime or a long retry delay.
		if !ok || q.scheduled != stamp(occurrence.At) || q.cycle != occurrence.CycleKey {
			if e = cancel(q.id, "提醒时段已过，或已由更晚的提醒替代"); e != nil {
				return e
			}
			continue
		}
		complete, sent, e := a.cycleCounts(t.ID, q.cycle)
		if e != nil {
			return e
		}
		if stopForRule(t.Rule, complete, sent) {
			if e = cancel(q.id, "本期已完成或已达到提醒次数上限"); e != nil {
				return e
			}
			continue
		}
		var email string
		if e = a.db.QueryRowContext(ctx, `SELECT email FROM users WHERE id=?`, t.UserID).Scan(&email); e != nil {
			return e
		}
		claim, e := a.db.ExecContext(ctx, `UPDATE notifications SET status='sending',attempts=attempts+1 WHERE id=? AND status IN ('pending','failed') AND attempts<?`, q.id, maxSendAttempts)
		if e != nil {
			return e
		}
		claimed, e := claim.RowsAffected()
		if e != nil {
			return e
		}
		if claimed == 0 {
			continue
		}
		body := "该做这件事啦：" + t.Title + "\n\n" + t.Description + "\n\n" + schedule.Summary(t.Rule) + "\n\n完成后请打开「念念」点击完成，可以附上文字或照片。\n本邮件由你在念念中创建的提醒任务触发。"
		sendCtx, cancelSend := context.WithTimeout(ctx, 35*time.Second)
		// The slot is now durably claimed. Network I/O must not block completing,
		// pausing, or editing other tasks. An already accepted mail cannot be recalled.
		a.mu.Unlock()
		sendErr := a.mailer.Send(sendCtx, email, "念念提醒 · "+t.Title, body)
		cancelSend()
		a.mu.Lock()
		persistCtx, cancelPersist := context.WithTimeout(context.WithoutCancel(ctx), 5*time.Second)
		if sendErr == nil {
			_, e = a.db.ExecContext(persistCtx, `UPDATE notifications SET status='sent',sent_at=?,error='',next_attempt_at='' WHERE id=?`, stamp(a.now()), q.id)
		} else {
			delay := time.Duration(1<<q.attempts) * time.Minute
			message := "邮件发送失败，将自动重试"
			if q.attempts+1 >= maxSendAttempts {
				message = "邮件发送连续失败 5 次，本次已停止，请检查服务器邮件配置"
			}
			_, e = a.db.ExecContext(persistCtx, `UPDATE notifications SET status='failed',error=?,next_attempt_at=? WHERE id=?`, message, stamp(a.now().Add(delay)), q.id)
		}
		cancelPersist()
		if e != nil {
			return fmt.Errorf("persist delivery state: %w", e)
		}
	}
	return nil
}
