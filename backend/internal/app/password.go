package app

import (
	"crypto/pbkdf2"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"database/sql"
	"encoding/hex"
	"net"
	"net/http"
	"strings"
	"time"
	"unicode/utf8"
)

const passwordIterations = 600000
const passwordHashPrefix = "pbkdf2-sha256$v1$600000$"

func validPassword(password string) bool {
	return utf8.ValidString(password) && utf8.RuneCountInString(password) >= 8 && len(password) <= 128
}

func hashPassword(password string) (string, error) {
	salt := make([]byte, 32)
	if _, err := rand.Read(salt); err != nil {
		return "", err
	}
	key, err := pbkdf2.Key(sha256.New, password, salt, passwordIterations, 32)
	if err != nil {
		return "", err
	}
	return passwordHashPrefix + hex.EncodeToString(salt) + "$" + hex.EncodeToString(key), nil
}

// Unknown users still pay the same KDF cost as a wrong password. Only this fixed,
// versioned format is accepted; stored values cannot choose an unbounded cost.
func matchesPassword(password, encoded string) bool {
	salt, want := make([]byte, 32), make([]byte, 32)
	valid := false
	if strings.HasPrefix(encoded, passwordHashPrefix) {
		parts := strings.Split(strings.TrimPrefix(encoded, passwordHashPrefix), "$")
		if len(parts) == 2 {
			s, e1 := hex.DecodeString(parts[0])
			k, e2 := hex.DecodeString(parts[1])
			if e1 == nil && e2 == nil && len(s) == 32 && len(k) == 32 {
				salt, want, valid = s, k, true
			}
		}
	}
	got, err := pbkdf2.Key(sha256.New, password, salt, passwordIterations, 32)
	return err == nil && subtle.ConstantTimeCompare(got, want) == 1 && valid
}

func (a *App) registerPassword(w http.ResponseWriter, r *http.Request) {
	a.setPasswordWithCode(w, r, false)
}

func (a *App) resetPassword(w http.ResponseWriter, r *http.Request) {
	a.setPasswordWithCode(w, r, true)
}

func (a *App) setPasswordWithCode(w http.ResponseWriter, r *http.Request, reset bool) {
	var in struct {
		Email    string `json:"email"`
		Code     string `json:"code"`
		Password string `json:"password"`
	}
	if !decode(w, r, &in) {
		return
	}
	email, valid := normalizedEmail(in.Email)
	if !valid || len(in.Code) != 6 {
		writeError(w, 400, "验证码无效或已过期")
		return
	}
	if !validPassword(in.Password) {
		writeError(w, 400, "密码至少 8 个字符，最多 128 字节，请勿省略首尾空格")
		return
	}
	a.authMu.Lock()
	defer a.authMu.Unlock()
	now := a.now()
	if !a.checkVerificationCode(w, email, in.Code, now) {
		return
	}
	tx, err := a.db.BeginTx(r.Context(), nil)
	if err != nil {
		dbFailure(w)
		return
	}
	defer tx.Rollback()
	user := User{Email: email}
	err = tx.QueryRow(`SELECT id,EXISTS(SELECT 1 FROM password_credentials p WHERE p.user_id=u.id) FROM users u WHERE email=?`, email).Scan(&user.ID, &user.PasswordSet)
	if err != nil && err != sql.ErrNoRows {
		dbFailure(w)
		return
	}
	if reset && err == sql.ErrNoRows {
		writeError(w, 400, "这个邮箱还没有注册，请先注册")
		return
	}
	if !reset && user.PasswordSet {
		writeError(w, 409, "这个账号已设置密码，请直接登录或重设密码")
		return
	}
	if user.ID == "" {
		user.ID = randomID()
		if _, err = tx.Exec(`INSERT INTO users(id,email,created_at) VALUES(?,?,?)`, user.ID, email, stamp(now)); err != nil {
			dbFailure(w)
			return
		}
	}
	encoded, err := hashPassword(in.Password)
	if err != nil {
		writeError(w, 500, "暂时无法保存密码，请稍后重试")
		return
	}
	if _, err = tx.Exec(`INSERT INTO password_credentials(user_id,password_hash,updated_at) VALUES(?,?,?) ON CONFLICT(user_id) DO UPDATE SET password_hash=excluded.password_hash,updated_at=excluded.updated_at`, user.ID, encoded, stamp(now)); err != nil {
		dbFailure(w)
		return
	}
	if reset {
		if _, err = tx.Exec(`DELETE FROM sessions WHERE user_id=?`, user.ID); err != nil {
			dbFailure(w)
			return
		}
	}
	if _, err = tx.Exec(`UPDATE codes SET expires_at=? WHERE email=?`, stamp(now), email); err != nil {
		dbFailure(w)
		return
	}
	if _, err = tx.Exec(`DELETE FROM password_failures WHERE scope=?`, "email:"+digest(email)); err != nil {
		dbFailure(w)
		return
	}
	token, err := createSession(tx, user.ID, now)
	if err != nil {
		dbFailure(w)
		return
	}
	if err = tx.Commit(); err != nil {
		dbFailure(w)
		return
	}
	user.PasswordSet = true
	writeJSON(w, 200, map[string]any{"token": token, "user": user})
}

func createSession(tx *sql.Tx, userID string, now time.Time) (string, error) {
	token := randomID()
	if _, err := tx.Exec(`INSERT INTO sessions(hash,user_id,expires_at) VALUES(?,?,?)`, digest(token), userID, stamp(now.Add(90*24*time.Hour))); err != nil {
		return "", err
	}
	if _, err := tx.Exec(`DELETE FROM sessions WHERE expires_at<=?`, stamp(now)); err != nil {
		return "", err
	}
	return token, nil
}

// authMu must be held. Correct codes are consumed only in the caller's successful
// transaction; incorrect attempts persist independently so rollback cannot reset them.
func (a *App) checkVerificationCode(w http.ResponseWriter, email, code string, now time.Time) bool {
	var hash, salt, expires string
	var attempts int
	err := a.db.QueryRow(`SELECT hash,salt,expires_at,attempts FROM codes WHERE email=?`, email).Scan(&hash, &salt, &expires, &attempts)
	if err != nil && err != sql.ErrNoRows {
		dbFailure(w)
		return false
	}
	expiry, parseErr := time.Parse(time.RFC3339Nano, expires)
	if err == sql.ErrNoRows || parseErr != nil || !now.Before(expiry) || attempts >= 5 {
		writeError(w, 400, "验证码无效或已过期")
		return false
	}
	if subtle.ConstantTimeCompare([]byte(hash), []byte(digest(email+salt+code))) != 1 {
		if _, err := a.db.Exec(`UPDATE codes SET attempts=attempts+1 WHERE email=?`, email); err != nil {
			dbFailure(w)
			return false
		}
		writeError(w, 400, "验证码无效或已过期")
		return false
	}
	return true
}

func (a *App) loginPassword(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Email    string `json:"email"`
		Password string `json:"password"`
	}
	if !decode(w, r, &in) {
		return
	}
	email, valid := normalizedEmail(in.Email)
	if !valid || !validPassword(in.Password) {
		writeError(w, 401, "邮箱或密码不正确")
		return
	}
	a.authMu.Lock()
	defer a.authMu.Unlock()
	now := a.now()
	// RemoteAddr is supplied by net/http; forwarding headers are intentionally not trusted.
	address, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		address = r.RemoteAddr
	}
	scopes := []struct {
		key   string
		limit int
	}{{"email:" + digest(email), 5}, {"source:" + digest(address), 30}}
	for _, scope := range scopes {
		var attempts int
		var reset string
		err := a.db.QueryRow(`SELECT attempts,reset_at FROM password_failures WHERE scope=?`, scope.key).Scan(&attempts, &reset)
		if err != nil && err != sql.ErrNoRows {
			dbFailure(w)
			return
		}
		if attempts >= scope.limit && reset > stamp(now) {
			writeError(w, 429, "登录尝试过多，请 15 分钟后重试，也可以用邮箱验证码重设密码")
			return
		}
	}
	var user User
	var encoded string
	err = a.db.QueryRow(`SELECT u.id,u.email,p.password_hash FROM users u JOIN password_credentials p ON p.user_id=u.id WHERE u.email=?`, email).Scan(&user.ID, &user.Email, &encoded)
	if err != nil && err != sql.ErrNoRows {
		dbFailure(w)
		return
	}
	matched := matchesPassword(in.Password, encoded)
	tx, err := a.db.BeginTx(r.Context(), nil)
	if err != nil {
		dbFailure(w)
		return
	}
	defer tx.Rollback()
	if _, err := tx.Exec(`DELETE FROM password_failures WHERE reset_at<=?`, stamp(now)); err != nil {
		dbFailure(w)
		return
	}
	if !matched {
		for _, scope := range scopes {
			if _, err := tx.Exec(`INSERT INTO password_failures(scope,attempts,reset_at) VALUES(?,1,?) ON CONFLICT(scope) DO UPDATE SET attempts=password_failures.attempts+1`, scope.key, stamp(now.Add(15*time.Minute))); err != nil {
				dbFailure(w)
				return
			}
		}
		if err := tx.Commit(); err != nil {
			dbFailure(w)
			return
		}
		writeError(w, 401, "邮箱或密码不正确")
		return
	}
	if _, err := tx.Exec(`DELETE FROM password_failures WHERE scope=?`, scopes[0].key); err != nil {
		dbFailure(w)
		return
	}
	token, err := createSession(tx, user.ID, now)
	if err != nil {
		dbFailure(w)
		return
	}
	if err = tx.Commit(); err != nil {
		dbFailure(w)
		return
	}
	user.PasswordSet = true
	writeJSON(w, 200, map[string]any{"token": token, "user": user})
}
