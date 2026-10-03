package schedule

import (
	"encoding/json"
	"fmt"
	"strings"
	"testing"
	"time"
)

func minuteEndRule(t *testing.T, first, end string, repeat int) Rule {
	t.Helper()
	var r Rule
	if err := json.Unmarshal([]byte(fmt.Sprintf(`{"kind":"minute_interval","mode":"until_done","timezone":"Asia/Shanghai","first_at":%q,"end_at":%q,"repeat_minutes":%d}`, first, end, repeat)), &r); err != nil {
		t.Fatal(err)
	}
	return r
}

func TestMinuteEndAtValidation(t *testing.T) {
	const first = "2026-10-03T14:00:00+08:00"
	for _, end := range []string{first, "2026-10-03T09:00:00Z", "2026-10-04T17:00:00.123+08:00"} {
		if err := Validate(minuteEndRule(t, first, end, 5)); err != nil {
			t.Errorf("valid end_at %s: %v", end, err)
		}
	}
	for _, end := range []string{"2026-10-03T13:59:59+08:00", "2026-10-03", "2026-10-03T17:00+08:00", "2026-10-03T17:00:00", "2026-10-03T9:00:00Z", "2026-10-03T17:00:00+24:00", "2026-10-03T17:00:00+00:60", "0000-10-03T17:00:00Z", "9999-12-31T23:59:59Z"} {
		t.Run(end, func(t *testing.T) {
			if Validate(minuteEndRule(t, first, end, 5)) == nil {
				t.Fatal("invalid end_at accepted")
			}
		})
	}
	r := minuteEndRule(t, first, "2026-10-03T17:00:00+08:00", 5)
	r.EndDate = "2026-10-03"
	if Validate(r) == nil {
		t.Fatal("both date and precise deadline accepted")
	}
	var ordinary Rule
	if err := json.Unmarshal([]byte(`{"kind":"daily","mode":"until_done","timezone":"Asia/Shanghai","times":["09:00"],"end_at":"2026-10-03T17:00:00+08:00"}`), &ordinary); err != nil {
		t.Fatal(err)
	}
	if Validate(ordinary) == nil {
		t.Fatal("ordinary rule accepted end_at")
	}
	encoded, err := json.Marshal(rule())
	if err != nil || strings.Contains(string(encoded), "end_at") {
		t.Fatalf("ordinary rule JSON changed: %s, %v", encoded, err)
	}
}

func TestMinuteEndAtInclusiveBoundaryAcrossMidnightAndTimezone(t *testing.T) {
	const first = "2026-10-31T23:58:37.123+08:00"
	const end = "2026-10-31T16:08:37.123Z"
	r := minuteEndRule(t, first, end, 5)
	deadline := instant(t, end)
	if next, ok := Next(r, deadline.Add(-time.Nanosecond)); !ok || !next.Equal(deadline) {
		t.Fatalf("inclusive deadline slot missing: %s, %v", next, ok)
	}
	for _, after := range []time.Time{deadline, deadline.Add(time.Nanosecond), deadline.Add(time.Hour)} {
		if next, ok := Next(r, after); ok {
			t.Errorf("next escaped deadline: %s", next)
		}
	}
	from := instant(t, first).Add(-time.Nanosecond)
	for _, slots := range [][]Occurrence{Due(r, from, deadline.Add(time.Hour)), DueLimited(r, from, deadline.Add(time.Hour), 10)} {
		if len(slots) != 3 || !slots[2].At.Equal(deadline) {
			t.Errorf("bounded due slots: %+v", slots)
		}
	}
	if slots := Due(r, deadline, deadline.Add(time.Hour)); len(slots) != 0 {
		t.Fatalf("due after deadline: %+v", slots)
	}
	if latest, ok := LatestDue(r, from, deadline.Add(time.Hour)); !ok || !latest.At.Equal(deadline) {
		t.Fatalf("latest escaped deadline: %+v, %v", latest, ok)
	}
	for _, at := range []time.Time{from.Add(-time.Hour), deadline} {
		cycle, ok := CycleAt(r, at)
		if !ok || cycle.Key != "minute_interval" || !cycle.End.Equal(deadline.Add(time.Nanosecond)) {
			t.Errorf("waiting period and exact deadline must share one cycle: %+v, %v", cycle, ok)
		}
	}
	if _, ok := CycleAt(r, deadline.Add(time.Nanosecond)); ok {
		t.Fatal("cycle remained active after precise deadline")
	}
	if got := Summary(r); !strings.Contains(got, "2026-11-01 00:08:37.123") || !strings.Contains(got, "完成后不再提醒") {
		t.Fatalf("summary omitted localized deadline or completion: %s", got)
	}
}

func TestMinuteEndAtEqualFirstProducesOneCandidate(t *testing.T) {
	const at = "2026-10-03T14:00:00+08:00"
	r := minuteEndRule(t, at, at, 5)
	first := instant(t, at)
	if slots := Due(r, first.Add(-time.Second), first.Add(time.Hour)); len(slots) != 1 || !slots[0].At.Equal(first) {
		t.Fatalf("single instant window: %+v", slots)
	}
}
