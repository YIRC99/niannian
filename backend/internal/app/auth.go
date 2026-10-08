package app

import (
	"crypto/rand"
	"database/sql"
	"fmt"
	"math/big"
	"net/http"
	stdmail "net/mail"
	"niannian/internal/mail"
	"strings"
	"time"
)

func normalizedEmail(s string) (string, bool) {
	s = strings.ToLower(strings.TrimSpace(s))
	if len(s) > 254 {
		return "", false
	}
	m, err := stdmail.ParseAddress(s)
	return s, err == nil && m.Address == s
}
func (a *App) requestCode(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Email string `json:"email"`
	}
	if !decode(w, r, &in) {
		return
	}
	email, valid := normalizedEmail(in.Email)
	if !valid {
		writeError(w, 400, "请输入有效邮箱")
		return
	}
	a.authMu.Lock()
	locked := true
	defer func() {
		if locked {
			a.authMu.Unlock()
		}
	}()
	now := a.now()
	var sentAt, day string
	var count int
	err := a.db.QueryRow(`SELECT sent_at,day,day_count FROM codes WHERE email=?`, email).Scan(&sentAt, &day, &count)
	if err != nil && err != sql.ErrNoRows {
		dbFailure(w)
		return
	}
	if sentAt != "" {
		last, _ := time.Parse(time.RFC3339Nano, sentAt)
		if now.Sub(last) < time.Minute {
			writeError(w, 429, "请 60 秒后再获取验证码")
			return
		}
	}
	today := now.UTC().Format("2006-01-02")
	if day != today {
		count = 0
	}
	if count >= 10 {
		writeError(w, 429, "今天的验证码次数已用完，请明天再试")
		return
	}
	n, err := rand.Int(rand.Reader, big.NewInt(1000000))
	if err != nil {
		writeError(w, 500, "暂时无法生成验证码")
		return
	}
	code := fmt.Sprintf("%06d", n.Int64())
	salt := randomID()
	_, err = a.db.Exec(`INSERT INTO codes(email,hash,salt,expires_at,attempts,sent_at,day,day_count) VALUES(?,?,?,?,0,?,?,?) ON CONFLICT(email) DO UPDATE SET hash=excluded.hash,salt=excluded.salt,expires_at=excluded.expires_at,attempts=0,sent_at=excluded.sent_at,day=excluded.day,day_count=excluded.day_count`, email, digest(email+salt+code), salt, stamp(now.Add(10*time.Minute)), stamp(now), today, count+1)
	if err != nil {
		dbFailure(w)
		return
	}
	a.authMu.Unlock()
	locked = false
	if err = a.mailer.Send(r.Context(), email, "念念 · 账号验证码", "你的验证码是："+code+"\n可用于注册、设置或重设密码及验证码登录，10 分钟内有效。若非本人操作，请忽略。请勿把验证码分享给他人。", mail.Content{Code: code}); err != nil {
		// A failed attempt still consumes quota; this keeps failure loops from spamming SMTP.
		a.db.Exec(`UPDATE codes SET expires_at=? WHERE email=? AND hash=?`, stamp(now), email, digest(email+salt+code))
		writeError(w, 502, "验证码邮件未能发出，请稍后重试或检查服务器邮件配置")
		return
	}
	writeJSON(w, 200, map[string]string{"message": "验证码已发送"})
}
func (a *App) verifyCode(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Email string `json:"email"`
		Code  string `json:"code"`
	}
	if !decode(w, r, &in) {
		return
	}
	email, valid := normalizedEmail(in.Email)
	if !valid || len(in.Code) != 6 {
		writeError(w, 400, "验证码无效或已过期")
		return
	}
	a.authMu.Lock()
	defer a.authMu.Unlock()
	now := a.now()
	if !a.checkVerificationCode(w, email, in.Code, now) {
		return
	}
	tx, err := a.db.Begin()
	if err != nil {
		dbFailure(w)
		return
	}
	defer tx.Rollback()
	user := User{ID: randomID(), Email: email}
	_, err = tx.Exec(`INSERT INTO users(id,email,created_at) VALUES(?,?,?) ON CONFLICT(email) DO NOTHING`, user.ID, email, stamp(now))
	if err != nil {
		dbFailure(w)
		return
	}
	if err = tx.QueryRow(`SELECT id,EXISTS(SELECT 1 FROM password_credentials p WHERE p.user_id=u.id) FROM users u WHERE email=?`, email).Scan(&user.ID, &user.PasswordSet); err != nil {
		dbFailure(w)
		return
	}
	token, err := createSession(tx, user.ID, now)
	if err != nil {
		dbFailure(w)
		return
	}
	if _, err = tx.Exec(`UPDATE codes SET expires_at=? WHERE email=?`, stamp(now), email); err != nil {
		dbFailure(w)
		return
	}
	if err = tx.Commit(); err != nil {
		dbFailure(w)
		return
	}
	writeJSON(w, 200, map[string]any{"token": token, "user": user})
}
func (a *App) logout(w http.ResponseWriter, r *http.Request) {
	token := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
	if _, err := a.db.Exec(`DELETE FROM sessions WHERE hash=?`, digest(token)); err != nil {
		dbFailure(w)
		return
	}
	writeJSON(w, 200, map[string]bool{"ok": true})
}
