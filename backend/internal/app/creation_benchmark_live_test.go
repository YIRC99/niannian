package app

import (
	"bytes"
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"

	"niannian/internal/ai"
	"niannian/internal/config"
	"niannian/internal/schedule"
)

// Opt-in measurement, not a golden-output assertion: preserve failures in the
// report rather than aborting the suite or silently rerunning a bad answer.
// The real app handler calls the real model; only clock, storage and mail are
// isolated. It neither starts a server nor changes the running backend clock.
func TestLiveReminderCreationBenchmark(t *testing.T) {
	if os.Getenv("NIANNIAN_LIVE_BENCHMARK") != "1" {
		t.Skip("opt-in real model benchmark")
	}
	output := os.Getenv("NIANNIAN_BENCHMARK_OUTPUT")
	if output == "" {
		t.Fatal("NIANNIAN_BENCHMARK_OUTPUT is required")
	}
	suitePath := filepath.Join("..", "..", "..", "docs", "benchmarks", "reminder-creation-v1.json")
	if selected := os.Getenv("NIANNIAN_BENCHMARK_SUITE"); selected != "" {
		suitePath = selected
	}
	data, err := os.ReadFile(suitePath)
	if err != nil {
		t.Fatal(err)
	}
	var suite struct {
		Cases []struct {
			ID    string `json:"id"`
			Now   string `json:"now"`
			Input string `json:"input"`
		} `json:"cases"`
	}
	if err = json.Unmarshal(data, &suite); err != nil {
		t.Fatal(err)
	}
	if len(suite.Cases) != 10 {
		t.Fatal("expected 10 cases")
	}
	cfg, err := config.Load(config.EmbeddedDefaults())
	if err != nil {
		t.Fatal(err)
	}
	report := map[string]any{
		"lane": "fixed_clock_backend_handler", "model_configured": config.DeepSeekFlashModel,
		"suite_file": filepath.Base(suitePath), "suite_sha256": fmt.Sprintf("%x", sha256.Sum256(data)),
		"started_at": time.Now().Format(time.RFC3339Nano), "source_commit": os.Getenv("NIANNIAN_BENCHMARK_COMMIT"),
		"method": "Real app HTTP handler and real provider; temporary DB and fake mailer; exact fixture clock; no task creation or scheduler started.",
	}
	records := []map[string]any{}
	save := func() {
		report["cases"] = records
		b, e := json.MarshalIndent(report, "", "  ")
		if e != nil {
			t.Fatal(e)
		}
		if e = os.MkdirAll(filepath.Dir(output), 0755); e != nil {
			t.Fatal(e)
		}
		if e = os.WriteFile(output, append(b, '\n'), 0600); e != nil {
			t.Fatal(e)
		}
	}
	for _, tc := range suite.Cases {
		t.Run(tc.ID, func(t *testing.T) {
			a, mailer, clock := setup(t)
			// Authenticate in the same fixed clock so future fixtures don't
			// accidentally expire the synthetic user's session.
			*clock, err = time.Parse(time.RFC3339, tc.Now)
			if err != nil {
				t.Fatal(err)
			}
			token := login(t, a, mailer, "benchmark@example.com")
			mailer.messages = nil
			a.parser = ai.New(cfg)
			trace := &benchmarkTransport{base: http.DefaultTransport}
			originalTransport := http.DefaultTransport
			http.DefaultTransport = trace
			defer func() { http.DefaultTransport = originalTransport }()
			body, _ := json.Marshal(map[string]string{"text": tc.Input})
			req := httptest.NewRequest("POST", "/api/v1/ai/parse", bytes.NewReader(body))
			req.Header.Set("Content-Type", "application/json")
			req.Header.Set("Authorization", "Bearer "+token)
			rec := httptest.NewRecorder()
			start := time.Now()
			a.Handler().ServeHTTP(rec, req)
			elapsed := time.Since(start).Milliseconds()
			var response any
			if err := json.Unmarshal(rec.Body.Bytes(), &response); err != nil {
				response = nil
			}
			counts := map[string]int{}
			for _, table := range []string{"tasks", "notifications", "completions", "reminder_plans", "garden_rewards"} {
				var count int
				if err := a.db.QueryRow("SELECT COUNT(*) FROM " + table).Scan(&count); err != nil {
					t.Fatal(err)
				}
				counts[table] = count
			}
			record := map[string]any{
				"id": tc.ID, "now": tc.Now, "input": tc.Input, "http_status": rec.Code,
				"elapsed_ms": elapsed, "request_started_at": start.Format(time.RFC3339Nano),
				"raw_response": rec.Body.String(), "response": response, "provider_calls": trace.calls,
				"side_effect_counts": counts, "mail_count": len(mailer.messages),
			}
			if rec.Code == 200 {
				var draft ai.Draft
				if err := json.Unmarshal(rec.Body.Bytes(), &draft); err != nil {
					t.Fatal(err)
				}
				if next, ok := schedule.Next(draft.Rule, *clock); ok {
					record["next_candidate"] = next.Format(time.RFC3339)
				}
				horizon := clock.AddDate(0, 0, 4)
				if tc.ID == "RC-04" {
					horizon = clock.AddDate(0, 1, 5)
				}
				record["candidate_preview"] = schedule.DueLimited(draft.Rule, *clock, horizon, 12)
			}
			records = append(records, record)
			save()
			t.Logf("%s status=%d elapsed_ms=%d provider_calls=%d", tc.ID, rec.Code, elapsed, len(trace.calls))
			for table, count := range counts {
				if count != 0 {
					t.Errorf("unexpected side effect %s=%d", table, count)
				}
			}
			if len(mailer.messages) != 0 {
				t.Error("unexpected email")
			}
		})
	}
	report["finished_at"] = time.Now().Format(time.RFC3339Nano)
	save()
}

type benchmarkTransport struct {
	base  http.RoundTripper
	calls []map[string]any
}

func (tr *benchmarkTransport) RoundTrip(req *http.Request) (*http.Response, error) {
	start := time.Now()
	res, err := tr.base.RoundTrip(req)
	item := map[string]any{"elapsed_ms": time.Since(start).Milliseconds()}
	if err != nil {
		item["transport_error"] = "provider transport failed"
		tr.calls = append(tr.calls, item)
		return res, err
	}
	item["status"] = res.StatusCode
	b, readErr := io.ReadAll(res.Body)
	res.Body.Close()
	res.Body = io.NopCloser(bytes.NewReader(b))
	if readErr == nil {
		var envelope map[string]any
		if json.Unmarshal(b, &envelope) == nil {
			// Keep only response fields needed for provenance and repair review.
			// Never persist authorization headers, credentials or config.
			for _, key := range []string{"model", "usage", "choices"} {
				if value, ok := envelope[key]; ok {
					item[key] = value
				}
			}
		}
	}
	tr.calls = append(tr.calls, item)
	return res, readErr
}
