package dev.niannian.reminder;

/** Regression cases for out-of-order month responses and cache invalidation. */
public final class CalendarRequestGuardTest {
    private static int checks;
    public static void main(String[] args) {
        CalendarRequestGuard guard = new CalendarRequestGuard();
        int october = guard.begin("2026-10", "Asia/Shanghai");
        int november = guard.begin("2026-11", "Asia/Shanghai");
        check(!guard.accepts(october, "2026-10", "Asia/Shanghai"), "old month cannot replace current month");
        check(guard.accepts(november, "2026-11", "Asia/Shanghai"), "latest month response accepted");
        int refresh = guard.begin("2026-11", "Asia/Shanghai");
        check(!guard.accepts(november, "2026-11", "Asia/Shanghai"), "old refresh cannot replace newer data");
        check(!guard.accepts(refresh, "2026-10", "Asia/Shanghai"), "response must match visible month");
        check(!guard.accepts(refresh, "2026-11", "UTC"), "response must match phone timezone");
        check(guard.accepts(refresh, "2026-11", "Asia/Shanghai"), "date-only selection keeps month request valid");
        guard.invalidate();
        check(!guard.accepts(refresh, "2026-11", "Asia/Shanghai"), "account reset rejects pending response");
        System.out.println("CalendarRequestGuardTest: " + checks + " checks passed");
    }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
