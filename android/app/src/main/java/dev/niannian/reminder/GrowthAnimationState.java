package dev.niannian.reminder;

/** Keeps an acknowledged completion from automatically celebrating twice after navigation. */
final class GrowthAnimationState {
    private String consumedId = "";
    static boolean validReceipt(String id, int from, int to, boolean added) {
        return id != null && !id.isEmpty() && from >= 0 && to >= 0
                && (added ? (long) to == (long) from + 1 : to == from);
    }
    boolean consume(String id, int from, int to, boolean added) {
        if (!added || !validReceipt(id, from, to, true) || id.equals(consumedId)) return false;
        consumedId = id;
        return true;
    }
    String savedId() { return consumedId; }
    void restore(String id) { consumedId = id == null ? "" : id; }
    void clear() { consumedId = ""; }
}
