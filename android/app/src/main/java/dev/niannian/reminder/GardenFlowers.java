package dev.niannian.reminder;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/** One stable flower identity shared by task lists, garden plots and growth feedback. */
public final class GardenFlowers {
    // This ordering is part of the task-to-flower identity; do not reorder on display changes.
    private static final int[] RESOURCES = {
            R.drawable.garden_flower_tulip,
            R.drawable.garden_flower_daisy,
            R.drawable.garden_flower_lupine,
            R.drawable.garden_flower_hydrangea,
            R.drawable.garden_flower_sunflower,
            R.drawable.garden_flower_rose,
            R.drawable.garden_flower_lavender,
            R.drawable.garden_flower_cosmos,
            R.drawable.garden_flower_lily,
            R.drawable.garden_flower_pansy,
            R.drawable.garden_flower_iris,
            R.drawable.garden_flower_hibiscus
    };
    private static final String[] NAMES = {"郁金香", "雏菊", "羽扇豆", "绣球", "向日葵", "玫瑰", "薰衣草", "波斯菊", "百合", "三色堇", "鸢尾", "木槿"};
    private static final String[] TYPES = {"tulip", "daisy", "lupine", "hydrangea", "sunflower", "rose", "lavender", "cosmos", "lily", "pansy", "iris", "hibiscus"};
    // Keep confirmed values across account switches: a failed preferences commit may still alter
    // Android's in-memory preferences, and must not become an accepted choice after switching back.
    private static final Map<String, Scope> SCOPES = new HashMap<>();
    private static Scope current;

    private static final class Scope {
        final SharedPreferences preferences;
        final FlowerSelection selection;
        Scope(SharedPreferences preferences) {
            this.preferences = preferences;
            this.selection = new FlowerSelection(RESOURCES.length, preferences.getAll());
        }
    }

    private GardenFlowers() { }

    /** Select the local store for a normalized server and signed-in email. Blank identity disables it. */
    public static synchronized void configure(Context context, String server, String account) {
        current = null;
        String key = FlowerSelection.scopeKey(server, account);
        if (context == null || key == null) return;
        try {
            Scope scope = SCOPES.get(key);
            if (scope == null) {
                scope = new Scope(context.getApplicationContext().getSharedPreferences(key, Context.MODE_PRIVATE));
                SCOPES.put(key, scope);
            }
            current = scope;
        } catch (RuntimeException unavailable) {
            // A storage failure must not leak the previous account's choices into this account.
        }
    }

    /** Returns true only when this account's choice is confirmed saved on this device. */
    public static synchronized boolean remember(String taskId, int index) {
        if (current == null) return false;
        Scope scope = current;
        return scope.selection.remember(taskId, index, values -> {
            SharedPreferences.Editor editor = scope.preferences.edit().clear();
            for (Map.Entry<String, Integer> entry : values.entrySet()) editor.putInt(entry.getKey(), entry.getValue());
            return editor.commit();
        });
    }

    public static int resourceFor(String taskId) {
        return RESOURCES[indexFor(taskId)];
    }

    public static String id(JSONObject plant) {
        return plant.optString("id", plant.optString("task_id"));
    }

    public static int indexFor(JSONObject plant) {
        String type = plant.optString("flower_type");
        for (int i = 0; i < TYPES.length; i++) if (TYPES[i].equals(type)) return i;
        return indexFor(id(plant));
    }

    public static int resourceFor(JSONObject plant) { return RESOURCES[indexFor(plant)]; }
    public static String nameFor(JSONObject plant) { return NAMES[indexFor(plant)]; }
    public static String typeFor(int index) { return TYPES[Math.floorMod(index, TYPES.length)]; }

    public static String nameFor(String taskId) {
        return NAMES[indexFor(taskId)];
    }

    public static int[] resources() {
        return RESOURCES.clone();
    }

    public static String[] names() {
        return NAMES.clone();
    }

    public static synchronized int indexFor(String taskId) {
        return current != null ? current.selection.indexFor(taskId)
                : taskId == null ? 0 : Math.floorMod(taskId.hashCode(), FlowerSelection.LEGACY_FLOWER_COUNT);
    }
}
