package dev.niannian.reminder;

/** Device background access is separate from notification and exact-alarm permissions. */
final class ReminderBackgroundPolicy {
    private ReminderBackgroundPolicy() { }
    static boolean needsSetup(boolean phone, boolean exempt, boolean restricted, boolean vendor, boolean reviewed) {
        return phone && (!exempt || restricted || (vendor && !reviewed));
    }
    static boolean isXiaomi(String manufacturer, String brand) {
        return "xiaomi".equalsIgnoreCase(manufacturer) || "xiaomi".equalsIgnoreCase(brand)
                || "redmi".equalsIgnoreCase(brand) || "poco".equalsIgnoreCase(brand);
    }
}
