package app

import (
	"context"
	"testing"
	"time"
)

func TestNotificationRecordsLinkToOwnedTask(t *testing.T) {
	a, m, now := setup(t)
	one := login(t, a, m, "one@example.com")
	two := login(t, a, m, "two@example.com")
	*now = now.Add(-20 * time.Minute)
	oneID := createTask(t, a, one, "until_done")
	twoID := createTask(t, a, two, "until_done")
	if c, out := request(t, a, "GET", "/api/v1/notifications", one, nil); c != 200 || len(out["notifications"].([]any)) != 0 {
		t.Fatalf("future schedule must not fabricate a notification: %d %+v", c, out)
	}
	*now = now.Add(20 * time.Minute)
	if err := a.tick(context.Background()); err != nil {
		t.Fatal(err)
	}
	for _, owner := range []struct{ token, taskID string }{{one, oneID}, {two, twoID}} {
		c, out := request(t, a, "GET", "/api/v1/notifications", owner.token, nil)
		if c != 200 {
			t.Fatalf("notifications: %d %+v", c, out)
		}
		records := out["notifications"].([]any)
		if len(records) != 1 {
			t.Fatalf("notification owner isolation: %+v", out)
		}
		record := records[0].(map[string]any)
		if record["task_id"] != owner.taskID || record["task_title"] != "申报财务" || record["status"] != "sent" {
			t.Fatalf("notification cannot link to its original task: %+v", record)
		}
		if c, detail := request(t, a, "GET", "/api/v1/tasks/"+record["task_id"].(string), owner.token, nil); c != 200 || detail["id"] != owner.taskID {
			t.Fatalf("notification detail: %d %+v", c, detail)
		}
	}
}

func TestArchivedTaskCanBeOpenedFromCompletionHistory(t *testing.T) {
	a, m, _ := setup(t)
	token := login(t, a, m, "one@example.com")
	id := createTask(t, a, token, "until_done")
	if c, out := request(t, a, "POST", "/api/v1/tasks/"+id+"/complete", token, map[string]any{"text": "已提交"}); c != 200 {
		t.Fatalf("complete: %d %+v", c, out)
	}
	if c, out := request(t, a, "DELETE", "/api/v1/tasks/"+id, token, nil); c != 200 {
		t.Fatalf("archive: %d %+v", c, out)
	}
	c, out := request(t, a, "GET", "/api/v1/history", token, nil)
	if c != 200 || len(out["records"].([]any)) != 1 {
		t.Fatalf("archived completion history: %d %+v", c, out)
	}
	record := out["records"].([]any)[0].(map[string]any)
	if record["task_id"] != id || record["text"] != "已提交" {
		t.Fatalf("completion lost its original task link or text: %+v", record)
	}
	c, detail := request(t, a, "GET", "/api/v1/tasks/"+record["task_id"].(string), token, nil)
	if c != 200 || detail["id"] != id || detail["title"] != "申报财务" || detail["archived"] != true || detail["status"] != "expired" || detail["next_reminder"] != "" {
		t.Fatalf("archived task detail: %d %+v", c, detail)
	}
	if c, out = request(t, a, "GET", "/api/v1/tasks", token, nil); c != 200 || len(out["tasks"].([]any)) != 0 {
		t.Fatalf("archived task returned to active list: %d %+v", c, out)
	}
}

func TestArchivedTaskStillRejectsOtherOwnersAndMutations(t *testing.T) {
	a, m, _ := setup(t)
	one := login(t, a, m, "one@example.com")
	two := login(t, a, m, "two@example.com")
	id := createTask(t, a, one, "until_done")
	if c, out := request(t, a, "DELETE", "/api/v1/tasks/"+id, one, nil); c != 200 {
		t.Fatalf("archive: %d %+v", c, out)
	}
	for _, tc := range []struct {
		name, method, suffix, token string
		body                        any
		status                      int
	}{
		{"other owner", "GET", "", two, nil, 404},
		{"unauthenticated", "GET", "", "", nil, 401},
		{"update", "PUT", "", one, taskBody("until_done"), 404},
		{"archive again", "DELETE", "", one, nil, 404},
		{"complete", "POST", "/complete", one, map[string]any{}, 404},
		{"snooze", "POST", "/snooze", one, map[string]int{"minutes": 10}, 404},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if c, out := request(t, a, tc.method, "/api/v1/tasks/"+id+tc.suffix, tc.token, tc.body); c != tc.status {
				t.Fatalf("archived task access: %d %+v", c, out)
			}
		})
	}
}
