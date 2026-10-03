package dev.niannian.reminder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Local flower choices; no Android dependency so isolation and failed writes can be checked. */
final class FlowerSelection {
    interface Writer { boolean save(Map<String, Integer> values); }

    private final int flowerCount;
    private final Map<String, Integer> confirmed = new HashMap<>();

    FlowerSelection(int flowerCount, Map<String, ?> stored) {
        if (flowerCount < 1) throw new IllegalArgumentException("Flower count must be positive");
        this.flowerCount = flowerCount;
        if (stored != null) for (Map.Entry<String, ?> entry : stored.entrySet()) {
            if (entry.getKey() != null && entry.getKey().matches("task_[0-9a-f]{64}")
                    && entry.getValue() instanceof Integer && validIndex((Integer) entry.getValue())) {
                confirmed.put(entry.getKey(), (Integer) entry.getValue());
            }
        }
    }

    static String scopeKey(String server, String account) {
        if (account == null || account.trim().isEmpty()) return null;
        try {
            String origin = SessionPolicy.normalizeOrigin(server);
            String email = account.trim().toLowerCase(Locale.ROOT);
            return "garden_flowers_v1_" + digest(origin.length() + ":" + origin + email);
        } catch (IOException invalid) {
            return null;
        }
    }

    static String taskKey(String taskId) {
        return taskId == null || taskId.trim().isEmpty() ? null : "task_" + digest(taskId);
    }

    int indexFor(String taskId) {
        Integer chosen = confirmed.get(taskKey(taskId));
        return chosen != null ? chosen : taskId == null ? 0 : Math.floorMod(taskId.hashCode(), flowerCount);
    }

    boolean remember(String taskId, int index, Writer writer) {
        String key = taskKey(taskId);
        if (key == null || !validIndex(index) || writer == null) return false;
        if (Integer.valueOf(index).equals(confirmed.get(key))) return true;
        Map<String, Integer> next = new HashMap<>(confirmed);
        next.put(key, index);
        try {
            if (!writer.save(Collections.unmodifiableMap(next))) return false;
        } catch (RuntimeException failed) {
            return false;
        }
        confirmed.clear();
        confirmed.putAll(next);
        return true;
    }

    private boolean validIndex(int index) { return index >= 0 && index < flowerCount; }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            char[] alphabet = "0123456789abcdef".toCharArray();
            char[] hex = new char[bytes.length * 2];
            for (int i = 0; i < bytes.length; i++) {
                hex[i * 2] = alphabet[(bytes[i] & 0xff) >>> 4];
                hex[i * 2 + 1] = alphabet[bytes[i] & 0x0f];
            }
            return new String(hex);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
