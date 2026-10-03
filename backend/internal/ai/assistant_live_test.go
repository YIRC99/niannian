package ai

import (
	"context"
	"net/http"
	"niannian/internal/config"
	"niannian/internal/schedule"
	"os"
	"strings"
	"testing"
	"time"
)

// Synthetic inputs only. Drafts must remain executable and disclose assumptions;
// exact choices are deliberately not pinned because the assistant may choose them.
func TestLiveAssistantProposals(t *testing.T) {
	if os.Getenv("NIANNIAN_LIVE_AI") != "1" {
		t.Skip("opt-in real Flash assistant proposals")
	}
	cfg, err := config.Load(config.EmbeddedDefaults())
	if err != nil {
		t.Fatal(err)
	}
	now := time.Date(2026, 10, 3, 20, 25, 37, 0, time.FixedZone("CST", 8*3600))
	for _, tc := range []struct {
		name, input, title            string
		soon, tomorrow, approximation bool
	}{
		{"later_cleaning", "等会儿提醒我搞卫生", "卫生", true, false, false},
		{"shortly_laundry", "一会儿提醒我收衣服", "衣服", true, false, false},
		{"choose_time", "找个合适的时间提醒我整理桌面，时间你来安排", "桌面", false, false, false},
		{"no_time", "提醒我浇花", "花", false, false, false},
		{"vague_afternoon", "明天下午提醒我取快递，几点你定", "快递", false, true, false},
		{"approximate_recurrence", "每天只在下午两点到五点之间提醒我收衣服，收一次就行", "衣服", false, false, true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			t.Parallel()
			client := New(cfg)
			client.http.Transport = intentTrace{base: http.DefaultTransport, t: t}
			d, err := client.Parse(context.Background(), tc.input, now)
			if err != nil {
				t.Fatal(err)
			}
			if !strings.Contains(d.Title, tc.title) || d.Rule.Mode != "until_done" || len(d.Warnings) == 0 {
				t.Fatalf("lost task/completion intent or assumption explanation: %+v", d)
			}
			next, ok := schedule.Next(d.Rule, now)
			if !ok || !next.After(now) || next.After(now.Add(48*time.Hour)) {
				t.Fatalf("no useful future proposal: %+v", d)
			}
			if tc.soon && (next.Before(now.Add(5*time.Minute)) || next.After(now.Add(15*time.Minute))) {
				t.Fatalf("a short delay should be 5–15 minutes: %+v", d)
			}
			if tc.tomorrow {
				next = next.In(now.Location())
				if next.Format("2006-01-02") != "2026-10-04" || next.Hour() < 12 || next.Hour() >= 18 {
					t.Fatalf("explicit tomorrow afternoon was lost: %+v", d)
				}
			}
			if tc.approximation {
				if d.Rule.Kind != "minute_interval" || d.Rule.EndAt == "" || !strings.Contains(strings.Join(d.Warnings, " "), "单次") {
					t.Fatalf("unsupported recurrence must propose and disclose a single exact window: %+v", d)
				}
			}
			t.Logf("%s; feedback=%v", d.Summary, d.Warnings)
		})
	}
}
