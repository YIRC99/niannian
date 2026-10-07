package dev.niannian.reminder;

import android.app.Activity;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** A ringing surface only: neither dismissal nor opening a task completes it. */
public final class ReminderAlarmActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String scope = "", planId = "", taskId = "";
    private TextView status;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!ReminderAlarmService.isRinging(scope, planId)) { finish(); return; }
            long remaining = ReminderAlarmService.remainingMillis(scope, planId);
            status.setText("正在提醒你 · " + ((remaining + 999L) / 1000L) + " 秒后自动停铃");
            handler.postDelayed(this, 500L);
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        else getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        render(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); render(intent); }

    private void render(Intent intent) {
        scope = value(intent, "scope"); planId = value(intent, "plan_id"); taskId = value(intent, "task_id");
        boolean probe = intent.getBooleanExtra("probe", false);
        if (probe) taskId = "";
        if (!ReminderAlarmService.isRinging(scope, planId)) { finish(); return; }
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(0xfff7f8f2);
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setGravity(Gravity.CENTER);
        body.setPadding(dp(28), dp(48), dp(28), dp(36));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -1));
        body.addView(text("念念 · 闹钟提醒", 16, 0xff527a58, false));
        TextView clock = text(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")), 54, 0xff173d29, true);
        LinearLayout.LayoutParams clockLayout = new LinearLayout.LayoutParams(-1, -2); clockLayout.setMargins(0, dp(28), 0, dp(18)); body.addView(clock, clockLayout);
        body.addView(text(value(intent, "title"), 26, 0xff283c32, true));
        status = text("正在提醒你", 14, 0xff68786d, false);
        LinearLayout.LayoutParams statusLayout = new LinearLayout.LayoutParams(-1, -2); statusLayout.setMargins(0, dp(18), 0, dp(40)); body.addView(status, statusLayout);
        Button stop = button("停止响铃", true);
        stop.setOnClickListener(v -> { ReminderAlarmService.stopForPlan(this, scope, planId); finish(); });
        body.addView(stop, new LinearLayout.LayoutParams(-1, dp(58)));
        Button open = button(probe ? "返回念念" : "查看这件小事", false); open.setOnClickListener(v -> openTask());
        LinearLayout.LayoutParams openLayout = new LinearLayout.LayoutParams(-1, dp(54)); openLayout.setMargins(0, dp(12), 0, 0); body.addView(open, openLayout);
        TextView hint = text("停铃不会完成事项", 13, 0xff68786d, false);
        LinearLayout.LayoutParams hintLayout = new LinearLayout.LayoutParams(-1, -2); hintLayout.setMargins(0, dp(20), 0, 0); body.addView(hint, hintLayout);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            body.setPadding(dp(28) + insets.getSystemWindowInsetLeft(), dp(48) + insets.getSystemWindowInsetTop(),
                    dp(28) + insets.getSystemWindowInsetRight(), dp(36) + insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(scroll); scroll.requestApplyInsets();
        handler.removeCallbacks(tick); handler.post(tick);
    }

    private void openTask() {
        ReminderAlarmService.stopForPlan(this, scope, planId);
        Runnable show = () -> {
            if (ReminderCoordinator.acceptsIntent(new Session(this), scope)) {
                try { ReminderPlatform.showIntent(this, scope, taskId).send(); } catch (PendingIntent.CanceledException ignored) { }
            }
            finish();
        };
        KeyguardManager lock = getSystemService(KeyguardManager.class);
        if (Build.VERSION.SDK_INT >= 26 && lock.isKeyguardLocked()) {
            handler.removeCallbacks(tick);
            lock.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
                @Override public void onDismissSucceeded() { show.run(); }
                @Override public void onDismissCancelled() { finish(); }
                @Override public void onDismissError() { finish(); }
            });
        } else show.run();
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setGravity(Gravity.CENTER); if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return view;
    }
    private Button button(String label, boolean primary) {
        Button button = new Button(this); button.setText(label); button.setAllCaps(false); button.setTextSize(17);
        button.setTextColor(primary ? 0xfffffdf7 : 0xff527a58); button.setMinHeight(dp(48)); button.setElevation(0);
        GradientDrawable background = new GradientDrawable(); background.setColor(primary ? 0xff527a58 : 0xffe8efe0); background.setCornerRadius(dp(20));
        button.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x22527a58), background, null)); return button;
    }
    private String value(Intent intent, String key) { String value = intent.getStringExtra(key); return value == null ? "" : value; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override public void onBackPressed() { ReminderAlarmService.stopForPlan(this, scope, planId); super.onBackPressed(); }
    @Override protected void onDestroy() { handler.removeCallbacks(tick); super.onDestroy(); }
}
