package dev.niannian.reminder;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.lang.reflect.*;

/** Runs only in the isolated .audit package; no production account or network is used. */
public final class UiAudit extends Instrumentation {
    private MainActivity activity;
    private Bundle arguments;
    private File output;
    private int captures;
    private final java.util.Set<String> capturedIds=new java.util.HashSet<>();
    private UiFixtures fixtures;
    @Override public void onCreate(Bundle args) { arguments=args; start(); }
    @Override public void onStart() {
        Bundle result=new Bundle();int resultCode=Activity.RESULT_CANCELED;
        try {
            if(!getTargetContext().getPackageName().endsWith(".audit"))throw new AssertionError("Isolated package required");
            // OEM background-launch rules can silently block startActivitySync forever.
            // Use the same foreground launch as the device runner, with a bounded wait.
            ActivityMonitor launch=addMonitor(MainActivity.class.getName(),null,false);
            try(ParcelFileDescriptor command=getUiAutomation().executeShellCommand("am start -W -n "+getTargetContext().getPackageName()+"/"+MainActivity.class.getName())){
                activity=(MainActivity)waitForMonitorWithTimeout(launch,15000);
                if(activity==null)throw new AssertionError("Test app did not open within 15 seconds; unlock the device and retry");
            } finally {removeMonitor(launch);}
            fixtures=new UiFixtures();
            output=new File(getTargetContext().getExternalFilesDir(null),"ui-audit/"+System.currentTimeMillis());
            if(!output.isDirectory()&&!output.mkdirs())throw new IOException("Screenshot directory unavailable");
            Bundle location=new Bundle();location.putString("stream","UI_AUDIT_DIR="+output.getAbsolutePath()+"\n");sendStatus(0,location);
            String group=arguments.getString("group","all");
            if(group.equals("all")||group.equals("account"))accountPages();
            authenticated();
            if(group.equals("all")||group.equals("detail"))detailPages();
            if(group.equals("all")||group.equals("editor"))editorPages();
            if(group.equals("arrangement"))arrangementPages();
            if(group.equals("all")||group.equals("main"))mainPages();
            if(group.equals("all")||group.equals("records"))recordPages();
            if(group.equals("all"))for(int page=1;page<=72;page++){if((page==48||page==58)&&!arguments.getString("system","false").equals("true"))continue;String id=String.format(java.util.Locale.ROOT,"%02d",page);if(!capturedIds.contains(id))throw new AssertionError("Missing planned screenshot: "+id);}
            result.putString("stream","PASS: "+captures+" screenshots with loopback fixtures; group="+group+".\n");
            resultCode=Activity.RESULT_OK;
        } catch(Throwable e) {
            result.putString("stream","FAIL: "+android.util.Log.getStackTraceString(e));
        } finally { if(activity!=null)runOnMainSync(()->activity.finish());if(fixtures!=null)try{fixtures.close();}catch(IOException ignored){} }
        finish(resultCode,result);
    }
    private void authenticated()throws Exception{main(()->{Session s=(Session)get("session");s.base=fixtures.base;s.token="ui-test-token";s.email="ui@example.test";s.password="ui-test-password";s.autoLogin=false;set("gardenData",fixtures.garden);set("taskData",fixtures.tasks);});}
    private void detailPages()throws Exception{
        String[] statuses={"pending","done","archived","paused","upcoming","expired"};String[] ids={"28","29","30","64","65","66"};
        for(int i=0;i<statuses.length;i++){String state=statuses[i];main(()->{JSONObject task=UiFixtures.task(state.equals("pending")?"minute_interval":"daily",state.equals("archived")?"done":state);task.put("archived",state.equals("archived"));set("selected",task);show("detail");if(!state.equals("pending")&&findText(activity.getWindow().getDecorView(),"这件事已完成")!=null)throw new AssertionError("Completion allowed for "+state);if(state.equals("archived")&&findText(activity.getWindow().getDecorView(),"编辑提醒")!=null)throw new AssertionError("Archived task editable");});capture(ids[i]);}
        main(()->{set("selected",UiFixtures.task("minute_interval","pending"));show("detail");});click("稍后再提醒我");capture("40");dismissDialog();click("归档这条提醒");capture("41");dismissDialog();
        main(()->{call("openComplete",new Class[]{});});capture("31");
        File photo=new File(activity.getCacheDir(),"ui-test-photo.jpg");try(FileOutputStream out=new FileOutputStream(photo)){out.write(fixtures.photo);}
        main(()->{set("photoFile",photo);set("completionText","已经收好了，今天的阳光刚刚好。");call("render",new Class[]{});});capture("32");click("移除照片");assertText("已经收好了，今天的阳光刚刚好。");
        if(arguments.getString("system","false").equals("true")){
            click("选择照片");SystemClock.sleep(1000);capture("48");systemBack();
            click("拍照留档");SystemClock.sleep(1000);capture("58");systemBack();
        }
        int[] counts={1,3,7,7,-1};String[] growthIds={"33","67","68","60","69"};
        for(int i=0;i<counts.length;i++){int index=i,n=counts[i];main(()->{JSONObject record=UiFixtures.record("completion","",false).put("id","growth-"+index);if(n>=0)record.put("growth",UiFixtures.obj("from_count",index==3?n:n-1,"to_count",n,"added",index!=3));set("completedRecord",record);show("grown");});SystemClock.sleep(2700);capture(growthIds[i]);if(i>=3)main(()->{if(findText(activity.getWindow().getDecorView(),"再看一次生长")!=null)throw new AssertionError("Repeated or unknown growth can replay");});}
        // Submit through the actual completion UI against loopback and check the resulting route.
        main(()->{set("selected",UiFixtures.task("daily","pending"));call("openComplete",new Class[]{});});click("完成，送出这份养分");awaitScreen("grown");
    }
    private void editorPages()throws Exception{
        main(()->call("openEditor",new Class[]{JSONObject.class},(Object)null));capture("10");
        fixtures.aiDelay=1600;main(()->field("ai").setText("15 分钟后提醒我收衣服，没完成就每 5 分钟提醒"));click("帮我安排");capture("11");SystemClock.sleep(1700);syncUi();capture("12");fixtures.aiDelay=0;
        String[] kinds={"once","daily","weekly","monthly_window","date_range","interval","ongoing","minute_interval"};
        for(int i=0;i<kinds.length;i++){String kind=kinds[i];main(()->{set("draft",UiFixtures.task(kind,"pending"));set("aiText","");set("editing",false);set("manualVisible",true);set("rulesExpanded",true);set("advancedExpanded",false);set("aiExpanded",false);set("restoredForm",null);set("aiWarning","");show("editor");});capture(String.valueOf(13+i));main(()->{JSONObject built=(JSONObject)call("buildTask",new Class[]{});if(!built.getJSONObject("rule").getString("kind").equals(kind))throw new AssertionError("Rule round trip "+kind);});}
        main(()->{JSONObject task=UiFixtures.task("minute_interval","pending");task.getJSONObject("rule").put("end_at",java.time.OffsetDateTime.now().plusHours(3).withSecond(37).withNano(0).toString());call("openEditor",new Class[]{JSONObject.class},task);});click("更多设置");capture("21");
        main(()->{JSONObject saved=(JSONObject)call("buildTask",new Class[]{});if(!saved.getJSONObject("rule").getString("end_at").equals(((JSONObject)get("draft")).getJSONObject("rule").getString("end_at")))throw new AssertionError("Exact deadline lost");activity.onBackPressed();});capture("42");dismissDialog();
        main(()->{call("openEditor",new Class[]{JSONObject.class},UiFixtures.task("daily","pending"));field("start_date").performClick();});capture("43");systemBack();
        main(()->call("pickTime",new Class[]{int.class,String.class},-1,"16:15"));capture("44");systemBack();
        main(()->((Spinner)get("kindPicker")).performClick());capture("45");systemBack();
        main(()->((Spinner)get("modePicker")).performClick());capture("46");systemBack();click("20:30");capture("47");systemBack();
        main(()->{JSONObject suggested=UiFixtures.task("weekly","pending").put("title","散步");suggested.getJSONObject("rule").put("weekdays",new JSONArray().put(6)).put("times",new JSONArray().put("09:00"));set("draft",suggested);set("aiText","空气好的时候提醒我散步");set("editing",false);set("manualVisible",true);set("rulesExpanded",false);set("restoredForm",null);set("aiWarning","暂不支持自动判断空气质量。我先安排在每周六上午，出发前请你确认天气。保存前可以继续调整。");show("editor");});capture("52");
        main(()->{set("rulesExpanded",true);set("aiWarning","");call("render",new Class[]{});for(CheckBox day:(CheckBox[])get("weekdays"))day.setChecked(false);});click("确认安排，播下种子");capture("53");assertText("请选择至少一个星期");
        fixtures.aiFailure=true;main(()->{call("openEditor",new Class[]{JSONObject.class},(Object)null);field("ai").setText("等会儿提醒我搞卫生");});click("帮我安排");SystemClock.sleep(600);syncUi();capture("54");assertText("重新安排");fixtures.aiFailure=false;
    }
    private void arrangementPages()throws Exception{
        final String request="15 分钟后提醒我收衣服，没完成就每 5 分钟提醒";
        final String firstNote="我先安排在 15 分钟后提醒。",secondNote="还没完成时，会按你选择的间隔继续提醒。",thirdNote="完成后永久停止这件事的提醒。";
        final String allNotes=firstNote+"\n"+secondNote+"\n"+thirdNote;
        fixtures.parsed=UiFixtures.task("minute_interval","pending").put("warnings",new JSONArray().put(firstNote).put(secondNote).put(thirdNote));
        main(()->{
            Session session=(Session)get("session");session.login("ui-test-token","ui@example.test","ui-test-password");
            activity.getSharedPreferences("reminder_execution_v1",Context.MODE_PRIVATE).edit().clear()
                    .putString("scope",ReminderCoordinator.scope(session)).putString("settings",fixtures.reminderSettings.toString())
                    .putBoolean("configured",false).putString("plans","[]").commit();
            call("openEditor",new Class[]{JSONObject.class},(Object)null);
        });
        awaitCondition("initial setup prompt",()->get("profileDialog")!=null);capture("arrangement-01-setup");
        clickDialog("先安排，稍后设置");assertScreen("editor");assertNoDialog();assertWrites(0,0,0);
        main(()->call("openEditor",new Class[]{JSONObject.class},(Object)null));awaitCondition("setup prompt on a new entry",()->get("profileDialog")!=null);
        clickDialog("去设置提醒方式");awaitScreen("reminders");
        main(()->{if(!"editor".equals(get("reminderSettingsBack")))throw new AssertionError("Settings lost the editor return destination");});capture("arrangement-02-settings");
        click("返回，继续安排");awaitScreen("editor");assertNoDialog();
        main(()->field("ai").setText(request));click("帮我安排");
        awaitCondition("AI arrangement summary",()->!(Boolean)get("taskBusy")&&(Boolean)get("manualVisible")&&allNotes.equals(get("aiWarning")));
        assertText("本次安排");assertText("确认后生效");assertText("查看安排依据与注意事项");assertText(firstNote);assertAbsent(allNotes);assertAbsent("念念可用的提醒方式  ›");
        main(()->{if((Boolean)get("rulesExpanded"))throw new AssertionError("AI rules should start collapsed");});capture("arrangement-03-summary");
        click("查看安排依据与注意事项");assertText(allNotes);assertAbsent(firstNote);click("收起说明");assertText(firstNote);assertAbsent(allNotes);
        main(()->field("title").setText("收好阳台的衣服"));click("调整规则");main(()->field("repeat_minutes").setText("7"));click("收起规则");
        click("设置");awaitScreen("reminders");click("念念可用的提醒方式  ›");
        main(()->{
            for(String channel:new String[]{"手机通知","手机日历","闹钟提醒","邮件（可选）"}){
                View found=findText(activity.getWindow().getDecorView(),channel);
                if(!(found instanceof Switch)||((Switch)found).isChecked())throw new AssertionError("Unconfigured channel was preselected: "+channel);
            }
        });
        click("邮件（可选）");click("启用所选提醒方式");
        awaitCondition("explicit mail-only settings saved",()->ReminderCoordinator.configured(activity)&&findText(activity.getWindow().getDecorView(),"提醒设置已保存")!=null);
        if(!fixtures.reminderSettings.optBoolean("configured")||!"[\"email\"]".equals(fixtures.reminderSettings.optJSONArray("channels").toString()))throw new AssertionError("Fixture did not receive explicit email-only settings");
        click("返回，继续安排");awaitScreen("editor");assertNoDialog();
        main(()->{
            if(!request.equals(get("aiText"))||!request.equals(field("ai").getText().toString()))throw new AssertionError("Settings return lost the original request");
            if(!"收好阳台的衣服".equals(field("title").getText().toString()))throw new AssertionError("Settings return lost the edited title");
            JSONObject built=(JSONObject)call("buildTask",new Class[]{});if(built.getJSONObject("rule").getInt("repeat_minutes")!=7)throw new AssertionError("Settings return lost the edited interval");
        });
        assertText("当前可用：邮件");click("选花");
        revealDescription("向日葵，未选中");clickDescription("向日葵，未选中");
        main(()->{if(!Integer.valueOf(4).equals(get("selectedFlowerIndex")))throw new AssertionError("New sunflower choice was not selected");});assertText("向日葵 · 这次就种它");capture("arrangement-04-flower");
        // An accidental back press must neither create nor complete a task.
        main(()->activity.onBackPressed());awaitCondition("leave-editor confirmation",()->get("profileDialog")!=null);clickDialog("继续编辑");assertScreen("editor");assertWrites(0,0,0);
        fixtures.taskDelay=450;
        main(()->{View save=findText(activity.getWindow().getDecorView(),"确认安排，播下种子");if(save==null)throw new AssertionError("New confirmation button absent");save.performClick();save.performClick();});
        awaitScreen("home");fixtures.taskDelay=0;settle();assertWrites(1,0,0);
        if(fixtures.lastCreateBody==null||!"sunflower".equals(fixtures.lastCreateBody.optString("flower_type"))||fixtures.lastCreateBody.getJSONObject("rule").getInt("repeat_minutes")!=7)throw new AssertionError("Saved request lost the selected flower or edited interval");
        assertAbsent("这件事已完成");capture("arrangement-05-created-home");
        main(()->call("openEditor",new Class[]{JSONObject.class},(Object)null));assertNoDialog();
        main(()->activity.onBackPressed());awaitCondition("new editor back confirmation",()->get("profileDialog")!=null);clickDialog("离开");awaitScreen("home");assertWrites(1,0,0);
        main(()->{set("selected",fixtures.detail);call("openEditor",new Class[]{JSONObject.class},fixtures.detail);});assertNoDialog();main(()->field("title").setText("衣服安排已调整"));click("保存修改");awaitScreen("detail");assertWrites(1,1,0);
        main(()->activity.onBackPressed());awaitScreen("home");assertWrites(1,1,0);
        Bundle checked=new Bundle();checked.putString("stream","ARRANGEMENT_REQUESTS create="+fixtures.createRequests.get()+", update="+fixtures.updateRequests.get()+", complete="+fixtures.completeRequests.get()+"\n");sendStatus(0,checked);
    }
    private void mainPages()throws Exception{
        fixtures.tasks=new JSONArray().put(UiFixtures.task("daily","pending"));main(()->{set("search","");set("filter","待完成");show("home");});settle();capture("22");
        main(()->{set("search","找不到的小事");show("home");});settle();capture("56");click("清除搜索");
        fixtures.tasks=new JSONArray();main(()->{set("taskData",new JSONArray());show("home");});settle();capture("55");
        fixtures.taskDelay=1800;main(()->{set("taskData",new JSONArray());show("home");Button refresh=(Button)findDescription(activity.getWindow().getDecorView(),"刷新提醒");if(refresh.getText().length()!=0)throw new AssertionError("Loading caption overlaps refresh icon");});capture("70");SystemClock.sleep(1800);fixtures.taskDelay=0;
        fixtures.garden=UiFixtures.garden(14);main(()->{set("gardenData",fixtures.garden);set("gardenMemoriesExpanded",false);show("garden");});settle();capture("23");
        main(()->{GardenPlotView scene=(GardenPlotView)get("gardenScene");set("gardenSelectedPlantId","task-01");call("drawGarden",new Class[]{});});clickDescription("展开花园抽屉");SystemClock.sleep(350);capture("26");
        main(()->activity.onBackPressed());clickDescriptionPrefix("选择花圃，");capture("27");systemBack();
        fixtures.gardenFailure=true;main(()->call("loadGarden",new Class[]{}));settle();capture("57");fixtures.gardenFailure=false;
        fixtures.garden=UiFixtures.garden(0);main(()->{set("gardenData",fixtures.garden);set("gardenMemoriesExpanded",false);show("garden");});settle();capture("25");
        main(()->show("settings"));capture("07");click("退出登录");capture("09");dismissDialog();
        clickDescriptionPrefix("检查连接和登录状态");settle();fixtures.exportDelay=1800;clickDescriptionPrefix("导出我的全部文字记录");capture("72");SystemClock.sleep(1800);capture("08");systemBack();fixtures.exportDelay=0;
    }
    private void recordPages()throws Exception{
        fixtures.garden=UiFixtures.garden(14);main(()->{set("calendarDate",java.time.LocalDate.now());set("calendarMonth",java.time.YearMonth.now());set("recordPage",0);show("history");});settle();capture("24");
        fixtures.entries=new JSONArray().put(UiFixtures.record("planned","",false));main(()->call("loadCalendar",new Class[]{Button.class},(Object)null));settle();capture("37");
        fixtures.entries=new JSONArray();main(()->call("loadCalendar",new Class[]{Button.class},(Object)null));settle();capture("38");
        JSONArray many=new JSONArray();for(int i=0;i<25;i++)many.put(UiFixtures.record("completion","",false).put("id","page-record-"+i));fixtures.entries=many;main(()->call("loadCalendar",new Class[]{Button.class},(Object)null));settle();main(()->{set("recordPage",1);call("drawCalendar",new Class[]{});});capture("39");
        fixtures.calendarFailure=true;main(()->call("loadCalendar",new Class[]{Button.class},(Object)null));settle();capture("71");fixtures.calendarFailure=false;
        String[] types={"completion","notification","notification","notification","completion"};String[] statuses={"","sent","failed","pending",""};String[] ids={"34","35","61","62","63"};
        for(int i=0;i<ids.length;i++){int index=i;main(()->{JSONObject record=UiFixtures.record(types[index],statuses[index],index==0);if(index==4)record.put("text","");set("selectedRecord",record);show("record");});settle();capture(ids[i]);}
        main(()->{set("photoId","photo-01");set("photoTitle","读书");show("photo");});settle();capture("36");
        fixtures.mediaFailure=true;main(()->show("photo"));settle();capture("59");fixtures.mediaFailure=false;clickDescription("照片加载失败，点击重试");settle();main(()->{if(findDescription(activity.getWindow().getDecorView(),"完成凭证照片")==null)throw new AssertionError("Photo retry did not recover");});
    }
    // The garden has a perpetual breeze; a queue-idle wait can never finish under software rendering.
    private void syncUi(){java.util.concurrent.CountDownLatch ready=new java.util.concurrent.CountDownLatch(1);new Handler(Looper.getMainLooper()).post(ready::countDown);try{if(!ready.await(8,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("UI thread did not respond");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
    private void settle(){SystemClock.sleep(650);syncUi();}
    private interface Probe{boolean ready()throws Exception;}
    private void awaitCondition(String label,Probe probe)throws Exception{for(int i=0;i<40;i++){final boolean[] ready={false};main(()->ready[0]=probe.ready());if(ready[0])return;SystemClock.sleep(200);}throw new AssertionError("Timed out: "+label);}
    private void assertScreen(String expected)throws Exception{main(()->{if(!expected.equals(get("screen")))throw new AssertionError("Expected "+expected+", got "+get("screen"));});}
    private void assertNoDialog()throws Exception{main(()->{if(get("profileDialog")!=null)throw new AssertionError("Unexpected setup prompt or dialog");});}
    private void assertWrites(int creates,int updates,int completes){if(fixtures.createRequests.get()!=creates||fixtures.updateRequests.get()!=updates||fixtures.completeRequests.get()!=completes)throw new AssertionError("Unexpected requests: create="+fixtures.createRequests.get()+", update="+fixtures.updateRequests.get()+", complete="+fixtures.completeRequests.get());}
    private void clickDialog(String label)throws Exception{main(()->{AlertDialog dialog=(AlertDialog)get("profileDialog");if(dialog==null)throw new AssertionError("Missing dialog for "+label);View found=findText(dialog.getWindow().getDecorView(),label);if(found==null)throw new AssertionError("Missing dialog action: "+label);found.performClick();});}
    private void revealDescription(String label)throws Exception{main(()->{View found=findDescription(activity.getWindow().getDecorView(),label);if(found==null)throw new AssertionError("Missing choice: "+label);found.requestRectangleOnScreen(new android.graphics.Rect(0,0,found.getWidth(),found.getHeight()),true);});SystemClock.sleep(250);}
    private void assertAbsent(String text)throws Exception{main(()->{if(findText(activity.getWindow().getDecorView(),text)!=null)throw new AssertionError("Unexpected visible text: "+text);});}
    private void awaitScreen(String expected)throws Exception{for(int i=0;i<25;i++){final boolean[] match={false};main(()->match[0]=expected.equals(get("screen")));if(match[0])return;SystemClock.sleep(200);}throw new AssertionError("Did not reach "+expected);}
    private void click(String label)throws Exception{main(()->{View found=findText(activity.getWindow().getDecorView(),label);if(found==null)throw new AssertionError("Missing action: "+label);while(!found.isClickable()&&found.getParent() instanceof View)found=(View)found.getParent();found.performClick();});}
    private void clickDescription(String label)throws Exception{main(()->{View found=findDescription(activity.getWindow().getDecorView(),label);if(found==null)throw new AssertionError("Missing action: "+label);found.performClick();});}
    private void clickDescriptionPrefix(String label)throws Exception{main(()->{View found=findDescriptionPrefix(activity.getWindow().getDecorView(),label);if(found==null)throw new AssertionError("Missing action: "+label);found.performClick();});}
    private View findDescriptionPrefix(View v,String text){if(v.getVisibility()!=View.VISIBLE)return null;if(v.getContentDescription()!=null&&v.getContentDescription().toString().startsWith(text))return v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){View found=findDescriptionPrefix(g.getChildAt(i),text);if(found!=null)return found;}}return null;}
    private void dismissDialog()throws Exception{main(()->{AlertDialog dialog=(AlertDialog)get("profileDialog");if(dialog==null)throw new AssertionError("Dialog absent");dialog.dismiss();});}
    private void systemBack(){getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);SystemClock.sleep(350);syncUi();}
    private void accountPages() throws Exception {
        main(()->{Session s=(Session)get("session");s.base="http://127.0.0.1:1";s.email="ui@example.test";s.password="";s.token="";s.autoLogin=false;set("authMode","login");show("login");});
        capture("01");
        if(!"capture".equals(arguments.getString("mode")))assertText("欢迎来到念念");
        main(()->{set("authMode","register");show("login");});capture("02");
        main(()->{set("authMode","reset");show("login");});capture("03");
        main(()->show("home"));capture("04");
        main(()->{((Session)get("session")).base="";show("server");});capture("05");
        main(()->{((Session)get("session")).base="http://127.0.0.1:1";show("server");});click("测试连接");settle();capture("06");assertText("重试连接");
        main(()->{set("authMode","login");show("login");call("authenticationFailed",new Class[]{boolean.class,String.class},false,"邮箱或密码不正确，请重新输入");});capture("49");
        main(()->{set("authMode","register");set("codeAvailableAt",System.currentTimeMillis()+60000);show("login");});capture("50");
        main(()->{set("homeNotice","登录已过期，请重新登录。提醒和记录仍然保留。");show("home");});capture("51");
        main(()->{set("authMode","login");show("login");});
        if(!"capture".equals(arguments.getString("mode"))){
            main(()->{EditText p=field("password");p.setText("audit-password");findDescription(activity.getWindow().getDecorView(),"显示密码").performClick();if(p.getTransformationMethod()!=null)throw new AssertionError("Password still concealed");findDescription(activity.getWindow().getDecorView(),"隐藏密码").performClick();if(p.getTransformationMethod()==null)throw new AssertionError("Password not concealed");field("email").setText("invalid");findText(activity.getWindow().getDecorView(),"登录念念").performClick();if(field("email").getError()==null)throw new AssertionError("Invalid email accepted");});
        }
    }
    private EditText field(String key)throws Exception{return (EditText)((java.util.Map<?,?>)get("fields")).get(key);}
    private void show(String screen)throws Exception{set("screen",screen);call("render",new Class[]{});}
    private Object get(String name)throws Exception{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(activity);}
    private void set(String name,Object value)throws Exception{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);f.set(activity,value);}
    private Object call(String name,Class<?>[] types,Object...args)throws Exception{Method m=MainActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(activity,args);}
    private interface Work{void run()throws Exception;}
    private void main(Work work)throws Exception{Throwable[] failure={null};runOnMainSync(()->{try{work.run();}catch(Throwable e){failure[0]=e;}});if(failure[0]!=null)throw new Exception(failure[0]);syncUi();}
    private void assertText(String text)throws Exception{main(()->{if(findText(activity.getWindow().getDecorView(),text)==null)throw new AssertionError("Missing visible text: "+text);});}
    private View findText(View v,String text){if(v.getVisibility()!=View.VISIBLE)return null;if(v instanceof TextView&&((TextView)v).getText().toString().equals(text))return v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){View found=findText(g.getChildAt(i),text);if(found!=null)return found;}}return null;}
    private View findDescription(View v,String text){if(v.getVisibility()!=View.VISIBLE)return null;if(v.getContentDescription()!=null&&text.contentEquals(v.getContentDescription()))return v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){View found=findDescription(g.getChildAt(i),text);if(found!=null)return found;}}return null;}
    private void capture(String id)throws Exception{
        if(!capturedIds.add(id))throw new AssertionError("Duplicate screenshot: "+id);
        syncUi();SystemClock.sleep(350);screenshot(id);captures++;
        StringBuilder hierarchy=new StringBuilder();main(()->describe(activity.getWindow().getDecorView(),hierarchy,0));try(Writer writer=new OutputStreamWriter(new FileOutputStream(new File(output,id+".txt")),java.nio.charset.StandardCharsets.UTF_8)){writer.write(hierarchy.toString());}
        final ScrollView[] scroll={null};final int[] start={0};main(()->{View body=(View)get("body");if(body.getParent() instanceof ScrollView){ScrollView candidate=(ScrollView)body.getParent();if(candidate.canScrollVertically(1)&&(AlertDialog)get("profileDialog")==null){scroll[0]=candidate;start[0]=candidate.getScrollY();}}});
        if(scroll[0]!=null&&!"arrangement".equals(arguments.getString("group"))){main(()->scroll[0].fullScroll(View.FOCUS_DOWN));SystemClock.sleep(200);screenshot(id+"-bottom");main(()->scroll[0].scrollTo(0,start[0]));}
        Bundle progress=new Bundle();progress.putString("stream","Captured "+id+"\n");sendStatus(0,progress);
    }
    private void screenshot(String name)throws IOException{Bitmap bitmap=getUiAutomation().takeScreenshot();if(bitmap==null)throw new AssertionError("Screenshot unavailable: "+name);try(OutputStream out=new FileOutputStream(new File(output,name+".png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}finally{bitmap.recycle();}}
    private void describe(View v,StringBuilder result,int depth){if(v.getVisibility()!=View.VISIBLE)return;for(int i=0;i<depth;i++)result.append(' ');result.append(v.getClass().getSimpleName()).append(" ").append(v.getWidth()).append('x').append(v.getHeight());if(v instanceof TextView){TextView t=(TextView)v;boolean password=t instanceof EditText&&(((EditText)t).getInputType()&android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)!=0;result.append(" ").append(password?"[password]":t.getText().toString());}if(v.getContentDescription()!=null)result.append(" [").append(v.getContentDescription()).append(']');result.append('\n');if(v instanceof ViewGroup){ViewGroup group=(ViewGroup)v;for(int i=0;i<group.getChildCount();i++)describe(group.getChildAt(i),result,depth+1);}}
}
