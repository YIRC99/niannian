package dev.niannian.reminder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import org.json.JSONObject;

/** Lives only while an authorized alarm is ringing. Scheduling stays with AlarmManager. */
public final class ReminderAlarmService extends Service {
    static final String TAG = "NiannianAlarm";
    private static final String START = "dev.niannian.reminder.RING", STOP = "dev.niannian.reminder.STOP_RING";
    private static final int NOTICE_ID = 62103;
    private static final long MAX_RING_MS = 60_000L;
    private static volatile ReminderAlarmService active;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile String scope = "", planId = "";
    private volatile boolean running;
    private boolean playbackStarted;
    private boolean probe;
    private volatile long endsAt;
    private volatile MediaPlayer player;
    private Vibrator vibrator;
    private PowerManager.WakeLock wakeLock;
    private AudioManager audio;
    private AudioFocusRequest focus;
    private final Runnable timeout = () -> finishRing("timeout");
    private final Runnable authorizationCheck = new Runnable() {
        @Override public void run() {
            if (!running) return;
            if (!ReminderCoordinator.isCurrentAlarm(ReminderAlarmService.this, scope, planId)) finishRing("no_longer_current");
            else handler.postDelayed(this, 1000L);
        }
    };

    static void start(Context context, String scope, JSONObject plan) {
        context.startForegroundService(new Intent(context, ReminderAlarmService.class).setAction(START)
                .putExtra("scope", scope).putExtra("plan_id", plan.optString("id"))
                .putExtra("task_id", plan.optString("task_id"))
                .putExtra("title", plan.optString("title", "念念提醒"))
                .putExtra("probe", plan.optBoolean("_diagnostic", false)));
    }

    /** Never starts a service merely to stop it, and never stops another task's alarm. */
    static void stopForPlan(Context context, String expectedScope, String expectedId) {
        ReminderAlarmService service = active;
        if (service == null) return;
        service.handler.post(() -> {
            if (service.matches(expectedScope, expectedId)) service.finishRing("cancelled");
        });
    }

    static boolean isRinging(String scope, String planId) {
        ReminderAlarmService service = active;
        return service != null && service.running && service.matches(scope, planId);
    }

    static long remainingMillis(String scope, String planId) {
        ReminderAlarmService service = active;
        return service != null && service.running && service.matches(scope, planId)
                ? Math.max(0L, service.endsAt - SystemClock.elapsedRealtime()) : 0L;
    }

    static boolean isAudioPlaying(String scope, String planId) {
        ReminderAlarmService service = active;
        if (service == null || !service.running || !service.matches(scope, planId)) return false;
        MediaPlayer current = service.player;
        try { return current != null && current.isPlaying(); }
        catch (RuntimeException released) { return false; }
    }

    private boolean matches(String expectedScope, String expectedId) {
        return !scope.isEmpty() && scope.equals(expectedScope) && planId.equals(expectedId);
    }

    @Override public void onCreate() { super.onCreate(); active = this; }
    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(startId); return START_NOT_STICKY; }
        String nextScope = intent.getStringExtra("scope"), nextId = intent.getStringExtra("plan_id");
        if (STOP.equals(intent.getAction())) {
            if (matches(nextScope, nextId)) finishRing("user_stopped");
            else if (!running) stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (!START.equals(intent.getAction()) || nextScope == null || nextId == null
                || !ReminderCoordinator.isCurrentAlarm(this, nextScope, nextId)
                || !ReminderPlatform.exact(this) || !ReminderPlatform.notifications(this, ReminderPlatform.ALARM)) {
            log("start_rejected");
            if (nextScope != null && nextId != null) ReminderCoordinator.alarmPlaybackFailed(this, nextScope, nextId);
            if (!running) stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (running && matches(nextScope, nextId)) return START_NOT_STICKY;
        releaseRing();
        scope = nextScope; planId = nextId; probe = intent.getBooleanExtra("probe", false);
        running = true; endsAt = SystemClock.elapsedRealtime() + MAX_RING_MS;
        try {
            ReminderPlatform.channels(this);
            Notification notice = notification(intent);
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTICE_ID, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED);
            else startForeground(NOTICE_ID, notice);
            log("service_started");
            PowerManager power = getSystemService(PowerManager.class);
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getPackageName() + ":alarm-ring");
            wakeLock.acquire(MAX_RING_MS + 2000L);
            handler.postDelayed(timeout, MAX_RING_MS);
            handler.postDelayed(authorizationCheck, 1000L);
            startAudio();
        } catch (RuntimeException failure) {
            log("service_failed");
            finishRing("start_failure");
        }
        return START_NOT_STICKY;
    }

    private Notification notification(Intent source) {
        Intent stop = new Intent(this, ReminderAlarmService.class).setAction(STOP)
                .setData(identity("stop")).putExtra("scope", scope).putExtra("plan_id", planId);
        PendingIntent stopAction = PendingIntent.getService(this, 0, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent open = new Intent(this, ReminderAlarmActivity.class).setAction(START).setData(identity("ring"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("scope", scope).putExtra("plan_id", planId).putExtra("task_id", source.getStringExtra("task_id"))
                .putExtra("title", source.getStringExtra("title")).putExtra("probe", probe);
        PendingIntent screen = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification publicNotice = new Notification.Builder(this, ReminderPlatform.ALARM)
                .setSmallIcon(R.drawable.ic_reminder).setContentTitle("念念闹钟").setContentText("有一件小事等你处理")
                .setCategory(Notification.CATEGORY_ALARM).setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "停止响铃", stopAction).build()).build();
        Notification.Builder notice = new Notification.Builder(this, ReminderPlatform.ALARM)
                .setSmallIcon(R.drawable.ic_reminder).setContentTitle(source.getStringExtra("title"))
                .setContentText("闹钟提醒 · 最长响铃一分钟").setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(publicNotice)
                .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(screen).setDeleteIntent(stopAction)
                .addAction(new Notification.Action.Builder(null, "停止响铃", stopAction).build())
                .setTimeoutAfter(MAX_RING_MS);
        // The service owns audio; the notification must not play a second ringtone.
        notice.setSound(null).setVibrate(new long[]{0L});
        if (Build.VERSION.SDK_INT >= 26) notice.setGroup("niannian_alarm_sound").setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT < 34 || manager.canUseFullScreenIntent()) notice.setFullScreenIntent(screen, true);
        return notice.build();
    }

    private Uri identity(String action) {
        return new Uri.Builder().scheme("niannian").authority("alarm").appendPath(action).appendPath(scope).appendPath(planId).build();
    }

    private void startAudio() {
        NotificationChannel channel = getSystemService(NotificationManager.class).getNotificationChannel(ReminderPlatform.ALARM);
        AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
        // Keep user choices: a muted alarm channel and zero alarm volume are never overridden.
        Uri sound = channel == null ? null : channel.getSound();
        if (channel != null && channel.shouldVibrate()) {
            vibrator = getSystemService(Vibrator.class);
            if (vibrator != null && vibrator.hasVibrator())
                vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0L, 500L, 700L}, 0), attributes);
        }
        if (sound == null) { audioFailed("channel_sound_disabled"); return; }
        audio = getSystemService(AudioManager.class);
        final String playingScope = scope, playingId = planId;
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(change -> {
                    if (matches(playingScope, playingId) && (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)) finishRing("audio_focus_lost");
                }, handler).build();
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { audioFailed("audio_focus_denied"); return; }
        try {
            MediaPlayer candidate = new MediaPlayer();
            player = candidate;
            candidate.setAudioAttributes(attributes);
            candidate.setDataSource(this, sound);
            candidate.setLooping(true);
            candidate.setOnPreparedListener(ready -> {
                if (!running || player != ready || SystemClock.elapsedRealtime() >= endsAt
                        || !ReminderCoordinator.isCurrentAlarm(this, scope, planId)) {
                    if (player == ready) finishRing("stale_audio_callback");
                    return;
                }
                try {
                    ready.start();
                    boolean playing = ready.isPlaying();
                    if (playing) playbackStarted = true;
                    log("audio_started playing=" + playing + " alarm_volume=" + audio.getStreamVolume(AudioManager.STREAM_ALARM));
                    if (playing) ReminderCoordinator.alarmPlaybackStarted(this, scope, planId);
                    else audioFailed("audio_not_playing");
                } catch (RuntimeException unavailable) { audioFailed("audio_start_failed"); }
            });
            candidate.setOnErrorListener((failed, what, extra) -> { if (player == failed) audioFailed("audio_failed"); return true; });
            candidate.prepareAsync();
        } catch (Exception unavailable) { audioFailed("audio_prepare_failed"); }
    }

    private void audioFailed(String reason) {
        log(reason);
        finishRing(reason);
    }

    private void log(String state) { Log.i(TAG, "state=" + state + " probe=" + probe + " at=" + System.currentTimeMillis()); }

    private void finishRing(String reason) {
        reportStopped(reason);
        releaseRing();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void reportStopped(String reason) {
        if (running) {
            log("stopped reason=" + reason);
            if ("cancelled".equals(reason) || "user_stopped".equals(reason))
                ReminderCoordinator.alarmDismissed(this, scope, planId);
            // Once audio started, a later interruption must preserve its delivered receipt.
            // Explicit cancellation (including task/account changes) is not a playback failure.
            if (!playbackStarted && !"cancelled".equals(reason) && !"user_stopped".equals(reason)
                    && !"no_longer_current".equals(reason))
                ReminderCoordinator.alarmPlaybackFailed(this, scope, planId);
        }
        ReminderCoordinator.alarmStopped(this, scope, planId);
    }

    private void releasePlayer() {
        MediaPlayer previous = player; player = null;
        if (previous != null) {
            previous.setOnPreparedListener(null); previous.setOnErrorListener(null);
            try { previous.release(); } catch (RuntimeException ignored) { }
        }
    }

    private void releaseRing() {
        running = false;
        playbackStarted = false;
        handler.removeCallbacks(timeout); handler.removeCallbacks(authorizationCheck);
        releasePlayer();
        if (vibrator != null) { vibrator.cancel(); vibrator = null; }
        if (audio != null && focus != null) { audio.abandonAudioFocusRequest(focus); focus = null; }
        if (wakeLock != null) { if (wakeLock.isHeld()) wakeLock.release(); wakeLock = null; }
    }

    @Override public void onDestroy() {
        reportStopped("destroyed");
        releaseRing();
        if (active == this) active = null;
        super.onDestroy();
    }
}
