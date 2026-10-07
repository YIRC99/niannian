package app

import (
	"fmt"
	"sync"
	"testing"
)

func TestGardenCompletionRewardsAreReusableAndIdempotent(t *testing.T) {
	a, m, _ := setup(t)
	token := login(t, a, m, "one@example.com")
	id := createTask(t, a, token, "until_done")
	status, first := request(t, a, "POST", "/api/v1/tasks/"+id+"/complete", token, map[string]any{})
	reward, ok := first["reward"].(map[string]any)
	if status != 200 || !ok || reward["water_drops"] != float64(3) || reward["balance"] != float64(3) || reward["added"] != true {
		t.Fatalf("completion must award reusable water: %d %+v", status, first)
	}
	status, second := request(t, a, "POST", "/api/v1/tasks/"+id+"/complete", token, map[string]any{})
	reward = second["reward"].(map[string]any)
	if status != 200 || first["id"] != second["id"] || reward["water_drops"] != float64(0) || reward["balance"] != float64(3) || reward["added"] != false {
		t.Fatalf("retry awarded twice: %d %+v", status, second)
	}
	_, garden := gardenRequest(t, a, token, "")
	plant := garden["plants"].([]any)[0].(map[string]any)
	if plant["growth_count"] != float64(0) || plant["stage"] != "seed" {
		t.Fatalf("completion must not also water its source flower: %+v", plant)
	}
}

func plantGardenFlower(t *testing.T, a *App, token, key string) string {
	t.Helper()
	status, out := request(t, a, "POST", "/api/v1/garden/plants", token, map[string]any{"title": "小雏菊", "flower_type": "daisy", "request_id": key})
	if status != 201 && status != 200 {
		t.Fatalf("plant: %d %+v", status, out)
	}
	return out["plant"].(map[string]any)["id"].(string)
}

func TestGardenWaterSpendingOwnershipAndRetry(t *testing.T) {
	a, m, _ := setup(t)
	one := login(t, a, m, "one@example.com")
	two := login(t, a, m, "two@example.com")
	taskID := createTask(t, a, one, "until_done")
	request(t, a, "POST", "/api/v1/tasks/"+taskID+"/complete", one, map[string]any{})
	id := plantGardenFlower(t, a, one, "seed-one")
	if retry := plantGardenFlower(t, a, one, "seed-one"); retry != id {
		t.Fatal("retry duplicated seed")
	}
	path := "/api/v1/garden/plants/" + id + "/water"
	if status, _ := request(t, a, "POST", path, two, map[string]any{"request_id": "foreign"}); status != 404 {
		t.Fatalf("foreign water = %d", status)
	}
	for i := 0; i < 3; i++ {
		status, out := request(t, a, "POST", path, one, map[string]any{"request_id": fmt.Sprint("water-", i)})
		if status != 200 || out["spent"] != float64(1) || out["wallet"].(map[string]any)["balance"] != float64(2-i) || out["plant"].(map[string]any)["growth_count"] != float64(i+1) {
			t.Fatalf("water %d: %d %+v", i, status, out)
		}
	}
	status, retry := request(t, a, "POST", path, one, map[string]any{"request_id": "water-0"})
	if status != 200 || retry["spent"] != float64(0) || retry["growth"].(map[string]any)["added"] != false || retry["plant"].(map[string]any)["stage"] != "leaf" {
		t.Fatalf("retry: %d %+v", status, retry)
	}
	if status, _ := request(t, a, "POST", path, one, map[string]any{"request_id": "empty"}); status != 409 {
		t.Fatalf("empty balance water = %d", status)
	}
	_, garden := gardenRequest(t, a, one, "")
	wallet := garden["wallet"].(map[string]any)
	if wallet["balance"] != float64(0) || wallet["total_earned"] != float64(3) || wallet["total_spent"] != float64(3) {
		t.Fatalf("wallet %+v", wallet)
	}
}

func TestGardenLegacyProgressAndArchivedFlowerCanBloom(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	id := createTask(t, a, token, "until_done")
	for i := 0; i < 6; i++ {
		addGardenCompletion(t, a, id, stamp(*now))
	}
	request(t, a, "DELETE", "/api/v1/tasks/"+id, token, nil)
	for i := 0; i < 2; i++ {
		status, garden := gardenRequest(t, a, token, "")
		p := garden["plants"].([]any)[0].(map[string]any)
		if status != 200 || p["growth_count"] != float64(6) || p["stage"] != "leaf" || garden["wallet"].(map[string]any)["balance"] != float64(0) {
			t.Fatalf("legacy migration %d: %+v", status, garden)
		}
	}
	other := createTask(t, a, token, "until_done")
	request(t, a, "POST", "/api/v1/tasks/"+other+"/complete", token, map[string]any{})
	status, water := request(t, a, "POST", "/api/v1/garden/plants/"+id+"/water", token, map[string]any{"request_id": "bloom"})
	if status != 200 || water["plant"].(map[string]any)["stage"] != "bloom" || water["wallet"].(map[string]any)["balance"] != float64(2) {
		t.Fatalf("archived flower: %d %+v", status, water)
	}
	if status, _ := request(t, a, "POST", "/api/v1/garden/plants/"+id+"/water", token, map[string]any{"request_id": "extra"}); status != 409 {
		t.Fatalf("bloom should not waste water: %d", status)
	}
}

func TestGardenConcurrentWaterCannotOverspend(t *testing.T) {
	a, m, _ := setup(t)
	token := login(t, a, m, "one@example.com")
	taskID := createTask(t, a, token, "until_done")
	request(t, a, "POST", "/api/v1/tasks/"+taskID+"/complete", token, map[string]any{})
	id := plantGardenFlower(t, a, token, "parallel-seed")
	var wg sync.WaitGroup
	statuses := make(chan int, 12)
	for i := 0; i < 12; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			status, _ := request(t, a, "POST", "/api/v1/garden/plants/"+id+"/water", token, map[string]any{"request_id": fmt.Sprint("parallel-", i)})
			statuses <- status
		}(i)
	}
	wg.Wait()
	close(statuses)
	success := 0
	for status := range statuses {
		if status == 200 {
			success++
		} else if status != 409 {
			t.Fatalf("unexpected parallel status %d", status)
		}
	}
	if success != 3 {
		t.Fatalf("spent %d times with 3 drops", success)
	}
	_, garden := gardenRequest(t, a, token, "")
	if garden["wallet"].(map[string]any)["balance"] != float64(0) {
		t.Fatalf("overspent: %+v", garden)
	}
}

func TestGardenTaskFlowerIsSavedAtomicallyAndSynced(t *testing.T) {
	a, m, _ := setup(t)
	token := login(t, a, m, "one@example.com")
	body := taskBody("until_done")
	body["flower_type"] = "hydrangea"
	status, out := request(t, a, "POST", "/api/v1/tasks", token, body)
	if status != 201 || out["flower_type"] != "hydrangea" {
		t.Fatalf("task flower: %d %+v", status, out)
	}
	id := out["id"].(string)
	body["flower_type"] = "lupine"
	status, out = request(t, a, "PUT", "/api/v1/tasks/"+id, token, body)
	if status != 200 || out["flower_type"] != "lupine" {
		t.Fatalf("update flower: %d %+v", status, out)
	}
	delete(body, "flower_type")
	status, out = request(t, a, "PUT", "/api/v1/tasks/"+id, token, body)
	if status != 200 || out["flower_type"] != "lupine" {
		t.Fatalf("old client erased flower: %d %+v", status, out)
	}
	status, out = request(t, a, "PUT", "/api/v1/garden/plants/"+id+"/flower", token, map[string]any{"flower_type": "tulip"})
	if status != 200 || out["plant"].(map[string]any)["flower_type"] != "tulip" {
		t.Fatalf("sync flower: %d %+v", status, out)
	}
	if _, err := a.db.Exec(`CREATE TRIGGER fail_plant BEFORE INSERT ON garden_plants BEGIN SELECT RAISE(ABORT,'test plant failure'); END`); err != nil {
		t.Fatal(err)
	}
	status, _ = request(t, a, "POST", "/api/v1/tasks", token, body)
	var count int
	if err := a.db.QueryRow(`SELECT COUNT(*) FROM tasks`).Scan(&count); err != nil || status != 500 || count != 1 {
		t.Fatalf("task/flower not atomic: status=%d count=%d err=%v", status, count, err)
	}
}

func TestGardenWaterFailureRollsBackBalanceAndProgress(t *testing.T) {
	a, m, _ := setup(t)
	token := login(t, a, m, "one@example.com")
	id := createTask(t, a, token, "until_done")
	request(t, a, "POST", "/api/v1/tasks/"+id+"/complete", token, map[string]any{})
	if _, err := a.db.Exec(`CREATE TRIGGER fail_water BEFORE INSERT ON garden_watering BEGIN SELECT RAISE(ABORT,'test water failure'); END`); err != nil {
		t.Fatal(err)
	}
	status, out := request(t, a, "POST", "/api/v1/garden/plants/"+id+"/water", token, map[string]any{"request_id": "failed-water"})
	if status != 500 || out["growth"] != nil || out["spent"] != nil {
		t.Fatalf("failed response: %d %+v", status, out)
	}
	_, garden := gardenRequest(t, a, token, "")
	if garden["wallet"].(map[string]any)["balance"] != float64(3) || garden["plants"].([]any)[0].(map[string]any)["growth_count"] != float64(0) {
		t.Fatalf("failed water mutated state: %+v", garden)
	}
}

func TestGardenProgressWalletAndRetrySurviveRestart(t *testing.T) {
	a, m, _ := setup(t)
	token := login(t, a, m, "one@example.com")
	id := createTask(t, a, token, "until_done")
	request(t, a, "POST", "/api/v1/tasks/"+id+"/complete", token, map[string]any{})
	flower := plantGardenFlower(t, a, token, "persistent-flower")
	path := "/api/v1/garden/plants/" + flower + "/water"
	request(t, a, "POST", path, token, map[string]any{"request_id": "persisted-water"})
	a.Close()
	again, err := New(a.cfg)
	if err != nil {
		t.Fatal(err)
	}
	defer again.Close()
	again.now = a.now
	status, out := request(t, again, "POST", path, token, map[string]any{"request_id": "persisted-water"})
	if status != 200 || out["spent"] != float64(0) || out["wallet"].(map[string]any)["balance"] != float64(2) || out["plant"].(map[string]any)["growth_count"] != float64(1) || out["plant"].(map[string]any)["flower_type"] != "daisy" {
		t.Fatalf("persistent retry: %d %+v", status, out)
	}
	status, out = request(t, again, "POST", "/api/v1/tasks/"+id+"/complete", token, map[string]any{})
	if status != 200 || out["reward"].(map[string]any)["water_drops"] != float64(0) || out["reward"].(map[string]any)["balance"] != float64(2) {
		t.Fatalf("restart reminted reward: %d %+v", status, out)
	}
}

func TestGardenRewardFailureRollsBackCompletion(t *testing.T) {
	a, m, _ := setup(t)
	token := login(t, a, m, "one@example.com")
	id := createTask(t, a, token, "until_done")
	if _, err := a.db.Exec(`CREATE TRIGGER fail_reward BEFORE INSERT ON garden_rewards WHEN NEW.amount>0 BEGIN SELECT RAISE(ABORT,'test reward failure'); END`); err != nil {
		t.Fatal(err)
	}
	status, out := request(t, a, "POST", "/api/v1/tasks/"+id+"/complete", token, map[string]any{})
	if status != 500 || out["reward"] != nil {
		t.Fatalf("failed reward emitted: %d %+v", status, out)
	}
	_, garden := gardenRequest(t, a, token, "")
	if garden["wallet"].(map[string]any)["balance"] != float64(0) || garden["total_completions"] != float64(0) {
		t.Fatalf("partial reward saved: %+v", garden)
	}
}

func TestGardenValidationAndWalletStayPrivate(t *testing.T) {
	a, m, _ := setup(t)
	one := login(t, a, m, "one@example.com")
	two := login(t, a, m, "two@example.com")
	id := createTask(t, a, one, "until_done")
	request(t, a, "POST", "/api/v1/tasks/"+id+"/complete", one, map[string]any{})
	flower := plantGardenFlower(t, a, one, "private-flower")
	for _, tc := range []struct {
		method, path, token string
		body                map[string]any
		want                int
	}{
		{"POST", "/api/v1/garden/plants", "", map[string]any{"title": "花", "flower_type": "tulip", "request_id": "auth"}, 401},
		{"POST", "/api/v1/garden/plants", one, map[string]any{"title": "花", "flower_type": "unknown", "request_id": "bad"}, 400},
		{"POST", "/api/v1/garden/plants/" + flower + "/water", one, map[string]any{}, 400},
		{"PUT", "/api/v1/garden/plants/" + flower + "/flower", two, map[string]any{"flower_type": "tulip"}, 404},
		{"POST", "/api/v1/garden/plants/" + flower + "/water", two, map[string]any{"request_id": "other-owner"}, 404},
	} {
		if status, _ := request(t, a, tc.method, tc.path, tc.token, tc.body); status != tc.want {
			t.Errorf("%s %s: %d != %d", tc.method, tc.path, status, tc.want)
		}
	}
	_, garden := gardenRequest(t, a, two, "")
	if len(garden["plants"].([]any)) != 0 || garden["wallet"].(map[string]any)["balance"] != float64(0) {
		t.Fatalf("foreign garden leaked: %+v", garden)
	}
}
