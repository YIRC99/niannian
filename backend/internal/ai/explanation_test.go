package ai

import (
	"encoding/json"
	"strings"
	"testing"
	"time"
)

func TestDraftExplanationUsesFinalRuleInsteadOfModelClockText(t *testing.T) {
	raw := `{"title":"整理桌面","description":"带抹布","rule":{"kind":"minute_interval","mode":"until_done","timezone":"Asia/Shanghai","first_at":"2026-11-12T10:00:00+08:00","end_at":"2026-11-12T11:30:00+08:00","repeat_minutes":13},"summary":"错误摘要","warnings":["下个提醒10:36，明天我自动补排"],"explanation":{"assumptions":[],"limitations":[]}}`
	now, _ := time.Parse(time.RFC3339, "2026-11-12T10:23:41+08:00")
	d, err := decodeDraft(raw, now)
	if err != nil {
		t.Fatal(err)
	}
	feedback := strings.Join(d.Warnings, " ")
	if !strings.Contains(feedback, "10:26:00") || !strings.Contains(feedback, "窗口已经开始") || strings.Contains(feedback, "10:36") || strings.Contains(feedback, "自动补排") {
		t.Fatalf("ungrounded explanation: %s", feedback)
	}
	if d.Description != "带抹布" || d.Rule.FirstAt != "2026-11-12T10:00:00+08:00" {
		t.Fatal("rendering must not change rule or task memo")
	}
	b, _ := json.Marshal(d)
	if strings.Contains(string(b), `"explanation"`) {
		t.Fatal("private model protocol leaked into public draft")
	}
}

func TestDraftExplanationRetainsDailyCompletionInSingleCrossMonthPeriod(t *testing.T) {
	raw := `{"title":"记录温度","rule":{"kind":"date_range","mode":"every_day","timezone":"Asia/Shanghai","start_date":"2026-09-26","end_date":"2026-10-04","times":["19:00"],"max_reminders":2},"explanation":{"assumptions":[],"limitations":["recurrence","holiday_calendar"]}}`
	d, err := decodeDraft(raw, time.Date(2026, 10, 2, 2, 0, 0, 0, time.UTC))
	if err != nil {
		t.Fatal(err)
	}
	feedback := strings.Join(d.Warnings, " ")
	for _, s := range []string{"停止当天提醒", "不会自动生成下一轮", "不能自动跳过法定节假日"} {
		if !strings.Contains(feedback, s) {
			t.Errorf("missing explanation %q: %s", s, feedback)
		}
	}
	if strings.Contains(feedback, "完成一次就永久停止") {
		t.Fatal("daily completion changed by fallback explanation")
	}
}

func TestDraftExplanationFlagsFailClosed(t *testing.T) {
	for _, explanation := range []string{`null`, `{"assumptions":[],"limitations":["invented"]}`, `{"assumptions":["first_time","first_time"],"limitations":[]}`, `{"assumptions":[],"limitations":["recurrence"]}`} {
		raw := `{"title":"每日记录","rule":{"kind":"daily","mode":"every_day","timezone":"Asia/Shanghai","times":["19:00"]},"explanation":` + explanation + `}`
		if _, err := decodeDraft(raw, time.Date(2026, 10, 8, 0, 0, 0, 0, time.UTC)); err == nil {
			t.Errorf("invalid or conflicting explanation accepted: %s", explanation)
		}
	}
}

func TestDraftExplanationDefaultAndCalendarLimits(t *testing.T) {
	raw := `{"title":"核对农历日期","rule":{"kind":"minute_interval","mode":"until_done","timezone":"Asia/Shanghai","first_at":"2026-10-08T12:10:37+08:00","repeat_minutes":30},"explanation":{"assumptions":["first_time","repeat_interval"],"limitations":["unverified_date"]}}`
	d, err := decodeDraft(raw, time.Date(2026, 10, 8, 4, 0, 37, 0, time.UTC))
	if err != nil {
		t.Fatal(err)
	}
	feedback := strings.Join(d.Warnings, " ")
	for _, s := range []string{"暂定", "调整", "农历", "日期"} {
		if !strings.Contains(feedback, s) {
			t.Errorf("missing %q: %s", s, feedback)
		}
	}
}
