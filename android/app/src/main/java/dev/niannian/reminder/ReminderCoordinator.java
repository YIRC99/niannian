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
import java.util.concurrent.TimeUnit;

/** Account-scoped durable execution. No running service or Activity is retained. */
public final class ReminderCoordinator {
    private static final Object LOCK = new Object();
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    private static final int PERIODIC_JOB = 62101, IMMEDIATE_JOB = 62102;
    private static final String STORE = "reminder_execution_v1";
    interface Result { void done(boolean success, String message); }
    interface SyncFinished { void done(boolean retry); }
    private ReminderCoordinator() { }

    private static SharedPreferences prefs(Context context) { return context.getSharedPreferences(STORE, Context.MODE_PRIVATE); }
    private static JSONObject object(String raw) { try { return new JSONObject(raw); } catch (Exception ignored) { return new JSONObject(); } }
    private static JSONArray array(String raw) { try { return new JSONArray(raw); } catch (Exception ignored) { return new JSONArray(); } }
    private static void put(JSONObject target, String name, Object value) { try { target.put(name, value); } catch (Exception ignored) { } }

    /** A short ringing grant outlives delivery reconciliation, never task cancellation. */
    static boolean isCurrentAlarm(Context context, String expectedScope, String id) {
        synchronized (LOCK) {
            JSONObject ringing=object(prefs(context).getString("ringing", "{}"));
            return expectedScope.equals(prefs(context).getString("scope", ""))
                    && expectedScope.equals(scope(new Session(context))) && id.equals(ringing.optString("id"))
                    && expectedScope.equals(ringing.optString("scope")) && ringing.optLong("until")>System.currentTimeMillis()
                    && allowed(context, object("{\"channel\":\"alarm\"}"), settings(context));
        }
    }
    static void alarmStopped(Context context, String expectedScope, String id) {
        synchronized (LOCK) {
            JSONObject ringing=object(prefs(context).getString("ringing", "{}"));
            if(expectedScope.equals(ringing.optString("scope"))&&id.equals(ringing.optString("id")))prefs(context).edit().remove("ringing").commit();
        }
    }
    static void alarmDismissed(Context context,String expectedScope,String id){
        synchronized(LOCK){
            JSONObject ringing=object(prefs(context).getString("ringing","{}"));
            if(expectedScope.equals(prefs(context).getString("scope",""))&&expectedScope.equals(ringing.optString("scope"))
                    &&id.equals(ringing.optString("id"))&&!ringing.optString("flow_id").isEmpty()){
                JSONObject receipt=flowReceipts(context).optJSONObject(id);
                if(receipt!=null&&"executing".equals(receipt.optString("status"))){
                    flowFeedback(context,ringing.optString("flow_id"),id,ringing.optString("task_id"),"cancelled","用户停止本次响铃，事项仍待完成",System.currentTimeMillis());
                    finishConsumedFlows(context);enqueueSync(context);
                }
            }
            alarmStopped(context,expectedScope,id);
        }
    }
    static void alarmPlaybackStarted(Context context,String expectedScope,String id){
        synchronized(LOCK){
            if(!isCurrentAlarm(context,expectedScope,id))return;
            if(id.equals(object(prefs(context).getString("alarm_test","{}")).optString("id")))return;
            JSONObject ringing=object(prefs(context).getString("ringing","{}"));
            if(!ringing.optString("flow_id").isEmpty()){
                flowFeedback(context,ringing.optString("flow_id"),id,ringing.optString("task_id"),"delivered","",System.currentTimeMillis());
                finishConsumedFlows(context);enqueueSync(context);return;
            }
            feedback(context,id,"delivered","");enqueueSync(context);
        }
    }
    static void alarmPlaybackFailed(Context context,String expectedScope,String id){
        synchronized(LOCK){
            if(!isCurrentAlarm(context,expectedScope,id))return;
            if(!id.equals(object(prefs(context).getString("alarm_test","{}")).optString("id"))){
                JSONObject ringing=object(prefs(context).getString("ringing","{}"));
                if(!ringing.optString("flow_id").isEmpty()){
                    flowFeedback(context,ringing.optString("flow_id"),id,ringing.optString("task_id"),"failed","闹钟未能开始播放，请检查闹钟声音设置",System.currentTimeMillis());finishConsumedFlows(context);
                }else feedback(context,id,"failed","闹钟未能开始播放，请检查闹钟声音设置");
                enqueueSync(context);
            }
            alarmStopped(context,expectedScope,id);
        }
    }
    private static void grantAlarm(Context context,String active,JSONObject plan){
        JSONObject ringing=new JSONObject();put(ringing,"scope",active);put(ringing,"id",plan.optString("id"));put(ringing,"task_id",plan.optString("task_id"));put(ringing,"until",System.currentTimeMillis()+65000);
        put(ringing,"flow_id",plan.optString("_flow_id"));
        if(!prefs(context).edit().putString("ringing",ringing.toString()).commit())throw new IllegalStateException("无法保存响铃状态");
    }
    private static void stopRinging(Context context,String active,String taskId){
        JSONObject ringing=object(prefs(context).getString("ringing", "{}"));
        if(active.equals(ringing.optString("scope"))&&(taskId==null||taskId.equals(ringing.optString("task_id")))){
            prefs(context).edit().remove("ringing").commit();ReminderAlarmService.stopForPlan(context,active,ringing.optString("id"));
        }
    }
    static void testAlarm(Context context,Session session)throws Exception{
        testReminder(context,session,"alarm");
    }
    static void testNotification(Context context,Session session)throws Exception{
        testReminder(context,session,"notification");
    }
    private static void testReminder(Context context,Session session,String channel)throws Exception{
        synchronized(LOCK){
            String active=scope(session);
            if(active.isEmpty()||!active.equals(prefs(context).getString("scope","")))throw new IllegalStateException("请先登录");
            boolean alarm="alarm".equals(channel);
            JSONObject plan=new JSONObject().put("channel",channel);
            if(!allowed(context,plan,settings(context)))throw new IllegalStateException(alarm
                    ?"请先保存闹钟开关，并允许精确闹钟和通知权限":"请先保存手机通知开关，并允许通知权限");
            cancelTestAlarm(context);
            long at=System.currentTimeMillis()+10000;
            plan.put("id","test-"+UUID.randomUUID()).put("task_id",alarm?"alarm-test":"").put("title",alarm?"念念闹钟测试":"念念通知测试").put("_diagnostic",true)
                    .put("body",alarm?"这是一次手机响铃测试，不会创建任务或完成记录。":"这是一条手机通知测试，不会创建任务或完成记录。")
                    .put("scheduled_at",Instant.ofEpochMilli(at).toString()).put("expires_at",Instant.ofEpochMilli(at+120000).toString());
            if(!prefs(context).edit().putString("alarm_test",plan.toString()).commit())throw new IllegalStateException("测试未保存，请重试");
            try{ReminderPlatform.schedule(context,active,plan);}catch(Exception failure){prefs(context).edit().remove("alarm_test").commit();throw failure;}
        }
    }
    static void cancelTestAlarm(Context context){
        synchronized(LOCK){
            JSONObject plan=object(prefs(context).getString("alarm_test","{}"));String active=prefs(context).getString("scope","");
            if(!plan.optString("id").isEmpty())ReminderPlatform.cancel(context,active,plan);
            stopRinging(context,active,"alarm-test");prefs(context).edit().remove("alarm_test").commit();
        }
    }
    private static void restoreTestReminder(Context context,String active){
        JSONObject test=object(prefs(context).getString("alarm_test","{}"));
        if(test.optString("id").isEmpty()||test.optBoolean("fired"))return;
        if(ReminderPolicy.timestamp(test.optString("expires_at"))<=System.currentTimeMillis()||!allowed(context,test,settings(context))){cancelTestAlarm(context);return;}
        try{ReminderPlatform.schedule(context,active,test);}
        catch(Exception failure){setStatus(context,"测试提醒未能恢复，请重新测试");}
    }

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

    /** Call from the task-write worker after taskChanged; never repeats the task mutation. */
    public static boolean awaitTaskSync(Context source, String base, String token) {
        Context context=source.getApplicationContext();
        Session session=new Session(context);
        if(!session.base.equals(base)||!session.token.equals(token))return false;
        Snapshot expected=snapshot(context,session);
        if(expected==null)return false;
        if(!configured(context))return true;
        boolean phone=false;
        for(String channel:selectedChannels(settings(context)))if(!"email".equals(channel))phone=true;
        if(!phone||!settings(context).optBoolean("enabled",false))return true;
        try{return WORK.submit(()->{synchronized(LOCK){return current(context,expected)
                &&prefs(context).getLong("last_sync_generation",-1)==expected.generation
                &&prefs(context).getBoolean("last_schedule_ready",false);}}).get(15,TimeUnit.SECONDS);}
        catch(Exception pending){return false;}
    }

    /** Call after a successful task write; cancellation happens before the next network request. */
    public static void taskChanged(Context source, Session session, String taskId) {
        Context context = source.getApplicationContext();
        synchronized (LOCK) {
            if (!scope(session).equals(prefs(context).getString("scope", ""))) return;
            advance(context);
            stopRinging(context,scope(session),taskId);
            cancelFlows(context,taskId,true);
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
            try { JSONArray flowSent,closedSent;
                synchronized(LOCK){flowSent=firstItems(flowOutbox(context,old.scope),256);closedSent=firstItems(closedFlows(context,old.scope),256);}
                JSONObject body = new JSONObject().put("device_id", old.device).put("detach", true)
                        .put("flow_results",flowSent).put("closed_flow_ids",closedSent);
                JSONObject response=sendReminderSync(old.api,body);
                synchronized(LOCK){acknowledgeFlows(context,old.scope,flowSent,response);}
            } catch (Exception ignored) { /* Local cancellation is immediate even while offline. */ }
        });
    }

    /** Queue server token revocation only after the preceding device-detach attempt has finished. */
    public static void afterClear(Runnable finished) { WORK.execute(finished); }

    private static void clearLocked(Context context) {
        SharedPreferences preferences = prefs(context);
        advance(context);
        String active = preferences.getString("scope", "");
        cancelTestAlarm(context);stopRinging(context,active,null);
        cancelFlows(context,null,true);
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
        preferences.edit().remove("local_flows").remove("last_sync_generation").remove("last_schedule_ready").commit();
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
        JSONObject requested = object(settings.toString());
        // Older servers still accept these fields. Explicitly disable their retired quiet window.
        put(requested, "quiet_start", ""); put(requested, "quiet_end", "");
        Snapshot snapshot;
        synchronized (LOCK) {
            advance(context);
            // Settings may disable a channel; stop old execution immediately.
            JSONArray plans = plans(context);
            String active = prefs(context).getString("scope", "");
            stopRinging(context,active,null);cancelTestAlarm(context);cancelFlows(context,null,true);
            for (int i = 0; i < plans.length(); i++) { JSONObject plan = plans.optJSONObject(i); if (plan != null) ReminderPlatform.cancel(context, active, plan); }
            prefs(context).edit().putString("plans", "[]").commit();
            snapshot = snapshot(context, session);
        }
        if (snapshot == null) { callback.done(false, "请先登录再设置提醒渠道"); return; }
        WORK.execute(() -> {
            try {
                JSONObject response = snapshot.api.json("PUT", "/api/v1/reminders/settings", requested);
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

    static void background(Context source, SyncFinished finished) {
        Context context = source.getApplicationContext();
        Snapshot snapshot = snapshot(context, new Session(context));
        if (snapshot == null) { finished.done(false); return; }
        WORK.execute(() -> {
            boolean success = syncNow(context, snapshot, false);
            finished.done(!success && isCurrent(context, snapshot));
        });
    }

    private static boolean syncNow(Context context, Snapshot snapshot, boolean claim) {
        try {
            JSONArray sent,flowSent,closedSent;
            synchronized (LOCK) {
                if (!current(context, snapshot)) return false;
                ReminderPlatform.channels(context);
                cleanupCalendar(context);
                sent = array(prefs(context).getString("feedback", "[]"));
                flowSent=firstItems(flowOutbox(context,snapshot.scope),256);closedSent=firstItems(closedFlows(context,snapshot.scope),256);
            }
            JSONObject request = new JSONObject().put("device_id", snapshot.device).put("capabilities", ReminderPlatform.capabilities(context).put("local_flow_v1",true))
                    .put("timezone", TimeZone.getDefault().getID()).put("claim", claim).put("results", sent)
                    .put("flow_results",flowSent).put("closed_flow_ids",closedSent);
            JSONObject response = sendReminderSync(snapshot.api,request);
            JSONObject migration = null;
            synchronized (LOCK) {
                if (!current(context, snapshot)) return false;
                if (needsLegacySettingsMigration(response) && !hasInFlightLegacyPlan(response, System.currentTimeMillis())) {
                    JSONObject saved = response.getJSONObject("settings");
                    migration = new JSONObject().put("enabled", saved.optBoolean("enabled", false))
                            .put("channels", new JSONArray(saved.getJSONArray("channels").toString()))
                            .put("min_interval_minutes", saved.getInt("min_interval_minutes"))
                            .put("timezone", saved.optString("timezone", TimeZone.getDefault().getID()))
                            .put("quiet_start", "").put("quiet_end", "");
                    // This first response already accepted legacy receipts and explicitly acknowledged flow results.
                    // Do not resend them after a settings write changes the server's plan generation.
                    acknowledge(context, sent);
                    acknowledgeFlows(context, snapshot.scope, flowSent, response);
                    sent = new JSONArray(); flowSent = new JSONArray(); closedSent = new JSONArray();
                    request.put("results", sent).put("flow_results", flowSent).put("closed_flow_ids", closedSent);
                }
            }
            if (migration != null) {
                if (!isCurrent(context, snapshot)) return false;
                snapshot.api.json("PUT", "/api/v1/reminders/settings", migration);
                if (!isCurrent(context, snapshot)) return false;
                response = sendReminderSync(snapshot.api, request);
                // One bounded migration per sync. A nonconforming old server is retried on a later sync.
                if (needsLegacySettingsMigration(response)) throw new IllegalStateException("旧提醒设置尚未更新");
            }
            synchronized (LOCK) {
                if (!current(context, snapshot)) return false;
                acknowledge(context, sent);
                acknowledgeFlows(context,snapshot.scope,flowSent,response);
                JSONObject settings = response.optJSONObject("settings");
                if (settings != null) prefs(context).edit().putString("settings", settings.toString()).commit();
                boolean configured = response.optBoolean("configured", settings != null && settings.optBoolean("configured", false));
                prefs(context).edit().putBoolean("configured", configured).commit();
                String capabilities = ReminderPlatform.capabilities(context).toString();
                boolean changed = !capabilities.equals(prefs(context).getString("capabilities", ""));
                // Android removes exact alarms when access is revoked and all alarms on force-stop.
                // Reopening or changing permissions must rebuild real OS alarms, not trust local flags.
                boolean flowVersion=response.optInt("local_flow_version",0)==1;
                JSONArray incomingFlows=flowVersion?response.optJSONArray("local_flows"):new JSONArray();
                boolean ready=reconcile(context, withoutFlowPlans(response.optJSONArray("plans"),incomingFlows), claim || changed);
                if(flowVersion)ready=reconcileFlows(context,incomingFlows,response.optJSONArray("revoked_flow_ids"),claim||changed)&&ready;
                else cancelFlows(context,null,true);
                if(configured&&settings(context).optBoolean("enabled",false)
                        &&(!response.optBoolean("active_device",true)||"no_authorized_channel".equals(response.optString("agent_status"))))ready=false;
                prefs(context).edit().putString("capabilities", capabilities).commit();
                String status = !configured ? "尚未开启渠道安排，原邮件提醒保持不变"
                        : !response.optBoolean("active_device", true) ? "提醒由另一台手机接管，本机已停止"
                        : !settings(context).optBoolean("enabled", false) ? "渠道安排已暂停"
                        : response.optString("agent_status").equals("no_authorized_channel") ? "没有已授权的可用方式，请先开启对应权限"
                        : plans(context).length()==0&&localFlows(context).length()==0 ? response.optString("agent_message","当前没有需要执行的手机提醒")
                        : localFlows(context).length()>0 ? "手机提醒已交给系统；已保存的通知和闹钟流程可离线执行"
                        : flowVersion ? "当前手机提醒已交给系统，后续步骤按流程联网补充"
                        : "当前提醒已交给系统；升级后端后，可提前保存后续通知和闹钟步骤";
                if(!ready)status="事项已保存，部分手机提醒尚未安排成功，请检查权限后联网重试";
                if(configured&&response.optBoolean("active_device",true)&&settings(context).optBoolean("enabled",false)){
                    JSONObject first=plans(context).optJSONObject(0);
                    if(first!=null&&!first.optString("reason").isEmpty())status+="\n"+("ai".equals(first.optString("source"))?"AI 的安排：":"按规则执行：")+first.optString("reason");
                    if(response.optBoolean("truncated"))status+="\n安排较多，目前只同步最近一批，后续联网继续补充。";
                }
                prefs(context).edit().putLong("last_sync", System.currentTimeMillis()).putString("status", status)
                        .putLong("last_sync_generation",snapshot.generation).putBoolean("last_schedule_ready",ready).commit();
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

    private static boolean needsLegacySettingsMigration(JSONObject response) {
        JSONObject settings = response.optJSONObject("settings");
        return settings != null && ReminderPolicy.needsLegacyQuietMigration(
                response.optBoolean("configured", settings.optBoolean("configured", false)),
                response.optBoolean("active_device", false), settings.optString("quiet_start"), settings.optString("quiet_end"));
    }

    private static boolean hasInFlightLegacyPlan(JSONObject response, long now) {
        JSONArray plans = withoutFlowPlans(response.optJSONArray("plans"), response.optInt("local_flow_version") == 1 ? response.optJSONArray("local_flows") : null);
        for (int i = 0; i < plans.length(); i++) {
            JSONObject plan = plans.optJSONObject(i);
            if (plan != null && ReminderPolicy.blocksSettingsMigration(now, ReminderPolicy.timestamp(plan.optString("scheduled_at")),
                    ReminderPolicy.timestamp(plan.optString("expires_at")), plan.optString("status"))) return true;
        }
        return false;
    }

    private static void setStatus(Context context, String message) { prefs(context).edit().putString("status", message).commit(); }
    private static JSONObject sendReminderSync(Api api,JSONObject request)throws Exception{
        try{return api.json("POST","/api/v1/reminders/sync",request);}
        catch(Api.Failure failure){
            if(failure.status!=400||!"请求内容格式不正确".equals(failure.getMessage()))throw failure;
            // 2.2 and older reject unknown JSON fields before handling the request.
            JSONObject legacy=new JSONObject(request.toString());legacy.remove("flow_results");legacy.remove("closed_flow_ids");
            JSONObject capabilities=legacy.optJSONObject("capabilities");if(capabilities!=null)capabilities.remove("local_flow_v1");
            return api.json("POST","/api/v1/reminders/sync",legacy);
        }
    }
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

    private static boolean reconcile(Context context, JSONArray received, boolean restore) throws Exception {
        boolean ready=true;
        if (received == null) received = new JSONArray();
        String active = prefs(context).getString("scope", "");
        JSONObject settings = settings(context), delivered = object(prefs(context).getString("delivered", "{}"));
        JSONArray oldPlans = plans(context), next = new JSONArray();
        Map<String, JSONObject> old = new HashMap<>();
        for (int i = 0; i < oldPlans.length(); i++) { JSONObject plan = oldPlans.optJSONObject(i); if (plan != null) old.put(plan.optString("id"), plan); }
        List<JSONObject> sorted = new ArrayList<>();
        for (int i = 0; i < received.length() && i < 512; i++) { JSONObject plan = received.optJSONObject(i); if (plan != null) sorted.add(plan); }
        sorted.sort(Comparator.comparingLong((JSONObject plan) -> ReminderPolicy.timestamp(plan.optString("scheduled_at")))
                .thenComparing(plan -> plan.optString("id")));
        long now = System.currentTimeMillis();
        Set<String> seen = new HashSet<>();
        Set<String> pendingTasks = new HashSet<>();
        Map<String, JSONObject> calendarPlans = new HashMap<>();
        for (JSONObject plan : sorted) {
            // Local execution fields only come from our stored copy, never from the server.
            plan = new JSONObject(plan.toString());
            plan.remove("local_event_id"); plan.remove("local_scheduled");
            String id = plan.optString("id"), task = plan.optString("task_id"), channel = plan.optString("channel");
            long at = ReminderPolicy.timestamp(plan.optString("scheduled_at")), until = ReminderPolicy.timestamp(plan.optString("expires_at"));
            if (id.isEmpty() || task.isEmpty() || !seen.add(id) || delivered.has(id)) continue;
            if (!allowed(context, plan, settings) || at <= 0 || until <= now || until <= at || at > now + 2 * 86400000L) {
                JSONObject prior = old.get(id);
                // Calendar only confirms insertion, not actual presentation. Expiry must not rewrite that history.
                boolean expiredCalendar = "calendar".equals(channel) && until <= now && prior != null && prior.optBoolean("local_scheduled", false);
                if (!expiredCalendar) {feedback(context, id, "failed", "计划过期或渠道未授权");ready=false;}
                continue;
            }
            if (pendingTasks.contains(task)) {
                feedback(context, id, "cancelled", "同一事项只保留最近一条待执行提醒"); continue;
            }
            if (next.length() >= ReminderPolicy.MAX_PLANS) {
                feedback(context, id, "failed", "已达到提醒频率或数量上限"); continue;
            }
            JSONObject existing = old.remove(id);
            if (existing != null && existing.optString("scheduled_at").equals(plan.optString("scheduled_at")) && existing.optString("channel").equals(channel)) {
                if (existing.has("local_event_id")) plan.put("local_event_id", existing.optLong("local_event_id"));
                plan.put("local_scheduled", existing.optBoolean("local_scheduled", false));
            } else if (existing != null) {
                boolean keepCalendar = "calendar".equals(channel) && "calendar".equals(existing.optString("channel"))
                        && calendarMarker(active, plan).equals(calendarMarker(active, existing));
                if (keepCalendar && existing.has("local_event_id")) plan.put("local_event_id", existing.optLong("local_event_id"));
                ReminderPlatform.cancel(context, active, existing, true, keepCalendar);
            }
            pendingTasks.add(task);
            if ("calendar".equals(channel)) calendarPlans.put(calendarMarker(active, plan), plan);
            next.put(plan);
        }
        for (JSONObject plan : old.values()) {
            boolean keepCalendar = false;
            if ("calendar".equals(plan.optString("channel"))) {
                JSONObject replacement = calendarPlans.get(calendarMarker(active, plan));
                long eventId = plan.optLong("local_event_id", -1);
                if (replacement != null && eventId > 0) {
                    if (!replacement.has("local_event_id")) replacement.put("local_event_id", eventId);
                    keepCalendar = replacement.optLong("local_event_id", -1) == eventId;
                }
            }
            ReminderPlatform.cancel(context, active, plan, !delivered.has(plan.optString("id")), keepCalendar);
        }
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
                ready=false;
                plan.put("local_scheduled", false);
                feedback(context, plan.optString("id"), "failed", "系统未接受安排，请检查渠道权限");
            }
            prefs(context).edit().putString("plans", next.toString()).commit();
        }
        return ready;
    }

    private static JSONArray localFlows(Context context){return array(prefs(context).getString("local_flows","[]"));}
    private static JSONArray firstItems(JSONArray source,int limit){JSONArray batch=new JSONArray();for(int i=0;i<source.length()&&i<limit;i++)batch.put(source.opt(i));return batch;}
    private static String flowKey(String type,String scope){return type+"_"+scope;}
    private static JSONArray flowOutbox(Context context,String scope){return array(prefs(context).getString(flowKey("flow_outbox",scope),"[]"));}
    private static JSONArray closedFlows(Context context,String scope){return array(prefs(context).getString(flowKey("flow_closed",scope),"[]"));}
    private static JSONObject flowReceipts(Context context){return object(prefs(context).getString(flowKey("flow_receipts",prefs(context).getString("scope","")),"{}"));}

    private static JSONArray withoutFlowPlans(JSONArray plans,JSONArray flows){
        Set<String> tasks=new HashSet<>();
        if(flows!=null)for(int i=0;i<flows.length();i++){JSONObject flow=flows.optJSONObject(i);if(flow!=null)tasks.add(flow.optString("task_id"));}
        JSONArray remaining=new JSONArray();
        if(plans!=null)for(int i=0;i<plans.length();i++){JSONObject plan=plans.optJSONObject(i);if(plan!=null&&!tasks.contains(plan.optString("task_id")))remaining.put(plan);}
        return remaining;
    }

    private static String stepStatus(JSONObject receipts,JSONObject step){
        JSONObject receipt=receipts.optJSONObject(step.optString("id"));
        return receipt==null?step.optString("status","pending"):receipt.optString("status","pending");
    }

    private static JSONObject flowPlan(JSONObject flow,JSONObject step){
        JSONObject plan=object(step.toString());
        put(plan,"task_id",flow.optString("task_id"));put(plan,"_flow_id",flow.optString("id"));
        return plan;
    }

    private static void queueClosedFlow(Context context,String scope,String id){
        if(scope.isEmpty()||id.isEmpty())return;
        JSONArray closed=closedFlows(context,scope);
        for(int i=0;i<closed.length();i++)if(id.equals(closed.optString(i)))return;
        closed.put(id);
        if(prefs(context).edit().putString(flowKey("flow_closed",scope),closed.toString()).commit()
                &&scope.equals(prefs(context).getString("scope","")))enqueueSync(context);
    }

    /** Closing is a separate acknowledged message; unacknowledged execution receipts survive it. */
    private static void cancelFlows(Context context,String taskId,boolean report){
        String active=prefs(context).getString("scope","");JSONArray flows=localFlows(context),kept=new JSONArray();
        for(int i=0;i<flows.length();i++){
            JSONObject flow=flows.optJSONObject(i);if(flow==null)continue;
            if(taskId!=null&&!taskId.equals(flow.optString("task_id"))){kept.put(flow);continue;}
            JSONArray steps=flow.optJSONArray("steps");
            if(steps!=null)for(int j=0;j<steps.length();j++){JSONObject step=steps.optJSONObject(j);if(step!=null)ReminderPlatform.cancel(context,active,flowPlan(flow,step));}
            if(report)queueClosedFlow(context,active,flow.optString("id"));
        }
        prefs(context).edit().putString("local_flows",kept.toString()).commit();
    }

    private static void acknowledgeFlows(Context context,String scope,JSONArray sent,JSONObject response){
        Set<String> accepted=new HashSet<>();JSONArray acks=response.optJSONArray("flow_result_acks");
        if(acks!=null)for(int i=0;i<acks.length();i++){
            JSONObject ack=acks.optJSONObject(i);if(ack!=null&&ack.optBoolean("accepted"))accepted.add(ack.optString("flow_id")+"\n"+ack.optString("step_id"));
        }
        Set<String> acceptedPayloads=new HashSet<>();
        for(int i=0;i<sent.length();i++){JSONObject item=sent.optJSONObject(i);if(item!=null&&accepted.contains(item.optString("flow_id")+"\n"+item.optString("step_id")))acceptedPayloads.add(item.toString());}
        JSONArray outbox=flowOutbox(context,scope),remaining=new JSONArray();
        for(int i=0;i<outbox.length();i++){JSONObject item=outbox.optJSONObject(i);if(item!=null&&!acceptedPayloads.contains(item.toString()))remaining.put(item);}
        Set<String> closedAck=new HashSet<>();JSONArray closedAcks=response.optJSONArray("closed_flow_acks");
        if(closedAcks!=null)for(int i=0;i<closedAcks.length();i++)closedAck.add(closedAcks.optString(i));
        JSONArray closed=closedFlows(context,scope),closedRemaining=new JSONArray();
        for(int i=0;i<closed.length();i++)if(!closedAck.contains(closed.optString(i)))closedRemaining.put(closed.optString(i));
        prefs(context).edit().putString(flowKey("flow_outbox",scope),remaining.toString())
                .putString(flowKey("flow_closed",scope),closedRemaining.toString()).commit();
        boolean progressed=remaining.length()<outbox.length()||closedRemaining.length()<closed.length();
        if(progressed&&(remaining.length()>0||closedRemaining.length()>0)&&scope.equals(prefs(context).getString("scope","")))enqueueSync(context);
    }

    private static void flowFeedback(Context context,String flowId,String stepId,String task,String status,String error,long at){
        if(!LocalReminderFlow.terminal(status))return;
        String active=prefs(context).getString("scope","");JSONObject receipts=flowReceipts(context),previous=receipts.optJSONObject(stepId);
        if(previous!=null&&LocalReminderFlow.terminal(previous.optString("status")))return;
        JSONObject result=new JSONObject();put(result,"flow_id",flowId);put(result,"step_id",stepId);put(result,"status",status);
        put(result,"occurred_at",Instant.ofEpochMilli(at).toString());put(result,"error",error);
        JSONObject receipt=object(result.toString());put(receipt,"at",at);put(receipt,"task_id",task);put(receipts,stepId,receipt);
        JSONArray outbox=flowOutbox(context,active);outbox.put(result);
        SharedPreferences.Editor save=prefs(context).edit().putString(flowKey("flow_receipts",active),receipts.toString())
                .putString(flowKey("flow_outbox",active),outbox.toString());
        if("delivered".equals(status)){
            JSONObject deliveries=object(prefs(context).getString("delivered","{}")),actual=new JSONObject();
            put(actual,"task_id",task);put(actual,"at",at);put(actual,"day",Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate().toString());
            put(deliveries,stepId,actual);save.putString("delivered",deliveries.toString());
        }
        if(!save.commit())throw new IllegalStateException("无法保存手机执行结果");
    }

    private static boolean finishedFlow(JSONObject flow,JSONObject receipts){
        JSONArray steps=flow.optJSONArray("steps");if(steps==null||steps.length()==0)return true;
        int delivered=0,terminal=0;
        for(int i=0;i<steps.length();i++){JSONObject step=steps.optJSONObject(i);if(step==null)continue;String status=stepStatus(receipts,step);
            if(LocalReminderFlow.terminal(status))terminal++;if("delivered".equals(status))delivered++;}
        return terminal==steps.length()||(flow.optBoolean("stop_after_success")&&delivered>0)
                ||delivered>=flow.optInt("remaining_reminders",6);
    }

    private static void finishConsumedFlows(Context context){
        JSONArray flows=localFlows(context);JSONObject receipts=flowReceipts(context);String active=prefs(context).getString("scope","");
        boolean changed=false;
        for(int i=0;i<flows.length();i++){
            JSONObject flow=flows.optJSONObject(i);if(flow==null||flow.optBoolean("_local_closed")||!finishedFlow(flow,receipts))continue;
            put(flow,"_local_closed",true);changed=true;queueClosedFlow(context,active,flow.optString("id"));
            JSONArray steps=flow.optJSONArray("steps");if(steps!=null)for(int j=0;j<steps.length();j++){
                JSONObject step=steps.optJSONObject(j);if(step!=null)ReminderPlatform.cancel(context,active,flowPlan(flow,step),!"delivered".equals(stepStatus(receipts,step)));
            }
        }
        if(changed)prefs(context).edit().putString("local_flows",flows.toString()).commit();
    }

    private static boolean validFlow(Context context,JSONObject flow){
        if(flow==null||!prefs(context).getString("device","").equals(flow.optString("device_id")))return false;
        if(flow.optString("generation").isEmpty()||flow.optString("cycle_key").isEmpty()||flow.optString("root_plan_id").isEmpty())return false;
        JSONArray steps=flow.optJSONArray("steps");List<LocalReminderFlow.Step> windows=new ArrayList<>();
        if(steps!=null)for(int i=0;i<steps.length();i++){JSONObject step=steps.optJSONObject(i);if(step==null)return false;
            windows.add(new LocalReminderFlow.Step(step.optString("id"),step.optInt("index",-1),step.optString("channel"),
                    ReminderPolicy.timestamp(step.optString("scheduled_at")),ReminderPolicy.timestamp(step.optString("expires_at"))));}
        return flow.optInt("remaining_reminders")>0&&flow.optInt("remaining_reminders")<=6
                &&LocalReminderFlow.valid(flow.optString("id"),flow.optString("task_id"),ReminderPolicy.timestamp(flow.optString("valid_until")),windows);
    }

    private static boolean reconcileFlows(Context context,JSONArray incoming,JSONArray revoked,boolean restore)throws Exception{
        if(incoming==null)incoming=new JSONArray();
        String active=prefs(context).getString("scope","");JSONArray old=localFlows(context),next=new JSONArray();
        Map<String,JSONObject> oldById=new HashMap<>();Set<String> revokedIds=new HashSet<>(),closedIds=new HashSet<>(),tasks=new HashSet<>(),stepIds=new HashSet<>();
        for(int i=0;i<old.length();i++){JSONObject flow=old.optJSONObject(i);if(flow!=null)oldById.put(flow.optString("id"),flow);}
        if(revoked!=null)for(int i=0;i<revoked.length();i++)revokedIds.add(revoked.optString(i));
        JSONArray closed=closedFlows(context,active);for(int i=0;i<closed.length();i++)closedIds.add(closed.optString(i));
        boolean ready=true;
        for(int i=0;i<incoming.length()&&i<128;i++){
            JSONObject raw=incoming.optJSONObject(i);if(raw==null)continue;JSONObject flow=object(raw.toString());
            String id=flow.optString("id");JSONObject previous=oldById.get(id);
            flow.remove("_local_closed");
            if(!validFlow(context,flow)||revokedIds.contains(id)||!tasks.add(flow.optString("task_id"))){
                if(!id.isEmpty())queueClosedFlow(context,active,id);ready=false;continue;
            }
            if(closedIds.contains(id)||(previous!=null&&previous.optBoolean("_local_closed")))put(flow,"_local_closed",true);
            Map<String,JSONObject> priorSteps=new HashMap<>();JSONArray previousSteps=previous==null?null:previous.optJSONArray("steps");
            if(previousSteps!=null)for(int j=0;j<previousSteps.length();j++){JSONObject step=previousSteps.optJSONObject(j);if(step!=null)priorSteps.put(step.optString("id"),step);}
            JSONArray steps=flow.optJSONArray("steps");boolean duplicate=false;
            for(int j=0;j<steps.length();j++){
                JSONObject step=steps.getJSONObject(j);step.remove("_local_scheduled");step.remove("_local_retry_at");
                if(!stepIds.add(step.optString("id"))){duplicate=true;break;}
                JSONObject prior=priorSteps.get(step.optString("id"));
                if(prior!=null&&prior.optString("scheduled_at").equals(step.optString("scheduled_at"))&&prior.optString("channel").equals(step.optString("channel"))){
                    put(step,"_local_scheduled",prior.optBoolean("_local_scheduled"));put(step,"_local_retry_at",prior.optLong("_local_retry_at"));
                }
            }
            if(duplicate){queueClosedFlow(context,active,id);ready=false;continue;}
            next.put(flow);oldById.remove(id);
        }
        JSONObject receipts=flowReceipts(context);
        for(JSONObject flow:oldById.values()){
            JSONArray steps=flow.optJSONArray("steps");if(steps!=null)for(int i=0;i<steps.length();i++){
                JSONObject step=steps.optJSONObject(i);if(step!=null)ReminderPlatform.cancel(context,active,flowPlan(flow,step),!"delivered".equals(stepStatus(receipts,step)));
            }
            if(revokedIds.contains(flow.optString("id")))stopRinging(context,active,flow.optString("task_id"));
            queueClosedFlow(context,active,flow.optString("id"));
        }
        // A settings migration can revoke a lease returned by the first sync before it was installed.
        // The device must close these explicit revocations too, or the server keeps their lease reserved.
        for(String id:revokedIds)queueClosedFlow(context,active,id);
        if(!prefs(context).edit().putString("local_flows",next.toString()).commit())throw new java.io.IOException("无法保存手机提醒流程");
        int scheduledCount=plans(context).length();long now=System.currentTimeMillis();
        for(int i=0;i<next.length();i++){
            JSONObject flow=next.getJSONObject(i);JSONArray steps=flow.getJSONArray("steps");
            for(int j=0;j<steps.length();j++){
                JSONObject step=steps.getJSONObject(j);String id=step.optString("id"),status=stepStatus(flowReceipts(context),step);
                if(flow.optBoolean("_local_closed")||LocalReminderFlow.terminal(status))continue;
                if("executing".equals(status)){
                    if(LocalReminderFlow.lostPlayback(status,isCurrentAlarm(context,active,id)))flowFeedback(context,flow.optString("id"),id,flow.optString("task_id"),"failed","上次播放未确认成功",now);
                    continue;
                }
                JSONObject plan=flowPlan(flow,step);long expires=ReminderPolicy.timestamp(step.optString("expires_at"));
                if(expires<=now||ReminderPolicy.timestamp(flow.optString("valid_until"))<=now){
                    flowFeedback(context,flow.optString("id"),id,flow.optString("task_id"),"failed","错过授权时段，本次未提醒",now);continue;
                }
                if(!allowed(context,plan,settings(context))){ReminderPlatform.cancel(context,active,plan);put(step,"_local_scheduled",false);ready=false;continue;}
                if(scheduledCount>=ReminderPolicy.MAX_PLANS){ready=false;continue;}
                scheduledCount++;
                if(!restore&&step.optBoolean("_local_scheduled"))continue;
                long retry=step.optLong("_local_retry_at");if(retry>ReminderPolicy.timestamp(plan.optString("scheduled_at")))put(plan,"scheduled_at",Instant.ofEpochMilli(retry).toString());
                try{ReminderPlatform.schedule(context,active,plan);put(step,"_local_scheduled",true);}
                catch(Exception failure){put(step,"_local_scheduled",false);ready=false;}
                if(!prefs(context).edit().putString("local_flows",next.toString()).commit())throw new java.io.IOException("无法保存系统排程状态");
            }
        }
        if(!prefs(context).edit().putString("local_flows",next.toString()).commit())throw new java.io.IOException("无法保存手机提醒状态");
        finishConsumedFlows(context);
        pruneFlowHistory(context);
        return ready;
    }

    private static void pruneFlowHistory(Context context){
        String active=prefs(context).getString("scope","");Set<String> retainedFlows=new HashSet<>(),unackedSteps=new HashSet<>();
        JSONArray flows=localFlows(context),outbox=flowOutbox(context,active);
        for(int i=0;i<flows.length();i++){JSONObject flow=flows.optJSONObject(i);if(flow!=null)retainedFlows.add(flow.optString("id"));}
        for(int i=0;i<outbox.length();i++){JSONObject result=outbox.optJSONObject(i);if(result!=null)unackedSteps.add(result.optString("step_id"));}
        JSONObject previous=flowReceipts(context),next=new JSONObject();
        for(java.util.Iterator<String> keys=previous.keys();keys.hasNext();){String id=keys.next();JSONObject receipt=previous.optJSONObject(id);
            if(receipt!=null&&(unackedSteps.contains(id)||retainedFlows.contains(receipt.optString("flow_id"))))put(next,id,receipt);}
        JSONObject deliveries=object(prefs(context).getString("delivered","{}")),recent=new JSONObject();long oldest=System.currentTimeMillis()-7*86400000L;
        for(java.util.Iterator<String> keys=deliveries.keys();keys.hasNext();){String id=keys.next();JSONObject value=deliveries.optJSONObject(id);if(value!=null&&value.optLong("at")>=oldest)put(recent,id,value);}
        prefs(context).edit().putString(flowKey("flow_receipts",active),next.toString()).putString("delivered",recent.toString()).commit();
    }

    /** Returns true for known flow identities, even when consumed or no longer allowed. */
    private static boolean fireFlow(Context context,String active,String id){
        JSONArray flows=localFlows(context);JSONObject flow=null,step=null;int stepIndex=-1;
        for(int i=0;i<flows.length();i++){
            JSONObject candidate=flows.optJSONObject(i);JSONArray steps=candidate==null?null:candidate.optJSONArray("steps");
            if(steps!=null)for(int j=0;j<steps.length();j++){JSONObject item=steps.optJSONObject(j);if(item!=null&&id.equals(item.optString("id"))){flow=candidate;step=item;stepIndex=j;break;}}
            if(flow!=null)break;
        }
        if(flow==null)return false;
        JSONObject receipts=flowReceipts(context);String status=stepStatus(receipts,step);
        if(flow.optBoolean("_local_closed")||LocalReminderFlow.terminal(status)||"executing".equals(status))return true;
        long now=System.currentTimeMillis(),blockedUntil=0;String task=flow.optString("task_id");JSONArray steps=flow.optJSONArray("steps");boolean predecessors=true;
        for(int i=0;i<stepIndex;i++){
            JSONObject prior=steps.optJSONObject(i);String priorStatus=stepStatus(receipts,prior);
            if(LocalReminderFlow.terminal(priorStatus))continue;
            boolean ringing=isCurrentAlarm(context,active,prior.optString("id"));
            if(LocalReminderFlow.lostPlayback(priorStatus,ringing)||(ReminderPolicy.timestamp(prior.optString("expires_at"))<=now&&!ringing))
                flowFeedback(context,flow.optString("id"),prior.optString("id"),task,"failed","前一步未在授权时段内执行",now);
            else {predecessors=false;blockedUntil=Math.max(blockedUntil,ringing
                    ?object(prefs(context).getString("ringing","{}")).optLong("until"):ReminderPolicy.timestamp(prior.optString("expires_at")));}
        }
        receipts=flowReceipts(context);
        if(finishedFlow(flow,receipts)){finishConsumedFlows(context);enqueueSync(context);return true;}
        JSONObject plan=flowPlan(flow,step),settings=settings(context),deliveries=object(prefs(context).getString("delivered","{}"));
        long last=0;int taskCount=0,total=0;String today=Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().toString();
        for(java.util.Iterator<String> keys=deliveries.keys();keys.hasNext();){JSONObject item=deliveries.optJSONObject(keys.next());if(item==null)continue;
            if(task.equals(item.optString("task_id")))last=Math.max(last,item.optLong("at"));
            if(today.equals(item.optString("day"))){total++;if(task.equals(item.optString("task_id")))taskCount++;}}
        // A restored server receipt carries actual delivery time, never its proposed schedule.
        for(int i=0;i<stepIndex;i++){JSONObject prior=steps.optJSONObject(i);if("delivered".equals(stepStatus(receipts,prior))){
            JSONObject receipt=receipts.optJSONObject(prior.optString("id"));
            long actual=receipt==null?ReminderPolicy.timestamp(prior.optString("occurred_at")):receipt.optLong("at");
            if(actual<=0){flowFeedback(context,flow.optString("id"),id,task,"failed","前步执行时间不完整，请联网同步",now);finishConsumedFlows(context);enqueueSync(context);return true;}last=Math.max(last,actual);}}
        long scheduled=ReminderPolicy.timestamp(step.optString("scheduled_at")),expires=ReminderPolicy.timestamp(step.optString("expires_at")),valid=ReminderPolicy.timestamp(flow.optString("valid_until"));
        long previousFinished=0;
        if(stepIndex>0){
            JSONObject previous=steps.optJSONObject(stepIndex-1),receipt=receipts.optJSONObject(previous.optString("id"));
            if(LocalReminderFlow.terminal(stepStatus(receipts,previous))){
                previousFinished=receipt==null?ReminderPolicy.timestamp(previous.optString("occurred_at")):receipt.optLong("at");
                if(previousFinished<=0){flowFeedback(context,flow.optString("id"),id,task,"failed","前步执行时间不完整，请联网同步",now);finishConsumedFlows(context);enqueueSync(context);return true;}
            }
        }
        long next=LocalReminderFlow.nextAttempt(now,scheduled,expires,valid,last,
                Math.max(flow.optInt("min_interval_minutes",1),settings.optInt("min_interval_minutes",1)),predecessors,previousFinished,step.optInt("wait_minutes"));
        if(next>=0){
            next=Math.max(next,blockedUntil);
            if(next>=Math.min(expires,valid))next=-1;
        }
        if(!allowed(context,plan,settings)||taskCount>=32||total>=ReminderPolicy.MAX_PLANS)next=-1;
        if(next<0){flowFeedback(context,flow.optString("id"),id,task,"failed","已过授权时段或达到提醒上限",now);finishConsumedFlows(context);enqueueSync(context);return true;}
        if(next>now){
            put(step,"_local_retry_at",next);put(step,"_local_scheduled",false);prefs(context).edit().putString("local_flows",flows.toString()).commit();
            put(plan,"scheduled_at",Instant.ofEpochMilli(next).toString());
            try{ReminderPlatform.schedule(context,active,plan);put(step,"_local_scheduled",true);prefs(context).edit().putString("local_flows",flows.toString()).commit();}
            catch(Exception failure){flowFeedback(context,flow.optString("id"),id,task,"failed","系统未接受本次延后",now);finishConsumedFlows(context);enqueueSync(context);}
            return true;
        }
        JSONObject consumed=new JSONObject();put(consumed,"flow_id",flow.optString("id"));put(consumed,"task_id",task);put(consumed,"status","executing");put(consumed,"at",now);put(receipts,id,consumed);
        put(step,"_local_scheduled",false);
        if(!prefs(context).edit().putString(flowKey("flow_receipts",active),receipts.toString()).putString("local_flows",flows.toString()).commit())return true;
        try{
            boolean alarm="alarm".equals(plan.optString("channel"));if(alarm)grantAlarm(context,active,plan);
            ReminderPlatform.notify(context,active,plan);
            if(!alarm){flowFeedback(context,flow.optString("id"),id,task,"delivered","",System.currentTimeMillis());finishConsumedFlows(context);}
        }catch(RuntimeException failed){flowFeedback(context,flow.optString("id"),id,task,"failed","系统未能开始本次提醒",System.currentTimeMillis());alarmStopped(context,active,id);finishConsumedFlows(context);}
        enqueueSync(context);return true;
    }

    private static String calendarMarker(String scope, JSONObject plan) {
        return ReminderPolicy.calendarMarker(scope, plan.optString("task_id"),
                ReminderPolicy.timestamp(plan.optString("scheduled_at")), TimeZone.getDefault().getID());
    }

    static void fire(Context source, String expectedScope, String id) {
        Context context = source.getApplicationContext();
        synchronized (LOCK) {
            if (!expectedScope.equals(prefs(context).getString("scope", "")) || !expectedScope.equals(scope(new Session(context)))) return;
            JSONObject test=object(prefs(context).getString("alarm_test","{}"));
            if(id.equals(test.optString("id"))){
                long now=System.currentTimeMillis();
                if(!test.optBoolean("fired")&&now>=ReminderPolicy.timestamp(test.optString("scheduled_at"))&&now<ReminderPolicy.timestamp(test.optString("expires_at"))&&allowed(context,test,settings(context))){
                    boolean alarm="alarm".equals(test.optString("channel"));
                    put(test,"fired",true);if(!prefs(context).edit().putString("alarm_test",test.toString()).commit())return;
                    if(alarm)grantAlarm(context,expectedScope,test);
                    android.util.Log.i("NiannianAlarm",(alarm?"test_receiver_fired":"test_notification_receiver_fired")+" at="+now);
                    try{ReminderPlatform.notify(context,expectedScope,test);
                        if(!alarm)android.util.Log.i("NiannianAlarm","test_notification_posted at="+System.currentTimeMillis());
                    }catch(RuntimeException failure){if(alarm)alarmStopped(context,expectedScope,id);android.util.Log.w("NiannianAlarm","test_start_failed at="+now);}
                }
                return;
            }
            if(fireFlow(context,expectedScope,id))return;
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
                    || !ReminderPolicy.due(now, ReminderPolicy.timestamp(plan.optString("scheduled_at")), ReminderPolicy.timestamp(plan.optString("expires_at")), last, settings.optInt("min_interval_minutes", 15), taskCount, totalCount)) {
                feedback(context, id, "failed", "计划已过期或达到提醒上限");
                plan.remove("local_scheduled");
            } else {
                // Mark consumed first: process death must never cause repeated sound.
                JSONObject receipt = new JSONObject(); put(receipt, "task_id", plan.optString("task_id")); put(receipt, "day", today); put(receipt, "at", now);
                put(delivered, id, receipt);
                if (!prefs(context).edit().putString("delivered", delivered.toString()).commit()) {
                    feedback(context, id, "failed", "无法保存执行记录，本次未弹出");
                    enqueueSync(context); return;
                }
                try { boolean alarm="alarm".equals(plan.optString("channel"));if(alarm)grantAlarm(context,expectedScope,plan);ReminderPlatform.notify(context, expectedScope, plan);if(!alarm)feedback(context, id, "delivered", ""); }
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
                    reconcileFlows(context,localFlows(context),new JSONArray(),true);
                    restoreTestReminder(context,active);
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
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).setPeriodic(15 * 60000L)
                .setBackoffCriteria(60000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build();
        context.getSystemService(JobScheduler.class).schedule(job);
    }
    static void enqueueSync(Context context) {
        if (prefs(context).getString("scope", "").isEmpty()) return;
        JobInfo job = new JobInfo.Builder(IMMEDIATE_JOB, new ComponentName(context, ReminderSyncJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(1000)
                .setBackoffCriteria(60000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build();
        context.getSystemService(JobScheduler.class).schedule(job);
    }
}
