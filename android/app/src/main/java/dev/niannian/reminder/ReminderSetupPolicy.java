package dev.niannian.reminder;

/** Read-only decisions for setup guidance; never changes the saved channel choices. */
final class ReminderSetupPolicy {
    private static final String[] CHANNELS = {"notification", "calendar", "alarm", "email"};
    private static final String[] NAMES = {"手机通知", "手机日历", "闹钟提醒", "邮件"};
    private ReminderSetupPolicy() { }

    static boolean isSelected(boolean configured, String[] selected, String channel) {
        return configured && contains(selected, channel);
    }

    static String setupPrompt(boolean configured, boolean enabled, String[] selected, String[] available) {
        if (!configured) return "还没有开启手机提醒。请到设置选择提醒方式并授权，原有邮件提醒保持不变。";
        if (!enabled) return "提醒安排已暂停，请到设置开启，并选择要使用的提醒方式。";
        boolean hasChoice = false;
        StringBuilder missing = new StringBuilder();
        for (int i = 0; i < CHANNELS.length; i++) {
            if (!contains(selected, CHANNELS[i])) continue;
            hasChoice = true;
            if (i < 3 && !contains(available, CHANNELS[i])) append(missing, NAMES[i]);
        }
        if (!hasChoice) return "还没有选择提醒方式，请到设置选择并开启至少一种方式。";
        if (missing.length() > 0) return "你选择的" + missing + "尚未获得所需权限，请到设置完成授权，或调整提醒方式。";
        return "";
    }

    static String availableChannelsSummary(boolean configured, boolean enabled, String[] selected, String[] available) {
        if (!configured) return "手机提醒尚未开启，原有邮件提醒保持不变";
        if (!enabled) return "提醒安排已暂停";
        StringBuilder ready = new StringBuilder();
        for (int i = 0; i < CHANNELS.length; i++) {
            if (contains(selected, CHANNELS[i]) && (i == 3 || contains(available, CHANNELS[i]))) append(ready, NAMES[i]);
        }
        return ready.length() == 0 ? "暂无可用的提醒方式" : "当前可用：" + ready;
    }

    private static boolean contains(String[] values, String target) {
        if (values != null) for (String value : values) if (target.equals(value)) return true;
        return false;
    }

    private static void append(StringBuilder text, String value) {
        if (text.length() > 0) text.append("、");
        text.append(value);
    }
}
