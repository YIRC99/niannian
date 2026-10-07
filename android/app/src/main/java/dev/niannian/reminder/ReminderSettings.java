package dev.niannian.reminder;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.TimeZone;

/** Expandable channel controls and device-specific background guidance. */
final class ReminderSettings {
    private static final int INK = 0xff294c36, MUTED = 0xff687565, GREEN = 0xff537957, PALE = 0xffedf4e9;
    static View view(Activity activity, Session session, Runnable changed) { return new Panel(activity, session, changed); }

    private static final class Panel extends LinearLayout {
        final Activity activity; final Session session; final Runnable changed;
        final LinearLayout detail; final TextView summary, status; final Switch enabled;
        final Switch[] channels = new Switch[4]; final TextView[] permission = new TextView[3];
        final Button[] access = new Button[3]; final Button interval, save;
        final String[] names = {"手机通知", "手机日历", "闹钟提醒", "邮件（可选）"};
        final String[] keys = {"notification", "calendar", "alarm", "email"};
        int minutes = 5;
        boolean dirty, updating;
        final SharedPreferences preferences;
        final SharedPreferences.OnSharedPreferenceChangeListener listener;

        Panel(Activity activity, Session session, Runnable changed) {
            super(activity); this.activity = activity; this.session = session; this.changed = changed;
            preferences = activity.getSharedPreferences("reminder_execution_v1", Activity.MODE_PRIVATE);
            setOrientation(VERTICAL); setPadding(dp(12), dp(8), dp(12), dp(10));
            GradientDrawable background = new GradientDrawable(); background.setColor(0xfff4f7ef); background.setCornerRadius(dp(14)); background.setStroke(dp(1), 0xffdbe6d6); setBackground(background);
            TextView title = label("念念可用的提醒方式  ›", 15, true); title.setMinHeight(dp(48)); title.setGravity(android.view.Gravity.CENTER_VERTICAL); addView(title);
            summary = label("正在读取渠道状态…", 12, false); addView(summary);
            detail = new LinearLayout(activity); detail.setOrientation(VERTICAL); detail.setVisibility(GONE); addView(detail);
            title.setOnClickListener(view -> { boolean open = detail.getVisibility() != VISIBLE; detail.setVisibility(open ? VISIBLE : GONE); title.setText(open ? "念念可用的提醒方式  ⌄" : "念念可用的提醒方式  ›"); refresh(); });
            summary.setOnClickListener(view -> title.performClick());
            Button backgroundAccess = button("检查后台提醒设置"); addView(backgroundAccess);
            backgroundAccess.setOnClickListener(view -> new android.app.AlertDialog.Builder(activity)
                    .setTitle("退出后，也能提醒你").setMessage(ReminderBackgroundAccess.instructions(activity))
                    .setPositiveButton("去系统设置", (dialog, which) -> ReminderBackgroundAccess.openSettings(activity))
                    .setNeutralButton("我已检查", (dialog, which) -> ReminderBackgroundAccess.reviewed(activity))
                    .setNegativeButton("返回", null).show());
            detail.addView(label("开启后，念念接管当前账号的提醒渠道；原邮件提醒改为按这里的选择发送，邮件默认关闭。", 12, false));
            enabled = toggle("让念念安排提醒"); detail.addView(enabled);
            for (int i = 0; i < keys.length; i++) {
                final int index = i;
                channels[i] = toggle(names[i]); detail.addView(channels[i]);
                if (i < 3) {
                    LinearLayout row = new LinearLayout(activity); row.setOrientation(HORIZONTAL); row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                    permission[i] = label("", 12, false); row.addView(permission[i], new LinearLayout.LayoutParams(0, -2, 1));
                    access[i] = button("去授权"); row.addView(access[i]); detail.addView(row);
                    access[i].setOnClickListener(view -> { ReminderPlatform.request(activity, keys[index]); });
                }
            }
            detail.addView(label("闹钟提前交给系统定时，到点持续响铃，手动停止或 60 秒后自动停止。日历只写入念念的本地日历，是否弹出由日历应用决定。", 12, false));
            interval = button("最短间隔 5 分钟");
            detail.addView(interval);
            interval.setOnClickListener(view -> {
                int[] values = {1, 2, 5, 10, 15, 30, 60}; String[] labels = {"1 分钟", "2 分钟", "5 分钟", "10 分钟", "15 分钟", "30 分钟", "60 分钟"};
                new android.app.AlertDialog.Builder(activity).setTitle("同一事项至少间隔多久")
                        .setItems(labels, (dialog, which) -> { minutes = values[which]; dirty = true; refreshText(); }).show();
            });
            detail.addView(label("同一事项每天最多 32 次，本机每天最多 128 次。已确认的通知和闹钟流程会提前交给手机，断网也能按已保存的步骤跟进；邮件及后续新流程需要联网。完成或暂停会撤销本机后续提醒，日历同一事项每天一项。强行停止应用会清除系统定时。", 12, false));
            status = label("", 12, false); detail.addView(status);
            save = button("保存渠道安排"); save.setTextColor(0xffffffff); save.setBackgroundTintList(ColorStateList.valueOf(GREEN)); detail.addView(save);
            save.setOnClickListener(view -> save());
            Button refresh = button("重新同步"); detail.addView(refresh);
            refresh.setOnClickListener(view -> { ReminderCoordinator.sync(activity, session); status.setText("正在同步…"); refresh(); });
            Button testNotification=button("10 秒后测试通知"),test=button("10 秒后测试闹钟"),cancelTest=button("取消测试");
            detail.addView(testNotification);detail.addView(test);detail.addView(cancelTest);
            testNotification.setOnClickListener(view->{try{ReminderCoordinator.testNotification(activity,session);status.setText("测试通知已交给系统，10 秒后提醒；可返回桌面等待。");}catch(Exception failure){status.setText(failure.getMessage());}});
            test.setOnClickListener(view->{try{ReminderCoordinator.testAlarm(activity,session);status.setText("测试闹钟已交给系统，10 秒后响铃；可返回桌面等待。");}catch(Exception failure){status.setText(failure.getMessage());}});
            cancelTest.setOnClickListener(view->{ReminderCoordinator.cancelTestAlarm(activity);status.setText("测试提醒已取消。");});
            listener = (prefs, key) -> {
                if ("settings".equals(key) || "configured".equals(key) || "status".equals(key) || "calendar_cleanup".equals(key)) activity.runOnUiThread(this::refresh);
            };
            refresh();
        }

        @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); preferences.registerOnSharedPreferenceChangeListener(listener); refresh(); }
        @Override protected void onDetachedFromWindow() { preferences.unregisterOnSharedPreferenceChangeListener(listener); super.onDetachedFromWindow(); }
        @Override public void onWindowFocusChanged(boolean focus) { super.onWindowFocusChanged(focus); if (focus) refresh(); }

        private Switch toggle(String text) {
            Switch toggle = new Switch(activity); toggle.setText(text); toggle.setTextSize(14); toggle.setTextColor(INK); toggle.setMinHeight(dp(48));
            toggle.setThumbTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}}, new int[]{GREEN, 0xffaab1a5}));
            toggle.setOnCheckedChangeListener((button, value) -> { if (!updating) { dirty = true; refresh(); } });
            return toggle;
        }

        private void refresh() {
            if (!ReminderCoordinator.acceptsIntent(session, preferences.getString("scope", ""))) { setVisibility(GONE); return; }
            if (!dirty) {
                updating = true;
                JSONObject settings = ReminderCoordinator.settings(activity);
                enabled.setChecked(!ReminderCoordinator.configured(activity) || settings.optBoolean("enabled", false));
                String[] selected = ReminderCoordinator.selectedChannels(settings);
                for (int i = 0; i < keys.length; i++) {
                    channels[i].setChecked(ReminderSetupPolicy.isSelected(ReminderCoordinator.configured(activity), selected, keys[i]));
                }
                minutes = settings.optInt("min_interval_minutes", 5);
                updating = false;
            }
            JSONObject capabilities = ReminderPlatform.capabilities(activity);
            StringBuilder compact = new StringBuilder();
            compact.append(!ReminderCoordinator.configured(activity) ? "待开启 · " : dirty ? "待保存 · " : enabled.isChecked() ? "已开启 · " : "已暂停 · ");
            for (int i = 0; i < keys.length; i++) {
                if (i > 0) compact.append(" · ");
                compact.append(i == 0 ? "通知" : i == 1 ? "日历" : i == 2 ? "闹钟" : "邮件");
                compact.append(!enabled.isChecked() || !channels[i].isChecked() ? "未启用" : i == 3 || capabilities.optBoolean(keys[i]) ? "可用" : "待授权");
                if (i < 3) {
                    boolean granted = capabilities.optBoolean(keys[i]);
                    permission[i].setText(granted ? "系统已授权" : i == 2 && !capabilities.optBoolean("exact_alarm") ? "需要精确闹钟权限" : "系统未授权或此手机不支持");
                    access[i].setText(granted ? "系统设置" : "去授权");
                }
            }
            if (!ReminderPlatform.exact(activity)) compact.append("\n通知时间可能延迟；闹钟需单独授权");
            summary.setText(compact.toString());
            status.setText(ReminderCoordinator.status(activity) + (ReminderCoordinator.hasCalendarCleanup(activity) ? "\n旧日历项尚未清理：请重新授予日历权限，或在系统日历中删除“念念提醒”日历。" : ""));
            save.setText(ReminderCoordinator.configured(activity) ? "保存渠道安排" : enabled.isChecked() ? "启用所选提醒方式" : "暂停提醒并接管邮件");
            refreshText();
        }

        private void refreshText() {
            if (interval != null) interval.setText("最短间隔 " + minutes + " 分钟");
        }
        private void save() {
            try {
                JSONArray selected = new JSONArray();
                for (int i = 0; i < keys.length; i++) if (channels[i].isChecked()) selected.put(keys[i]);
                if (enabled.isChecked() && selected.length() == 0) { status.setText("至少选择一种提醒方式，或关闭安排"); return; }
                JSONObject settings = new JSONObject().put("enabled", enabled.isChecked()).put("channels", selected)
                        .put("quiet_start", "").put("quiet_end", "")
                        .put("min_interval_minutes", minutes).put("timezone", TimeZone.getDefault().getID());
                controlsEnabled(detail, false); status.setText("正在保存并同步…");
                ReminderCoordinator.saveSettings(activity, session, settings, (success, message) -> activity.runOnUiThread(() -> {
                    if (!isAttachedToWindow()) return;
                    controlsEnabled(detail, true); if (success) dirty = false;
                    refresh(); status.setText(message);
                    if (success && changed != null) changed.run();
                }));
            } catch (Exception failure) { controlsEnabled(detail, true); status.setText("设置未保存，请重试"); }
        }
        private void controlsEnabled(View view, boolean enabled) {
            view.setEnabled(enabled);
            if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) controlsEnabled(((ViewGroup) view).getChildAt(i), enabled);
        }
        private TextView label(String text, int size, boolean strong) {
            TextView label = new TextView(activity); label.setText(text); label.setTextSize(size); label.setTextColor(strong ? INK : MUTED); label.setPadding(0, dp(4), 0, dp(4));
            if (strong) label.setTypeface(null, Typeface.BOLD); return label;
        }
        private Button button(String text) {
            Button button = new Button(activity); button.setText(text); button.setTextSize(13); button.setTextColor(INK); button.setAllCaps(false); button.setMinHeight(dp(48)); button.setBackgroundTintList(ColorStateList.valueOf(PALE)); return button;
        }
        private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    }
}
