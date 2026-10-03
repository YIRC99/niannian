package dev.niannian.reminder;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Keeps the exact first reminder instant while the form is reviewed or edited. */
final class MinuteIntervalForm {
    private MinuteIntervalForm() {}

    static String defaultFirstAt(Instant now, ZoneId zone) {
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(now.plusSeconds(15 * 60).atZone(zone).withNano(0));
    }

    static String date(String firstAt, ZoneId zone) {
        return local(firstAt, zone).toLocalDate().toString();
    }

    static String time(String firstAt, ZoneId zone) {
        return DateTimeFormatter.ISO_LOCAL_TIME.format(local(firstAt, zone).toLocalTime());
    }

    static String reschedule(String firstAt, LocalDate date, LocalTime time, ZoneId zone) {
        ZonedDateTime previous = local(firstAt, zone);
        LocalDateTime next = LocalDateTime.of(date, time);
        if (previous.toLocalDateTime().equals(next)) return firstAt;
        List<ZoneOffset> offsets = zone.getRules().getValidOffsets(next);
        if (offsets.isEmpty()) throw new IllegalArgumentException("这个时间因夏令时调整不存在，请重新选择");
        ZoneOffset offset = offsets.contains(previous.getOffset()) ? previous.getOffset() : offsets.get(0);
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(ZonedDateTime.ofStrict(next, offset, zone));
    }

    static String validate(String firstAt, ZoneId zone, LocalDate end) {
        ZonedDateTime first = local(firstAt, zone);
        if (end != null && end.isBefore(first.toLocalDate()))
            throw new IllegalArgumentException("结束日期不能早于首次提醒日期");
        return firstAt;
    }

    private static ZonedDateTime local(String firstAt, ZoneId zone) {
        if (firstAt == null || !firstAt.matches("\\d{4}-\\d{2}-\\d{2}[Tt]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?(?:[Zz]|[+-]\\d{2}:\\d{2})"))
            throw new IllegalArgumentException("请选择完整的首次提醒日期和时间");
        try {
            return OffsetDateTime.parse(firstAt, DateTimeFormatter.ISO_OFFSET_DATE_TIME).atZoneSameInstant(zone);
        } catch (DateTimeException invalid) {
            throw new IllegalArgumentException("首次提醒时间无效，请重新选择");
        }
    }
}
