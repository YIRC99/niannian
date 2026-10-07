package dev.niannian.reminder;

public final class ReminderBackgroundPolicyTest {
    private static int checks;
    public static void main(String[] args) {
        yes(!ReminderBackgroundPolicy.needsSetup(false, false, true, true, false), "email-only never asks for background access");
        yes(ReminderBackgroundPolicy.needsSetup(true, false, false, false, true), "battery restriction still needs attention after review");
        yes(ReminderBackgroundPolicy.needsSetup(true, true, true, false, true), "explicit background restriction overrides battery exemption");
        yes(ReminderBackgroundPolicy.needsSetup(true, true, false, true, false), "vendor autostart cannot be inferred from notification permission");
        yes(!ReminderBackgroundPolicy.needsSetup(true, true, false, true, true), "reviewed vendor setup does not prompt on every task");
        yes(!ReminderBackgroundPolicy.needsSetup(true, true, false, false, false), "unrestricted standard Android needs no extra step");
        yes(ReminderBackgroundPolicy.isXiaomi("Xiaomi", "Redmi"), "Xiaomi family recognized");
        yes(ReminderBackgroundPolicy.isXiaomi("unknown", "POCO"), "POCO uses vendor guidance");
        yes(!ReminderBackgroundPolicy.isXiaomi("Google", "google"), "other phones use generic guidance");
        System.out.println("ReminderBackgroundPolicyTest: " + checks + " assertions passed");
    }
    private static void yes(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
