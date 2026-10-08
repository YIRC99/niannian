package app

import (
	"context"
	"crypto/subtle"
	"database/sql"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

const adminSchema = `
CREATE TABLE IF NOT EXISTS admin_audit(id INTEGER PRIMARY KEY AUTOINCREMENT,created_at TEXT NOT NULL,method TEXT NOT NULL,path TEXT NOT NULL,status INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS app_releases(id TEXT PRIMARY KEY,version_code INTEGER NOT NULL UNIQUE,version_name TEXT NOT NULL,notes TEXT NOT NULL,sha256 TEXT NOT NULL,size INTEGER NOT NULL,created_at TEXT NOT NULL,published INTEGER NOT NULL DEFAULT 1);
`

func (a *App) registerAdmin(root *http.ServeMux) {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /admin/users", a.adminUsers)
	mux.HandleFunc("GET /admin/users/{id}", a.adminUser)
	mux.HandleFunc("POST /admin/users", a.adminSaveUser)
	mux.HandleFunc("PUT /admin/users/{id}", a.adminSaveUser)
	mux.HandleFunc("DELETE /admin/users/{id}", a.adminDeleteUser)
	mux.HandleFunc("GET /admin/tasks", a.adminTasks)
	mux.HandleFunc("POST /admin/tasks", a.adminAsUser(a.createTask))
	mux.HandleFunc("GET /admin/tasks/{id}", a.adminAsUser(a.getTask))
	mux.HandleFunc("PUT /admin/tasks/{id}", a.adminAsUser(a.updateTask))
	mux.HandleFunc("PUT /admin/tasks/{id}/paused", a.adminAsUser(a.setTaskPaused))
	mux.HandleFunc("DELETE /admin/tasks/{id}", a.adminAsUser(a.archiveTask))
	mux.HandleFunc("GET /admin/logs", a.adminLogs)
	mux.HandleFunc("GET /admin/releases", a.adminReleases)
	mux.HandleFunc("POST /admin/releases", a.uploadRelease)
	mux.HandleFunc("PUT /admin/releases/{id}", a.setReleasePublished)
	root.Handle("/admin/", a.adminAuthorize(mux))
	root.Handle("/admin", a.adminAuthorize(mux))
}

type adminResponse struct {
	http.ResponseWriter
	status int
}

func (w *adminResponse) Unwrap() http.ResponseWriter { return w.ResponseWriter }

func (w *adminResponse) WriteHeader(code int) {
	if w.status == 0 {
		w.status = code
		w.ResponseWriter.WriteHeader(code)
	}
}
func (w *adminResponse) Write(b []byte) (int, error) {
	if w.status == 0 {
		w.WriteHeader(200)
	}
	return w.ResponseWriter.Write(b)
}

func (a *App) adminAuthorize(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// file:// pages have an opaque "null" origin. No cookies are accepted.
		origin := r.Header.Get("Origin")
		if origin == "null" {
			w.Header().Set("Access-Control-Allow-Origin", "null")
			w.Header().Set("Vary", "Origin")
			w.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
			w.Header().Set("Access-Control-Allow-Headers", "Authorization, Content-Type")
			if r.Header.Get("Access-Control-Request-Private-Network") == "true" {
				w.Header().Set("Access-Control-Allow-Private-Network", "true")
			}
		} else if origin != "" {
			writeError(w, 403, "请双击本地管理页面使用")
			return
		}
		if r.Method == "OPTIONS" {
			w.WriteHeader(204)
			return
		}
		if len(a.cfg.AdminKey) < 32 {
			writeError(w, 503, "管理功能未启用，请配置至少 32 位的 admin_key")
			return
		}
		supplied := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
		want, got := digest(a.cfg.AdminKey), digest(supplied)
		if !strings.HasPrefix(r.Header.Get("Authorization"), "Bearer ") || subtle.ConstantTimeCompare([]byte(want), []byte(got)) != 1 {
			writeError(w, 401, "管理密钥不正确")
			return
		}
		response := &adminResponse{ResponseWriter: w}
		next.ServeHTTP(response, r)
		// Never record request bodies, credentials, query strings, or headers.
		if r.Method != "GET" && r.Method != "HEAD" {
			status := response.status
			if status == 0 {
				status = 200
			}
			_, _ = a.db.Exec(`INSERT INTO admin_audit(created_at,method,path,status) VALUES(?,?,?,?)`, stamp(a.now()), r.Method, r.URL.Path, status)
			_, _ = a.db.Exec(`DELETE FROM admin_audit WHERE id < (SELECT COALESCE(MAX(id),0)-10000 FROM admin_audit)`)
		}
	})
}

func adminPage(r *http.Request) (int, int) {
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	if limit < 1 || limit > 100 {
		limit = 30
	}
	offset, _ := strconv.Atoi(r.URL.Query().Get("offset"))
	if offset < 0 {
		offset = 0
	}
	return limit, offset
}

type adminUserView struct {
	ID          string `json:"id"`
	Email       string `json:"email"`
	CreatedAt   string `json:"created_at"`
	PasswordSet bool   `json:"password_set"`
	TaskCount   int    `json:"task_count"`
}

const adminUserColumns = `u.id,u.email,u.created_at,EXISTS(SELECT 1 FROM password_credentials p WHERE p.user_id=u.id),(SELECT COUNT(*) FROM tasks t WHERE t.user_id=u.id AND t.archived=0)`

func (a *App) adminUsers(w http.ResponseWriter, r *http.Request) {
	limit, offset := adminPage(r)
	q := "%" + r.URL.Query().Get("q") + "%"
	var total int
	if err := a.db.QueryRow(`SELECT COUNT(*) FROM users WHERE email LIKE ? OR id LIKE ?`, q, q).Scan(&total); err != nil {
		dbFailure(w)
		return
	}
	rows, err := a.db.Query(`SELECT `+adminUserColumns+` FROM users u WHERE u.email LIKE ? OR u.id LIKE ? ORDER BY u.created_at DESC,u.id LIMIT ? OFFSET ?`, q, q, limit, offset)
	if err != nil {
		dbFailure(w)
		return
	}
	defer rows.Close()
	out := []adminUserView{}
	for rows.Next() {
		var u adminUserView
		if rows.Scan(&u.ID, &u.Email, &u.CreatedAt, &u.PasswordSet, &u.TaskCount) != nil {
			dbFailure(w)
			return
		}
		out = append(out, u)
	}
	if rows.Err() != nil {
		dbFailure(w)
		return
	}
	writeJSON(w, 200, map[string]any{"users": out, "total": total})
}
func (a *App) adminUser(w http.ResponseWriter, r *http.Request) {
	var u adminUserView
	err := a.db.QueryRow(`SELECT `+adminUserColumns+` FROM users u WHERE u.id=?`, r.PathValue("id")).Scan(&u.ID, &u.Email, &u.CreatedAt, &u.PasswordSet, &u.TaskCount)
	if err == sql.ErrNoRows {
		writeError(w, 404, "账号不存在")
		return
	}
	if err != nil {
		dbFailure(w)
		return
	}
	writeJSON(w, 200, u)
}
func (a *App) adminSaveUser(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Email    string `json:"email"`
		Password string `json:"password"`
	}
	if !decode(w, r, &in) {
		return
	}
	email, ok := normalizedEmail(in.Email)
	if !ok {
		writeError(w, 400, "请输入有效邮箱")
		return
	}
	creating := r.Method == "POST"
	if (creating || in.Password != "") && !validPassword(in.Password) {
		writeError(w, 400, "密码至少 8 个字，最多 128 字节")
		return
	}
	hash := ""
	if in.Password != "" {
		var err error
		hash, err = hashPassword(in.Password)
		if err != nil {
			dbFailure(w)
			return
		}
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	a.authMu.Lock()
	defer a.authMu.Unlock()
	tx, err := a.db.BeginTx(r.Context(), nil)
	if err != nil {
		dbFailure(w)
		return
	}
	defer tx.Rollback()
	id := r.PathValue("id")
	oldEmail := ""
	if !creating {
		err = tx.QueryRow(`SELECT email FROM users WHERE id=?`, id).Scan(&oldEmail)
		if err == sql.ErrNoRows {
			writeError(w, 404, "账号不存在")
			return
		}
		if err != nil {
			dbFailure(w)
			return
		}
	} else {
		id = randomID()
	}
	var duplicate int
	if err = tx.QueryRow(`SELECT COUNT(*) FROM users WHERE email=? AND id<>?`, email, id).Scan(&duplicate); err != nil {
		dbFailure(w)
		return
	}
	if duplicate > 0 {
		writeError(w, 409, "该邮箱已存在")
		return
	}
	now := stamp(a.now())
	if creating {
		_, err = tx.Exec(`INSERT INTO users(id,email,created_at) VALUES(?,?,?)`, id, email, now)
	} else {
		_, err = tx.Exec(`UPDATE users SET email=? WHERE id=?`, email, id)
	}
	if err != nil {
		dbFailure(w)
		return
	}
	if hash != "" {
		_, err = tx.Exec(`INSERT INTO password_credentials(user_id,password_hash,updated_at) VALUES(?,?,?) ON CONFLICT(user_id) DO UPDATE SET password_hash=excluded.password_hash,updated_at=excluded.updated_at`, id, hash, now)
		if err != nil {
			dbFailure(w)
			return
		}
	}
	if hash != "" || oldEmail != email {
		if _, err = tx.Exec(`DELETE FROM sessions WHERE user_id=?`, id); err != nil {
			dbFailure(w)
			return
		}
		if _, err = tx.Exec(`DELETE FROM codes WHERE email IN (?,?)`, oldEmail, email); err != nil {
			dbFailure(w)
			return
		}
	}
	if err = tx.Commit(); err != nil {
		dbFailure(w)
		return
	}
	status := 200
	if creating {
		status = 201
	}
	writeJSON(w, status, map[string]string{"id": id, "email": email})
}
func (a *App) adminDeleteUser(w http.ResponseWriter, r *http.Request) {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.authMu.Lock()
	defer a.authMu.Unlock()
	tx, err := a.db.BeginTx(r.Context(), nil)
	if err != nil {
		dbFailure(w)
		return
	}
	defer tx.Rollback()
	id := r.PathValue("id")
	var email string
	err = tx.QueryRow(`SELECT email FROM users WHERE id=?`, id).Scan(&email)
	if err == sql.ErrNoRows {
		writeError(w, 404, "账号不存在")
		return
	}
	if err != nil {
		dbFailure(w)
		return
	}
	rows, err := tx.Query(`SELECT filename FROM media WHERE user_id=?`, id)
	if err != nil {
		dbFailure(w)
		return
	}
	files := []string{}
	for rows.Next() {
		var f string
		if rows.Scan(&f) != nil {
			rows.Close()
			dbFailure(w)
			return
		}
		files = append(files, f)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		dbFailure(w)
		return
	}
	for _, table := range []string{"reminder_decisions", "reminder_strategies"} {
		if _, err = tx.Exec(`DELETE FROM `+table+` WHERE task_id IN (SELECT id FROM tasks WHERE user_id=?)`, id); err != nil {
			dbFailure(w)
			return
		}
	}
	for _, table := range []string{"reminder_local_flows", "reminder_plans", "reminder_devices", "reminder_settings", "garden_watering", "garden_rewards", "garden_plants", "garden_wallets", "completions", "notifications", "tasks", "media", "ai_requests", "sessions", "password_credentials"} {
		if _, err = tx.Exec(`DELETE FROM `+table+` WHERE user_id=?`, id); err != nil {
			dbFailure(w)
			return
		}
	}
	if _, err = tx.Exec(`DELETE FROM codes WHERE email=?`, email); err != nil {
		dbFailure(w)
		return
	}
	if _, err = tx.Exec(`DELETE FROM users WHERE id=?`, id); err != nil {
		dbFailure(w)
		return
	}
	if err = tx.Commit(); err != nil {
		dbFailure(w)
		return
	}
	for _, f := range files {
		if f != "" && filepath.Base(f) == f {
			_ = os.Remove(filepath.Join(a.cfg.DataDir, "media", f))
		}
	}
	writeJSON(w, 200, map[string]string{"message": "账号及其数据已删除"})
}

func (a *App) adminAsUser(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id := r.URL.Query().Get("user_id")
		if taskID := r.PathValue("id"); taskID != "" {
			err := a.db.QueryRow(`SELECT user_id FROM tasks WHERE id=?`, taskID).Scan(&id)
			if err == sql.ErrNoRows {
				writeError(w, 404, "任务不存在")
				return
			}
			if err != nil {
				dbFailure(w)
				return
			}
		}
		var u User
		err := a.db.QueryRow(`SELECT id,email FROM users WHERE id=?`, id).Scan(&u.ID, &u.Email)
		if err == sql.ErrNoRows {
			writeError(w, 404, "请指定存在的账号")
			return
		}
		if err != nil {
			dbFailure(w)
			return
		}
		next(w, r.WithContext(context.WithValue(r.Context(), userContextKey{}, u)))
	}
}
func (a *App) adminTasks(w http.ResponseWriter, r *http.Request) {
	limit, offset := adminPage(r)
	user := r.URL.Query().Get("user_id")
	q := "%" + r.URL.Query().Get("q") + "%"
	where := ` WHERE (?='' OR user_id=?) AND (title LIKE ? OR id LIKE ?)`
	args := []any{user, user, q, q}
	var total int
	if err := a.db.QueryRow(`SELECT COUNT(*) FROM tasks`+where, args...).Scan(&total); err != nil {
		dbFailure(w)
		return
	}
	rows, err := a.db.Query(`SELECT `+taskColumns+` FROM tasks`+where+` ORDER BY created_at DESC,id LIMIT ? OFFSET ?`, append(args, limit, offset)...)
	if err != nil {
		dbFailure(w)
		return
	}
	items := []task{}
	for rows.Next() {
		t, err := scanTask(rows)
		if err != nil {
			rows.Close()
			dbFailure(w)
			return
		}
		items = append(items, t)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		dbFailure(w)
		return
	}
	type item struct {
		task
		UserID string `json:"user_id"`
		Email  string `json:"email"`
	}
	out := []item{}
	for _, t := range items {
		var email string
		if a.db.QueryRow(`SELECT email FROM users WHERE id=?`, t.UserID).Scan(&email) != nil {
			dbFailure(w)
			return
		}
		out = append(out, item{a.view(t), t.UserID, email})
	}
	writeJSON(w, 200, map[string]any{"tasks": out, "total": total})
}
func (a *App) adminLogs(w http.ResponseWriter, r *http.Request) {
	limit, offset := adminPage(r)
	kind := r.URL.Query().Get("kind")
	user := r.URL.Query().Get("user_id")
	type entry struct {
		Time   string `json:"time"`
		Kind   string `json:"kind"`
		UserID string `json:"user_id"`
		Detail string `json:"detail"`
		Status string `json:"status"`
		Error  string `json:"error"`
	}
	query := ` FROM (SELECT created_at AS time,'admin' AS kind,'' AS user_id,method||' '||path AS detail,CAST(status AS TEXT) AS status,'' AS error FROM admin_audit UNION ALL SELECT scheduled_at,'reminder',user_id,'email · '||task_title,status,error FROM notifications UNION ALL SELECT scheduled_at,'reminder',user_id,channel||' · '||title,status,error FROM reminder_plans) WHERE (?='' OR kind=?) AND (?='' OR user_id=?)`
	args := []any{kind, kind, user, user}
	var total int
	if a.db.QueryRow(`SELECT COUNT(*)`+query, args...).Scan(&total) != nil {
		dbFailure(w)
		return
	}
	rows, err := a.db.Query(`SELECT time,kind,user_id,detail,status,error`+query+` ORDER BY time DESC LIMIT ? OFFSET ?`, append(args, limit, offset)...)
	if err != nil {
		dbFailure(w)
		return
	}
	defer rows.Close()
	out := []entry{}
	for rows.Next() {
		var e entry
		if rows.Scan(&e.Time, &e.Kind, &e.UserID, &e.Detail, &e.Status, &e.Error) != nil {
			dbFailure(w)
			return
		}
		out = append(out, e)
	}
	if rows.Err() != nil {
		dbFailure(w)
		return
	}
	writeJSON(w, 200, map[string]any{"logs": out, "total": total})
}
