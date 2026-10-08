package app

import (
	"encoding/json"
	"fmt"
	"net/http"
	"niannian/internal/ai"
	"niannian/internal/config"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// Real model + production HTTP handler. Storage/mail/clock are isolated, and
// all responses (including failed assertions) are kept as inspectable evidence.
func TestLiveClarificationConversations(t *testing.T) {
	if os.Getenv("NIANNIAN_LIVE_CLARIFICATION") != "1" {
		t.Skip("opt-in real conversation evaluation")
	}
	output := os.Getenv("NIANNIAN_CLARIFICATION_OUTPUT")
	if output == "" {
		t.Fatal("output path required")
	}
	cfg, err := config.Load(config.EmbeddedDefaults())
	if err != nil {
		t.Fatal(err)
	}
	records := []map[string]any{}
	defer func() {
		b, _ := json.MarshalIndent(map[string]any{"source_commit": os.Getenv("NIANNIAN_BENCHMARK_COMMIT"), "method": "real model and production HTTP handler; fixed clocks, temporary DB, fake mail; no scheduler or server started", "cases": records}, "", "  ")
		os.MkdirAll(filepath.Dir(output), 0755)
		if err := os.WriteFile(output, b, 0600); err != nil {
			t.Error(err)
		}
	}()
	for _, name := range []string{"free_text", "timezone_choice", "midnight", "partial_then_correction", "prior_answer_midnight"} {
		t.Run(name, func(t *testing.T) {
			a, m, clock := setup(t)
			*clock, _ = time.Parse(time.RFC3339, "2026-10-08T20:00:00+08:00")
			if name == "midnight" {
				*clock, _ = time.Parse(time.RFC3339, "2026-10-08T23:59:00+08:00")
			}
			token := login(t, a, m, "one@example.com")
			m.messages = nil
			a.parser = ai.New(cfg)
			original := "纽约那个面试，提前半小时叫我。"
			if name == "timezone_choice" {
				original = "10月9日下午2点面试，提前半小时提醒我。两点可能是北京时间，也可能是纽约时间，先让我选一下时区。"
			}
			if name == "midnight" {
				original = "明天面试，提前半小时提醒我。"
			}
			record := map[string]any{"id": name, "input": original}
			steps := []map[string]any{}
			defer func() {
				counts := map[string]int{}
				for _, table := range []string{"tasks", "notifications", "completions", "reminder_plans", "garden_rewards"} {
					var n int
					a.db.QueryRow("SELECT COUNT(*) FROM " + table).Scan(&n)
					counts[table] = n
					if n != 0 {
						t.Errorf("side effect %s", table)
					}
				}
				record["steps"] = steps
				record["side_effect_counts"] = counts
				record["mail_count"] = len(m.messages)
				record["passed"] = !t.Failed()
				records = append(records, record)
			}()
			ask := func(answers []ai.Answer, reference string) map[string]any {
				trace := &benchmarkTransport{base: http.DefaultTransport}
				old := http.DefaultTransport
				http.DefaultTransport = trace
				defer func() { http.DefaultTransport = old }()
				payload := map[string]any{"text": original, "supports_clarification": true, "answers": answers, "reference_time": reference}
				start := time.Now()
				code, out := request(t, a, "POST", "/api/v1/ai/parse", token, payload)
				steps = append(steps, map[string]any{"now": clock.Format(time.RFC3339), "request": payload, "http_status": code, "response": out, "provider_calls": trace.calls, "elapsed_ms": time.Since(start).Milliseconds()})
				if code != 200 {
					t.Fatalf("HTTP %d %+v", code, out)
				}
				return out
			}
			first := ask(nil, "")
			if first["status"] != "needs_clarification" || first["rule"] != nil {
				t.Fatalf("missing anchor became draft: %+v", first)
			}
			question := first["clarification"].(map[string]any)
			reference := first["reference_time"].(string)
			answer := "2026年10月9日纽约当地14:00开始"
			if name == "timezone_choice" {
				answer = ""
				options, _ := question["options"].([]any)
				for _, v := range options {
					o := v.(map[string]any)
					if strings.Contains(fmt.Sprint(o["label"], o["value"]), "纽约") {
						answer = o["value"].(string)
						break
					}
				}
				if answer == "" {
					t.Fatalf("missing selectable New York option: %+v", question)
				}
			}
			if name == "midnight" {
				*clock, _ = time.Parse(time.RFC3339, "2026-10-09T00:01:00+08:00")
				answer = "下午两点，北京时间"
			}
			if name == "partial_then_correction" {
				answer = "10月9日，纽约当地时间"
			}
			if name == "prior_answer_midnight" {
				answer = "明天，纽约当地时间"
			}
			answers := []ai.Answer{{Question: question["question"].(string), Answer: answer}}
			final := ask(answers, reference)
			if name == "partial_then_correction" || name == "prior_answer_midnight" {
				if final["status"] != "needs_clarification" || final["rule"] != nil {
					t.Fatalf("partial answer invented a clock: %+v", final)
				}
				answers[0].AnsweredAt = final["answered_at"].(string)
				q := final["clarification"].(map[string]any)
				nextAnswer := "改成10月10日上午10点，纽约当地时间"
				if name == "prior_answer_midnight" {
					*clock, _ = time.Parse(time.RFC3339, "2026-10-09T00:01:00+08:00")
					nextAnswer = "下午2点"
				}
				answers = append(answers, ai.Answer{Question: q["question"].(string), Answer: nextAnswer})
				final = ask(answers, reference)
			}
			rule, ok := final["rule"].(map[string]any)
			if !ok {
				t.Fatalf("complete answer did not produce draft: %+v", final)
			}
			zone, day, at := "America/New_York", "2026-10-09", "13:30"
			if name == "midnight" {
				zone = "Asia/Shanghai"
			}
			if name == "partial_then_correction" {
				day = "2026-10-10"
				at = "09:30"
			}
			encoded, _ := json.Marshal(rule)
			// A pending obligation may be minute_interval or once; both must preserve
			// the exact date, event timezone and advance interval.
			if rule["timezone"] != zone || !strings.Contains(string(encoded), day) || !strings.Contains(string(encoded), at) {
				t.Errorf("expected %s %s %s: %s", zone, day, at, encoded)
			}
			t.Logf("%s completed in %d steps", name, len(steps))
		})
	}
}
