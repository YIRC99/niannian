package app

import (
	"encoding/json"
	"niannian/internal/ai"
	"niannian/internal/config"
	"os"
	"testing"
	"time"
)

// Only the parser reaches a real service. Accounts, tasks and notifications use
// a temporary database and fakeMailer; no server or SMTP connection is started.
func TestLiveAIRouteCreatesAndCompletesReminder(t *testing.T) {
	if os.Getenv("NIANNIAN_LIVE_AI") != "1" {
		t.Skip("opt-in real Flash parsing through the app route")
	}
	cfg, err := config.Load(config.EmbeddedDefaults())
	if err != nil {
		t.Fatal(err)
	}
	for _, tc := range []struct {
		name, text string
		window     bool
	}{
		{"relative_ten_minutes", "十分钟之后提醒我收衣服", false},
		{"active_afternoon_window", "提醒我今天下午两点到下午五点去收衣服，收一次就可以了", true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			a, m, now := setup(t)
			token := login(t, a, m, "one@example.com")
			*now = time.Date(2026, 10, 3, 16, 0, 37, 123000000, now.Location())
			parsedAt := *now
			a.parser = ai.New(cfg)
			code, out := request(t, a, "POST", "/api/v1/ai/parse", token, map[string]string{"text": tc.text})
			if code != 200 {
				t.Fatalf("AI route: %d %+v", code, out)
			}
			encoded, err := json.Marshal(out)
			if err != nil {
				t.Fatal(err)
			}
			var draft ai.Draft
			if err := json.Unmarshal(encoded, &draft); err != nil {
				t.Fatal(err)
			}
			if draft.Rule.Kind != "minute_interval" || draft.Rule.Mode != "until_done" || draft.Rule.RepeatMinutes < 1 || draft.Rule.RepeatMinutes > 1440 || draft.Rule.MaxReminders != 0 || draft.Rule.EndDate != "" {
				t.Fatalf("task intent changed: %+v", draft.Rule)
			}
			first, err := time.Parse(time.RFC3339Nano, draft.Rule.FirstAt)
			if err != nil {
				t.Fatal(err)
			}
			if tc.window {
				end, err := time.Parse(time.RFC3339Nano, draft.Rule.EndAt)
				if err != nil || !first.Equal(time.Date(2026, 10, 3, 14, 0, 0, 0, now.Location())) || !end.Equal(time.Date(2026, 10, 3, 17, 0, 0, 0, now.Location())) {
					t.Fatalf("active window changed: %+v", draft.Rule)
				}
			} else if !first.Equal(parsedAt.Truncate(time.Second).Add(10*time.Minute)) || draft.Rule.EndAt != "" {
				t.Fatalf("relative reminder lost its exact delay: %+v", draft.Rule)
			}
			*now = now.Add(20 * time.Second) // Simulate time spent confirming the AI draft.
			code, out = request(t, a, "POST", "/api/v1/tasks", token, map[string]any{"title": draft.Title, "description": draft.Description, "rule": draft.Rule})
			if code != 201 {
				t.Fatalf("AI draft could not be confirmed: %d %+v", code, out)
			}
			id := out["id"].(string)
			next, err := time.Parse(time.RFC3339Nano, out["next_reminder"].(string))
			if err != nil || !next.After(*now) {
				t.Fatalf("confirmed task has no future reminder: %+v", out)
			}
			m.messages = nil
			tickMinutes(t, a, m, 0)
			*now = next
			tickMinutes(t, a, m, 1)
			if code, out := request(t, a, "POST", "/api/v1/tasks/"+id+"/complete", token, map[string]any{}); code != 200 {
				t.Fatalf("completion: %d %+v", code, out)
			}
			*now = now.Add(time.Duration(draft.Rule.RepeatMinutes) * time.Minute)
			tickMinutes(t, a, m, 1)
			t.Logf("confirmed and completed: %s", draft.Summary)
		})
	}
}
