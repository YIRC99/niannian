package dev.niannian.reminder;

import java.time.Instant;

public final class ReminderPolicyTest {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        long morning = Instant.parse("2026-10-07T01:00:00Z").toEpochMilli();
        yes(ReminderPolicy.needsLegacyQuietMigration(true, true, "22:00", "08:00"), "configured active old-server settings need migration");
        yes(!ReminderPolicy.needsLegacyQuietMigration(false, true, "22:00", "08:00"), "unconfigured defaults never become saved settings");
        yes(!ReminderPolicy.needsLegacyQuietMigration(true, false, "22:00", "08:00"), "another active device prevents settings migration");
        yes(!ReminderPolicy.needsLegacyQuietMigration(true, true, "", ""), "new-server cleared settings need no write");
        yes(ReminderPolicy.needsLegacyQuietMigration(true, true, "22:00", ""), "partial legacy setting is removed too");
        yes(ReminderPolicy.needsLegacyQuietMigration(true, true, "00:00", "00:00"), "equal legacy settings are cleared instead of retained");
        yes(ReminderPolicy.blocksSettingsMigration(morning, morning, morning + 300000, "scheduled"), "unacknowledged due plan blocks generation reset");
        yes(ReminderPolicy.blocksSettingsMigration(morning, morning - 1, morning + 300000, "pending"), "already due plan blocks migration");
        yes(ReminderPolicy.blocksSettingsMigration(morning, morning + 60000, morning + 300000, "scheduled"), "about-to-fire plan has a network race guard");
        yes(!ReminderPolicy.blocksSettingsMigration(morning, morning + 60001, morning + 300000, "scheduled"), "future plan outside race guard can be replaced");
        yes(!ReminderPolicy.blocksSettingsMigration(morning, morning - 300000, morning, "scheduled"), "expired window no longer blocks migration");
        for(String terminal : new String[]{"delivered", "accepted", "failed", "cancelled", "sent"})
            yes(!ReminderPolicy.blocksSettingsMigration(morning, morning, morning + 300000, terminal), "confirmed " + terminal + " does not block migration");
        yes(ReminderPolicy.blocksSettingsMigration(morning, morning, morning + 300000, "sending"), "unknown in-flight outcome is not assumed terminal");
        long breakfast = Instant.parse("2026-10-08T06:22:55+08:00").toEpochMilli();
        long bedtime = Instant.parse("2026-10-08T23:30:00+08:00").toEpochMilli();
        yes(ReminderPolicy.due(breakfast, breakfast, breakfast + 300000, 0, 5, 0, 0), "explicit early morning reminder executes at its scheduled time");
        yes(ReminderPolicy.due(bedtime, bedtime, bedtime + 300000, 0, 5, 0, 0), "explicit late night reminder executes at its scheduled time");
        yes(!ReminderPolicy.due(breakfast, breakfast, breakfast + 300000, breakfast - 60000, 5, 0, 0), "early morning reminders still obey minimum spacing");
        yes(ReminderPolicy.due(morning, morning, morning + 60000, 0, 5, 0, 0), "fresh due plan admitted");
        yes(!ReminderPolicy.due(morning, morning + 5000, morning + 60000, 0, 5, 0, 0), "early broadcast rejected");
        yes(!ReminderPolicy.due(morning, morning - 60000, morning, 0, 5, 0, 0), "expiry exclusive");
        yes(!ReminderPolicy.due(morning, morning, morning + 60000, morning - 299999, 5, 0, 0), "minimum gap applies across channels");
        yes(ReminderPolicy.due(morning, morning, morning + 60000, morning - 300000, 5, 0, 0), "minimum gap exact boundary");
        yes(ReminderPolicy.due(morning, morning, morning + 60000, morning - 60000, 1, 0, 0), "one minute gap is supported");
        yes(!ReminderPolicy.due(morning, morning, morning + 60000, morning - 59999, 1, 0, 0), "one minute exact lower boundary");
        yes(ReminderPolicy.due(morning, morning, morning + 60000, morning - 120000, 2, 0, 0), "two minute gap is supported");
        yes(!ReminderPolicy.due(morning, morning, morning + 60000, morning - 119999, 2, 0, 0), "saved two minute setting remains effective");
        yes(!ReminderPolicy.due(morning, morning, morning + 60000, morning - 59999, 0, 0, 0), "invalid gap cannot bypass one minute minimum");
        yes(!ReminderPolicy.due(morning, morning, morning + 60000, 0, 5, 32, 0), "per task daily cap");
        yes(!ReminderPolicy.due(morning, morning, morning + 60000, 0, 5, 0, 128), "global daily cap");
        yes(ReminderPolicy.scope("https://a.example", "A@EXAMPLE.COM").equals(ReminderPolicy.scope("https://a.example", " a@example.com ")), "email canonicalized");
        yes(!ReminderPolicy.scope("https://a.example", "a@example.com").equals(ReminderPolicy.scope("https://b.example", "a@example.com")), "server isolation");
        yes(!ReminderPolicy.scope("https://a.example", "a@example.com").equals(ReminderPolicy.scope("https://a.example", "b@example.com")), "account isolation");
        yes(ReminderPolicy.scope("", "a@example.com").isEmpty(), "blank scope disabled");
        yes(ReminderPolicy.timestamp("2026-10-07T09:00:00+08:00") == morning, "offset timestamp preserved");
        yes(ReminderPolicy.timestamp("2026-10-07T09:00:00") == 0, "unqualified local timestamp rejected");
        yes(!ReminderPolicy.fresh(3, "a", 4, "a"), "generation suppresses stale response");
        yes(!ReminderPolicy.fresh(3, "a", 3, "b"), "scope suppresses stale response");
        yes(ReminderPolicy.fresh(3, "a", 3, "a"), "current response accepted");
        String calendar = ReminderPolicy.calendarMarker("account-a", "task-a", morning, "Asia/Shanghai");
        yes(calendar.equals(ReminderPolicy.calendarMarker("account-a", "task-a", morning + 60000, "Asia/Shanghai")), "same task and local day reuse a calendar event");
        yes(!calendar.equals(ReminderPolicy.calendarMarker("account-a", "task-a", morning + 86400000L, "Asia/Shanghai")), "different local days keep distinct events");
        yes(!calendar.equals(ReminderPolicy.calendarMarker("account-b", "task-a", morning, "Asia/Shanghai")), "calendar accounts remain isolated");
        yes(!calendar.equals(ReminderPolicy.calendarMarker("account-a", "task-b", morning, "Asia/Shanghai")), "calendar tasks remain isolated");
        long localMidnight = Instant.parse("2026-10-07T16:00:00Z").toEpochMilli();
        yes(!ReminderPolicy.calendarMarker("a", "t", localMidnight - 1, "Asia/Shanghai").equals(ReminderPolicy.calendarMarker("a", "t", localMidnight, "Asia/Shanghai")), "calendar day changes at local midnight");
        yes(!ReminderPolicy.calendarMarker("a", "task/a", morning, "Asia/Shanghai").equals(ReminderPolicy.calendarMarker("a", "task%2Fa", morning, "Asia/Shanghai")), "task identity cannot collide through URI escaping");
        System.out.println("ReminderPolicyTest: " + assertions + " assertions passed");
    }
    private static void yes(boolean value, String label) { assertions++; if (!value) throw new AssertionError(label); }
}
