package dev.niannian.reminder;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Presents the current editor fields without changing or validating the draft. */
final class ReminderSummary {
    private ReminderSummary() {}

    static String leadTime(String kind, Map<String, String> fields) {
        String time = values(kind, "until_done", fields, null)[0];
        if ("once".equals(kind)) {
            String date = field(fields, "start_date");
            return date.isEmpty() ? "请选择提醒日期" : date + " · " + time;
        }
        return time;
    }

    static String followUp(String kind, String mode) {
        if ("notify_once".equals(mode) && !"minute_interval".equals(kind) && !"ongoing".equals(kind))
            return "once".equals(kind) ? "仅提醒一次，不再催办。" : "每期只提醒一次，不再催办。";
        return "保存时确定跟进流程，未完成才逐步换用已授权方式；每次只安排下一条，遵守静默时段与提醒上限。";
    }

    static String[] labels(String kind) {
        return "minute_interval".equals(kind)
                ? new String[]{"首次提醒", "重复间隔", "完成之后"}
                : new String[]{"提醒时间", "重复安排", "完成之后"};
    }

    static String[] values(String kind, String mode, Map<String, String> fields, List<Integer> weekdays) {
        if ("minute_interval".equals(kind)) {
            String date = field(fields, "first_date"), time = field(fields, "first_time");
            String first = date.isEmpty() || time.isEmpty() ? "请选择首次提醒时间" : date + " " + time;
            String minutes = field(fields, "repeat_minutes");
            return new String[]{first, minutes.isEmpty() ? "请填写重复间隔" : "每 " + minutes + " 分钟", "永久停止提醒"};
        }
        String schedule;
        switch (kind == null ? "" : kind) {
            case "once": schedule = "只在一天提醒"; break;
            case "daily": schedule = "每天"; break;
            case "weekly": schedule = weekly(weekdays); break;
            case "monthly_window":
                String start = field(fields, "month_start"), end = field(fields, "month_end");
                schedule = start.isEmpty() || end.isEmpty() ? "请填写每月日期" : "每月 " + start + "–" + end + " 日";
                break;
            case "date_range": schedule = "指定日期内每天"; break;
            case "interval":
                String days = field(fields, "every_days");
                schedule = days.isEmpty() ? "请填写间隔天数" : "每隔 " + days + " 天";
                break;
            case "ongoing": schedule = "每天，直到完成"; break;
            default: return new String[]{"暂不支持", "请调整重复方式", "请先调整规则"};
        }
        String times = field(fields, "times").replaceAll("[,，\\s]+", "、").replaceAll("^、|、$", "");
        return new String[]{times.isEmpty() ? "请选择提醒时间" : times, schedule, completion(kind, mode)};
    }

    static String note(String kind, String mode, Map<String, String> fields) {
        List<String> notes = new ArrayList<>();
        String start = "minute_interval".equals(kind) ? "" : field(fields, "start_date");
        String end = field(fields, "end_date");
        String endAt = "minute_interval".equals(kind) ? field(fields, "end_at") : "";
        if (!endAt.isEmpty()) {
            try {
                ZoneId zone = ZoneId.of(field(fields, "timezone"));
                notes.add("截止 " + MinuteIntervalForm.date(endAt, zone) + " " + MinuteIntervalForm.time(endAt, zone) + "，之后停止提醒");
            } catch (IllegalArgumentException invalid) {
                notes.add("截止时间待调整");
            } catch (java.time.DateTimeException invalid) {
                notes.add("截止时间待调整");
            }
        } else if (!start.isEmpty() && !end.isEmpty()) notes.add(start + " 至 " + end);
        else if (!start.isEmpty()) notes.add(start + " 起");
        else if (!end.isEmpty()) notes.add("至 " + end + " 当天结束");

        String cap = field(fields, "max_reminders");
        boolean hasCap;
        try { hasCap = Integer.parseInt(cap) > 0; } catch (NumberFormatException invalid) { hasCap = false; }
        // notify_once already limits each cycle to one notification; its cap is ignored by the scheduler.
        if (hasCap && !"notify_once".equals(mode)) {
            String scope = "minute_interval".equals(kind) || "ongoing".equals(kind) || "once".equals(kind) ? ""
                    : "every_day".equals(mode) || "daily".equals(kind) || "interval".equals(kind) ? "每天" : "每期";
            notes.add(scope + "最多 " + cap + " 次");
        }
        String timezone = field(fields, "timezone");
        if (!timezone.isEmpty()) notes.add("时区 " + timezone);
        return String.join(" · ", notes);
    }

    private static String completion(String kind, String mode) {
        if ("ongoing".equals(kind)) return "永久停止提醒";
        if ("notify_once".equals(mode)) return "once".equals(kind) ? "只提醒一次，无需完成" : "每期只提醒一次，无需完成";
        if ("every_day".equals(mode)) return "once".equals(kind) ? "停止当天提醒" : "停止当天提醒，下个提醒日继续";
        if (!"until_done".equals(mode)) return "请选择完成条件";
        switch (kind) {
            case "once": return "停止本次提醒";
            case "weekly": return "停止本周提醒";
            case "monthly_window": return "停止本月提醒";
            case "date_range": return "停止本轮提醒";
            default: return "停止当天提醒";
        }
    }

    private static String weekly(List<Integer> weekdays) {
        TreeSet<Integer> chosen = new TreeSet<>();
        if (weekdays != null) for (Integer day : weekdays) if (day != null && day >= 1 && day <= 7) chosen.add(day);
        if (chosen.isEmpty()) return "请选择提醒星期";
        String[] names = {"一", "二", "三", "四", "五", "六", "日"};
        List<String> labels = new ArrayList<>();
        for (int day : chosen) labels.add(names[day - 1]);
        return "每周" + String.join("、", labels);
    }

    private static String field(Map<String, String> fields, String key) {
        String value = fields == null ? null : fields.get(key);
        return value == null ? "" : value.trim();
    }
}
