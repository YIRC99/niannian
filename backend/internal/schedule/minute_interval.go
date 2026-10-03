package schedule

import (
	"errors"
	"regexp"
	"time"
)

// time.Parse alone also accepts a one-digit hour and out-of-range offsets.
var firstAtFormat = regexp.MustCompile(`^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-](?:[01]\d|2[0-3]):[0-5]\d)$`)

func (p *prepared) prepareMinuteInterval() error {
	r := p.r
	if r.Mode != "until_done" {
		return errors.New("分钟间隔提醒仅支持完成后不再提醒")
	}
	if len(r.Times) != 0 || r.StartDate != "" || len(r.Weekdays) != 0 || r.MonthStart != 0 || r.MonthEnd != 0 || r.EveryDays != 0 {
		return errors.New("分钟间隔提醒不能同时设置每日时间、开始日期或其他重复周期")
	}
	if r.RepeatMinutes < 1 || r.RepeatMinutes > 1440 {
		return errors.New("分钟间隔需为 1 到 1440")
	}
	first, err := time.Parse(time.RFC3339Nano, r.FirstAt)
	if err != nil || !firstAtFormat.MatchString(r.FirstAt) || first.Year() < 1 {
		return errors.New("首次时间需为包含秒和时区的 RFC3339 格式")
	}
	p.firstAt = first.In(p.loc)
	if p.firstAt.Year() < 1 || p.firstAt.Year() > 9999 {
		return errors.New("首次时间需在当地公元 1 到 9999 年内")
	}
	if r.EndDate != "" && p.end.Before(civil(p.firstAt)) {
		return errors.New("结束日期不能早于首次提醒日期")
	}
	if r.EndAt != "" {
		if r.EndDate != "" {
			return errors.New("结束日期和精确截止时间只能设置一种")
		}
		end, err := time.Parse(time.RFC3339Nano, r.EndAt)
		if err != nil || !firstAtFormat.MatchString(r.EndAt) || end.Year() < 1 {
			return errors.New("截止时间需为包含秒和时区的 RFC3339 格式")
		}
		p.endAt = end.In(p.loc)
		if p.endAt.Year() < 1 || p.endAt.Year() > 9999 {
			return errors.New("截止时间需在当地公元 1 到 9999 年内")
		}
		if p.endAt.Before(p.firstAt) {
			return errors.New("截止时间不能早于首次提醒时间")
		}
	}
	return nil
}

// minuteIndex returns the elapsed cadence index at or before at. Using Unix
// seconds instead of time.Sub preserves anchors older than 292 years.
func (p prepared) minuteIndex(at time.Time) int64 {
	elapsed := at.Unix() - p.firstAt.Unix()
	if at.Nanosecond() < p.firstAt.Nanosecond() {
		elapsed--
	}
	return elapsed / (int64(p.r.RepeatMinutes) * 60)
}

func (p prepared) minuteAt(index int64) time.Time {
	seconds := p.firstAt.Unix() + index*int64(p.r.RepeatMinutes)*60
	return time.Unix(seconds, int64(p.firstAt.Nanosecond())).In(p.loc)
}

func (p prepared) minuteEnd() time.Time {
	if p.r.EndAt != "" {
		// Cycle ends are exclusive; end_at itself is an eligible instant.
		return p.endAt.Add(time.Nanosecond)
	}
	end := time.Date(10000, time.January, 1, 0, 0, 0, 0, time.UTC)
	if p.r.EndDate != "" {
		end = p.end.AddDate(0, 0, 1)
	}
	return p.localMidnight(end)
}

func (p prepared) minuteNext(after time.Time) (time.Time, bool) {
	if !after.Before(p.minuteEnd()) {
		return time.Time{}, false
	}
	next := p.firstAt
	if !after.Before(next) {
		next = p.minuteAt(p.minuteIndex(after) + 1)
	}
	if !next.Before(p.minuteEnd()) || next.After(after.In(p.loc).AddDate(5, 0, 0)) {
		return time.Time{}, false
	}
	return next, true
}

func (p prepared) minuteDue(from, to, firstDay, lastDay time.Time, limit int) []Occurrence {
	if firstDay.After(lastDay) {
		return nil
	}
	lower := p.localMidnight(firstDay)
	if from.Before(lower) {
		from = lower.Add(-time.Nanosecond)
	}
	upper := p.localMidnight(lastDay.AddDate(0, 0, 1))
	if end := p.minuteEnd(); end.Before(upper) {
		upper = end
	}
	if !to.Before(upper) {
		to = upper.Add(-time.Nanosecond)
	}
	if !to.After(from) || to.Before(p.firstAt) {
		return nil
	}
	start := int64(0)
	if !from.Before(p.firstAt) {
		start = p.minuteIndex(from) + 1
	}
	end := p.minuteIndex(to)
	if limit > 0 && end-start+1 > int64(limit) {
		end = start + int64(limit) - 1
	}
	if end < start {
		return nil
	}
	result := make([]Occurrence, 0, end-start+1)
	for i := start; i <= end; i++ {
		result = append(result, Occurrence{At: p.minuteAt(i), CycleKey: "minute_interval"})
	}
	return result
}
