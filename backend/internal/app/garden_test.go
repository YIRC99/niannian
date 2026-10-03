package app

import (
	"encoding/json"
	"fmt"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func gardenRequest(t *testing.T, a *App, token, query string) (int, map[string]any) {
	t.Helper()
	r := httptest.NewRequest("GET", "/api/v1/garden"+query, nil)
	if token != "" {
		r.Header.Set("Authorization", "Bearer "+token)
	}
	w := httptest.NewRecorder()
	a.Handler().ServeHTTP(w, r)
	out := map[string]any{}
	if w.Code == 200 {
		if err := json.Unmarshal(w.Body.Bytes(), &out); err != nil {
			t.Fatalf("garden JSON: %v", err)
		}
	}
	return w.Code, out
}

func addGardenCompletion(t *testing.T, a *App, taskID, at string) string {
	t.Helper()
	id := randomID()
	_, err := a.db.Exec(`INSERT INTO completions(id,user_id,task_id,task_title,cycle_key,text,media_ids,created_at) SELECT ?,user_id,id,title,?,'完成记录','[]',? FROM tasks WHERE id=?`, id, id, at, taskID)
	if err != nil {
		t.Fatal(err)
	}
	return id
}

func TestGardenRequiresAuthenticationAndValidTimezone(t *testing.T) {
	a, m, _ := setup(t)
	token := login(t, a, m, "one@example.com")
	for _, auth := range []string{"", "invalid-token"} {
		if status, _ := gardenRequest(t, a, auth, ""); status != 401 {
			t.Fatalf("unauthorized garden status = %d", status)
		}
	}
	for _, query := range []string{"?timezone=Invalid%2FZone", "?timezone=Local", "?timezone=", "?timezone=UTC&timezone=UTC", "?timezone=%zz", "?other=value", "?timezone=" + strings.Repeat("a", 101)} {
		if status, _ := gardenRequest(t, a, token, query); status != 400 {
			t.Errorf("invalid query %q status = %d", query, status)
		}
	}
	for _, query := range []string{"", "?timezone=Asia%2FShanghai"} {
		status, out := gardenRequest(t, a, token, query)
		if status != 200 || out["timezone"] != "Asia/Shanghai" || out["total_completions"] != float64(0) || out["week_completions"] != float64(0) || out["total_plants"] != float64(0) || out["truncated"] != false {
			t.Fatalf("empty garden: %d %+v", status, out)
		}
		if plants, ok := out["plants"].([]any); !ok || len(plants) != 0 {
			t.Fatalf("plants must be an empty array: %+v", out)
		}
		if recent, ok := out["recent_completions"].([]any); !ok || len(recent) != 0 {
			t.Fatalf("recent completions must be an empty array: %+v", out)
		}
	}
}

func TestGardenStagesOwnershipAndArchivedPlants(t *testing.T) {
	a, m, now := setup(t)
	one := login(t, a, m, "one@example.com")
	two := login(t, a, m, "two@example.com")
	want := map[string]struct {
		count    int
		stage    string
		archived bool
	}{}
	total := 0
	for _, tc := range []struct {
		count int
		stage string
	}{{0, "seed"}, {1, "sprout"}, {2, "sprout"}, {3, "leaf"}, {6, "leaf"}, {7, "bloom"}, {14, "bloom"}} {
		id := createTask(t, a, one, "until_done")
		want[id] = struct {
			count    int
			stage    string
			archived bool
		}{tc.count, tc.stage, false}
		for i := 0; i < tc.count; i++ {
			addGardenCompletion(t, a, id, stamp(now.Add(-time.Duration(i)*24*time.Hour)))
		}
		total += tc.count
	}
	archivedID := createTask(t, a, one, "until_done")
	addGardenCompletion(t, a, archivedID, stamp(now.Add(-time.Hour)))
	want[archivedID] = struct {
		count    int
		stage    string
		archived bool
	}{1, "sprout", true}
	total++
	unusedArchive := createTask(t, a, one, "until_done")
	for _, id := range []string{archivedID, unusedArchive} {
		if status, _ := request(t, a, "DELETE", "/api/v1/tasks/"+id, one, nil); status != 200 {
			t.Fatal("could not archive fixture")
		}
	}
	foreignID := createTask(t, a, two, "until_done")
	addGardenCompletion(t, a, foreignID, stamp(*now))
	// Defend both sides of the join even if a legacy row has mismatched ownership.
	if _, err := a.db.Exec(`INSERT INTO completions(id,user_id,task_id,task_title,cycle_key,text,media_ids,created_at) SELECT 'mismatched',user_id,?,'foreign secret','mismatched','','[]',? FROM tasks WHERE id=?`, foreignID, stamp(*now), archivedID); err != nil {
		t.Fatal(err)
	}
	status, out := gardenRequest(t, a, one, "")
	if status != 200 || out["total_completions"] != float64(total) || out["total_plants"] != float64(len(want)) {
		t.Fatalf("garden totals: %d %+v", status, out)
	}
	plants := out["plants"].([]any)
	if len(plants) != len(want) {
		t.Fatalf("lost or leaked plants: %+v", plants)
	}
	for _, raw := range plants {
		p := raw.(map[string]any)
		w, ok := want[p["task_id"].(string)]
		if !ok || p["completion_count"] != float64(w.count) || p["stage"] != w.stage || p["archived"] != w.archived || p["title"] != "申报财务" {
			t.Errorf("unexpected plant: %+v", p)
		}
		if (p["last_completed_at"] == "") != (w.count == 0) {
			t.Errorf("last completion: %+v", p)
		}
	}
	if plants[len(plants)-1].(map[string]any)["task_id"] != archivedID {
		t.Fatal("archived plant should follow active plants")
	}
	for _, raw := range out["recent_completions"].([]any) {
		if raw.(map[string]any)["task_id"] == foreignID {
			t.Fatal("foreign completion leaked")
		}
	}
}

func TestGardenWeekUsesLocalMondayAndRealCompletions(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	id := createTask(t, a, token, "until_done")
	*now = time.Date(2026, 10, 5, 0, 30, 0, 0, now.Location())
	for _, at := range []string{"2026-10-04T15:59:59.999999999Z", "2026-10-05T00:00:00+08:00", "2026-10-04T16:20:00Z"} {
		addGardenCompletion(t, a, id, at)
	}
	if _, err := a.db.Exec(`INSERT INTO notifications(id,user_id,task_id,task_title,cycle_key,scheduled_at,sent_at,status,rule_snapshot) SELECT 'reminder',user_id,id,title,'notice',?,?,'sent',rule FROM tasks WHERE id=?`, stamp(*now), stamp(*now), id); err != nil {
		t.Fatal(err)
	}
	for _, tc := range []struct {
		zone, monday string
		count        int
	}{{"Asia%2FShanghai", "2026-10-05", 2}, {"UTC", "2026-09-28", 3}, {"America%2FLos_Angeles", "2026-09-28", 3}} {
		status, out := gardenRequest(t, a, token, "?timezone="+tc.zone)
		if status != 200 || out["week_start_date"] != tc.monday || out["week_completions"] != float64(tc.count) || out["total_completions"] != float64(3) || out["generated_at"] != stamp(*now) {
			t.Errorf("week %s: %d %+v", tc.zone, status, out)
		}
	}
	*now = now.AddDate(0, 0, 7)
	status, out := gardenRequest(t, a, token, "")
	if status != 200 || out["week_completions"] != float64(0) || out["total_completions"] != float64(3) || out["plants"].([]any)[0].(map[string]any)["stage"] != "leaf" {
		t.Fatalf("skipped days must not reduce growth: %d %+v", status, out)
	}
}

func TestGardenRecentLimitDoesNotLimitTotalsAndKeepsMedia(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	id := createTask(t, a, token, "until_done")
	newest := ""
	for i := 0; i < 20; i++ {
		completionID := addGardenCompletion(t, a, id, stamp(now.Add(-time.Duration(i)*time.Hour)))
		if i == 0 {
			newest = completionID
		}
	}
	if _, err := a.db.Exec(`UPDATE completions SET media_ids='["photo-1"]',text='第一次留念' WHERE id=?`, newest); err != nil {
		t.Fatal(err)
	}
	status, out := gardenRequest(t, a, token, "")
	if status != 200 || out["total_completions"] != float64(20) || out["week_completions"] != float64(20) {
		t.Fatalf("full completion totals: %d %+v", status, out)
	}
	recent := out["recent_completions"].([]any)
	if len(recent) != 12 {
		t.Fatalf("recent count = %d", len(recent))
	}
	first := recent[0].(map[string]any)
	if first["id"] != newest || first["task_id"] != id || first["task_title"] != "申报财务" || first["text"] != "第一次留念" || first["cycle_key"] != newest || first["created_at"] != stamp(*now) || first["media_ids"].([]any)[0] != "photo-1" {
		t.Fatalf("recent schema: %+v", first)
	}
	if out["plants"].([]any)[0].(map[string]any)["completion_count"] != float64(20) {
		t.Fatal("plant count was limited by recent records")
	}
}

func TestGardenDuplicateCompletionOnlyGrowsOnce(t *testing.T) {
	a, m, _ := setup(t)
	token := login(t, a, m, "one@example.com")
	id := createTask(t, a, token, "until_done")
	for i := 0; i < 2; i++ {
		if status, _ := request(t, a, "POST", "/api/v1/tasks/"+id+"/complete", token, map[string]any{}); status != 200 {
			t.Fatalf("completion status = %d", status)
		}
	}
	status, out := gardenRequest(t, a, token, "")
	if status != 200 || out["total_completions"] != float64(1) || out["plants"].([]any)[0].(map[string]any)["stage"] != "sprout" {
		t.Fatalf("duplicate completion inflated growth: %d %+v", status, out)
	}
}

func TestGardenExplicitlyReportsPlantTruncation(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	id := createTask(t, a, token, "until_done")
	var userID string
	if err := a.db.QueryRow(`SELECT user_id FROM tasks WHERE id=?`, id).Scan(&userID); err != nil {
		t.Fatal(err)
	}
	tx, err := a.db.Begin()
	if err != nil {
		t.Fatal(err)
	}
	defer tx.Rollback()
	for i := 0; i < 2001; i++ {
		key := fmt.Sprintf("archived-%04d", i)
		if _, err = tx.Exec(`INSERT INTO tasks(id,user_id,title,description,rule,archived,created_at,updated_at) VALUES(?,?,?,'','{}',1,?,?)`, key, userID, key, stamp(*now), stamp(*now)); err != nil {
			t.Fatal(err)
		}
		if _, err = tx.Exec(`INSERT INTO completions(id,user_id,task_id,task_title,cycle_key,text,media_ids,created_at) VALUES(?,?,?,?,?,'','[]',?)`, key, userID, key, key, key, stamp(*now)); err != nil {
			t.Fatal(err)
		}
	}
	if err = tx.Commit(); err != nil {
		t.Fatal(err)
	}
	status, out := gardenRequest(t, a, token, "")
	if status != 200 || out["total_plants"] != float64(2002) || out["total_completions"] != float64(2001) || out["plant_limit"] != float64(2000) || out["truncated"] != true || len(out["plants"].([]any)) != 2000 {
		t.Fatalf("plant truncation must be explicit: %d totals=%v limit=%v truncated=%v", status, out["total_plants"], out["plant_limit"], out["truncated"])
	}
	if out["plants"].([]any)[0].(map[string]any)["task_id"] != id {
		t.Fatal("active seed omitted in favor of archived plants")
	}
}
