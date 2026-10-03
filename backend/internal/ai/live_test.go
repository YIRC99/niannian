package ai

import (
	"context"
	"niannian/internal/config"
	"os"
	"testing"
	"time"
)

func TestLiveDeepSeek(t *testing.T) {
	if os.Getenv("NIANNIAN_LIVE_AI") != "1" {
		t.Skip("opt-in actual provider check")
	}
	cfg, err := config.Load(config.EmbeddedDefaults())
	if err != nil {
		t.Fatal(err)
	}
	client := New(cfg)
	cases := []struct{ text, kind, mode string }{
		{"每个月1号到15号，每天早上9点提醒我申报公司财务，任何一天完成后当月就不再提醒，下个月继续。", "monthly_window", "until_done"},
		{"明天早上9点提醒洗衣服，11点再提醒一次，一共最多两次，做完就停止。", "once", "until_done"},
		{"从2026年10月5日到2026年10月12日，每天晚上8点提醒我做拉伸，每一天都需要完成。", "date_range", "every_day"},
		{"从今天起每天晚上8点提醒我交材料，没交就每天提醒，哪一天交了以后就永远不提醒，没有截止日期。", "ongoing", "until_done"},
	}
	for _, tc := range cases {
		t.Run(tc.kind, func(t *testing.T) {
			ctx, cancel := context.WithTimeout(context.Background(), 65*time.Second)
			defer cancel()
			d, err := client.Parse(ctx, tc.text, time.Date(2026, 10, 3, 4, 0, 0, 0, time.UTC))
			if err != nil {
				t.Fatal(err)
			}
			if d.Rule.Kind != tc.kind || d.Rule.Mode != tc.mode {
				t.Fatalf("semantic mismatch: %s / %s", d.Rule.Kind, d.Rule.Mode)
			}
			if tc.kind == "monthly_window" && (d.Rule.MonthStart != 1 || d.Rule.MonthEnd != 15) {
				t.Fatal("wrong monthly window")
			}
			if tc.kind == "once" && (len(d.Rule.Times) != 2 || d.Rule.MaxReminders != 2 || d.Rule.StartDate != "2026-10-04") {
				t.Fatal("wrong two-reminder rule")
			}
			t.Log(d.Summary)
		})
	}
}
