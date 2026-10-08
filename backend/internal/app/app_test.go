package app

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"image"
	"image/png"
	"mime/multipart"

	"net/http/httptest"
	"niannian/internal/config"
	"niannian/internal/mail"
	"regexp"
	"strings"
	"testing"
	"time"
)

type fakeMailer struct {
	messages []string
	fail     bool
}

func (m *fakeMailer) Send(_ context.Context, to, subject, body string, content ...mail.Content) error {
	if m.fail {
		return errors.New("test failure")
	}
	if len(content) != 1 {
		return errors.New("missing structured email template content")
	}
	if strings.Contains(subject, "验证码") {
		if len(content[0].Code) != 6 || !strings.Contains(body, content[0].Code) {
			return errors.New("verification code not passed to HTML template")
		}
	} else if content[0].Title == "" || !strings.Contains(body, content[0].Title) || !strings.Contains(body, content[0].Body) {
		return errors.New("reminder title/body not passed to HTML template")
	}
	m.messages = append(m.messages, to+"\n"+subject+"\n"+body)
	return nil
}
func setup(t *testing.T) (*App, *fakeMailer, *time.Time) {
	t.Helper()
	a, err := New(config.Config{DataDir: t.TempDir(), AllowedEmails: []string{"one@example.com", "two@example.com"}})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { a.Close() })
	now := time.Date(2026, 10, 3, 9, 10, 0, 0, time.FixedZone("CST", 8*3600))
	a.now = func() time.Time { return now }
	m := &fakeMailer{}
	a.mailer = m
	return a, m, &now
}
func request(t *testing.T, a *App, method, path, token string, body any) (int, map[string]any) {
	t.Helper()
	var b bytes.Buffer
	if body != nil {
		if err := json.NewEncoder(&b).Encode(body); err != nil {
			t.Fatal(err)
		}
	}
	r := httptest.NewRequest(method, path, &b)
	r.Header.Set("Content-Type", "application/json")
	if token != "" {
		r.Header.Set("Authorization", "Bearer "+token)
	}
	w := httptest.NewRecorder()
	a.Handler().ServeHTTP(w, r)
	var out map[string]any
	if err := json.Unmarshal(w.Body.Bytes(), &out); err != nil {
		t.Fatalf("invalid json %d: %s", w.Code, w.Body.String())
	}
	return w.Code, out
}
func login(t *testing.T, a *App, m *fakeMailer, email string) string {
	t.Helper()
	code, out := request(t, a, "POST", "/api/v1/auth/code", "", map[string]any{"email": email})
	if code != 200 {
		t.Fatalf("code %d %+v", code, out)
	}
	digits := regexp.MustCompile(`\b[0-9]{6}\b`).FindString(m.messages[len(m.messages)-1])
	if digits == "" {
		t.Fatal("no verification code")
	}
	code, out = request(t, a, "POST", "/api/v1/auth/verify", "", map[string]any{"email": email, "code": digits})
	if code != 200 {
		t.Fatalf("verify %d %+v", code, out)
	}
	return out["token"].(string)
}
func taskBody(mode string) map[string]any {
	return map[string]any{"title": "申报财务", "description": "完成即停止本期提醒", "rule": map[string]any{"kind": "monthly_window", "mode": mode, "timezone": "Asia/Shanghai", "times": []string{"09:00", "18:00"}, "month_start": 1, "month_end": 15}}
}
func createTask(t *testing.T, a *App, token, mode string) string {
	t.Helper()
	code, out := request(t, a, "POST", "/api/v1/tasks", token, taskBody(mode))
	if code != 201 {
		t.Fatalf("create %d %+v", code, out)
	}
	return out["id"].(string)
}
func TestAuthRateLimitsAndPersistence(t *testing.T) {
	a, m, _ := setup(t)
	token := login(t, a, m, "one@example.com")
	if len(m.messages) != 1 {
		t.Fatal("unexpected mail")
	}
	if c, _ := request(t, a, "POST", "/api/v1/auth/code", "", map[string]string{"email": "one@example.com"}); c != 429 {
		t.Fatalf("rate limit %d", c)
	}
	if c, _ := request(t, a, "GET", "/api/v1/me", "bogus", nil); c != 401 {
		t.Fatalf("bad token = %d", c)
	}
	cfg := a.cfg
	a.Close()
	again, err := New(cfg)
	if err != nil {
		t.Fatal(err)
	}
	defer again.Close()
	again.now = a.now
	if c, out := request(t, again, "GET", "/api/v1/me", token, nil); c != 200 || out["email"] != "one@example.com" {
		t.Fatalf("session persistence %d %+v", c, out)
	}
	request(t, again, "POST", "/api/v1/auth/logout", token, map[string]any{})
	if c, _ := request(t, again, "GET", "/api/v1/me", token, nil); c != 401 {
		t.Fatalf("logout %d", c)
	}
}
func TestTaskIsolationCompletionHistoryAndArchive(t *testing.T) {
	a, m, _ := setup(t)
	one := login(t, a, m, "one@example.com")
	two := login(t, a, m, "two@example.com")
	id := createTask(t, a, one, "until_done")
	if c, _ := request(t, a, "GET", "/api/v1/tasks/"+id, two, nil); c != 404 {
		t.Fatalf("isolation %d", c)
	}
	if c, _ := request(t, a, "POST", "/api/v1/tasks/"+id+"/complete", two, map[string]any{}); c != 404 {
		t.Fatalf("completion isolation %d", c)
	}
	_, first := request(t, a, "POST", "/api/v1/tasks/"+id+"/complete", one, map[string]any{"text": "已提交"})
	_, second := request(t, a, "POST", "/api/v1/tasks/"+id+"/complete", one, map[string]any{"text": "重试"})
	if first["id"] != second["id"] {
		t.Fatal("completion is not idempotent")
	}
	request(t, a, "DELETE", "/api/v1/tasks/"+id, one, nil)
	_, out := request(t, a, "GET", "/api/v1/tasks", one, nil)
	if len(out["tasks"].([]any)) != 0 {
		t.Fatal("archived task still listed")
	}
	_, out = request(t, a, "GET", "/api/v1/history", one, nil)
	if len(out["records"].([]any)) != 1 {
		t.Fatal("lost archive history")
	}
	_, out = request(t, a, "GET", "/api/v1/history", two, nil)
	if len(out["records"].([]any)) != 0 {
		t.Fatal("history leaked")
	}
}
func TestCompletionStopsCurrentCycleButEveryDayResumes(t *testing.T) {
	for _, mode := range []string{"until_done", "every_day"} {
		t.Run(mode, func(t *testing.T) {
			a, m, now := setup(t)
			token := login(t, a, m, "one@example.com")
			*now = now.Add(-20 * time.Minute)
			id := createTask(t, a, token, mode)
			m.messages = nil
			*now = now.Add(20 * time.Minute)
			if err := a.tick(context.Background()); err != nil {
				t.Fatal(err)
			}
			if len(m.messages) != 1 {
				t.Fatalf("first mail %d", len(m.messages))
			}
			if err := a.tick(context.Background()); err != nil {
				t.Fatal(err)
			}
			if len(m.messages) != 1 {
				t.Fatal("duplicate slot sent")
			}
			request(t, a, "POST", "/api/v1/tasks/"+id+"/complete", token, map[string]any{})
			*now = now.Add(10 * time.Hour)
			a.tick(context.Background())
			if len(m.messages) != 1 {
				t.Fatal("mail after completed")
			}
			*now = now.Add(24 * time.Hour)
			a.tick(context.Background())
			want := 1
			if mode == "every_day" {
				want = 2
			}
			if len(m.messages) != want {
				t.Fatalf("next day mails = %d want %d", len(m.messages), want)
			}
		})
	}
}
func TestMediaOwnershipAndValidation(t *testing.T) {
	a, m, _ := setup(t)
	one := login(t, a, m, "one@example.com")
	two := login(t, a, m, "two@example.com")
	upload := func(token string, data []byte) (int, map[string]any) {
		var b bytes.Buffer
		w := multipart.NewWriter(&b)
		f, _ := w.CreateFormFile("file", "test.png")
		f.Write(data)
		w.Close()
		r := httptest.NewRequest("POST", "/api/v1/media", &b)
		r.Header.Set("Content-Type", w.FormDataContentType())
		r.Header.Set("Authorization", "Bearer "+token)
		res := httptest.NewRecorder()
		a.Handler().ServeHTTP(res, r)
		var out map[string]any
		json.Unmarshal(res.Body.Bytes(), &out)
		return res.Code, out
	}
	if c, _ := upload(one, []byte("<svg onload=bad()>")); c != 400 {
		t.Fatalf("invalid media %d", c)
	}
	var img bytes.Buffer
	png.Encode(&img, image.NewRGBA(image.Rect(0, 0, 2, 2)))
	c, out := upload(one, img.Bytes())
	if c != 201 {
		t.Fatalf("upload %d %+v", c, out)
	}
	id := out["id"].(string)
	if c, _ := request(t, a, "GET", "/api/v1/media/"+id, two, nil); c != 404 {
		t.Fatalf("media isolation %d", c)
	}
	task := createTask(t, a, two, "until_done")
	if c, _ := request(t, a, "POST", "/api/v1/tasks/"+task+"/complete", two, map[string]any{"media_ids": []string{id}}); c != 400 {
		t.Fatalf("foreign attachment %d", c)
	}
	task = createTask(t, a, one, "until_done")
	if c, _ := request(t, a, "POST", "/api/v1/tasks/"+task+"/complete", one, map[string]any{"media_ids": []string{id}}); c != 200 {
		t.Fatalf("own attachment %d", c)
	}
	r := httptest.NewRequest("GET", "/api/v1/media/"+id, nil)
	r.Header.Set("Authorization", "Bearer "+one)
	res := httptest.NewRecorder()
	a.Handler().ServeHTTP(res, r)
	if res.Code != 200 || !bytes.Equal(res.Body.Bytes(), img.Bytes()) {
		t.Fatal("image bytes changed or unavailable")
	}
}
func TestOutboxRetrySurvivesRestartAndCatchesUpOnlyLatest(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	id := createTask(t, a, token, "until_done")
	_ = id
	m.messages = nil
	m.fail = true
	*now = now.Add(10 * time.Hour)
	a.tick(context.Background())
	_, out := request(t, a, "GET", "/api/v1/notifications", token, nil)
	if len(out["notifications"].([]any)) != 1 {
		t.Fatalf("catchup %+v", out)
	}
	cfg := a.cfg
	a.Close()
	b, err := New(cfg)
	if err != nil {
		t.Fatal(err)
	}
	defer b.Close()
	b.now = func() time.Time { return *now }
	m.fail = false
	b.mailer = m
	b.tick(context.Background())
	if len(m.messages) != 0 {
		t.Fatal("retry ignored backoff")
	}
	*now = now.Add(6 * time.Minute)
	b.tick(context.Background())
	b.tick(context.Background())
	if len(m.messages) != 1 {
		t.Fatalf("retry mails %d", len(m.messages))
	}
	_, out = request(t, b, "GET", "/api/v1/notifications", token, nil)
	raw := fmt.Sprint(out)
	if !strings.Contains(raw, "sent") {
		t.Fatal(raw)
	}
}

func TestCreateRejectsOnlyPastDates(t *testing.T) {
	a, m, _ := setup(t)
	token := login(t, a, m, "one@example.com")
	body := taskBody("until_done")
	rule := body["rule"].(map[string]any)
	rule["kind"] = "once"
	rule["start_date"] = "2026-10-02"
	rule["times"] = []string{"09:00"}
	if c, out := request(t, a, "POST", "/api/v1/tasks", token, body); c != 400 {
		t.Fatalf("past date should reject: %d %+v", c, out)
	}
}
func TestLegacyAllowlistDoesNotRevokeExistingSession(t *testing.T) {
	a, m, _ := setup(t)
	token := login(t, a, m, "one@example.com")
	a.cfg.AllowedEmails = []string{"two@example.com"}
	if c, _ := request(t, a, "GET", "/api/v1/me", token, nil); c != 200 {
		t.Fatalf("legacy allowlist blocked an authenticated user: %d", c)
	}
}

func TestAnyEmailCanRegisterLoginAgainAndReceiveReminder(t *testing.T) {
	a, m, now := setup(t) // Legacy config deliberately lists other addresses.
	*now = now.Add(-20 * time.Minute)
	token := login(t, a, m, "new-person@example.org")
	if c, out := request(t, a, "GET", "/api/v1/me", token, nil); c != 200 || out["email"] != "new-person@example.org" {
		t.Fatalf("new account not accessible: %d %+v", c, out)
	}
	createTask(t, a, token, "until_done")
	*now = now.Add(2 * time.Minute)
	login(t, a, m, "new-person@example.org")
	var users int
	if err := a.db.QueryRow(`SELECT COUNT(*) FROM users WHERE email=?`, "new-person@example.org").Scan(&users); err != nil || users != 1 {
		t.Fatalf("repeat login duplicated account: count=%d err=%v", users, err)
	}
	m.messages = nil
	*now = now.Add(18 * time.Minute)
	if err := a.tick(context.Background()); err != nil {
		t.Fatal(err)
	}
	if len(m.messages) != 1 || !strings.HasPrefix(m.messages[0], "new-person@example.org\n") {
		t.Fatal("registered email did not receive its scheduled reminder")
	}
	if c, _ := request(t, a, "POST", "/api/v1/auth/code", "", map[string]string{"email": "not an email"}); c != 400 {
		t.Fatalf("invalid email accepted: %d", c)
	}
}
func TestSnoozeSendsExactlyOnceAndSupersedesEarlierSlot(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	*now = now.Add(-20 * time.Minute)
	id := createTask(t, a, token, "until_done")
	m.messages = nil
	if c, out := request(t, a, "POST", "/api/v1/tasks/"+id+"/snooze", token, map[string]int{"minutes": 60}); c != 200 {
		t.Fatalf("snooze %d %+v", c, out)
	}
	*now = now.Add(20 * time.Minute)
	a.tick(context.Background())
	if len(m.messages) != 0 {
		t.Fatal("sent before snooze")
	}
	*now = now.Add(40 * time.Minute)
	a.tick(context.Background())
	a.tick(context.Background())
	if len(m.messages) != 1 {
		t.Fatalf("snooze mails %d", len(m.messages))
	}
	*now = now.Add(10 * time.Minute)
	a.tick(context.Background())
	if len(m.messages) != 1 {
		t.Fatal("normal earlier slot resent after snooze")
	}
	*now = now.Add(9 * time.Hour)
	a.tick(context.Background())
	if len(m.messages) != 2 {
		t.Fatal("snooze suppressed later regular reminder")
	}
}
func TestNoCatchupBeforeCreationAndNoCatchupFromPreviousDay(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	createTask(t, a, token, "until_done")
	m.messages = nil
	a.tick(context.Background())
	if len(m.messages) != 0 {
		t.Fatal("sent slot before creation")
	}
	*now = now.Add(20 * time.Hour)
	a.tick(context.Background())
	if len(m.messages) != 0 {
		t.Fatal("caught up previous day")
	}
}
func TestNotifyOnceAndCapArePerCycle(t *testing.T) {
	for _, mode := range []string{"notify_once", "until_done"} {
		t.Run(mode, func(t *testing.T) {
			a, m, now := setup(t)
			token := login(t, a, m, "one@example.com")
			*now = now.Add(-20 * time.Minute)
			body := taskBody(mode)
			body["rule"].(map[string]any)["max_reminders"] = 1
			if c, out := request(t, a, "POST", "/api/v1/tasks", token, body); c != 201 {
				t.Fatalf("%d %+v", c, out)
			}
			m.messages = nil
			*now = now.Add(time.Hour)
			a.tick(context.Background())
			*now = now.Add(10 * time.Hour)
			a.tick(context.Background())
			*now = now.Add(24 * time.Hour)
			a.tick(context.Background())
			if len(m.messages) != 1 {
				t.Fatalf("cap=%d", len(m.messages))
			}
			*now = time.Date(2026, 11, 3, 10, 0, 0, 0, now.Location())
			a.tick(context.Background())
			if len(m.messages) != 2 {
				t.Fatal("cap did not reset next month")
			}
		})
	}
}
func TestOngoingCompletionNeverRestartsOnAnotherDay(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	*now = now.Add(-20 * time.Minute)
	body := taskBody("until_done")
	rule := body["rule"].(map[string]any)
	rule["kind"] = "ongoing"
	rule["start_date"] = "2026-10-03"
	c, out := request(t, a, "POST", "/api/v1/tasks", token, body)
	if c != 201 {
		t.Fatalf("ongoing create %d %+v", c, out)
	}
	id := out["id"].(string)
	m.messages = nil
	*now = now.Add(time.Hour)
	a.tick(context.Background())
	request(t, a, "POST", "/api/v1/tasks/"+id+"/complete", token, map[string]any{})
	*now = now.Add(24 * time.Hour)
	a.tick(context.Background())
	*now = now.AddDate(0, 1, 0)
	a.tick(context.Background())
	if len(m.messages) != 1 {
		t.Fatalf("ongoing restarted: %d mails", len(m.messages))
	}
	if c, out = request(t, a, "GET", "/api/v1/tasks/"+id, token, nil); c != 200 || out["status"] != "done" || out["next_reminder"] != "" {
		t.Fatalf("ongoing view %d %+v", c, out)
	}
}
func TestSingleProcessLock(t *testing.T) {
	a, _, _ := setup(t)
	b, err := New(a.cfg)
	if err == nil {
		b.Close()
		t.Fatal("second process accepted same data directory")
	}
}
func TestOTPBruteForceExpiresCode(t *testing.T) {
	a, m, _ := setup(t)
	email := "one@example.com"
	request(t, a, "POST", "/api/v1/auth/code", "", map[string]string{"email": email})
	correct := regexp.MustCompile(`\b[0-9]{6}\b`).FindString(m.messages[0])
	wrong := "000000"
	if correct == wrong {
		wrong = "999999"
	}
	for i := 0; i < 5; i++ {
		if c, _ := request(t, a, "POST", "/api/v1/auth/verify", "", map[string]string{"email": email, "code": wrong}); c != 400 {
			t.Fatalf("wrong code %d", c)
		}
	}
	if c, _ := request(t, a, "POST", "/api/v1/auth/verify", "", map[string]string{"email": email, "code": correct}); c != 400 {
		t.Fatal("accepted code after brute force limit")
	}
}

type mailFunc func(context.Context, string, string, string) error

func (f mailFunc) Send(ctx context.Context, to, subject, body string, _ ...mail.Content) error {
	return f(ctx, to, subject, body)
}
func TestSlowSMTPDoesNotBlockUnrelatedCompletion(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	*now = now.Add(-20 * time.Minute)
	createTask(t, a, token, "until_done")
	body := taskBody("until_done")
	body["rule"].(map[string]any)["times"] = []string{"18:00"}
	_, created := request(t, a, "POST", "/api/v1/tasks", token, body)
	id := created["id"].(string)
	*now = now.Add(20 * time.Minute)
	started, release, finished := make(chan struct{}), make(chan struct{}), make(chan struct{})
	a.mailer = mailFunc(func(context.Context, string, string, string) error { close(started); <-release; return nil })
	go func() { a.tick(context.Background()); close(finished) }()
	<-started
	completed := make(chan int, 1)
	go func() {
		c, _ := request(t, a, "POST", "/api/v1/tasks/"+id+"/complete", token, map[string]any{})
		completed <- c
	}()
	select {
	case c := <-completed:
		close(release)
		<-finished
		if c != 200 {
			t.Fatalf("completion status %d", c)
		}
	case <-time.After(300 * time.Millisecond):
		close(release)
		<-finished
		<-completed
		t.Fatal("slow SMTP blocked unrelated completion")
	}
}
func TestSuccessfulDeliveryPersistsWhenShutdownCancelsContext(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	*now = now.Add(-20 * time.Minute)
	createTask(t, a, token, "until_done")
	*now = now.Add(20 * time.Minute)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	a.mailer = mailFunc(func(context.Context, string, string, string) error { cancel(); return nil })
	_ = a.tick(ctx)
	_, out := request(t, a, "GET", "/api/v1/notifications", token, nil)
	items := out["notifications"].([]any)
	if len(items) != 1 || items[0].(map[string]any)["status"] != "sent" {
		t.Fatalf("lost accepted delivery %+v", out)
	}
}
func TestQueueRechecksTimeBetweenSlowDeliveries(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	*now = time.Date(2026, 10, 3, 23, 57, 0, 0, now.Location())
	body := taskBody("until_done")
	body["rule"].(map[string]any)["times"] = []string{"23:58"}
	request(t, a, "POST", "/api/v1/tasks", token, body)
	request(t, a, "POST", "/api/v1/tasks", token, body)
	*now = now.Add(2 * time.Minute)
	sent := 0
	a.mailer = mailFunc(func(context.Context, string, string, string) error {
		sent++
		*now = now.Add(2 * time.Minute)
		return nil
	})
	a.tick(context.Background())
	if sent != 1 {
		t.Fatalf("sent %d including expired previous day", sent)
	}
}
func TestRuleEditCanReplaceFailedSlotButNeverResendSuccessfulSlot(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	*now = now.Add(-20 * time.Minute)
	id := createTask(t, a, token, "until_done")
	*now = now.Add(20 * time.Minute)
	m.messages = nil
	m.fail = true
	a.tick(context.Background())
	body := taskBody("until_done")
	body["rule"].(map[string]any)["max_reminders"] = 2
	request(t, a, "PUT", "/api/v1/tasks/"+id, token, body)
	m.fail = false
	a.tick(context.Background())
	if len(m.messages) != 1 {
		t.Fatal("edited rule permanently lost failed slot")
	}
	body["rule"].(map[string]any)["max_reminders"] = 3
	request(t, a, "PUT", "/api/v1/tasks/"+id, token, body)
	a.tick(context.Background())
	if len(m.messages) != 1 {
		t.Fatal("successful slot resent after rule edit")
	}
}
func TestTitleNormalizesLineBreaksForMailSubject(t *testing.T) {
	a, m, _ := setup(t)
	token := login(t, a, m, "one@example.com")
	body := taskBody("until_done")
	body["title"] = "申报\n财务\r\n信息"
	c, out := request(t, a, "POST", "/api/v1/tasks", token, body)
	if c != 201 || out["title"] != "申报 财务 信息" {
		t.Fatalf("unsafe mail subject %d %+v", c, out)
	}
}
