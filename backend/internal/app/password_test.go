package app

import (
	"bytes"
	"encoding/json"
	"net/http/httptest"
	"regexp"
	"strings"
	"testing"
	"time"
)

func passwordRequest(t *testing.T, a *App, path string, body map[string]string) (int, map[string]any) {
	t.Helper()
	b, err := json.Marshal(body)
	if err != nil {
		t.Fatal(err)
	}
	r := httptest.NewRequest("POST", "/api/v1/auth/"+path, bytes.NewReader(b))
	w := httptest.NewRecorder()
	a.Handler().ServeHTTP(w, r)
	out := map[string]any{}
	_ = json.Unmarshal(w.Body.Bytes(), &out)
	return w.Code, out
}

func freshPasswordCode(t *testing.T, a *App, m *fakeMailer, now *time.Time, email string) string {
	t.Helper()
	*now = now.Add(2 * time.Minute)
	if status, out := passwordRequest(t, a, "code", map[string]string{"email": email}); status != 200 {
		t.Fatalf("request code %d %+v", status, out)
	}
	return regexp.MustCompile(`\b[0-9]{6}\b`).FindString(m.messages[len(m.messages)-1])
}

func registerPassword(t *testing.T, a *App, m *fakeMailer, now *time.Time, email, password string) map[string]any {
	t.Helper()
	code := freshPasswordCode(t, a, m, now, email)
	status, out := passwordRequest(t, a, "register", map[string]string{"email": email, "password": password, "code": code})
	if status != 200 {
		t.Fatalf("register %d %+v", status, out)
	}
	u, ok := out["user"].(map[string]any)
	if !ok || u["password_set"] != true || out["token"] == nil {
		t.Fatalf("incomplete register response %+v", out)
	}
	return out
}

func TestPasswordRegisterRequiresCodeAndStoresSaltedKDF(t *testing.T) {
	a, m, now := setup(t)
	if status, _ := passwordRequest(t, a, "register", map[string]string{"email": "one@example.com", "password": "correct password", "code": "123456"}); status != 400 {
		t.Fatalf("registration without issued code = %d, want 400", status)
	}
	one := registerPassword(t, a, m, now, "one@example.com", "correct password")
	two := registerPassword(t, a, m, now, "two@example.com", "correct password")
	var hash1, hash2 string
	if err := a.db.QueryRow(`SELECT password_hash FROM password_credentials WHERE user_id=?`, one["user"].(map[string]any)["id"]).Scan(&hash1); err != nil {
		t.Fatal(err)
	}
	if err := a.db.QueryRow(`SELECT password_hash FROM password_credentials WHERE user_id=?`, two["user"].(map[string]any)["id"]).Scan(&hash2); err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(hash1, "pbkdf2-sha256$v1$600000$") || hash1 == hash2 || strings.Contains(hash1, "correct password") {
		t.Fatal("passwords must use versioned independently salted KDF")
	}
	if status, out := request(t, a, "GET", "/api/v1/me", one["token"].(string), nil); status != 200 || out["password_set"] != true {
		t.Fatalf("me %d %+v", status, out)
	}
}

func TestPasswordLoginNormalizesOnlyEmailAndUsesGenericFailure(t *testing.T) {
	a, m, now := setup(t)
	registerPassword(t, a, m, now, "one@example.com", "  correct password  ")
	if status, out := passwordRequest(t, a, "login", map[string]string{"email": " ONE@example.com ", "password": "  correct password  "}); status != 200 || out["user"].(map[string]any)["password_set"] != true {
		t.Fatalf("valid password %d %+v", status, out)
	}
	status, bad := passwordRequest(t, a, "login", map[string]string{"email": "one@example.com", "password": "correct password"})
	missingStatus, missing := passwordRequest(t, a, "login", map[string]string{"email": "missing@example.com", "password": "correct password"})
	if status != 401 || missingStatus != 401 || bad["error"] != missing["error"] {
		t.Fatalf("credential failures differ %d %+v / %d %+v", status, bad, missingStatus, missing)
	}
}

func TestPasswordRegisterCannotReplaceExistingPasswordOrConsumeCode(t *testing.T) {
	a, m, now := setup(t)
	registerPassword(t, a, m, now, "one@example.com", "first password")
	code := freshPasswordCode(t, a, m, now, "one@example.com")
	body := map[string]string{"email": "one@example.com", "code": code, "password": "second password"}
	if status, _ := passwordRequest(t, a, "register", body); status != 409 {
		t.Fatalf("duplicate registration = %d", status)
	}
	if status, _ := passwordRequest(t, a, "login", map[string]string{"email": "one@example.com", "password": "first password"}); status != 200 {
		t.Fatalf("original password lost = %d", status)
	}
	if status, _ := passwordRequest(t, a, "password/reset", body); status != 200 {
		t.Fatalf("rejected registration consumed code = %d", status)
	}
}

func TestPasswordLegacyUpgradeKeepsIdentityTasksAndSessions(t *testing.T) {
	a, m, now := setup(t)
	oldToken := login(t, a, m, "one@example.com")
	id := createTask(t, a, oldToken, "until_done")
	_, before := request(t, a, "GET", "/api/v1/me", oldToken, nil)
	if before["password_set"] != false {
		t.Fatalf("legacy password state %+v", before)
	}
	out := registerPassword(t, a, m, now, "one@example.com", "new password")
	if out["user"].(map[string]any)["id"] != before["id"] {
		t.Fatal("upgrade replaced account")
	}
	for _, token := range []string{oldToken, out["token"].(string)} {
		if status, _ := request(t, a, "GET", "/api/v1/tasks/"+id, token, nil); status != 200 {
			t.Fatalf("lost task or session %d", status)
		}
	}
	code := freshPasswordCode(t, a, m, now, "one@example.com")
	if status, out := passwordRequest(t, a, "verify", map[string]string{"email": "one@example.com", "code": code}); status != 200 || out["user"].(map[string]any)["password_set"] != true {
		t.Fatalf("legacy response %d %+v", status, out)
	}
}

func TestPasswordLoginAndFailureLimitSurviveRestart(t *testing.T) {
	a, m, now := setup(t)
	registerPassword(t, a, m, now, "one@example.com", "correct password")
	for i := 0; i < 5; i++ {
		if status, _ := passwordRequest(t, a, "login", map[string]string{"email": "one@example.com", "password": "wrong password"}); status != 401 {
			t.Fatalf("attempt %d status %d", i, status)
		}
	}
	cfg := a.cfg
	a.Close()
	b, err := New(cfg)
	if err != nil {
		t.Fatal(err)
	}
	defer b.Close()
	b.now = func() time.Time { return *now }
	if status, _ := passwordRequest(t, b, "login", map[string]string{"email": "one@example.com", "password": "correct password"}); status != 429 {
		t.Fatalf("lost durable lock = %d", status)
	}
	*now = now.Add(16 * time.Minute)
	if status, out := passwordRequest(t, b, "login", map[string]string{"email": "one@example.com", "password": "correct password"}); status != 200 {
		t.Fatalf("password lost or lock never clears %d %+v", status, out)
	}
}

func TestPasswordResetRevokesOnlyOwnSessionsAndKeepsData(t *testing.T) {
	a, m, now := setup(t)
	one := registerPassword(t, a, m, now, "one@example.com", "first password")
	two := registerPassword(t, a, m, now, "two@example.com", "other password")
	old := one["token"].(string)
	id := createTask(t, a, old, "until_done")
	_, second := passwordRequest(t, a, "login", map[string]string{"email": "one@example.com", "password": "first password"})
	code := freshPasswordCode(t, a, m, now, "one@example.com")
	status, out := passwordRequest(t, a, "password/reset", map[string]string{"email": "one@example.com", "code": code, "password": "replacement password"})
	if status != 200 {
		t.Fatalf("reset %d %+v", status, out)
	}
	for _, token := range []string{old, second["token"].(string)} {
		if status, _ := request(t, a, "GET", "/api/v1/me", token, nil); status != 401 {
			t.Fatalf("old session survived reset = %d", status)
		}
	}
	if status, _ := request(t, a, "GET", "/api/v1/me", two["token"].(string), nil); status != 200 {
		t.Fatal("reset affected another account")
	}
	if status, _ := request(t, a, "GET", "/api/v1/tasks/"+id, out["token"].(string), nil); status != 200 {
		t.Fatal("reset lost task")
	}
	if status, _ := passwordRequest(t, a, "login", map[string]string{"email": "one@example.com", "password": "first password"}); status != 401 {
		t.Fatal("old password accepted")
	}
	if status, _ := passwordRequest(t, a, "login", map[string]string{"email": "one@example.com", "password": "replacement password"}); status != 200 {
		t.Fatal("new password rejected")
	}
	if status, _ := passwordRequest(t, a, "password/reset", map[string]string{"email": "one@example.com", "code": code, "password": "another password"}); status != 400 {
		t.Fatal("reset code reusable")
	}
}

func TestPasswordValidationAndOTPAttemptLimit(t *testing.T) {
	a, m, now := setup(t)
	code := freshPasswordCode(t, a, m, now, "one@example.com")
	for _, password := range []string{"1234567", "中文密码不足八", strings.Repeat("界", 43)} {
		if status, _ := passwordRequest(t, a, "register", map[string]string{"email": "one@example.com", "code": code, "password": password}); status != 400 {
			t.Fatalf("invalid password length accepted = %d", status)
		}
	}
	wrong := "000000"
	if code == wrong {
		wrong = "999999"
	}
	for i := 0; i < 5; i++ {
		if status, _ := passwordRequest(t, a, "register", map[string]string{"email": "one@example.com", "code": wrong, "password": "valid password"}); status != 400 {
			t.Fatalf("wrong OTP = %d", status)
		}
	}
	if status, _ := passwordRequest(t, a, "register", map[string]string{"email": "one@example.com", "code": code, "password": "valid password"}); status != 400 {
		t.Fatal("OTP limit bypassed by registration")
	}
}

func TestPasswordCommitFailureRollsBackCodePasswordAndSessions(t *testing.T) {
	for _, path := range []string{"register", "password/reset"} {
		t.Run(path, func(t *testing.T) {
			a, m, now := setup(t)
			var token string
			if path == "password/reset" {
				token = registerPassword(t, a, m, now, "one@example.com", "first password")["token"].(string)
			}
			code := freshPasswordCode(t, a, m, now, "one@example.com")
			if _, err := a.db.Exec(`CREATE TABLE password_commit_guard(user_id TEXT REFERENCES users(id) DEFERRABLE INITIALLY DEFERRED); CREATE TRIGGER fail_password_commit AFTER INSERT ON sessions BEGIN INSERT INTO password_commit_guard(user_id) VALUES('missing-user'); END`); err != nil {
				t.Fatal(err)
			}
			body := map[string]string{"email": "one@example.com", "code": code, "password": "replacement password"}
			if status, out := passwordRequest(t, a, path, body); status != 500 || out["token"] != nil {
				t.Fatalf("failed commit emitted session %d %+v", status, out)
			}
			if token != "" {
				if status, _ := request(t, a, "GET", "/api/v1/me", token, nil); status != 200 {
					t.Fatal("failed reset revoked old session")
				}
			}
			if _, err := a.db.Exec(`DROP TRIGGER fail_password_commit`); err != nil {
				t.Fatal(err)
			}
			if token != "" {
				if status, _ := passwordRequest(t, a, "login", map[string]string{"email": "one@example.com", "password": "first password"}); status != 200 {
					t.Fatal("failed reset changed password")
				}
			}
			if status, out := passwordRequest(t, a, path, body); status != 200 {
				t.Fatalf("failed commit consumed code or created user %d %+v", status, out)
			}
		})
	}
}

func TestPasswordResetRejectsUnverifiedAndUnknownAccounts(t *testing.T) {
	a, m, now := setup(t)
	registered := registerPassword(t, a, m, now, "one@example.com", "first password")
	code := freshPasswordCode(t, a, m, now, "one@example.com")
	wrong := "000000"
	if code == wrong {
		wrong = "999999"
	}
	if status, _ := passwordRequest(t, a, "password/reset", map[string]string{"email": "one@example.com", "code": wrong, "password": "new password"}); status != 400 {
		t.Fatalf("reset with incorrect code = %d", status)
	}
	if status, _ := request(t, a, "GET", "/api/v1/me", registered["token"].(string), nil); status != 200 {
		t.Fatal("unverified reset revoked session")
	}
	unknownCode := freshPasswordCode(t, a, m, now, "missing@example.com")
	if status, _ := passwordRequest(t, a, "password/reset", map[string]string{"email": "missing@example.com", "code": unknownCode, "password": "new password"}); status != 400 {
		t.Fatalf("reset created account = %d", status)
	}
	var count int
	if err := a.db.QueryRow(`SELECT COUNT(*) FROM users WHERE email='missing@example.com'`).Scan(&count); err != nil || count != 0 {
		t.Fatalf("unknown account created: count=%d err=%v", count, err)
	}
	*now = now.Add(11 * time.Minute)
	if status, _ := passwordRequest(t, a, "password/reset", map[string]string{"email": "one@example.com", "code": code, "password": "new password"}); status != 400 {
		t.Fatalf("expired reset code accepted = %d", status)
	}
}

func TestPasswordSourceLimitCannotBeBypassedWithForwardedHeader(t *testing.T) {
	a, _, _ := setup(t)
	for i := 0; i < 30; i++ {
		email := strings.Repeat("a", i+1) + "@example.com"
		if status, _ := passwordRequest(t, a, "login", map[string]string{"email": email, "password": "wrong password"}); status != 401 {
			t.Fatalf("source attempt %d = %d", i, status)
		}
	}
	r := httptest.NewRequest("POST", "/api/v1/auth/login", strings.NewReader(`{"email":"next@example.com","password":"wrong password"}`))
	r.Header.Set("X-Forwarded-For", "203.0.113.10")
	w := httptest.NewRecorder()
	a.Handler().ServeHTTP(w, r)
	if w.Code != 429 {
		t.Fatalf("forwarded header bypassed source limit = %d", w.Code)
	}
}
