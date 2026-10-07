package dev.niannian.reminder;

import java.util.List;

public final class LocalReminderFlowTest {
    private static int assertions;
    private static void equal(long want, long actual, String label) { assertions++; if (want != actual) throw new AssertionError(label + ": " + actual + " != " + want); }
    private static void check(boolean condition, String label) { assertions++; if (!condition) throw new AssertionError(label); }
    public static void main(String[] args) {
        equal(100_000, LocalReminderFlow.nextAttempt(100_000, 100_000, 180_000, 200_000, 0, 1, true), "first step is due");
        equal(100_000, LocalReminderFlow.nextAttempt(90_000, 100_000, 180_000, 200_000, 0, 1, true), "early callback waits for schedule");
        equal(130_000, LocalReminderFlow.nextAttempt(120_000, 120_000, 190_000, 200_000, 70_000, 1, true), "late actual delivery shifts minimum interval");
        equal(-1, LocalReminderFlow.nextAttempt(120_000, 120_000, 130_000, 200_000, 70_000, 1, true), "cannot retry at exclusive window end");
        equal(-1, LocalReminderFlow.nextAttempt(200_000, 120_000, 300_000, 200_000, 0, 1, true), "flow validity bounds each step");
        equal(121_000, LocalReminderFlow.nextAttempt(120_000, 120_000, 190_000, 200_000, 0, 1, false), "preceding playback must finish reporting");
        equal(-1, LocalReminderFlow.nextAttempt(189_500, 120_000, 190_000, 200_000, 0, 1, false), "do not spin past expiry");
        equal(-1, LocalReminderFlow.nextAttempt(100_000, 100_000, 99_000, 200_000, 0, 1, true), "reject inverted window");
        equal(2_400_000,LocalReminderFlow.nextAttempt(1_800_000,1_800_000,2_700_000,3_000_000,600_000,15,true,600_000,30),"late delivery preserves the actual strategy wait");
        equal(2_400_000,LocalReminderFlow.nextAttempt(1_800_000,1_800_000,2_700_000,3_000_000,0,15,true,600_000,30),"late failure also preserves the strategy wait");
        equal(-1,LocalReminderFlow.nextAttempt(1_800_000,1_800_000,2_100_000,3_000_000,600_000,15,true,600_000,30),"late predecessor cannot extend the lease window");
        check(LocalReminderFlow.terminal("delivered"), "delivered is processed");
        check(LocalReminderFlow.terminal("failed"), "failed is processed");
        check(LocalReminderFlow.terminal("cancelled"), "cancelled is processed");
        check(!LocalReminderFlow.terminal("executing"), "launching service is not a playback receipt");
        check(!LocalReminderFlow.terminal("scheduled"), "OS registration is not delivery");
        check(LocalReminderFlow.lostPlayback("executing", false), "superseded grant fails an unstarted alarm");
        check(!LocalReminderFlow.lostPlayback("executing", true), "current playback start still has time to finish");
        check(!LocalReminderFlow.lostPlayback("delivered", false), "another task cannot rewrite successful playback");
        check(!LocalReminderFlow.lostPlayback("scheduled", false), "future step does not need a ringing grant");
        var first = new LocalReminderFlow.Step("s1", 0, "notification", 100_000, 160_000);
        var second = new LocalReminderFlow.Step("s2", 1, "alarm", 160_000, 220_000);
        check(LocalReminderFlow.valid("f", "task", 220_000, List.of(first, second)), "notification and alarm prefix accepted");
        check(!LocalReminderFlow.valid("f", "task", 219_000, List.of(first, second)), "expiry outside lease rejected");
        check(!LocalReminderFlow.valid("f", "task", 220_000, List.of(first, first)), "duplicate step identity rejected");
        check(!LocalReminderFlow.valid("f", "task", 220_000, List.of(second, first)), "out of order window rejected");
        check(!LocalReminderFlow.valid("f", "task", 220_000, List.of(new LocalReminderFlow.Step("mail", 0, "email", 100_000, 160_000))), "email cannot execute locally");
        check(!LocalReminderFlow.valid("f", "task", 220_000, List.of(new LocalReminderFlow.Step("cal", 0, "calendar", 100_000, 160_000))), "calendar is a server boundary");
        check(!LocalReminderFlow.valid("", "task", 220_000, List.of(first)), "missing authorization rejected");
        check(!LocalReminderFlow.valid("f", "task", 220_000, List.of()), "empty sequence rejected");
        System.out.println("LocalReminderFlow: " + assertions + " assertions passed");
    }
}
