package dev.niannian.reminder;

import java.time.*;
import java.util.Objects;

/** Run with javac/java; no Android runtime is required. */
public final class MinuteIntervalFormTest {
    private static int checks;
    public static void main(String[] args) {
        ZoneId shanghai = ZoneId.of("Asia/Shanghai");
        equal("2026-11-01T00:05:37+08:00", MinuteIntervalForm.defaultFirstAt(Instant.parse("2026-10-31T15:50:37Z"), shanghai), "15 minute default crosses a month boundary");
        String original = "2026-10-03T04:05:06.123456789Z";
        equal("2026-10-03", MinuteIntervalForm.date(original, shanghai), "date uses reminder timezone");
        equal("12:05:06.123456789", MinuteIntervalForm.time(original, shanghai), "display keeps subsecond precision");
        equal(original, MinuteIntervalForm.reschedule(original, LocalDate.parse("2026-10-03"), LocalTime.parse("12:05:06.123456789"), shanghai), "untouched fields keep exact timestamp and offset");
        equal(original, MinuteIntervalForm.validate(original, shanghai, null), "validation leaves source timestamp unchanged");
        equal("2026-10-04T12:05:06.123456789+08:00", MinuteIntervalForm.reschedule(original, LocalDate.parse("2026-10-04"), LocalTime.parse("12:05:06.123456789"), shanghai), "date edit keeps seconds and nanos");
        equal("2026-10-03T12:10:06.123456789+08:00", MinuteIntervalForm.reschedule(original, LocalDate.parse("2026-10-03"), LocalTime.parse("12:10:06.123456789"), shanghai), "minute edit keeps seconds and nanos");
        equal("2026-10-03T12:05:00+08:00", MinuteIntervalForm.reschedule("2026-10-03T12:00:00+08:00", LocalDate.parse("2026-10-03"), LocalTime.of(12,5), shanghai), "zero seconds are explicit in saved RFC3339");
        String localMidnight = "2026-10-03T16:00:00Z";
        equal(localMidnight, MinuteIntervalForm.validate(localMidnight, shanghai, LocalDate.parse("2026-10-04")), "end date includes local first date");
        rejects(() -> MinuteIntervalForm.validate(localMidnight, shanghai, LocalDate.parse("2026-10-03")), "end date before local first reminder");
        for (String invalid : new String[]{"", "2026-10-03T12:00:00", "2026-10-03T12:00+08:00", "2026-02-30T12:00:00+08:00"})
            rejects(() -> MinuteIntervalForm.validate(invalid, shanghai, null), "invalid RFC3339 " + invalid);
        ZoneId newYork = ZoneId.of("America/New_York");
        String secondOverlap = "2026-11-01T01:30:47-05:00";
        equal(secondOverlap, MinuteIntervalForm.reschedule(secondOverlap, LocalDate.parse("2026-11-01"), LocalTime.parse("01:30:47"), newYork), "review keeps second DST overlap occurrence");
        equal("2026-11-01T01:40:47-05:00", MinuteIntervalForm.reschedule(secondOverlap, LocalDate.parse("2026-11-01"), LocalTime.parse("01:40:47"), newYork), "edit keeps valid overlap offset");
        rejects(() -> MinuteIntervalForm.reschedule("2026-03-08T01:30:00-05:00", LocalDate.parse("2026-03-08"), LocalTime.parse("02:30:00"), newYork), "reject nonexistent DST local time");
        String first = "2026-10-03T14:00:00+08:00", end = "2026-10-03T09:00:07.123456789Z";
        equal(end, MinuteIntervalForm.validateEndAt(first, end, shanghai, null), "deadline validation preserves original seconds, nanos and offset");
        equal(end, MinuteIntervalForm.reschedule(end, LocalDate.parse("2026-10-03"), LocalTime.parse("17:00:07.123456789"), shanghai), "reviewing a deadline does not rewrite its original offset");
        equal("2026-10-03T17:10:07.123456789+08:00", MinuteIntervalForm.reschedule(end, LocalDate.parse("2026-10-03"), LocalTime.parse("17:10:07.123456789"), shanghai), "editing deadline minutes preserves seconds and nanos");
        equal(first, MinuteIntervalForm.validateEndAt(first, first, shanghai, null), "deadline includes its exact instant");
        equal("2026-10-03T06:00:00Z", MinuteIntervalForm.validateEndAt(first, "2026-10-03T06:00:00Z", shanghai, null), "equal instants with different offsets are allowed unchanged");
        equal("", MinuteIntervalForm.validateEndAt(first, "", shanghai, LocalDate.parse("2026-10-04")), "cleared exact deadline permits an end date");
        equal("", MinuteIntervalForm.validateEndAt(first, null, shanghai, null), "missing deadline stays unset");
        rejects(() -> MinuteIntervalForm.validateEndAt(first, end, shanghai, LocalDate.parse("2026-10-03")), "date and exact deadline are mutually exclusive");
        rejects(() -> MinuteIntervalForm.validateEndAt(first, "2026-10-03T05:59:59.999999999Z", shanghai, null), "deadline cannot precede the first instant");
        rejects(() -> MinuteIntervalForm.validateEndAt(first, "2026-10-03T17:00+08:00", shanghai, null), "deadline must be full RFC3339");
        System.out.println("MinuteIntervalFormTest: " + checks + " checks passed");
    }
    private static void equal(Object expected, Object actual, String description) {
        checks++;
        if (!Objects.equals(expected, actual)) throw new AssertionError(description + ": expected " + expected + ", got " + actual);
    }
    private static void rejects(Runnable action, String description) {
        checks++;
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError(description + ": expected validation error");
    }
}
