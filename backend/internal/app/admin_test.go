package app

import (
	"encoding/json"
	"net/http/httptest"
	"strings"
	"testing"
)

const testAdminKey = "test-admin-key-with-enough-entropy-123456"

func adminSetup(t *testing.T) *App {
	a, _, _ := setup(t)
	if err := json.Unmarshal([]byte(`{"admin_key":"`+testAdminKey+`"}`), &a.cfg); err != nil {
		t.Fatal(err)
	}
	return a
}
func TestAdminAuthAndLocalFileCORS(t *testing.T) {
	a := adminSetup(t)
	for _, path := range []string{"/admin/users", "/admin/tasks", "/admin/logs", "/admin/releases", "/admin/unknown"} {
		r := httptest.NewRequest("GET", path, nil)
		w := httptest.NewRecorder()
		a.Handler().ServeHTTP(w, r)
		if w.Code != 401 {
			t.Fatalf("%s without key = %d", path, w.Code)
		}
	}
	for _, key := range []string{"", "wrong", testAdminKey} {
		r := httptest.NewRequest("GET", "/admin/users", nil)
		r.Header.Set("Origin", "null")
		r.Header.Set("Authorization", "Bearer "+key)
		w := httptest.NewRecorder()
		a.Handler().ServeHTTP(w, r)
		want := 401
		if key == testAdminKey {
			want = 200
		}
		if w.Code != want || w.Header().Get("Access-Control-Allow-Origin") != "null" {
			t.Fatalf("auth/cors %d %s", w.Code, w.Body.String())
		}
	}
	r := httptest.NewRequest("OPTIONS", "/admin/users", nil)
	r.Header.Set("Origin", "null")
	r.Header.Set("Access-Control-Request-Method", "POST")
	r.Header.Set("Access-Control-Request-Headers", "authorization,content-type")
	w := httptest.NewRecorder()
	a.Handler().ServeHTTP(w, r)
	if w.Code != 204 {
		t.Fatal(w.Code)
	}
	a.cfg = adminSetup(t).cfg
	_ = json.Unmarshal([]byte(`{"admin_key":""}`), &a.cfg)
	w = httptest.NewRecorder()
	a.Handler().ServeHTTP(w, httptest.NewRequest("GET", "/admin/users", nil))
	if w.Code != 503 {
		t.Fatal("empty key must disable admin", w.Code)
	}
}

func TestAdminUserTaskCRUDAndIsolation(t *testing.T) {
	a := adminSetup(t)
	code, out := request(t, a, "POST", "/admin/users", testAdminKey, map[string]any{"email": "admin-created@example.com", "password": "valid-password-123"})
	if code != 201 {
		t.Fatalf("create user: %d %v", code, out)
	}
	id := out["id"].(string)
	code, out = request(t, a, "POST", "/api/v1/auth/login", "", map[string]any{"email": "admin-created@example.com", "password": "valid-password-123"})
	if code != 200 {
		t.Fatal(code, out)
	}
	token := out["token"].(string)
	code, out = request(t, a, "POST", "/admin/tasks?user_id="+id, testAdminKey, taskBody("until_done"))
	if code != 201 {
		t.Fatal(code, out)
	}
	taskID := out["id"].(string)
	code, out = request(t, a, "GET", "/admin/tasks?user_id="+id, testAdminKey, nil)
	if code != 200 || len(out["tasks"].([]any)) != 1 {
		t.Fatal(code, out)
	}
	body := taskBody("until_done")
	body["title"] = "管理员修改"
	code, out = request(t, a, "PUT", "/admin/tasks/"+taskID, testAdminKey, body)
	if code != 200 {
		t.Fatal(code, out)
	}
	code, out = request(t, a, "GET", "/api/v1/tasks/"+taskID, token, nil)
	if code != 200 || out["title"] != "管理员修改" {
		t.Fatal(code, out)
	}
	code, _ = request(t, a, "GET", "/api/v1/tasks", testAdminKey, nil)
	if code != 401 {
		t.Fatal("admin key must not act as a user")
	}
	code, out = request(t, a, "DELETE", "/admin/tasks/"+taskID, testAdminKey, nil)
	if code != 200 {
		t.Fatal(code, out)
	}
	var archived int
	a.db.QueryRow(`SELECT archived FROM tasks WHERE id=?`, taskID).Scan(&archived)
	if archived != 1 {
		t.Fatal("deletion should archive and preserve history")
	}
	code, out = request(t, a, "PUT", "/admin/users/"+id, testAdminKey, map[string]any{"email": "changed@example.com", "password": "new-password-123"})
	if code != 200 {
		t.Fatal(code, out)
	}
	code, _ = request(t, a, "GET", "/api/v1/me", token, nil)
	if code != 401 {
		t.Fatal("credentials change must revoke sessions")
	}
	code, out = request(t, a, "DELETE", "/admin/users/"+id, testAdminKey, nil)
	if code != 200 {
		t.Fatal(code, out)
	}
	code, out = request(t, a, "GET", "/admin/users", testAdminKey, nil)
	if code != 200 || len(out["users"].([]any)) != 0 {
		t.Fatal(code, out)
	}
	code, out = request(t, a, "GET", "/admin/logs", testAdminKey, nil)
	b, _ := json.Marshal(out)
	if code != 200 || strings.Contains(string(b), "valid-password") || strings.Contains(string(b), testAdminKey) {
		t.Fatal(code, out)
	}
}

func TestPublicUpdateWithoutLogin(t *testing.T) {
	a := adminSetup(t)
	w := httptest.NewRecorder()
	a.Handler().ServeHTTP(w, httptest.NewRequest("GET", "/api/v1/updates/latest?version_code=1", nil))
	if w.Code != 200 || !strings.Contains(w.Body.String(), `"available":false`) {
		t.Fatalf("%d %s", w.Code, w.Body.String())
	}
}
