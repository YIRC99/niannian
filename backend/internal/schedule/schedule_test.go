package schedule

import (
	"strings"
	"testing"
	"time"
)

func rule() Rule {
	return Rule{Kind: "monthly_window", Mode: "until_done", Timezone: "Asia/Shanghai", Times: []string{"09:00"}, MonthStart: 1, MonthEnd: 15}
}

func instant(t *testing.T, value string) time.Time {
	t.Helper()
	v, err := time.Parse(time.RFC3339, value)
	if err != nil {
		t.Fatal(err)
	}
	return v
}

func TestValidate(t *testing.T) {
	valid := []Rule{rule()}
	for _, kind := range []string{"once", "daily", "weekly", "date_range", "interval"} {
		r := rule()
		r.Kind = kind
		r.StartDate = "2026-10-01"
		r.EndDate = "2026-10-15"
		r.EveryDays = 3
		r.Weekdays = []int{1, 5}
		valid = append(valid, r)
	}
	for _, r := range valid {
		if err := Validate(r); err != nil {
			t.Errorf("valid %s: %v", r.Kind, err)
		}
	}
	cases := map[string]func(*Rule){
		"unknown kind":        func(r *Rule) { r.Kind = "yearly" },
		"unknown mode":        func(r *Rule) { r.Mode = "repeat" },
		"missing timezone":    func(r *Rule) { r.Timezone = "" },
		"invalid timezone":    func(r *Rule) { r.Timezone = "Mars/Olympus" },
		"host local timezone": func(r *Rule) { r.Timezone = "Local" },
		"no time":             func(r *Rule) { r.Times = nil },
		"duplicate time":      func(r *Rule) { r.Times = []string{"09:00", "09:00"} },
		"bad time":            func(r *Rule) { r.Times = []string{"24:00"} },
		"short time":          func(r *Rule) { r.Times = []string{"9:00"} },
		"too many times": func(r *Rule) {
			r.Times = []string{"00:00", "01:00", "02:00", "03:00", "04:00", "05:00", "06:00", "07:00", "08:00"}
		},
		"bad date":                func(r *Rule) { r.StartDate = "2026-02-30" },
		"reversed dates":          func(r *Rule) { r.StartDate = "2026-10-15"; r.EndDate = "2026-10-01" },
		"reversed month days":     func(r *Rule) { r.MonthStart = 16; r.MonthEnd = 15 },
		"missing month start":     func(r *Rule) { r.MonthStart = 0 },
		"month day 32":            func(r *Rule) { r.MonthEnd = 32 },
		"negative cap":            func(r *Rule) { r.MaxReminders = -1 },
		"excessive cap":           func(r *Rule) { r.MaxReminders = 101 },
		"once missing date":       func(r *Rule) { r.Kind = "once" },
		"range missing end":       func(r *Rule) { r.Kind = "date_range"; r.StartDate = "2026-10-03" },
		"weekly no weekdays":      func(r *Rule) { r.Kind = "weekly" },
		"weekday out of bounds":   func(r *Rule) { r.Kind = "weekly"; r.Weekdays = []int{0} },
		"duplicate weekdays":      func(r *Rule) { r.Kind = "weekly"; r.Weekdays = []int{1, 1} },
		"interval missing anchor": func(r *Rule) { r.Kind = "interval"; r.EveryDays = 2 },
		"interval zero":           func(r *Rule) { r.Kind = "interval"; r.StartDate = "2026-10-03" },
		"interval too large":      func(r *Rule) { r.Kind = "interval"; r.StartDate = "2026-10-03"; r.EveryDays = 366 },
	}
	for name, change := range cases {
		t.Run(name, func(t *testing.T) {
			r := rule()
			change(&r)
			if Validate(r) == nil {
				t.Fatal("invalid rule accepted")
			}
		})
	}
}

func TestMonthlyWindowCyclesAndBounds(t *testing.T) {
	r := rule()
	cases := []struct {
		at              string
		valid           bool
		key, start, end string
	}{
		{"2026-09-30T23:59:59+08:00", false, "", "", ""},
		{"2026-10-01T00:00:00+08:00", true, "2026-10", "2026-10-01T00:00:00+08:00", "2026-10-16T00:00:00+08:00"},
		{"2026-10-15T23:59:59+08:00", true, "2026-10", "2026-10-01T00:00:00+08:00", "2026-10-16T00:00:00+08:00"},
		{"2026-10-16T00:00:00+08:00", false, "", "", ""},
		{"2026-11-01T08:00:00+08:00", true, "2026-11", "2026-11-01T00:00:00+08:00", "2026-11-16T00:00:00+08:00"},
	}
	for _, tc := range cases {
		t.Run(tc.at, func(t *testing.T) {
			c, ok := CycleAt(r, instant(t, tc.at))
			if ok != tc.valid {
				t.Fatalf("active=%v want %v", ok, tc.valid)
			}
			if ok && (c.Key != tc.key || !c.Start.Equal(instant(t, tc.start)) || !c.End.Equal(instant(t, tc.end))) {
				t.Fatalf("cycle=%+v", c)
			}
		})
	}
	r.Mode = "every_day"
	c, ok := CycleAt(r, instant(t, "2026-10-01T08:00:00+08:00"))
	if !ok || c.Key != "2026-10-01" || !c.End.Equal(instant(t, "2026-10-02T00:00:00+08:00")) {
		t.Fatalf("daily completion cycle=%+v, %v", c, ok)
	}
}

func TestDueExclusiveFromInclusiveToAndSort(t *testing.T) {
	r := rule()
	r.Times = []string{"18:00", "09:00"}
	got := Due(r, instant(t, "2026-10-01T09:00:00+08:00"), instant(t, "2026-10-02T09:00:00+08:00"))
	want := []string{"2026-10-01T18:00:00+08:00", "2026-10-02T09:00:00+08:00"}
	if len(got) != len(want) {
		t.Fatalf("got %v", got)
	}
	for i := range want {
		if !got[i].At.Equal(instant(t, want[i])) || got[i].CycleKey != "2026-10" {
			t.Errorf("occurrence[%d]=%+v", i, got[i])
		}
	}
	if len(Due(r, instant(t, want[0]), instant(t, want[0]))) != 0 {
		t.Fatal("empty range must be empty")
	}
	if r.Times[0] != "18:00" {
		t.Fatal("Due mutated caller times")
	}
}

func TestNextAcrossMonthlyAndDateBounds(t *testing.T) {
	r := rule()
	got, ok := Next(r, instant(t, "2026-10-15T09:00:00+08:00"))
	if !ok || !got.Equal(instant(t, "2026-11-01T09:00:00+08:00")) {
		t.Fatalf("next=%s active=%v", got, ok)
	}
	r.EndDate = "2026-10-15"
	if _, ok := Next(r, instant(t, "2026-10-15T09:00:00+08:00")); ok {
		t.Fatal("returned after end date")
	}
	r.EndDate = ""
	r.StartDate = "2026-11-03"
	got, ok = Next(r, instant(t, "2026-10-01T00:00:00+08:00"))
	if !ok || !got.Equal(instant(t, "2026-11-03T09:00:00+08:00")) {
		t.Fatalf("next before start=%s, %v", got, ok)
	}
}

func TestMonthEndClampsStartAndEnd(t *testing.T) {
	r := rule()
	r.MonthStart = 31
	r.MonthEnd = 31
	for _, date := range []string{"2026-02-28", "2028-02-29", "2026-04-30", "2026-01-31"} {
		at := instant(t, date+"T08:00:00+08:00")
		c, ok := CycleAt(r, at)
		if !ok || c.Start.Format("2006-01-02") != date {
			t.Fatalf("clamp %s: %+v %v", date, c, ok)
		}
		got, ok := Next(r, at)
		if !ok || got.Format("2006-01-02 15:04") != date+" 09:00" {
			t.Fatalf("clamp slot %s: %s %v", date, got, ok)
		}
	}
}

func TestWeeklyISOYearAndCompletionCycle(t *testing.T) {
	r := rule()
	r.Kind = "weekly"
	r.Weekdays = []int{1, 7}
	c, ok := CycleAt(r, instant(t, "2027-01-01T08:00:00+08:00"))
	if !ok || c.Key != "2026-W53" || c.Start.Format("2006-01-02") != "2026-12-28" || c.End.Format("2006-01-02") != "2027-01-04" {
		t.Fatalf("ISO cycle=%+v %v", c, ok)
	}
	got := Due(r, instant(t, "2026-12-27T23:59:59+08:00"), instant(t, "2027-01-03T09:00:00+08:00"))
	if len(got) != 2 || got[0].At.Format("2006-01-02") != "2026-12-28" || got[1].At.Format("2006-01-02") != "2027-01-03" {
		t.Fatalf("weekday slots=%v", got)
	}
	r.Mode = "every_day"
	if _, ok := CycleAt(r, instant(t, "2027-01-01T08:00:00+08:00")); ok {
		t.Fatal("non-scheduled day should have no daily cycle")
	}
}

func TestDateRangeOnceAndNotificationCapsRemainBackendPolicy(t *testing.T) {
	r := rule()
	r.Kind = "date_range"
	r.StartDate = "2026-10-03"
	r.EndDate = "2026-10-05"
	r.Mode = "notify_once"
	c, ok := CycleAt(r, instant(t, "2026-10-03T01:00:00+08:00"))
	if !ok || c.Key != "range:2026-10-03:2026-10-05" || c.End.Format("2006-01-02") != "2026-10-06" {
		t.Fatalf("range=%+v %v", c, ok)
	}
	got := Due(r, instant(t, "2026-10-02T00:00:00+08:00"), instant(t, "2026-10-06T00:00:00+08:00"))
	if len(got) != 3 {
		t.Fatalf("all potential slots needed for successful-count policy, got %v", got)
	}
	r.Kind = "once"
	r.Mode = "until_done"
	r.Times = []string{"09:00", "12:00"}
	r.MaxReminders = 2
	got = Due(r, instant(t, "2026-10-02T00:00:00+08:00"), instant(t, "2026-10-06T00:00:00+08:00"))
	if len(got) != 2 || got[0].CycleKey != "once:2026-10-03" || got[1].CycleKey != got[0].CycleKey {
		t.Fatalf("once=%v", got)
	}
	if _, ok := Next(r, instant(t, "2026-10-03T12:00:00+08:00")); ok {
		t.Fatal("once schedule continued tomorrow")
	}
}

func TestIntervalUsesCalendarDaysAcrossDST(t *testing.T) {
	r := rule()
	r.Kind = "interval"
	r.Timezone = "America/New_York"
	r.StartDate = "2026-03-07"
	r.EveryDays = 2
	got := Due(r, instant(t, "2026-03-07T00:00:00-05:00"), instant(t, "2026-03-12T00:00:00-04:00"))
	want := []string{"2026-03-07T09:00:00-05:00", "2026-03-09T09:00:00-04:00", "2026-03-11T09:00:00-04:00"}
	if len(got) != len(want) {
		t.Fatalf("interval=%v", got)
	}
	for i := range want {
		if !got[i].At.Equal(instant(t, want[i])) {
			t.Errorf("interval[%d]=%s", i, got[i].At)
		}
	}
	if _, ok := CycleAt(r, instant(t, "2026-03-08T12:00:00-04:00")); ok {
		t.Fatal("non-interval day active")
	}
	if _, ok := CycleAt(r, instant(t, "2026-03-05T12:00:00-05:00")); ok {
		t.Fatal("day before anchor active")
	}
}

func TestTimezoneMidnightAndDailyDST(t *testing.T) {
	r := rule()
	r.Kind = "daily"
	r.Times = []string{"00:00"}
	c, ok := CycleAt(r, instant(t, "2026-10-02T16:00:00Z"))
	if !ok || c.Key != "2026-10-03" {
		t.Fatalf("wrong local date=%+v %v", c, ok)
	}
	got := Due(r, instant(t, "2026-10-02T15:59:59Z"), instant(t, "2026-10-02T16:00:00Z"))
	if len(got) != 1 {
		t.Fatalf("midnight=%v", got)
	}
	r.Timezone = "America/New_York"
	c, ok = CycleAt(r, instant(t, "2026-03-08T12:00:00-04:00"))
	if !ok || c.End.Sub(c.Start) != 23*time.Hour {
		t.Fatalf("spring DST day=%+v %v", c, ok)
	}
	c, ok = CycleAt(r, instant(t, "2026-11-01T12:00:00-05:00"))
	if !ok || c.End.Sub(c.Start) != 25*time.Hour {
		t.Fatalf("fall DST day=%+v %v", c, ok)
	}
}

func TestDSTMissingClockSlotSkippedAndRepeatedSlotOnce(t *testing.T) {
	r := rule()
	r.Kind = "daily"
	r.Timezone = "America/New_York"
	r.Times = []string{"02:30"}
	got := Due(r, instant(t, "2026-03-08T00:00:00-05:00"), instant(t, "2026-03-09T00:00:00-04:00"))
	if len(got) != 0 {
		t.Fatalf("nonexistent local time must not normalize to wrong clock: %v", got)
	}
	r.Times = []string{"01:30"}
	got = Due(r, instant(t, "2026-11-01T00:00:00-04:00"), instant(t, "2026-11-02T00:00:00-05:00"))
	if len(got) != 1 {
		t.Fatalf("repeated clock slot sent twice: %v", got)
	}
}

func TestRuleBoundsClipCycleAndInvalidRulesAreSafe(t *testing.T) {
	r := rule()
	r.StartDate = "2026-10-03"
	r.EndDate = "2026-10-10"
	c, ok := CycleAt(r, instant(t, "2026-10-05T12:00:00+08:00"))
	if !ok || c.Start.Format("2006-01-02") != "2026-10-03" || c.End.Format("2006-01-02") != "2026-10-11" {
		t.Fatalf("clipped=%+v %v", c, ok)
	}
	r.Timezone = "invalid"
	if _, ok := CycleAt(r, time.Now()); ok {
		t.Fatal("invalid CycleAt")
	}
	if _, ok := Next(r, time.Now()); ok {
		t.Fatal("invalid Next")
	}
	if got := Due(r, time.Now().Add(-time.Hour), time.Now()); len(got) != 0 {
		t.Fatalf("invalid Due=%v", got)
	}
}

func TestSummaryExplainsStopRule(t *testing.T) {
	r := rule()
	want := "每月 1—15 日，每天 09:00，完成后停止本月提醒"
	if got := Summary(r); got != want {
		t.Fatalf("summary=%q want %q", got, want)
	}
	r.Mode = "every_day"
	if !strings.Contains(Summary(r), "当天") {
		t.Fatal("daily completion effect missing")
	}
	r.Mode = "notify_once"
	r.MaxReminders = 2
	if !strings.Contains(Summary(r), "只提醒一次") {
		t.Fatal("notify_once effect missing")
	}
}

func TestLongIntervalAnchorDoesNotOverflowDuration(t *testing.T) {
	r := rule()
	r.Kind = "interval"
	r.StartDate = "1700-01-01"
	r.EveryDays = 7
	anchor, _ := time.Parse("2006-01-02", r.StartDate)
	day, _ := time.Parse("2006-01-02", "2026-10-03")
	days := (day.Unix() - anchor.Unix()) / 86400
	want := day.AddDate(0, 0, int((7-days%7)%7)).Format("2006-01-02")
	got, ok := Next(r, instant(t, "2026-10-03T00:00:00+08:00"))
	if !ok || got.Format("2006-01-02") != want {
		t.Fatalf("long anchored interval=%s, %v; want %s", got, ok, want)
	}
}

func TestYearOneDateIsNotConfusedWithMissingDate(t *testing.T) {
	r := rule()
	r.Kind = "once"
	r.StartDate = "0001-01-01"
	r.Timezone = "UTC"
	if err := Validate(r); err != nil {
		t.Fatalf("explicit year one date: %v", err)
	}
	c, ok := CycleAt(r, instant(t, "0001-01-01T08:00:00Z"))
	if !ok || c.Key != "once:0001-01-01" {
		t.Fatalf("year one cycle=%+v, %v", c, ok)
	}
}

func TestNextFiveYearHorizonAndInclusiveEndDate(t *testing.T) {
	r := rule()
	r.Kind = "daily"
	r.StartDate = "2031-10-04"
	if _, ok := Next(r, instant(t, "2026-10-03T09:00:00+08:00")); ok {
		t.Fatal("next escaped five-year horizon")
	}
	r.StartDate = "2026-10-03"
	r.EndDate = "2026-10-03"
	r.Times = []string{"23:59"}
	got := Due(r, instant(t, "2026-10-03T00:00:00+08:00"), instant(t, "2026-10-04T00:00:00+08:00"))
	if len(got) != 1 || got[0].At.Format("15:04") != "23:59" {
		t.Fatalf("inclusive final day=%v", got)
	}
}

func TestSummaryAllKindsAndInvalidRules(t *testing.T) {
	if Summary(Rule{}) != "提醒规则无效" {
		t.Fatal("invalid summary must not look valid")
	}
	for _, kind := range []string{"once", "daily", "weekly", "date_range", "interval"} {
		r := rule()
		r.Kind = kind
		r.StartDate = "2026-10-03"
		r.EndDate = "2026-10-15"
		r.EveryDays = 2
		r.Weekdays = []int{7, 1}
		r.MaxReminders = 2
		got := Summary(r)
		if got == "提醒规则无效" || !strings.Contains(got, "09:00") || !strings.Contains(got, "最多 2 次") {
			t.Errorf("%s summary=%s", kind, got)
		}
		if r.Weekdays[0] != 7 {
			t.Fatal("Summary mutated caller weekdays")
		}
	}
}

func TestOngoingKeepsOneCompletionCycleForever(t *testing.T) {
	r := rule()
	r.Kind = "ongoing"
	r.StartDate = "2026-10-03"
	if err := Validate(r); err != nil {
		t.Fatalf("ongoing rule: %v", err)
	}
	for _, at := range []string{"2026-10-03T00:00:00+08:00", "2026-10-04T08:00:00+08:00", "2030-01-01T23:59:59+08:00"} {
		c, ok := CycleAt(r, instant(t, at))
		if !ok || c.Key != "ongoing:2026-10-03" || c.Start.Format("2006-01-02") != "2026-10-03" || c.End.Year() != 10000 || c.End.Month() != time.January || c.End.Day() != 1 {
			t.Fatalf("ongoing cycle at %s = %+v, %v", at, c, ok)
		}
	}
	if _, ok := CycleAt(r, instant(t, "2026-10-02T23:59:59+08:00")); ok {
		t.Fatal("ongoing active before start date")
	}
	got := Due(r, instant(t, "2026-10-03T00:00:00+08:00"), instant(t, "2026-10-05T09:00:00+08:00"))
	if len(got) != 3 {
		t.Fatalf("ongoing daily slots=%v", got)
	}
	for _, slot := range got {
		if slot.CycleKey != "ongoing:2026-10-03" {
			t.Fatalf("ongoing reset completion cycle: %+v", slot)
		}
	}
	if got := Summary(r); got != "从 2026-10-03 起，每天 09:00，完成后不再提醒" {
		t.Fatalf("ongoing summary=%s", got)
	}
}

func TestOngoingOptionalDeadlineAndValidation(t *testing.T) {
	r := rule()
	r.Kind = "ongoing"
	r.StartDate = "2026-10-03"
	r.EndDate = "2026-10-05"
	c, ok := CycleAt(r, instant(t, "2026-10-05T23:59:59+08:00"))
	if !ok || c.End.Format("2006-01-02") != "2026-10-06" {
		t.Fatalf("ongoing deadline cycle=%+v, %v", c, ok)
	}
	if _, ok := Next(r, instant(t, "2026-10-05T09:00:00+08:00")); ok {
		t.Fatal("ongoing continued beyond deadline")
	}
	if !strings.Contains(Summary(r), "至 2026-10-05") {
		t.Fatal("ongoing deadline missing from summary")
	}
	for _, mode := range []string{"every_day", "notify_once"} {
		r.Mode = mode
		if Validate(r) == nil {
			t.Errorf("ongoing accepted %s", mode)
		}
	}
	r.Mode = "until_done"
	r.StartDate = ""
	if Validate(r) == nil {
		t.Fatal("ongoing missing start date accepted")
	}
}

func TestCandidateYearsStayWithinFourDigits(t *testing.T) {
	r := rule()
	r.Kind = "daily"
	r.Timezone = "UTC"
	last := instant(t, "9999-12-31T23:59:59Z")
	if got, ok := Next(r, last); ok {
		t.Fatalf("Next produced unencodable RFC3339 year: %s", got)
	}
	if _, ok := CycleAt(r, last.Add(time.Second)); ok {
		t.Fatal("cycle beyond year 9999 accepted")
	}
	if got := Due(r, last, last.Add(24*time.Hour)); len(got) != 0 {
		t.Fatalf("Due produced year 10000 candidates: %v", got)
	}
	r.Kind = "ongoing"
	r.StartDate = "2026-10-03"
	c, ok := CycleAt(r, instant(t, "2026-10-03T08:00:00Z"))
	if !ok {
		t.Fatal("missing ongoing cycle")
	}
	if _, ok := Next(r, c.End.Add(-time.Nanosecond)); ok {
		t.Fatal("completed ongoing cycle should have no next cycle")
	}
}
