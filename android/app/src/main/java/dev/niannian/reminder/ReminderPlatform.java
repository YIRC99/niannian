package dev.niannian.reminder;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.provider.CalendarContract;
import android.provider.Settings;
import org.json.JSONObject;
import java.util.TimeZone;

/** Only Android system operations; no network calls, clocks kept by AlarmManager. */
final class ReminderPlatform {
    static final String NOTIFICATION = "niannian_reminders_v1", ALARM = "niannian_alarms_v1";
    static final int PERMISSIONS_REQUEST = 6201;

    static void channels(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel normal = new NotificationChannel(NOTIFICATION, "事项提醒", NotificationManager.IMPORTANCE_DEFAULT);
        normal.setDescription("念念安排的事项通知，遵守系统勿扰和通知设置");
        NotificationChannel alarm = new NotificationChannel(ALARM, "闹钟提醒", NotificationManager.IMPORTANCE_HIGH);
        alarm.setDescription("用户允许的定时闹钟提醒；仅响一次，不循环轰炸");
        alarm.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
        manager.createNotificationChannel(normal);
        manager.createNotificationChannel(alarm);
    }

    static boolean notifications(Context context, String channel) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel actual = manager.getNotificationChannel(channel);
        return manager.areNotificationsEnabled() && (actual == null || actual.getImportance() != NotificationManager.IMPORTANCE_NONE);
    }

    static boolean exact(Context context) {
        return Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager.class).canScheduleExactAlarms();
    }

    static boolean calendar(Context context) {
        return context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
                && context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
                && context.getPackageManager().resolveContentProvider(CalendarContract.AUTHORITY, 0) != null;
    }

    static JSONObject capabilities(Context context) {
        JSONObject result = new JSONObject();
        try {
            result.put("notification", notifications(context, NOTIFICATION));
            result.put("calendar", calendar(context));
            result.put("alarm", exact(context) && notifications(context, ALARM));
            result.put("exact_alarm", exact(context));
        } catch (Exception ignored) { }
        return result;
    }

    static void request(Activity activity, String channel) {
        if ("calendar".equals(channel)) {
            if (calendar(activity)) open(activity, new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + activity.getPackageName())));
            else activity.requestPermissions(new String[]{Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR}, PERMISSIONS_REQUEST);
            return;
        }
        if (("exact_alarm".equals(channel) || "alarm".equals(channel)) && !exact(activity)) {
            if (Build.VERSION.SDK_INT >= 31) open(activity, new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + activity.getPackageName())));
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, PERMISSIONS_REQUEST);
        } else {
            Intent intent = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName())
                    .putExtra(Settings.EXTRA_CHANNEL_ID, "alarm".equals(channel) ? ALARM : NOTIFICATION);
            open(activity, intent);
        }
    }

    private static void open(Activity activity, Intent intent) {
        try { activity.startActivity(intent); }
        catch (RuntimeException unavailable) { activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + activity.getPackageName()))); }
    }

    private static PendingIntent alarmIntent(Context context, String scope, JSONObject plan, int flags) {
        Intent intent = new Intent(context, ReminderReceiver.class).setAction("dev.niannian.reminder.FIRE")
                .setData(new Uri.Builder().scheme("niannian").authority("reminder").appendPath(scope).appendPath(plan.optString("id")).build())
                .putExtra("scope", scope).putExtra("plan_id", plan.optString("id"));
        return PendingIntent.getBroadcast(context, 0, intent, flags | PendingIntent.FLAG_IMMUTABLE);
    }

    static PendingIntent showIntent(Context context, String scope, String task) {
        Intent intent = new Intent(context, MainActivity.class).setAction(Intent.ACTION_MAIN)
                .setData(new Uri.Builder().scheme("niannian").authority("task").appendPath(scope).appendPath(task).build())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("reminder_scope", scope).putExtra("reminder_task_id", task);
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void schedule(Context context, String scope, JSONObject plan) throws Exception {
        long at = ReminderPolicy.timestamp(plan.optString("scheduled_at"));
        AlarmManager manager = context.getSystemService(AlarmManager.class);
        PendingIntent pending = alarmIntent(context, scope, plan, PendingIntent.FLAG_UPDATE_CURRENT);
        if ("alarm".equals(plan.optString("channel"))) {
            if (!exact(context) || !notifications(context, ALARM)) throw new SecurityException("闹钟或通知权限未开启");
            manager.setAlarmClock(new AlarmManager.AlarmClockInfo(at, showIntent(context, scope, plan.optString("task_id"))), pending);
        } else if (exact(context)) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending);
        else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending);
    }

    static void cancel(Context context, String scope, JSONObject plan) {
        cancel(context, scope, plan, true);
    }

    static void cancel(Context context, String scope, JSONObject plan, boolean removeNotice) {
        PendingIntent pending = alarmIntent(context, scope, plan, PendingIntent.FLAG_NO_CREATE);
        if (pending != null) { context.getSystemService(AlarmManager.class).cancel(pending); pending.cancel(); }
        if (removeNotice) context.getSystemService(NotificationManager.class).cancel(scope + ":" + plan.optString("id"), 1);
        long eventId = plan.optLong("local_event_id", -1);
        if (eventId > 0 && calendar(context)) {
            try {
                if (ownsCalendarItem(context, eventId, false, scope))
                    context.getContentResolver().delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId), null, null);
            }
            catch (RuntimeException unavailable) { ReminderCoordinator.calendarCleanup(context, eventId, false); }
        } else if (eventId > 0) ReminderCoordinator.calendarCleanup(context, eventId, false);
    }

    static boolean ownsCalendarItem(Context context, long id, boolean calendar, String scope) {
        if (scope.isEmpty()) return false;
        Uri uri = ContentUris.withAppendedId(calendar ? CalendarContract.Calendars.CONTENT_URI : CalendarContract.Events.CONTENT_URI, id);
        String[] projection = calendar ? new String[]{CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.ACCOUNT_TYPE}
                : new String[]{CalendarContract.Events.CUSTOM_APP_PACKAGE, CalendarContract.Events.CUSTOM_APP_URI};
        try (Cursor cursor = context.getContentResolver().query(uri, projection, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) return false;
            return calendar ? (context.getPackageName() + ":" + scope).equals(cursor.getString(0)) && CalendarContract.ACCOUNT_TYPE_LOCAL.equals(cursor.getString(1))
                    : context.getPackageName().equals(cursor.getString(0)) && cursor.getString(1) != null && cursor.getString(1).startsWith("niannian://reminder/" + scope + "/");
        }
    }

    static void notify(Context context, String scope, JSONObject plan) {
        boolean alarm = "alarm".equals(plan.optString("channel"));
        String channel = alarm ? ALARM : NOTIFICATION;
        if (!notifications(context, channel)) throw new SecurityException("系统通知已关闭");
        Notification notification = new Notification.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_reminder).setContentTitle(plan.optString("title", "念念提醒"))
                .setContentText(plan.optString("body", "该处理这件小事了"))
                .setStyle(new Notification.BigTextStyle().bigText(plan.optString("body")))
                .setContentIntent(showIntent(context, scope, plan.optString("task_id")))
                .setCategory(alarm ? Notification.CATEGORY_ALARM : Notification.CATEGORY_REMINDER)
                .setVisibility(Notification.VISIBILITY_PRIVATE).setAutoCancel(true).setOnlyAlertOnce(true)
                .setTimeoutAfter(60 * 60 * 1000L).build();
        context.getSystemService(NotificationManager.class).notify(scope + ":" + plan.optString("id"), 1, notification);
    }

    static long createCalendar(Context context, String scope) {
        if (!calendar(context)) throw new SecurityException("日历权限未开启");
        ContentValues values = new ContentValues();
        String account = context.getPackageName() + ":" + scope;
        values.put(CalendarContract.Calendars.ACCOUNT_NAME, account);
        values.put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL);
        values.put(CalendarContract.Calendars.NAME, "niannian-" + scope);
        values.put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, "念念提醒");
        values.put(CalendarContract.Calendars.CALENDAR_COLOR, 0xff54765b);
        values.put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER);
        values.put(CalendarContract.Calendars.OWNER_ACCOUNT, account);
        values.put(CalendarContract.Calendars.VISIBLE, 1); values.put(CalendarContract.Calendars.SYNC_EVENTS, 1);
        values.put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().getID());
        Uri uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
                .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account)
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL).build();
        // A process may have died after insertion, before saving the ID. Recover only our own local calendar.
        try (Cursor cursor = context.getContentResolver().query(CalendarContract.Calendars.CONTENT_URI,
                new String[]{CalendarContract.Calendars._ID}, CalendarContract.Calendars.ACCOUNT_NAME + "=? AND " + CalendarContract.Calendars.ACCOUNT_TYPE + "=?",
                new String[]{account, CalendarContract.ACCOUNT_TYPE_LOCAL}, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getLong(0);
        }
        Uri created = context.getContentResolver().insert(uri, values);
        if (created == null) throw new IllegalStateException("手机日历不支持自动写入");
        return ContentUris.parseId(created);
    }

    static long calendarEvent(Context context, long calendarId, String scope, JSONObject plan) {
        if (!calendar(context)) throw new SecurityException("日历权限未开启");
        String marker = "niannian://reminder/" + scope + "/" + plan.optString("id");
        long eventId = -1;
        try (Cursor cursor = context.getContentResolver().query(CalendarContract.Events.CONTENT_URI, new String[]{CalendarContract.Events._ID},
                CalendarContract.Events.CALENDAR_ID + "=? AND " + CalendarContract.Events.CUSTOM_APP_URI + "=?",
                new String[]{String.valueOf(calendarId), marker}, null)) {
            if (cursor != null && cursor.moveToFirst()) eventId = cursor.getLong(0);
        }
        long at = ReminderPolicy.timestamp(plan.optString("scheduled_at"));
        ContentValues values = new ContentValues();
        values.put(CalendarContract.Events.CALENDAR_ID, calendarId);
        values.put(CalendarContract.Events.TITLE, plan.optString("title"));
        values.put(CalendarContract.Events.DESCRIPTION, plan.optString("body"));
        values.put(CalendarContract.Events.DTSTART, at); values.put(CalendarContract.Events.DTEND, at + 5 * 60000L);
        values.put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().getID());
        values.put(CalendarContract.Events.HAS_ALARM, 1);
        values.put(CalendarContract.Events.CUSTOM_APP_PACKAGE, context.getPackageName());
        values.put(CalendarContract.Events.CUSTOM_APP_URI, marker);
        if (eventId <= 0) {
            Uri event = context.getContentResolver().insert(CalendarContract.Events.CONTENT_URI, values);
            if (event == null) throw new IllegalStateException("未能写入日历");
            eventId = ContentUris.parseId(event);
        } else context.getContentResolver().update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId), values, null, null);
        context.getContentResolver().delete(CalendarContract.Reminders.CONTENT_URI, CalendarContract.Reminders.EVENT_ID + "=?", new String[]{String.valueOf(eventId)});
        ContentValues reminder = new ContentValues();
        reminder.put(CalendarContract.Reminders.EVENT_ID, eventId); reminder.put(CalendarContract.Reminders.MINUTES, 0);
        reminder.put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT);
        try {
            if (context.getContentResolver().insert(CalendarContract.Reminders.CONTENT_URI, reminder) == null) throw new IllegalStateException("日历无法设置提醒");
        } catch (RuntimeException failure) {
            context.getContentResolver().delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId), null, null);
            throw failure;
        }
        return eventId;
    }
}
