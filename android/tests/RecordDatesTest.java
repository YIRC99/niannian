package dev.niannian.reminder;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Zero-dependency regression tests; run the main method with java. */
public final class RecordDatesTest {
    private static int checks;

    public static void main(String[] args) {
        parsesActualInstants();
        rejectsMissingOrInvalidTimes();
        groupsByLocalDateAcrossMidnight();
        handlesDaylightSavingTransitions();
        sortsActualInstantsWithoutMutatingInput();
        buildsMondayFirstMonths();
        System.out.println("RecordDatesTest: " + checks + " checks passed");
    }

    private static void parsesActualInstants() {
        equal(Instant.parse("2026-10-03T04:05:06Z"),
                RecordDates.parseInstant("2026-10-03T04:05:06Z"), "UTC timestamp");
        equal(Instant.parse("2026-10-03T04:05:06.123456789Z"),
                RecordDates.parseInstant("2026-10-03T12:05:06.123456789+08:00"), "nanoseconds and positive offset");
        equal(Instant.parse("2026-10-03T04:05:06.100Z"),
                RecordDates.parseInstant("2026-10-02T23:05:06.1-05:00"), "fraction and negative offset");
        equal(Instant.parse("2026-10-03T04:05:06Z"),
                RecordDates.parseInstant("2026-10-03t04:05:06z"), "RFC3339 lowercase separators");
        equal(Instant.parse("2026-10-03T04:05:06Z"),
                RecordDates.parseInstant(" 2026-10-03T04:05:06Z "), "surrounding whitespace");
    }

    private static void rejectsMissingOrInvalidTimes() {
        for (String timestamp : Arrays.asList(null, "", "  ", "invalid", "2026-10-03",
                "2026-10-03T04:05:06", "2026-02-29T00:00:00Z", "2026-10-03T25:00:00Z")) {
            equal(null, RecordDates.parseInstant(timestamp), "invalid instant " + timestamp);
            equal(null, RecordDates.localDate(timestamp, ZoneId.of("Asia/Shanghai")), "invalid date " + timestamp);
            equal(null, RecordDates.localTime(timestamp, ZoneId.of("Asia/Shanghai")), "invalid time " + timestamp);
        }
    }

    private static void groupsByLocalDateAcrossMidnight() {
        ZoneId shanghai = ZoneId.of("Asia/Shanghai");
        equal(LocalDate.of(2026, 10, 4), RecordDates.localDate("2026-10-03T16:00:00Z", shanghai), "UTC record enters next local day");
        equal(LocalDate.of(2026, 10, 3), RecordDates.localDate("2026-10-03T15:59:59.999999999Z", shanghai), "instant before local midnight");
        equal("00:00", RecordDates.localTime("2026-10-03T16:00:00Z", shanghai), "midnight time");
        equal(LocalDate.of(2025, 12, 31), RecordDates.localDate("2026-01-01T01:00:00Z", ZoneId.of("America/Los_Angeles")), "year boundary in negative offset");
        equal(LocalDate.of(2026, 10, 2), RecordDates.localDate("2026-10-03T00:30:00+08:00", ZoneId.of("UTC")), "source offset changes UTC date");
    }

    private static void handlesDaylightSavingTransitions() {
        ZoneId newYork = ZoneId.of("America/New_York");
        equal("01:59", RecordDates.localTime("2026-03-08T06:59:59Z", newYork), "before spring jump");
        equal("03:00", RecordDates.localTime("2026-03-08T07:00:00Z", newYork), "after spring jump");
        equal(LocalDate.of(2026, 3, 7), RecordDates.localDate("2026-03-08T04:59:59Z", newYork), "day before DST transition");
        equal(LocalDate.of(2026, 3, 8), RecordDates.localDate("2026-03-08T05:00:00Z", newYork), "midnight before DST transition");
        equal("01:30", RecordDates.localTime("2026-11-01T05:30:00Z", newYork), "first autumn 01:30");
        equal("01:30", RecordDates.localTime("2026-11-01T06:30:00Z", newYork), "second autumn 01:30");
        equal(LocalDate.of(2026, 11, 1), RecordDates.localDate("2026-11-01T06:30:00Z", newYork), "autumn overlap local day");
    }

    private static void sortsActualInstantsWithoutMutatingInput() {
        String earliest = "2026-10-03T00:30:00+08:00";
        String middle = "2026-10-02T20:00:00Z";
        String latest = "2026-10-02T18:00:00-05:00";
        List<String> input = Arrays.asList(middle, "broken", earliest, null, latest);
        equal(Arrays.asList(latest, middle, earliest, "broken", null), RecordDates.sortedTimestamps(input, true), "descending actual instants, invalid last");
        equal(Arrays.asList(earliest, middle, latest, "broken", null), RecordDates.sortedTimestamps(input, false), "ascending actual instants, invalid last");
        equal(Arrays.asList(middle, "broken", earliest, null, latest), input, "input remains unchanged");
        List<String> equalInstants = Arrays.asList("2026-10-03T00:00:00Z", "2026-10-03T08:00:00+08:00");
        equal(equalInstants, RecordDates.sortedTimestamps(equalInstants, true), "equal instant order remains stable");
    }

    private static void buildsMondayFirstMonths() {
        equal(0, RecordDates.mondayLeadingBlanks(YearMonth.of(2026, 6)), "Monday starts first column");
        equal(6, RecordDates.mondayLeadingBlanks(YearMonth.of(2026, 2)), "Sunday starts last column");
        equal(3, RecordDates.mondayLeadingBlanks(YearMonth.of(2026, 10)), "Thursday has three blanks");
        equal(28, RecordDates.daysInMonth(YearMonth.of(2026, 2)), "normal February");
        equal(29, RecordDates.daysInMonth(YearMonth.of(2028, 2)), "leap February");
        equal(30, RecordDates.daysInMonth(YearMonth.of(2026, 4)), "April");
        equal(31, RecordDates.daysInMonth(YearMonth.of(2026, 10)), "October");
    }

    private static void equal(Object expected, Object actual, String description) {
        checks++;
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(description + ": expected " + expected + ", got " + actual);
        }
    }
}
