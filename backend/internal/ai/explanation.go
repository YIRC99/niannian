package ai

import (
	"errors"
	"fmt"
	"strings"
	"time"

	"niannian/internal/schedule"
)

// These are private model decisions, not new public API fields. The model
// identifies what it assumed or could not express; final time/completion prose
// is always rendered from the validated final rule, never copied from a draft.
type draftExplanation struct {
	Assumptions []string `json:"assumptions"`
	Limitations []string `json:"limitations"`
}

func (e *draftExplanation) validate(rule schedule.Rule) error {
	if e == nil || e.Assumptions == nil || e.Limitations == nil {
		return errors.New("缺少explanation及其assumptions/limitations数组")
	}
	allowed := []map[string]bool{
		{"first_time": true, "repeat_interval": true, "year": true, "rescheduled": true, "task": true},
		{"recurrence": true, "holiday_calendar": true, "unverified_date": true, "complex_cadence": true},
	}
	for i, list := range [][]string{e.Assumptions, e.Limitations} {
		seen := map[string]bool{}
		for _, tag := range list {
			if !allowed[i][tag] || seen[tag] {
				return errors.New("explanation标签未知或重复")
			}
			seen[tag] = true
			if tag == "recurrence" && rule.Kind != "date_range" && rule.Kind != "once" && rule.Kind != "minute_interval" {
				return errors.New("无法自动重复的单轮替代必须使用date_range、once或minute_interval")
			}
		}
	}
	return nil
}

func (e draftExplanation) warnings(rule schedule.Rule, now time.Time) []string {
	next, _ := schedule.Next(rule, now) // Already checked in decodeDraft.
	state := ""
	if rule.Kind == "minute_interval" && rule.EndAt != "" {
		first, _ := time.Parse(time.RFC3339, rule.FirstAt)
		if now.Before(first) {
			state = "窗口尚未开始。"
		} else {
			state = "窗口已经开始，只安排余下时段，保留原起点的节奏，不补发全部历史提醒。"
		}
	}
	warnings := []string{fmt.Sprintf("%s%s。按当前时间计算，下一个规则时间点是 %s（%s）；确认后才会创建。", state, schedule.Summary(rule), next.Format("2006-01-02 15:04:05"), rule.Timezone)}
	if rule.Kind == "monthly_window" && rule.MonthEnd > 28 {
		warnings = append(warnings, "短月按当月最后一天截断，不会顺延到下个月。")
	}
	var assumed []string
	rescheduled := false
	for _, tag := range e.Assumptions {
		switch tag {
		case "first_time":
			assumed = append(assumed, "首次时间")
		case "repeat_interval":
			assumed = append(assumed, "重复间隔")
		case "year":
			assumed = append(assumed, "年份")
		case "task":
			assumed = append(assumed, "待办事项")
		case "rescheduled":
			rescheduled = true
		}
	}
	if len(assumed) > 0 {
		warnings = append(warnings, "你没有明确指定"+strings.Join(assumed, "、")+"，以上为暂定建议，可在草稿中调整。")
	}
	if rescheduled {
		warnings = append(warnings, "原先要求的时间已经过去，以上是建议调整后的安排，请核对新日期与时刻。")
	}
	var limits []string
	for _, tag := range e.Limitations {
		switch tag {
		case "recurrence":
			limits = append(limits, "原来的自动重复条件暂时不能完整表达，这次只安排上述这一轮，不会自动生成下一轮")
		case "holiday_calendar":
			limits = append(limits, "本次按上述规则的日期执行，不能自动跳过法定节假日或识别调休")
		case "unverified_date":
			limits = append(limits, "农历或其他特殊日期尚需核对，本次只先安排核对日期的待办，不代表原事件日期已经确认")
		case "complex_cadence":
			limits = append(limits, "原来复杂的不等间隔暂时不能完整表达，本次采用上面的替代节奏")
		}
	}
	if len(limits) > 0 {
		warnings = append(warnings, strings.Join(limits, "；")+"。请核对这些替代条件后再确认。")
	}
	return warnings
}
