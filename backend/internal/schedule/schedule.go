// Package schedule evaluates reminder rules using the rule's local calendar.
// It produces candidate slots; completion and successful-send caps belong to
// the durable scheduler so a failed email does not consume a reminder.
package schedule

import (
	"errors"
	"fmt"
	"sort"
	"strconv"
	"strings"
	"time"
	_ "time/tzdata" // The Linux binary must not depend on host zoneinfo files.
)

type Rule struct {
	Kind         string   `json:"kind"`
	Mode         string   `json:"mode"`
	Timezone     string   `json:"timezone"`
	Times        []string `json:"times"`
	StartDate    string   `json:"start_date"`
	EndDate      string   `json:"end_date"`
	Weekdays     []int    `json:"weekdays"`
	MonthStart   int      `json:"month_start"`
	MonthEnd     int      `json:"month_end"`
	EveryDays    int      `json:"every_days"`
	MaxReminders int      `json:"max_reminders"`
}

// Cycle contains its start and excludes its end. Keys do not depend on the
// notification clock times, so changing a clock time preserves completion.
type Cycle struct {
	Key        string
	Start, End time.Time
}
type Occurrence struct {
	At       time.Time
	CycleKey string
}

type prepared struct {
	r       Rule
	loc     *time.Location
	minutes []int
	// Dates are UTC calendar labels, never elapsed-time instants. This keeps
	// interval day arithmetic correct across daylight saving transitions.
	start, end time.Time
}

func Validate(r Rule) error { _, err := prepare(r); return err }

func prepare(r Rule) (prepared, error) {
	p := prepared{r: r}
	switch r.Kind {
	case "once", "daily", "weekly", "monthly_window", "date_range", "interval", "ongoing":
	default:
		return p, errors.New("不支持的提醒类型")
	}
	switch r.Mode {
	case "until_done", "every_day", "notify_once":
	default:
		return p, errors.New("不支持的完成方式")
	}
	if r.Timezone == "" || r.Timezone == "Local" {
		return p, errors.New("请设置明确的时区，例如 Asia/Shanghai")
	}
	var err error
	p.loc, err = time.LoadLocation(r.Timezone)
	if err != nil {
		return p, errors.New("时区无效")
	}
	if len(r.Times) < 1 || len(r.Times) > 8 {
		return p, errors.New("每天需设置 1 到 8 个提醒时间")
	}
	seenTimes := make(map[string]bool, len(r.Times))
	for _, clock := range r.Times {
		if len(clock) != 5 || clock[2] != ':' || seenTimes[clock] {
			return p, errors.New("提醒时间需为不重复的 HH:mm 格式")
		}
		for _, i := range []int{0, 1, 3, 4} {
			if clock[i] < '0' || clock[i] > '9' {
				return p, errors.New("提醒时间格式无效")
			}
		}
		h, _ := strconv.Atoi(clock[:2])
		m, _ := strconv.Atoi(clock[3:])
		if h > 23 || m > 59 {
			return p, errors.New("提醒时间超出有效范围")
		}
		seenTimes[clock] = true
		p.minutes = append(p.minutes, h*60+m)
	}
	sort.Ints(p.minutes)
	for _, field := range []struct {
		value string
		out   *time.Time
	}{{r.StartDate, &p.start}, {r.EndDate, &p.end}} {
		if field.value == "" {
			continue
		}
		d, err := time.Parse("2006-01-02", field.value)
		if err != nil || d.Year() < 1 || d.Format("2006-01-02") != field.value {
			return p, errors.New("日期需为有效的 YYYY-MM-DD 格式")
		}
		*field.out = d
	}
	if r.StartDate != "" && r.EndDate != "" && p.end.Before(p.start) {
		return p, errors.New("结束日期不能早于开始日期")
	}
	if r.MaxReminders < 0 || r.MaxReminders > 100 {
		return p, errors.New("每周期提醒上限需为 0 到 100，0 表示不限")
	}
	seenDays := map[int]bool{}
	for _, day := range r.Weekdays {
		if day < 1 || day > 7 || seenDays[day] {
			return p, errors.New("星期需为不重复的 1 到 7")
		}
		seenDays[day] = true
	}
	switch r.Kind {
	case "ongoing":
		if r.StartDate == "" {
			return p, errors.New("持续提醒需设置开始日期")
		}
		if r.Mode != "until_done" {
			return p, errors.New("持续提醒仅支持完成后不再提醒")
		}
	case "once":
		if r.StartDate == "" {
			return p, errors.New("单次提醒需设置日期")
		}
	case "date_range":
		if r.StartDate == "" || r.EndDate == "" {
			return p, errors.New("日期范围需同时设置开始和结束日期")
		}
	case "weekly":
		if len(r.Weekdays) == 0 {
			return p, errors.New("每周提醒需至少选择一天")
		}
	case "monthly_window":
		if r.MonthStart < 1 || r.MonthEnd > 31 || r.MonthStart > r.MonthEnd {
			return p, errors.New("每月范围需在 1 到 31 日内且起始日不能晚于结束日")
		}
	case "interval":
		if r.StartDate == "" || r.EveryDays < 1 || r.EveryDays > 365 {
			return p, errors.New("间隔提醒需设置开始日期和 1 到 365 天的间隔")
		}
	}
	return p, nil
}

func civil(t time.Time) time.Time {
	y, m, d := t.Date()
	return time.Date(y, m, d, 0, 0, 0, 0, time.UTC)
}

func (p prepared) localMidnight(day time.Time) time.Time {
	y, m, d := day.Date()
	v := time.Date(y, m, d, 0, 0, 0, 0, p.loc)
	// Some zones move clocks at midnight. Date can normalize a missing
	// midnight backwards; move to the first representable local day instant.
	for civil(v).Before(day) {
		v = v.Add(time.Minute)
	}
	return v
}

func (p prepared) withinDates(day time.Time) bool {
	return (p.r.StartDate == "" || !day.Before(p.start)) && (p.r.EndDate == "" || !day.After(p.end))
}

func (p prepared) weekdaySelected(day time.Time) bool {
	d := int(day.Weekday())
	if d == 0 {
		d = 7
	}
	for _, selected := range p.r.Weekdays {
		if d == selected {
			return true
		}
	}
	return false
}

func (p prepared) cycle(day time.Time) (Cycle, bool) {
	if day.Year() < 1 || day.Year() > 9999 || !p.withinDates(day) {
		return Cycle{}, false
	}
	r := p.r
	start, end := day, day.AddDate(0, 0, 1)
	key := day.Format("2006-01-02")
	switch r.Kind {
	case "ongoing":
		start = p.start
		end = time.Date(10000, time.January, 1, 0, 0, 0, 0, time.UTC)
		if r.EndDate != "" {
			end = p.end.AddDate(0, 0, 1)
		}
		key = "ongoing:" + r.StartDate
	case "once":
		if !day.Equal(p.start) {
			return Cycle{}, false
		}
		key = "once:" + r.StartDate
	case "weekly":
		if r.Mode == "every_day" {
			if !p.weekdaySelected(day) {
				return Cycle{}, false
			}
		} else {
			weekday := int(day.Weekday())
			if weekday == 0 {
				weekday = 7
			}
			start = day.AddDate(0, 0, 1-weekday)
			end = start.AddDate(0, 0, 7)
			year, week := day.ISOWeek()
			key = fmt.Sprintf("%04d-W%02d", year, week)
		}
	case "monthly_window":
		last := time.Date(day.Year(), day.Month()+1, 0, 0, 0, 0, 0, time.UTC).Day()
		firstDay, lastDay := min(r.MonthStart, last), min(r.MonthEnd, last)
		if day.Day() < firstDay || day.Day() > lastDay {
			return Cycle{}, false
		}
		start = time.Date(day.Year(), day.Month(), firstDay, 0, 0, 0, 0, time.UTC)
		end = time.Date(day.Year(), day.Month(), lastDay+1, 0, 0, 0, 0, time.UTC)
		key = day.Format("2006-01")
	case "date_range":
		start = p.start
		end = p.end.AddDate(0, 0, 1)
		key = "range:" + r.StartDate + ":" + r.EndDate
	case "interval":
		// These are UTC date labels, so every civil day is exactly 24 hours.
		elapsedDays := int((day.Unix() - p.start.Unix()) / 86400)
		if elapsedDays%r.EveryDays != 0 {
			return Cycle{}, false
		}
		key = "interval:" + key
	}
	if r.Mode == "every_day" {
		start = day
		end = day.AddDate(0, 0, 1)
		key = day.Format("2006-01-02")
	}
	if r.StartDate != "" && start.Before(p.start) {
		start = p.start
	}
	if r.EndDate != "" && end.After(p.end.AddDate(0, 0, 1)) {
		end = p.end.AddDate(0, 0, 1)
	}
	return Cycle{Key: key, Start: p.localMidnight(start), End: p.localMidnight(end)}, true
}

// CycleAt includes the active period before its first notification of the day.
func CycleAt(r Rule, at time.Time) (Cycle, bool) {
	p, err := prepare(r)
	if err != nil {
		return Cycle{}, false
	}
	return p.cycle(civil(at.In(p.loc)))
}

func (p prepared) slots(day time.Time) []Occurrence {
	c, ok := p.cycle(day)
	if !ok || (p.r.Kind == "weekly" && !p.weekdaySelected(day)) {
		return nil
	}
	result := make([]Occurrence, 0, len(p.minutes))
	for _, minute := range p.minutes {
		at := time.Date(day.Year(), day.Month(), day.Day(), minute/60, minute%60, 0, 0, p.loc)
		// Skip nonexistent wall-clock times on the DST spring transition.
		// A repeated fall clock time produces exactly one deterministic slot.
		if !civil(at).Equal(day) || at.Hour() != minute/60 || at.Minute() != minute%60 {
			continue
		}
		result = append(result, Occurrence{At: at, CycleKey: c.Key})
	}
	sort.Slice(result, func(i, j int) bool { return result[i].At.Before(result[j].At) })
	return result
}

// Due returns candidate slots in (from,to]. To bound work for invalid caller
// ranges, it examines at most the latest five calendar years ending at to.
// Backend catch-up should use only the latest due slot from the current day.
func Due(r Rule, from, to time.Time) []Occurrence {
	if !to.After(from) {
		return nil
	}
	p, err := prepare(r)
	if err != nil {
		return nil
	}
	first, last := civil(from.In(p.loc)), civil(to.In(p.loc))
	if first.Year() > 9999 || last.Year() < 1 {
		return nil
	}
	if last.Year() > 9999 {
		last = time.Date(9999, time.December, 31, 0, 0, 0, 0, time.UTC)
	}
	if first.Year() < 1 {
		first = time.Date(1, time.January, 1, 0, 0, 0, 0, time.UTC)
	}
	if limit := last.AddDate(-5, 0, 0); first.Before(limit) {
		first = limit
	}
	if r.StartDate != "" && first.Before(p.start) {
		first = p.start
	}
	if r.EndDate != "" && last.After(p.end) {
		last = p.end
	}
	if r.Kind == "once" && last.After(p.start) {
		last = p.start
	}
	var result []Occurrence
	for day := first; !day.After(last); day = day.AddDate(0, 0, 1) {
		for _, slot := range p.slots(day) {
			if slot.At.After(from) && !slot.At.After(to) {
				result = append(result, slot)
			}
		}
	}
	return result
}

// Next returns the first candidate strictly after after within five years.
// It does not know whether a cycle has already been completed or notified.
func Next(r Rule, after time.Time) (time.Time, bool) {
	p, err := prepare(r)
	if err != nil {
		return time.Time{}, false
	}
	first := civil(after.In(p.loc))
	if first.Year() > 9999 {
		return time.Time{}, false
	}
	horizon := after.In(p.loc).AddDate(5, 0, 0)
	last := civil(horizon)
	if last.Year() > 9999 {
		last = time.Date(9999, time.December, 31, 0, 0, 0, 0, time.UTC)
	}
	if first.Year() < 1 {
		first = time.Date(1, time.January, 1, 0, 0, 0, 0, time.UTC)
	}
	if r.StartDate != "" && first.Before(p.start) {
		first = p.start
	}
	if r.EndDate != "" && last.After(p.end) {
		last = p.end
	}
	if r.Kind == "once" && last.After(p.start) {
		last = p.start
	}
	for day := first; !day.After(last); day = day.AddDate(0, 0, 1) {
		for _, slot := range p.slots(day) {
			if slot.At.After(after) && !slot.At.After(horizon) {
				return slot.At, true
			}
		}
	}
	return time.Time{}, false
}

func Summary(r Rule) string {
	p, err := prepare(r)
	if err != nil {
		return "提醒规则无效"
	}
	times := make([]string, 0, len(p.minutes))
	for _, minute := range p.minutes {
		times = append(times, fmt.Sprintf("%02d:%02d", minute/60, minute%60))
	}
	clock := strings.Join(times, "、")
	var text, cycle string
	switch r.Kind {
	case "ongoing":
		text = "从 " + r.StartDate + " 起，每天 " + clock
	case "once":
		text = r.StartDate + " " + clock
		cycle = "本次"
	case "daily":
		text = "每天 " + clock
		cycle = "当天"
	case "weekly":
		days := append([]int(nil), r.Weekdays...)
		sort.Ints(days)
		names := []string{"", "一", "二", "三", "四", "五", "六", "日"}
		labels := make([]string, 0, len(days))
		for _, d := range days {
			labels = append(labels, "周"+names[d])
		}
		text = "每" + strings.Join(labels, "、") + " " + clock
		cycle = "本周"
	case "monthly_window":
		text = fmt.Sprintf("每月 %d—%d 日，每天 %s", r.MonthStart, r.MonthEnd, clock)
		cycle = "本月"
	case "date_range":
		text = r.StartDate + " 至 " + r.EndDate + "，每天 " + clock
		cycle = "本轮"
	case "interval":
		text = fmt.Sprintf("从 %s 起，每 %d 天 %s", r.StartDate, r.EveryDays, clock)
		cycle = "当天"
	}
	switch r.Mode {
	case "until_done":
		if r.Kind == "ongoing" {
			text += "，完成后不再提醒"
		} else {
			text += "，完成后停止" + cycle + "提醒"
		}
	case "every_day":
		text += "，完成后停止当天提醒，下一提醒日重新开始"
	case "notify_once":
		text += "，每周期只提醒一次"
	}
	if r.MaxReminders > 0 && r.Mode != "notify_once" {
		text += fmt.Sprintf("，每周期最多 %d 次", r.MaxReminders)
	}
	if r.Kind != "once" && r.Kind != "date_range" {
		if r.StartDate != "" && r.Kind != "interval" && r.Kind != "ongoing" {
			text += "，" + r.StartDate + " 起"
		}
		if r.EndDate != "" {
			text += "，至 " + r.EndDate
		}
	}
	return text
}
