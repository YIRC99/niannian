package dev.niannian.reminder;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.text.*;
import android.text.method.LinkMovementMethod;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import androidx.core.content.FileProvider;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

/** Native, lightweight UI. All network and image work happens off the UI thread. */
public class MainActivity extends Activity {
    private static final int BLUE = 0xff3568e8, INK = 0xff192744, MUTED = 0xff64748b,
            BG = 0xfff5f8ff, PAPER = 0xfffefeff, PALE = 0xffeaf1ff, LINE = 0xffdbe4f2,
            RED = 0xffb74949, GREEN = 0xff22765b;
    private static final String[] KINDS = {"once", "daily", "weekly", "monthly_window", "date_range", "interval", "ongoing"};
    private static final String[] KIND_LABELS = {"只在一天提醒", "每天", "每周指定星期", "每月一段时间", "指定日期范围", "每隔几天", "每天提醒，做完结束"};
    private static final String[] MODES = {"until_done", "every_day", "notify_once"};
    private static final String[] MODE_LABELS = {"本期完成一次就停止", "每天分别完成", "本期只通知一次"};
    private final ExecutorService workers = Executors.newFixedThreadPool(3);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Session session;
    private int accountEpoch = 0, pageEpoch = 0;
    private String screen = "home", filter = "全部", search = "";
    private JSONObject selected = new JSONObject(), draft = new JSONObject(), restoredForm;
    private JSONArray taskData = new JSONArray();
    private LinearLayout root, body, taskList;
    private TextView errorView;
    private final Map<String, EditText> fields = new LinkedHashMap<>();
    private Spinner kindPicker, modePicker;
    private CheckBox[] weekdays;
    private File photoFile, cameraFile;
    private String completionText = "", aiText = "", aiWarning = "";
    private boolean editing = false, manualVisible = false, taskBusy = false;
    private long codeAvailableAt;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        session = new Session(this);
        if (state != null) {
            screen = state.getString("screen", "home"); filter = state.getString("filter", "全部");
            try { selected = new JSONObject(state.getString("selected", "{}")); draft = new JSONObject(state.getString("draft", "{}")); restoredForm = new JSONObject(state.getString("form", "{}")); } catch (Exception ignored) {}
            editing = state.getBoolean("editing"); manualVisible = state.getBoolean("manual");
            aiText = state.getString("aiText", ""); aiWarning = state.getString("aiWarning", ""); completionText = state.getString("completionText", "");
            String p = state.getString("photo", ""), c = state.getString("camera", "");
            if (!p.isEmpty()) photoFile = new File(p); if (!c.isEmpty()) cameraFile = new File(c);
            codeAvailableAt = state.getLong("codeAvailableAt");
        }
        if (session.base.isEmpty()) screen = "server";
        else if (session.token.isEmpty() && !screen.equals("server")) screen = "login";
        render();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        captureTransient();
        out.putString("screen", screen); out.putString("selected", selected.toString()); out.putString("draft", draft.toString());
        out.putString("form", restoredForm == null ? "{}" : restoredForm.toString()); out.putString("filter", filter);
        out.putBoolean("editing", editing); out.putBoolean("manual", manualVisible);
        out.putString("aiText", aiText); out.putString("aiWarning", aiWarning); out.putString("completionText", completionText);
        out.putString("photo", photoFile == null ? "" : photoFile.getAbsolutePath()); out.putString("camera", cameraFile == null ? "" : cameraFile.getAbsolutePath());
        out.putLong("codeAvailableAt", codeAvailableAt); super.onSaveInstanceState(out);
    }
    @Override public void onDestroy() { super.onDestroy(); workers.shutdownNow(); ui.removeCallbacksAndMessages(null); }
    @Override public void onBackPressed() {
        if (screen.equals("home") || screen.equals("login") || (screen.equals("server") && session.base.isEmpty())) { super.onBackPressed(); return; }
        if (screen.equals("editor")) {
            new AlertDialog.Builder(this).setTitle("离开编辑？").setMessage("还没有保存的修改将丢弃。").setNegativeButton("继续编辑", null)
                    .setPositiveButton("离开", (d,w) -> navigate(editing ? "detail" : "home")).show(); return;
        }
        navigate(screen.equals("photo") ? "history" : screen.equals("complete") ? "detail" : session.token.isEmpty() ? "login" : "home");
    }
    private void navigate(String next) { captureTransient(); screen = next; render(); }
    private void captureTransient() {
        if (screen.equals("editor")) {
            restoredForm = new JSONObject();
            try {
                for (Map.Entry<String, EditText> entry : fields.entrySet()) restoredForm.put(entry.getKey(), entry.getValue().getText().toString());
                if (kindPicker != null) restoredForm.put("kind_index", kindPicker.getSelectedItemPosition());
                if (modePicker != null) restoredForm.put("mode_index", modePicker.getSelectedItemPosition());
                JSONArray days = new JSONArray(); if (weekdays != null) for (int i=0;i<7;i++) if (weekdays[i].isChecked()) days.put(i+1);
                restoredForm.put("weekdays", days);
            } catch (Exception ignored) {}
            if (fields.containsKey("ai")) aiText = value("ai");
        }
        if (screen.equals("complete") && fields.containsKey("completion")) completionText = value("completion");
    }
    private void render() {
        pageEpoch++; taskBusy = false; fields.clear(); kindPicker = null; modePicker = null; weekdays = null;
        root = column(); root.setBackgroundColor(BG); setContentView(root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 35) { android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime()); v.setPadding(i.left, i.top, i.right, i.bottom); }
            return insets;
        });
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        body = column(); body.setPadding(dp(24), dp(20), dp(24), dp(24)); scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        if (!Arrays.asList("home", "history", "settings", "login", "server").contains(screen)) {
            TextView back = text("‹  返回", 15, BLUE, true); back.setMinHeight(dp(48)); back.setGravity(Gravity.CENTER_VERTICAL); back.setOnClickListener(v -> onBackPressed()); body.addView(back);
        }
        errorView = text("", 14, RED, false); errorView.setPadding(dp(14), dp(12), dp(14), dp(12)); errorView.setBackground(shape(0xffffeeee, 14)); errorView.setVisibility(View.GONE); body.addView(errorView);
        switch (screen) {
            case "server": renderServer(); break;
            case "login": renderLogin(); break;
            case "history": renderHistory(false); break;
            case "logs": renderHistory(true); break;
            case "settings": renderSettings(); break;
            case "editor": renderEditor(); break;
            case "detail": renderDetail(); break;
            case "complete": renderComplete(); break;
            case "photo": renderPhoto(); break;
            default: renderHome(); break;
        }
        if (Arrays.asList("home", "history", "settings").contains(screen) && !session.token.isEmpty()) bottomNav();
    }
    private void header(String overline, String title, String subtitle) {
        if (!overline.isEmpty()) { TextView over = text(overline, 12, BLUE, true); over.setLetterSpacing(.09f); body.addView(over); gap(body, 8); }
        body.addView(text(title, 28, INK, true));
        if (!subtitle.isEmpty()) { gap(body,8); body.addView(text(subtitle, 14, MUTED, false)); }
        gap(body,24);
    }
    private void renderServer() {
        ImageView logo = new ImageView(this); logo.setImageResource(R.drawable.ic_launcher); body.addView(logo, new LinearLayout.LayoutParams(dp(76), dp(76))); gap(body,24);
        header("NIANNIAN", "把惦记的事，交给念念", "连接你的提醒服务，邮件会按时到达。手机不用一直开着。");
        field(body,"server","服务器地址",session.base,"https://你的域名 或 http://局域网IP:8080",1);
        note(body,"本地调试：手机和电脑连接同一个 Wi-Fi，填写电脑的局域网 IP；手机上的 localhost 指向手机自己。");
        Button test = button("测试连接", false); body.addView(test); test.setOnClickListener(v -> {
            String address;
            try { address = Api.normalize(value("server")); } catch (Exception e) { showError(e.getMessage()); return; }
            run(test, a -> new Api(address, "").json("GET", "/health", null), data -> {
                if (!"ok".equals(data.optString("status"))) { showError("地址可访问，但不是念念提醒服务"); return; }
                toast("连接成功 · " + data.optString("version", "服务正常"));
            });
        }); gap(body,12);
        Button save = button(session.base.isEmpty() ? "连接并继续" : "保存服务器地址", true); body.addView(save);
        save.setOnClickListener(v -> {
            String address;
            try { address = Api.normalize(value("server")); } catch (Exception e) { showError(e.getMessage()); return; }
            run(save, a -> new Api(address, "").json("GET", "/health", null), data -> {
                if (!"ok".equals(data.optString("status"))) { showError("这不是念念提醒服务，请检查地址"); return; }
                if (!session.base.equals(address)) { accountEpoch++; session.switchServer(address); clearAccountData(); }
                navigate(session.token.isEmpty() ? "login" : "settings");
            });
        });
        if (!session.base.isEmpty()) { gap(body,12); Button cancel = button("返回", false); body.addView(cancel); cancel.setOnClickListener(v -> navigate(session.token.isEmpty() ? "login" : "settings")); }
        gap(body,20); note(body,"切换到其他服务器后需要重新登录。不同服务器的任务和照片分别保存。公网部署建议使用 HTTPS。");
    }
    private void renderLogin() {
        ImageView logo = new ImageView(this); logo.setImageResource(R.drawable.ic_launcher); body.addView(logo, new LinearLayout.LayoutParams(dp(68),dp(68))); gap(body,24);
        header("欢迎回来", "用邮箱，收下每个提醒", "验证码登录后会记住你。提醒只发到这个邮箱。");
        EditText email = field(body,"email","接收提醒的邮箱",session.email,"你的邮箱地址",1); email.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        EditText code = field(body,"code","邮箱验证码","","输入邮件中的验证码",1); code.setInputType(android.text.InputType.TYPE_CLASS_NUMBER); code.setFilters(new InputFilter[]{new InputFilter.LengthFilter(8)});
        Button send = button("发送验证码", false); body.addView(send); updateCodeButton(send);
        send.setOnClickListener(v -> {
            String address = value("email"); if (!android.util.Patterns.EMAIL_ADDRESS.matcher(address).matches()) { email.setError("请填写有效的邮箱地址"); return; }
            run(send, a -> a.json("POST", "/api/v1/auth/code", object("email",address)), result -> { codeAvailableAt = System.currentTimeMillis()+60000; updateCodeButton(send); toast("验证码已发送，请查看邮箱和垃圾邮件"); });
        }); gap(body,12);
        Button login = button("登录念念", true); body.addView(login);
        login.setOnClickListener(v -> {
            String address=value("email"), digits=value("code");
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(address).matches()) { email.setError("请填写有效的邮箱地址"); return; }
            if (digits.length()!=6) { code.setError("请输入 6 位验证码"); return; }
            run(login, a -> a.json("POST", "/api/v1/auth/verify", object("email",address,"code",digits)), result -> {
                try { session.login(result.getString("token"),result.getJSONObject("user").getString("email")); accountEpoch++; clearAccountData(); navigate("home"); }
                catch(Exception e) { showError("无法保存登录状态，请重试"); }
            });
        }); gap(body,24); note(body,session.base);
        Button change = button("更换服务器",false); body.addView(change); change.setOnClickListener(v -> navigate("server"));
    }
    private void updateCodeButton(Button button) {
        long remaining = (codeAvailableAt - System.currentTimeMillis() + 999) / 1000;
        if (remaining <= 0) { button.setEnabled(true); button.setText("发送验证码"); return; }
        button.setEnabled(false); button.setText(remaining + " 秒后可重发");
        int p=pageEpoch; ui.postDelayed(() -> { if (pageEpoch==p && !isDestroyed()) updateCodeButton(button); }, 1000);
    }
    private void renderHome() {
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("M 月 d 日  EEEE", Locale.CHINA));
        header(date, "念念提醒", "小事不漏掉，重要的事慢慢做好。");
        LinearLayout intro = column(); intro.setPadding(dp(20),dp(18),dp(20),dp(18)); intro.setBackground(shape(PALE,20));
        intro.addView(text("今天，有什么需要惦记？",19,INK,true)); gap(intro,6); intro.addView(text("说一句话，就能安排好提醒。",14,MUTED,false)); gap(intro,16);
        Button create=button("＋  新建提醒",true); intro.addView(create); create.setOnClickListener(v -> openEditor(null)); body.addView(intro); gap(body,24);
        LinearLayout titleRow=row(); TextView title=text("我的提醒",18,INK,true); titleRow.addView(title,new LinearLayout.LayoutParams(0,-2,1)); Button refresh=smallButton("刷新"); titleRow.addView(refresh); body.addView(titleRow);
        EditText query = new EditText(this); styleField(query); query.setSingleLine(true); query.setText(search); query.setHint("搜索提醒标题或内容"); query.setTextSize(14); body.addView(query); gap(body,12);
        HorizontalScrollView tabs=new HorizontalScrollView(this); tabs.setHorizontalScrollBarEnabled(false); LinearLayout tabRow=row(); tabs.addView(tabRow);
        for(String name:new String[]{"全部","待完成","已完成","已暂停"}) { Button tab=smallButton(name); tab.setTextColor(filter.equals(name)?PAPER:MUTED); tab.setBackground(shape(filter.equals(name)?BLUE:PALE,24)); LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,dp(44));lp.setMargins(0,0,dp(8),0);tabRow.addView(tab,lp); tab.setOnClickListener(v->{filter=name; render();}); }
        body.addView(tabs);gap(body,16); taskList=column();body.addView(taskList);
        query.addTextChangedListener(watcher(() -> { search=query.getText().toString().trim(); drawTasks(); }));
        refresh.setOnClickListener(v -> loadTasks(refresh));
        drawTasks(); loadTasks(refresh);
    }
    private void loadTasks(Button trigger) {
        if (taskData.length()==0) { taskList.removeAllViews(); taskList.addView(text("正在整理你的提醒…",14,MUTED,false)); }
        run(trigger, a -> a.json("GET","/api/v1/tasks",null), result->{taskData=result.optJSONArray("tasks"); if(taskData==null)taskData=new JSONArray();drawTasks();});
    }
    private void drawTasks() {
        if(taskList==null)return;taskList.removeAllViews();int visible=0;
        for(int i=0;i<taskData.length();i++) {
            JSONObject task=taskData.optJSONObject(i);if(task==null)continue;String status=task.optString("status","pending");
            if(filter.equals("待完成")&&!status.equals("pending"))continue;
            if(filter.equals("已完成")&&!status.equals("done"))continue;
            if(filter.equals("已暂停")&&!status.equals("paused"))continue;
            if(!search.isEmpty()&&!(task.optString("title")+task.optString("description")).toLowerCase(Locale.ROOT).contains(search.toLowerCase(Locale.ROOT)))continue;
            visible++;LinearLayout card=card(taskList);LinearLayout top=row();TextView label=text(taskStatusLabel(task),12,statusColor(status),true);top.addView(label,new LinearLayout.LayoutParams(0,-2,1));
            String next=task.optString("next_reminder");top.addView(text(next.isEmpty()?"":formatTime(next),12,MUTED,false));card.addView(top);gap(card,12);
            TextView title=text(task.optString("title"),20,INK,true);card.addView(title);gap(card,8);card.addView(text(task.optString("summary"),14,MUTED,false));gap(card,18);
            LinearLayout actions=row();Button details=button("查看详情",false);actions.addView(details,new LinearLayout.LayoutParams(0,dp(48),1));details.setOnClickListener(v->{selected=task;navigate("detail");});
            if(status.equals("pending")) { gapHorizontal(actions,10);Button done=button("✓  完成",true);actions.addView(done,new LinearLayout.LayoutParams(0,dp(48),1));done.setOnClickListener(v->{selected=task;openComplete();}); }
            card.addView(actions);
        }
        if(visible==0)empty(taskList,taskData.length()==0?"让第一件小事，不再被忘记":"这里暂时没有提醒",taskData.length()==0?"试试新建「每月 1 到 15 日提醒申报，完成后停止」。":"换个筛选条件，或者新建一条提醒。");
    }
    private void renderDetail() {
        header(taskStatusLabel(selected),selected.optString("title"),selected.optString("summary"));
        if(!selected.optString("description").isEmpty()) { body.addView(text(selected.optString("description"),16,INK,false));gap(body,24); }
        LinearLayout info=card(body);info.addView(text("下一次提醒",13,MUTED,false));gap(info,6);info.addView(text(selected.optString("next_reminder").isEmpty()?"当前没有待发送的提醒":formatFull(selected.optString("next_reminder")),20,INK,true));gap(info,10);
        info.addView(text("完成和邮件记录会一直保留。",13,MUTED,false));
        String status=selected.optString("status");
        if(status.equals("pending")) {
            Button done=button("✓  这件事已完成",true);body.addView(done);done.setOnClickListener(v->openComplete());gap(body,12);
            Button later=button("稍后再提醒我",false);body.addView(later);later.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("延后提醒（不能跨过本期）").setItems(new String[]{"15 分钟","30 分钟","1 小时","3 小时"},(d,w)->{
                int[] mins={15,30,60,180};String id=selected.optString("id");run(later,a->a.json("POST","/api/v1/tasks/"+id+"/snooze",object("minutes",mins[w])),r->{toast("已安排稍后提醒");reloadSelected();});
            }).show());gap(body,24);
        } else {
            JSONObject rule=selected.optJSONObject("rule");String kind=rule==null?"":rule.optString("kind"),mode=rule==null?"":rule.optString("mode");
            String doneCopy=kind.equals("ongoing")||kind.equals("once")||kind.equals("date_range")?"这次安排已经结束，所有记录都会保留。":"本期已经结束，下一期会按规则重新开始。";
            if(mode.equals("notify_once"))doneCopy="本期的提醒邮件已经发出，不需要再点击完成。";
            note(body,status.equals("done")?doneCopy:status.equals("upcoming")?"还没有到本期时间，开始后再记录完成。":status.equals("expired")?"这条提醒已超出日期范围，记录依然保留。":"已暂停发送邮件，可以随时恢复。");
        }
        Button edit=button("编辑提醒",false);body.addView(edit);edit.setOnClickListener(v->openEditor(selected));gap(body,12);
        Button pause=button(selected.optBoolean("paused")?"恢复提醒":"暂停提醒",false);body.addView(pause);pause.setOnClickListener(v->{
            JSONObject payload=object("title",selected.optString("title"),"description",selected.optString("description"),"rule",selected.optJSONObject("rule"),"paused",!selected.optBoolean("paused"));
            String id=selected.optString("id");run(pause,a->a.json("PUT","/api/v1/tasks/"+id,payload),r->{selected=r;render();});
        });gap(body,12);
        Button records=button("查看完成记录",false);body.addView(records);records.setOnClickListener(v->navigate("history"));gap(body,24);
        Button archive=button("归档这条提醒",false);archive.setTextColor(RED);body.addView(archive);archive.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("归档这条提醒？").setMessage("归档后停止发送提醒，已完成的文字、照片和邮件记录会保留。").setNegativeButton("再想想",null).setPositiveButton("归档",(d,w)->{
            String id=selected.optString("id");run(archive,a->a.json("DELETE","/api/v1/tasks/"+id,null),r->{taskData=new JSONArray();toast("已归档，记录已保留");navigate("home");});
        }).show());
    }
    private void reloadSelected() { String id=selected.optString("id");run(null,a->a.json("GET","/api/v1/tasks/"+id,null),r->{selected=r;render();}); }
    private void openEditor(JSONObject task) {
        editing=task!=null;draft=task==null?new JSONObject():copy(task);restoredForm=null;manualVisible=editing;aiText="";aiWarning="";
        screen="editor";render();
    }
    private void renderEditor() {
        header(editing?"调整安排":"新建提醒",editing?"让安排更合适":"想到什么，就说什么",editing?"修改规则不会删除过去的记录。":"先生成草稿，确认时间和停止条件后保存。");
        if(!editing) {
            LinearLayout ai=column();ai.setPadding(dp(18),dp(18),dp(18),dp(18));ai.setBackground(shape(PALE,20));
            field(ai,"ai","用一句话描述",aiText,"每月 1 到 15 日，每天 9 点提醒我申报财务，完成一次就停止本月提醒",4);
            Button generate=button("✦  帮我安排",true);ai.addView(generate);generate.setOnClickListener(v->{
                String sentence=value("ai");if(sentence.length()<2){showError("先写下想提醒的事情和时间");return;}aiText=sentence;
                run(generate,a->a.json("POST","/api/v1/ai/parse",object("text",sentence)),result->{draft=result;restoredForm=null;manualVisible=true;JSONArray warnings=result.optJSONArray("warnings");aiWarning=warnings==null?"":join(warnings,"\n");render();});
            });body.addView(ai);gap(body,12);
            LinearLayout samples=row();Button tax=smallButton("每月申报");Button laundry=smallButton("洗衣提醒");samples.addView(tax,new LinearLayout.LayoutParams(0,dp(44),1));gapHorizontal(samples,8);samples.addView(laundry,new LinearLayout.LayoutParams(0,dp(44),1));body.addView(samples);
            tax.setOnClickListener(v->fields.get("ai").setText("每月 1 到 15 日每天早上 9 点提醒我申报公司财务，完成一次就停止本月提醒"));
            laundry.setOnClickListener(v->fields.get("ai").setText("今晚 8 点提醒我洗衣服，9 点再提醒一次；如果已经完成就不再提醒"));
            gap(body,12);Button manual=button(manualVisible?"下面也可以手动调整":"直接手动创建",false);body.addView(manual);manual.setOnClickListener(v->{captureTransient();manualVisible=true;render();});gap(body,24);
        }
        if(!manualVisible)return;
        if(!aiWarning.isEmpty())note(body,aiWarning);
        JSONObject rule=draft.optJSONObject("rule");if(rule==null)rule=new JSONObject();
        section(body,editing?"提醒内容":"检查一下提醒内容");
        field(body,"title","事情名称",formValue("title",draft.optString("title")),"例如：申报公司财务",1);
        field(body,"description","补充说明（可不填）",formValue("description",draft.optString("description")),"写下要准备的材料或注意事项",3);
        section(body,"什么时候提醒");
        label(body,"重复方式");kindPicker=spinner(KIND_LABELS);body.addView(kindPicker);kindPicker.setSelection(restoredForm!=null?restoredForm.optInt("kind_index",indexOf(KINDS,rule.optString("kind","monthly_window"))):indexOf(KINDS,rule.optString("kind","monthly_window")));gap(body,16);
        LinearLayout monthly=column();field(monthly,"month_start","每月开始日",formValue("month_start",String.valueOf(rule.optInt("month_start",1))),"1",1);number(fields.get("month_start"));field(monthly,"month_end","每月结束日",formValue("month_end",String.valueOf(rule.optInt("month_end",15))),"15",1);number(fields.get("month_end"));note(monthly,"当月没有 29、30 或 31 日时，按当月最后一天计算。");body.addView(monthly);
        LinearLayout weekly=column();label(weekly,"每周哪几天");weekdays=new CheckBox[7];JSONArray chosen=restoredForm==null?rule.optJSONArray("weekdays"):restoredForm.optJSONArray("weekdays");
        LinearLayout weekRow=row();String[] names={"周一","周二","周三","周四","周五","周六","周日"};weekly.addView(weekRow);
        for(int i=0;i<7;i++){if(i==4){weekRow=row();weekly.addView(weekRow);}CheckBox box=new CheckBox(this);box.setText(names[i]);box.setTextColor(INK);box.setTextSize(14);box.setButtonTintList(ColorStateList.valueOf(BLUE));box.setPadding(0,0,0,0);box.setMinHeight(dp(48));weekdays[i]=box;box.setChecked(contains(chosen,i+1));weekRow.addView(box,new LinearLayout.LayoutParams(0,-2,1));}body.addView(weekly);gap(weekly,12);
        LinearLayout interval=column();field(interval,"every_days","每隔多少天",formValue("every_days",String.valueOf(rule.optInt("every_days",1))),"1 至 365",1);number(fields.get("every_days"));body.addView(interval);
        field(body,"start_date","开始日期（可不填）",formValue("start_date",rule.optString("start_date")),"点击选择日期",1);dateField(fields.get("start_date"));
        field(body,"end_date","结束日期（可不填）",formValue("end_date",rule.optString("end_date")),"不填则持续提醒",1);dateField(fields.get("end_date"));
        note(body,"一次提醒、间隔提醒、做完结束都需要开始日期；日期范围需要开始与结束。长按日期可以清除。");
        JSONArray times=rule.optJSONArray("times");field(body,"times","每天的提醒时间",formValue("times",times==null?"09:00":join(times,", ")),"09:00, 20:00",1);
        Button addTime=smallButton("＋  选择一个时间");body.addView(addTime);addTime.setOnClickListener(v->new TimePickerDialog(this,(picker,h,m)->{String t=String.format(Locale.ROOT,"%02d:%02d",h,m);String current=value("times");fields.get("times").setText(current.isEmpty()?t:current+", "+t);},9,0,true).show());gap(body,12);note(body,"24 小时制，多个时间用逗号分开，每天最多 8 次。");
        section(body,"什么时候停止");label(body,"完成与提醒方式");modePicker=spinner(MODE_LABELS);body.addView(modePicker);modePicker.setSelection(restoredForm!=null?restoredForm.optInt("mode_index",indexOf(MODES,rule.optString("mode","until_done"))):indexOf(MODES,rule.optString("mode","until_done")));gap(body,12);
        TextView modeHelp=text("",13,MUTED,false);body.addView(modeHelp);gap(body,16);
        field(body,"max_reminders","每期最多提醒几次",formValue("max_reminders",String.valueOf(rule.optInt("max_reminders",0))),"0 表示不限次数",1);number(fields.get("max_reminders"));
        field(body,"timezone","时区",formValue("timezone",rule.optString("timezone","Asia/Shanghai")),"Asia/Shanghai",1);
        note(body,"申报：选「每月一段时间」和「本期完成一次就停止」。每天喝药：选「每天分别完成」。洗衣只提醒两次：填写两个时间，并把最多次数设为 2。");
        Button save=button(editing?"保存修改":"保存这条提醒",true);body.addView(save);save.setOnClickListener(v->{
            JSONObject payload;try{payload=buildTask();}catch(Exception e){showError(e.getMessage());return;}
            String id=draft.optString("id");final boolean isEditing=editing;run(save,a->a.json(isEditing?"PUT":"POST",isEditing?"/api/v1/tasks/"+id:"/api/v1/tasks",payload),result->{selected=result;taskData=new JSONArray();restoredForm=null;toast(isEditing?"修改已保存":"提醒已安排");screen="detail";render();});
        });
        Runnable updateKind=()->{String kind=KINDS[kindPicker.getSelectedItemPosition()];monthly.setVisibility(kind.equals("monthly_window")?View.VISIBLE:View.GONE);weekly.setVisibility(kind.equals("weekly")?View.VISIBLE:View.GONE);interval.setVisibility(kind.equals("interval")?View.VISIBLE:View.GONE);modePicker.setEnabled(!kind.equals("ongoing"));if(kind.equals("ongoing"))modePicker.setSelection(0);updateModeHelp(modeHelp);};
        kindPicker.setOnItemSelectedListener(selection(updateKind));updateKind.run();
        Runnable updateMode=()->updateModeHelp(modeHelp);modePicker.setOnItemSelectedListener(selection(updateMode));updateMode.run();
    }
    private void updateModeHelp(TextView view){String[] help={"在本期内完成一次，就停止本期的后续提醒；下期重新开始。每月按月，每周按周，日期范围按整个范围算一期。","每天都需要分别完成。今天完成后，明天仍会照常提醒。","每期只发出第一封提醒邮件，不用点击完成。"};view.setText(KINDS[kindPicker.getSelectedItemPosition()].equals("ongoing")?"每天提醒，直到你完成这件事；完成后永久结束，不会在明天重新开始。":help[modePicker.getSelectedItemPosition()]);}
    private JSONObject buildTask() throws Exception {
        String title=value("title"),kind=KINDS[kindPicker.getSelectedItemPosition()],mode=MODES[modePicker.getSelectedItemPosition()];
        if(title.isEmpty())throw new IOException("请填写事情名称");if(title.codePointCount(0,title.length())>100)throw new IOException("事情名称请控制在 100 字以内");
        String start=value("start_date"),end=value("end_date");LocalDate s=null,e=null;
        try{if(!start.isEmpty())s=LocalDate.parse(start);if(!end.isEmpty())e=LocalDate.parse(end);}catch(Exception x){throw new IOException("日期请使用 YYYY-MM-DD 格式");}
        if(Arrays.asList("once","date_range","interval","ongoing").contains(kind)&&s==null)throw new IOException("这种提醒需要填写开始日期");
        if(kind.equals("ongoing"))mode="until_done";
        if(kind.equals("date_range")&&e==null)throw new IOException("日期范围需要填写结束日期");if(s!=null&&e!=null&&e.isBefore(s))throw new IOException("结束日期不能早于开始日期");
        String zone=value("timezone");try{ZoneId.of(zone);}catch(Exception x){throw new IOException("时区无效，例如 Asia/Shanghai");}
        TreeSet<String> times=new TreeSet<>();for(String t:value("times").replace('，',',').split("[,\\s]+")){if(!t.matches("(?:[01]\\d|2[0-3]):[0-5]\\d"))throw new IOException("时间请按 09:00, 20:00 这样的格式填写");times.add(t);}
        if(times.isEmpty()||times.size()>8)throw new IOException("每天请填写 1 至 8 个提醒时间");
        int cap=parseNumber("max_reminders",0,100),from=1,to=15,every=1;
        if(kind.equals("monthly_window")){from=parseNumber("month_start",1,31);to=parseNumber("month_end",1,31);if(from>to)throw new IOException("每月开始日不能大于结束日");}
        if(kind.equals("interval"))every=parseNumber("every_days",1,365);
        JSONArray days=new JSONArray();if(kind.equals("weekly")){for(int i=0;i<7;i++)if(weekdays[i].isChecked())days.put(i+1);if(days.length()==0)throw new IOException("请选择至少一个星期");}
        JSONObject rule=object("kind",kind,"mode",mode,"timezone",zone,"times",new JSONArray(times),"start_date",start,"end_date",end,"weekdays",days,"month_start",from,"month_end",to,"every_days",every,"max_reminders",cap);
        JSONObject task=object("title",title,"description",value("description"),"rule",rule);if(editing)task.put("paused",draft.optBoolean("paused"));return task;
    }
    private int parseNumber(String name,int min,int max)throws IOException{try{int x=Integer.parseInt(value(name));if(x<min||x>max)throw new Exception();return x;}catch(Exception e){throw new IOException("请填写 "+min+" 至 "+max+" 之间的整数");}}
    private void openComplete(){photoFile=null;cameraFile=null;completionText="";navigate("complete");}
    private void renderComplete(){
        header("完成一件事",selected.optString("title"),"直接完成也很好。想留个凭证，可以写几句或拍张照。");
        field(body,"completion","完成备注（可不填）",completionText,"例如：今天已经提交申报，回执见照片。",4);
        LinearLayout photoRow=row();Button camera=button("拍照留档",false),gallery=button("选择照片",false);photoRow.addView(camera,new LinearLayout.LayoutParams(0,dp(52),1));gapHorizontal(photoRow,10);photoRow.addView(gallery,new LinearLayout.LayoutParams(0,dp(52),1));body.addView(photoRow);gap(body,16);
        camera.setOnClickListener(v->{captureTransient();takePhoto();});gallery.setOnClickListener(v->{captureTransient();Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("image/*");i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"image/jpeg","image/png","image/webp"});try{startActivityForResult(i,102);}catch(Exception e){showError("没有可用的相册应用");}});
        if(photoFile!=null&&photoFile.isFile()){
            ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setBackground(shape(PALE,16));body.addView(image,new LinearLayout.LayoutParams(-1,dp(220)));loadLocalImage(image,photoFile);gap(body,8);
            Button remove=smallButton("移除照片");body.addView(remove);remove.setOnClickListener(v->{captureTransient();photoFile=null;render();});gap(body,16);
        }
        note(body,"完成后会保存时间、文字和照片，并按这条提醒的规则停止本期或当天的后续邮件。");
        Button save=button("✓  保存并完成",true);body.addView(save);save.setOnClickListener(v->{
            if(taskBusy)return;taskBusy=true;String text=value("completion"),id=selected.optString("id");File photo=photoFile;
            run(save,a->{JSONArray ids=new JSONArray();if(photo!=null)ids.put(a.upload(photo).getString("id"));return a.json("POST","/api/v1/tasks/"+id+"/complete",object("text",text,"media_ids",ids));},result->{taskBusy=false;photoFile=null;completionText="";taskData=new JSONArray();toast("已完成，又少了一件惦记的事");navigate("home");});
        });
    }
    private void takePhoto(){
        try{
            File dir=new File(getCacheDir(),"photos");if(!dir.exists()&&!dir.mkdirs())throw new IOException();cameraFile=new File(dir,"camera-"+System.currentTimeMillis()+".jpg");
            Uri uri=FileProvider.getUriForFile(this,getPackageName()+".files",cameraFile);Intent i=new Intent(MediaStore.ACTION_IMAGE_CAPTURE);i.putExtra(MediaStore.EXTRA_OUTPUT,uri);i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_READ_URI_PERMISSION);i.setClipData(ClipData.newRawUri("照片",uri));startActivityForResult(i,101);
        }catch(Exception e){showError("无法打开相机，可以改为从相册选择照片");}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(result!=RESULT_OK)return;
        Uri source=request==101&&cameraFile!=null?Uri.fromFile(cameraFile):request==102&&data!=null?data.getData():null;if(source==null)return;
        taskBusy=true;toast("正在处理照片，请稍等");run(null,a->preparePhoto(source),file->{taskBusy=false;photoFile=file;render();});
    }
    private File preparePhoto(Uri source)throws Exception{
        Bitmap bitmap;
        if(Build.VERSION.SDK_INT>=28){ImageDecoder.Source input=ImageDecoder.createSource(getContentResolver(),source);bitmap=ImageDecoder.decodeBitmap(input,(decoder,info,src)->{decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);int w=info.getSize().getWidth(),h=info.getSize().getHeight();float scale=Math.min(1f,2048f/Math.max(w,h));decoder.setTargetSize(Math.max(1,(int)(w*scale)),Math.max(1,(int)(h*scale)));});}
        else{BitmapFactory.Options opt=new BitmapFactory.Options();opt.inJustDecodeBounds=true;try(InputStream in=getContentResolver().openInputStream(source)){BitmapFactory.decodeStream(in,null,opt);}opt.inSampleSize=1;while(Math.max(opt.outWidth,opt.outHeight)/opt.inSampleSize>2048)opt.inSampleSize*=2;opt.inJustDecodeBounds=false;try(InputStream in=getContentResolver().openInputStream(source)){bitmap=BitmapFactory.decodeStream(in,null,opt);}if(bitmap!=null){int orientation=1;try(InputStream in=getContentResolver().openInputStream(source)){orientation=new android.media.ExifInterface(in).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,1);}catch(IOException ignored){}Matrix matrix=new Matrix();switch(orientation){case 2:matrix.setScale(-1,1);break;case 3:matrix.setRotate(180);break;case 4:matrix.setRotate(180);matrix.postScale(-1,1);break;case 5:matrix.setRotate(90);matrix.postScale(-1,1);break;case 6:matrix.setRotate(90);break;case 7:matrix.setRotate(-90);matrix.postScale(-1,1);break;case 8:matrix.setRotate(-90);break;}if(!matrix.isIdentity()){Bitmap rotated=Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),matrix,true);if(rotated!=bitmap)bitmap.recycle();bitmap=rotated;}}}
        if(bitmap==null)throw new IOException("照片无法读取，请选择 JPEG、PNG 或 WebP 图片");
        File dir=new File(getCacheDir(),"photos");if(!dir.exists()&&!dir.mkdirs())throw new IOException("无法保存照片");File out=new File(dir,"evidence-"+System.currentTimeMillis()+".jpg");
        try(OutputStream stream=new FileOutputStream(out)){if(!bitmap.compress(Bitmap.CompressFormat.JPEG,88,stream))throw new IOException("照片处理失败");}finally{bitmap.recycle();}
        if(out.length()>10*1024*1024)throw new IOException("照片太大，请换一张小一点的图片");return out;
    }
    private void renderHistory(boolean logs){
        header(logs?"邮件送达情况":"每一步，都有记录",logs?"提醒邮件":"完成记录",logs?"发送失败的邮件和原因也会保留。":"文字和照片，记下那些已经做好的事。");
        if(!logs){Button logButton=button("查看提醒邮件记录",false);body.addView(logButton);logButton.setOnClickListener(v->navigate("logs"));gap(body,20);}
        Button refresh=smallButton("刷新记录");body.addView(refresh);gap(body,16);LinearLayout list=column();body.addView(list);list.addView(text("正在读取记录…",14,MUTED,false));
        Runnable load=()->run(refresh,a->a.json("GET",logs?"/api/v1/notifications":"/api/v1/history",null),result->{
            JSONArray records=result.optJSONArray(logs?"notifications":"records");drawHistoryPage(list,records,logs,0);
        });refresh.setOnClickListener(v->load.run());load.run();
    }
    private void drawHistoryPage(LinearLayout list,JSONArray records,boolean logs,int page){
            list.removeAllViews();if(records==null||records.length()==0){empty(list,logs?"邮件还在路上":"从完成第一件事开始",logs?"到提醒时间后，发送结果会显示在这里。":"每次点击完成，你的记录都会留在这里。");return;}
            int pageCount=(records.length()+19)/20;note(list,"共 "+records.length()+" 条记录 · 第 "+(page+1)+" / "+pageCount+" 页");
            for(int i=page*20;i<Math.min(records.length(),(page+1)*20);i++){JSONObject item=records.optJSONObject(i);if(item==null)continue;LinearLayout card=card(list);card.addView(text(item.optString("task_title","已归档的提醒"),18,INK,true));gap(card,8);
                if(logs){String state=item.optString("status");card.addView(text(state.equals("sent")?"已发送":state.equals("failed")?"发送失败":"等待发送",14,state.equals("failed")?RED:state.equals("sent")?GREEN:MUTED,true));gap(card,6);card.addView(text("计划时间  "+formatFull(item.optString("scheduled_at")),13,MUTED,false));if(!item.optString("sent_at").isEmpty())card.addView(text("发送时间  "+formatFull(item.optString("sent_at")),13,MUTED,false));if(!item.optString("error").isEmpty()){gap(card,8);card.addView(text(item.optString("error"),13,RED,false));}}
                else{card.addView(text(formatFull(item.optString("created_at")),13,MUTED,false));if(!item.optString("text").isEmpty()){gap(card,12);card.addView(text(item.optString("text"),15,INK,false));}JSONArray images=item.optJSONArray("media_ids");if(images!=null)for(int n=0;n<images.length();n++){String id=images.optString(n);gap(card,12);ImageView photo=new ImageView(this);photo.setContentDescription("完成凭证照片，点击查看大图");photo.setScaleType(ImageView.ScaleType.CENTER_CROP);photo.setBackground(shape(PALE,14));card.addView(photo,new LinearLayout.LayoutParams(-1,dp(200)));loadImage(photo,id);photo.setOnClickListener(v->{selected=object("media_id",id,"title",item.optString("task_title"));navigate("photo");});}}
            }
            if(pageCount>1){LinearLayout paging=row();Button previous=button("上一页",false),next=button("下一页",false);previous.setEnabled(page>0);next.setEnabled(page+1<pageCount);paging.addView(previous,new LinearLayout.LayoutParams(0,dp(50),1));gapHorizontal(paging,10);paging.addView(next,new LinearLayout.LayoutParams(0,dp(50),1));list.addView(paging);previous.setOnClickListener(v->{drawHistoryPage(list,records,logs,page-1);body.getParent().requestChildFocus(body,list);});next.setOnClickListener(v->{drawHistoryPage(list,records,logs,page+1);body.getParent().requestChildFocus(body,list);});}
    }
    private void renderPhoto(){header("完成凭证",selected.optString("title"),"照片通过当前账号读取。");ImageView photo=new ImageView(this);photo.setAdjustViewBounds(true);photo.setScaleType(ImageView.ScaleType.FIT_CENTER);photo.setMinimumHeight(dp(300));body.addView(photo,new LinearLayout.LayoutParams(-1,-2));loadImage(photo,selected.optString("media_id"));}
    private void renderSettings(){
        header("给生活留一点余地","我的","念念负责提醒，你负责把日子过好。");
        LinearLayout account=card(body);account.addView(text("接收提醒的邮箱",13,MUTED,false));gap(account,8);TextView email=text(session.email,19,INK,true);email.setTextIsSelectable(true);account.addView(email);gap(account,12);account.addView(text("登录状态已安全保存在这台手机上。",13,MUTED,false));
        section(body,"连接与数据");label(body,"当前服务器");TextView address=text(session.base,15,INK,false);address.setTextIsSelectable(true);body.addView(address);gap(body,12);
        Button change=button("更换服务器地址",false);body.addView(change);change.setOnClickListener(v->navigate("server"));gap(body,12);
        Button check=button("检查连接和登录状态",false);body.addView(check);check.setOnClickListener(v->run(check,a->{a.json("GET","/health",null);return a.json("GET","/api/v1/me",null);},r->toast("连接正常，当前登录："+r.optString("email"))));gap(body,12);
        Button export=button("导出我的全部文字记录",false);body.addView(export);export.setOnClickListener(v->run(export,a->{JSONObject data=a.json("GET","/api/v1/export",null);File dir=new File(getCacheDir(),"exports");if(!dir.exists()&&!dir.mkdirs())throw new IOException("无法创建导出文件");File file=new File(dir,"念念记录-"+LocalDate.now()+"-"+UUID.randomUUID()+".json");try(OutputStream out=new FileOutputStream(file)){out.write(data.toString(2).getBytes(StandardCharsets.UTF_8));}return file;},file->{Uri uri=FileProvider.getUriForFile(this,getPackageName()+".files",file);Intent i=new Intent(Intent.ACTION_SEND);i.setType("application/json");i.putExtra(Intent.EXTRA_STREAM,uri);i.setClipData(ClipData.newRawUri("念念记录",uri));i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(Intent.createChooser(i,"保存或分享记录"));}));gap(body,12);
        note(body,"导出包含提醒规则、完成文字和邮件记录。照片原文件保存在服务器的数据目录，完整备份时请一起保存该目录。");
        section(body,"关于念念");body.addView(text("念念提醒 1.0.0",16,INK,true));gap(body,8);note(body,"邮件由服务器按任务时区发送，无需在手机后台常驻。AI 只生成可编辑的草稿，保存之后才会开始提醒。");
        Button logout=button("退出登录",false);logout.setTextColor(RED);body.addView(logout);logout.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("退出当前账号？").setMessage("服务器上的提醒仍会照常发送，任务和记录不会删除。").setNegativeButton("取消",null).setPositiveButton("退出",(d,w)->{
            Api old=new Api(session.base,session.token);accountEpoch++;session.clear();clearAccountData();navigate("login");workers.submit(()->{try{old.json("POST","/api/v1/auth/logout",object());}catch(Exception ignored){}});
        }).show());
    }
    private void clearAccountData(){taskData=new JSONArray();selected=new JSONObject();draft=new JSONObject();restoredForm=null;photoFile=null;cameraFile=null;search="";filter="全部";aiText="";completionText="";taskList=null;}
    private interface Job<T>{T run(Api api)throws Exception;}
    private interface Result<T>{void accept(T value);}
    private <T> void run(Button trigger,Job<T> job,Result<T> success){
        int account=accountEpoch,page=pageEpoch;Api api=new Api(session.base,session.token);CharSequence caption=trigger==null?"":trigger.getText();if(trigger!=null){trigger.setEnabled(false);trigger.setText("请稍等…");}errorView.setVisibility(View.GONE);
        workers.submit(()->{try{T result=job.run(api);ui.post(()->{if(isDestroyed()||account!=accountEpoch||page!=pageEpoch)return;if(trigger!=null){trigger.setEnabled(true);trigger.setText(caption);}success.accept(result);});}
            catch(Exception e){ui.post(()->{if(isDestroyed()||account!=accountEpoch||page!=pageEpoch)return;taskBusy=false;if(trigger!=null){trigger.setEnabled(true);trigger.setText(caption);}if(e instanceof Api.Failure&&((Api.Failure)e).status==401&&!session.token.isEmpty()){accountEpoch++;session.clear();clearAccountData();screen="login";render();showError("登录已过期，请重新获取验证码登录");}else showError(e.getMessage()==null?"操作未完成，请重试":e.getMessage());});}
        });
    }
    private void loadImage(ImageView target,String id){
        int a=accountEpoch,p=pageEpoch,maxSize=screen.equals("photo")?1600:640;Api api=new Api(session.base,session.token);workers.submit(()->{try{byte[] bytes=api.media(id);BitmapFactory.Options opt=new BitmapFactory.Options();opt.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,opt);opt.inSampleSize=1;while(Math.max(opt.outWidth,opt.outHeight)/opt.inSampleSize>maxSize)opt.inSampleSize*=2;opt.inJustDecodeBounds=false;Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length,opt);ui.post(()->{if(!isDestroyed()&&accountEpoch==a&&pageEpoch==p&&target.isAttachedToWindow()){target.setImageBitmap(bitmap);}else if(bitmap!=null)bitmap.recycle();});}catch(Exception e){ui.post(()->{if(!isDestroyed()&&accountEpoch==a&&pageEpoch==p){target.setContentDescription("照片加载失败，点击重试");target.setImageResource(android.R.drawable.ic_menu_report_image);target.setOnClickListener(v->loadImage(target,id));}});}});
    }
    private void loadLocalImage(ImageView target,File file){int p=pageEpoch;workers.submit(()->{Bitmap bitmap=BitmapFactory.decodeFile(file.getAbsolutePath());ui.post(()->{if(!isDestroyed()&&pageEpoch==p)target.setImageBitmap(bitmap);else if(bitmap!=null)bitmap.recycle();});});}
    private void bottomNav(){
        LinearLayout bar=row();bar.setPadding(dp(12),dp(8),dp(12),dp(10));bar.setBackgroundColor(PAPER);String[] pages={"home","history","settings"},labels={"提醒","记录","我的"},symbols={"◷","▤","○"};
        for(int i=0;i<3;i++){final String page=pages[i];TextView nav=text(symbols[i]+"\n"+labels[i],13,screen.equals(page)?BLUE:MUTED,screen.equals(page));nav.setGravity(Gravity.CENTER);nav.setMinHeight(dp(56));nav.setBackground(shape(screen.equals(page)?PALE:PAPER,16));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(60),1);lp.setMargins(dp(4),0,dp(4),0);bar.addView(nav,lp);nav.setOnClickListener(v->{if(!screen.equals(page))navigate(page);});}root.addView(bar);
    }
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private LinearLayout card(LinearLayout parent){LinearLayout c=column();c.setPadding(dp(20),dp(20),dp(20),dp(20));c.setBackground(shape(PAPER,20));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,0,0,dp(14));parent.addView(c,lp);return c;}
    private TextView text(String value,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setLineSpacing(dp(3),1f);if(bold)t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));return t;}
    private Button button(String caption,boolean primary){Button b=new Button(this);b.setAllCaps(false);b.setText(caption);b.setTextSize(15);b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));b.setTextColor(primary?PAPER:BLUE);b.setMinHeight(dp(52));b.setPadding(dp(14),dp(10),dp(14),dp(10));b.setElevation(0);b.setStateListAnimator(null);b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x224c74d8),shape(primary?BLUE:PALE,16),null));b.setLayoutParams(new LinearLayout.LayoutParams(-1,-2));return b;}
    private Button smallButton(String caption){Button b=button(caption,false);b.setTextSize(13);b.setMinHeight(dp(44));b.setMinWidth(dp(64));b.setPadding(dp(14),dp(6),dp(14),dp(6));b.setLayoutParams(new LinearLayout.LayoutParams(-2,dp(44)));return b;}
    private GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private EditText field(LinearLayout parent,String key,String caption,String content,String hint,int lines){label(parent,caption);EditText e=new EditText(this);styleField(e);e.setHint(hint);e.setText(content);e.setGravity(Gravity.TOP|Gravity.START);e.setMinLines(lines);e.setMaxLines(lines==1?1:7);e.setSingleLine(lines==1);e.setInputType(android.text.InputType.TYPE_CLASS_TEXT|(lines>1?android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE:0));fields.put(key,e);parent.addView(e,new LinearLayout.LayoutParams(-1,-2));gap(parent,16);return e;}
    private void styleField(EditText e){GradientDrawable bg=shape(PAPER,14);bg.setStroke(dp(1),LINE);e.setBackground(bg);e.setTextColor(INK);e.setHintTextColor(0xff8a99ae);e.setTextSize(16);e.setPadding(dp(15),dp(14),dp(15),dp(14));e.setMinHeight(dp(52));e.setSelectAllOnFocus(false);}
    private void label(LinearLayout parent,String title){parent.addView(text(title,13,MUTED,true));gap(parent,8);}
    private void section(LinearLayout parent,String title){gap(parent,10);parent.addView(text(title,19,INK,true));gap(parent,18);}
    private void note(LinearLayout parent,String message){parent.addView(text(message,13,MUTED,false));gap(parent,16);}
    private void gap(LinearLayout parent,int height){Space s=new Space(this);parent.addView(s,new LinearLayout.LayoutParams(1,dp(height)));}
    private void gapHorizontal(LinearLayout parent,int width){Space s=new Space(this);parent.addView(s,new LinearLayout.LayoutParams(dp(width),1));}
    private void empty(LinearLayout parent,String title,String description){LinearLayout box=column();box.setPadding(dp(12),dp(40),dp(12),dp(30));TextView mark=text("✓",36,BLUE,true);mark.setGravity(Gravity.CENTER);box.addView(mark);gap(box,16);TextView t=text(title,18,INK,true);t.setGravity(Gravity.CENTER);box.addView(t);gap(box,8);TextView hint=text(description,14,MUTED,false);hint.setGravity(Gravity.CENTER);box.addView(hint);parent.addView(box);}
    private Spinner spinner(String[] labels){Spinner s=new Spinner(this);ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,labels);adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);s.setAdapter(adapter);s.setMinimumHeight(dp(52));s.setBackground(shape(PAPER,14));s.setPadding(dp(12),0,dp(12),0);return s;}
    private AdapterView.OnItemSelectedListener selection(Runnable action){return new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int i,long id){action.run();}public void onNothingSelected(AdapterView<?> p){}};}
    private TextWatcher watcher(Runnable action){return new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){action.run();}public void afterTextChanged(Editable e){}};}
    private void dateField(EditText e){e.setFocusable(false);e.setOnClickListener(v->{LocalDate date;try{date=LocalDate.parse(e.getText());}catch(Exception x){date=LocalDate.now();}new DatePickerDialog(this,(picker,y,m,d)->e.setText(LocalDate.of(y,m+1,d).toString()),date.getYear(),date.getMonthValue()-1,date.getDayOfMonth()).show();});e.setOnLongClickListener(v->{e.setText("");toast("日期已清除");return true;});}
    private void number(EditText e){e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);}
    private String value(String key){EditText field=fields.get(key);return field==null?"":field.getText().toString().trim();}
    private String formValue(String key,String fallback){return restoredForm==null?fallback:restoredForm.optString(key,fallback);}
    private int dp(int x){return Math.round(x*getResources().getDisplayMetrics().density);}
    private void showError(String message){errorView.setText(message);errorView.setVisibility(View.VISIBLE);errorView.announceForAccessibility(message);toast(message);}
    private void toast(String message){Toast.makeText(this,message,Toast.LENGTH_LONG).show();}
    private static JSONObject object(Object... values){JSONObject o=new JSONObject();try{for(int i=0;i<values.length;i+=2)o.put((String)values[i],values[i+1]);}catch(JSONException e){throw new IllegalArgumentException(e);}return o;}
    private static JSONObject copy(JSONObject o){try{return new JSONObject(o.toString());}catch(Exception e){return new JSONObject();}}
    private static int indexOf(String[] values,String value){for(int i=0;i<values.length;i++)if(values[i].equals(value))return i;return 0;}
    private static boolean contains(JSONArray array,int value){if(array!=null)for(int i=0;i<array.length();i++)if(array.optInt(i)==value)return true;return false;}
    private static String join(JSONArray array,String separator){StringBuilder b=new StringBuilder();for(int i=0;i<array.length();i++){if(i>0)b.append(separator);b.append(array.optString(i));}return b.toString();}
    private String statusLabel(String status){switch(status){case "done":return "本期已完成";case "upcoming":return "尚未开始";case "paused":return "已暂停";case "expired":return "已结束";default:return "待完成";}}
    private String taskStatusLabel(JSONObject task){JSONObject rule=task.optJSONObject("rule");return task.optString("status").equals("done")&&rule!=null&&rule.optString("mode").equals("notify_once")?"本期已提醒":statusLabel(task.optString("status"));}
    private int statusColor(String status){return status.equals("done")?GREEN:status.equals("pending")?BLUE:MUTED;}
    private String formatFull(String raw){try{return OffsetDateTime.parse(raw).atZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy/M/d HH:mm",Locale.CHINA));}catch(Exception e){return raw;}}
    private String formatTime(String raw){try{ZonedDateTime t=OffsetDateTime.parse(raw).atZoneSameInstant(ZoneId.systemDefault());return (t.toLocalDate().equals(LocalDate.now())?"今天 ":t.format(DateTimeFormatter.ofPattern("M/d ")))+t.format(DateTimeFormatter.ofPattern("HH:mm"));}catch(Exception e){return raw;}}
}
