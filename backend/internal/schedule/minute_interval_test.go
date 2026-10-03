package schedule

import (
	"encoding/json"
	"fmt"
	"strings"
	"testing"
	"time"
)

func minuteRule(t *testing.T, firstAt string, repeat int) Rule {
	t.Helper()
	var r Rule
	if err := json.Unmarshal([]byte(fmt.Sprintf(`{"kind":"minute_interval","mode":"until_done","timezone":"Asia/Shanghai","first_at":%q,"repeat_minutes":%d}`, firstAt, repeat)), &r); err != nil {
		t.Fatal(err)
	}
	return r
}

func TestMinuteIntervalValidation(t *testing.T) {
	for _, first := range []string{"2026-10-03T09:15:37+08:00", "2026-10-03T01:15:37Z", "2026-10-03T01:15:37.123Z"} {
		for _, repeat := range []int{1, 5, 1440} {
			if err := Validate(minuteRule(t, first, repeat)); err != nil {
				t.Errorf("valid first=%s repeat=%d: %v", first, repeat, err)
			}
		}
	}
	for _, first := range []string{"", "2026-10-03", "2026-10-03T09:15+08:00", "2026-10-03T09:15:00", "2026-10-03T9:15:00+08:00", "2026-10-03T09:15:00+24:00", "2026-10-03T09:15:00+00:60", "2026-02-30T09:15:00Z", "0000-10-03T09:15:00Z"} {
		t.Run(first, func(t *testing.T) {
			if Validate(minuteRule(t, first, 5)) == nil {
				t.Fatal("invalid first_at accepted")
			}
		})
	}
	for _, repeat := range []int{-1, 0, 1441} {
		if Validate(minuteRule(t, "2026-10-03T09:15:37+08:00", repeat)) == nil {
			t.Errorf("invalid repeat_minutes=%d accepted", repeat)
		}
	}
	for name, change := range map[string]func(*Rule){
		"daily mode":   func(r *Rule) { r.Mode = "every_day" },
		"once mode":    func(r *Rule) { r.Mode = "notify_once" },
		"clock times":  func(r *Rule) { r.Times = []string{"09:00"} },
		"start date":   func(r *Rule) { r.StartDate = "2026-10-03" },
		"weekdays":     func(r *Rule) { r.Weekdays = []int{1} },
		"month start":  func(r *Rule) { r.MonthStart = 1 },
		"month end":    func(r *Rule) { r.MonthEnd = 15 },
		"day interval": func(r *Rule) { r.EveryDays = 3 },
		"early end":    func(r *Rule) { r.EndDate = "2026-10-02" },
		"negative cap": func(r *Rule) { r.MaxReminders = -1 },
		"high cap":     func(r *Rule) { r.MaxReminders = 101 },
	} {
		t.Run(name, func(t *testing.T) {
			r := minuteRule(t, "2026-10-03T09:15:37+08:00", 5)
			change(&r)
			if Validate(r) == nil {
				t.Fatal("conflicting minute rule accepted")
			}
		})
	}
}

func TestMinuteIntervalFieldsDoNotChangeOrdinaryJSON(t *testing.T) {
	encoded, err := json.Marshal(rule())
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(encoded), "first_at") || strings.Contains(string(encoded), "repeat_minutes") {
		t.Fatalf("ordinary stored rule changed: %s", encoded)
	}
	for _, extra := range []string{`"first_at":"2026-10-03T09:15:37+08:00"`, `"repeat_minutes":5`} {
		var r Rule
		if err := json.Unmarshal([]byte(`{"kind":"daily","mode":"until_done","timezone":"Asia/Shanghai","times":["09:00"],`+extra+`}`), &r); err != nil {
			t.Fatal(err)
		}
		if Validate(r) == nil {
			t.Errorf("ordinary rule accepted minute fields: %s", extra)
		}
	}
}

func TestMinuteIntervalNextPreservesExactSeconds(t *testing.T) {
	r := minuteRule(t, "2026-10-03T09:15:37+08:00", 5)
	for _, tc := range []struct{ after, want string }{
		{"2026-10-03T09:00:37+08:00", "2026-10-03T09:15:37+08:00"},
		{"2026-10-03T09:15:36.999999999+08:00", "2026-10-03T09:15:37+08:00"},
		{"2026-10-03T09:15:37+08:00", "2026-10-03T09:20:37+08:00"},
		{"2026-10-03T09:20:36.999999999+08:00", "2026-10-03T09:20:37+08:00"},
		{"2026-10-03T09:20:37+08:00", "2026-10-03T09:25:37+08:00"},
	} {
		got, ok := Next(r, instant(t, tc.after))
		if !ok || !got.Equal(instant(t, tc.want)) {
			t.Errorf("after=%s next=%s, %v want=%s", tc.after, got, ok, tc.want)
		}
	}
}

func TestMinuteIntervalCrossesMidnightAndMonthWithoutReset(t *testing.T) {
	r := minuteRule(t, "2026-10-31T23:58:37+08:00", 5)
	got := Due(r, instant(t, "2026-10-31T23:58:36+08:00"), instant(t, "2026-11-01T00:08:37+08:00"))
	want := []string{"2026-10-31T23:58:37+08:00", "2026-11-01T00:03:37+08:00", "2026-11-01T00:08:37+08:00"}
	if len(got) != len(want) {
		t.Fatalf("slots=%v", got)
	}
	for i := range got {
		if !got[i].At.Equal(instant(t, want[i])) || got[i].CycleKey != "minute_interval" {
			t.Errorf("slot[%d]=%+v", i, got[i])
		}
	}
	got = Due(r, instant(t, want[0]), instant(t, want[1]))
	if len(got) != 1 || !got[0].At.Equal(instant(t, want[1])) {
		t.Fatalf("exclusive from / inclusive to: %v", got)
	}
}

func TestMinuteIntervalCompletionCoversWaitAndCadenceEdits(t *testing.T) {
	r := minuteRule(t, "2026-10-02T17:15:37Z", 5)
	for _, at := range []string{"2026-10-02T23:55:00+08:00", "2026-10-03T00:00:00+08:00", "2026-10-03T01:00:00+08:00", "2026-11-01T00:00:00+08:00", "2030-01-01T00:00:00+08:00"} {
		c, ok := CycleAt(r, instant(t, at))
		if !ok || c.Key != "minute_interval" || c.Start.Year() != 1 || c.Start.Month() != time.January || c.Start.Day() != 1 || c.End.Year() != 10000 {
			t.Errorf("at=%s cycle=%+v, %v", at, c, ok)
		}
	}
	edited := minuteRule(t, "2026-10-04T09:30:00+08:00", 15)
	c, ok := CycleAt(edited, instant(t, "2026-10-05T09:00:00+08:00"))
	if !ok || c.Key != "minute_interval" {
		t.Fatalf("cadence edit reset completion key: %+v, %v", c, ok)
	}
}

func TestMinuteIntervalDeadlineAndCapsRemainBackendPolicy(t *testing.T) {
	r := minuteRule(t, "2026-10-31T23:58:37+08:00", 5)
	r.EndDate = "2026-11-01"
	r.MaxReminders = 1
	got := Due(r, instant(t, "2026-10-31T23:58:36+08:00"), instant(t, "2026-11-02T00:05:00+08:00"))
	if len(got) != 289 || got[len(got)-1].At.Format(time.RFC3339) != "2026-11-01T23:58:37+08:00" {
		t.Fatalf("deadline or backend-owned cap: count=%d", len(got))
	}
	if _, ok := Next(r, instant(t, "2026-11-01T23:58:37+08:00")); ok {
		t.Fatal("next exceeded inclusive local deadline")
	}
	c, ok := CycleAt(r, instant(t, "2026-11-01T23:59:59+08:00"))
	if !ok || !c.End.Equal(instant(t, "2026-11-02T00:00:00+08:00")) {
		t.Fatalf("deadline cycle=%+v, %v", c, ok)
	}
	if _, ok := CycleAt(r, instant(t, "2026-11-02T00:00:00+08:00")); ok {
		t.Fatal("cycle remained after deadline")
	}
}

func TestMinuteIntervalUsesElapsedMinutesAcrossDST(t *testing.T) {
	r := minuteRule(t, "2026-03-08T01:58:37-05:00", 5)
	r.Timezone = "America/New_York"
	got, ok := Next(r, instant(t, "2026-03-08T01:58:37-05:00"))
	if !ok || got.Format(time.RFC3339) != "2026-03-08T03:03:37-04:00" {
		t.Fatalf("spring DST cadence=%s, %v", got, ok)
	}
	r = minuteRule(t, "2026-11-01T01:58:37-04:00", 5)
	r.Timezone = "America/New_York"
	got, ok = Next(r, instant(t, "2026-11-01T01:58:37-04:00"))
	if !ok || got.Format(time.RFC3339) != "2026-11-01T01:03:37-05:00" {
		t.Fatalf("fall DST cadence=%s, %v", got, ok)
	}
}

func TestMinuteIntervalSummaryUsesRuleTimezoneAndPermanentCompletion(t *testing.T) {
	r := minuteRule(t, "2026-10-03T01:15:37Z", 5)
	want := "2026-10-03 09:15:37 首次提醒，之后每 5 分钟提醒一次，完成后不再提醒"
	if got := Summary(r); got != want {
		t.Fatalf("summary=%q want=%q", got, want)
	}
	r.EndDate = "2026-10-05"
	r.MaxReminders = 3
	if got := Summary(r); !strings.Contains(got, "最多 3 次") || !strings.Contains(got, "至 2026-10-05") {
		t.Fatalf("summary omitted constraints: %s", got)
	}
}

func TestMinuteIntervalVeryOldAnchorAndFourDigitYears(t *testing.T) {
	r := minuteRule(t, "1700-01-01T00:00:37Z", 5)
	r.Timezone = "UTC"
	got, ok := Next(r, instant(t, "2026-10-03T09:15:37Z"))
	if !ok || got.Format(time.RFC3339) != "2026-10-03T09:20:37Z" {
		t.Fatalf("old anchor cadence=%s, %v", got, ok)
	}
	last := instant(t, "9999-12-31T23:59:59Z")
	if got, ok := Next(r, last); ok {
		t.Fatalf("unencodable candidate=%s", got)
	}
	if got := Due(r, last, last.Add(time.Hour)); len(got) != 0 {
		t.Fatalf("year 10000 candidates=%v", got)
	}
}

func TestMinuteIntervalLatestDueFindsOnlyNewestAnchoredSlot(t *testing.T) {
	r := minuteRule(t, "2026-10-31T23:58:37.123+08:00", 5)
	for _, tc := range []struct{ from, to, want string }{
		{"2026-10-31T23:00:00+08:00", "2026-10-31T23:58:37.122999999+08:00", ""},
		{"2026-10-31T23:00:00+08:00", "2026-10-31T23:58:37.123+08:00", "2026-10-31T23:58:37.123+08:00"},
		{"2026-10-31T23:58:37.123+08:00", "2026-11-01T00:03:37.122999999+08:00", ""},
		{"2026-10-31T23:58:37.123+08:00", "2026-11-01T00:03:37.123+08:00", "2026-11-01T00:03:37.123+08:00"},
		{"2026-10-31T23:58:37.123+08:00", "2026-11-01T00:06:00+08:00", "2026-11-01T00:03:37.123+08:00"},
	} {
		got, ok := LatestDue(r, instant(t, tc.from), instant(t, tc.to))
		if tc.want == "" {
			if ok {
				t.Errorf("unexpected latest=%+v", got)
			}
		} else if !ok || !got.At.Equal(instant(t, tc.want)) || got.CycleKey != "minute_interval" {
			t.Errorf("latest=%+v, %v want=%s", got, ok, tc.want)
		}
	}
	next, ok := Next(r, instant(t, "2026-11-01T00:03:37.122999999+08:00"))
	if !ok || next.Format(time.RFC3339Nano) != "2026-11-01T00:03:37.123+08:00" {
		t.Fatalf("fractional anchor lost: %s, %v", next, ok)
	}
	r.EndDate = "2026-11-01"
	got, ok := LatestDue(r, instant(t, "2026-10-31T23:00:00+08:00"), instant(t, "2027-01-01T00:00:00+08:00"))
	if !ok || got.At.Format(time.RFC3339Nano) != "2026-11-01T23:58:37.123+08:00" {
		t.Fatalf("latest escaped deadline: %+v, %v", got, ok)
	}
	r = minuteRule(t, "1700-01-01T00:00:37Z", 1)
	r.Timezone = "UTC"
	got, ok = LatestDue(r, instant(t, "1700-01-01T00:00:00Z"), instant(t, "9999-12-31T23:59:59Z").Add(time.Hour))
	if !ok || got.At.Format(time.RFC3339) != "9999-12-31T23:59:37Z" {
		t.Fatalf("latest from ancient anchor=%+v, %v", got, ok)
	}
}

func TestMinuteIntervalDueLimitedBoundsAllocationAndKeepsEarliest(t *testing.T) {
	r := minuteRule(t, "2026-10-31T23:58:37+08:00", 1)
	from, to := instant(t, "2026-10-31T23:00:00+08:00"), instant(t, "2030-01-01T00:00:00+08:00")
	got := DueLimited(r, from, to, 2)
	if len(got) != 2 || got[0].At.Format(time.RFC3339) != "2026-10-31T23:58:37+08:00" || got[1].At.Format(time.RFC3339) != "2026-10-31T23:59:37+08:00" {
		t.Fatalf("limited candidates=%v", got)
	}
	for _, limit := range []int{0, -1} {
		if got := DueLimited(r, from, to, limit); len(got) != 0 {
			t.Errorf("nonpositive limit=%d generated %d candidates", limit, len(got))
		}
	}
	r = minuteRule(t, "2026-10-01T00:00:00+08:00", 1)
	month := Due(r, instant(t, "2026-09-30T23:59:59+08:00"), instant(t, "2026-10-31T23:59:59+08:00"))
	if len(month) != 31*24*60 {
		t.Fatalf("Due silently truncated month to %d candidates", len(month))
	}
	old := minuteRule(t, "1700-01-01T00:00:00Z", 1440)
	old.Timezone = "UTC"
	got = DueLimited(old, instant(t, "1700-01-01T00:00:00Z"), instant(t, "2030-01-01T00:00:00Z"), 1)
	if len(got) != 1 || got[0].At.Format(time.RFC3339) != "2025-01-01T00:00:00Z" {
		t.Fatalf("five calendar year bound=%v", got)
	}
}

func TestBoundedQueriesKeepOrdinaryScheduleBehavior(t *testing.T) {
	r := rule()
	r.Times = []string{"18:00", "09:00"}
	from, to := instant(t, "2026-10-01T09:00:00+08:00"), instant(t, "2026-10-02T18:00:00+08:00")
	all := Due(r, from, to)
	got := DueLimited(r, from, to, 2)
	if len(got) != 2 || !got[0].At.Equal(all[0].At) || !got[1].At.Equal(all[1].At) || got[0].CycleKey != all[0].CycleKey || got[1].CycleKey != all[1].CycleKey {
		t.Fatalf("ordinary limited=%v all=%v", got, all)
	}
	latest, ok := LatestDue(r, from, to)
	if !ok || !latest.At.Equal(all[len(all)-1].At) || latest.CycleKey != all[len(all)-1].CycleKey {
		t.Fatalf("ordinary latest=%+v, %v all=%v", latest, ok, all)
	}
	for _, r := range []Rule{{}, minuteRule(t, "2026-10-01T09:00:00+08:00", 0)} {
		if _, ok := LatestDue(r, from, to); ok {
			t.Fatal("invalid latest accepted")
		}
		if got := DueLimited(r, from, to, 2); len(got) != 0 {
			t.Fatalf("invalid limited=%v", got)
		}
	}
	if _, ok := LatestDue(rule(), to, from); ok {
		t.Fatal("reversed latest range accepted")
	}
}
