package dev.niannian.reminder;

import android.app.Activity;
import android.app.NotificationManager;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.CalendarContract;
import android.view.View;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Account-scoped durable execution. No running service or Activity is retained. */
public final class ReminderCoordinator {
    private static final Object LOCK = new Object();
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    private static final int PERIODIC_JOB = 62101, IMMEDIATE_JOB = 62102;
    private static final String STORE = "reminder_execution_v1";
    interface Result { void done(boolean success, String message); }
    private ReminderCoordinator() { }

    private static SharedPreferences prefs(Context context) { return context.getSharedPreferences(STORE, Context.MODE_PRIVATE); }
    private static JSONObject object(String raw) { try { return new JSONObject(raw); } catch (Exception ignored) { return new JSONObject(); } }
    private static JSONArray array(String raw) { try { return new JSONArray(raw); } catch (Exception ignored) { return new JSONArray(); } }
    private static void put(JSONObject target, String name, Object value) { try { target.put(name, value); } catch (Exception ignored) { } }

    static String scope(Session session) {
        synchronized (session) { return session.token.isEmpty() ? "" : ReminderPolicy.scope(session.base, session.email); }
    }
    public static boolean acceptsIntent(Session session, String scope) { return scope != null && !scope.isEmpty() && scope.equals(scope(session)); }

    /** Call after successful login, or once when the Activity is created with a valid session. */
    public static void attach(Context source, Session session) {
        Context context = source.getApplicationContext();
        synchronized (LOCK) {
            String next = scope(session);
            if (next.isEmpty()) return;
            if (!next.equals(prefs(context).getString("scope", ""))) {
                clearLocked(context);
                prefs(context).edit().putString("scope", next).putString("settings", "{}").putBoolean("configured", false).commit();
            }
            ReminderPlatform.channels(context);
            cleanupCalendar(context);
            schedulePeriodic(context);
        }
        restore(context, null);
        submit(context, snapshot(context, session), true, null);
    }

    public static void onResume(Context source, Session session) {
        Context context = source.getApplicationContext();
        synchronized (LOCK) {
            if (!ReminderPlatform.capabilities(context).toString().equals(prefs(context).getString("capabilities", ""))) restore(context, null);
        }
        sync(context, session);
    }
    public static void sync(Context source, Session session) {
        Context context = source.getApplicationContext();
        submit(context, snapshot(context, session), false, null);
    }

    /** Call after a successful task write; cancellation happens before the next network request. */
    public static void taskChanged(Context source, Session session, String taskId) {
        Context context = source.getApplicationContext();
        synchronized (LOCK) {
            if (!scope(session).equals(prefs(context).getString("scope", ""))) return;
            advance(context);
            JSONArray plans = plans(context), kept = new JSONArray();
            String active = prefs(context).getString("scope", "");
            for (int i = 0; i < plans.length(); i++) {
                JSONObject plan = plans.optJSONObject(i); if (plan == null) continue;
                if (taskId == null || taskId.equals(plan.optString("task_id"))) {
                    ReminderPlatform.cancel(context, active, plan);
                    feedback(context, plan.optString("id"), "cancelled", "事项已更新");
                } else kept.put(plan);
            }
            JSONObject delivered = object(prefs(context).getString("delivered", "{}"));
            for (java.util.Iterator<String> keys = delivered.keys(); keys.hasNext();) {
                String id = keys.next(); JSONObject item = delivered.optJSONObject(id);
                if (item != null && (taskId == null || taskId.equals(item.optString("task_id"))))
                    context.getSystemService(NotificationManager.class).cancel(active + ":" + id, 1);
            }
            prefs(context).edit().putString("plans", kept.toString()).commit();
        }
        sync(context, session);
    }

    /** Safe for a network worker after a successful mutation, even if its Activity was destroyed. */
    public static void taskChanged(Context source, String base, String token, String taskId) {
        Context context = source.getApplicationContext();
        synchronized (LOCK) {
            Session current = new Session(context);
            if (!current.base.equals(base) || !current.token.equals(token) || token.isEmpty()) return;
            taskChanged(context, current, taskId);
        }
    }

    /** Call before mutating Session on logout/account/server change. */
    public static void clear(Context source) {
        Context context = source.getApplicationContext();
        Snapshot old;
        synchronized (LOCK) { old = snapshot(context, new Session(context)); clearLocked(context); }
        if (old != null) WORK.execute(() -> {
            try { JSONObject body = new JSONObject().put("device_id", old.device).put("detach", true);
                old.api.json("POST", "/api/v1/reminders/sync", body);
            } catch (Exception ignored) { /* Local cancellation is immediate even while offline. */ }
        });
    }

    /** Queue server token revocation only after the preceding device-detach attempt has finished. */
    public static void afterClear(Runnable finished) { WORK.execute(finished); }

    private static void clearLocked(Context context) {
        SharedPreferences preferences = prefs(context);
        advance(context);
        String active = preferences.getString("scope", "");
        JSONArray plans = plans(context);
        for (int i = 0; i < plans.length(); i++) {
            JSONObject plan = plans.optJSONObject(i); if (plan != null) ReminderPlatform.cancel(context, active, plan);
        }
        long calendar = preferences.getLong("calendar", -1);
        if (calendar > 0) calendarCleanup(context, calendar, true);
        context.getSystemService(NotificationManager.class).cancelAll();
        context.getSystemService(JobScheduler.class).cancel(PERIODIC_JOB);
        context.getSystemService(JobScheduler.class).cancel(IMMEDIATE_JOB);
        preferences.edit().remove("scope").remove("plans").remove("feedback").remove("delivered")
                .remove("settings").remove("configured").remove("calendar").remove("last_sync").remove("status").remove("capabilities").commit();
        cleanupCalendar(context);
    }

    private static void advance(Context context) {
        SharedPreferences preferences = prefs(context);
        preferences.edit().putLong("generation", preferences.getLong("generation", 0) + 1).commit();
    }

    static JSONObject settings(Context context) { synchronized (LOCK) { return object(prefs(context).getString("settings", "{}")); } }
    static boolean configured(Context context) { return prefs(context).getBoolean("configured", false); }
    static String status(Context context) { return prefs(context).getString("status", "开启后，念念会按授权渠道安排提醒"); }
    static int pending(Context context) { synchronized (LOCK) { return plans(context).length(); } }
    public static JSONObject capabilities(Context context, Session session) { return ReminderPlatform.capabilities(context); }
    public static View channelSettings(Activity activity, Session session, Runnable onChanged) { return ReminderSettings.view(activity, session, onChanged); }

    /** Empty means no setup guidance is needed; this never enables channels or requests permissions. */
    public static String setupPrompt(Context context, Session session) {
        synchronized (LOCK) {
            if (session == null || !acceptsIntent(session, prefs(context).getString("scope", ""))) return "";
            JSONObject settings = settings(context);
            return ReminderSetupPolicy.setupPrompt(configured(context), settings.optBoolean("enabled", false),
                    selectedChannels(settings), availableChannels(context));
        }
    }

    /** Reports enabled, authorized choices, not which channel the AI has scheduled for a task. */
    public static String availableChannelsSummary(Context context, Session session) {
        synchronized (LOCK) {
            if (session == null || !acceptsIntent(session, prefs(context).getString("scope", ""))) return "";
            JSONObject settings = settings(context);
            return ReminderSetupPolicy.availableChannelsSummary(configured(context), settings.optBoolean("enabled", false),
                    selectedChannels(settings), availableChannels(context));
        }
    }

    static String[] selectedChannels(JSONObject settings) {
        JSONArray channels = settings.optJSONArray("channels");
        String[] selected = new String[channels == null ? 0 : channels.length()];
        for (int i = 0; i < selected.length; i++) selected[i] = channels.optString(i);
        return selected;
    }

    private static String[] availableChannels(Context context) {
        JSONObject capabilities = ReminderPlatform.capabilities(context);
        List<String> available = new ArrayList<>();
        for (String channel : new String[]{"notification", "calendar", "alarm"}) {
            if (capabilities.optBoolean(channel, false)) available.add(channel);
        }
        return available.toArray(new String[0]);
    }

    static void saveSettings(Context source, Session session, JSONObject settings, Result callback) {
        Context context = source.getApplicationContext();
        Snapshot snapshot;
        synchronized (LOCK) {
            advance(context);
            // Settings may disable a channel or change quiet hours; stop old execution immediately.
            JSONArray plans = plans(context);
            String active = prefs(context).getString("scope", "");
            for (int i = 0; i < plans.length(); i++) { JSONObject plan = plans.optJSONObject(i); if (plan != null) ReminderPlatform.cancel(context, active, plan); }
            prefs(context).edit().putString("plans", "[]").commit();
            snapshot = snapshot(context, session);
        }
        if (snapshot == null) { callback.done(false, "请先登录再设置提醒渠道"); return; }
        WORK.execute(() -> {
            try {
                JSONObject response = snapshot.api.json("PUT", "/api/v1/reminders/settings", settings);
                synchronized (LOCK) {
                    if (!current(context, snapshot)) return;
                    JSONObject saved = response.optJSONObject("settings");
                    prefs(context).edit().putString("settings", (saved == null ? response : saved).toString()).putBoolean("configured", true).commit();
                }
                boolean success = syncNow(context, snapshot, true);
                if (isCurrent(context, snapshot)) callback.done(true, success ? "提醒设置已保存" : "设置已保存，计划同步待重试");
            } catch (Exception failure) {
                if (isCurrent(context, snapshot)) { setStatus(context, "保存失败，请重试；本机旧提醒已暂停"); callback.done(false, friendly(failure)); }
            }
        });
    }

    private static final class Snapshot {
        final String scope, device, token; final long generation; final Api api;
        Snapshot(String scope, String device, long generation, Api api) { this.scope = scope; this.device = device; this.generation = generation; this.api = api; token = api.token; }
    }

    private static Snapshot snapshot(Context context, Session session) {
        synchronized (LOCK) { synchronized (session) {
            String scope = scope(session);
            SharedPreferences preferences = prefs(context);
            if (scope.isEmpty() || !scope.equals(preferences.getString("scope", ""))) return null;
            String device = preferences.getString("device", "");
            if (device.isEmpty()) { device = UUID.randomUUID().toString(); preferences.edit().putString("device", device).commit(); }
            return new Snapshot(scope, device, preferences.getLong("generation", 0), new Api(session.base, session.token));
        } }
    }

    private static boolean current(Context context, Snapshot snapshot) {
        SharedPreferences preferences = prefs(context);
        if (!ReminderPolicy.fresh(snapshot.generation, snapshot.scope, preferences.getLong("generation", 0), preferences.getString("scope", ""))) return false;
        Session actual = new Session(context);
        return snapshot.scope.equals(scope(actual)) && snapshot.token.equals(actual.token);
    }
    private static boolean isCurrent(Context context, Snapshot snapshot) { synchronized (LOCK) { return current(context, snapshot); } }

    private static void submit(Context context, Snapshot snapshot, boolean claim, Runnable finished) {
        if (snapshot == null) { if (finished != null) finished.run(); return; }
        WORK.execute(() -> { try { syncNow(context, snapshot, claim); } finally { if (finished != null) finished.run(); } });
    }

    static void background(Context source, Runnable finished) {
        Context context = source.getApplicationContext();
        submit(context, snapshot(context, new Session(context)), false, finished);
    }

    private static boolean syncNow(Context context, Snapshot snapshot, boolean claim) {
        try {
            JSONArray sent;
            synchronized (LOCK) {
                if (!current(context, snapshot)) return false;
                ReminderPlatform.channels(context);
                cleanupCalendar(context);
                sent = array(prefs(context).getString("feedback", "[]"));
            }
            JSONObject request = new JSONObject().put("device_id", snapshot.device).put("capabilities", ReminderPlatform.capabilities(context))
                    .put("timezone", TimeZone.getDefault().getID()).put("claim", claim).put("results", sent);
            JSONObject response = snapshot.api.json("POST", "/api/v1/reminders/sync", request);
            synchronized (LOCK) {
                if (!current(context, snapshot)) return false;
                acknowledge(context, sent);
                JSONObject settings = response.optJSONObject("settings");
                if (settings != null) prefs(context).edit().putString("settings", settings.toString()).commit();
                boolean configured = response.optBoolean("configured", settings != null && settings.optBoolean("configured", false));
                prefs(context).edit().putBoolean("configured", configured).commit();
                String capabilities = ReminderPlatform.capabilities(context).toString();
                boolean changed = !capabilities.equals(prefs(context).getString("capabilities", ""));
                // Android removes exact alarms when access is revoked and all alarms on force-stop.
                // Reopening or changing permissions must rebuild real OS alarms, not trust local flags.
                reconcile(context, response.optJSONArray("plans"), claim || changed);
                prefs(context).edit().putString("capabilities", capabilities).commit();
                String status = !configured ? "尚未开启渠道安排，原邮件提醒保持不变"
                        : !response.optBoolean("active_device", true) ? "提醒由另一台手机接管，本机已停止"
                        : !settings(context).optBoolean("enabled", false) ? "渠道安排已暂停"
                        : response.optString("agent_status").equals("no_authorized_channel") ? "没有已授权的可用方式，请先开启对应权限"
                        : plans(context).length()==0 ? response.optString("agent_message","当前没有需要执行的手机提醒")
                        : "已同步 "+plans(context).length()+" 条手机安排，系统省电可能延迟执行";
                if(configured&&response.optBoolean("active_device",true)&&settings(context).optBoolean("enabled",false)){
                    JSONObject first=plans(context).optJSONObject(0);
                    if(first!=null&&!first.optString("reason").isEmpty())status+="\n"+("ai".equals(first.optString("source"))?"AI 的安排：":"按规则执行：")+first.optString("reason");
                    if(response.optBoolean("truncated"))status+="\n安排较多，目前只同步最近一批，后续联网继续补充。";
                }
                prefs(context).edit().putLong("last_sync", System.currentTimeMillis()).putString("status", status).commit();
            }
            return true;
        } catch (Exception failure) {
            synchronized (LOCK) {
                if (!current(context, snapshot)) return false;
                if (failure instanceof Api.Failure && ((Api.Failure) failure).status == 401) {
                    clearLocked(context);
                    setStatus(context, "登录已过期，本机提醒已停止");
                } else setStatus(context, failure instanceof Api.Failure && ((Api.Failure) failure).status == 404
                        ? "当前服务器尚未支持渠道安排，请更新后端" : "同步失败，已有未过期计划仍保留；请联网重试");
            }
            return false;
        }
    }

    private static void setStatus(Context context, String message) { prefs(context).edit().putString("status", message).commit(); }
    private static String friendly(Exception failure) { return failure instanceof Api.Failure ? failure.getMessage() : "无法保存，请检查网络后重试"; }
    private static JSONArray plans(Context context) { return array(prefs(context).getString("plans", "[]")); }

    private static boolean allowed(Context context, JSONObject plan, JSONObject settings) {
        if (!configured(context) || !settings.optBoolean("enabled", false)) return false;
        String channel = plan.optString("channel");
        if (!channel.equals("notification") && !channel.equals("calendar") && !channel.equals("alarm")) return false;
        JSONArray enabled = settings.optJSONArray("channels");
        boolean found = false;
        if (enabled != null) for (int i = 0; i < enabled.length(); i++) if (channel.equals(enabled.optString(i))) found = true;
        return found && ReminderPlatform.capabilities(context).optBoolean(channel, false);
    }

    private static void reconcile(Context context, JSONArray received, boolean restore) throws Exception {
        if (received == null) received = new JSONArray();
        String active = prefs(context).getString("scope", "");
        JSONObject settings = settings(context), delivered = object(prefs(context).getString("delivered", "{}"));
        JSONArray oldPlans = plans(context), next = new JSONArray();
        Map<String, JSONObject> old = new HashMap<>();
        for (int i = 0; i < oldPlans.length(); i++) { JSONObject plan = oldPlans.optJSONObject(i); if (plan != null) old.put(plan.optString("id"), plan); }
        List<JSONObject> sorted = new ArrayList<>();
        for (int i = 0; i < received.length() && i < 512; i++) { JSONObject plan = received.optJSONObject(i); if (plan != null) sorted.add(plan); }
        sorted.sort(Comparator.comparingLong(plan -> ReminderPolicy.timestamp(plan.optString("scheduled_at"))));
        long now = System.currentTimeMillis();
        Set<String> seen = new HashSet<>();
        Map<String, Long> previous = new HashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        for (JSONObject plan : sorted) {
            String id = plan.optString("id"), task = plan.optString("task_id"), channel = plan.optString("channel");
            long at = ReminderPolicy.timestamp(plan.optString("scheduled_at")), until = ReminderPolicy.timestamp(plan.optString("expires_at"));
            if (id.isEmpty() || task.isEmpty() || !seen.add(id) || delivered.has(id)) continue;
            if (!allowed(context, plan, settings) || at <= 0 || until <= now || until <= at || at > now + 2 * 86400000L
                    || ReminderPolicy.inQuiet(at, settings.optString("timezone", TimeZone.getDefault().getID()), settings.optString("quiet_start", "22:00"), settings.optString("quiet_end", "08:00"))) {
                feedback(context, id, "failed", "计划过期、静默时段或渠道未授权"); continue;
            }
            String dayKey = task + ":" + Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate();
            long last = previous.containsKey(task) ? previous.get(task) : 0;
            if (next.length() >= ReminderPolicy.MAX_PLANS || counts.getOrDefault(dayKey, 0) >= 32 || (last > 0 && at - last < Math.max(5, settings.optInt("min_interval_minutes", 15)) * 60000L)) {
                feedback(context, id, "failed", "已达到提醒频率或数量上限"); continue;
            }
            JSONObject existing = old.remove(id);
            if (existing != null && existing.optString("scheduled_at").equals(plan.optString("scheduled_at")) && existing.optString("channel").equals(channel)) {
                if (existing.has("local_event_id")) plan.put("local_event_id", existing.optLong("local_event_id"));
                plan.put("local_scheduled", existing.optBoolean("local_scheduled", false));
            } else if (existing != null) ReminderPlatform.cancel(context, active, existing);
            previous.put(task, at); counts.put(dayKey, counts.getOrDefault(dayKey, 0) + 1);
            next.put(plan);
        }
        for (JSONObject plan : old.values()) ReminderPlatform.cancel(context, active, plan, !delivered.has(plan.optString("id")));
        // Persist before creating OS objects, so a killed process can still cancel/recover them.
        if (!prefs(context).edit().putString("plans", next.toString()).commit()) throw new java.io.IOException("无法保存本机提醒计划");
        for (int i = 0; i < next.length(); i++) {
            JSONObject plan = next.getJSONObject(i);
            if (!restore && plan.optBoolean("local_scheduled", false)) continue;
            try {
                if ("calendar".equals(plan.optString("channel"))) {
                    long calendar = prefs(context).getLong("calendar", -1);
                    if (calendar <= 0) { calendar = ReminderPlatform.createCalendar(context, active); prefs(context).edit().putLong("calendar", calendar).commit(); }
                    long event = ReminderPlatform.calendarEvent(context, calendar, active, plan);
                    plan.put("local_event_id", event);
                } else ReminderPlatform.schedule(context, active, plan);
                plan.put("local_scheduled", true);
                feedback(context, plan.optString("id"), "scheduled", "");
            } catch (Exception failure) {
                plan.put("local_scheduled", false);
                feedback(context, plan.optString("id"), "failed", "系统未接受安排，请检查渠道权限");
            }
            prefs(context).edit().putString("plans", next.toString()).commit();
        }
    }

    static void fire(Context source, String expectedScope, String id) {
        Context context = source.getApplicationContext();
        synchronized (LOCK) {
            if (!expectedScope.equals(prefs(context).getString("scope", "")) || !expectedScope.equals(scope(new Session(context)))) return;
            JSONObject delivered = object(prefs(context).getString("delivered", "{}"));
            if (delivered.has(id)) return;
            JSONArray plans = plans(context); JSONObject plan = null;
            for (int i = 0; i < plans.length(); i++) if (id.equals(plans.optJSONObject(i).optString("id"))) plan = plans.optJSONObject(i);
            if (plan == null || "calendar".equals(plan.optString("channel"))) return;
            JSONObject settings = settings(context);
            long now = System.currentTimeMillis(), last = 0; int taskCount = 0, totalCount = 0;
            String today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().toString();
            for (java.util.Iterator<String> keys = delivered.keys(); keys.hasNext();) {
                JSONObject item = delivered.optJSONObject(keys.next()); if (item == null) continue;
                if (plan.optString("task_id").equals(item.optString("task_id"))) last = Math.max(last, item.optLong("at"));
                if (today.equals(item.optString("day"))) { totalCount++; if (plan.optString("task_id").equals(item.optString("task_id"))) taskCount++; }
            }
            JSONObject retained = new JSONObject();
            for (java.util.Iterator<String> keys = delivered.keys(); keys.hasNext();) {
                String key = keys.next(); JSONObject item = delivered.optJSONObject(key);
                if (item != null && item.optLong("at") >= now - 7 * 86400000L) put(retained, key, item);
            }
            delivered = retained;
            if (!allowed(context, plan, settings)
                    || ReminderPolicy.inQuiet(now, settings.optString("timezone", TimeZone.getDefault().getID()), settings.optString("quiet_start", "22:00"), settings.optString("quiet_end", "08:00"))
                    || !ReminderPolicy.due(now, ReminderPolicy.timestamp(plan.optString("scheduled_at")), ReminderPolicy.timestamp(plan.optString("expires_at")), last, settings.optInt("min_interval_minutes", 15), taskCount, totalCount)) {
                feedback(context, id, "failed", "计划已过期、进入静默时段或达到提醒上限");
                plan.remove("local_scheduled");
            } else {
                // Mark consumed first: process death must never cause repeated sound.
                JSONObject receipt = new JSONObject(); put(receipt, "task_id", plan.optString("task_id")); put(receipt, "day", today); put(receipt, "at", now);
                put(delivered, id, receipt);
                if (!prefs(context).edit().putString("delivered", delivered.toString()).commit()) {
                    feedback(context, id, "failed", "无法保存执行记录，本次未弹出");
                    enqueueSync(context); return;
                }
                try { ReminderPlatform.notify(context, expectedScope, plan); feedback(context, id, "delivered", ""); }
                catch (RuntimeException failure) { feedback(context, id, "failed", "系统通知未能展示"); }
            }
            enqueueSync(context);
        }
    }

    static void restore(Context source, Runnable finished) {
        Context context = source.getApplicationContext();
        new Thread(() -> {
            try { synchronized (LOCK) {
                String active = prefs(context).getString("scope", "");
                if (active.isEmpty()) return;
                if (!active.equals(scope(new Session(context)))) { clearLocked(context); return; }
                ReminderPlatform.channels(context);
                try {
                    reconcile(context, plans(context), true);
                    prefs(context).edit().putString("capabilities", ReminderPlatform.capabilities(context).toString()).commit();
                } catch (Exception ignored) { setStatus(context, "恢复提醒失败，请打开应用重试"); }
                schedulePeriodic(context); enqueueSync(context);
            } } finally { if (finished != null) finished.run(); }
        }, "niannian-reminder-restore").start();
    }

    private static void feedback(Context context, String id, String status, String error) {
        if (id.isEmpty()) return;
        JSONArray previous = array(prefs(context).getString("feedback", "[]")), next = new JSONArray();
        for (int i = Math.max(0, previous.length() - 255); i < previous.length(); i++) {
            JSONObject item = previous.optJSONObject(i); if (item != null && !id.equals(item.optString("plan_id"))) next.put(item);
        }
        JSONObject item = new JSONObject(); put(item, "plan_id", id); put(item, "status", status); put(item, "error", error);
        next.put(item); prefs(context).edit().putString("feedback", next.toString()).commit();
    }

    private static void acknowledge(Context context, JSONArray sent) {
        Set<String> acknowledged = new HashSet<>();
        for (int i = 0; i < sent.length(); i++) acknowledged.add(sent.optJSONObject(i).toString());
        JSONArray previous = array(prefs(context).getString("feedback", "[]")), next = new JSONArray();
        for (int i = 0; i < previous.length(); i++) if (!acknowledged.contains(previous.optJSONObject(i).toString())) next.put(previous.optJSONObject(i));
        prefs(context).edit().putString("feedback", next.toString()).commit();
    }

    static void calendarCleanup(Context context, long id, boolean calendar) {
        JSONArray pending = array(prefs(context).getString("calendar_cleanup", "[]"));
        JSONObject item = new JSONObject(); put(item, "id", id); put(item, "calendar", calendar); put(item, "scope", prefs(context).getString("scope", ""));
        pending.put(item); prefs(context).edit().putString("calendar_cleanup", pending.toString()).commit();
    }

    private static void cleanupCalendar(Context context) {
        if (!ReminderPlatform.calendar(context)) return;
        JSONArray pending = array(prefs(context).getString("calendar_cleanup", "[]")), remaining = new JSONArray();
        for (int i = 0; i < pending.length(); i++) {
            JSONObject item = pending.optJSONObject(i); if (item == null) continue;
            try {
                boolean calendar = item.optBoolean("calendar"); long id = item.optLong("id"); String scope = item.optString("scope");
                if (ReminderPlatform.ownsCalendarItem(context, id, calendar, scope))
                    context.getContentResolver().delete(ContentUris.withAppendedId(calendar ? CalendarContract.Calendars.CONTENT_URI : CalendarContract.Events.CONTENT_URI, id), null, null);
            }
            catch (RuntimeException failure) { remaining.put(item); }
        }
        prefs(context).edit().putString("calendar_cleanup", remaining.toString()).commit();
    }

    static boolean hasCalendarCleanup(Context context) { return array(prefs(context).getString("calendar_cleanup", "[]")).length() > 0; }
    private static void schedulePeriodic(Context context) {
        JobInfo job = new JobInfo.Builder(PERIODIC_JOB, new ComponentName(context, ReminderSyncJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).setPeriodic(15 * 60000L).build();
        context.getSystemService(JobScheduler.class).schedule(job);
    }
    static void enqueueSync(Context context) {
        if (prefs(context).getString("scope", "").isEmpty()) return;
        JobInfo job = new JobInfo.Builder(IMMEDIATE_JOB, new ComponentName(context, ReminderSyncJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(1000).build();
        context.getSystemService(JobScheduler.class).schedule(job);
    }
}
