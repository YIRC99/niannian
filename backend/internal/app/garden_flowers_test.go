package app

import (
	"fmt"
	"testing"
)

var expandedGardenFlowerTypes = []string{
	"tulip", "daisy", "lupine", "hydrangea", "sunflower", "rose",
	"lavender", "cosmos", "lily", "pansy", "iris", "hibiscus",
}

func TestGardenExpandedFlowersPlantRetryAndOwnership(t *testing.T) {
	a, m, _ := setup(t)
	one := login(t, a, m, "one@example.com")
	two := login(t, a, m, "two@example.com")
	want := make(map[string]string)
	for _, flower := range expandedGardenFlowerTypes {
		t.Run(flower, func(t *testing.T) {
			body := map[string]any{"title": "我的花", "flower_type": flower, "request_id": "seed-" + flower}
			status, out := request(t, a, "POST", "/api/v1/garden/plants", one, body)
			if status != 201 {
				t.Fatalf("plant %s: %d %+v", flower, status, out)
			}
			plant := out["plant"].(map[string]any)
			id := plant["id"].(string)
			want[id] = flower
			if plant["flower_type"] != flower || plant["stage"] != "seed" || plant["growth_count"] != float64(0) {
				t.Fatalf("incorrect new plant: %+v", plant)
			}
			status, out = request(t, a, "POST", "/api/v1/garden/plants", one, body)
			if status != 200 || out["plant"].(map[string]any)["id"] != id {
				t.Fatalf("retry changed plant: %d %+v", status, out)
			}
			if status, _ := request(t, a, "PUT", "/api/v1/garden/plants/"+id+"/flower", two, map[string]any{"flower_type": flower}); status != 404 {
				t.Fatalf("other account can change %s: %d", flower, status)
			}
		})
	}
	status, garden := gardenRequest(t, a, one, "")
	if status != 200 || garden["total_plants"] != float64(12) {
		t.Fatalf("expanded garden: %d %+v", status, garden)
	}
	for _, raw := range garden["plants"].([]any) {
		plant := raw.(map[string]any)
		if want[plant["id"].(string)] != plant["flower_type"] {
			t.Fatalf("garden lost flower identity: %+v", plant)
		}
	}
	_, other := gardenRequest(t, a, two, "")
	if other["total_plants"] != float64(0) {
		t.Fatalf("flowers leaked to other account: %+v", other)
	}
}

func TestGardenExpandedTaskFlowersRoundTrip(t *testing.T) {
	a, m, _ := setup(t)
	token := login(t, a, m, "one@example.com")
	for _, flower := range expandedGardenFlowerTypes {
		t.Run(flower, func(t *testing.T) {
			body := taskBody("until_done")
			body["flower_type"] = flower
			status, out := request(t, a, "POST", "/api/v1/tasks", token, body)
			if status != 201 || out["flower_type"] != flower {
				t.Fatalf("create task flower: %d %+v", status, out)
			}
			id := out["id"].(string)
			status, out = request(t, a, "PUT", "/api/v1/garden/plants/"+id+"/flower", token, map[string]any{"flower_type": "tulip"})
			if status != 200 || out["plant"].(map[string]any)["flower_type"] != "tulip" {
				t.Fatalf("change flower: %d %+v", status, out)
			}
			status, out = request(t, a, "PUT", "/api/v1/tasks/"+id, token, body)
			if status != 200 || out["flower_type"] != flower {
				t.Fatalf("edit task flower: %d %+v", status, out)
			}
			delete(body, "flower_type")
			status, out = request(t, a, "PUT", "/api/v1/tasks/"+id, token, body)
			if status != 200 || out["flower_type"] != flower {
				t.Fatalf("old client erased flower: %d %+v", status, out)
			}
			status, out = request(t, a, "GET", "/api/v1/tasks/"+id, token, nil)
			if status != 200 || out["flower_type"] != flower {
				t.Fatalf("task reload lost flower: %d %+v", status, out)
			}
		})
	}
}

func TestGardenExpandedFlowerGrowthPreservesLegacyPlant(t *testing.T) {
	a, m, now := setup(t)
	token := login(t, a, m, "one@example.com")
	legacyID := createTask(t, a, token, "until_done")
	for i := 0; i < 7; i++ {
		addGardenCompletion(t, a, legacyID, stamp(*now))
	}
	for i := 0; i < 3; i++ {
		id := createTask(t, a, token, "until_done")
		if status, out := request(t, a, "POST", "/api/v1/tasks/"+id+"/complete", token, map[string]any{}); status != 200 {
			t.Fatalf("earn water: %d %+v", status, out)
		}
	}
	status, out := request(t, a, "POST", "/api/v1/garden/plants", token, map[string]any{"title": "木槿", "flower_type": "hibiscus", "request_id": "new-flower"})
	if status != 201 {
		t.Fatalf("new flower: %d %+v", status, out)
	}
	id := out["plant"].(map[string]any)["id"].(string)
	for i := 1; i <= 7; i++ {
		status, out = request(t, a, "POST", "/api/v1/garden/plants/"+id+"/water", token, map[string]any{"request_id": fmt.Sprint("water-", i)})
		if status != 200 || out["plant"].(map[string]any)["flower_type"] != "hibiscus" || out["plant"].(map[string]any)["growth_count"] != float64(i) {
			t.Fatalf("new flower growth: %d %+v", status, out)
		}
	}
	if status, _ := request(t, a, "POST", "/api/v1/garden/plants/"+id+"/water", token, map[string]any{"request_id": "after-bloom"}); status != 409 {
		t.Fatalf("bloom should not consume more water: %d", status)
	}
	_, garden := gardenRequest(t, a, token, "")
	if garden["wallet"].(map[string]any)["balance"] != float64(2) {
		t.Fatalf("new species changed water accounting: %+v", garden)
	}
	foundLegacy, foundNew := false, false
	for _, raw := range garden["plants"].([]any) {
		plant := raw.(map[string]any)
		if plant["id"] == legacyID {
			foundLegacy = true
			if plant["flower_type"] != "" || plant["growth_count"] != float64(7) || plant["stage"] != "bloom" {
				t.Fatalf("legacy identity or progress changed: %+v", plant)
			}
		}
		if plant["id"] == id {
			foundNew = true
			if plant["flower_type"] != "hibiscus" || plant["stage"] != "bloom" {
				t.Fatalf("new flower absent from blooming collection: %+v", plant)
			}
		}
	}
	if !foundLegacy || !foundNew {
		t.Fatal("garden lost old or new flower")
	}
}
