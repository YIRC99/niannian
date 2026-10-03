package dev.niannian.reminder;

public final class GrowthAnimationStateTest {
    private static int assertions;
    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        GrowthAnimationState state = new GrowthAnimationState();
        check(GrowthAnimationState.validReceipt("first", 0, 1, true), "first real completion is valid");
        check(state.consume("first", 0, 1, true), "first delivery animates");
        check(!state.consume("first", 0, 1, true), "returning to success must not repeat celebration");
        GrowthAnimationState recreated = new GrowthAnimationState();
        recreated.restore(state.savedId());
        check(!recreated.consume("first", 0, 1, true), "restored activity must not repeat celebration");
        check(recreated.consume("second", 2, 3, true), "new leaf milestone animates");
        check(recreated.consume("third", 6, 7, true), "new blossom milestone animates");
        check(recreated.consume("fourth", 7, 8, true), "same-stage nourishment also animates");
        check(GrowthAnimationState.validReceipt("duplicate", 8, 8, false), "idempotent response still renders current stage");
        check(!recreated.consume("duplicate", 8, 8, false), "idempotent response never adds animated growth");
        check(!GrowthAnimationState.validReceipt("", 0, 1, true), "missing completion id is not an event");
        check(!GrowthAnimationState.validReceipt(null, 0, 1, true), "null completion id is not an event");
        check(!GrowthAnimationState.validReceipt("bad", -1, 0, true), "unknown prior count cannot fabricate sprouting");
        check(!GrowthAnimationState.validReceipt("bad", 3, 2, true), "growth cannot go backward");
        check(!GrowthAnimationState.validReceipt("bad", 2, 7, true), "one completion cannot skip several milestones");
        check(!GrowthAnimationState.validReceipt("bad", 2, 3, false), "duplicate cannot increase growth");
        check(!GrowthAnimationState.validReceipt("bad", Integer.MAX_VALUE, Integer.MIN_VALUE, true), "overflow rejected");
        recreated.clear();
        check(recreated.savedId().isEmpty(), "account change clears receipt memory");
        check(recreated.consume("first", 0, 1, true), "new account starts with fresh animation state");
        System.out.println("GrowthAnimationStateTest: " + assertions + " assertions passed");
    }
}
