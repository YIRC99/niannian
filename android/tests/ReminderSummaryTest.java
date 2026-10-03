package dev.niannian.reminder;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Run with javac/java; no Android runtime is required. */
public final class ReminderSummaryTest {
    private static int checks, failures;

    public static void main(String[] args) {
        Map<String, String> fields = fields("times", "09:00, 20:30", "timezone", "Asia/Shanghai",
                "first_date", "2026-11-01", "first_time", "00:05:37", "repeat_minutes", "5",
                "month_start", "1", "month_end", "15", "every_days", "3");
        List<Integer> days = Arrays.asList(7, 1, 3, 3);

        array(new String[]{"首次提醒", "重复间隔", "完成之后"}, ReminderSummary.labels("minute_interval"), "minute labels");
        for (String kind : new String[]{"once", "daily", "weekly", "monthly_window", "date_range", "interval", "ongoing", "unsupported"})
            array(new String[]{"提醒时间", "重复安排", "完成之后"}, ReminderSummary.labels(kind), kind + " labels");

        array(new String[]{"2026-11-01 00:05:37", "每 5 分钟", "永久停止提醒"},
                ReminderSummary.values("minute_interval", "until_done", fields, days), "minute uses actual absolute time, including seconds");
        array(new String[]{"09:00、20:30", "每天", "停止当天提醒"},
                ReminderSummary.values("daily", "until_done", fields, days), "daily completion is not permanent");
        array(new String[]{"09:00、20:30", "每周一、三、日", "停止本周提醒"},
                ReminderSummary.values("weekly", "until_done", fields, days), "weekly days are sorted and deduplicated");
        array(new String[]{"09:00、20:30", "每月 1–15 日", "停止本月提醒"},
                ReminderSummary.values("monthly_window", "until_done", fields, days), "monthly completion stops this month");
        array(new String[]{"09:00、20:30", "每隔 3 天", "停止当天提醒"},
                ReminderSummary.values("interval", "until_done", fields, days), "interval resumes on a later scheduled day");
        array(new String[]{"09:00、20:30", "只在一天提醒", "停止本次提醒"},
                ReminderSummary.values("once", "until_done", fields, days), "once has one completion cycle");
        array(new String[]{"09:00、20:30", "指定日期内每天", "停止本轮提醒"},
                ReminderSummary.values("date_range", "until_done", fields, days), "date range shares one completion cycle");
        array(new String[]{"09:00、20:30", "每天，直到完成", "永久停止提醒"},
                ReminderSummary.values("ongoing", "until_done", fields, days), "ongoing completion is permanent");
        equal("停止当天提醒，下个提醒日继续", ReminderSummary.values("weekly", "every_day", fields, days)[2], "daily completion resumes on selected days, not necessarily tomorrow");
        equal("停止当天提醒", ReminderSummary.values("once", "every_day", fields, days)[2], "one-day reminder does not promise another day");
        equal("每期只提醒一次，无需完成", ReminderSummary.values("monthly_window", "notify_once", fields, days)[2], "notification-only mode does not require completion");
        equal("只提醒一次，无需完成", ReminderSummary.values("once", "notify_once", fields, days)[2], "one-day notification-only mode");
        array(new String[]{"暂不支持", "请调整重复方式", "请先调整规则"},
                ReminderSummary.values("unsupported", "until_done", fields, days), "unsupported rules do not appear valid");

        Map<String, String> bounded = fields("start_date", "2026-10-03", "end_date", "2026-10-31",
                "max_reminders", "4", "timezone", "America/New_York");
        equal("2026-10-03 至 2026-10-31 · 每期最多 4 次 · 时区 America/New_York",
                ReminderSummary.note("weekly", "until_done", bounded), "bounded rule retains dates, cap, and nonlocal timezone");
        equal("2026-10-03 至 2026-10-31 · 每天最多 4 次 · 时区 America/New_York",
                ReminderSummary.note("monthly_window", "every_day", bounded), "every-day cap is per day");
        equal("至 2026-10-31 当天结束 · 最多 4 次 · 时区 America/New_York",
                ReminderSummary.note("minute_interval", "until_done", bounded), "minute rule ignores stale start date and preserves end day");
        equal("2026-10-03 至 2026-10-31 · 最多 4 次 · 时区 America/New_York",
                ReminderSummary.note("ongoing", "until_done", bounded), "ongoing cap belongs to the whole task");
        equal("2026-10-03 至 2026-10-31 · 时区 America/New_York",
                ReminderSummary.note("weekly", "notify_once", bounded), "notify-once ignores the ineffective cap");
        equal("2026-10-03 起 · 时区 Asia/Shanghai", ReminderSummary.note("daily", "until_done",
                fields("start_date", "2026-10-03", "max_reminders", "0", "timezone", "Asia/Shanghai")), "zero cap is omitted");
        equal("至 2026-10-31 当天结束", ReminderSummary.note("daily", "until_done",
                fields("end_date", "2026-10-31")), "end-only bound includes its last day");
        equal("", ReminderSummary.note("daily", "until_done", Collections.emptyMap()), "empty notes do not invent a timezone");

        array(new String[]{"请选择首次提醒时间", "请填写重复间隔", "永久停止提醒"},
                ReminderSummary.values("minute_interval", "until_done", Collections.emptyMap(), Collections.emptyList()), "empty minute draft stays visibly incomplete");
        array(new String[]{"请选择提醒时间", "请选择提醒星期", "停止本周提醒"},
                ReminderSummary.values("weekly", "until_done", Collections.emptyMap(), Collections.emptyList()), "empty weekly draft stays visibly incomplete");
        equal("09:00、12:30、20:00", ReminderSummary.values("daily", "until_done",
                fields("times", " 09:00， 12:30\n20:00 "), Collections.emptyList())[0], "time separators are normalized");
        equal("请填写每月日期", ReminderSummary.values("monthly_window", "until_done", Collections.emptyMap(), days)[1], "empty monthly draft does not invent defaults");
        equal("请填写间隔天数", ReminderSummary.values("interval", "until_done", Collections.emptyMap(), days)[1], "empty interval draft does not invent defaults");
        equal(Arrays.asList(7, 1, 3, 3), days, "rendering does not mutate caller weekdays");

        if (failures > 0) throw new AssertionError(failures + " of " + checks + " checks failed");
        System.out.println("ReminderSummaryTest: " + checks + " checks passed");
    }

    private static Map<String, String> fields(String... entries) {
        Map<String, String> result = new HashMap<>();
        for (int i = 0; i < entries.length; i += 2) result.put(entries[i], entries[i + 1]);
        return result;
    }

    private static void array(String[] expected, String[] actual, String description) {
        equal(Arrays.asList(expected), Arrays.asList(actual), description);
    }

    private static void equal(Object expected, Object actual, String description) {
        checks++;
        if (!Objects.equals(expected, actual)) {
            failures++;
            System.err.println(description + ": expected " + expected + ", got " + actual);
        }
    }
}
