package app

import (
	"fmt"
	"niannian/internal/schedule"
	"testing"
)

func assertCompletionGrowth(t *testing.T, out map[string]any, from, to int, fromStage, toStage string, added bool) {
	t.Helper()
	growth, ok := out["growth"].(map[string]any)
	if !ok {
		t.Fatalf("missing completion growth: %+v", out)
	}
	if growth["from_count"] != float64(from) || growth["to_count"] != float64(to) || growth["from_stage"] != fromStage || growth["to_stage"] != toStage || growth["added"] != added {
		t.Fatalf("unexpected completion growth: %+v", growth)
	}
}

func TestCompletionGrowthFirstAndDuplicateUseRealCounts(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	id := createTask(t, a, token, "until_done")
	path := "/api/v1/tasks/" + id + "/complete"
	status, first := request(t, a, "POST", path, token, map[string]any{"text": "已完成"})
	if status != 200 || first["task_id"] != id || first["text"] != "已完成" || first["id"] == "" || first["created_at"] != stamp(*now) {
		t.Fatalf("first completion: %d %+v", status, first)
	}
	assertCompletionGrowth(t, first, 0, 0, "seed", "seed", false)
	status, duplicate := request(t, a, "POST", path, token, map[string]any{"text": "重试"})
	if status != 200 || duplicate["id"] != first["id"] || duplicate["text"] != first["text"] {
		t.Fatalf("duplicate completion: %d %+v", status, duplicate)
	}
	assertCompletionGrowth(t, duplicate, 0, 0, "seed", "seed", false)
	// An old response's count is not the retry count: use all current stored rows.
	addGardenCompletion(t, a, id, stamp(*now))
	status, duplicate = request(t, a, "POST", path, token, map[string]any{})
	if status != 200 || duplicate["id"] != first["id"] {
		t.Fatalf("later duplicate completion: %d %+v", status, duplicate)
	}
	assertCompletionGrowth(t, duplicate, 1, 1, "sprout", "sprout", false)
	for _, route := range []string{"/api/v1/history", "/api/v1/export", "/api/v1/garden"} {
		status, out := request(t, a, "GET", route, token, nil)
		if status != 200 {
			t.Fatalf("history route %s: %d %+v", route, status, out)
		}
		key := "records"
		if route == "/api/v1/garden" {
			key = "recent_completions"
		}
		records := out[key].([]any)
		if len(records) != 2 {
			t.Fatalf("duplicate stored extra completion in %s: %+v", route, records)
		}
		for _, raw := range records {
			if _, exists := raw.(map[string]any)["growth"]; exists {
				t.Fatalf("response-only growth leaked into %s", route)
			}
		}
	}
}

func TestCompletionGrowthStageThresholds(t *testing.T) {
	for _, tc := range []struct {
		from             int
		fromStage, stage string
	}{{1, "sprout", "sprout"}, {2, "sprout", "leaf"}, {6, "leaf", "bloom"}, {7, "bloom", "bloom"}} {
		t.Run(fmt.Sprint(tc.from), func(t *testing.T) {
			a, m, now := setup(t)
			token := login(t, a, m, "one@example.com")
			id := createTask(t, a, token, "until_done")
			for i := 0; i < tc.from; i++ {
				addGardenCompletion(t, a, id, stamp(*now))
			}
			status, out := request(t, a, "POST", "/api/v1/tasks/"+id+"/complete", token, map[string]any{})
			if status != 200 {
				t.Fatalf("complete: %d %+v", status, out)
			}
			assertCompletionGrowth(t, out, tc.from, tc.from, tc.fromStage, tc.fromStage, false)
			status, garden := gardenRequest(t, a, token, "")
			if status != 200 || garden["plants"].([]any)[0].(map[string]any)["growth_count"] != out["growth"].(map[string]any)["to_count"] {
				t.Fatalf("growth disagrees with garden: %d %+v", status, garden)
			}
		})
	}
}

func TestCompletionGrowthIsolatesOwnersAndRejectsArchivedTasks(t *testing.T) {
	a, m, now := setup(t)
	one := login(t, a, m, "one@example.com")
	two := login(t, a, m, "two@example.com")
	id := createTask(t, a, one, "until_done")
	foreign := createTask(t, a, two, "until_done")
	addGardenCompletion(t, a, foreign, stamp(*now))
	// Legacy rows can disagree with their task owner; neither direction counts.
	for i, pair := range [][2]string{{id, foreign}, {foreign, id}} {
		if _, err := a.db.Exec(`INSERT INTO completions(id,user_id,task_id,task_title,cycle_key,text,media_ids,created_at) SELECT ?,user_id,?,'foreign secret',?,'secret','[]',? FROM tasks WHERE id=?`, fmt.Sprint("mismatch-", i), pair[0], fmt.Sprint("mismatch-", i), stamp(*now), pair[1]); err != nil {
			t.Fatal(err)
		}
	}
	path := "/api/v1/tasks/" + id + "/complete"
	if status, out := request(t, a, "POST", path, two, map[string]any{}); status != 404 || out["growth"] != nil {
		t.Fatalf("foreign task completion: %d %+v", status, out)
	}
	status, out := request(t, a, "POST", path, one, map[string]any{})
	if status != 200 {
		t.Fatalf("owned task completion: %d %+v", status, out)
	}
	assertCompletionGrowth(t, out, 0, 0, "seed", "seed", false)
	status, out = request(t, a, "POST", path, one, map[string]any{})
	if status != 200 {
		t.Fatalf("owned task retry: %d %+v", status, out)
	}
	assertCompletionGrowth(t, out, 0, 0, "seed", "seed", false)
	if status, out := request(t, a, "DELETE", "/api/v1/tasks/"+id, one, nil); status != 200 {
		t.Fatalf("archive: %d %+v", status, out)
	}
	if status, out := request(t, a, "POST", path, one, map[string]any{}); status != 404 || out["growth"] != nil {
		t.Fatalf("archived completion: %d %+v", status, out)
	}
}

func TestCompletionGrowthDoesNotReturnForeignCycleRecord(t *testing.T) {
	a, m, now := setup(t)
	one := login(t, a, m, "one@example.com")
	two := login(t, a, m, "two@example.com")
	id := createTask(t, a, one, "until_done")
	foreign := createTask(t, a, two, "until_done")
	task, err := scanTask(a.db.QueryRow(`SELECT `+taskColumns+` FROM tasks WHERE id=?`, id))
	if err != nil {
		t.Fatal(err)
	}
	cycle, _ := schedule.CycleAt(task.Rule, *now)
	if _, err := a.db.Exec(`INSERT INTO completions(id,user_id,task_id,task_title,cycle_key,text,media_ids,created_at) SELECT 'foreign-cycle',user_id,?,'foreign secret',?,'secret','[]',? FROM tasks WHERE id=?`, id, cycle.Key, stamp(*now), foreign); err != nil {
		t.Fatal(err)
	}
	status, out := request(t, a, "POST", "/api/v1/tasks/"+id+"/complete", one, map[string]any{})
	if status != 500 || out["growth"] != nil || out["id"] != nil || out["text"] != nil {
		t.Fatalf("foreign cycle must not be returned as own completion: %d %+v", status, out)
	}
}

func TestCompletionGrowthFailureDoesNotEmitOrPersistGrowth(t *testing.T) {
	for name, fault := range map[string]string{
		"write":  `CREATE TRIGGER fail_completion_update BEFORE UPDATE OF snooze_until ON tasks BEGIN SELECT RAISE(ABORT,'test write failure'); END`,
		"commit": `CREATE TABLE completion_commit_guard(user_id TEXT REFERENCES users(id) DEFERRABLE INITIALLY DEFERRED); CREATE TRIGGER fail_completion_commit AFTER INSERT ON completions BEGIN INSERT INTO completion_commit_guard(user_id) VALUES('missing-user'); END`,
	} {
		t.Run(name, func(t *testing.T) {
			a, m, _ := setup(t)
			token := login(t, a, m, "one@example.com")
			id := createTask(t, a, token, "until_done")
			if _, err := a.db.Exec(fault); err != nil {
				t.Fatal(err)
			}
			status, out := request(t, a, "POST", "/api/v1/tasks/"+id+"/complete", token, map[string]any{})
			if status != 500 || out["growth"] != nil || out["id"] != nil {
				t.Fatalf("failed completion emitted growth: %d %+v", status, out)
			}
			var count int
			if err := a.db.QueryRow(`SELECT COUNT(*) FROM completions WHERE task_id=?`, id).Scan(&count); err != nil || count != 0 {
				t.Fatalf("failed completion persisted rows: count=%d err=%v", count, err)
			}
		})
	}
}
