package dev.niannian.reminder;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Calendar grouping uses the record's instant in the selected display time zone. */
public final class RecordDates {
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);

    private RecordDates() {}

    /** Parses RFC3339 offset timestamps, including optional fractional seconds. */
    public static Instant parseInstant(String timestamp) {
        if (timestamp == null || timestamp.trim().isEmpty()) return null;
        try {
            return OffsetDateTime.parse(timestamp.trim(), DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (DateTimeParseException invalid) {
            return null;
        }
    }

    /** Invalid or missing record timestamps have no calendar date. */
    public static LocalDate localDate(String timestamp, ZoneId zone) {
        Instant instant = parseInstant(timestamp);
        return instant == null ? null : instant.atZone(zone).toLocalDate();
    }

    /** Returns local 24-hour time, or null for an invalid timestamp. */
    public static String localTime(String timestamp, ZoneId zone) {
        Instant instant = parseInstant(timestamp);
        return instant == null ? null : CLOCK.format(instant.atZone(zone));
    }

    /** Returns a sorted copy; invalid entries stay at the end in their original order. */
    public static List<String> sortedTimestamps(List<String> timestamps, boolean descending) {
        List<String> result = new ArrayList<>(timestamps);
        result.sort((left, right) -> {
            Instant leftInstant = parseInstant(left);
            Instant rightInstant = parseInstant(right);
            if (leftInstant == null) return rightInstant == null ? 0 : 1;
            if (rightInstant == null) return -1;
            return descending ? rightInstant.compareTo(leftInstant) : leftInstant.compareTo(rightInstant);
        });
        return result;
    }

    public static int mondayLeadingBlanks(YearMonth month) {
        return month.atDay(1).getDayOfWeek().getValue() - 1;
    }

    public static int daysInMonth(YearMonth month) {
        return month.lengthOfMonth();
    }
}
