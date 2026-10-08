package app

import (
	"context"
	"niannian/internal/ai"
	"niannian/internal/schedule"
	"strings"
	"testing"
	"time"
)

type clarificationParser struct{ inputs []string }

func (p *clarificationParser) Parse(_ context.Context, text string, _ time.Time) (ai.Draft, error) {
	p.inputs = append(p.inputs, text)
	if strings.Contains(text, "10月9日纽约时间14点") {
		return ai.Draft{Title: "面试", Rule: schedule.Rule{Kind: "once", Mode: "until_done", Timezone: "America/New_York", StartDate: "2026-10-09", Times: []string{"13:30"}}}, nil
	}
	return ai.Draft{}, &ai.ClarificationError{Clarification: ai.Clarification{Question: "面试是哪天几点？", Options: []ai.ClarificationOption{}, InputHint: "请写当地时间"}}
}
func TestClarificationRouteAndFollowupNeverCreateTask(t *testing.T) {
	a, m, clock := setup(t)
	token := login(t, a, m, "one@example.com")
	m.messages = nil
	parser := &clarificationParser{}
	a.parser = parser
	*clock, _ = time.Parse(time.RFC3339, "2026-10-08T23:59:00+08:00")
	reference := clock.Format(time.RFC3339)
	for _, supports := range []bool{false, true} {
		code, out := request(t, a, "POST", "/api/v1/ai/parse", token, map[string]any{"text": "纽约面试前半小时提醒", "supports_clarification": supports})
		if supports && (code != 200 || out["status"] != "needs_clarification" || out["rule"] != nil) {
			t.Fatalf("new client: %d %+v", code, out)
		}
		if supports && out["reference_time"] != reference {
			t.Fatal("missing original reference time")
		}
		if !supports && (code != 422 || out["error"] != "面试是哪天几点？") {
			t.Fatalf("legacy client: %d %+v", code, out)
		}
	}
	*clock = clock.Add(2 * time.Minute)
	code, out := request(t, a, "POST", "/api/v1/ai/parse", token, map[string]any{"text": "纽约面试前半小时提醒", "supports_clarification": true, "reference_time": reference, "answers": []ai.Answer{{Question: "面试是哪天几点？", Answer: "10月9日纽约时间14点"}}})
	if code != 200 || out["title"] != "面试" || !strings.Contains(parser.inputs[2], "纽约面试前半小时提醒") {
		t.Fatalf("follow-up lost original: %d %+v", code, out)
	}
	if !strings.Contains(parser.inputs[2], reference) {
		t.Fatal("follow-up replaced original clock")
	}
	for _, ref := range []string{"", "2026-09-01T00:00:00+08:00", "2026-10-10T00:00:00+08:00"} {
		code, _ := request(t, a, "POST", "/api/v1/ai/parse", token, map[string]any{"text": "事项", "reference_time": ref, "answers": []ai.Answer{{Question: "何时？", Answer: "两点"}}})
		if code != 400 {
			t.Error("invalid reference accepted")
		}
	}
	for _, table := range []string{"tasks", "notifications", "completions", "reminder_plans", "garden_rewards"} {
		var count int
		a.db.QueryRow("SELECT COUNT(*) FROM " + table).Scan(&count)
		if count != 0 {
			t.Errorf("created %s", table)
		}
	}
	if len(m.messages) != 0 {
		t.Error("sent mail")
	}
	code, _ = request(t, a, "POST", "/api/v1/ai/parse", token, map[string]any{"text": "事项", "answers": []ai.Answer{{Question: "何时", Answer: " "}}})
	if code != 400 || len(parser.inputs) != 3 {
		t.Error("invalid answer reached model")
	}
	code, _ = request(t, a, "POST", "/api/v1/ai/parse", "", map[string]any{"text": "事项"})
	if code != 401 {
		t.Error("missing authentication")
	}
	for i := 0; i < 5; i++ {
		request(t, a, "POST", "/api/v1/ai/parse", token, map[string]any{"text": "事项", "supports_clarification": true})
	}
	code, _ = request(t, a, "POST", "/api/v1/ai/parse", token, map[string]any{"text": "事项", "supports_clarification": true})
	if code != 429 {
		t.Error("clarification bypassed quota")
	}
}
