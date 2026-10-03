package dev.niannian.reminder;

import android.app.*;
import android.animation.ValueAnimator;
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
    private static final int BLUE = 0xff527a58, INK = 0xff283c32, MUTED = 0xff68786d,
            BG = 0xfff7f8f2, PAPER = 0xfffffdf7, PALE = 0xffe8efe0, LINE = 0xffdfe5d7,
            RED = 0xffb65b55, GREEN = 0xff3d7c58, YELLOW = 0xff946b25, INFO = 0xff527eab;
    private static final String[] KINDS = {"once", "daily", "weekly", "monthly_window", "date_range", "interval", "ongoing", "minute_interval", "unsupported"};
    private static final String[] KIND_LABELS = {"只在一天提醒", "每天", "每周指定星期", "每月一段时间", "指定日期范围", "每隔几天", "每天提醒，做完结束", "每隔几分钟催一次", "请选择重复方式"};
    private static final String[] MODES = {"until_done", "every_day", "notify_once"};
    private static final String[] MODE_LABELS = {"本期完成一次就停止", "每天分别完成", "本期只通知一次"};
    private final ExecutorService workers = Executors.newFixedThreadPool(3);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Session session;
    private int accountEpoch = 0, pageEpoch = 0, taskLoadRequest = 0;
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
    private boolean rulesExpanded = false, advancedExpanded = false, aiExpanded = true;
    private JSONObject selectedRecord = new JSONObject();
    private JSONArray calendarData = new JSONArray();
    private String calendarLoadedKey = "", calendarNotice = "";
    private boolean calendarLoading = false, calendarTruncated = false;
    private final CalendarRequestGuard calendarRequests = new CalendarRequestGuard();
    private final Set<String> pauseInFlight = new HashSet<>();
    private LocalDate calendarDate = LocalDate.now();
    private YearMonth calendarMonth = YearMonth.now();
    private int recordPage = 0, plannedTaskRequest = 0;
    private String detailBack = "home", photoBack = "record", photoId = "", photoTitle = "";
    private LinearLayout calendarGrid, calendarRecords;
    private TextView calendarCaption;
    private Button calendarRefresh;
    private JSONObject gardenData;
    private LinearLayout gardenContent, gardenPreview;
    private int gardenRequest = 0, gardenPage = 0, gardenTaskRequest = 0;
    private String gardenNotice = "", recordBack = "history";
    private String gardenSelectedPlantId = "";
    private boolean gardenLoading = false, gardenMemoriesExpanded = false;
    private JSONObject completedRecord = new JSONObject();
    private final Map<Integer, Bitmap> artCache = new HashMap<>();
    private final GrowthAnimationState growthAnimationState = new GrowthAnimationState();
    private GardenGrowthView growthView;
    private GardenPlotView gardenScene;
    private boolean motionHostActive;
    private long codeAvailableAt;
    private String authMode = "login", authEmail = "", homeNotice = "";
    private boolean authBusy, automaticLoginAttempted, credentialWritePending;
    private int authRequest;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        session = new Session(this);
        if (state != null) {
            screen = state.getString("screen", "home"); filter = state.getString("filter", "全部");
            try { selected = new JSONObject(state.getString("selected", "{}")); draft = new JSONObject(state.getString("draft", "{}")); restoredForm = new JSONObject(state.getString("form", "{}")); } catch (Exception ignored) {}
            editing = state.getBoolean("editing"); manualVisible = state.getBoolean("manual");
            rulesExpanded=state.getBoolean("rulesExpanded"); advancedExpanded=state.getBoolean("advancedExpanded"); aiExpanded=state.getBoolean("aiExpanded",true);
            detailBack=state.getString("detailBack","home"); photoBack=state.getString("photoBack","record"); photoId=state.getString("photoId",""); photoTitle=state.getString("photoTitle",""); recordPage=state.getInt("recordPage");
            recordBack=state.getString("recordBack","history"); gardenPage=state.getInt("gardenPage");
            gardenSelectedPlantId=state.getString("gardenSelectedPlantId","");gardenMemoriesExpanded=state.getBoolean("gardenMemoriesExpanded");
            try { completedRecord=new JSONObject(state.getString("completedRecord","{}")); } catch(Exception ignored) {}
            growthAnimationState.restore(state.getString("consumedGrowthId", ""));
            try { calendarDate=LocalDate.parse(state.getString("calendarDate",LocalDate.now().toString())); calendarMonth=YearMonth.parse(state.getString("calendarMonth",YearMonth.now().toString())); selectedRecord=new JSONObject(state.getString("selectedRecord","{}")); } catch(Exception ignored) {}
            aiText = state.getString("aiText", ""); aiWarning = state.getString("aiWarning", ""); completionText = state.getString("completionText", "");
            String p = state.getString("photo", ""), c = state.getString("camera", "");
            if (!p.isEmpty()) photoFile = new File(p); if (!c.isEmpty()) cameraFile = new File(c);
            codeAvailableAt = state.getLong("codeAvailableAt");
            authMode=state.getString("authMode","login");authEmail=state.getString("authEmail","");
            automaticLoginAttempted=state.getBoolean("automaticLoginAttempted");homeNotice=state.getString("homeNotice","");
        }
        if (session.base.isEmpty()) screen = "server";
        else if (session.token.isEmpty() && !screen.equals("server") && !screen.equals("login")) screen = "home";
        render();
        if(screen.equals("home")&&session.token.isEmpty()&&session.autoLogin&&session.hasCredentials()&&!automaticLoginAttempted)autoLogin();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        captureTransient();
        out.putString("screen", screen); out.putString("selected", selected.toString()); out.putString("draft", draft.toString());
        out.putString("form", restoredForm == null ? "{}" : restoredForm.toString()); out.putString("filter", filter);
        out.putBoolean("editing", editing); out.putBoolean("manual", manualVisible);
        out.putBoolean("rulesExpanded",rulesExpanded); out.putBoolean("advancedExpanded",advancedExpanded); out.putBoolean("aiExpanded",aiExpanded);
        out.putString("detailBack",detailBack); out.putString("photoBack",photoBack); out.putString("photoId",photoId); out.putString("photoTitle",photoTitle);
        out.putString("recordBack",recordBack); out.putInt("gardenPage",gardenPage); out.putString("completedRecord",completedRecord.toString());
        out.putString("gardenSelectedPlantId",gardenSelectedPlantId);out.putBoolean("gardenMemoriesExpanded",gardenMemoriesExpanded);
        out.putString("consumedGrowthId",growthAnimationState.savedId());
        out.putString("calendarDate",calendarDate.toString()); out.putString("calendarMonth",calendarMonth.toString()); out.putInt("recordPage",recordPage); out.putString("selectedRecord",selectedRecord.toString());
        out.putString("aiText", aiText); out.putString("aiWarning", aiWarning); out.putString("completionText", completionText);
        out.putString("photo", photoFile == null ? "" : photoFile.getAbsolutePath()); out.putString("camera", cameraFile == null ? "" : cameraFile.getAbsolutePath());
        out.putLong("codeAvailableAt", codeAvailableAt); super.onSaveInstanceState(out);
        out.putString("authMode",authMode);out.putString("authEmail",authEmail);out.putString("homeNotice",homeNotice);
        out.putBoolean("automaticLoginAttempted",automaticLoginAttempted&&!authBusy);
    }
    @Override protected void onResume() { super.onResume(); setMotionHostActive(true); }
    @Override protected void onPause() { setMotionHostActive(false); super.onPause(); }
    @Override public void onDestroy() { stopPageMotion(); super.onDestroy(); workers.shutdownNow(); ui.removeCallbacksAndMessages(null); }
    private void setMotionHostActive(boolean active) {
        motionHostActive=active;
        if(growthView!=null)growthView.setHostActive(active);
        if(gardenScene!=null)gardenScene.setHostActive(active);
        if(!active&&body!=null){body.animate().cancel();body.setAlpha(1f);body.setTranslationY(0f);}
    }
    private void stopPageMotion() {
        if(growthView!=null){growthView.setOnPhaseChangedListener(null);growthView.setHostActive(false);growthView=null;}
        if(gardenScene!=null){gardenScene.setHostActive(false);gardenScene=null;}
        if(body!=null)body.animate().cancel();
    }
    @Override public void onBackPressed() {
        if(!canLeaveAuthentication())return;
        if (screen.equals("home") || (screen.equals("server") && session.base.isEmpty())) { super.onBackPressed(); return; }
        if (screen.equals("editor")) {
            new AlertDialog.Builder(this).setTitle("离开编辑？").setMessage("还没有保存的修改将丢弃。").setNegativeButton("继续编辑", null)
                    .setPositiveButton("离开", (d,w) -> navigate(editing ? "detail" : "home")).show(); return;
        }
        navigate(screen.equals("photo") ? photoBack : screen.equals("record") ? recordBack : screen.equals("detail") ? detailBack : screen.equals("complete") ? "detail" : "home");
    }
    private void navigate(String next) {
        if(!canLeaveAuthentication())return;
        authRequest++;authBusy=false;
        boolean changed=!screen.equals(next);captureTransient();screen=next;render();
        if(changed&&motionHostActive&&!next.equals("grown")&&ValueAnimator.areAnimatorsEnabled()){
            body.setAlpha(0f);body.setTranslationY(dp(5));body.animate().alpha(1f).translationY(0f).setDuration(180)
                    .setInterpolator(new android.view.animation.PathInterpolator(.22f,1f,.36f,1f)).start();
        }
    }
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
        if (screen.equals("login") && fields.containsKey("email")) authEmail=value("email");
    }
    private void render() {
        stopPageMotion();
        if(session.token.isEmpty()&&!Arrays.asList("home","login","server").contains(screen))screen="home";
        pageEpoch++; taskBusy = false; fields.clear(); kindPicker = null; modePicker = null; weekdays = null;
        root = column(); root.setBackgroundColor(BG); setContentView(root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 35) { android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime()); v.setPadding(i.left, i.top, i.right, i.bottom); }
            return insets;
        });
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        body = column(); body.setPadding(dp(16), dp(12), dp(16), dp(20)); scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        gardenContent=null; gardenPreview=null;
        if (!Arrays.asList("home", "garden", "history", "settings", "login", "server").contains(screen)) {
            TextView back = text("‹  返回", 15, BLUE, true); back.setMinHeight(dp(48)); back.setGravity(Gravity.CENTER_VERTICAL); back.setOnClickListener(v -> onBackPressed()); body.addView(back);
        }
        errorView = text("", 14, RED, false); errorView.setPadding(dp(14), dp(12), dp(14), dp(12)); errorView.setBackground(shape(0xffffeeee, 14)); errorView.setVisibility(View.GONE); body.addView(errorView);
        switch (screen) {
            case "server": renderServer(); break;
            case "login": renderLogin(); break;
            case "history": renderHistory(); break;
            case "garden": renderGarden(); break;
            case "grown": renderGrown(); break;
            case "record": renderRecord(); break;
            case "settings": renderSettings(); break;
            case "editor": renderEditor(); break;
            case "detail": renderDetail(); break;
            case "complete": renderComplete(); break;
            case "photo": renderPhoto(); break;
            default: renderHome(); break;
        }
        if (Arrays.asList("home", "garden", "history", "settings").contains(screen) && !session.token.isEmpty()) bottomNav();
    }
    private void header(String overline, String title, String subtitle) {
        if (!overline.isEmpty()) { TextView over = text(overline, 12, BLUE, true); over.setLetterSpacing(.09f); body.addView(over); gap(body, 8); }
        body.addView(text(title, 22, INK, true));
        if (!subtitle.isEmpty()) { gap(body,8); body.addView(text(subtitle, 14, MUTED, false)); }
        gap(body,16);
    }
    private void renderServer() {
        body.addView(art(R.drawable.garden_sprout,96));gap(body,14);
        header("念念 · 小事生长的地方", "给生活，种一点期待", "连接你的提醒服务。把小事种下来，让念念陪你记得。");
        field(body,"server","服务器地址",session.base,"https://你的域名 或 http://局域网IP:56507",1);
        note(body,"本地调试：手机和电脑连接同一个 Wi-Fi，填写电脑的局域网 IP；手机上的 localhost 指向手机自己。");
        Button test = button("测试连接", false); body.addView(test); test.setOnClickListener(v -> {
            String address;
            try { address = Api.normalize(value("server")); } catch (Exception e) { showError(e.getMessage()); return; }
            run(test, a -> {try{return new Api(address, "").json("GET", "/health", null);}catch(Api.Failure e){throw new IOException(e.getMessage());}}, data -> {
                if (!"ok".equals(data.optString("status"))) { showError("地址可访问，但不是念念提醒服务"); return; }
                toast("连接成功 · " + data.optString("version", "服务正常"));
            });
        }); gap(body,12);
        Button save = button(session.base.isEmpty() ? "连接并继续" : "保存服务器地址", true); body.addView(save);
        save.setOnClickListener(v -> {
            String address;
            try { address = Api.normalize(value("server")); } catch (Exception e) { showError(e.getMessage()); return; }
            try {
                String previous=session.base;
                session.switchServer(address);
                if(!previous.equals(session.base)){accountEpoch++;clearAccountData();codeAvailableAt=0;}
                homeNotice="";
                if(!session.token.isEmpty()){navigate("home");toast("服务器地址已保存");}
                else if(session.hasCredentials()){automaticLoginAttempted=false;autoLogin();}
                else{homeNotice="服务器地址已保存，请先登录。已有账号可验证邮箱设置一次密码。";navigate("home");}
            }catch(Exception e){showError("服务器地址未保存，请重试");}
        });
        if (!session.base.isEmpty()) { gap(body,12); Button cancel = button("返回", false); body.addView(cancel); cancel.setOnClickListener(v -> navigate("home")); }
        gap(body,20); note(body,"保存后会用手机已记住的账号密码尝试登录新服务器。不同服务器的任务和照片分别保存。公网地址请使用 HTTPS。");
    }
    private void renderLogin() {
        boolean passwordLogin=authMode.equals("login"),reset=authMode.equals("reset");
        body.addView(art(R.drawable.garden_bloom,86));gap(body,10);
        header("欢迎来到念念",passwordLogin?"你的小花园，在等你":reset?"重新设置登录密码":"给账号设置一个密码",
                passwordLogin?"用邮箱和密码登录，下次打开就能继续。":reset?"验证邮箱后换个新密码，原来的提醒和记录都在。":"新用户验证邮箱后注册；已有账号设置后，原来的记录继续保留。");
        EditText email=field(body,"email","接收提醒的邮箱",authEmail.isEmpty()?session.email:authEmail,"你的邮箱地址",1);
        email.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        if(!passwordLogin){
            EditText code=field(body,"code","邮箱验证码","","输入 6 位验证码",1);code.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);code.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});code.setSaveEnabled(false);
            Button send=button("发送验证码",false);body.addView(send);updateCodeButton(send);
            send.setOnClickListener(v->{String address=value("email");if(!android.util.Patterns.EMAIL_ADDRESS.matcher(address).matches()){email.setError("请填写有效的邮箱地址");return;}
                run(send,a->new Api(a.base,"").json("POST","/api/v1/auth/code",object("email",address)),r->{codeAvailableAt=System.currentTimeMillis()+60000;updateCodeButton(send);toast("验证码已发送，请查看邮箱和垃圾邮件");});});gap(body,12);
        }
        EditText password=field(body,"password",passwordLogin?"登录密码":"设置密码",passwordLogin?session.password:"","至少 8 个字符",1);
        password.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);password.setSaveEnabled(false);
        password.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);password.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        if(passwordLogin){
            if(!email.getText().toString().trim().equalsIgnoreCase(session.email))password.setText("");
            boolean[] remembered={password.getText().toString().equals(session.password)};
            email.addTextChangedListener(watcher(()->{if(remembered[0]&&!email.getText().toString().trim().equalsIgnoreCase(session.email)){remembered[0]=false;password.setText("");}}));
            password.addTextChangedListener(watcher(()->remembered[0]=false));
        }
        note(body,"登录后，账号密码会加密记在这台手机上，下次自动登录。");
        Button login=button(passwordLogin?"登录念念":reset?"保存新密码并登录":"验证邮箱并继续",true);body.addView(login);
        login.setOnClickListener(v->{
            String address=value("email"),secret=password.getText().toString(),digits=passwordLogin?"":value("code");
            if(!android.util.Patterns.EMAIL_ADDRESS.matcher(address).matches()){email.setError("请填写有效的邮箱地址");return;}
            if(secret.codePointCount(0,secret.length())<8||secret.getBytes(StandardCharsets.UTF_8).length>128){password.setError("密码至少 8 个字符，最多 128 字节");return;}
            if(!passwordLogin&&digits.length()!=6){fields.get("code").setError("请输入 6 位验证码");return;}
            startAuthentication(login,passwordLogin?"/api/v1/auth/login":reset?"/api/v1/auth/password/reset":"/api/v1/auth/register",address,secret,digits,false);
        });gap(body,10);
        LinearLayout links=row();Button primaryLink=smallButton(passwordLogin?"注册 / 首次设置密码":"已有密码，返回登录");links.addView(primaryLink,new LinearLayout.LayoutParams(0,dp(48),1));primaryLink.setOnClickListener(v->openAuth(passwordLogin?"register":"login"));
        if(passwordLogin){Button forgot=smallButton("忘记密码");links.addView(forgot);forgot.setOnClickListener(v->openAuth("reset"));}body.addView(links);gap(body,14);note(body,session.base);
        Button change = button("更换服务器",false); body.addView(change); change.setOnClickListener(v -> navigate("server"));
        gap(body,8);Button home=smallButton("返回首页");body.addView(home);home.setOnClickListener(v->navigate("home"));
    }
    private boolean canLeaveAuthentication(){if(credentialWritePending){toast("正在保存密码，请稍候");return false;}return true;}
    private void openAuth(String mode){if(!canLeaveAuthentication())return;captureTransient();authMode=mode;homeNotice="";navigate("login");}
    private void autoLogin(){
        if(automaticLoginAttempted||!session.hasCredentials()||session.base.isEmpty())return;
        automaticLoginAttempted=true;homeNotice="";navigate("home");authBusy=true;render();
        startAuthentication(null,"/api/v1/auth/login",session.email,session.password,"",true);
    }
    private void startAuthentication(Button trigger,String endpoint,String address,String password,String code,boolean automatic){
        int request=++authRequest,account=accountEpoch,page=pageEpoch;String base=session.base;
        CharSequence caption=trigger==null?"":trigger.getText();authBusy=true;errorView.setVisibility(View.GONE);
        credentialWritePending=!endpoint.equals("/api/v1/auth/login");
        if(trigger!=null){trigger.setEnabled(false);trigger.setText("正在登录…");}
        JSONObject payload=object("email",address,"password",password);if(!code.isEmpty())try{payload.put("code",code);}catch(JSONException ignored){}
        workers.submit(()->{
            JSONObject response=null;Exception error=null;
            try{response=new Api(base,"").json("POST",endpoint,payload);}catch(Exception e){error=e;}
            JSONObject result=response;Exception failure=error;
            ui.post(()->{
                if(isDestroyed()||request!=authRequest||account!=accountEpoch||page!=pageEpoch||!base.equals(session.base))return;
                authBusy=false;credentialWritePending=false;if(trigger!=null){trigger.setEnabled(true);trigger.setText(caption);}
                if(failure==null){
                    try{
                        String token=result.getString("token"),email=result.getJSONObject("user").getString("email");
                        if(token.isEmpty()||!email.equalsIgnoreCase(address.trim()))throw new IOException("账号返回不一致");
                        session.login(token,email,password);accountEpoch++;clearAccountData();authEmail=email;homeNotice="";authMode="login";
                        if(!automatic)automaticLoginAttempted=false;navigate("home");return;
                    }catch(Exception ignored){authenticationFailed(automatic,"无法保存登录信息，请重试");return;}
                }
                authenticationFailed(automatic,authFailureMessage(failure));
            });
        });
    }
    private String authFailureMessage(Exception error){
        if(error instanceof Api.Failure){int status=((Api.Failure)error).status;
            if(status==404||status==405)return "当前服务器还不支持密码登录，请更新后端到 1.4.0";
            if(status==401)return "邮箱或密码不正确，或这个服务器还没有你的账号";
        }
        return error.getMessage()==null?"暂时无法登录，请重试":error.getMessage();
    }
    private void authenticationFailed(boolean automatic,String message){
        if(automatic){homeNotice="自动登录未成功："+message;screen="home";render();toast(homeNotice);}else showError(message);
    }
    private void loginExpired(){
        if(credentialWritePending)return;
        try{session.clear();}catch(Exception e){showError("无法更新登录状态，请重试");return;}
        accountEpoch++;clearAccountData();
        if(session.autoLogin&&session.hasCredentials()&&!automaticLoginAttempted)autoLogin();
        else{homeNotice="登录已过期，请重新登录。手机已记住你的账号信息。";navigate("home");}
    }
    private void updateCodeButton(Button button) {
        long remaining = (codeAvailableAt - System.currentTimeMillis() + 999) / 1000;
        if (remaining <= 0) { button.setEnabled(true); button.setText("发送验证码"); return; }
        button.setEnabled(false); button.setText(remaining + " 秒后可重发");
        int p=pageEpoch; ui.postDelayed(() -> { if (pageEpoch==p && !isDestroyed()) updateCodeButton(button); }, 1000);
    }
    private void renderGuestHome(){
        header("念念 · 今天","让小事，慢慢长大",authBusy?"正在用已保存的账号连接你的小花园…":"登录后，继续照料你种下的小事。");
        LinearLayout welcome=card(body);welcome.setGravity(Gravity.CENTER_HORIZONTAL);welcome.addView(art(R.drawable.garden_sprout,140));gap(welcome,14);
        TextView caption=text(authBusy?"正在自动登录":"你的小花园，还在等你",18,INK,true);caption.setGravity(Gravity.CENTER);welcome.addView(caption);gap(welcome,10);
        TextView description=text(homeNotice.isEmpty()?"每一件认真完成的小事，都会留下成长。":homeNotice,14,homeNotice.isEmpty()?MUTED:RED,false);description.setGravity(Gravity.CENTER);welcome.addView(description);
        if(authBusy){gap(welcome,12);ProgressBar progress=new ProgressBar(this);welcome.addView(progress,new LinearLayout.LayoutParams(dp(28),dp(28)));}
        gap(body,8);Button login=button(authBusy?"改用手动登录":"登录我的花园",true);body.addView(login);login.setOnClickListener(v->openAuth("login"));gap(body,10);
        Button register=button("注册 / 首次设置密码",false);body.addView(register);register.setOnClickListener(v->openAuth("register"));gap(body,14);
        Button change=smallButton("更换服务器");body.addView(change);change.setOnClickListener(v->navigate("server"));
    }
    private void renderHome() {
        if(session.token.isEmpty()){renderGuestHome();return;}
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("M 月 d 日  EEEE", Locale.CHINA));
        LinearLayout heading=row(), titles=column(); titles.addView(text("念念",25,INK,true)); titles.addView(text(date,12,MUTED,false)); heading.addView(titles,new LinearLayout.LayoutParams(0,-2,1));
        Button create=smallButton("＋ 播种");create.setTextColor(PAPER);create.setBackground(shape(BLUE,18));create.setContentDescription("播下一颗种子，新建提醒任务");heading.addView(create);create.setOnClickListener(v->openEditor(null));body.addView(heading);gap(body,18);
        gardenPreview=column();body.addView(gardenPreview);drawGardenPreview();gap(body,16);
        LinearLayout titleRow=row(); TextView title=text("我的小事",17,INK,true); titleRow.addView(title,new LinearLayout.LayoutParams(0,-2,1)); Button refresh=smallButton("刷新"); titleRow.addView(refresh); body.addView(titleRow);gap(body,8);
        EditText query = new EditText(this); styleField(query); query.setSingleLine(true); query.setText(search); query.setHint("搜索提醒标题或内容"); query.setTextSize(14); body.addView(query); gap(body,12);
        HorizontalScrollView tabs=new HorizontalScrollView(this); tabs.setHorizontalScrollBarEnabled(false); LinearLayout tabRow=row(); tabs.addView(tabRow);
        for(String name:new String[]{"全部","待完成","已完成","已暂停"}) { Button tab=smallButton(name); tab.setTextColor(filter.equals(name)?PAPER:MUTED); tab.setBackground(shape(filter.equals(name)?BLUE:PALE,12)); LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,dp(48));lp.setMargins(0,0,dp(6),0);tabRow.addView(tab,lp); tab.setOnClickListener(v->{filter=name; render();}); }
        body.addView(tabs);gap(body,12); taskList=column();body.addView(taskList);
        query.addTextChangedListener(watcher(() -> { search=query.getText().toString().trim(); drawTasks(); }));
        refresh.setOnClickListener(v -> {loadTasks(refresh);loadGarden();});
        drawTasks(); loadTasks(refresh);loadGarden();
    }
    private void loadTasks(Button trigger) {
        int request=++taskLoadRequest;
        if (taskData.length()==0) { taskList.removeAllViews(); taskList.addView(text("正在整理你的提醒…",14,MUTED,false)); }
        run(trigger, a -> a.json("GET","/api/v1/tasks",null), result->{if(request!=taskLoadRequest)return;taskData=result.optJSONArray("tasks"); if(taskData==null)taskData=new JSONArray();drawTasks();});
    }
    private void drawTasks() {
        if(taskList==null)return;taskList.removeAllViews();int visible=0;
        for(int i=0;i<taskData.length();i++) {
            JSONObject task=taskData.optJSONObject(i);if(task==null)continue;String status=task.optString("status","pending");
            if(filter.equals("待完成")&&!status.equals("pending"))continue;
            if(filter.equals("已完成")&&!status.equals("done"))continue;
            if(filter.equals("已暂停")&&!status.equals("paused"))continue;
            if(!search.isEmpty()&&!(task.optString("title")+task.optString("description")).toLowerCase(Locale.ROOT).contains(search.toLowerCase(Locale.ROOT)))continue;
            visible++;LinearLayout card=card(taskList),top=row(),copy=column();ImageView plant=art(plantResource(task.optString("id")),44);top.addView(plant);gapHorizontal(top,10);TextView title=text(task.optString("title"),16,INK,true);title.setMaxLines(2);title.setEllipsize(TextUtils.TruncateAt.END);copy.addView(title);gap(copy,3);
            String next=task.optString("next_reminder");copy.addView(text(taskStatusLabel(task)+(next.isEmpty()?"":" · "+formatTime(next)),12,statusColor(status),false));top.addView(copy,new LinearLayout.LayoutParams(0,-2,1));
            if(status.equals("pending")) {gapHorizontal(top,8);Button done=smallButton("完成");top.addView(done);done.setOnClickListener(v->{selected=task;detailBack="home";openComplete();});}else{gapHorizontal(top,8);top.addView(text("›",20,MUTED,false));}
            card.addView(top);gap(card,4);LinearLayout ruleRow=row();TextView summary=text(task.optString("summary"),13,MUTED,false);summary.setMaxLines(2);summary.setEllipsize(TextUtils.TruncateAt.END);ruleRow.addView(summary,new LinearLayout.LayoutParams(0,-2,1));gapHorizontal(ruleRow,8);addReminderSwitch(ruleRow,task,true);card.addView(ruleRow);card.setContentDescription(task.optString("title")+"，查看详情");card.setOnClickListener(v->{selected=task;detailBack="home";navigate("detail");});
        }
        if(visible==0){empty(taskList,taskData.length()==0?"从一颗小小的种子开始":"这里暂时没有提醒",taskData.length()==0?"把想做的小事种下来。每次完成，都是一份养分。":"换个筛选条件，或者播下一颗新种子。");if(taskData.length()==0){Button create=button("播下第一颗种子",true);taskList.addView(create);create.setOnClickListener(v->openEditor(null));}}
    }
    private void drawGardenPreview(){
        if(gardenPreview==null)return;gardenPreview.removeAllViews();
        LinearLayout preview=row();preview.setPadding(dp(14),dp(14),dp(12),dp(14));preview.setBackground(shape(PALE,24));gardenPreview.addView(preview);
        preview.addView(art(R.drawable.garden_watering,64));gapHorizontal(preview,10);LinearLayout copy=column();
        copy.addView(text(gardenData==null?"让小事，慢慢长大":"你的花园，已经种下 "+gardenData.optInt("total_plants")+" 株",17,INK,true));gap(copy,5);
        String message=gardenData==null?"播下一件事，照料一株小小的期待。":"本周浇灌 "+gardenData.optInt("week_completions")+" 次 · 走进花圃看看";
        if(!gardenNotice.isEmpty())message="花园暂未更新，点这里查看";
        copy.addView(text(message,12,MUTED,false));preview.addView(copy,new LinearLayout.LayoutParams(0,-2,1));gapHorizontal(preview,4);preview.addView(text("›",24,BLUE,false));preview.setContentDescription(message+"，打开我的花园");preview.setOnClickListener(v->navigate("garden"));
    }
    private void loadGarden(){
        int request=++gardenRequest;String zone=ZoneId.systemDefault().getId();gardenLoading=true;gardenNotice="";
        if(screen.equals("garden"))drawGarden();
        run(null,a->{JSONObject result=a.json("GET","/api/v1/garden?timezone="+Uri.encode(zone),null);if(!zone.equals(result.optString("timezone"))||result.optJSONArray("plants")==null||result.optJSONArray("recent_completions")==null||!result.has("total_completions"))throw new IOException("花园数据不完整，请更新后端后重试");return result;},result->{
            if(request!=gardenRequest)return;gardenData=result;gardenLoading=false;gardenNotice="";
            if(screen.equals("garden"))drawGarden();else if(screen.equals("home")){drawGardenPreview();drawTasks();}
        },failure->{
            if(request!=gardenRequest)return;gardenLoading=false;gardenNotice=failure instanceof Api.Failure&&(((Api.Failure)failure).status==404||((Api.Failure)failure).status==405)?"花园需要 1.3.0 或更新的后端。原有提醒仍可照常使用。":failure.getMessage()==null?"花园暂时没连上，请稍后再试。":failure.getMessage();
            if(screen.equals("garden"))drawGarden();else drawGardenPreview();
        });
    }
    private void renderGarden(){
        LinearLayout heading=row();LinearLayout titles=column();titles.addView(text("我的花园",25,INK,true));gap(titles,5);titles.addView(text("你认真做过的小事，都在这里。",13,MUTED,false));heading.addView(titles,new LinearLayout.LayoutParams(0,-2,1));Button create=smallButton("＋ 播种");heading.addView(create);create.setOnClickListener(v->openEditor(null));body.addView(heading);gap(body,16);
        gardenContent=column();body.addView(gardenContent);drawGarden();loadGarden();
    }
    private void drawGarden(){
        if(gardenContent==null)return;gardenTaskRequest++;
        if(gardenScene!=null){gardenScene.setHostActive(false);gardenScene=null;}
        gardenContent.removeAllViews();
        if(!gardenNotice.isEmpty()){
            TextView notice=text(gardenNotice,13,RED,false);gardenContent.addView(notice);gap(gardenContent,8);Button retry=smallButton("重新连接花园");gardenContent.addView(retry);retry.setOnClickListener(v->loadGarden());gap(gardenContent,12);
            if(gardenData!=null)note(gardenContent,"下面是上次读取的成长记录，暂未更新。");
        }
        if(gardenData==null){
            if(gardenLoading){note(gardenContent,"正在打开你的花园…");View placeholder=new View(this);placeholder.setBackground(shape(PALE,24));gardenContent.addView(placeholder,new LinearLayout.LayoutParams(-1,dp(190)));}
            return;
        }
        JSONArray plants=gardenData.optJSONArray("plants");List<JSONObject> ordered=new ArrayList<>();int[] stages=new int[4];
        for(int i=0;i<plants.length();i++){JSONObject plant=plants.optJSONObject(i);if(plant!=null){ordered.add(plant);stages[stageIndex(plant.optString("stage"))]++;}}
        // Completion changes the server ordering; keep a plant's garden position stable while it grows.
        ordered.sort(Comparator.comparing(plant->plant.optString("task_id")));
        int total=ordered.size(),pages=Math.max(1,(total+11)/12);gardenPage=Math.max(0,Math.min(gardenPage,pages-1));
        LinearLayout census=row();census.addView(text("已经种下 "+gardenData.optInt("total_plants")+" 株",19,INK,true),new LinearLayout.LayoutParams(0,-2,1));
        TextView bloom=text((gardenData.optBoolean("truncated")?"已加载中 ":"")+stages[3]+" 株开花",12,BLUE,true);bloom.setGravity(Gravity.CENTER);census.addView(bloom);gardenContent.addView(census);gap(gardenContent,8);
        LinearLayout legend=row();addGardenStage(legend,R.drawable.garden_seed,"种子",stages[0]);addGardenStage(legend,R.drawable.garden_sprout,"幼芽",stages[1]);addGardenStage(legend,R.drawable.garden_leaf,"枝叶",stages[2]);addGardenStage(legend,R.drawable.garden_bloom,"开花",stages[3]);gardenContent.addView(legend);gap(gardenContent,10);
        if(gardenData.optBoolean("truncated"))note(gardenContent,"已加载 "+total+" 株，下面的阶段数对应这一批；种植总数与累计养分仍是全量。");
        if(gardenLoading)note(gardenContent,"正在更新花园…");
        int from=gardenPage*12,to=Math.min(total,from+12);
        GardenPlotView scene=new GardenPlotView(this);gardenScene=scene;scene.setPlants(ordered.subList(from,to));scene.setSelectedPlantId(gardenSelectedPlantId);scene.setHostActive(motionHostActive);
        scene.setBackground(shape(PALE,24));scene.setClipToOutline(true);scene.setOnPlantClickListener((plant,trigger)->{gardenSelectedPlantId=plant.optString("task_id");scene.setSelectedPlantId(gardenSelectedPlantId);openGardenTask(plant,trigger);});
        int sceneHeight=Math.max(420,Math.min(620,(getResources().getConfiguration().screenWidthDp-32)*4/3));
        gardenContent.addView(scene,new LinearLayout.LayoutParams(-1,dp(sceneHeight)));gap(gardenContent,10);
        if(total==0){note(gardenContent,"第一片花圃，等着你来播种。每条提醒都会在这里拥有自己的位置。");Button start=button("播下第一颗种子",true);gardenContent.addView(start);start.setOnClickListener(v->openEditor(null));return;}
        if(pages>1){
            LinearLayout paging=row();Button prev=smallButton("‹"),next=smallButton("›");prev.setContentDescription("上一片花圃");next.setContentDescription("下一片花圃");prev.setEnabled(gardenPage>0);next.setEnabled(gardenPage+1<pages);paging.addView(prev);gapHorizontal(paging,8);
            String[] beds=new String[pages];for(int i=0;i<pages;i++)beds[i]="第 "+(i+1)+" / "+pages+" 片花圃";
            Spinner picker=spinner(beds);picker.setContentDescription("选择花圃");picker.setSelection(gardenPage);paging.addView(picker,new LinearLayout.LayoutParams(0,dp(48),1));gapHorizontal(paging,8);paging.addView(next);gardenContent.addView(paging);gap(gardenContent,8);
            picker.setOnItemSelectedListener(selection(()->{int page=picker.getSelectedItemPosition();if(page!=gardenPage){gardenPage=page;drawGarden();}}));prev.setOnClickListener(v->{gardenPage--;drawGarden();});next.setOnClickListener(v->{gardenPage++;drawGarden();});
        }
        TextView hint=text((pages>1?"这片是第 "+(from+1)+"–"+to+" 株 · ":"")+"轻点植物，看看它对应的小事",12,MUTED,false);hint.setGravity(Gravity.CENTER);gardenContent.addView(hint);gap(gardenContent,14);
        LinearLayout care=row();care.addView(art(R.drawable.garden_watering,42));gapHorizontal(care,8);LinearLayout careCopy=column();careCopy.addView(text("本周浇灌 "+gardenData.optInt("week_completions")+" 次 · 累计 "+gardenData.optInt("total_completions")+" 份养分",13,BLUE,true));gap(careCopy,3);careCopy.addView(text("完成 1 次发芽，3 次长叶，7 次开花。",11,MUTED,false));care.addView(careCopy,new LinearLayout.LayoutParams(0,-2,1));gardenContent.addView(care);gap(gardenContent,14);
        Button memories=button(gardenMemoriesExpanded?"收起成长日记  ▴":"翻开成长日记  ▾",false);gardenContent.addView(memories);LinearLayout diary=column();gardenContent.addView(diary);diary.setVisibility(gardenMemoriesExpanded?View.VISIBLE:View.GONE);
        memories.setOnClickListener(v->{gardenMemoriesExpanded=!gardenMemoriesExpanded;diary.setVisibility(gardenMemoriesExpanded?View.VISIBLE:View.GONE);memories.setText(gardenMemoriesExpanded?"收起成长日记  ▴":"翻开成长日记  ▾");});
        gap(diary,12);JSONArray recent=gardenData.optJSONArray("recent_completions");
        if(recent.length()==0)note(diary,"完成第一件小事后，这里会留下它的成长记忆。");
        for(int i=0;i<recent.length();i++){
            JSONObject record=recent.optJSONObject(i);if(record==null)continue;LinearLayout line=row();line.setPadding(0,dp(10),0,dp(10));LinearLayout copy=column();copy.addView(text(record.optString("task_title"),14,INK,true));gap(copy,4);copy.addView(text(formatFull(record.optString("created_at"))+" · 养分 +1",12,MUTED,false));String message=record.optString("text");
            if(!message.isEmpty()){gap(copy,5);TextView note=text(message,13,INK,false);note.setMaxLines(2);note.setEllipsize(TextUtils.TruncateAt.END);copy.addView(note);}JSONArray photos=record.optJSONArray("media_ids");if(photos!=null&&photos.length()>0){gap(copy,4);copy.addView(text(photos.length()+" 张照片 · 点开回看",12,BLUE,false));}
            line.addView(copy,new LinearLayout.LayoutParams(0,-2,1));gapHorizontal(line,12);line.addView(art(R.drawable.garden_leaf,42));diary.addView(line);line.setBackground(new RippleDrawable(ColorStateList.valueOf(0x18527a58),shape(BG,12),null));line.setOnClickListener(v->{selectedRecord=copy(record);try{selectedRecord.put("_type","completion");selectedRecord.put("_at",record.optString("created_at"));selectedRecord.put("state","completed");}catch(JSONException ignored){}recordBack="garden";navigate("record");});
        }
        gap(diary,12);Button all=button("去日历，回看更多成长记录",false);diary.addView(all);all.setOnClickListener(v->navigate("history"));
    }
    private void addGardenStage(LinearLayout parent,int resource,String name,int count){LinearLayout group=row();group.setGravity(Gravity.CENTER);group.addView(art(resource,27));gapHorizontal(group,2);TextView label=text(name+" "+count,11,MUTED,false);label.setIncludeFontPadding(false);group.addView(label);parent.addView(group,new LinearLayout.LayoutParams(0,dp(36),1));}
    private void openGardenTask(JSONObject plant,View trigger){int request=++gardenTaskRequest;trigger.setEnabled(false);run(null,a->a.json("GET","/api/v1/tasks/"+plant.optString("task_id"),null),result->{trigger.setEnabled(true);if(request!=gardenTaskRequest)return;selected=result;detailBack="garden";navigate("detail");},failure->{trigger.setEnabled(true);if(request!=gardenTaskRequest)return;showError(failure.getMessage()==null?"这株植物的提醒暂时打不开，请重试。":failure.getMessage());});}
    private int stageIndex(String stage){switch(stage){case "sprout":return 1;case "leaf":return 2;case "bloom":return 3;default:return 0;}}
    private int stageResource(String stage){switch(stage){case "sprout":return R.drawable.garden_sprout;case "leaf":return R.drawable.garden_leaf;case "bloom":return R.drawable.garden_bloom;default:return R.drawable.garden_seed;}}
    private String stageLabel(String stage){switch(stage){case "sprout":return "小芽冒头";case "leaf":return "枝叶舒展";case "bloom":return "已经开花";default:return "刚刚播种";}}
    private String growthHint(int count){if(count==0)return "等待第一次完成，唤醒小芽";if(count<3)return "再完成 "+(3-count)+" 次，就会长出新叶";if(count<7)return "再完成 "+(7-count)+" 次，就会开花";return "每一次认真完成，都会继续留在这里";}
    private int plantResource(String taskId){
        if(gardenData!=null){JSONArray plants=gardenData.optJSONArray("plants");if(plants!=null)for(int i=0;i<plants.length();i++){
            JSONObject plant=plants.optJSONObject(i);if(plant==null||!taskId.equals(plant.optString("task_id")))continue;
            if(plant.optString("stage").equals("bloom")){int variant=Math.floorMod(taskId.hashCode(),3);if(variant==1)return R.drawable.garden_bloom_pink_v2;if(variant==2)return R.drawable.garden_bloom_lilac_v2;}
            return stageResource(plant.optString("stage"));
        }}
        return R.drawable.garden_watering;
    }
    private ImageView art(int resource,int size){
        Bitmap bitmap=artCache.get(resource);if(bitmap==null){BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeResource(getResources(),resource,options);options.inSampleSize=1;while(Math.max(options.outWidth,options.outHeight)/options.inSampleSize>512)options.inSampleSize*=2;options.inJustDecodeBounds=false;options.inScaled=false;bitmap=BitmapFactory.decodeResource(getResources(),resource,options);if(bitmap!=null)artCache.put(resource,bitmap);}
        ImageView image=new ImageView(this);image.setImageBitmap(bitmap);image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);image.setLayoutParams(new LinearLayout.LayoutParams(dp(size),dp(size)));return image;
    }
    private void renderGrown(){
        JSONObject receipt=completedRecord.optJSONObject("growth");String completionId=completedRecord.optString("id");
        int from=receipt==null?-1:receipt.optInt("from_count",-1),to=receipt==null?-1:receipt.optInt("to_count",-1);
        boolean added=receipt!=null&&receipt.optBoolean("added");
        boolean known=receipt!=null&&receipt.opt("added") instanceof Boolean&&receipt.opt("from_count") instanceof Number&&receipt.opt("to_count") instanceof Number&&GrowthAnimationState.validReceipt(completionId,from,to,added);
        gap(body,8);TextView overline=text("一件小事，认真完成",12,BLUE,true);overline.setGravity(Gravity.CENTER);body.addView(overline);gap(body,12);
        GardenGrowthView animation=new GardenGrowthView(this);growthView=animation;animation.setHostActive(motionHostActive);animation.setBackground(shape(PALE,28));animation.setClipToOutline(true);body.addView(animation,new LinearLayout.LayoutParams(-1,dp(280)));gap(body,18);
        String celebration=!known||!added?"这次完成，已经记下":to==1?"小芽，探出头啦":to==3?"新叶，慢慢舒展开来":to==7?"这件小事，开花了":"又添了一份小小的努力";
        TextView title=text(celebration,23,INK,true);title.setGravity(Gravity.CENTER);body.addView(title);gap(body,8);TextView message=text(completedRecord.optString("task_title",selected.optString("title")),16,INK,true);message.setGravity(Gravity.CENTER);body.addView(message);gap(body,8);
        TextView phase=text("",13,MUTED,false);phase.setGravity(Gravity.CENTER);phase.setMinHeight(dp(42));body.addView(phase);int page=pageEpoch;
        animation.setOnPhaseChangedListener(caption->{if(page==pageEpoch&&screen.equals("grown"))phase.setText(caption);});
        if(known){
            animation.showGrowth(from,to,added,growthAnimationState.consume(completionId,from,to,added));
            TextView count=text(added?"养分 +1 · 已累计 "+to+" 份养分":"已累计 "+to+" 份养分",12,BLUE,true);count.setGravity(Gravity.CENTER);body.addView(count);
            if(added){gap(body,6);Button replay=smallButton("再看一次生长");replay.setEnabled(ValueAnimator.areAnimatorsEnabled());LinearLayout replayRow=row();replayRow.setGravity(Gravity.CENTER);replayRow.addView(replay);body.addView(replayRow);replay.setOnClickListener(v->{if(growthView!=null)growthView.replay();});}
        }else{animation.showRecorded();TextView compatibility=text("完成记录已保存。后端更新到 1.3.1 后，\n这里会展示这株植物的真实生长过程。",12,MUTED,false);compatibility.setGravity(Gravity.CENTER);body.addView(compatibility);}
        gap(body,18);
        Button garden=button("去看看我的花园",true);body.addView(garden);garden.setOnClickListener(v->navigate("garden"));gap(body,10);Button next=button("回到今天的小事",false);body.addView(next);next.setOnClickListener(v->navigate("home"));gap(body,16);
        Button memory=smallButton("回看这次完成记录");body.addView(memory);memory.setOnClickListener(v->{selectedRecord=copy(completedRecord);try{selectedRecord.put("_type","completion");selectedRecord.put("_at",completedRecord.optString("created_at"));selectedRecord.put("state","completed");}catch(JSONException ignored){}recordBack="grown";navigate("record");});
    }
    private void renderDetail() {
        boolean archived=selected.optBoolean("archived");
        LinearLayout plantHeading=row();plantHeading.addView(art(plantResource(selected.optString("id")),60));gapHorizontal(plantHeading,10);plantHeading.addView(text(archived?"成长会留下，回忆也会":"每一次完成，都让它长大一点",13,BLUE,true),new LinearLayout.LayoutParams(0,-2,1));body.addView(plantHeading);gap(body,12);
        header(archived?"已归档":taskStatusLabel(selected),selected.optString("title"),selected.optString("summary"));
        if(archived){note(body,"这条提醒已归档，以下内容仅供查看。完成文字、照片与邮件记录仍然保留。");if(!selected.optString("description").isEmpty())body.addView(text(selected.optString("description"),14,INK,false));return;}
        if(!selected.optString("description").isEmpty()) { body.addView(text(selected.optString("description"),14,INK,false));gap(body,16); }
        LinearLayout info=card(body);info.addView(text("下一次提醒",12,MUTED,false));gap(info,6);info.addView(text(selected.optString("next_reminder").isEmpty()?"当前没有待发送的提醒":formatFull(selected.optString("next_reminder")),16,INK,true));gap(info,8);
        info.addView(text("完成和邮件记录会一直保留。",12,MUTED,false));
        addReminderSwitch(info,selected);
        String status=selected.optString("status");
        if(status.equals("pending")) {
            Button done=button("✓  这件事已完成",true);body.addView(done);done.setOnClickListener(v->openComplete());gap(body,12);
            Button later=button("稍后再提醒我",false);body.addView(later);later.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("延后提醒（不能跨过本期）").setItems(new String[]{"15 分钟","30 分钟","1 小时","3 小时"},(d,w)->{
                int[] mins={15,30,60,180};String id=selected.optString("id");run(later,a->a.json("POST","/api/v1/tasks/"+id+"/snooze",object("minutes",mins[w])),r->{toast("已安排稍后提醒");reloadSelected();});
            }).show());gap(body,24);
        } else {
            JSONObject rule=selected.optJSONObject("rule");String kind=rule==null?"":rule.optString("kind"),mode=rule==null?"":rule.optString("mode");
            String doneCopy=kind.equals("ongoing")||kind.equals("minute_interval")||kind.equals("once")||kind.equals("date_range")?"这次安排已经结束，所有记录都会保留。":"本期已经结束，下一期会按规则重新开始。";
            if(mode.equals("notify_once"))doneCopy="本期的提醒邮件已经发出，不需要再点击完成。";
            note(body,status.equals("done")?doneCopy:status.equals("upcoming")?"还没有到本期时间，开始后再记录完成。":status.equals("expired")?"这条提醒已超出日期范围，记录依然保留。":"已暂停发送邮件，可以随时恢复。");
        }
        Button edit=button("编辑提醒",false);body.addView(edit);edit.setOnClickListener(v->openEditor(selected));gap(body,12);
        Button records=button("查看日历安排",false);body.addView(records);records.setOnClickListener(v->{LocalDate next=RecordDates.localDate(selected.optString("next_reminder"),ZoneId.systemDefault());calendarDate=next==null?LocalDate.now():next;calendarMonth=YearMonth.from(calendarDate);recordPage=0;navigate("history");});gap(body,16);
        Button archive=button("归档这条提醒",false);archive.setTextColor(RED);body.addView(archive);archive.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("归档这条提醒？").setMessage("归档后停止发送提醒，已完成的文字、照片和邮件记录会保留。").setNegativeButton("再想想",null).setPositiveButton("归档",(d,w)->{
            String id=selected.optString("id");run(archive,a->a.json("DELETE","/api/v1/tasks/"+id,null),r->{taskData=new JSONArray();invalidateCalendar();toast("已归档，记录已保留");navigate(detailBack.equals("garden")||detailBack.equals("record")&&recordBack.equals("garden")?"garden":calendarDetailBack()?"history":"home");});
        }).show());
    }
    private void reloadSelected() { String id=selected.optString("id");run(null,a->a.json("GET","/api/v1/tasks/"+id,null),r->{selected=r;render();}); }
    private boolean calendarDetailBack(){return detailBack.equals("record")||detailBack.equals("history");}
    private void addReminderSwitch(LinearLayout parent,JSONObject task){
        addReminderSwitch(parent,task,false);
    }
    private void addReminderSwitch(LinearLayout parent,JSONObject task,boolean compact){
        String id=task.optString("id");boolean enabled=!task.optBoolean("paused");
        Switch toggle=new Switch(this);toggle.setText(compact?"":enabled?"提醒已开启":"提醒已暂停");toggle.setTextSize(13);toggle.setTextColor(enabled?BLUE:MUTED);toggle.setShowText(false);toggle.setSwitchPadding(compact?0:dp(12));toggle.setMinHeight(dp(48));toggle.setMinWidth(dp(48));toggle.setPadding(0,0,dp(2),0);
        toggle.setThumbTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{BLUE,0xff929d90}));toggle.setTrackTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{0xffbdcfac,LINE}));
        toggle.setChecked(enabled);toggle.setEnabled(!pauseInFlight.contains(id));toggle.setContentDescription(task.optString("title")+"，"+(enabled?"提醒已开启":"提醒已暂停")+"，提醒开关");parent.addView(toggle,new LinearLayout.LayoutParams(compact?dp(56):-1,dp(48)));
        boolean[] restoring={false};toggle.setOnCheckedChangeListener((view,checked)->{
            if(restoring[0])return;
            if(!pauseInFlight.add(id)){restoring[0]=true;toggle.setChecked(enabled);restoring[0]=false;return;}
            int account=accountEpoch,page=pageEpoch;Api api=new Api(session.base,session.token);toggle.setEnabled(false);toggle.setText(compact?"":"正在保存…");errorView.setVisibility(View.GONE);
            workers.submit(()->{
                JSONObject updated=null;Exception failure=null;
                try{updated=api.json("PUT","/api/v1/tasks/"+id+"/paused",object("paused",!checked));}catch(Exception error){failure=error;}
                JSONObject result=updated;Exception error=failure;
                ui.post(()->{
                    if(isDestroyed()||account!=accountEpoch)return;
                    pauseInFlight.remove(id);
                    if(error instanceof Api.Failure&&((Api.Failure)error).status==401){loginExpired();return;}
                    if(error==null){
                        taskLoadRequest++;
                        for(int i=0;i<taskData.length();i++)if(id.equals(taskData.optJSONObject(i)==null?"":taskData.optJSONObject(i).optString("id")))try{taskData.put(i,result);}catch(JSONException ignored){}
                        if(id.equals(selected.optString("id")))selected=result;
                        invalidateCalendar();
                    }
                    if(page!=pageEpoch){
                        if(screen.equals("home"))loadTasks(null);else if(screen.equals("detail")&&id.equals(selected.optString("id")))reloadSelected();else if(screen.equals("history")&&error==null)loadCalendar(null);
                        if(error!=null)toast("“"+task.optString("title")+"”的开关未保存，请重试");
                        return;
                    }
                    if(error!=null){restoring[0]=true;toggle.setChecked(enabled);restoring[0]=false;toggle.setEnabled(true);toggle.setText(compact?"":enabled?"提醒已开启":"提醒已暂停");toggle.setTextColor(enabled?BLUE:MUTED);if(screen.equals("home"))drawTasks();String message=error instanceof Api.Failure&&(((Api.Failure)error).status==404||((Api.Failure)error).status==405)?"请更新后端后再切换提醒开关":error.getMessage();showError(message==null?"开关未保存，请重试":message);return;}
                    if(screen.equals("home"))drawTasks();else if(screen.equals("detail"))render();
                });
            });
        });
    }
    private void openEditor(JSONObject task) {
        if(task!=null&&pauseInFlight.contains(task.optString("id"))){toast("正在保存开关状态，请稍等");return;}
        editing=task!=null;draft=task==null?new JSONObject():copy(task);restoredForm=null;manualVisible=editing;rulesExpanded=editing;advancedExpanded=false;aiExpanded=!editing;aiText="";aiWarning="";
        screen="editor";render();
    }
    private void renderEditor() {
        LinearLayout seedHeading=row();seedHeading.addView(art(R.drawable.garden_seed,66));gapHorizontal(seedHeading,12);LinearLayout seedCopy=column();seedCopy.addView(text(editing?"照料这颗种子":"播下一件小事",23,INK,true));gap(seedCopy,4);seedCopy.addView(text(editing?"调整提醒，按自己的节奏来。":"每条提醒，都是花园里的一颗种子。",12,MUTED,false));seedHeading.addView(seedCopy,new LinearLayout.LayoutParams(0,-2,1));body.addView(seedHeading);gap(body,18);
        if(!editing) {
            if(aiExpanded) {
                EditText sentence=field(body,"ai","说一句话，安排提醒",aiText,"例如：15 分钟后提醒我交资料，没完成就每 5 分钟催一次",2);
                LinearLayout actions=row();Button generate=button(manualVisible?"重新生成并替换草稿":"✦ 帮我安排",true);actions.addView(generate,new LinearLayout.LayoutParams(0,dp(48),1));
                if(!manualVisible){gapHorizontal(actions,8);Button manual=smallButton("手动填");actions.addView(manual);manual.setOnClickListener(v->{captureTransient();manualVisible=true;rulesExpanded=true;aiExpanded=false;render();});}
                body.addView(actions);gap(body,12);
                generate.setOnClickListener(v->{String input=sentence.getText().toString().trim();if(input.length()<2){showError("先写下要提醒的事和时间");return;}aiText=input;
                    run(generate,a->a.json("POST","/api/v1/ai/parse",object("text",input)),result->{draft=result;restoredForm=null;manualVisible=true;rulesExpanded=false;advancedExpanded=false;aiExpanded=false;JSONArray warnings=result.optJSONArray("warnings");aiWarning=warnings==null?"":join(warnings,"\n");render();});
                });
            } else {
                Button change=smallButton("✦ 修改描述 / 重新生成");body.addView(change);change.setOnClickListener(v->{captureTransient();aiExpanded=true;render();});gap(body,8);
            }
        }
        if(!manualVisible)return;
        if(!aiWarning.isEmpty())note(body,aiWarning);
        JSONObject rule=draft.optJSONObject("rule");if(rule==null)rule=new JSONObject();
        field(body,"title","提醒事项",formValue("title",draft.optString("title")),"例如：申报公司财务",1);
        TextView preview=text("",13,INK,false);preview.setPadding(dp(12),dp(10),dp(12),dp(10));preview.setBackground(shape(PALE,12));body.addView(preview);gap(body,10);
        Button save=button(editing?"保存修改":"播下种子，开始提醒",true);body.addView(save);save.setOnClickListener(v->saveEditor(save));gap(body,4);
        Button adjust=smallButton(rulesExpanded?"收起规则  ▴":"调整规则  ▾");body.addView(adjust);
        LinearLayout rules=column();body.addView(rules);rules.setVisibility(rulesExpanded?View.VISIBLE:View.GONE);
        adjust.setOnClickListener(v->{rulesExpanded=!rulesExpanded;rules.setVisibility(rulesExpanded?View.VISIBLE:View.GONE);adjust.setText(rulesExpanded?"收起规则  ▴":"调整规则  ▾");});
        int initialKind=Arrays.asList(KINDS).indexOf(rule.optString("kind","monthly_window"));if(initialKind<0)initialKind=KINDS.length-1;
        int restoredKind=restoredForm!=null?restoredForm.optInt("kind_index",initialKind):initialKind;if(restoredKind<0||restoredKind>=KINDS.length)restoredKind=KINDS.length-1;
        label(rules,"重复方式");kindPicker=spinner(KIND_LABELS);rules.addView(kindPicker);kindPicker.setSelection(restoredKind);gap(rules,10);
        LinearLayout monthly=column();LinearLayout[] monthPair=pairedFields(monthly);
        field(monthPair[0],"month_start","每月开始日",formValue("month_start",String.valueOf(rule.optInt("month_start",1))),"1",1);number(fields.get("month_start"));
        field(monthPair[1],"month_end","每月结束日",formValue("month_end",String.valueOf(rule.optInt("month_end",15))),"15",1);number(fields.get("month_end"));
        note(monthly,"超出当月天数时，按月末计算。");rules.addView(monthly);
        LinearLayout weekly=column();label(weekly,"每周哪几天");weekdays=new CheckBox[7];JSONArray chosen=restoredForm==null?rule.optJSONArray("weekdays"):restoredForm.optJSONArray("weekdays");
        LinearLayout weekRow=row();weekly.addView(weekRow);String[] names={"周一","周二","周三","周四","周五","周六","周日"};
        for(int i=0;i<7;i++){if(i==4){weekRow=row();weekly.addView(weekRow);}CheckBox box=new CheckBox(this);box.setText(names[i]);box.setTextColor(INK);box.setTextSize(13);box.setButtonTintList(ColorStateList.valueOf(BLUE));box.setMinHeight(dp(48));box.setPadding(0,0,0,0);weekdays[i]=box;box.setChecked(contains(chosen,i+1));weekRow.addView(box,new LinearLayout.LayoutParams(0,-2,1));}rules.addView(weekly);gap(weekly,8);
        LinearLayout interval=column();field(interval,"every_days","每隔几天",formValue("every_days",String.valueOf(rule.optInt("every_days",1))),"1 至 365",1);number(fields.get("every_days"));rules.addView(interval);
        LinearLayout minutes=column();rules.addView(minutes);
        String initialZone=formValue("timezone",rule.optString("timezone","Asia/Shanghai"));ZoneId firstZone;try{firstZone=ZoneId.of(initialZone);}catch(Exception invalid){firstZone=ZoneId.of("Asia/Shanghai");}
        String initialFirst=rule.optString("kind").equals("minute_interval")?rule.optString("first_at"):MinuteIntervalForm.defaultFirstAt(Instant.now(),firstZone);
        EditText hiddenFirst=new EditText(this);hiddenFirst.setText(formValue("first_at",initialFirst));fields.put("first_at",hiddenFirst);
        LinearLayout[] firstPair=pairedFields(minutes);
        EditText firstDate=field(firstPair[0],"first_date","首次提醒日期","","选择日期",1);firstDate.setFocusable(false);firstDate.setOnClickListener(v->pickMinuteFirstDate());
        EditText firstTime=field(firstPair[1],"first_time","首次提醒时间","","选择时间",1);firstTime.setFocusable(false);firstTime.setOnClickListener(v->pickMinuteFirstTime());
        field(minutes,"repeat_minutes","之后每隔几分钟",formValue("repeat_minutes",String.valueOf(rule.optString("kind").equals("minute_interval")?rule.optInt("repeat_minutes",5):5)),"1 至 1440",1);number(fields.get("repeat_minutes"));
        note(minutes,"按下方时区显示首次时间。之后持续提醒，跨天、跨月也继续；点完成后永久停止。");
        LinearLayout[] datePair=pairedFields(rules);
        field(datePair[0],"start_date","开始日期",formValue("start_date",rule.optString("start_date")),"不限 / 选择",1);dateField(fields.get("start_date"));
        field(datePair[1],"end_date","结束日期",formValue("end_date",rule.optString("end_date")),"不限 / 选择",1);dateField(fields.get("end_date"));
        TextView dateHelp=text("",12,MUTED,false);rules.addView(dateHelp);gap(rules,10);
        JSONArray initialTimes=rule.optJSONArray("times");EditText hiddenTimes=new EditText(this);hiddenTimes.setText(formValue("times",initialTimes==null?"09:00":join(initialTimes,", ")));fields.put("times",hiddenTimes);
        LinearLayout fixedTimes=column();rules.addView(fixedTimes);label(fixedTimes,"提醒时间");HorizontalScrollView timesScroll=new HorizontalScrollView(this);timesScroll.setHorizontalScrollBarEnabled(false);LinearLayout timeRow=row();timesScroll.addView(timeRow);fixedTimes.addView(timesScroll);gap(fixedTimes,10);drawTimeChips(timeRow);
        LinearLayout completionMode=column();rules.addView(completionMode);label(completionMode,"完成后怎么提醒");modePicker=spinner(MODE_LABELS);completionMode.addView(modePicker);modePicker.setSelection(restoredForm!=null?restoredForm.optInt("mode_index",indexOf(MODES,rule.optString("mode","until_done"))):indexOf(MODES,rule.optString("mode","until_done")));gap(completionMode,6);
        TextView modeHelp=text("",12,MUTED,false);completionMode.addView(modeHelp);gap(completionMode,8);
        Button advanced=smallButton(advancedExpanded?"收起更多设置  ▴":"更多设置  ▾");rules.addView(advanced);LinearLayout extra=column();rules.addView(extra);extra.setVisibility(advancedExpanded?View.VISIBLE:View.GONE);
        advanced.setOnClickListener(v->{advancedExpanded=!advancedExpanded;extra.setVisibility(advancedExpanded?View.VISIBLE:View.GONE);advanced.setText(advancedExpanded?"收起更多设置  ▴":"更多设置  ▾");});
        field(extra,"description","补充说明",formValue("description",draft.optString("description")),"可不填",2);
        LinearLayout[] settingsPair=pairedFields(extra);field(settingsPair[0],"max_reminders","提醒次数上限",formValue("max_reminders",String.valueOf(rule.optInt("max_reminders",0))),"0 表示不限",1);number(fields.get("max_reminders"));
        field(settingsPair[1],"timezone","时区",formValue("timezone",rule.optString("timezone","Asia/Shanghai")),"Asia/Shanghai",1);note(extra,"上限填 0 表示不限次数，提醒按所选时区发送。");
        refreshMinuteFirstFields();
        Runnable updatePreview=()->preview.setText(editorSummary());
        Runnable updateKind=()->{String kind=KINDS[kindPicker.getSelectedItemPosition()];boolean minuteKind=kind.equals("minute_interval");monthly.setVisibility(kind.equals("monthly_window")?View.VISIBLE:View.GONE);weekly.setVisibility(kind.equals("weekly")?View.VISIBLE:View.GONE);interval.setVisibility(kind.equals("interval")?View.VISIBLE:View.GONE);minutes.setVisibility(minuteKind?View.VISIBLE:View.GONE);datePair[0].setVisibility(minuteKind?View.GONE:View.VISIBLE);fixedTimes.setVisibility(minuteKind?View.GONE:View.VISIBLE);completionMode.setVisibility(minuteKind?View.GONE:View.VISIBLE);modePicker.setEnabled(!kind.equals("ongoing")&&!minuteKind);if(kind.equals("ongoing")||minuteKind)modePicker.setSelection(0);dateHelp.setText(minuteKind?"结束日期可不填；填写后提醒至当天结束，长按可清除。":kind.equals("date_range")?"开始和结束日期都要填，长按可清除。":Arrays.asList("once","interval","ongoing").contains(kind)?"需选择开始日期，长按日期可清除。":"日期可不填，长按日期可清除。");updateModeHelp(modeHelp);updatePreview.run();};
        kindPicker.setOnItemSelectedListener(selection(updateKind));modePicker.setOnItemSelectedListener(selection(()->{updateModeHelp(modeHelp);updatePreview.run();}));updateKind.run();
        for(Map.Entry<String,EditText> entry:fields.entrySet())if(!entry.getKey().equals("ai"))entry.getValue().addTextChangedListener(watcher(updatePreview));
        hiddenFirst.addTextChangedListener(watcher(()->{refreshMinuteFirstFields();updatePreview.run();}));fields.get("timezone").addTextChangedListener(watcher(()->{refreshMinuteFirstFields();updatePreview.run();}));
        hiddenTimes.addTextChangedListener(watcher(()->drawTimeChips(timeRow)));for(CheckBox day:weekdays)day.setOnCheckedChangeListener((v,checked)->updatePreview.run());
    }
    private LinearLayout[] pairedFields(LinearLayout parent){
        boolean stack=getResources().getConfiguration().screenWidthDp<350||getResources().getConfiguration().fontScale>1.25f;
        LinearLayout group=stack?column():row();group.setGravity(Gravity.TOP);parent.addView(group);LinearLayout left=column(),right=column();Space gutter=new Space(this);
        group.addView(left,stack?new LinearLayout.LayoutParams(-1,-2):new LinearLayout.LayoutParams(0,-2,1));group.addView(gutter,new LinearLayout.LayoutParams(dp(10),1));gutter.setVisibility(stack?View.GONE:View.VISIBLE);
        group.addView(right,stack?new LinearLayout.LayoutParams(-1,-2):new LinearLayout.LayoutParams(0,-2,1));
        // Adapt the existing fields in place so rotating during a save keeps its request callback.
        group.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{boolean narrow=r-l<dp(318)||getResources().getConfiguration().fontScale>1.25f;int orientation=narrow?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL;if(group.getOrientation()==orientation)return;group.setOrientation(orientation);gutter.setVisibility(narrow?View.GONE:View.VISIBLE);left.setLayoutParams(narrow?new LinearLayout.LayoutParams(-1,-2):new LinearLayout.LayoutParams(0,-2,1));right.setLayoutParams(narrow?new LinearLayout.LayoutParams(-1,-2):new LinearLayout.LayoutParams(0,-2,1));});
        return new LinearLayout[]{left,right};
    }
    private String editorSummary(){
        if(kindPicker==null||modePicker==null)return "检查时间和完成条件后，保存即可。";
        String kind=KINDS[kindPicker.getSelectedItemPosition()],schedule=KIND_LABELS[kindPicker.getSelectedItemPosition()];
        if(kind.equals("unsupported"))return "当前版本暂不支持这条规则，请选择重复方式后再保存。";
        if(kind.equals("minute_interval")){
            String first=value("first_date").isEmpty()?"请选择首次提醒时间":value("first_date")+" "+value("first_time");
            String end=value("end_date"),cap=value("max_reminders");
            return "首次："+first+"（"+value("timezone")+"）\n之后每隔 "+value("repeat_minutes")+" 分钟提醒，完成后永久停止"
                    +(end.isEmpty()?"":"\n提醒至 "+end+" 当天结束")+(!cap.isEmpty()&&!cap.equals("0")?" · 最多 "+cap+" 次":"");
        }
        if(kind.equals("monthly_window"))schedule="每月 "+value("month_start")+"–"+value("month_end")+" 日";
        if(kind.equals("interval"))schedule="每隔 "+value("every_days")+" 天";
        if(kind.equals("weekly")){StringBuilder days=new StringBuilder();String[] names={"一","二","三","四","五","六","日"};for(int i=0;i<7;i++)if(weekdays[i].isChecked()){if(days.length()>0)days.append("、");days.append(names[i]);}schedule="每周"+days;}
        String start=value("start_date"),end=value("end_date"),range=start.isEmpty()?(end.isEmpty()?"":"至 "+end):start+(end.isEmpty()?" 起":" 至 "+end);
        String stop=kind.equals("ongoing")?"完成后结束":MODE_LABELS[modePicker.getSelectedItemPosition()];String cap=value("max_reminders");if(!cap.isEmpty()&&!cap.equals("0"))stop+=" · 最多 "+cap+" 次";
        return schedule+" · "+value("times")+(range.isEmpty()?"":"\n"+range)+"\n"+stop;
    }
    private void drawTimeChips(LinearLayout row){
        row.removeAllViews();String raw=value("times");String[] times=raw.isEmpty()?new String[0]:raw.replace('，',',').split("[,\\s]+");
        for(int i=0;i<times.length;i++){final int position=i;final String time=times[i];Button chip=smallButton(time);row.addView(chip);gapHorizontal(row,6);chip.setContentDescription(time+"，修改或移除时间");chip.setOnClickListener(v->new AlertDialog.Builder(this).setTitle(time).setItems(new String[]{"修改时间","移除时间"},(dialog,which)->{if(which==1){List<String> updated=new ArrayList<>(Arrays.asList(times));updated.remove(position);fields.get("times").setText(TextUtils.join(", ",updated));}else pickTime(position,time);}).show());}
        Button add=smallButton("＋ 时间");add.setEnabled(times.length<8);row.addView(add);add.setOnClickListener(v->pickTime(-1,"09:00"));
    }
    private void pickTime(int position,String initial){
        LocalTime time;try{time=LocalTime.parse(initial);}catch(Exception e){time=LocalTime.of(9,0);}new TimePickerDialog(this,(view,h,m)->{
            String current=value("times");List<String> times=new ArrayList<>();if(!current.isEmpty())times.addAll(Arrays.asList(current.replace('，',',').split("[,\\s]+")));String chosen=String.format(Locale.ROOT,"%02d:%02d",h,m);if(position>=0&&position<times.size())times.set(position,chosen);else times.add(chosen);TreeSet<String> sorted=new TreeSet<>(times);fields.get("times").setText(TextUtils.join(", ",sorted));
        },time.getHour(),time.getMinute(),true).show();
    }
    private void refreshMinuteFirstFields(){
        try{
            ZoneId zone=ZoneId.of(value("timezone"));
            fields.get("first_date").setText(MinuteIntervalForm.date(value("first_at"),zone));
            fields.get("first_time").setText(MinuteIntervalForm.time(value("first_at"),zone));
        }catch(Exception invalid){
            fields.get("first_date").setText("");fields.get("first_time").setText("");
        }
    }
    private String minuteFirstOrDefault(ZoneId zone){
        try{return MinuteIntervalForm.validate(value("first_at"),zone,null);}catch(IllegalArgumentException invalid){return MinuteIntervalForm.defaultFirstAt(Instant.now(),zone);}
    }
    private void pickMinuteFirstDate(){
        try{
            ZoneId zone=ZoneId.of(value("timezone"));String source=minuteFirstOrDefault(zone);LocalDate date=LocalDate.parse(MinuteIntervalForm.date(source,zone));
            new DatePickerDialog(this,(picker,y,m,d)->{
                try{fields.get("first_at").setText(MinuteIntervalForm.reschedule(source,LocalDate.of(y,m+1,d),LocalTime.parse(MinuteIntervalForm.time(source,zone)),zone));}
                catch(IllegalArgumentException invalid){showError(invalid.getMessage());}
            },date.getYear(),date.getMonthValue()-1,date.getDayOfMonth()).show();
        }catch(DateTimeException invalid){showError("请先填写有效时区，例如 Asia/Shanghai");}
    }
    private void pickMinuteFirstTime(){
        try{
            ZoneId zone=ZoneId.of(value("timezone"));String source=minuteFirstOrDefault(zone);LocalTime time=LocalTime.parse(MinuteIntervalForm.time(source,zone));
            new TimePickerDialog(this,(picker,h,m)->{
                try{fields.get("first_at").setText(MinuteIntervalForm.reschedule(source,LocalDate.parse(MinuteIntervalForm.date(source,zone)),time.withHour(h).withMinute(m),zone));}
                catch(IllegalArgumentException invalid){showError(invalid.getMessage());}
            },time.getHour(),time.getMinute(),true).show();
        }catch(DateTimeException invalid){showError("请先填写有效时区，例如 Asia/Shanghai");}
    }
    private void saveEditor(Button button){
        JSONObject payload;try{payload=buildTask();}catch(Exception e){showError(e.getMessage());return;}String id=draft.optString("id");boolean isEditing=editing;
        run(button,a->a.json(isEditing?"PUT":"POST",isEditing?"/api/v1/tasks/"+id:"/api/v1/tasks",payload),result->{selected=result;taskData=new JSONArray();restoredForm=null;invalidateCalendar();if(!isEditing)detailBack="home";toast(isEditing?"修改已保存":"种子已播下，提醒也安排好了");screen="detail";render();});
    }
    private void updateModeHelp(TextView view){String[] help={"本期完成后停止，下期重新提醒。","今天完成后，明天继续提醒。","每期只发一次，无需点击完成。"};view.setText(Arrays.asList("ongoing","minute_interval").contains(KINDS[kindPicker.getSelectedItemPosition()])?"完成后永久结束，不再重复。":help[modePicker.getSelectedItemPosition()]);}
    private JSONObject buildTask() throws Exception {
        String title=value("title"),kind=KINDS[kindPicker.getSelectedItemPosition()],mode=MODES[modePicker.getSelectedItemPosition()];
        if(kind.equals("unsupported"))throw new IOException("当前规则暂不支持，请先选择重复方式");
        if(title.isEmpty())throw new IOException("请填写事情名称");if(title.codePointCount(0,title.length())>100)throw new IOException("事情名称请控制在 100 字以内");
        boolean minuteKind=kind.equals("minute_interval");String start=minuteKind?"":value("start_date"),end=value("end_date");LocalDate s=null,e=null;
        try{if(!start.isEmpty())s=LocalDate.parse(start);if(!end.isEmpty())e=LocalDate.parse(end);}catch(Exception x){throw new IOException("日期请使用 YYYY-MM-DD 格式");}
        if(Arrays.asList("once","date_range","interval","ongoing").contains(kind)&&s==null)throw new IOException("这种提醒需要填写开始日期");
        if(kind.equals("ongoing"))mode="until_done";
        if(kind.equals("date_range")&&e==null)throw new IOException("日期范围需要填写结束日期");if(s!=null&&e!=null&&e.isBefore(s))throw new IOException("结束日期不能早于开始日期");
        String zone=value("timezone");try{ZoneId.of(zone);}catch(Exception x){throw new IOException("时区无效，例如 Asia/Shanghai");}
        int cap=parseNumber("max_reminders",0,100);
        if(minuteKind){
            String first=MinuteIntervalForm.validate(value("first_at"),ZoneId.of(zone),e);int repeat=parseNumber("repeat_minutes",1,1440);
            JSONObject rule=object("kind",kind,"mode","until_done","timezone",zone,"first_at",first,"repeat_minutes",repeat,"times",new JSONArray(),"start_date","","end_date",end,"weekdays",new JSONArray(),"month_start",0,"month_end",0,"every_days",0,"max_reminders",cap);
            JSONObject task=object("title",title,"description",value("description"),"rule",rule);if(editing)task.put("paused",draft.optBoolean("paused"));return task;
        }
        TreeSet<String> times=new TreeSet<>();for(String t:value("times").replace('，',',').split("[,\\s]+")){if(!t.matches("(?:[01]\\d|2[0-3]):[0-5]\\d"))throw new IOException("时间请按 09:00, 20:00 这样的格式填写");times.add(t);}
        if(times.isEmpty()||times.size()>8)throw new IOException("每天请填写 1 至 8 个提醒时间");
        int from=1,to=15,every=1;
        if(kind.equals("monthly_window")){from=parseNumber("month_start",1,31);to=parseNumber("month_end",1,31);if(from>to)throw new IOException("每月开始日不能大于结束日");}
        if(kind.equals("interval"))every=parseNumber("every_days",1,365);
        JSONArray days=new JSONArray();if(kind.equals("weekly")){for(int i=0;i<7;i++)if(weekdays[i].isChecked())days.put(i+1);if(days.length()==0)throw new IOException("请选择至少一个星期");}
        JSONObject rule=object("kind",kind,"mode",mode,"timezone",zone,"times",new JSONArray(times),"start_date",start,"end_date",end,"weekdays",days,"month_start",from,"month_end",to,"every_days",every,"max_reminders",cap);
        JSONObject task=object("title",title,"description",value("description"),"rule",rule);if(editing)task.put("paused",draft.optBoolean("paused"));return task;
    }
    private int parseNumber(String name,int min,int max)throws IOException{try{int x=Integer.parseInt(value(name));if(x<min||x>max)throw new Exception();return x;}catch(Exception e){throw new IOException("请填写 "+min+" 至 "+max+" 之间的整数");}}
    private void openComplete(){photoFile=null;cameraFile=null;completionText="";navigate("complete");}
    private void renderComplete(){
        LinearLayout artRow=row();artRow.addView(art(R.drawable.garden_watering,76));gapHorizontal(artRow,10);artRow.addView(text("给这件小事，一份养分",18,INK,true),new LinearLayout.LayoutParams(0,-2,1));body.addView(artRow);gap(body,14);
        header("完成一件事",selected.optString("title"),"完成就能补充养分。也可以留一句话、一张照片，记住这一刻。");
        field(body,"completion","完成备注（可不填）",completionText,"例如：今天已经提交申报，回执见照片。",4);
        LinearLayout photoRow=row();Button camera=button("拍照留档",false),gallery=button("选择照片",false);photoRow.addView(camera,new LinearLayout.LayoutParams(0,dp(52),1));gapHorizontal(photoRow,10);photoRow.addView(gallery,new LinearLayout.LayoutParams(0,dp(52),1));body.addView(photoRow);gap(body,16);
        camera.setOnClickListener(v->{captureTransient();takePhoto();});gallery.setOnClickListener(v->{captureTransient();Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("image/*");i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"image/jpeg","image/png","image/webp"});try{startActivityForResult(i,102);}catch(Exception e){showError("没有可用的相册应用");}});
        if(photoFile!=null&&photoFile.isFile()){
            ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setBackground(shape(PALE,16));body.addView(image,new LinearLayout.LayoutParams(-1,dp(220)));loadLocalImage(image,photoFile);gap(body,8);
            Button remove=smallButton("移除照片");body.addView(remove);remove.setOnClickListener(v->{captureTransient();photoFile=null;render();});gap(body,16);
        }
        note(body,"完成后会保存时间、文字和照片，并按这条提醒的规则停止本期或当天的后续邮件。");
        Button save=button("完成，送出这份养分",true);body.addView(save);save.setOnClickListener(v->{
            if(taskBusy)return;taskBusy=true;String text=value("completion"),id=selected.optString("id");File photo=photoFile;
            run(save,a->{JSONArray ids=new JSONArray();if(photo!=null)ids.put(a.upload(photo).getString("id"));return a.json("POST","/api/v1/tasks/"+id+"/complete",object("text",text,"media_ids",ids));},result->{taskBusy=false;photoFile=null;completionText="";taskData=new JSONArray();completedRecord=copy(result);invalidateCalendar();navigate("grown");});
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
    private void renderHistory(){
        LinearLayout heading=row();heading.addView(text("日历安排",22,INK,true),new LinearLayout.LayoutParams(0,-2,1));calendarRefresh=smallButton("刷新");heading.addView(calendarRefresh);body.addView(heading);gap(body,10);
        LinearLayout controls=row();Button previous=smallButton("‹");previous.setContentDescription("上个月");Button next=smallButton("›");next.setContentDescription("下个月");Button today=smallButton("今天");
        controls.addView(previous,new LinearLayout.LayoutParams(dp(48),dp(48)));calendarCaption=text("",17,INK,true);calendarCaption.setGravity(Gravity.CENTER);controls.addView(calendarCaption,new LinearLayout.LayoutParams(0,dp(48),1));controls.addView(next,new LinearLayout.LayoutParams(dp(48),dp(48)));gapHorizontal(controls,4);controls.addView(today);body.addView(controls);
        calendarGrid=column();body.addView(calendarGrid);gap(body,6);
        LinearLayout legendTop=row(),legendBottom=row();legendTop.addView(text("● 待提醒 / 已提醒",12,INFO,false),new LinearLayout.LayoutParams(0,-2,1));legendTop.addView(text("● 已完成",12,GREEN,false),new LinearLayout.LayoutParams(0,-2,1));legendBottom.addView(text("● 未完成",12,YELLOW,false),new LinearLayout.LayoutParams(0,-2,1));legendBottom.addView(text("● 已过期",12,RED,false),new LinearLayout.LayoutParams(0,-2,1));body.addView(legendTop);gap(body,5);body.addView(legendBottom);gap(body,8);body.addView(text("到点未完成为黄色，周期结束仍未完成为红色。",11,MUTED,false));gap(body,4);body.addView(text("按手机时区显示 · "+ZoneId.systemDefault().getId(),11,MUTED,false));gap(body,14);
        calendarRecords=column();body.addView(calendarRecords);
        previous.setOnClickListener(v->changeMonth(-1));next.setOnClickListener(v->changeMonth(1));today.setOnClickListener(v->{plannedTaskRequest++;YearMonth previousMonth=calendarMonth;calendarDate=LocalDate.now();calendarMonth=YearMonth.from(calendarDate);recordPage=0;if(!calendarMonth.equals(previousMonth))loadCalendar(null);else drawCalendar();});
        calendarRefresh.setOnClickListener(v->loadCalendar(null));drawCalendar();loadCalendar(null);
    }
    private void changeMonth(int delta){calendarMonth=calendarMonth.plusMonths(delta);calendarDate=calendarMonth.atDay(Math.min(calendarDate.getDayOfMonth(),calendarMonth.lengthOfMonth()));recordPage=0;loadCalendar(null);}
    private String calendarKey(){return calendarMonth+"|"+ZoneId.systemDefault().getId();}
    private boolean calendarLoaded(){return calendarLoadedKey.equals(calendarKey());}
    private void invalidateCalendar(){calendarRequests.invalidate();plannedTaskRequest++;calendarData=new JSONArray();calendarLoadedKey="";calendarNotice="";calendarTruncated=false;calendarLoading=false;gardenRequest++;gardenTaskRequest++;gardenData=null;gardenLoading=false;gardenNotice="";}
    private void loadCalendar(Button trigger){
        plannedTaskRequest++;
        String month=calendarMonth.toString(),zone=ZoneId.systemDefault().getId(),key=calendarKey();int request=calendarRequests.begin(month,zone);
        if(!calendarLoaded()){calendarData=new JSONArray();calendarLoadedKey="";calendarTruncated=false;}
        calendarLoading=true;calendarNotice="";calendarRefresh.setEnabled(false);drawCalendar();
        run(null,a->{JSONObject result=a.json("GET","/api/v1/calendar?month="+month+"&timezone="+Uri.encode(zone),null);if(!month.equals(result.optString("month"))||!zone.equals(result.optString("timezone"))||result.optJSONArray("entries")==null)throw new IOException("服务器返回的日历不完整，请更新后端后重试");return result;},result->{
            if(!calendarRequests.accepts(request,calendarMonth.toString(),ZoneId.systemDefault().getId()))return;
            calendarData=result.optJSONArray("entries");calendarLoadedKey=key;calendarTruncated=result.optBoolean("truncated");calendarLoading=false;calendarRefresh.setEnabled(true);drawCalendar();
        },failure->{
            if(!calendarRequests.accepts(request,calendarMonth.toString(),ZoneId.systemDefault().getId()))return;
            calendarLoading=false;calendarRefresh.setEnabled(true);calendarNotice=failure instanceof Api.Failure&&(((Api.Failure)failure).status==404||((Api.Failure)failure).status==405)?"请更新后端以查看未来安排":failure.getMessage()==null?"这个月的安排读取失败，请刷新重试":failure.getMessage();drawCalendar();
        });
    }
    private List<JSONObject> calendarEntries(){
        List<JSONObject> result=new ArrayList<>();if(!calendarLoaded())return result;
        for(int i=0;i<calendarData.length();i++){JSONObject raw=calendarData.optJSONObject(i);if(raw==null)continue;JSONObject entry=copy(raw);try{entry.put("_type",raw.optString("type"));entry.put("_at",raw.optString("at"));}catch(JSONException ignored){}result.add(entry);}return result;
    }
    private void drawCalendar(){
        if(calendarGrid==null||calendarRecords==null)return;calendarCaption.setText(calendarMonth.getYear()+" 年 "+calendarMonth.getMonthValue()+" 月");calendarGrid.removeAllViews();
        Map<LocalDate,int[]> marks=new HashMap<>();List<JSONObject> chosen=new ArrayList<>();int invalid=0;ZoneId zone=ZoneId.systemDefault();
        for(JSONObject entry:calendarEntries()){LocalDate day=RecordDates.localDate(entry.optString("_at"),zone);if(day==null){invalid++;continue;}int[] counts=marks.computeIfAbsent(day,k->new int[4]);counts[calendarStateIndex(entry)]++;if(day.equals(calendarDate))chosen.add(entry);}
        LinearLayout weekdays=row();for(String day:new String[]{"一","二","三","四","五","六","日"}){TextView label=text(day,12,MUTED,false);label.setGravity(Gravity.CENTER);weekdays.addView(label,new LinearLayout.LayoutParams(0,dp(32),1));}calendarGrid.addView(weekdays);
        int blanks=RecordDates.mondayLeadingBlanks(calendarMonth),days=RecordDates.daysInMonth(calendarMonth),count=((blanks+days+6)/7)*7;LinearLayout week=null;
        for(int cell=0;cell<count;cell++){if(cell%7==0){week=row();calendarGrid.addView(week);}int day=cell-blanks+1;LinearLayout dateCell=column();dateCell.setGravity(Gravity.CENTER);LinearLayout.LayoutParams cellSize=new LinearLayout.LayoutParams(0,dp(48),1);cellSize.setMargins(dp(1),dp(2),dp(1),dp(2));week.addView(dateCell,cellSize);if(day<1||day>days)continue;
            LocalDate date=calendarMonth.atDay(day);boolean chosenDate=date.equals(calendarDate);int[] counts=marks.getOrDefault(date,new int[4]);GradientDrawable tile=shape(chosenDate?PALE:BG,12);if(chosenDate||date.equals(LocalDate.now()))tile.setStroke(dp(chosenDate?2:1),BLUE);dateCell.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22527a58),tile,null));TextView dayText=text(String.valueOf(day),14,chosenDate?BLUE:INK,chosenDate);dayText.setGravity(Gravity.CENTER);dateCell.addView(dayText);LinearLayout dots=row();dots.setGravity(Gravity.CENTER);int[] colors={INFO,GREEN,YELLOW,RED};for(int state=0;state<4;state++)if(counts[state]>0){if(dots.getChildCount()>0)gapHorizontal(dots,3);dots.addView(text("●",8,colors[state],false));}dateCell.addView(dots,new LinearLayout.LayoutParams(-1,dp(12)));dateCell.setContentDescription(date+"，待提醒或已提醒 "+counts[0]+" 条，已完成 "+counts[1]+" 条，未完成 "+counts[2]+" 条，已过期 "+counts[3]+" 条"+(chosenDate?"，已选择":""));dateCell.setSelected(chosenDate);dateCell.setOnClickListener(v->{plannedTaskRequest++;calendarDate=date;recordPage=0;drawCalendar();});
        }
        chosen.sort((a,b)->{Instant first=RecordDates.parseInstant(a.optString("_at")),second=RecordDates.parseInstant(b.optString("_at"));int order=second.compareTo(first);return order!=0?order:a.optString("id").compareTo(b.optString("id"));});drawCalendarRecords(chosen,invalid);
    }
    private void drawCalendarRecords(List<JSONObject> entries,int invalid){
        calendarRecords.removeAllViews();LinearLayout heading=row();heading.addView(text(calendarDate.format(DateTimeFormatter.ofPattern("M 月 d 日 EEEE",Locale.CHINA)),15,INK,true),new LinearLayout.LayoutParams(0,-2,1));heading.addView(text(entries.size()+" 条",12,MUTED,false));calendarRecords.addView(heading);gap(calendarRecords,10);
        if(calendarLoading)note(calendarRecords,calendarLoaded()?"正在更新这个月的安排…":"正在读取这个月的安排…");
        if(!calendarNotice.isEmpty()){TextView notice=text(calendarNotice,13,RED,false);calendarRecords.addView(notice);gap(calendarRecords,8);if(calendarLoaded())note(calendarRecords,"下方是上次读取的安排，状态可能已变化。");}
        if(calendarTruncated)note(calendarRecords,"这个月的安排较多，目前只显示部分内容。");
        if(invalid>0)note(calendarRecords,invalid+" 条记录的时间无法识别，未归入日历。");
        if(entries.isEmpty()){if(calendarLoading||!calendarNotice.isEmpty())return;TextView empty=text(calendarLoaded()?calendarTruncated?"当前结果中没有这一天的安排，整月数据尚未显示完整。":"这一天没有提醒安排或完成记录。":"点击刷新，读取这个月的安排。",13,MUTED,false);empty.setPadding(0,dp(16),0,dp(24));calendarRecords.addView(empty);return;}
        int pageCount=(entries.size()+19)/20;recordPage=Math.max(0,Math.min(recordPage,pageCount-1));
        for(int i=recordPage*20;i<Math.min(entries.size(),(recordPage+1)*20);i++){JSONObject entry=entries.get(i);boolean reminder=entry.optString("_type").equals("notification");LinearLayout card=card(calendarRecords),top=row();TextView time=text(RecordDates.localTime(entry.optString("_at"),ZoneId.systemDefault()),12,MUTED,true);top.addView(time,new LinearLayout.LayoutParams(dp(52),-2));TextView title=text(entry.optString("task_title","已归档的提醒"),15,INK,true);title.setMaxLines(2);title.setEllipsize(TextUtils.TruncateAt.END);top.addView(title,new LinearLayout.LayoutParams(0,-2,1));gapHorizontal(top,6);top.addView(text("›",20,MUTED,false));card.addView(top);gap(card,5);
            boolean planned=entry.optString("_type").equals("planned");String summary="● "+calendarStateLabel(entry)+(planned?" · 计划安排":reminder?" · 提醒记录":" · 完成记录");TextView subtitle=text(summary,12,calendarStateColor(entry),false);card.addView(subtitle);
            if(reminder){gap(card,3);card.addView(text(notificationLabel(entry),12,MUTED,false));}
            if(!planned&&!reminder){String message=entry.optString("text");JSONArray photos=entry.optJSONArray("media_ids");String details=message.replace('\n',' ');if(photos!=null&&photos.length()>0)details+=(details.isEmpty()?"":" · ")+photos.length()+" 张照片";if(!details.isEmpty()){TextView note=text(details,12,MUTED,false);note.setMaxLines(2);note.setEllipsize(TextUtils.TruncateAt.END);gap(card,3);card.addView(note);}}
            if(planned){card.setOnClickListener(v->{String taskId=entry.optString("task_id"),monthKey=calendarKey();LocalDate day=calendarDate;int request=++plannedTaskRequest;card.setEnabled(false);run(null,a->a.json("GET","/api/v1/tasks/"+taskId,null),result->{card.setEnabled(true);if(request!=plannedTaskRequest||!monthKey.equals(calendarKey())||!day.equals(calendarDate))return;selected=result;detailBack="history";navigate("detail");},failure->{card.setEnabled(true);if(request!=plannedTaskRequest||!monthKey.equals(calendarKey())||!day.equals(calendarDate))return;showError(failure.getMessage()==null?"无法读取这条提醒，请重试":failure.getMessage());});});}else card.setOnClickListener(v->{selectedRecord=entry;recordBack="history";navigate("record");});
        }
        if(pageCount>1){LinearLayout paging=row();Button previous=smallButton("上一页"),next=smallButton("下一页");previous.setEnabled(recordPage>0);next.setEnabled(recordPage+1<pageCount);paging.addView(previous);TextView count=text((recordPage+1)+" / "+pageCount,12,MUTED,false);count.setGravity(Gravity.CENTER);paging.addView(count,new LinearLayout.LayoutParams(0,-2,1));paging.addView(next);calendarRecords.addView(paging);previous.setOnClickListener(v->{recordPage--;drawCalendarRecords(entries,invalid);});next.setOnClickListener(v->{recordPage++;drawCalendarRecords(entries,invalid);});}
    }
    private int calendarStateIndex(JSONObject entry){switch(entry.optString("state")){case "completed":return 1;case "pending":return 2;case "expired":return 3;default:return 0;}}
    private int calendarStateColor(JSONObject entry){return new int[]{INFO,GREEN,YELLOW,RED}[calendarStateIndex(entry)];}
    private String calendarStateLabel(JSONObject entry){switch(entry.optString("state")){case "completed":return "已完成";case "pending":return "未完成";case "expired":return "已过期";case "notified":return "已提醒";default:return "待提醒";}}
    private String notificationLabel(JSONObject item){switch(item.optString("status")){case "sent":return "邮件已发送";case "failed":return "邮件发送失败";case "pending":case "sending":return "邮件等待发送";default:return "邮件状态："+item.optString("status","未知");}}
    private void renderRecord(){
        boolean reminder=selectedRecord.optString("_type").equals("notification");header(reminder?"提醒记录":"完成记录",selectedRecord.optString("task_title","提醒详情"),formatFull(selectedRecord.optString("_at")));
        if(selectedRecord.has("state")){body.addView(text("● "+calendarStateLabel(selectedRecord),13,calendarStateColor(selectedRecord),true));gap(body,12);}
        if(reminder){LinearLayout info=card(body);info.addView(text(notificationLabel(selectedRecord),15,selectedRecord.optString("status").equals("failed")?RED:BLUE,true));gap(info,8);info.addView(text("计划提醒："+formatFull(selectedRecord.optString("scheduled_at")),13,MUTED,false));if(!selectedRecord.optString("sent_at").isEmpty()){gap(info,5);info.addView(text("实际发送："+formatFull(selectedRecord.optString("sent_at")),13,MUTED,false));}if(!selectedRecord.optString("error").isEmpty()){gap(info,8);info.addView(text(selectedRecord.optString("error"),13,RED,false));}if(selectedRecord.optString("status").equals("sent")){gap(info,8);info.addView(text("服务器已发出邮件，收件情况请以邮箱为准。",12,MUTED,false));}}
        else {String message=selectedRecord.optString("text");if(!message.isEmpty()){TextView note=text(message,14,INK,false);note.setTextIsSelectable(true);body.addView(note);gap(body,14);}else note(body,"这次完成没有填写文字备注。");JSONArray photos=selectedRecord.optJSONArray("media_ids");if(photos!=null)for(int i=0;i<photos.length();i++){String id=photos.optString(i);ImageView image=new ImageView(this);image.setContentDescription("完成凭证照片，点击查看大图");image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setBackground(shape(PALE,12));body.addView(image,new LinearLayout.LayoutParams(-1,dp(200)));loadImage(image,id);image.setOnClickListener(v->{photoId=id;photoTitle=selectedRecord.optString("task_title");photoBack="record";navigate("photo");});gap(body,10);}}
        Button task=button("查看这条提醒",false);body.addView(task);String taskId=selectedRecord.optString("task_id");if(taskId.isEmpty()){task.setEnabled(false);gap(body,8);note(body,"这条记录没有关联的提醒编号，记录本身仍可查看。更新服务器后可关联提醒详情。");return;}
        task.setOnClickListener(v->run(task,a->{try{return a.json("GET","/api/v1/tasks/"+taskId,null);}catch(Api.Failure failure){if(failure.status==404)return object("_missing",true);throw failure;}},result->{if(result.optBoolean("_missing")){showError("暂时无法读取这条提醒。记录和照片仍保留在这里；已归档详情需要新版服务器。");return;}selected=result;detailBack="record";navigate("detail");}));
    }
    private void renderPhoto(){header("完成凭证",photoTitle,"");ImageView photo=new ImageView(this);photo.setAdjustViewBounds(true);photo.setScaleType(ImageView.ScaleType.FIT_CENTER);photo.setMinimumHeight(dp(300));body.addView(photo,new LinearLayout.LayoutParams(-1,-2));loadImage(photo,photoId);}
    private void renderSettings(){
        LinearLayout hello=row();hello.addView(art(R.drawable.garden_bloom,74));gapHorizontal(hello,12);LinearLayout words=column();words.addView(text("我的小天地",24,INK,true));gap(words,5);words.addView(text("照料生活，也照顾自己。",13,MUTED,false));hello.addView(words,new LinearLayout.LayoutParams(0,-2,1));body.addView(hello);gap(body,18);
        LinearLayout account=card(body);account.addView(text("接收提醒的邮箱",13,MUTED,false));gap(account,8);TextView email=text(session.email,19,INK,true);email.setTextIsSelectable(true);account.addView(email);gap(account,12);account.addView(text("登录状态已安全保存在这台手机上。",13,MUTED,false));
        section(body,"连接与数据");label(body,"当前服务器");TextView address=text(session.base,15,INK,false);address.setTextIsSelectable(true);body.addView(address);gap(body,12);
        Button change=button("更换服务器地址",false);body.addView(change);change.setOnClickListener(v->navigate("server"));gap(body,12);
        Button check=button("检查连接和登录状态",false);body.addView(check);check.setOnClickListener(v->run(check,a->{a.json("GET","/health",null);return a.json("GET","/api/v1/me",null);},r->toast("连接正常，当前登录："+r.optString("email"))));gap(body,12);
        Button password=button(session.hasCredentials()?"修改登录密码":"设置密码，以后自动登录",false);body.addView(password);password.setOnClickListener(v->{authEmail=session.email;openAuth(session.hasCredentials()?"reset":"register");});gap(body,12);
        Button export=button("导出我的全部文字记录",false);body.addView(export);export.setOnClickListener(v->run(export,a->{JSONObject data=a.json("GET","/api/v1/export",null);File dir=new File(getCacheDir(),"exports");if(!dir.exists()&&!dir.mkdirs())throw new IOException("无法创建导出文件");File file=new File(dir,"念念记录-"+LocalDate.now()+"-"+UUID.randomUUID()+".json");try(OutputStream out=new FileOutputStream(file)){out.write(data.toString(2).getBytes(StandardCharsets.UTF_8));}return file;},file->{Uri uri=FileProvider.getUriForFile(this,getPackageName()+".files",file);Intent i=new Intent(Intent.ACTION_SEND);i.setType("application/json");i.putExtra(Intent.EXTRA_STREAM,uri);i.setClipData(ClipData.newRawUri("念念记录",uri));i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(Intent.createChooser(i,"保存或分享记录"));}));gap(body,12);
        note(body,"导出包含提醒规则、完成文字和邮件记录。照片原文件保存在服务器的数据目录，完整备份时请一起保存该目录。");
        section(body,"关于念念");body.addView(text("念念 1.4.0 · 小事生长的地方",15,INK,true));gap(body,8);note(body,"创建任务就是播种，真实完成才会增加养分。已经拥有的成长，不会因为漏做而消失。邮件按时发送，手机无需后台常驻。");
        Button logout=button("退出登录",false);logout.setTextColor(RED);body.addView(logout);logout.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("退出当前账号？").setMessage("服务器上的提醒仍会照常发送，任务和记录不会删除。").setNegativeButton("取消",null).setPositiveButton("退出",(d,w)->{
            Api old=new Api(session.base,session.token);try{session.logout();}catch(Exception e){showError("退出登录未保存，请重试");return;}accountEpoch++;clearAccountData();homeNotice="已退出登录。账号信息仍记在这台手机上，点登录即可继续。";navigate("home");workers.submit(()->{try{old.json("POST","/api/v1/auth/logout",object());}catch(Exception ignored){}});
        }).show());
    }
    private void clearAccountData(){growthAnimationState.clear();gardenPage=0;gardenSelectedPlantId="";gardenMemoriesExpanded=false;gardenContent=null;gardenPreview=null;recordBack="history";completedRecord=new JSONObject();taskData=new JSONArray();taskLoadRequest++;pauseInFlight.clear();selected=new JSONObject();draft=new JSONObject();selectedRecord=new JSONObject();invalidateCalendar();calendarDate=LocalDate.now();calendarMonth=YearMonth.now();recordPage=0;detailBack="home";photoBack="record";photoId="";photoTitle="";calendarGrid=null;calendarRecords=null;calendarCaption=null;calendarRefresh=null;restoredForm=null;photoFile=null;cameraFile=null;search="";filter="全部";aiText="";aiWarning="";completionText="";taskList=null;manualVisible=false;rulesExpanded=false;advancedExpanded=false;aiExpanded=true;}
    private interface Job<T>{T run(Api api)throws Exception;}
    private interface Result<T>{void accept(T value);}
    private <T> void run(Button trigger,Job<T> job,Result<T> success){
        run(trigger,job,success,null);
    }
    private <T> void run(Button trigger,Job<T> job,Result<T> success,Result<Exception> failure){
        int account=accountEpoch,page=pageEpoch;Api api=new Api(session.base,session.token);CharSequence caption=trigger==null?"":trigger.getText();if(trigger!=null){trigger.setEnabled(false);trigger.setText("请稍等…");}errorView.setVisibility(View.GONE);
        workers.submit(()->{try{T result=job.run(api);ui.post(()->{if(isDestroyed()||account!=accountEpoch||page!=pageEpoch)return;if(trigger!=null){trigger.setEnabled(true);trigger.setText(caption);}success.accept(result);});}
            catch(Exception e){ui.post(()->{if(isDestroyed()||account!=accountEpoch||page!=pageEpoch)return;taskBusy=false;if(trigger!=null){trigger.setEnabled(true);trigger.setText(caption);}if(e instanceof Api.Failure&&((Api.Failure)e).status==401&&!session.token.isEmpty()){loginExpired();}else if(failure!=null)failure.accept(e);else showError(e.getMessage()==null?"操作未完成，请重试":e.getMessage());});}
        });
    }
    private void loadImage(ImageView target,String id){
        int a=accountEpoch,p=pageEpoch,maxSize=screen.equals("photo")?1600:640;Api api=new Api(session.base,session.token);workers.submit(()->{try{byte[] bytes=api.media(id);BitmapFactory.Options opt=new BitmapFactory.Options();opt.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,opt);opt.inSampleSize=1;while(Math.max(opt.outWidth,opt.outHeight)/opt.inSampleSize>maxSize)opt.inSampleSize*=2;opt.inJustDecodeBounds=false;Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length,opt);ui.post(()->{if(!isDestroyed()&&accountEpoch==a&&pageEpoch==p&&target.isAttachedToWindow()){target.setImageBitmap(bitmap);}else if(bitmap!=null)bitmap.recycle();});}catch(Exception e){ui.post(()->{if(!isDestroyed()&&accountEpoch==a&&pageEpoch==p){target.setContentDescription("照片加载失败，点击重试");target.setImageResource(android.R.drawable.ic_menu_report_image);target.setOnClickListener(v->loadImage(target,id));}});}});
    }
    private void loadLocalImage(ImageView target,File file){int p=pageEpoch;workers.submit(()->{Bitmap bitmap=BitmapFactory.decodeFile(file.getAbsolutePath());ui.post(()->{if(!isDestroyed()&&pageEpoch==p)target.setImageBitmap(bitmap);else if(bitmap!=null)bitmap.recycle();});});}
    private void bottomNav(){
        View divider=new View(this);divider.setBackgroundColor(LINE);root.addView(divider,new LinearLayout.LayoutParams(-1,dp(1)));
        LinearLayout bar=row();bar.setBaselineAligned(false);bar.setPadding(dp(8),dp(5),dp(8),dp(7));bar.setBackgroundColor(PAPER);
        String[] pages={"home","garden","history","settings"},labels={"今天","花园","日历","我的"},icons={"today","garden","history","settings"};
        for(int i=0;i<4;i++){
            final String page=pages[i];boolean active=screen.equals(page);LinearLayout nav=column();nav.setGravity(Gravity.CENTER);nav.setBaselineAligned(false);nav.setPadding(0,0,0,0);
            FrameLayout plate=new FrameLayout(this);plate.setBackground(shape(active?PALE:Color.TRANSPARENT,15));GardenIconView icon=new GardenIconView(this,icons[i],active?BLUE:MUTED);plate.addView(icon,new FrameLayout.LayoutParams(dp(24),dp(24),Gravity.CENTER));
            LinearLayout.LayoutParams iconBox=new LinearLayout.LayoutParams(dp(56),dp(32));iconBox.gravity=Gravity.CENTER_HORIZONTAL;nav.addView(plate,iconBox);gap(nav,4);
            TextView caption=text(labels[i],11,active?BLUE:MUTED,active);caption.setGravity(Gravity.CENTER);caption.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);caption.setIncludeFontPadding(false);caption.setLineSpacing(0,1);caption.setSingleLine(true);caption.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);nav.addView(caption,new LinearLayout.LayoutParams(-1,-2));
            nav.setBackground(new RippleDrawable(ColorStateList.valueOf(0x18527a58),shape(Color.TRANSPARENT,18),null));nav.setContentDescription(labels[i]);nav.setSelected(active);nav.setFocusable(true);nav.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);bar.addView(nav,new LinearLayout.LayoutParams(0,dp(62),1));nav.setOnClickListener(v->{if(!screen.equals(page))navigate(page);});
        }
        root.addView(bar);
    }
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private LinearLayout card(LinearLayout parent){LinearLayout c=column();c.setPadding(dp(16),dp(14),dp(16),dp(14));GradientDrawable surface=shape(PAPER,22);surface.setStroke(dp(1),LINE);c.setBackground(surface);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,0,0,dp(12));parent.addView(c,lp);return c;}
    private TextView text(String value,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setLineSpacing(dp(2),1f);if(bold)t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));return t;}
    private Button button(String caption,boolean primary){Button b=new Button(this);b.setAllCaps(false);b.setText(caption);b.setTextSize(14);b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));b.setTextColor(primary?PAPER:BLUE);b.setMinHeight(dp(48));b.setPadding(dp(12),dp(8),dp(12),dp(8));b.setElevation(0);b.setStateListAnimator(null);b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22527a58),shape(primary?BLUE:PALE,18),null));b.setLayoutParams(new LinearLayout.LayoutParams(-1,-2));return b;}
    private Button smallButton(String caption){Button b=button(caption,false);b.setTextSize(13);b.setMinHeight(dp(48));b.setMinWidth(dp(48));b.setPadding(dp(10),dp(6),dp(10),dp(6));b.setLayoutParams(new LinearLayout.LayoutParams(-2,dp(48)));return b;}
    private GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private EditText field(LinearLayout parent,String key,String caption,String content,String hint,int lines){label(parent,caption);EditText e=new EditText(this);styleField(e);e.setHint(hint);e.setText(content);e.setGravity(Gravity.TOP|Gravity.START);e.setMinLines(lines);e.setMaxLines(lines==1?1:7);e.setSingleLine(lines==1);e.setInputType(android.text.InputType.TYPE_CLASS_TEXT|(lines>1?android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE:0));fields.put(key,e);parent.addView(e,new LinearLayout.LayoutParams(-1,-2));gap(parent,10);return e;}
    private void styleField(EditText e){GradientDrawable bg=shape(PAPER,14);bg.setStroke(dp(1),LINE);e.setBackground(bg);e.setTextColor(INK);e.setHintTextColor(0xff7b887b);e.setTextSize(14);e.setPadding(dp(12),dp(10),dp(12),dp(10));e.setMinHeight(dp(48));e.setSelectAllOnFocus(false);}
    private void label(LinearLayout parent,String title){parent.addView(text(title,12,MUTED,true));gap(parent,5);}
    private void section(LinearLayout parent,String title){gap(parent,8);parent.addView(text(title,16,INK,true));gap(parent,12);}
    private void note(LinearLayout parent,String message){parent.addView(text(message,12,MUTED,false));gap(parent,10);}
    private void gap(LinearLayout parent,int height){Space s=new Space(this);parent.addView(s,new LinearLayout.LayoutParams(1,dp(height)));}
    private void gapHorizontal(LinearLayout parent,int width){Space s=new Space(this);parent.addView(s,new LinearLayout.LayoutParams(dp(width),1));}
    private void empty(LinearLayout parent,String title,String description){LinearLayout box=column();box.setGravity(Gravity.CENTER_HORIZONTAL);box.setPadding(dp(12),dp(18),dp(12),dp(20));box.addView(art(R.drawable.garden_seed,96));gap(box,12);TextView t=text(title,17,INK,true);t.setGravity(Gravity.CENTER);box.addView(t);gap(box,8);TextView hint=text(description,13,MUTED,false);hint.setGravity(Gravity.CENTER);box.addView(hint);parent.addView(box);}
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
