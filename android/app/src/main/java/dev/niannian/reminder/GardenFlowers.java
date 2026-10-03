package dev.niannian.reminder;

/** One stable flower identity shared by task lists, garden plots and growth feedback. */
public final class GardenFlowers {
    // This ordering is part of the task-to-flower identity; do not reorder on display changes.
    private static final int[] RESOURCES = {
            R.drawable.garden_flower_tulip,
            R.drawable.garden_flower_daisy,
            R.drawable.garden_flower_lupine,
            R.drawable.garden_flower_hydrangea
    };
    private static final String[] NAMES = {"郁金香", "雏菊", "羽扇豆", "绣球"};

    private GardenFlowers() { }

    public static int resourceFor(String taskId) {
        return RESOURCES[indexFor(taskId)];
    }

    public static String nameFor(String taskId) {
        return NAMES[indexFor(taskId)];
    }

    public static int[] resources() {
        return RESOURCES.clone();
    }

    public static String[] names() {
        return NAMES.clone();
    }

    private static int indexFor(String taskId) {
        return taskId == null ? 0 : Math.floorMod(taskId.hashCode(), RESOURCES.length);
    }
}
