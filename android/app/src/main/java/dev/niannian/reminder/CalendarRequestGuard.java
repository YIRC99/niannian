package dev.niannian.reminder;

/** The displayed month owns its latest request, including refreshes in that month. */
final class CalendarRequestGuard {
    private int sequence;
    private String month = "", timezone = "";

    int begin(String month, String timezone) {
        this.month = month;
        this.timezone = timezone;
        return ++sequence;
    }

    boolean accepts(int request, String month, String timezone) {
        return request == sequence && this.month.equals(month) && this.timezone.equals(timezone);
    }

    void invalidate() {
        sequence++;
        month = "";
        timezone = "";
    }
}
