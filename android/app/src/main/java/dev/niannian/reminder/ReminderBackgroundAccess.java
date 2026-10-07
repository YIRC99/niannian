package dev.niannian.reminder;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Toast;
import org.json.JSONObject;

/** Public system settings only. A review acknowledgement is never reported as a permission grant. */
final class ReminderBackgroundAccess {
    private ReminderBackgroundAccess() { }
    private static boolean xiaomi() { return ReminderBackgroundPolicy.isXiaomi(Build.MANUFACTURER, Build.BRAND); }
    private static boolean exempt(Context context) {
        PowerManager power = context.getSystemService(PowerManager.class);
        return power != null && power.isIgnoringBatteryOptimizations(context.getPackageName());
    }
    private static boolean restricted(Context context) {
        ActivityManager manager = context.getSystemService(ActivityManager.class);
        return Build.VERSION.SDK_INT >= 28 && manager != null && manager.isBackgroundRestricted();
    }
    static boolean needsSetup(Context context) {
        JSONObject settings = ReminderCoordinator.settings(context);
        boolean phone = false;
        if (ReminderCoordinator.configured(context) && settings.optBoolean("enabled")) {
            for (String channel : ReminderCoordinator.selectedChannels(settings))
                if ("notification".equals(channel) || "alarm".equals(channel)) phone = true;
        }
        boolean reviewed = context.getSharedPreferences("reminder_background_v1", Context.MODE_PRIVATE).getBoolean("reviewed", false);
        return ReminderBackgroundPolicy.needsSetup(phone, exempt(context), restricted(context), xiaomi(), reviewed);
    }
    static void reviewed(Context context) {
        context.getSharedPreferences("reminder_background_v1", Context.MODE_PRIVATE).edit().putBoolean("reviewed", true).apply();
    }
    static String status(Context context) {
        if (restricted(context)) return "系统正在限制后台运行";
        if (!exempt(context)) return "后台省电限制尚未解除";
        return xiaomi() ? "省电限制已解除；自启动需在系统设置核对" : "省电限制已解除";
    }
    static String instructions(Context context) {
        return status(context) + (xiaomi()
                ? "\n\n在念念的应用信息中：\n1. 开启「自启动」\n2. 省电策略选择「无限制」\n\n仅允许通知和闹钟，仍可能被一键清理停止。设置后可在提醒设置中测试通知和闹钟。"
                : "\n\n在念念的应用信息中，将电池使用设为「不受限制」或「允许后台运行」。如果手机提供自启动开关，也请开启。设置后可在提醒设置中测试通知和闹钟。")
                + "\n\n返回桌面、切换应用无需保持页面打开。系统设置中的「强行停止」仍会撤销提醒，需重新打开念念恢复。";
    }
    static void openSettings(Activity activity) {
        try {
            activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + activity.getPackageName())));
        } catch (RuntimeException unavailable) {
            Toast.makeText(activity, "请在手机设置 → 应用 → 念念提醒中检查后台权限", Toast.LENGTH_LONG).show();
        }
    }
}
