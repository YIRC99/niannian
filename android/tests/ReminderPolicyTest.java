package dev.niannian.reminder;

import java.time.Instant;

public final class ReminderPolicyTest {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        long morning = Instant.parse("2026-10-07T01:00:00Z").toEpochMilli();
        yes(ReminderPolicy.inQuiet(morning, "Asia/Shanghai", "22:00", "10:00"), "overnight quiet window");
        yes(!ReminderPolicy.inQuiet(morning, "Asia/Shanghai", "22:00", "08:00"), "outside quiet window");
        yes(ReminderPolicy.inQuiet(morning, "Asia/Shanghai", "09:00", "10:00"), "quiet start inclusive");
        yes(!ReminderPolicy.inQuiet(morning, "Asia/Shanghai", "08:00", "09:00"), "quiet end exclusive");
        yes(!ReminderPolicy.inQuiet(morning, "Asia/Shanghai", "09:00", "09:00"), "equal times disables quiet window");
        yes(!ReminderPolicy.inQuiet(morning, "Asia/Shanghai", "", ""), "server disabled quiet window");
        yes(ReminderPolicy.inQuiet(morning, "Asia/Shanghai", "22:00", ""), "partial quiet setting fails closed");
        yes(ReminderPolicy.inQuiet(morning, "invalid", "invalid", "08:00"), "invalid settings fail closed");
        yes(ReminderPolicy.due(morning, morning, morning + 60000, 0, 5, 0, 0), "fresh due plan admitted");
        yes(!ReminderPolicy.due(morning, morning + 5000, morning + 60000, 0, 5, 0, 0), "early broadcast rejected");
        yes(!ReminderPolicy.due(morning, morning - 60000, morning, 0, 5, 0, 0), "expiry exclusive");
        yes(!ReminderPolicy.due(morning, morning, morning + 60000, morning - 299999, 5, 0, 0), "minimum gap applies across channels");
        yes(ReminderPolicy.due(morning, morning, morning + 60000, morning - 300000, 5, 0, 0), "minimum gap exact boundary");
        yes(!ReminderPolicy.due(morning, morning, morning + 60000, morning - 100000, 0, 0, 0), "invalid gap cannot bypass five minute minimum");
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
        System.out.println("ReminderPolicyTest: " + assertions + " assertions passed");
    }
    private static void yes(boolean value, String label) { assertions++; if (!value) throw new AssertionError(label); }
}
