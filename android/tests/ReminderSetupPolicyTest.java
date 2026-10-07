package dev.niannian.reminder;

public final class ReminderSetupPolicyTest {
    private static int assertions;
    private static final String[] NONE = {}, PHONE = {"notification", "calendar", "alarm"};

    public static void main(String[] args) {
        yes(!ReminderSetupPolicy.setupPrompt(false, true, PHONE, NONE).isEmpty(), "legacy account needs phone setup despite default template");
        yes(!ReminderSetupPolicy.setupPrompt(false, true, PHONE, PHONE).isEmpty(), "system permissions alone do not enable unconfigured channels");
        yes(ReminderSetupPolicy.availableChannelsSummary(false, true, PHONE, PHONE).contains("原有邮件"), "legacy summary preserves existing mail state");
        yes(!ReminderSetupPolicy.isSelected(false, PHONE, "notification"), "unconfigured server template must not preselect a phone channel");
        yes(!ReminderSetupPolicy.isSelected(false, new String[]{"email"}, "email"), "unconfigured template must not select optional mail");
        yes(ReminderSetupPolicy.isSelected(true, new String[]{"email"}, "email"), "explicit saved email choice restored");
        yes(!ReminderSetupPolicy.isSelected(true, null, "notification"), "missing saved channels do not grant a default");
        yes(!ReminderSetupPolicy.setupPrompt(true, false, PHONE, PHONE).isEmpty(), "paused reminders need an enable hint");
        yes(ReminderSetupPolicy.availableChannelsSummary(true, false, PHONE, PHONE).contains("暂停"), "paused summary never claims channels available");
        yes(!ReminderSetupPolicy.setupPrompt(true, true, NONE, PHONE).isEmpty(), "no selected channel needs setup");
        yes(!ReminderSetupPolicy.setupPrompt(true, true, new String[]{"unknown"}, PHONE).isEmpty(), "unsupported channel is not usable");
        yes(ReminderSetupPolicy.setupPrompt(true, true, new String[]{"notification"}, new String[]{"notification"}).isEmpty(), "granted selected notification needs no prompt");
        yes(ReminderSetupPolicy.setupPrompt(true, true, new String[]{"calendar"}, new String[]{"calendar"}).isEmpty(), "granted selected calendar needs no prompt");
        yes(ReminderSetupPolicy.setupPrompt(true, true, new String[]{"alarm"}, new String[]{"alarm"}).isEmpty(), "granted selected alarm needs no prompt");
        yes(ReminderSetupPolicy.setupPrompt(true, true, new String[]{"email"}, NONE).isEmpty(), "explicit mail-only choice does not require phone permissions");
        yes(ReminderSetupPolicy.setupPrompt(true, true, new String[]{"notification", "email"}, NONE).contains("手机通知"), "mail fallback does not hide a selected missing notification permission");
        String missingCalendar = ReminderSetupPolicy.setupPrompt(true, true, new String[]{"notification", "calendar"}, new String[]{"notification"});
        yes(missingCalendar.contains("手机日历"), "partially available choices name the missing calendar permission");
        yes(!missingCalendar.contains("手机通知"), "available selected notification is not reported as missing");
        yes(ReminderSetupPolicy.setupPrompt(true, true, new String[]{"alarm"}, NONE).contains("闹钟"), "alarm requires its complete platform capability");
        String summary = ReminderSetupPolicy.availableChannelsSummary(true, true, new String[]{"notification", "calendar", "email"}, new String[]{"notification", "alarm"});
        yes(summary.contains("手机通知") && summary.contains("邮件"), "summary includes available saved choices");
        yes(!summary.contains("手机日历") && !summary.contains("闹钟"), "summary excludes missing permission and unselected channels");
        yes(!summary.contains("已安排") && !summary.contains("已选择"), "summary does not invent the AI execution channel");
        yes(ReminderSetupPolicy.availableChannelsSummary(true, true, new String[]{"notification"}, NONE).contains("暂无"), "unavailable choices cannot be described as ready");
        yes(!ReminderSetupPolicy.setupPrompt(true, true, null, null).isEmpty(), "absent state still guides setup without throwing");
        System.out.println("ReminderSetupPolicyTest: " + assertions + " assertions passed");
    }

    private static void yes(boolean value, String label) { assertions++; if (!value) throw new AssertionError(label); }
}
