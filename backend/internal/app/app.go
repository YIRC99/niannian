package app

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/gofrs/flock"
	_ "modernc.org/sqlite"
	"niannian/internal/ai"
	"niannian/internal/config"
	"niannian/internal/mail"
	"niannian/internal/schedule"
)

type mailSender interface {
	Send(context.Context, string, string, string) error
}
type aiParser interface {
	Parse(context.Context, string, time.Time) (ai.Draft, error)
}
type App struct {
	cfg       config.Config
	db        *sql.DB
	lock      *flock.Flock
	mailer    mailSender
	parser    aiParser
	now       func() time.Time
	mu        sync.Mutex
	authMu    sync.Mutex
	closeOnce sync.Once
}
type User struct {
	ID          string `json:"id"`
	Email       string `json:"email"`
	PasswordSet bool   `json:"password_set"`
}
type userContextKey struct{}
type task struct {
	ID           string        `json:"id"`
	Title        string        `json:"title"`
	Description  string        `json:"description"`
	FlowerType   string        `json:"flower_type"`
	Rule         schedule.Rule `json:"rule"`
	Paused       bool          `json:"paused"`
	CreatedAt    string        `json:"created_at"`
	UpdatedAt    string        `json:"updated_at"`
	Summary      string        `json:"summary"`
	Status       string        `json:"status"`
	NextReminder string        `json:"next_reminder"`
	CycleKey     string        `json:"cycle_key"`
	UserID       string        `json:"-"`
	Archived     bool          `json:"archived,omitempty"`
	SnoozeUntil  string        `json:"-"`
	SnoozeCycle  string        `json:"-"`
}
type Completion struct {
	ID        string   `json:"id"`
	TaskID    string   `json:"task_id"`
	TaskTitle string   `json:"task_title"`
	CycleKey  string   `json:"cycle_key"`
	Text      string   `json:"text"`
	MediaIDs  []string `json:"media_ids"`
	CreatedAt string   `json:"created_at"`
}
type Notification struct {
	ID          string `json:"id"`
	TaskID      string `json:"task_id"`
	TaskTitle   string `json:"task_title"`
	ScheduledAt string `json:"scheduled_at"`
	SentAt      string `json:"sent_at"`
	Status      string `json:"status"`
	Error       string `json:"error"`
	Channel     string `json:"channel"`
	Source      string `json:"source"`
	Reason      string `json:"reason"`
}

func New(cfg config.Config) (*App, error) {
	if cfg.DataDir == "" {
		cfg.DataDir = "./data"
	}
	if err := os.MkdirAll(filepath.Join(cfg.DataDir, "media"), 0700); err != nil {
		return nil, err
	}
	guard := flock.New(filepath.Join(cfg.DataDir, "process.lock"))
	ok, err := guard.TryLock()
	if err != nil {
		return nil, err
	}
	if !ok {
		return nil, errors.New("数据目录已由另一进程使用")
	}
	db, err := sql.Open("sqlite", filepath.Join(cfg.DataDir, "niannian.sqlite"))
	if err != nil {
		guard.Unlock()
		return nil, err
	}
	db.SetMaxOpenConns(1)
	a := &App{cfg: cfg, db: db, lock: guard, mailer: mail.New(cfg), parser: ai.New(cfg), now: time.Now}
	if _, err = db.Exec(schema + reminderSchema + gardenSchema); err != nil {
		a.Close()
		return nil, fmt.Errorf("initialize database: %w", err)
	}
	// The exclusive directory lock guarantees an interrupted send has no live worker.
	// SMTP acknowledgement is not transactional: a crash after acceptance may repeat one mail.
	if _, err = db.Exec(`UPDATE notifications SET status='failed', next_attempt_at='', error='服务重启，等待重试' WHERE status='sending'`); err != nil {
		a.Close()
		return nil, err
	}
	if _, err = db.Exec(`UPDATE reminder_plans SET status='failed',reported_at=?,error='服务在邮件发送时重启，SMTP是否接受未知，等待重新评估' WHERE channel='email' AND status='sending'`, stamp(a.now())); err != nil {
		a.Close()
		return nil, err
	}
	return a, nil
}
func (a *App) Close() error {
	var err error
	a.closeOnce.Do(func() {
		err = a.db.Close()
		if e := a.lock.Unlock(); err == nil {
			err = e
		}
	})
	return err
}

const schema = `
PRAGMA journal_mode=WAL;
PRAGMA synchronous=FULL;
PRAGMA foreign_keys=ON;
PRAGMA busy_timeout=5000;
CREATE TABLE IF NOT EXISTS ai_requests(user_id TEXT NOT NULL,created_at TEXT NOT NULL);
CREATE INDEX IF NOT EXISTS ai_requests_user ON ai_requests(user_id,created_at);
CREATE TABLE IF NOT EXISTS users(id TEXT PRIMARY KEY,email TEXT NOT NULL UNIQUE,created_at TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS codes(email TEXT PRIMARY KEY,hash TEXT NOT NULL,salt TEXT NOT NULL,expires_at TEXT NOT NULL,attempts INTEGER NOT NULL DEFAULT 0,sent_at TEXT NOT NULL,day TEXT NOT NULL,day_count INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS sessions(hash TEXT PRIMARY KEY,user_id TEXT NOT NULL REFERENCES users(id),expires_at TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS password_credentials(user_id TEXT PRIMARY KEY REFERENCES users(id),password_hash TEXT NOT NULL,updated_at TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS password_failures(scope TEXT PRIMARY KEY,attempts INTEGER NOT NULL,reset_at TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS tasks(id TEXT PRIMARY KEY,user_id TEXT NOT NULL REFERENCES users(id),title TEXT NOT NULL,description TEXT NOT NULL,rule TEXT NOT NULL,paused INTEGER NOT NULL DEFAULT 0,archived INTEGER NOT NULL DEFAULT 0,created_at TEXT NOT NULL,updated_at TEXT NOT NULL,snooze_until TEXT NOT NULL DEFAULT '',snooze_cycle TEXT NOT NULL DEFAULT '');
CREATE INDEX IF NOT EXISTS tasks_user ON tasks(user_id,archived);
CREATE TABLE IF NOT EXISTS media(id TEXT PRIMARY KEY,user_id TEXT NOT NULL REFERENCES users(id),filename TEXT NOT NULL,content_type TEXT NOT NULL,size INTEGER NOT NULL,created_at TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS completions(id TEXT PRIMARY KEY,user_id TEXT NOT NULL REFERENCES users(id),task_id TEXT NOT NULL REFERENCES tasks(id),task_title TEXT NOT NULL,cycle_key TEXT NOT NULL,text TEXT NOT NULL,media_ids TEXT NOT NULL,created_at TEXT NOT NULL,UNIQUE(task_id,cycle_key));
CREATE INDEX IF NOT EXISTS completion_user ON completions(user_id,created_at);
CREATE TABLE IF NOT EXISTS notifications(id TEXT PRIMARY KEY,user_id TEXT NOT NULL REFERENCES users(id),task_id TEXT NOT NULL REFERENCES tasks(id),task_title TEXT NOT NULL,cycle_key TEXT NOT NULL,scheduled_at TEXT NOT NULL,sent_at TEXT NOT NULL DEFAULT '',status TEXT NOT NULL DEFAULT 'pending',error TEXT NOT NULL DEFAULT '',attempts INTEGER NOT NULL DEFAULT 0,next_attempt_at TEXT NOT NULL DEFAULT '',rule_snapshot TEXT NOT NULL,UNIQUE(task_id,scheduled_at));
CREATE INDEX IF NOT EXISTS notification_due ON notifications(status,next_attempt_at);
CREATE INDEX IF NOT EXISTS notification_user ON notifications(user_id,scheduled_at);
`

func (a *App) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /health", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, 200, map[string]string{"status": "ok", "version": "2.0.0"})
	})
	mux.HandleFunc("POST /api/v1/auth/code", a.requestCode)
	mux.HandleFunc("POST /api/v1/auth/verify", a.verifyCode)
	mux.HandleFunc("POST /api/v1/auth/register", a.registerPassword)
	mux.HandleFunc("POST /api/v1/auth/login", a.loginPassword)
	mux.HandleFunc("POST /api/v1/auth/password/reset", a.resetPassword)
	secured := func(pattern string, h http.HandlerFunc) { mux.Handle(pattern, a.authorize(h)) }
	secured("GET /api/v1/me", func(w http.ResponseWriter, r *http.Request) { writeJSON(w, 200, currentUser(r)) })
	secured("POST /api/v1/auth/logout", a.logout)
	secured("GET /api/v1/tasks", a.listTasks)
	secured("POST /api/v1/tasks", a.createTask)
	secured("GET /api/v1/tasks/{id}", a.getTask)
	secured("PUT /api/v1/tasks/{id}", a.updateTask)
	secured("PUT /api/v1/tasks/{id}/paused", a.setTaskPaused)
	secured("DELETE /api/v1/tasks/{id}", a.archiveTask)
	secured("POST /api/v1/tasks/{id}/complete", a.completeTask)
	secured("POST /api/v1/tasks/{id}/snooze", a.snoozeTask)
	secured("GET /api/v1/history", a.history)
	secured("GET /api/v1/garden", a.garden)
	secured("POST /api/v1/garden/plants", a.createGardenPlant)
	secured("POST /api/v1/garden/plants/{id}/water", a.waterGardenPlant)
	secured("PUT /api/v1/garden/plants/{id}/flower", a.setGardenFlower)
	secured("GET /api/v1/notifications", a.notifications)
	secured("GET /api/v1/reminders/settings", a.getReminderSettings)
	secured("PUT /api/v1/reminders/settings", a.putReminderSettings)
	secured("POST /api/v1/reminders/sync", a.syncReminders)
	secured("GET /api/v1/calendar", a.calendar)
	secured("POST /api/v1/media", a.uploadMedia)
	secured("GET /api/v1/media/{id}", a.getMedia)
	secured("POST /api/v1/ai/parse", a.parseAI)
	secured("GET /api/v1/export", a.export)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Cache-Control", "no-store")
		defer func() {
			if recover() != nil {
				writeError(w, 500, "服务暂时不可用")
			}
		}()
		mux.ServeHTTP(w, r)
	})
}
func currentUser(r *http.Request) User { return r.Context().Value(userContextKey{}).(User) }
func (a *App) authorize(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		token := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
		if token == "" || token == r.Header.Get("Authorization") {
			writeError(w, 401, "请先登录")
			return
		}
		var u User
		err := a.db.QueryRowContext(r.Context(), `SELECT u.id,u.email,EXISTS(SELECT 1 FROM password_credentials p WHERE p.user_id=u.id) FROM sessions s JOIN users u ON u.id=s.user_id WHERE s.hash=? AND s.expires_at>?`, digest(token), stamp(a.now())).Scan(&u.ID, &u.Email, &u.PasswordSet)
		if err != nil {
			writeError(w, 401, "登录已过期，请重新登录")
			return
		}
		next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), userContextKey{}, u)))
	})
}
func decode(w http.ResponseWriter, r *http.Request, v any) bool {
	r.Body = http.MaxBytesReader(w, r.Body, 1<<20)
	d := json.NewDecoder(r.Body)
	d.DisallowUnknownFields()
	if err := d.Decode(v); err != nil {
		writeError(w, 400, "请求内容格式不正确")
		return false
	}
	var extra any
	if err := d.Decode(&extra); err != io.EOF {
		writeError(w, 400, "请求只能包含一个 JSON 对象")
		return false
	}
	return true
}
func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}
func writeError(w http.ResponseWriter, status int, msg string) {
	writeJSON(w, status, map[string]string{"error": msg})
}
func stamp(t time.Time) string { return t.UTC().Format("2006-01-02T15:04:05.000000000Z") }
func digest(s string) string   { h := sha256.Sum256([]byte(s)); return hex.EncodeToString(h[:]) }
func randomID() string {
	b := make([]byte, 24)
	if _, err := rand.Read(b); err != nil {
		panic("secure random unavailable")
	}
	return hex.EncodeToString(b)
}
func dbFailure(w http.ResponseWriter) { writeError(w, 500, "数据保存失败，请稍后重试") }
