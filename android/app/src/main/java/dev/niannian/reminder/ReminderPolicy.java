package dev.niannian.reminder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Base64;

/** Platform independent limits; platform delivery always checks them again at execution time. */
final class ReminderPolicy {
    static final int MAX_PLANS = 128;
    static boolean needsLegacyQuietMigration(boolean configured, boolean activeDevice, String start, String end) {
        return configured && activeDevice && ((start != null && !start.isEmpty()) || (end != null && !end.isEmpty()));
    }
    static boolean blocksSettingsMigration(long now, long scheduled, long expires, String status) {
        if ("delivered".equals(status) || "accepted".equals(status) || "failed".equals(status)
                || "cancelled".equals(status) || "sent".equals(status)) return false;
        // Cover a plan becoming due during the settings request as well as one already executing.
        return scheduled > 0 && expires > now && scheduled <= now + 60_000L;
    }
    static boolean due(long now, long scheduled, long expires, long last, int interval, int taskCount, int totalCount) {
        return scheduled > 0 && expires > scheduled && now >= scheduled && now < expires
                && (last == 0 || now - last >= Math.max(1, interval) * 60000L)
                && taskCount < 32 && totalCount < MAX_PLANS;
    }
    static String scope(String base, String email) {
        if (base == null || base.isBlank() || email == null || email.isBlank()) return "";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest((base + "\n" + email.trim().toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte value : digest) out.append(String.format(Locale.ROOT, "%02x", value & 255));
            return out.toString();
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    static long timestamp(String value) {
        try { return Instant.parse(value).toEpochMilli(); } catch (RuntimeException invalid) { return 0; }
    }
    static boolean fresh(long expected, String scope, long actual, String currentScope) {
        return !scope.isEmpty() && expected == actual && scope.equals(currentScope);
    }
    static String calendarMarker(String scope, String task, long scheduled, String zone) {
        String safeTask = Base64.getUrlEncoder().withoutPadding().encodeToString(task.getBytes(StandardCharsets.UTF_8));
        return "niannian://reminder/" + scope + "/task/" + safeTask + "/day/"
                + Instant.ofEpochMilli(scheduled).atZone(ZoneId.of(zone)).toLocalDate();
    }
}
