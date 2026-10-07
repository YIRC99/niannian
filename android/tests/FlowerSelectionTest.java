package dev.niannian.reminder;

import java.util.HashMap;
import java.util.Map;

public final class FlowerSelectionTest {
    private static int assertions;

    public static void main(String[] args) {
        String scope = FlowerSelection.scopeKey("HTTPS://EXAMPLE.COM:443/", " ME@EXAMPLE.COM ");
        check(scope.equals(FlowerSelection.scopeKey("https://example.com", "me@example.com")), "equivalent server and email share a scope");
        check(!scope.equals(FlowerSelection.scopeKey("https://other.example.com", "me@example.com")), "servers stay isolated");
        check(!scope.equals(FlowerSelection.scopeKey("https://example.com", "other@example.com")), "accounts stay isolated");
        check(!scope.equals(FlowerSelection.scopeKey("http://example.com", "me@example.com")), "different schemes stay isolated");
        check(!scope.equals(FlowerSelection.scopeKey("https://example.com:56507", "me@example.com")), "different ports stay isolated");
        check(!scope.contains("example") && !scope.contains("@"), "scope name contains no account or server text");
        for (String invalid : new String[]{null, "", "example.com", "https://example.com/api"}) {
            check(FlowerSelection.scopeKey(invalid, "me@example.com") == null, "invalid server disables storage");
        }
        check(FlowerSelection.scopeKey("https://example.com", " ") == null, "empty account disables storage");
        check(FlowerSelection.scopeKey("https://example.com", null) == null, "missing account disables storage");

        Map<String, Integer> disk = new HashMap<>();
        FlowerSelection selection = new FlowerSelection(4, disk);
        for (String task : new String[]{"task-one", "task-two", "polygenelubricants", ""}) {
            same(Math.floorMod(task.hashCode(), 4), selection.indexFor(task), "unselected tasks preserve the original flower");
        }
        same(0, selection.indexFor(null), "null task preserves original fallback");
        int fallback = selection.indexFor("task-one");
        int chosen = (fallback + 1) % 4;
        check(selection.remember("task-one", chosen, values -> { disk.clear(); disk.putAll(values); return true; }), "successful persistence accepts the selection");
        same(chosen, selection.indexFor("task-one"), "selected flower overrides hash");
        same(chosen, new FlowerSelection(4, disk).indexFor("task-one"), "a recreated selection loads the saved flower");
        same(Math.floorMod("task-one".hashCode(), 4), new FlowerSelection(4, new HashMap<>()).indexFor("task-one"), "another scope has no selection");
        check(!disk.keySet().iterator().next().contains("task-one"), "stored keys contain no raw task id");
        for (int index = 0; index < 4; index++) {
            check(selection.remember("flower-" + index, index, values -> { disk.clear(); disk.putAll(values); return true; }), "each displayed flower can be saved");
            same(index, new FlowerSelection(4, disk).indexFor("flower-" + index), "each displayed flower survives reload");
        }
        for (int index : new int[]{-1, 4, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            check(!selection.remember("task-one", index, values -> { throw new AssertionError("invalid index reached storage"); }), "invalid flower rejected");
        }
        for (String task : new String[]{null, "", " "}) {
            check(!selection.remember(task, 1, values -> { throw new AssertionError("empty task reached storage"); }), "empty task rejected");
        }
        same(chosen, selection.indexFor("task-one"), "invalid input does not change confirmed flower");

        check(!selection.remember("task-one", (chosen + 1) % 4, values -> false), "failed persistence is reported");
        same(chosen, selection.indexFor("task-one"), "failed persistence retains confirmed flower");
        check(!selection.remember("task-one", (chosen + 1) % 4, values -> { throw new IllegalStateException("storage unavailable"); }), "storage exception is reported as failure");
        same(chosen, selection.indexFor("task-one"), "storage exception retains confirmed flower");
        check(selection.remember("task-two", 2, values -> { disk.clear(); disk.putAll(values); return true; }), "later save succeeds");
        same(chosen, new FlowerSelection(4, disk).indexFor("task-one"), "later save excludes the earlier failed change");
        same(2, new FlowerSelection(4, disk).indexFor("task-two"), "later save survives reload");

        Map<String, Object> corrupt = new HashMap<>(disk);
        corrupt.put(FlowerSelection.taskKey("task-one"), 99);
        corrupt.put(FlowerSelection.taskKey("task-two"), "2");
        FlowerSelection restored = new FlowerSelection(4, corrupt);
        same(fallback, restored.indexFor("task-one"), "invalid stored index falls back safely");
        same(Math.floorMod("task-two".hashCode(), 4), restored.indexFor("task-two"), "invalid stored type falls back safely");
        checkExpandedCatalogKeepsLegacyIdentity(disk);
        System.out.println("FlowerSelectionTest: " + assertions + " assertions passed");
    }

    private static void checkExpandedCatalogKeepsLegacyIdentity(Map<String, Integer> legacyDisk) {
        Map<String, Integer> expandedDisk = new HashMap<>(legacyDisk);
        FlowerSelection expanded = new FlowerSelection(12, expandedDisk);
        FlowerSelection original = new FlowerSelection(4, legacyDisk);
        for (String task : new String[]{"task-one", "task-two", "polygenelubricants", "", null}) {
            same(original.indexFor(task), expanded.indexFor(task), "expansion preserves saved and fallback identities");
        }
        for (int i = 0; i < 100; i++) {
            String task = "legacy-task-" + i;
            same(Math.floorMod(task.hashCode(), 4), expanded.indexFor(task), "new catalog does not remap legacy hashes");
        }
        for (int index = 4; index < 12; index++) {
            String task = "new-flower-" + index;
            check(expanded.remember(task, index, values -> { expandedDisk.clear(); expandedDisk.putAll(values); return true; }), "new flowers can be selected");
            same(index, new FlowerSelection(12, expandedDisk).indexFor(task), "new flowers survive reload");
            same(Math.floorMod(task.hashCode(), 4), new FlowerSelection(12, new HashMap<>()).indexFor(task), "new selections do not leak to another scope");
        }
        check(!expanded.remember("task-one", 12, values -> { throw new AssertionError("out-of-range flower reached storage"); }), "index after final flower is rejected");
        same(original.indexFor("task-one"), expanded.indexFor("task-one"), "rejected selection preserves old flower");
        check(!expanded.remember("new-flower-4", 11, values -> false), "new flower failed write is reported");
        same(4, new FlowerSelection(12, expandedDisk).indexFor("new-flower-4"), "new flower failed write preserves confirmed identity");
    }

    private static void same(int expected, int actual, String label) { check(expected == actual, label); }
    private static void check(boolean value, String label) { assertions++; if (!value) throw new AssertionError(label); }
}
