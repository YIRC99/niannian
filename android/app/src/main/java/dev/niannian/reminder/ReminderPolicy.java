package dev.niannian.reminder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Locale;

/** Platform independent limits; platform delivery always checks them again at execution time. */
final class ReminderPolicy {
    static final int MAX_PLANS = 128;
    static boolean inQuiet(long instant, String zone, String start, String end) {
        if ("".equals(start) && "".equals(end)) return false;
        try {
            LocalTime from = LocalTime.parse(start), until = LocalTime.parse(end);
            if (from.equals(until)) return false;
            LocalTime current = Instant.ofEpochMilli(instant).atZone(ZoneId.of(zone)).toLocalTime();
            return from.isBefore(until) ? !current.isBefore(from) && current.isBefore(until)
                    : !current.isBefore(from) || current.isBefore(until);
        } catch (RuntimeException invalid) { return true; }
    }
    static boolean due(long now, long scheduled, long expires, long last, int interval, int taskCount, int totalCount) {
        return scheduled > 0 && expires > scheduled && now >= scheduled && now < expires
                && (last == 0 || now - last >= Math.max(5, interval) * 60000L)
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
}
