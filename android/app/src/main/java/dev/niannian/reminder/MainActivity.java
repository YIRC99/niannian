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
            BG = 0xfffffcf5, PAPER = 0xfffffdf8, PALE = 0xffe8efe0, LINE = 0xffdfe5d7,
            RED = 0xffb65b55, GREEN = 0xff3d7c58, YELLOW = 0xff946b25, INFO = 0xff527eab;
    private static final int HOME_TITLE = 0xff173d29, HOME_SEED = 0xff5b805b,
            HOME_WOOD_TEXT = 0xff5b4730, HOME_ENCOURAGEMENT = 0xff818570;
    private static final String[] KINDS = {"once", "daily", "weekly", "monthly_window", "date_range", "interval", "ongoing", "minute_interval", "unsupported"};
    private static final String[] KIND_LABELS = {"只在一天提醒", "每天", "每周指定星期", "每月一段时间", "指定日期范围", "每隔几天", "每天提醒，做完结束", "每隔几分钟催一次", "请选择重复方式"};
    private static final String[] MODES = {"until_done", "every_day", "notify_once"};
    private static final String[] MODE_LABELS = {"本期完成一次就停止", "每天分别完成", "本期只通知一次"};
    private final ExecutorService workers = Executors.newFixedThreadPool(3);
    private final PhotoRequestQueue photoRequests = new PhotoRequestQueue();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Session session;
    private int accountEpoch = 0, pageEpoch = 0, taskLoadRequest = 0;
    private String screen = "home", filter = "待完成", search = "";
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
    private int selectedFlowerIndex = -1;
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
    private LinearLayout calendarGrid, calendarRecords, calendarFeedback;
    private TextView calendarCaption;
    private Button calendarRefresh;
    private JSONObject gardenData;
    private LinearLayout gardenContent, gardenPreview, gardenSelection;
    private boolean homeSearchExpanded;
    private int gardenRequest = 0, gardenPage = 0, gardenTaskRequest = 0;
    private String gardenNotice = "", recordBack = "history";
    private String gardenSelectedPlantId = "";
    private boolean gardenLoading = false, gardenMemoriesExpanded = false;
    private JSONObject completedRecord = new JSONObject();
    private final GrowthAnimationState growthAnimationState = new GrowthAnimationState();
    private GardenGrowthView growthView;
    private GardenPlotView gardenScene;
    private GardenDrawerLayout gardenDrawer;
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
            screen = state.getString("screen", "home"); filter = state.getString("filter", "待完成");
            search=state.getString("search","");homeSearchExpanded=state.getBoolean("homeSearchExpanded");
            try { selected = new JSONObject(state.getString("selected", "{}")); draft = new JSONObject(state.getString("draft", "{}")); restoredForm = new JSONObject(state.getString("form", "{}")); } catch (Exception ignored) {}
            editing = state.getBoolean("editing"); manualVisible = state.getBoolean("manual");
            selectedFlowerIndex=state.getInt("selectedFlowerIndex",-1);
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
        out.putString("search",search);out.putBoolean("homeSearchExpanded",homeSearchExpanded);
        out.putBoolean("editing", editing); out.putBoolean("manual", manualVisible);
        out.putInt("selectedFlowerIndex",selectedFlowerIndex);
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
    @Override public void onDestroy() { stopPageMotion(); super.onDestroy(); workers.shutdownNow(); photoRequests.close(); ui.removeCallbacksAndMessages(null); }
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
        if(screen.equals("garden")&&gardenMemoriesExpanded&&gardenDrawer!=null){gardenDrawer.setExpanded(false,true);return;}
        if (screen.equals("home") || (screen.equals("server") && session.base.isEmpty())) { super.onBackPressed(); return; }
        if (screen.equals("editor")) {
            if(taskBusy){toast("正在处理提醒，请稍等");return;}
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
        photoRequests.invalidate();
        GardenFlowers.configure(this,session.base,session.email);
        if(session.token.isEmpty()&&!Arrays.asList("home","login","server").contains(screen))screen="home";
        pageEpoch++; taskBusy = false; fields.clear(); kindPicker = null; modePicker = null; weekdays = null;
        boolean edgeDecoration=screen.equals("record")||screen.equals("photo");
        boolean fixedMail=isFixedMailRecord();
        boolean recordFooter=screen.equals("record")&&hasRecordFooter()&&!fixedMail;
        // Only decoration enters system-bar space; interactive content keeps its safe insets.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(),!edgeDecoration&&Build.VERSION.SDK_INT<35);
        getWindow().setStatusBarColor(edgeDecoration?Color.TRANSPARENT:BG);
        getWindow().setNavigationBarColor(edgeDecoration?Color.TRANSPARENT:BG);
        if(Build.VERSION.SDK_INT>=29)getWindow().setNavigationBarContrastEnforced(!edgeDecoration);
        root = column();root.setBackgroundColor(edgeDecoration?Color.TRANSPARENT:BG);
        if(edgeDecoration){
            FrameLayout page=new FrameLayout(this);page.setBackgroundColor(BG);
            if(fixedMail){
                ImageView grass=new ImageView(this);
                grass.setImageBitmap(GardenArtCache.backdrop(getResources(),R.drawable.sowing_footer_foliage));
                grass.setAdjustViewBounds(true);grass.setScaleType(ImageView.ScaleType.FIT_CENTER);
                grass.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                page.addView(grass,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
            }
            ImageView leaves=new ImageView(this);
            leaves.setImageBitmap(GardenArtCache.backdrop(getResources(),R.drawable.sowing_corner_leaves));
            leaves.setScaleType(ImageView.ScaleType.FIT_START);leaves.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            FrameLayout.LayoutParams leavesLp=new FrameLayout.LayoutParams(dp(196),dp(131),Gravity.TOP|Gravity.END);
            if(fixedMail)leavesLp.topMargin=dp(31); // About 100 physical pixels on the user's 520dpi phone.
            page.addView(leaves,leavesLp);
            page.addView(root,new FrameLayout.LayoutParams(-1,-1));setContentView(page);
        }else setContentView(root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if(Build.VERSION.SDK_INT>=30&&(edgeDecoration||Build.VERSION.SDK_INT>=35)){
                android.graphics.Insets i=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()|WindowInsets.Type.ime());
                v.setPadding(i.left,i.top,i.right,recordFooter&&!insets.isVisible(WindowInsets.Type.ime())?0:i.bottom);
            }else if(edgeDecoration){
                int bottom=insets.getSystemWindowInsetBottom();
                v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),recordFooter&&bottom<dp(100)?0:bottom);
            }
            return insets;
        });
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false); scroll.setVerticalScrollBarEnabled(false);
        if(screen.equals("editor")){
            FrameLayout page=new FrameLayout(this);root.addView(page,new LinearLayout.LayoutParams(-1,0,1));
            ImageView leaves=new ImageView(this);leaves.setImageBitmap(GardenArtCache.load(getResources(),R.drawable.sowing_corner_leaves));leaves.setScaleType(ImageView.ScaleType.FIT_END);leaves.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            page.addView(leaves,new FrameLayout.LayoutParams(dp(160),dp(108),Gravity.TOP|Gravity.END));page.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        }else if(!fixedMail&&!screen.equals("garden"))root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        body = column(); body.setPadding(dp(16), dp(12), dp(16), dp(20));
        if(recordFooter){body.setPadding(dp(16),dp(12),dp(16),0);body.setClipToPadding(false);}
        if(screen.equals("editor"))body.setPadding(dp(16),dp(4),dp(16),0);
        if((screen.equals("home")&&!session.token.isEmpty())||screen.equals("garden")||screen.equals("history"))body.setPadding(0,0,0,0);
        if(fixedMail||screen.equals("garden"))root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        else scroll.addView(body,new ScrollView.LayoutParams(-1,-2));
        gardenContent=null; gardenPreview=null; gardenSelection=null; gardenDrawer=null;
        if (!Arrays.asList("home", "garden", "history", "settings", "login", "server").contains(screen)) {
            if(screen.equals("editor")||screen.equals("record")||screen.equals("photo")){
                LinearLayout back=row();back.setMinimumHeight(dp(48));back.addView(new GardenIconView(this,"back",INK),new LinearLayout.LayoutParams(dp(20),dp(20)));gapHorizontal(back,5);back.addView(text("返回",14,INK,false));back.setContentDescription("返回");back.setFocusable(true);back.setOnClickListener(v->onBackPressed());body.addView(back,new LinearLayout.LayoutParams(dp(80),dp(48)));
            }else{
                TextView back = text("‹  返回", 15, BLUE, true); back.setMinHeight(dp(48)); back.setGravity(Gravity.CENTER_VERTICAL); back.setOnClickListener(v -> onBackPressed()); body.addView(back);
            }
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
        body.addView(art(R.drawable.garden_sprout_v3,96));gap(body,14);
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
        LinearLayout welcome=card(body);welcome.setGravity(Gravity.CENTER_HORIZONTAL);welcome.addView(art(R.drawable.garden_sprout_v3,140));gap(welcome,14);
        TextView caption=text(authBusy?"正在自动登录":"你的小花园，还在等你",18,INK,true);caption.setGravity(Gravity.CENTER);welcome.addView(caption);gap(welcome,10);
        TextView description=text(homeNotice.isEmpty()?"每一件认真完成的小事，都会留下成长。":homeNotice,14,homeNotice.isEmpty()?MUTED:RED,false);description.setGravity(Gravity.CENTER);welcome.addView(description);
        if(authBusy){gap(welcome,12);ProgressBar progress=new ProgressBar(this);welcome.addView(progress,new LinearLayout.LayoutParams(dp(28),dp(28)));}
        gap(body,8);Button login=button(authBusy?"改用手动登录":"登录我的花园",true);body.addView(login);login.setOnClickListener(v->openAuth("login"));gap(body,10);
        Button register=button("注册 / 首次设置密码",false);body.addView(register);register.setOnClickListener(v->openAuth("register"));gap(body,14);
        Button change=smallButton("更换服务器");body.addView(change);change.setOnClickListener(v->navigate("server"));
    }
    private void renderHome() {
        if(session.token.isEmpty()){renderGuestHome();return;}
        int heroHeight=Math.max(236,Math.min(304,Math.round(getResources().getConfiguration().screenWidthDp*.74f)));
        FrameLayout hero=new FrameLayout(this);
        hero.addView(scenery(R.drawable.garden_home_hero_v3),new FrameLayout.LayoutParams(-1,-1));
        View fade=new View(this);fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0x00fffcf5,BG}));
        hero.addView(fade,new FrameLayout.LayoutParams(-1,dp(64),Gravity.BOTTOM));
        LinearLayout heading=row(),titles=column();heading.setBaselineAligned(false);heading.setGravity(Gravity.TOP);
        TextView brand=homeTitle("念念",32);brand.setShadowLayer(dp(1),0,dp(1),0xbbfffdf8);titles.addView(brand);gap(titles,3);
        titles.addView(text(LocalDate.now().format(DateTimeFormatter.ofPattern("M 月 d 日  EEEE",Locale.CHINA)),12,HOME_TITLE,false));
        heading.addView(titles,new LinearLayout.LayoutParams(0,-2,1));
        Button create=homeSeedButton();
        heading.addView(create);create.setOnClickListener(v->openEditor(null));
        FrameLayout.LayoutParams headingLp=new FrameLayout.LayoutParams(-1,-2,Gravity.TOP);headingLp.setMargins(dp(22),dp(12),dp(16),0);hero.addView(heading,headingLp);
        gardenPreview=column();gardenPreview.setPadding(dp(4),dp(9),dp(4),dp(9));
        FrameLayout.LayoutParams previewLp=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.START);previewLp.setMargins(dp(12),0,dp(16),dp(32));hero.addView(gardenPreview,previewLp);
        body.addView(hero,new LinearLayout.LayoutParams(-1,dp(heroHeight)));drawGardenPreview();
        LinearLayout content=column();content.setPadding(dp(16),0,dp(16),dp(20));body.addView(content);
        LinearLayout titleRow=row();titleRow.setBaselineAligned(false);titleRow.addView(homeTitle("今天的小事",23),new LinearLayout.LayoutParams(0,-2,1));
        Button find=iconButton("search","搜索提醒"),refresh=iconButton("refresh","刷新提醒");titleRow.addView(find);titleRow.addView(refresh);content.addView(titleRow);
        EditText query=new EditText(this);styleField(query);query.setSingleLine(true);query.setText(search);query.setHint("搜索提醒标题或内容");
        query.setVisibility(homeSearchExpanded||!search.isEmpty()?View.VISIBLE:View.GONE);content.addView(query);
        find.setOnClickListener(v->{homeSearchExpanded=query.getVisibility()!=View.VISIBLE;query.setVisibility(homeSearchExpanded?View.VISIBLE:View.GONE);if(homeSearchExpanded){query.requestFocus();((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(query,InputMethodManager.SHOW_IMPLICIT);}else{query.setText("");hideKeyboard(query);}});
        LinearLayout tabs=row();List<Button> filters=new ArrayList<>();
        for(String name:new String[]{"待完成","全部","已完成","已暂停"}){
            Button tab=smallButton(name);tab.setTextSize(12);tab.setPadding(0,0,0,0);tab.setTag(name);filters.add(tab);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(48),1);if(filters.size()>1)lp.leftMargin=dp(4);tabs.addView(tab,lp);
            tab.setOnClickListener(v->{filter=name;for(Button item:filters)styleFilter(item,filter.equals(item.getTag()));drawTasks();});
            styleFilter(tab,filter.equals(name));
        }
        content.addView(tabs);gap(content,8);taskList=column();content.addView(taskList);
        query.addTextChangedListener(watcher(()->{search=query.getText().toString().trim();drawTasks();}));
        refresh.setOnClickListener(v->{loadTasks(refresh);loadGarden();});
        content.addView(homeEncouragement());
        drawTasks();loadTasks(refresh);loadGarden();
    }
    private TextView homeTitle(String caption,int size){
        TextView title=text(caption,size,HOME_TITLE,true);
        title.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));
        androidx.core.view.ViewCompat.setAccessibilityHeading(title,true);
        return title;
    }
    private Button homeSeedButton(){
        Button seed=smallButton("＋  播种");
        android.graphics.drawable.InsetDrawable capsule=new android.graphics.drawable.InsetDrawable(shape(HOME_SEED,18),0,dp(7),0,dp(7));
        seed.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33fffdf8),capsule,null));
        seed.setBackgroundTintList(null);
        // Background replacement can reset padding; keep the visible capsule wide and its touch area 48dp tall.
        seed.setPadding(dp(14),0,dp(14),0);seed.setMinimumWidth(dp(84));seed.setMinWidth(dp(84));
        seed.setTextColor(PAPER);seed.setTextSize(14);seed.setLetterSpacing(0f);seed.setIncludeFontPadding(false);
        seed.setSingleLine(true);seed.setGravity(Gravity.CENTER);seed.setContentDescription("播种，新建提醒");
        return seed;
    }
    private View homeEncouragement(){
        LinearLayout message=row();message.setBaselineAligned(false);message.setGravity(Gravity.CENTER);
        message.setPadding(dp(18),dp(22),dp(18),dp(14));
        ImageView left=new ImageView(this);left.setImageResource(R.drawable.garden_encouragement_leaves);
        left.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        message.addView(left,new LinearLayout.LayoutParams(dp(26),dp(36)));
        TextView copy=text("完成一件小事，花园就长大一点",12,HOME_ENCOURAGEMENT,false);
        copy.setGravity(Gravity.CENTER);copy.setTypeface(Typeface.create("serif",Typeface.NORMAL));copy.setLetterSpacing(.04f);
        copy.setPadding(dp(6),0,dp(6),0);
        copy.setMaxWidth(dp(Math.max(1,Math.min(250,getResources().getConfiguration().screenWidthDp-120))));
        message.addView(copy,new LinearLayout.LayoutParams(-2,-2));
        ImageView right=new ImageView(this);right.setImageResource(R.drawable.garden_encouragement_leaves);right.setScaleX(-1f);right.setRotation(-12f);
        right.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        message.addView(right,new LinearLayout.LayoutParams(dp(26),dp(36)));
        return message;
    }
    private void styleFilter(Button tab,boolean active){
        tab.setTextColor(active?INK:MUTED);tab.setSelected(active);
        android.graphics.drawable.InsetDrawable inset=new android.graphics.drawable.InsetDrawable(shape(active?0xffdcebd2:0xfff3f1e9,9),0,dp(8),0,dp(8));
        tab.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22527a58),inset,null));
    }
    private void hideKeyboard(View view){((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(view.getWindowToken(),0);view.clearFocus();}
    private Button iconButton(String kind,String label){
        GardenIconView symbol=new GardenIconView(this,kind,INK);int size=dp(22);
        symbol.measure(View.MeasureSpec.makeMeasureSpec(size,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(size,View.MeasureSpec.EXACTLY));symbol.layout(0,0,size,size);
        Button button=new Button(this){@Override protected void onDraw(Canvas canvas){super.onDraw(canvas);int saved=canvas.save();canvas.translate((getWidth()-size)/2f,(getHeight()-size)/2f);symbol.draw(canvas);canvas.restoreToCount(saved);}};
        button.setText("");button.setContentDescription(label);button.setMinimumWidth(dp(48));button.setMinimumHeight(dp(48));button.setPadding(0,0,0,0);button.setElevation(0);button.setStateListAnimator(null);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22527a58),shape(Color.TRANSPARENT,12),null));button.setLayoutParams(new LinearLayout.LayoutParams(dp(48),dp(48)));return button;
    }
    private ImageView scenery(int resource){
        ImageView image=new ImageView(this);image.setImageBitmap(GardenArtCache.backdrop(getResources(),resource));image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);return image;
    }
    private void loadTasks(Button trigger) {
        int request=++taskLoadRequest;
        if (taskData.length()==0) { taskList.removeAllViews(); taskList.addView(text("正在整理你的提醒…",14,MUTED,false)); }
        run(trigger, a -> a.json("GET","/api/v1/tasks",null), result->{if(request!=taskLoadRequest)return;taskData=result.optJSONArray("tasks"); if(taskData==null)taskData=new JSONArray();drawTasks();});
    }
    private void drawTasks() {
        if(taskList==null)return;taskList.removeAllViews();int visible=0;
        for(int i=0;i<taskData.length();i++){
            JSONObject task=taskData.optJSONObject(i);if(task==null)continue;String status=task.optString("status","pending");
            if(filter.equals("待完成")&&!status.equals("pending"))continue;
            if(filter.equals("已完成")&&!status.equals("done"))continue;
            if(filter.equals("已暂停")&&!status.equals("paused"))continue;
            if(!search.isEmpty()&&!(task.optString("title")+task.optString("description")).toLowerCase(Locale.ROOT).contains(search.toLowerCase(Locale.ROOT)))continue;
            visible++;LinearLayout card=card(taskList);card.setPadding(dp(12),dp(9),dp(8),dp(9));
            LinearLayout line=row(),copy=column();line.addView(art(plantResource(task.optString("id")),40));gapHorizontal(line,10);
            TextView title=text(task.optString("title"),15,INK,true);title.setMaxLines(2);title.setEllipsize(TextUtils.TruncateAt.END);copy.addView(title);gap(copy,5);
            String next=task.optString("next_reminder");String summary=task.optString("summary");
            TextView detail=text(next.isEmpty()?summary:formatTime(next)+(summary.isEmpty()?"":" · "+summary),12,MUTED,false);detail.setMaxLines(2);detail.setEllipsize(TextUtils.TruncateAt.END);copy.addView(detail);
            line.addView(copy,new LinearLayout.LayoutParams(0,-2,1));gapHorizontal(line,6);
            if(status.equals("pending")){
                Button done=smallButton("完成");done.setTextSize(12);done.setTextColor(YELLOW);
                done.setBackground(new android.graphics.drawable.InsetDrawable(shape(0xfffff0d9,12),0,dp(10),0,dp(10)));
                done.setContentDescription("完成"+task.optString("title"));line.addView(done);done.setOnClickListener(v->{selected=task;detailBack="home";openComplete();});
            }else{
                TextView badge=text(taskStatusLabel(task),10,statusColor(status),false);badge.setMaxWidth(dp(78));badge.setGravity(Gravity.CENTER);badge.setPadding(dp(6),dp(4),dp(6),dp(4));badge.setBackground(shape(status.equals("done")?PALE:0xfff0f0e9,9));line.addView(badge);
            }
            addReminderSwitch(line,task,true);card.addView(line);card.setContentDescription(task.optString("title")+"，"+taskStatusLabel(task)+"，"+summary+"，查看详情");
            card.setOnClickListener(v->{selected=task;detailBack="home";navigate("detail");});
        }
        if(visible==0){empty(taskList,taskData.length()==0?"从一颗小小的种子开始":"这里暂时没有提醒",taskData.length()==0?"把想做的小事种下来。每次完成，都是一份养分。":"换个筛选条件，或者播下一颗新种子。");if(taskData.length()==0){Button create=button("播下第一颗种子",true);taskList.addView(create);create.setOnClickListener(v->openEditor(null));}}
    }
    private void drawGardenPreview(){
        if(gardenPreview==null)return;gardenPreview.removeAllViews();
        String title=gardenData==null?"走进我的花园":"花园里，已种下 "+gardenData.optInt("total_plants")+" 株";
        if(!gardenNotice.isEmpty())title="花园暂未更新，点此查看";
        FrameLayout sign=new FrameLayout(this);
        sign.setBackground(new RippleDrawable(ColorStateList.valueOf(0x225b4730),getDrawable(R.drawable.garden_notice_board),getDrawable(R.drawable.garden_notice_board)));
        sign.setPadding(dp(14),dp(10),dp(14),dp(28));sign.setMinimumWidth(dp(164));sign.setMinimumHeight(dp(68));sign.setRotation(-5f);
        TextView caption=text(title,14,HOME_WOOD_TEXT,false);
        caption.setTypeface(getResources().getFont(R.font.garden_handwriting));caption.setLetterSpacing(.02f);caption.setGravity(Gravity.CENTER);
        caption.setMaxWidth(dp(204));caption.setMaxLines(2);caption.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        sign.addView(caption,new FrameLayout.LayoutParams(-1,-2,Gravity.CENTER));
        gardenPreview.addView(sign,new LinearLayout.LayoutParams(-2,-2));sign.setContentDescription(title+"，打开我的花园");sign.setFocusable(true);sign.setOnClickListener(v->navigate("garden"));
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
        gardenContent=column();body.addView(gardenContent,new LinearLayout.LayoutParams(-1,0,1));drawGarden();loadGarden();
    }
    private void drawGarden(){
        if(gardenContent==null)return;gardenTaskRequest++;
        photoRequests.invalidate();
        if(gardenScene!=null){gardenScene.setHostActive(false);gardenScene=null;}
        gardenContent.removeAllViews();
        JSONArray plants=gardenData==null?new JSONArray():gardenData.optJSONArray("plants");
        List<JSONObject> ordered=new ArrayList<>();int[] stages=new int[4];
        if(plants!=null)for(int i=0;i<plants.length();i++){JSONObject plant=plants.optJSONObject(i);if(plant!=null){ordered.add(plant);stages[stageIndex(plant.optString("stage"))]++;}}
        ordered.sort(Comparator.comparing(plant->plant.optString("task_id")));
        int total=ordered.size(),pages=Math.max(1,(total+11)/12);gardenPage=Math.max(0,Math.min(gardenPage,pages-1));
        int from=gardenPage*12,to=Math.min(total,from+12);List<JSONObject> bed=ordered.subList(from,to);
        JSONObject picked=null;for(JSONObject plant:bed)if(plant.optString("task_id").equals(gardenSelectedPlantId))picked=plant;
        if(picked==null&&!bed.isEmpty())picked=bed.get(0);gardenSelectedPlantId=picked==null?"":picked.optString("task_id");
        FrameLayout stage=new FrameLayout(this);GardenPlotView scene=new GardenPlotView(this);gardenScene=scene;
        scene.setPlants(bed);scene.setSelectedPlantId(gardenSelectedPlantId);scene.setHostActive(motionHostActive);
        stage.addView(scene,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout heading=column();heading.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView gardenTitle=text("我的花园",24,INK,true);gardenTitle.setGravity(Gravity.CENTER);heading.addView(gardenTitle);gap(heading,8);
        String census=gardenData==null?(gardenLoading?"正在打开你的花园…":"花园等待重新连接"):"已种 "+gardenData.optInt("total_plants")+" 株 · "+(gardenData.optBoolean("truncated")?"已加载中开花 ":"开花 ")+stages[3]+" 株";
        TextView censusView=text(census,12,INK,true);censusView.setGravity(Gravity.CENTER);heading.addView(censusView);
        FrameLayout.LayoutParams headingLp=new FrameLayout.LayoutParams(-1,-2,Gravity.TOP);headingLp.setMargins(dp(16),dp(46),dp(16),0);stage.addView(heading,headingLp);
        Button create=homeSeedButton();create.setOnClickListener(v->openEditor(null));FrameLayout.LayoutParams createLp=new FrameLayout.LayoutParams(-2,dp(48),Gravity.TOP|Gravity.END);createLp.setMargins(0,dp(6),dp(16),0);stage.addView(create,createLp);
        addGardenSign(stage);
        LinearLayout controls=column();controls.setPadding(dp(16),dp(8),dp(16),dp(4));GradientDrawable sheet=shape(PAPER,22);sheet.setCornerRadii(new float[]{dp(22),dp(22),dp(22),dp(22),0,0,0,0});controls.setBackground(sheet);
        LinearLayout drawerHeader=column();controls.addView(drawerHeader);
        FrameLayout grip=new FrameLayout(this);View handle=new View(this);handle.setBackground(shape(LINE,3));grip.addView(handle,new FrameLayout.LayoutParams(dp(36),dp(4),Gravity.CENTER));drawerHeader.addView(grip,new LinearLayout.LayoutParams(-1,dp(12)));grip.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        gardenSelection=column();drawerHeader.addView(gardenSelection,new LinearLayout.LayoutParams(-1,dp(64)));drawGardenSelection(picked);
        if(picked==null){TextView status=text(!gardenNotice.isEmpty()?"花园暂未更新，展开查看":gardenData==null?"正在打开你的花园…":"从一颗小小的种子开始",15,INK,true);status.setGravity(Gravity.CENTER_VERTICAL);gardenSelection.addView(status,new LinearLayout.LayoutParams(-1,-1));}
        scene.setOnPlantClickListener((plant,trigger)->{gardenTaskRequest++;gardenSelectedPlantId=plant.optString("task_id");scene.setSelectedPlantId(gardenSelectedPlantId);drawGardenSelection(plant);});
        if(gardenData!=null&&total==0){
            LinearLayout welcome=column();welcome.setGravity(Gravity.CENTER);welcome.setPadding(dp(24),0,dp(24),0);
            welcome.addView(art(R.drawable.sowing_seed_mascot,128));gap(welcome,12);
            FrameLayout.LayoutParams welcomeLp=new FrameLayout.LayoutParams(-1,-2,Gravity.CENTER);welcomeLp.topMargin=dp(90);stage.addView(welcome,welcomeLp);
        }
        LinearLayout paging=row();Button prev=iconButton("back","上一片花圃"),next=iconButton("chevron_right","下一片花圃");prev.setEnabled(gardenPage>0);next.setEnabled(gardenPage+1<pages);prev.setAlpha(gardenPage>0?1f:.35f);next.setAlpha(gardenPage+1<pages?1f:.35f);paging.addView(prev);
        Button picker=smallButton("第 "+(gardenPage+1)+" / "+pages+" 片  ⌄");picker.setTextColor(INK);picker.setBackground(shape(Color.TRANSPARENT,16));picker.setContentDescription("选择花圃，当前第 "+(gardenPage+1)+" 片，共 "+pages+" 片");paging.addView(picker,new LinearLayout.LayoutParams(0,dp(48),1));paging.addView(next);
        picker.setOnClickListener(v->showGardenBedPicker(picker,pages));prev.setOnClickListener(v->{gardenPage--;drawGarden();});next.setOnClickListener(v->{gardenPage++;drawGarden();});
        if(total==0){prev.setVisibility(View.GONE);next.setVisibility(View.GONE);picker.setVisibility(View.GONE);Button start=smallButton("播下第一颗种子");paging.addView(start,new LinearLayout.LayoutParams(0,dp(48),1));start.setOnClickListener(v->openEditor(null));}
        Button memories=smallButton("成长日记 ⌃");memories.setBackground(shape(Color.TRANSPARENT,12));paging.addView(memories);drawerHeader.addView(paging,new LinearLayout.LayoutParams(-1,dp(48)));
        ScrollView drawerScroll=new ScrollView(this);drawerScroll.setFillViewport(true);drawerScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);drawerScroll.setVerticalScrollBarEnabled(false);
        LinearLayout diary=column();drawerScroll.addView(diary,new ScrollView.LayoutParams(-1,-2));controls.addView(drawerScroll,new LinearLayout.LayoutParams(-1,0,1));
        if(errorView.getParent() instanceof ViewGroup)((ViewGroup)errorView.getParent()).removeView(errorView);diary.addView(errorView);
        if(!gardenNotice.isEmpty()){
            note(diary,gardenNotice+(gardenData==null?"":"\n下面是上次读取的成长记录，暂未更新。"));Button retry=button("重新连接花园",false);diary.addView(retry);retry.setOnClickListener(v->loadGarden());
        }
        GardenDrawerLayout drawer=new GardenDrawerLayout(this,stage,controls,drawerHeader,drawerScroll);gardenDrawer=drawer;
        gardenContent.addView(drawer,new LinearLayout.LayoutParams(-1,-1));
        boolean[] diaryBuilt={false};
        drawer.setOnOpening(()->{if(!diaryBuilt[0]){diaryBuilt[0]=true;drawGardenDiary(diary);}});
        drawer.setListener(expanded->{
            gardenMemoriesExpanded=expanded;memories.setText(expanded?"收起日记 ⌄":"成长日记 ⌃");memories.setContentDescription(expanded?"收起花园抽屉":"展开花园抽屉");
        });
        memories.setOnClickListener(v->drawer.setExpanded(!gardenMemoriesExpanded,true));
        drawer.setExpanded(gardenMemoriesExpanded||!gardenNotice.isEmpty(),false);
    }
    private void addGardenSign(FrameLayout stage){
        TextView sign=text("种下小小的种子\n收获更好的自己",13,HOME_WOOD_TEXT,false);
        sign.setTypeface(getResources().getFont(R.font.garden_handwriting));sign.setGravity(Gravity.CENTER);sign.setLineSpacing(dp(4),1);
        sign.setBackgroundResource(R.drawable.garden_notice_board);sign.setPadding(dp(12),dp(8),dp(12),dp(26));sign.setRotation(4f);
        sign.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(dp(134),dp(80),Gravity.BOTTOM|Gravity.END);lp.setMargins(0,0,dp(8),dp(20));stage.addView(sign,lp);
        stage.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->sign.setVisibility(b-t>=dp(490)?View.VISIBLE:View.GONE));
    }
    private void showGardenBedPicker(View anchor,int pages){
        int epoch=pageEpoch;LinearLayout options=column();options.setPadding(dp(10),dp(10),dp(10),dp(10));
        ScrollView scroll=new ScrollView(this);scroll.addView(options);scroll.setBackground(shape(PAPER,18));
        PopupWindow popup=new PopupWindow(scroll,dp(192),dp(Math.min(280,pages*52+20)),true);
        popup.setBackgroundDrawable(shape(PAPER,18));popup.setOutsideTouchable(true);popup.setElevation(dp(8));
        for(int i=0;i<pages;i++){
            final int page=i;Button choice=smallButton("第 "+(i+1)+" 片"+(i==gardenPage?"     ✓":""));choice.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
            choice.setSelected(i==gardenPage);choice.setBackground(shape(i==gardenPage?PALE:PAPER,12));choice.setTextColor(INK);
            options.addView(choice,new LinearLayout.LayoutParams(-1,dp(52)));
            choice.setOnClickListener(v->{popup.dismiss();if(epoch==pageEpoch&&screen.equals("garden")){gardenPage=page;drawGarden();}});
        }
        popup.showAsDropDown(anchor,0,0,Gravity.CENTER_HORIZONTAL);
    }
    private void drawGardenDiary(LinearLayout diary){
        diary.setPadding(0,dp(12),0,dp(20));diary.addView(homeTitle("成长日记",23));gap(diary,12);
        if(gardenData==null){note(diary,gardenLoading?"正在读取成长记录…":"连接成功后，这里会显示你的成长记录。");return;}
        note(diary,"本周完成 "+gardenData.optInt("week_completions")+" 次 · 累计养分 "+gardenData.optInt("total_completions"));
        if(gardenLoading)note(diary,"正在更新花园…");
        if(gardenData.optBoolean("truncated"))note(diary,"植物仅展示已加载部分；累计养分与本周完成取全部记录。");
        LinearLayout milestones=row();int[] images={R.drawable.garden_seed_v3,R.drawable.garden_sprout_v3,R.drawable.garden_leaf_v3,R.drawable.garden_bloom};
        String[] names={"种子","发芽","长叶","开花"};int[] counts={0,1,3,7};
        for(int i=0;i<4;i++){
            LinearLayout step=column();step.setGravity(Gravity.CENTER);step.addView(art(images[i],54));gap(step,6);
            TextView label=text(names[i]+"\n"+counts[i]+" 次",12,INK,false);label.setGravity(Gravity.CENTER);step.addView(label);step.setContentDescription("累计完成 "+counts[i]+" 次："+names[i]);
            milestones.addView(step,new LinearLayout.LayoutParams(0,-2,1));
        }
        diary.addView(milestones);gap(diary,12);note(diary,"每次完成都是养分，漏做也不会枯萎。");
        JSONArray recent=gardenData.optJSONArray("recent_completions");
        if(recent==null||recent.length()==0)note(diary,"完成第一件小事后，这里会留下成长记忆。");
        if(recent!=null)for(int i=0;i<recent.length();i++){
            JSONObject record=recent.optJSONObject(i);if(record==null)continue;
            LinearLayout entry=row();entry.setGravity(Gravity.TOP);FrameLayout rail=new FrameLayout(this);
            View stem=new View(this);stem.setBackgroundColor(LINE);rail.addView(stem,new FrameLayout.LayoutParams(dp(1),-1,Gravity.CENTER_HORIZONTAL));
            View dot=new View(this);dot.setBackground(shape(BLUE,5));FrameLayout.LayoutParams dotLp=new FrameLayout.LayoutParams(dp(8),dp(8),Gravity.TOP|Gravity.CENTER_HORIZONTAL);dotLp.topMargin=dp(20);rail.addView(dot,dotLp);
            entry.addView(rail,new LinearLayout.LayoutParams(dp(16),-1));gapHorizontal(entry,4);
            LinearLayout line=row();line.setPadding(dp(9),dp(10),dp(9),dp(10));line.setMinimumHeight(dp(86));GradientDrawable surface=shape(PAPER,14);surface.setStroke(dp(1),LINE);line.setBackground(new RippleDrawable(ColorStateList.valueOf(0x18527a58),surface,null));
            LinearLayout.LayoutParams lineLp=new LinearLayout.LayoutParams(0,-2,1);lineLp.bottomMargin=dp(8);entry.addView(line,lineLp);diary.addView(entry);
            line.addView(art(GardenFlowers.resourceFor(record.optString("task_id")),34));gapHorizontal(line,9);LinearLayout copy=column();
            copy.addView(text(formatFull(record.optString("created_at")),11,MUTED,false));gap(copy,5);TextView title=text(record.optString("task_title"),14,INK,true);title.setMaxLines(2);title.setEllipsize(TextUtils.TruncateAt.END);copy.addView(title);gap(copy,5);
            String message=record.optString("text");JSONArray photos=record.optJSONArray("media_ids");boolean hasPhoto=photos!=null&&photos.length()>0;
            TextView note=text(message.isEmpty()?(hasPhoto?"完成留念 · "+photos.length()+" 张照片":"完成留念 · 养分 +1"):message,12,MUTED,false);note.setMaxLines(2);note.setEllipsize(TextUtils.TruncateAt.END);copy.addView(note);line.addView(copy,new LinearLayout.LayoutParams(0,-2,1));
            Runnable open=()->{selectedRecord=copy(record);try{selectedRecord.put("_type","completion");selectedRecord.put("_at",record.optString("created_at"));selectedRecord.put("state","completed");}catch(JSONException ignored){}recordBack="garden";navigate("record");};
            line.setFocusable(true);line.setOnClickListener(v->open.run());
            if(hasPhoto){gapHorizontal(line,8);RecordPhotoView photo=new RecordPhotoView(this,false,true);line.addView(photo,new LinearLayout.LayoutParams(dp(68),dp(72)));loadRecordPhoto(photo,photos.optString(0),open);}
        }
        gap(diary,8);Button all=button("去日历回看更多  ›",false);diary.addView(all);all.setOnClickListener(v->navigate("history"));
    }
    private void drawGardenSelection(JSONObject plant){
        if(gardenSelection==null)return;gardenSelection.removeAllViews();if(plant==null)return;
        LinearLayout line=row();line.addView(art(plantResource(plant.optString("task_id")),40));gapHorizontal(line,10);
        LinearLayout copy=column();TextView title=text(plant.optString("title"),14,INK,true);title.setSingleLine(true);title.setEllipsize(TextUtils.TruncateAt.END);copy.addView(title);
        TextView progress=text("已完成 "+plant.optInt("completion_count")+" 次 · "+GardenFlowers.nameFor(plant.optString("task_id")),11,MUTED,false);progress.setSingleLine(true);progress.setEllipsize(TextUtils.TruncateAt.END);copy.addView(progress);line.addView(copy,new LinearLayout.LayoutParams(0,-2,1));
        Button open=smallButton("查看小事 ›");open.setTextSize(12);open.setBackground(shape(Color.TRANSPARENT,12));line.addView(open);open.setOnClickListener(v->openGardenTask(plant,open));gardenSelection.addView(line);
    }
    private void addGardenStage(LinearLayout parent,int resource,String name,int count){LinearLayout group=row();group.setGravity(Gravity.CENTER);group.addView(art(resource,27));gapHorizontal(group,2);TextView label=text(name+" "+count,11,MUTED,false);label.setIncludeFontPadding(false);group.addView(label);parent.addView(group,new LinearLayout.LayoutParams(0,dp(36),1));}
    private void openGardenTask(JSONObject plant,View trigger){int request=++gardenTaskRequest;trigger.setEnabled(false);run(null,a->a.json("GET","/api/v1/tasks/"+plant.optString("task_id"),null),result->{trigger.setEnabled(true);if(request!=gardenTaskRequest)return;selected=result;detailBack="garden";navigate("detail");},failure->{trigger.setEnabled(true);if(request!=gardenTaskRequest)return;showError(failure.getMessage()==null?"这株植物的提醒暂时打不开，请重试。":failure.getMessage());});}
    private int stageIndex(String stage){switch(stage){case "sprout":return 1;case "leaf":return 2;case "bloom":return 3;default:return 0;}}
    private int stageResource(String stage){switch(stage){case "sprout":return R.drawable.garden_sprout_v3;case "leaf":return R.drawable.garden_leaf_v3;case "bloom":return R.drawable.garden_bloom;default:return R.drawable.garden_seed_v3;}}
    private String stageLabel(String stage){switch(stage){case "sprout":return "小芽冒头";case "leaf":return "枝叶舒展";case "bloom":return "已经开花";default:return "刚刚播种";}}
    private String growthHint(int count){if(count==0)return "等待第一次完成，唤醒小芽";if(count<3)return "再完成 "+(3-count)+" 次，就会长出新叶";if(count<7)return "再完成 "+(7-count)+" 次，就会开花";return "每一次认真完成，都会继续留在这里";}
    private int plantResource(String taskId){
        if(gardenData!=null){JSONArray plants=gardenData.optJSONArray("plants");if(plants!=null)for(int i=0;i<plants.length();i++){
            JSONObject plant=plants.optJSONObject(i);if(plant==null||!taskId.equals(plant.optString("task_id")))continue;
            if(plant.optString("stage").equals("bloom"))return GardenFlowers.resourceFor(taskId);
            return stageResource(plant.optString("stage"));
        }}
        return R.drawable.garden_watering;
    }
    private ImageView art(int resource,int size){
        ImageView image=new ImageView(this);image.setImageBitmap(GardenArtCache.load(getResources(),resource));image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);image.setLayoutParams(new LinearLayout.LayoutParams(dp(size),dp(size)));return image;
    }
    private void renderGrown(){
        JSONObject receipt=completedRecord.optJSONObject("growth");String completionId=completedRecord.optString("id");
        int from=receipt==null?-1:receipt.optInt("from_count",-1),to=receipt==null?-1:receipt.optInt("to_count",-1);
        boolean added=receipt!=null&&receipt.optBoolean("added");
        boolean known=receipt!=null&&receipt.opt("added") instanceof Boolean&&receipt.opt("from_count") instanceof Number&&receipt.opt("to_count") instanceof Number&&GrowthAnimationState.validReceipt(completionId,from,to,added);
        gap(body,8);TextView overline=text("一件小事，认真完成",12,BLUE,true);overline.setGravity(Gravity.CENTER);body.addView(overline);gap(body,12);
        GardenGrowthView animation=new GardenGrowthView(this);growthView=animation;animation.setTaskId(completedRecord.optString("task_id",selected.optString("id")));animation.setHostActive(motionHostActive);animation.setBackground(shape(PALE,28));animation.setClipToOutline(true);body.addView(animation,new LinearLayout.LayoutParams(-1,dp(280)));gap(body,18);
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
            note(body,status.equals("done")?doneCopy:status.equals("upcoming")?"还没有到本期时间，开始后再记录完成。":status.equals("expired")?"这条提醒已过截止时间，记录依然保留。":"已暂停发送邮件，可以随时恢复。");
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
                        // Pausing changes future schedules, not plant identity or earned growth.
                        // Keep loaded garden data and any in-flight garden read intact.
                        invalidateCalendarSchedule();
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
        selectedFlowerIndex=editing?GardenFlowers.indexFor(task.optString("id")):-1;
        screen="editor";render();
    }
    private void renderEditor() {
        addSowingHeading();
        if(!editing){
            boolean compact=manualVisible&&!aiExpanded;
            LinearLayout inputArea=column();inputArea.setPadding(dp(10),dp(10),dp(10),dp(10));inputArea.setBackground(shape(0xffdeebd8,12));body.addView(inputArea);
            EditText sentence=new EditText(this);styleField(sentence);sentence.setBackground(shape(PAPER,8));sentence.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));sentence.setIncludeFontPadding(false);sentence.setTextSize(14);sentence.setPadding(dp(12),dp(10),dp(12),dp(10));sentence.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);sentence.setGravity(Gravity.TOP|Gravity.START);sentence.setMinLines(compact?1:2);sentence.setMaxLines(compact?3:5);sentence.setMinHeight(dp(compact?44:80));sentence.setText(aiText);sentence.setHint("等会儿提醒我搞卫生，时间你来安排");fields.put("ai",sentence);inputArea.addView(sentence,new LinearLayout.LayoutParams(-1,-2));
            LinearLayout actions=row();Button generate=button(manualVisible?"重新安排":"帮我安排",!manualVisible);
            if(manualVisible){generate.setBackground(shape(Color.TRANSPARENT,10));generate.setTextSize(12);generate.setTextColor(INK);actions.setGravity(Gravity.END);actions.addView(generate,new LinearLayout.LayoutParams(-2,dp(48)));TextView separator=text("|",12,LINE,false);actions.addView(separator);}
            else{gap(body,10);actions.addView(generate,new LinearLayout.LayoutParams(0,dp(48),1));}
            Button manual=smallButton("手动填写");manual.setBackground(shape(Color.TRANSPARENT,10));manual.setTextColor(INK);manual.setTextSize(manualVisible?12:14);actions.addView(manual);body.addView(actions);gap(body,manualVisible?2:16);
            if(manualVisible&&draft.optJSONObject("rule")==null){
                inputArea.setVisibility(View.GONE);actions.setVisibility(View.GONE);
                Button useAi=smallButton("用一句话让念念安排");useAi.setBackground(shape(Color.TRANSPARENT,10));body.addView(useAi);
                useAi.setOnClickListener(v->{if(taskBusy)return;boolean show=inputArea.getVisibility()!=View.VISIBLE;inputArea.setVisibility(show?View.VISIBLE:View.GONE);actions.setVisibility(show?View.VISIBLE:View.GONE);useAi.setText(show?"收起，继续手动填写":"用一句话让念念安排");});
            }
            manual.setOnClickListener(v->{if(taskBusy)return;captureTransient();manualVisible=true;rulesExpanded=true;aiExpanded=true;render();});
            generate.setOnClickListener(v->{if(taskBusy)return;String input=sentence.getText().toString().trim();if(input.length()<2){showError("先写下想让我提醒你的事");return;}captureTransient();aiText=input;hideKeyboard(sentence);taskBusy=true;
                run(generate,a->a.json("POST","/api/v1/ai/parse",object("text",input)),result->{taskBusy=false;draft=result;restoredForm=null;manualVisible=true;rulesExpanded=false;advancedExpanded=false;aiExpanded=false;JSONArray warnings=result.optJSONArray("warnings");aiWarning=warnings==null?"":join(warnings,"\n");render();});
            });
        }
        if(!manualVisible){addFlowerPreview(body);return;}
        if(!aiWarning.isEmpty())note(body,aiWarning);
        JSONObject rule=draft.optJSONObject("rule");if(rule==null)rule=new JSONObject();
        LinearLayout draftPanel=column();draftPanel.setPadding(dp(12),dp(12),dp(12),dp(2));GradientDrawable draftSurface=shape(PAPER,10);draftSurface.setStroke(dp(1),0xffe5e7dc);draftPanel.setBackground(draftSurface);body.addView(draftPanel);
        EditText title=editorRowField(draftPanel,"title","小事名称",formValue("title",draft.optString("title")),"例如：收衣服",null);title.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));
        LinearLayout summaryBlock=column();draftPanel.addView(summaryBlock);summaryBlock.setVisibility(rulesExpanded?View.GONE:View.VISIBLE);
        TextView[] summaryLabels=new TextView[3],summaryValues=new TextView[3];
        LinearLayout[] summaryRows=new LinearLayout[3];String[] symbols={"clock","cycle","circle_check"};
        for(int i=0;i<3;i++){
            LinearLayout row=row();row.setPadding(dp(5),dp(8),0,dp(8));row.setMinimumHeight(dp(48));row.setGravity(Gravity.CENTER_VERTICAL);
            GardenIconView icon=new GardenIconView(this,symbols[i],INK);row.addView(icon,new LinearLayout.LayoutParams(dp(22),dp(22)));gapHorizontal(row,14);
            summaryLabels[i]=text("",15,INK,false);row.addView(summaryLabels[i],new LinearLayout.LayoutParams(dp(84),-2));
            summaryValues[i]=text("",15,INK,false);summaryValues[i].setGravity(Gravity.END|Gravity.CENTER_VERTICAL);row.addView(summaryValues[i],new LinearLayout.LayoutParams(0,-2,1));gapHorizontal(row,8);row.addView(new GardenIconView(this,"chevron_right",INK),new LinearLayout.LayoutParams(dp(16),dp(16)));
            summaryBlock.addView(row);summaryRows[i]=row;
            if(i<2){View divider=new View(this);divider.setBackgroundColor(LINE);summaryBlock.addView(divider,new LinearLayout.LayoutParams(-1,dp(1)));}
        }
        TextView previewNote=text("",11,MUTED,false);summaryBlock.addView(previewNote);gap(draftPanel,4);
        LinearLayout adjustRow=row();adjustRow.setGravity(Gravity.END);Button adjust=smallButton(rulesExpanded?"收起规则":"调整规则");adjust.setTextColor(INK);adjust.setBackground(shape(Color.TRANSPARENT,10));adjustRow.addView(adjust);GardenIconView adjustArrow=new GardenIconView(this,"chevron_right",INK);adjustRow.addView(adjustArrow,new LinearLayout.LayoutParams(dp(16),dp(16)));draftPanel.addView(adjustRow);
        LinearLayout rules=column();rules.setPadding(dp(10),dp(4),dp(10),dp(8));GradientDrawable ruleSurface=shape(PAPER,12);ruleSurface.setStroke(dp(1),LINE);rules.setBackground(ruleSurface);LinearLayout.LayoutParams rulesLp=new LinearLayout.LayoutParams(-1,-2);rulesLp.topMargin=dp(8);body.addView(rules,rulesLp);rules.setVisibility(rulesExpanded?View.VISIBLE:View.GONE);
        View.OnClickListener toggleRules=v->{rulesExpanded=!rulesExpanded;rules.setVisibility(rulesExpanded?View.VISIBLE:View.GONE);summaryBlock.setVisibility(rulesExpanded?View.GONE:View.VISIBLE);adjust.setText(rulesExpanded?"收起规则":"调整规则");};adjust.setOnClickListener(toggleRules);adjustRow.setOnClickListener(toggleRules);
        for(LinearLayout summaryRow:summaryRows){summaryRow.setBackground(new RippleDrawable(ColorStateList.valueOf(0x18527a58),shape(Color.TRANSPARENT,8),null));summaryRow.setFocusable(true);summaryRow.setOnClickListener(v->{rulesExpanded=true;rules.setVisibility(View.VISIBLE);summaryBlock.setVisibility(View.GONE);adjust.setText("收起规则");rules.post(()->rules.requestRectangleOnScreen(new Rect(0,0,rules.getWidth(),dp(180)),false));});}

        int initialKind=Arrays.asList(KINDS).indexOf(rule.optString("kind","monthly_window"));if(initialKind<0)initialKind=KINDS.length-1;
        int restoredKind=restoredForm!=null?restoredForm.optInt("kind_index",initialKind):initialKind;if(restoredKind<0||restoredKind>=KINDS.length)restoredKind=KINDS.length-1;
        kindPicker=editorChoice(KIND_LABELS);editorControlRow(rules,"重复方式","cycle",kindPicker);kindPicker.setSelection(restoredKind);
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
        EditText firstDate=editorRowField(minutes,"first_date","首次提醒日期","","选择日期","history");firstDate.setFocusable(false);firstDate.setOnClickListener(v->pickMinuteDate("first_at"));
        EditText firstTime=editorRowField(minutes,"first_time","首次提醒时间","","选择时间","clock");firstTime.setFocusable(false);firstTime.setOnClickListener(v->pickMinuteTime("first_at"));
        editorRowField(minutes,"repeat_minutes","间隔（分钟）",formValue("repeat_minutes",String.valueOf(rule.optString("kind").equals("minute_interval")?rule.optInt("repeat_minutes",5):5)),"1 至 1440","repeat");number(fields.get("repeat_minutes"));
        TextView minuteCompletion=text("永久停止提醒",14,INK,false);minuteCompletion.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);editorControlRow(minutes,"完成之后","lock",minuteCompletion);
        String initialEnd=rule.optString("kind").equals("minute_interval")&&!rule.isNull("end_at")?rule.optString("end_at"):"";
        EditText hiddenEnd=new EditText(this);hiddenEnd.setText(formValue("end_at",initialEnd));fields.put("end_at",hiddenEnd);
        LinearLayout deadline=column();minutes.addView(deadline);
        EditText deadlineDate=editorRowField(deadline,"deadline_date","截止日期","","选择日期","history");deadlineDate.setFocusable(false);deadlineDate.setOnClickListener(v->pickMinuteDate("end_at"));
        EditText deadlineTime=editorRowField(deadline,"deadline_time","截止时间","","选择时间","clock");deadlineTime.setFocusable(false);deadlineTime.setOnClickListener(v->pickMinuteTime("end_at"));
        Button clearDeadline=smallButton("清除截止，不限时间");deadline.addView(clearDeadline);
        Button useEndDate=smallButton("改为日期截止（当天结束）");deadline.addView(useEndDate);
        Button setDeadline=smallButton("设置截止时间");minutes.addView(setDeadline);
        LinearLayout[] datePair={column(),column()};rules.addView(datePair[0]);rules.addView(datePair[1]);
        editorRowField(datePair[0],"start_date","开始日期",formValue("start_date",rule.optString("start_date")),"不限 / 选择","history");dateField(fields.get("start_date"));
        editorRowField(datePair[1],"end_date","结束日期",formValue("end_date",rule.optString("end_date")),"不限 / 选择","history");dateField(fields.get("end_date"));
        TextView dateHelp=text("",12,MUTED,false);rules.addView(dateHelp);gap(rules,10);
        JSONArray initialTimes=rule.optJSONArray("times");EditText hiddenTimes=new EditText(this);hiddenTimes.setText(formValue("times",initialTimes==null?"09:00":join(initialTimes,", ")));fields.put("times",hiddenTimes);
        LinearLayout fixedTimes=column();rules.addView(fixedTimes);label(fixedTimes,"提醒时间");HorizontalScrollView timesScroll=new HorizontalScrollView(this);timesScroll.setHorizontalScrollBarEnabled(false);LinearLayout timeRow=row();timesScroll.addView(timeRow);fixedTimes.addView(timesScroll);gap(fixedTimes,10);drawTimeChips(timeRow);
        LinearLayout completionMode=column();rules.addView(completionMode);modePicker=editorChoice(MODE_LABELS);LinearLayout modeRow=editorControlRow(completionMode,"完成之后","circle_check",modePicker);modePicker.setSelection(restoredForm!=null?restoredForm.optInt("mode_index",indexOf(MODES,rule.optString("mode","until_done"))):indexOf(MODES,rule.optString("mode","until_done")));
        TextView permanent=text("永久停止提醒",14,INK,false);permanent.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);LinearLayout permanentRow=editorControlRow(completionMode,"完成之后","lock",permanent);
        TextView modeHelp=text("",12,MUTED,false);modeHelp.setPadding(dp(10),dp(10),dp(10),dp(10));modeHelp.setBackground(shape(0xfff0f4e9,8));completionMode.addView(modeHelp);gap(completionMode,8);
        LinearLayout advancedPanel=column();advancedPanel.setPadding(dp(10),0,dp(10),dp(8));GradientDrawable advancedSurface=shape(PAPER,12);advancedSurface.setStroke(dp(1),LINE);advancedPanel.setBackground(advancedSurface);LinearLayout.LayoutParams advancedLp=new LinearLayout.LayoutParams(-1,-2);advancedLp.topMargin=dp(8);body.addView(advancedPanel,advancedLp);
        LinearLayout advancedHeading=row();advancedHeading.addView(new GardenIconView(this,"gear",INK),new LinearLayout.LayoutParams(dp(21),dp(21)));gapHorizontal(advancedHeading,8);
        Button advanced=smallButton("更多设置");advanced.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);advanced.setTextColor(INK);advanced.setBackground(shape(Color.TRANSPARENT,10));advancedHeading.addView(advanced,new LinearLayout.LayoutParams(0,dp(48),1));GardenIconView advancedArrow=new GardenIconView(this,"chevron_down",INK);advancedArrow.setRotation(advancedExpanded?180:0);advancedHeading.addView(advancedArrow,new LinearLayout.LayoutParams(dp(20),dp(20)));advancedPanel.addView(advancedHeading);
        LinearLayout extra=column();advancedPanel.addView(extra);extra.setVisibility(advancedExpanded?View.VISIBLE:View.GONE);
        View.OnClickListener toggleAdvanced=v->{advancedExpanded=!advancedExpanded;extra.setVisibility(advancedExpanded?View.VISIBLE:View.GONE);advancedArrow.setRotation(advancedExpanded?180:0);advanced.setContentDescription("更多设置，"+(advancedExpanded?"已展开":"已收起"));};advanced.setOnClickListener(toggleAdvanced);advancedHeading.setOnClickListener(toggleAdvanced);
        field(extra,"description","补充说明",formValue("description",draft.optString("description")),"可不填",2);
        LinearLayout[] settingsPair=pairedFields(extra);field(settingsPair[0],"max_reminders","提醒次数上限",formValue("max_reminders",String.valueOf(rule.optInt("max_reminders",0))),"0 表示不限",1);number(fields.get("max_reminders"));
        field(settingsPair[1],"timezone","时区",formValue("timezone",rule.optString("timezone","Asia/Shanghai")),"Asia/Shanghai",1);note(extra,"上限填 0 表示不限次数，提醒按所选时区发送。");
        addFlowerPreview(extra);
        Button zonePreview=smallButton(value("timezone"));zonePreview.setTextColor(INK);zonePreview.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);zonePreview.setBackground(shape(Color.TRANSPARENT,8));LinearLayout minuteZone=editorControlRow(rules,"时区","globe",zonePreview);
        zonePreview.setOnClickListener(v->{advancedExpanded=true;extra.setVisibility(View.VISIBLE);advancedArrow.setRotation(180);EditText zone=fields.get("timezone");zone.requestFocus();zone.post(()->zone.requestRectangleOnScreen(new Rect(0,0,zone.getWidth(),zone.getHeight()),false));});
        refreshMinuteFields("first_at");refreshMinuteFields("end_at");
        Runnable updatePreview=()->updateEditorSummary(summaryLabels,summaryValues,previewNote);
        Runnable updateKind=()->{
            String kind=KINDS[kindPicker.getSelectedItemPosition()];boolean minuteKind=kind.equals("minute_interval"),ongoing=kind.equals("ongoing"),hasDeadline=!value("end_at").isEmpty();
            monthly.setVisibility(kind.equals("monthly_window")?View.VISIBLE:View.GONE);weekly.setVisibility(kind.equals("weekly")?View.VISIBLE:View.GONE);interval.setVisibility(kind.equals("interval")?View.VISIBLE:View.GONE);
            minutes.setVisibility(minuteKind?View.VISIBLE:View.GONE);minuteZone.setVisibility(minuteKind?View.VISIBLE:View.GONE);deadline.setVisibility(hasDeadline?View.VISIBLE:View.GONE);
            setDeadline.setVisibility(hasDeadline?View.GONE:View.VISIBLE);setDeadline.setText(value("end_date").isEmpty()?"设置截止时间":"改为精确时间截止");
            datePair[0].setVisibility(minuteKind?View.GONE:View.VISIBLE);datePair[1].setVisibility(minuteKind&&hasDeadline?View.GONE:View.VISIBLE);fixedTimes.setVisibility(minuteKind?View.GONE:View.VISIBLE);
            completionMode.setVisibility(minuteKind?View.GONE:View.VISIBLE);modeRow.setVisibility(ongoing?View.GONE:View.VISIBLE);permanentRow.setVisibility(ongoing?View.VISIBLE:View.GONE);
            modePicker.setEnabled(!ongoing&&!minuteKind);if(ongoing||minuteKind)modePicker.setSelection(0);
            dateHelp.setText(minuteKind?(hasDeadline?"未完成就继续提醒，完成或到截止时间后停止。":value("end_date").isEmpty()?"未设截止：没完成就继续提醒，完成后永久停止。":"未完成就提醒至结束日期当天；完成后永久停止，长按可清除日期。"):
                kind.equals("date_range")?"开始和结束日期都要填，长按可清除。":Arrays.asList("once","interval","ongoing").contains(kind)?"需选择开始日期，长按日期可清除。":"日期可不填，长按日期可清除。");
            updateModeHelp(modeHelp);updatePreview.run();
        };
        kindPicker.setOnItemSelectedListener(selection(updateKind));modePicker.setOnItemSelectedListener(selection(()->{updateModeHelp(modeHelp);updatePreview.run();}));updateKind.run();
        clearDeadline.setOnClickListener(v->{fields.get("end_date").setText("");hiddenEnd.setText("");});
        useEndDate.setOnClickListener(v->{try{String date=MinuteIntervalForm.date(value("end_at"),ZoneId.of(value("timezone")));hiddenEnd.setText("");fields.get("end_date").setText(date);}catch(Exception invalid){showError("请先选择有效的截止时间和时区");}});
        setDeadline.setOnClickListener(v->pickMinuteDeadline());
        for(Map.Entry<String,EditText> entry:fields.entrySet())if(!entry.getKey().equals("ai"))entry.getValue().addTextChangedListener(watcher(updatePreview));
        hiddenFirst.addTextChangedListener(watcher(()->{refreshMinuteFields("first_at");updatePreview.run();}));hiddenEnd.addTextChangedListener(watcher(()->{refreshMinuteFields("end_at");updateKind.run();}));
        fields.get("end_date").addTextChangedListener(watcher(updateKind));fields.get("timezone").addTextChangedListener(watcher(()->{zonePreview.setText(value("timezone"));refreshMinuteFields("first_at");refreshMinuteFields("end_at");updatePreview.run();}));
        hiddenTimes.addTextChangedListener(watcher(()->drawTimeChips(timeRow)));for(CheckBox day:weekdays)day.setOnCheckedChangeListener((v,checked)->updatePreview.run());

        gap(body,12);Button save=button(editing?"保存修改":"播下种子，开始提醒",true);save.setTextSize(16);save.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));save.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22527a58),shape(0xff567e5d,10),null));body.addView(save,new LinearLayout.LayoutParams(-1,dp(50)));save.setOnClickListener(v->saveEditor(save));
        if(!editing&&draft.optJSONObject("rule")!=null){body.addView(new Space(this),new LinearLayout.LayoutParams(1,0,1));addSeedFooter(body);}
        else addRuleFooter(body);
    }
    private void addRuleFooter(LinearLayout parent){
        FrameLayout footer=new FrameLayout(this);ImageView foliage=scenery(R.drawable.sowing_footer_foliage);foliage.setScaleType(ImageView.ScaleType.FIT_END);footer.addView(foliage,new FrameLayout.LayoutParams(-1,-1));
        TextView words=text("把小事\n种成美好的自己",16,HOME_WOOD_TEXT,false);words.setTypeface(getResources().getFont(R.font.garden_handwriting));words.setGravity(Gravity.CENTER);words.setRotation(-8);footer.addView(words,new FrameLayout.LayoutParams(-1,-2,Gravity.CENTER));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(110));lp.setMargins(-dp(16),dp(12),-dp(16),0);parent.addView(footer,lp);
    }
    private LinearLayout editorControlRow(LinearLayout parent,String caption,String symbol,View control){
        boolean stacked=getResources().getConfiguration().screenWidthDp<360||getResources().getConfiguration().fontScale>1.2f;
        LinearLayout container=column(),line=stacked?column():row();line.setPadding(0,dp(6),0,dp(6));line.setMinimumHeight(dp(56));
        LinearLayout label=row();if(symbol!=null){label.addView(new GardenIconView(this,symbol,INK),new LinearLayout.LayoutParams(dp(20),dp(20)));gapHorizontal(label,8);}
        TextView text=text(caption,14,INK,false);label.addView(text,new LinearLayout.LayoutParams(0,-2,1));
        line.addView(label,new LinearLayout.LayoutParams(stacked?-1:dp(symbol==null?86:118),-2));if(stacked)gap(line,6);else gapHorizontal(line,8);
        line.addView(control,stacked?new LinearLayout.LayoutParams(-1,-2):new LinearLayout.LayoutParams(0,-2,1));container.addView(line);
        View divider=new View(this);divider.setBackgroundColor(0xffeef0e7);container.addView(divider,new LinearLayout.LayoutParams(-1,dp(1)));parent.addView(container);
        text.setId(View.generateViewId());text.setLabelFor(control.getId()==View.NO_ID?generateControlId(control):control.getId());return container;
    }
    private int generateControlId(View control){int id=View.generateViewId();control.setId(id);return id;}
    private EditText editorRowField(LinearLayout parent,String key,String caption,String content,String hint,String symbol){
        EditText input=new EditText(this);styleField(input);input.setSingleLine(true);input.setText(content);input.setHint(hint);input.setPadding(dp(9),dp(8),dp(9),dp(8));
        GradientDrawable surface=shape(PAPER,7);surface.setStroke(dp(1),LINE);input.setBackground(surface);fields.put(key,input);editorControlRow(parent,caption,symbol,input);return input;
    }
    private Spinner editorChoice(String[] labels){
        Spinner choice=spinner(labels);choice.setPadding(dp(6),0,dp(2),0);
        ArrayAdapter<String> adapter=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_item,labels){
            @Override public View getView(int position,View convert,ViewGroup parent){
                LinearLayout line=row();line.setMinimumHeight(dp(48));TextView caption=text(getItem(position),14,INK,false);caption.setMaxLines(2);caption.setEllipsize(TextUtils.TruncateAt.END);
                line.addView(caption,new LinearLayout.LayoutParams(0,-2,1));line.addView(new GardenIconView(MainActivity.this,"chevron_right",INK),new LinearLayout.LayoutParams(dp(16),dp(16)));return line;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);choice.setAdapter(adapter);return choice;
    }
    private void addSowingHeading(){
        if(editing||manualVisible&&rulesExpanded){
            LinearLayout heading=row(),copy=column();copy.addView(homeTitle(editing?"照料这颗种子":"播下一件小事",25));gap(copy,8);copy.addView(text(editing?"调整提醒，按自己的节奏来":"把要记住的事，交给念念",14,INK,false));
            heading.addView(copy,new LinearLayout.LayoutParams(0,-2,1));heading.addView(art(R.drawable.garden_leaf_v3,58));body.addView(heading);gap(body,18);return;
        }
        LinearLayout heading=row(),copy=column();
        TextView title=text(editing?"照料这颗种子":"播下一件小事",25,HOME_TITLE,true);title.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));copy.addView(title);gap(copy,8);
        copy.addView(text(editing?"调整提醒，按自己的节奏来。":"把要记住的事，交给念念",14,INK,false));
        heading.addView(copy,new LinearLayout.LayoutParams(0,-2,1));
        FrameLayout illustration=new FrameLayout(this);
        ImageView seed=art(R.drawable.sowing_seed_mascot,80);FrameLayout.LayoutParams seedPosition=new FrameLayout.LayoutParams(dp(80),dp(108),Gravity.BOTTOM|Gravity.START);seedPosition.bottomMargin=dp(4);illustration.addView(seed,seedPosition);
        TextView words=text("小小的\n一件事\n也会长出美好的花",12,0xff9d8e75,false);words.setTypeface(getResources().getFont(R.font.garden_handwriting));words.setGravity(Gravity.CENTER);words.setLineSpacing(dp(3),1);words.setRotation(-10);FrameLayout.LayoutParams copyPosition=new FrameLayout.LayoutParams(dp(78),-2,Gravity.CENTER_VERTICAL|Gravity.END);copyPosition.bottomMargin=dp(12);illustration.addView(words,copyPosition);
        GardenIconView heart=new GardenIconView(this,"heart",0xffa29076);FrameLayout.LayoutParams heartPosition=new FrameLayout.LayoutParams(dp(17),dp(17),Gravity.END|Gravity.BOTTOM);heartPosition.setMargins(0,0,dp(10),dp(16));illustration.addView(heart,heartPosition);
        heading.addView(illustration,new LinearLayout.LayoutParams(dp(getResources().getConfiguration().screenWidthDp<360?124:145),dp(124)));body.addView(heading);gap(body,8);
    }
    private void addFlowerPreview(LinearLayout parent){
        gap(parent,10);TextView title=text("选一朵喜欢的花",16,INK,true);title.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));parent.addView(title);gap(parent,12);
        int[] pictures=GardenFlowers.resources();String[] names=GardenFlowers.names();LinearLayout flowers=row();
        FrameLayout[] choices=new FrameLayout[pictures.length];TextView[] captions=new TextView[pictures.length];FrameLayout[] checks=new FrameLayout[pictures.length];
        Runnable update=()->{for(int j=0;j<choices.length;j++){
            boolean checked=selectedFlowerIndex==j;GradientDrawable plate=shape(checked?0xffe8f0df:Color.TRANSPARENT,12);plate.setStroke(dp(checked?2:1),checked?BLUE:0xffe4e7dc);choices[j].setBackground(new RippleDrawable(ColorStateList.valueOf(0x22527a58),plate,null));choices[j].setSelected(checked);choices[j].setContentDescription(names[j]+(checked?"，已选中":"，未选中"));checks[j].setVisibility(checked?View.VISIBLE:View.INVISIBLE);captions[j].setTextColor(checked?HOME_TITLE:MUTED);captions[j].setTypeface(Typeface.create("sans-serif",checked?Typeface.BOLD:Typeface.NORMAL));
        }};
        for(int i=0;i<pictures.length;i++){
            final int choice=i;FrameLayout item=new FrameLayout(this);choices[i]=item;item.setPadding(dp(4),dp(10),dp(4),dp(8));item.setMinimumHeight(dp(112));
            LinearLayout content=column();content.setGravity(Gravity.CENTER);ImageView flower=art(pictures[i],64);content.addView(flower,new LinearLayout.LayoutParams(-1,dp(64)));gap(content,8);TextView caption=text(names[i],12,MUTED,false);caption.setGravity(Gravity.CENTER);captions[i]=caption;content.addView(caption);item.addView(content,new FrameLayout.LayoutParams(-1,-2,Gravity.CENTER));
            FrameLayout check=new FrameLayout(this);check.setBackground(shape(BLUE,20));GardenIconView mark=new GardenIconView(this,"check",PAPER);check.addView(mark,new FrameLayout.LayoutParams(dp(14),dp(14),Gravity.CENTER));FrameLayout.LayoutParams badge=new FrameLayout.LayoutParams(dp(20),dp(20),Gravity.TOP|Gravity.END);item.addView(check,badge);checks[i]=check;
            item.setFocusable(true);item.setAccessibilityDelegate(new View.AccessibilityDelegate(){@Override public void onInitializeAccessibilityNodeInfo(View host,android.view.accessibility.AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(host,info);info.setClassName("android.widget.RadioButton");info.setCheckable(true);info.setChecked(host.isSelected());}});
            item.setOnClickListener(v->{if(taskBusy){toast("正在处理提醒，请稍等");return;}selectedFlowerIndex=choice;update.run();});LinearLayout.LayoutParams slot=new LinearLayout.LayoutParams(0,-2,1);if(i>0)slot.leftMargin=dp(7);flowers.addView(item,slot);
        }
        update.run();parent.addView(flowers);gap(parent,12);note(parent,"完成 1 次发芽、3 次长叶、7 次开花。花种会在这台手机上记住。");
    }
    private void addSeedFooter(LinearLayout parent){
        FrameLayout footer=new FrameLayout(this);footer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        ImageView foliage=new ImageView(this);foliage.setImageBitmap(GardenArtCache.backdrop(getResources(),R.drawable.sowing_footer_foliage));foliage.setScaleType(ImageView.ScaleType.FIT_XY);footer.addView(foliage,new FrameLayout.LayoutParams(-1,dp(144),Gravity.BOTTOM));
        TextView words=text("让生活的每一件小事\n都被温柔记住",17,0xff9b8b71,false);words.setTypeface(getResources().getFont(R.font.garden_handwriting));words.setLineSpacing(dp(7),1);words.setGravity(Gravity.CENTER);words.setRotation(-10);FrameLayout.LayoutParams wordsPosition=new FrameLayout.LayoutParams(dp(182),-2,Gravity.TOP|Gravity.START);wordsPosition.setMargins(dp(6),dp(35),0,0);footer.addView(words,wordsPosition);
        GardenIconView heart=new GardenIconView(this,"heart",0xffaa947b);FrameLayout.LayoutParams heartPosition=new FrameLayout.LayoutParams(dp(18),dp(18),Gravity.TOP|Gravity.START);heartPosition.setMargins(dp(169),dp(82),0,0);footer.addView(heart,heartPosition);
        FrameLayout pot=new FrameLayout(this);ImageView plant=art(R.drawable.garden_seed_pot_v3,136);pot.addView(plant,new FrameLayout.LayoutParams(-1,-1));TextView name=text("念念",17,0xff82583e,false);name.setTypeface(getResources().getFont(R.font.garden_handwriting));name.setGravity(Gravity.CENTER);FrameLayout.LayoutParams namePosition=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);namePosition.bottomMargin=dp(29);pot.addView(name,namePosition);FrameLayout.LayoutParams potPosition=new FrameLayout.LayoutParams(dp(136),dp(158),Gravity.END|Gravity.BOTTOM);potPosition.setMargins(0,0,dp(22),dp(8));footer.addView(pot,potPosition);
        LinearLayout.LayoutParams footerPosition=new LinearLayout.LayoutParams(-1,dp(190));footerPosition.setMargins(-dp(16),dp(8),-dp(16),0);parent.addView(footer,footerPosition);
    }
    private void updateEditorSummary(TextView[] labels,TextView[] values,TextView note){
        if(kindPicker==null||modePicker==null)return;
        String kind=KINDS[kindPicker.getSelectedItemPosition()],mode=MODES[modePicker.getSelectedItemPosition()];
        Map<String,String> data=new HashMap<>();for(String key:fields.keySet())data.put(key,value(key));
        List<Integer> days=new ArrayList<>();if(weekdays!=null)for(int i=0;i<weekdays.length;i++)if(weekdays[i].isChecked())days.add(i+1);
        String[] captions=ReminderSummary.labels(kind),contents=ReminderSummary.values(kind,mode,data,days);
        for(int i=0;i<3;i++){labels[i].setText(captions[i]);values[i].setText(contents[i]);}
        String extra=ReminderSummary.note(kind,mode,data);note.setText(extra);note.setVisibility(extra.isEmpty()?View.GONE:View.VISIBLE);
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
    private void refreshMinuteFields(String key){
        String prefix=key.equals("first_at")?"first":"deadline";
        try{
            ZoneId zone=ZoneId.of(value("timezone"));
            fields.get(prefix+"_date").setText(MinuteIntervalForm.date(value(key),zone));
            fields.get(prefix+"_time").setText(MinuteIntervalForm.time(value(key),zone));
        }catch(Exception invalid){
            fields.get(prefix+"_date").setText("");fields.get(prefix+"_time").setText("");
        }
    }
    private String minuteFirstOrDefault(ZoneId zone){
        try{return MinuteIntervalForm.validate(value("first_at"),zone,null);}catch(IllegalArgumentException invalid){return MinuteIntervalForm.defaultFirstAt(Instant.now(),zone);}
    }
    private String minuteInstantOrDefault(String key,ZoneId zone){
        if(key.equals("first_at"))return minuteFirstOrDefault(zone);
        try{MinuteIntervalForm.date(value(key),zone);return value(key);}catch(IllegalArgumentException invalid){
            String first=minuteFirstOrDefault(zone),end=value("end_date");
            if(!end.isEmpty())try{return MinuteIntervalForm.reschedule(first,LocalDate.parse(end),LocalTime.of(23,59),zone);}catch(IllegalArgumentException|DateTimeException ignored){}
            return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(OffsetDateTime.parse(first).plusHours(3).withSecond(0).withNano(0));
        }
    }
    private void setMinuteInstant(String key,String instant){
        if(key.equals("end_at"))fields.get("end_date").setText("");
        fields.get(key).setText(instant);
    }
    private void pickMinuteDate(String key){
        try{
            ZoneId zone=ZoneId.of(value("timezone"));String source=minuteInstantOrDefault(key,zone);LocalDate date=LocalDate.parse(MinuteIntervalForm.date(source,zone));
            new DatePickerDialog(this,(picker,y,m,d)->{
                try{setMinuteInstant(key,MinuteIntervalForm.reschedule(source,LocalDate.of(y,m+1,d),LocalTime.parse(MinuteIntervalForm.time(source,zone)),zone));}
                catch(IllegalArgumentException invalid){showError(invalid.getMessage());}
            },date.getYear(),date.getMonthValue()-1,date.getDayOfMonth()).show();
        }catch(DateTimeException invalid){showError("请先填写有效时区，例如 Asia/Shanghai");}
    }
    private void pickMinuteTime(String key){
        try{
            ZoneId zone=ZoneId.of(value("timezone"));String source=minuteInstantOrDefault(key,zone);LocalTime time=LocalTime.parse(MinuteIntervalForm.time(source,zone));
            new TimePickerDialog(this,(picker,h,m)->{
                try{setMinuteInstant(key,MinuteIntervalForm.reschedule(source,LocalDate.parse(MinuteIntervalForm.date(source,zone)),time.withHour(h).withMinute(m),zone));}
                catch(IllegalArgumentException invalid){showError(invalid.getMessage());}
            },time.getHour(),time.getMinute(),true).show();
        }catch(DateTimeException invalid){showError("请先填写有效时区，例如 Asia/Shanghai");}
    }
    private void pickMinuteDeadline(){
        try{
            ZoneId zone=ZoneId.of(value("timezone"));String source=minuteInstantOrDefault("end_at",zone);LocalDate date=LocalDate.parse(MinuteIntervalForm.date(source,zone));LocalTime time=LocalTime.parse(MinuteIntervalForm.time(source,zone));
            new DatePickerDialog(this,(datePicker,y,month,day)->new TimePickerDialog(this,(timePicker,h,m)->{
                try{setMinuteInstant("end_at",MinuteIntervalForm.reschedule(source,LocalDate.of(y,month+1,day),time.withHour(h).withMinute(m),zone));}
                catch(IllegalArgumentException invalid){showError(invalid.getMessage());}
            },time.getHour(),time.getMinute(),true).show(),date.getYear(),date.getMonthValue()-1,date.getDayOfMonth()).show();
        }catch(DateTimeException invalid){showError("请先填写有效时区，例如 Asia/Shanghai");}
    }
    private void saveEditor(Button button){
        if(taskBusy)return;
        JSONObject payload;try{payload=buildTask();}catch(Exception e){showError(e.getMessage());return;}String id=draft.optString("id");boolean isEditing=editing;
        taskBusy=true;
        int flower=selectedFlowerIndex;
        run(button,a->a.json(isEditing?"PUT":"POST",isEditing?"/api/v1/tasks/"+id:"/api/v1/tasks",payload),result->{taskBusy=false;boolean flowerSaved=flower<0||GardenFlowers.remember(result.optString("id"),flower);selected=result;taskData=new JSONArray();restoredForm=null;invalidateCalendar();if(!isEditing)detailBack="home";toast(!flowerSaved?(isEditing?"提醒已修改，花种未保存，请重试":"提醒已创建，花种未保存，可在调整规则里重选"):isEditing?"修改已保存":"种子已播下，提醒也安排好了");screen="detail";render();});
    }
    private void updateModeHelp(TextView view){String[] help={"本期完成后停止，下期重新提醒。","今天完成后，明天继续提醒。","每期只发一次，无需点击完成。"};view.setText(Arrays.asList("ongoing","minute_interval").contains(KINDS[kindPicker.getSelectedItemPosition()])?"完成后永久结束，不再重复。":help[modePicker.getSelectedItemPosition()]);}
    private JSONObject buildTask() throws Exception {
        String title=value("title"),kind=KINDS[kindPicker.getSelectedItemPosition()],mode=MODES[modePicker.getSelectedItemPosition()];
        if(kind.equals("unsupported"))throw new IOException("当前规则暂不支持，请先选择重复方式");
        if(title.isEmpty())throw new IOException("请填写事情名称");if(title.codePointCount(0,title.length())>100)throw new IOException("事情名称请控制在 100 字以内");
        boolean minuteKind=kind.equals("minute_interval");String endAt=minuteKind?value("end_at"):"",start=minuteKind?"":value("start_date"),end=minuteKind&&!endAt.isEmpty()?"":value("end_date");LocalDate s=null,e=null;
        try{if(!start.isEmpty())s=LocalDate.parse(start);if(!end.isEmpty())e=LocalDate.parse(end);}catch(Exception x){throw new IOException("日期请使用 YYYY-MM-DD 格式");}
        if(Arrays.asList("once","date_range","interval","ongoing").contains(kind)&&s==null)throw new IOException("这种提醒需要填写开始日期");
        if(kind.equals("ongoing"))mode="until_done";
        if(kind.equals("date_range")&&e==null)throw new IOException("日期范围需要填写结束日期");if(s!=null&&e!=null&&e.isBefore(s))throw new IOException("结束日期不能早于开始日期");
        String zone=value("timezone");try{ZoneId.of(zone);}catch(Exception x){throw new IOException("时区无效，例如 Asia/Shanghai");}
        int cap=parseNumber("max_reminders",0,100);
        if(minuteKind){
            String first=MinuteIntervalForm.validate(value("first_at"),ZoneId.of(zone),e);int repeat=parseNumber("repeat_minutes",1,1440);
            endAt=MinuteIntervalForm.validateEndAt(first,endAt,ZoneId.of(zone),e);
            JSONObject rule=object("kind",kind,"mode","until_done","timezone",zone,"first_at",first,"repeat_minutes",repeat,"times",new JSONArray(),"start_date","","end_date",end,"weekdays",new JSONArray(),"month_start",0,"month_end",0,"every_days",0,"max_reminders",cap);
            if(!endAt.isEmpty())rule.put("end_at",endAt);
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
        FrameLayout hero=new FrameLayout(this);
        hero.addView(scenery(R.drawable.calendar_header),new FrameLayout.LayoutParams(-1,-1));
        View fade=new View(this);fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0x00fffcf5,BG}));
        hero.addView(fade,new FrameLayout.LayoutParams(-1,dp(20),Gravity.BOTTOM));
        LinearLayout heading=row(),titles=column();heading.setGravity(Gravity.TOP);
        TextView brand=homeTitle("念念",30);brand.setShadowLayer(dp(1),0,dp(1),0xccfffdf8);titles.addView(brand);
        gap(titles,5);titles.addView(text("日历安排",14,HOME_TITLE,false));
        heading.addView(titles,new LinearLayout.LayoutParams(0,-2,1));
        calendarRefresh=iconButton("refresh","刷新日历");
        calendarRefresh.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22527a58),shape(0xeefffdf8,24),null));
        heading.addView(calendarRefresh);
        FrameLayout.LayoutParams titleLp=new FrameLayout.LayoutParams(-1,-2,Gravity.TOP);
        titleLp.setMargins(dp(22),dp(14),dp(16),0);hero.addView(heading,titleLp);
        int height=Math.max(154,Math.min(192,Math.round(getResources().getConfiguration().screenWidthDp*.45f)));
        body.addView(hero,new LinearLayout.LayoutParams(-1,dp(height)));
        LinearLayout content=column();content.setPadding(dp(16),0,dp(16),dp(16));body.addView(content);
        calendarFeedback=column();content.addView(calendarFeedback);
        LinearLayout controls=row(),month=row();month.setBackground(shape(0xfff2f3ed,24));
        Button previous=iconButton("back","上个月"),next=iconButton("chevron_right","下个月");
        month.addView(previous);calendarCaption=text("",16,HOME_TITLE,true);calendarCaption.setGravity(Gravity.CENTER);
        month.addView(calendarCaption,new LinearLayout.LayoutParams(0,dp(48),1));month.addView(next);
        controls.addView(month,new LinearLayout.LayoutParams(0,-2,1));gapHorizontal(controls,12);
        Button today=smallButton("今天");today.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22527a58),shape(0xffdff0df,24),null));
        controls.addView(today,new LinearLayout.LayoutParams(dp(62),dp(48)));content.addView(controls);gap(content,8);
        calendarGrid=column();content.addView(calendarGrid);gap(content,6);
        LinearLayout legend=row();String[] labels={"待提醒","已完成","未完成","已过期"};
        int[] colors={0xff3984ef,0xff5da977,0xffffad32,0xffeb6571};
        for(int i=0;i<labels.length;i++){
            LinearLayout item=row();item.setGravity(Gravity.CENTER);
            View dot=new View(this);dot.setBackground(shape(colors[i],5));item.addView(dot,new LinearLayout.LayoutParams(dp(8),dp(8)));gapHorizontal(item,5);
            item.addView(text(labels[i],11,MUTED,false));legend.addView(item,new LinearLayout.LayoutParams(0,-2,1));
        }
        legend.setPadding(0,dp(8),0,dp(8));legend.setMinimumHeight(dp(48));legend.setFocusable(true);
        legend.setContentDescription("日历状态说明，点击查看");legend.setBackground(new RippleDrawable(ColorStateList.valueOf(0x15527a58),null,shape(PAPER,8)));
        legend.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("日历里的颜色")
                .setMessage("蓝色：待提醒，或仅通知类的已提醒。\n绿色：本期已完成。\n黄色：到点后还未完成。\n红色：本期截止后仍未完成。\n\n邮件是否发送成功，在提醒记录中单独显示。\n时间按手机时区显示："+ZoneId.systemDefault().getId())
                .setPositiveButton("知道了",null).show());
        content.addView(legend);calendarDivider(content);gap(content,12);
        calendarRecords=column();content.addView(calendarRecords);
        previous.setOnClickListener(v->changeMonth(-1));next.setOnClickListener(v->changeMonth(1));
        today.setOnClickListener(v->{plannedTaskRequest++;YearMonth previousMonth=calendarMonth;calendarDate=LocalDate.now();calendarMonth=YearMonth.from(calendarDate);recordPage=0;if(!calendarMonth.equals(previousMonth))loadCalendar(null);else drawCalendar();});
        calendarRefresh.setOnClickListener(v->loadCalendar(null));drawCalendar();loadCalendar(null);
    }
    private void calendarDivider(LinearLayout parent){
        View line=new View(this);line.setBackgroundColor(0xffe9ece3);parent.addView(line,new LinearLayout.LayoutParams(-1,dp(1)));
    }
    private void drawCalendarFeedback(){
        calendarFeedback.removeAllViews();
        if(calendarNotice.isEmpty()&&!calendarLoading)return;
        LinearLayout banner=row();banner.setPadding(dp(10),dp(4),dp(8),dp(4));
        boolean failed=!calendarNotice.isEmpty();
        banner.setBackground(shape(failed?0xffffedbd:0xffedf2e8,12));
        banner.addView(new GardenIconView(this,failed?"info":"clock",failed?YELLOW:BLUE),new LinearLayout.LayoutParams(dp(20),dp(20)));gapHorizontal(banner,8);
        String message=failed?(calendarLoaded()?"没有刷新成功，显示上次读取的安排":calendarNotice):(calendarLoaded()?"正在更新这个月的安排…":"正在读取这个月的安排…");
        TextView label=text(message,12,failed?YELLOW:MUTED,false);label.setMinHeight(dp(40));label.setGravity(Gravity.CENTER_VERTICAL);
        banner.addView(label,new LinearLayout.LayoutParams(0,-2,1));
        if(failed){Button retry=smallButton("重试");retry.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22527a58),shape(PAPER,24),null));banner.addView(retry);retry.setOnClickListener(v->loadCalendar(null));}
        calendarFeedback.addView(banner);gap(calendarFeedback,10);
    }
    private void changeMonth(int delta){calendarMonth=calendarMonth.plusMonths(delta);calendarDate=calendarMonth.atDay(Math.min(calendarDate.getDayOfMonth(),calendarMonth.lengthOfMonth()));recordPage=0;loadCalendar(null);}
    private String calendarKey(){return calendarMonth+"|"+ZoneId.systemDefault().getId();}
    private boolean calendarLoaded(){return calendarLoadedKey.equals(calendarKey());}
    private void invalidateCalendarSchedule(){calendarRequests.invalidate();plannedTaskRequest++;calendarData=new JSONArray();calendarLoadedKey="";calendarNotice="";calendarTruncated=false;calendarLoading=false;}
    private void invalidateCalendar(){invalidateCalendarSchedule();gardenRequest++;gardenTaskRequest++;gardenData=null;gardenLoading=false;gardenNotice="";}
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
        if(calendarGrid==null||calendarRecords==null)return;
        calendarCaption.setText(calendarMonth.getYear()+" 年 "+calendarMonth.getMonthValue()+" 月");
        drawCalendarFeedback();calendarGrid.removeAllViews();
        ZoneId zone=ZoneId.systemDefault();Map<LocalDate,int[]> marks=new HashMap<>();List<JSONObject> chosen=new ArrayList<>();int invalid=0;
        for(JSONObject entry:calendarEntries()){
            LocalDate day=RecordDates.localDate(entry.optString("_at"),zone);
            if(day==null){invalid++;continue;}
            int[] counts=marks.computeIfAbsent(day,k->new int[4]);counts[calendarStateIndex(entry)]++;
            if(day.equals(calendarDate))chosen.add(entry);
        }
        LinearLayout weekdays=row();
        for(String day:new String[]{"一","二","三","四","五","六","日"}){
            TextView label=text(day,12,HOME_TITLE,false);label.setGravity(Gravity.CENTER);
            weekdays.addView(label,new LinearLayout.LayoutParams(0,dp(30),1));
        }
        calendarGrid.addView(weekdays);
        int blanks=RecordDates.mondayLeadingBlanks(calendarMonth),days=RecordDates.daysInMonth(calendarMonth),count=((blanks+days+6)/7)*7;
        LinearLayout week=null;
        for(int cell=0;cell<count;cell++){
            if(cell%7==0){week=row();calendarGrid.addView(week);}
            int day=cell-blanks+1;
            FrameLayout hit=new FrameLayout(this);week.addView(hit,new LinearLayout.LayoutParams(0,dp(48),1));
            if(day<1||day>days)continue;
            LocalDate date=calendarMonth.atDay(day);boolean chosenDate=date.equals(calendarDate),isToday=date.equals(LocalDate.now());
            int[] counts=marks.getOrDefault(date,new int[4]);
            LinearLayout tile=column();tile.setGravity(Gravity.CENTER);
            GradientDrawable surface=shape(chosenDate?0xff65946a:Color.TRANSPARENT,13);
            if(isToday&&!chosenDate)surface.setStroke(dp(1),0xffa9c4a0);
            tile.setBackground(surface);hit.addView(tile,new FrameLayout.LayoutParams(dp(38),dp(40),Gravity.CENTER));
            TextView number=text(String.valueOf(day),14,chosenDate?PAPER:INK,chosenDate);number.setGravity(Gravity.CENTER);tile.addView(number);
            LinearLayout dots=row();dots.setGravity(Gravity.CENTER);
            int[] colors={0xff3984ef,0xff5da977,0xffffad32,0xffeb6571};
            for(int state=0;state<4;state++)if(counts[state]>0){
                if(dots.getChildCount()>0)gapHorizontal(dots,2);
                View dot=new View(this);dot.setBackground(shape(colors[state],3));dots.addView(dot,new LinearLayout.LayoutParams(dp(5),dp(5)));
            }
            tile.addView(dots,new LinearLayout.LayoutParams(-1,dp(8)));
            hit.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22527a58),null,shape(PAPER,13)));
            hit.setContentDescription(date+"，待提醒或已提醒 "+counts[0]+" 条，已完成 "+counts[1]+" 条，未完成 "+counts[2]+" 条，已过期 "+counts[3]+" 条"+(isToday?"，今天":"")+(chosenDate?"，已选择":""));
            hit.setSelected(chosenDate);hit.setFocusable(true);
            hit.setOnClickListener(v->{plannedTaskRequest++;calendarDate=date;recordPage=0;drawCalendar();});
        }
        chosen.sort((a,b)->{Instant first=RecordDates.parseInstant(a.optString("_at")),second=RecordDates.parseInstant(b.optString("_at"));int order=second.compareTo(first);return order!=0?order:a.optString("id").compareTo(b.optString("id"));});
        drawCalendarRecords(chosen,invalid);
    }
    private void drawCalendarRecords(List<JSONObject> entries,int invalid){
        calendarRecords.removeAllViews();
        boolean stale=calendarLoaded()&&!calendarNotice.isEmpty();
        LinearLayout heading=row();
        heading.addView(text(calendarDate.format(DateTimeFormatter.ofPattern("M月d日 EEEE",Locale.CHINA)),17,HOME_TITLE,true),new LinearLayout.LayoutParams(0,-2,1));
        heading.addView(text(stale?"上次读取":calendarLoaded()?entries.size()+" 条":"",12,MUTED,false));
        calendarRecords.addView(heading);gap(calendarRecords,10);
        if(calendarTruncated)note(calendarRecords,"这个月的安排较多，目前只显示部分内容。");
        if(invalid>0)note(calendarRecords,invalid+" 条记录的时间无法识别，未归入日历。");
        if(entries.isEmpty()){
            if(calendarLoading&&!calendarLoaded()){calendarSkeleton();return;}
            if(!calendarNotice.isEmpty()){
                if(stale)note(calendarRecords,"上次读取时，这一天没有记录，当前状态可能已变化。");
                return;
            }
            if(calendarLoaded()){
                gap(calendarRecords,20);calendarArt(calendarRecords,R.drawable.calendar_empty_journal,114);
                gap(calendarRecords,12);
                TextView empty=text(calendarTruncated?"当前结果中没有这一天的安排，整月数据尚未显示完整。":"这一天没有提醒安排或完成记录",13,MUTED,false);
                empty.setGravity(Gravity.CENTER);calendarRecords.addView(empty);gap(calendarRecords,24);
            }
            return;
        }
        int pageCount=(entries.size()+19)/20;recordPage=Math.max(0,Math.min(recordPage,pageCount-1));
        for(int i=recordPage*20;i<Math.min(entries.size(),(recordPage+1)*20);i++){
            JSONObject entry=entries.get(i);boolean reminder=entry.optString("_type").equals("notification"),planned=entry.optString("_type").equals("planned");
            LinearLayout card=column();card.setPadding(dp(10),dp(10),dp(10),dp(10));card.setMinimumHeight(dp(64));
            GradientDrawable surface=shape(PAPER,12);surface.setStroke(dp(1),0xffe8ebe2);
            card.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22527a58),surface,null));
            LinearLayout.LayoutParams cardLp=new LinearLayout.LayoutParams(-1,-2);cardLp.bottomMargin=dp(7);calendarRecords.addView(card,cardLp);
            LinearLayout line=row();line.addView(art(calendarRecordArt(entry),32));gapHorizontal(line,10);
            LinearLayout words=column(),top=row();
            TextView time=text(RecordDates.localTime(entry.optString("_at"),ZoneId.systemDefault()),13,INK,false);
            top.addView(time,new LinearLayout.LayoutParams(dp(48),-2));
            TextView title=text(entry.optString("task_title","已归档的提醒"),15,INK,true);title.setMaxLines(2);title.setEllipsize(TextUtils.TruncateAt.END);
            top.addView(title,new LinearLayout.LayoutParams(0,-2,1));words.addView(top);gap(words,5);
            String summary=calendarStateLabel(entry)+(planned?" · 计划安排":reminder?" · 提醒记录":" · 完成记录");
            words.addView(text(summary,11,calendarStateColor(entry),false));
            if(reminder&&!entry.optString("status").equals("sent")){
                gap(words,3);words.addView(text(notificationLabel(entry),11,entry.optString("status").equals("failed")?RED:MUTED,false));
            }
            if(!planned&&!reminder){
                String message=entry.optString("text").replace('\n',' ');JSONArray photos=entry.optJSONArray("media_ids");
                if(photos!=null&&photos.length()>0)message+=(message.isEmpty()?"":" · ")+photos.length()+" 张照片";
                if(!message.isEmpty()){TextView preview=text(message,11,MUTED,false);preview.setMaxLines(1);preview.setEllipsize(TextUtils.TruncateAt.END);gap(words,4);words.addView(preview);}
            }
            line.addView(words,new LinearLayout.LayoutParams(0,-2,1));gapHorizontal(line,6);
            line.addView(new GardenIconView(this,"chevron_right",INK),new LinearLayout.LayoutParams(dp(16),dp(16)));card.addView(line);card.setFocusable(true);
            if(planned){
                card.setOnClickListener(v->{String taskId=entry.optString("task_id"),monthKey=calendarKey();LocalDate day=calendarDate;int request=++plannedTaskRequest;card.setEnabled(false);
                    run(null,a->a.json("GET","/api/v1/tasks/"+taskId,null),result->{card.setEnabled(true);if(request!=plannedTaskRequest||!monthKey.equals(calendarKey())||!day.equals(calendarDate))return;selected=result;detailBack="history";navigate("detail");},
                        failure->{card.setEnabled(true);if(request!=plannedTaskRequest||!monthKey.equals(calendarKey())||!day.equals(calendarDate))return;showError(failure.getMessage()==null?"无法读取这条提醒，请重试":failure.getMessage());});
                });
            }else card.setOnClickListener(v->{selectedRecord=entry;recordBack="history";navigate("record");});
        }
        if(stale){gap(calendarRecords,8);note(calendarRecords,"上次读取的状态可能已变化。");}
        if(pageCount>1){
            TextView limit=text("每页最多 20 条",11,MUTED,false);limit.setGravity(Gravity.CENTER);calendarRecords.addView(limit);gap(calendarRecords,4);
            LinearLayout paging=row();Button previous=smallButton("‹ 上一页"),next=smallButton("下一页 ›");
            previous.setEnabled(recordPage>0);next.setEnabled(recordPage+1<pageCount);previous.setAlpha(recordPage>0?1f:.4f);next.setAlpha(recordPage+1<pageCount?1f:.4f);
            paging.addView(previous);TextView count=text((recordPage+1)+" / "+pageCount,14,HOME_TITLE,false);count.setGravity(Gravity.CENTER);
            paging.addView(count,new LinearLayout.LayoutParams(0,-2,1));paging.addView(next);calendarRecords.addView(paging);
            previous.setOnClickListener(v->{recordPage--;drawCalendarRecords(entries,invalid);calendarRecords.requestRectangleOnScreen(new Rect(0,0,calendarRecords.getWidth(),dp(90)),false);});
            next.setOnClickListener(v->{recordPage++;drawCalendarRecords(entries,invalid);calendarRecords.requestRectangleOnScreen(new Rect(0,0,calendarRecords.getWidth(),dp(90)),false);});
        }else{
            gap(calendarRecords,14);TextView hint=text("点击安排或记录，回看每一件小事",11,MUTED,false);hint.setGravity(Gravity.CENTER);calendarRecords.addView(hint);
        }
    }
    // Record artwork is an identity decoration, never an inferred growth stage.
    private int calendarRecordArt(JSONObject entry){
        return entry.optString("_type").equals("notification")?R.drawable.calendar_mail:GardenFlowers.resourceFor(entry.optString("task_id"));
    }
    private void calendarArt(LinearLayout parent,int resource,int height){
        ImageView image=new ImageView(this);
        boolean illustration=resource==R.drawable.calendar_mail||resource==R.drawable.calendar_empty_journal;
        image.setImageBitmap(illustration?GardenArtCache.backdrop(getResources(),resource):GardenArtCache.load(getResources(),resource));
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        parent.addView(image,new LinearLayout.LayoutParams(-1,dp(height)));
    }
    private void calendarSkeleton(){
        for(int i=0;i<3;i++){
            LinearLayout row=row();row.setPadding(dp(12),dp(14),dp(12),dp(14));row.setBackground(shape(0xfff1f2ec,12));
            View icon=new View(this);icon.setBackground(shape(0xffe2e7dc,16));row.addView(icon,new LinearLayout.LayoutParams(dp(32),dp(32)));gapHorizontal(row,12);
            LinearLayout bars=column();View title=new View(this);title.setBackground(shape(0xffe2e7dc,5));bars.addView(title,new LinearLayout.LayoutParams(dp(108),dp(12)));gap(bars,9);
            View subtitle=new View(this);subtitle.setBackground(shape(0xffe7eadf,4));bars.addView(subtitle,new LinearLayout.LayoutParams(dp(156),dp(8)));row.addView(bars);
            row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(8);calendarRecords.addView(row,lp);
        }
    }
    private int calendarStateIndex(JSONObject entry){switch(entry.optString("state")){case "completed":return 1;case "pending":return 2;case "expired":return 3;default:return 0;}}
    private int calendarStateColor(JSONObject entry){return new int[]{INFO,GREEN,YELLOW,RED}[calendarStateIndex(entry)];}
    private String calendarStateLabel(JSONObject entry){switch(entry.optString("state")){case "completed":return "已完成";case "pending":return "未完成";case "expired":return "已过期";case "notified":return "已提醒";default:return "待提醒";}}
    private String notificationLabel(JSONObject item){switch(item.optString("status")){case "sent":return "邮件已发送";case "failed":return "邮件发送失败";case "pending":case "sending":return "邮件等待发送";default:return "邮件状态："+item.optString("status","未知");}}
    private void renderRecord(){
        boolean reminder=selectedRecord.optString("_type").equals("notification");
        String title=selectedRecord.optString("task_title","提醒详情"),status=selectedRecord.optString("status");
        boolean sent=status.equals("sent"),failed=status.equals("failed"),waiting=status.equals("pending")||status.equals("sending");
        gap(body,18);body.addView(homeTitle(reminder?"提醒记录":"完成记录",28));gap(body,10);
        body.addView(text(reminder?(sent?"这一件小事，已经发送提醒":failed?"这次提醒，暂时没有发出":waiting?"提醒已安排，正在等待发送":"回看这一次邮件提醒"):"这一件小事，已经完成啦",14,HOME_TITLE,false));gap(body,22);
        LinearLayout info=card(body),identity=row();
        identity.addView(art(calendarRecordArt(selectedRecord),52));gapHorizontal(identity,12);
        LinearLayout words=column();TextView taskTitle=homeTitle(title,20);
        if(isFixedMailRecord()){taskTitle.setMaxLines(2);taskTitle.setEllipsize(TextUtils.TruncateAt.END);taskTitle.setContentDescription(title);}
        words.addView(taskTitle);
        if(!reminder){gap(words,8);words.addView(text(formatFull(selectedRecord.optString("_at")),13,MUTED,false));}
        identity.addView(words,new LinearLayout.LayoutParams(0,-2,1));info.addView(identity);gap(info,12);
        String badge=reminder?(sent?"已发送":failed?"发送失败":waiting?"等待发送":notificationLabel(selectedRecord)):"已完成";
        int color=reminder?(failed?RED:sent?INFO:YELLOW):GREEN;
        LinearLayout statusRow=row();statusRow.addView(recordBadge(badge,color,reminder?(failed?"info":sent?"send":"clock"):"circle_check"));
        if(reminder&&selectedRecord.has("state")){gapHorizontal(statusRow,10);statusRow.addView(text(calendarStateLabel(selectedRecord),12,calendarStateColor(selectedRecord),false));}
        info.addView(statusRow);
        if(reminder){
            gap(info,14);calendarDivider(info);
            recordInfoRow(info,"clock","计划提醒",formatFull(selectedRecord.optString("scheduled_at")));
            calendarDivider(info);
            String actual=selectedRecord.optString("sent_at");
            recordInfoRow(info,"send","实际发送",!actual.isEmpty()?formatFull(actual):failed?"未发送成功":sent?"未记录发送时间":"尚未发送");
            if(failed||waiting){
                gap(info,8);LinearLayout message=column();message.setPadding(dp(12),dp(12),dp(12),dp(12));message.setBackground(shape(failed?0xffffeee5:0xffedf3e7,12));
                if(failed){message.addView(text("发生了什么",14,RED,true));gap(message,6);}
                String error=selectedRecord.optString("error");
                message.addView(text(failed?(error.isEmpty()?"邮件暂未发送成功，请稍后查看发送情况。":error):"提醒已安排，正在等待发送。",13,failed?RED:HOME_TITLE,false));info.addView(message);
            }
            gap(body,10);
            if(isFixedMailRecord()){
                // The illustration gives up height first; the delivery message and action stay visible.
                ImageView mail=new ImageView(this);
                mail.setImageBitmap(GardenArtCache.backdrop(getResources(),R.drawable.calendar_mail));
                mail.setScaleType(ImageView.ScaleType.FIT_CENTER);mail.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                body.addView(mail,new LinearLayout.LayoutParams(-1,0,1));
            }else calendarArt(body,R.drawable.calendar_mail,132);
            gap(body,12);
            if(sent){
                TextView delivery=text("服务器已发出邮件，收件情况请以邮箱为准。",13,HOME_TITLE,false);delivery.setGravity(Gravity.CENTER);delivery.setPadding(dp(16),dp(16),dp(16),dp(16));delivery.setBackground(shape(0xffedf3e7,14));body.addView(delivery);gap(body,24);
            }
        }else{
            String message=selectedRecord.optString("text");LinearLayout noteCard=card(body);
            if(!message.isEmpty()){
                noteCard.addView(text("备注",12,MUTED,false));gap(noteCard,10);
                TextView note=text(message,15,INK,false);note.setTextIsSelectable(true);note.setPadding(dp(12),dp(14),dp(12),dp(14));note.setBackground(shape(0xfff5f4ee,12));noteCard.addView(note);
            }else{
                noteCard.setGravity(Gravity.CENTER_HORIZONTAL);noteCard.addView(art(R.drawable.garden_sprout_v3,46));gap(noteCard,12);
                TextView noNote=text("这次完成没有填写文字备注",14,MUTED,false);noNote.setGravity(Gravity.CENTER);noteCard.addView(noNote);gap(noteCard,12);
            }
            JSONArray photos=selectedRecord.optJSONArray("media_ids");int photoCount=photos==null?0:photos.length();
            for(int i=0;i<photoCount;i++){
                String id=photos.optString(i);if(id.isEmpty())continue;
                RecordPhotoView image=new RecordPhotoView(this,false);body.addView(image,new LinearLayout.LayoutParams(-1,dp(244)));
                Runnable open=()->{photoId=id;photoTitle=selectedRecord.optString("task_title");photoBack="record";navigate("photo");};
                loadRecordPhoto(image,id,open);gap(body,8);
                LinearLayout hint=row();hint.setGravity(Gravity.CENTER);hint.addView(new GardenIconView(this,"search",MUTED),new LinearLayout.LayoutParams(dp(16),dp(16)));gapHorizontal(hint,5);hint.addView(text("点击照片查看大图",12,MUTED,false));body.addView(hint);gap(body,16);
            }
            if(photoCount==0){TextView noPhoto=text("本次没有附上照片",12,MUTED,false);noPhoto.setGravity(Gravity.CENTER);noPhoto.setPadding(0,dp(16),0,dp(24));body.addView(noPhoto);}
        }
        Button task=button("查看这条提醒",true);task.setTextSize(16);task.setMinHeight(dp(52));body.addView(task);
        String taskId=selectedRecord.optString("task_id");
        if(taskId.isEmpty()){task.setEnabled(false);task.setAlpha(.5f);gap(body,8);note(body,"这条记录没有关联的提醒编号，记录本身仍可查看。更新服务器后可关联提醒详情。");}
        else task.setOnClickListener(v->run(task,a->{try{return a.json("GET","/api/v1/tasks/"+taskId,null);}catch(Api.Failure failure){if(failure.status==404)return object("_missing",true);throw failure;}},result->{if(result.optBoolean("_missing")){showError("暂时无法读取这条提醒。记录和照片仍保留在这里；已归档详情需要新版服务器。");return;}selected=result;detailBack="record";navigate("detail");}));
        if(hasRecordFooter()&&!isFixedMailRecord()){
            gap(body,16);
            body.addView(new View(this),new LinearLayout.LayoutParams(-1,0,1));
            ImageView foliage=new ImageView(this);
            foliage.setImageBitmap(GardenArtCache.backdrop(getResources(),R.drawable.sowing_footer_foliage));
            foliage.setAdjustViewBounds(true);foliage.setScaleType(ImageView.ScaleType.FIT_CENTER);
            foliage.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams foliageLp=new LinearLayout.LayoutParams(-1,-2);
            foliageLp.setMargins(-dp(16),0,-dp(16),0);body.addView(foliage,foliageLp);
        }
    }
    private boolean hasRecordFooter(){
        JSONArray photos=selectedRecord.optJSONArray("media_ids");
        return selectedRecord.optString("_type").equals("notification")||photos==null||photos.length()==0;
    }
    private boolean isFixedMailRecord(){
        return screen.equals("record")&&selectedRecord.optString("_type").equals("notification")&&selectedRecord.optString("status").equals("sent");
    }
    private LinearLayout recordBadge(String label,int color,String icon){
        LinearLayout badge=row();badge.setPadding(dp(12),dp(8),dp(14),dp(8));
        badge.setBackground(shape((color&0x00ffffff)|0x18000000,22));
        badge.addView(new GardenIconView(this,icon,color),new LinearLayout.LayoutParams(dp(18),dp(18)));gapHorizontal(badge,6);
        badge.addView(text(label,13,color,true));return badge;
    }
    private void recordInfoRow(LinearLayout parent,String icon,String label,String value){
        LinearLayout row=row();row.setMinimumHeight(dp(54));row.setPadding(0,dp(10),0,dp(10));
        row.addView(new GardenIconView(this,icon,HOME_TITLE),new LinearLayout.LayoutParams(dp(22),dp(22)));gapHorizontal(row,10);
        row.addView(text(label,13,HOME_TITLE,false),new LinearLayout.LayoutParams(0,-2,1));
        TextView detail=text(value,13,INK,false);detail.setGravity(Gravity.END);
        row.addView(detail,new LinearLayout.LayoutParams(0,-2,1.6f));parent.addView(row);
    }
    private void renderPhoto(){
        gap(body,18);body.addView(homeTitle("完成凭证",28));gap(body,10);body.addView(text(photoTitle,16,HOME_TITLE,false));gap(body,18);
        RecordPhotoView photo=new RecordPhotoView(this,true);
        int height=Math.max(320,Math.min(680,getResources().getConfiguration().screenHeightDp-210));
        body.addView(photo,new LinearLayout.LayoutParams(-1,dp(height)));loadRecordPhoto(photo,photoId,null);
    }
    private void loadRecordPhoto(RecordPhotoView target,String id,Runnable open){
        int account=accountEpoch,page=pageEpoch,maxSize=screen.equals("photo")?1600:640;
        int generation=photoRequests.generation();
        Object request=new Object();target.setTag(request);target.loading();Api api=new Api(session.base,session.token);
        boolean accepted=photoRequests.execute(generation,()->{
            try{
                byte[] bytes=api.media(id);if(!photoRequests.isCurrent(generation))return;
                BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
                BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
                if(options.outWidth<=0||options.outHeight<=0)throw new IOException("无法读取照片");
                options.inSampleSize=1;while(Math.max(options.outWidth,options.outHeight)/options.inSampleSize>maxSize)options.inSampleSize*=2;
                options.inJustDecodeBounds=false;Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
                if(bitmap==null)throw new IOException("无法读取照片");
                ui.post(()->{
                    if(!isDestroyed()&&photoRequests.isCurrent(generation)&&accountEpoch==account&&pageEpoch==page&&target.getTag()==request&&target.isAttachedToWindow())target.ready(bitmap,open);
                    else bitmap.recycle();
                });
            }catch(Exception failure){
                ui.post(()->{if(!isDestroyed()&&photoRequests.isCurrent(generation)&&accountEpoch==account&&pageEpoch==page&&target.getTag()==request&&target.isAttachedToWindow())
                    target.failed(()->loadRecordPhoto(target,id,open));});
            }
        });
        if(!accepted)target.failed(()->loadRecordPhoto(target,id,open));
    }
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
    private void clearAccountData(){gardenSelection=null;homeSearchExpanded=false;growthAnimationState.clear();gardenPage=0;gardenSelectedPlantId="";gardenMemoriesExpanded=false;gardenContent=null;gardenPreview=null;recordBack="history";completedRecord=new JSONObject();taskData=new JSONArray();taskLoadRequest++;pauseInFlight.clear();selected=new JSONObject();draft=new JSONObject();selectedRecord=new JSONObject();invalidateCalendar();calendarDate=LocalDate.now();calendarMonth=YearMonth.now();recordPage=0;detailBack="home";photoBack="record";photoId="";photoTitle="";calendarGrid=null;calendarRecords=null;calendarFeedback=null;calendarCaption=null;calendarRefresh=null;restoredForm=null;photoFile=null;cameraFile=null;search="";filter="待完成";aiText="";aiWarning="";completionText="";taskList=null;manualVisible=false;rulesExpanded=false;advancedExpanded=false;aiExpanded=true;}
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
    private LinearLayout card(LinearLayout parent){LinearLayout c=column();c.setPadding(dp(16),dp(14),dp(16),dp(14));GradientDrawable surface=shape(PAPER,14);surface.setStroke(dp(1),LINE);c.setBackground(surface);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,0,0,dp(12));parent.addView(c,lp);return c;}
    private TextView text(String value,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(value);t.setIncludeFontPadding(false);t.setTextSize(size);t.setTextColor(color);t.setLineSpacing(dp(2),1f);if(bold)t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));return t;}
    private Button button(String caption,boolean primary){Button b=new Button(this);b.setAllCaps(false);b.setText(caption);b.setTextSize(14);b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));b.setTextColor(primary?PAPER:BLUE);b.setMinimumWidth(0);b.setMinWidth(dp(48));b.setMinHeight(dp(48));b.setPadding(dp(12),dp(8),dp(12),dp(8));b.setElevation(0);b.setStateListAnimator(null);b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22527a58),shape(primary?BLUE:PALE,12),null));b.setLayoutParams(new LinearLayout.LayoutParams(-1,-2));return b;}
    private Button smallButton(String caption){Button b=button(caption,false);b.setTextSize(13);b.setMinHeight(dp(48));b.setMinWidth(dp(48));b.setPadding(dp(10),dp(6),dp(10),dp(6));b.setLayoutParams(new LinearLayout.LayoutParams(-2,dp(48)));return b;}
    private GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private EditText field(LinearLayout parent,String key,String caption,String content,String hint,int lines){if(!caption.isEmpty())label(parent,caption);EditText e=new EditText(this);styleField(e);e.setHint(hint);e.setText(content);e.setGravity(Gravity.TOP|Gravity.START);e.setMinLines(lines);e.setMaxLines(lines==1?1:7);e.setSingleLine(lines==1);e.setInputType(android.text.InputType.TYPE_CLASS_TEXT|(lines>1?android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE:0));fields.put(key,e);parent.addView(e,new LinearLayout.LayoutParams(-1,-2));gap(parent,10);return e;}
    private void styleField(EditText e){GradientDrawable bg=shape(PAPER,14);bg.setStroke(dp(1),LINE);e.setBackground(bg);e.setTextColor(INK);e.setHintTextColor(0xff7b887b);e.setTextSize(14);e.setPadding(dp(12),dp(10),dp(12),dp(10));e.setMinHeight(dp(48));e.setSelectAllOnFocus(false);}
    private void label(LinearLayout parent,String title){parent.addView(text(title,12,MUTED,true));gap(parent,5);}
    private void section(LinearLayout parent,String title){gap(parent,8);parent.addView(text(title,16,INK,true));gap(parent,12);}
    private void note(LinearLayout parent,String message){parent.addView(text(message,12,MUTED,false));gap(parent,10);}
    private void gap(LinearLayout parent,int height){Space s=new Space(this);parent.addView(s,new LinearLayout.LayoutParams(1,dp(height)));}
    private void gapHorizontal(LinearLayout parent,int width){Space s=new Space(this);parent.addView(s,new LinearLayout.LayoutParams(dp(width),1));}
    private void empty(LinearLayout parent,String title,String description){LinearLayout box=column();box.setGravity(Gravity.CENTER_HORIZONTAL);box.setPadding(dp(12),dp(18),dp(12),dp(20));box.addView(art(R.drawable.garden_seed_v3,96));gap(box,12);TextView t=text(title,17,INK,true);t.setGravity(Gravity.CENTER);box.addView(t);gap(box,8);TextView hint=text(description,13,MUTED,false);hint.setGravity(Gravity.CENTER);box.addView(hint);parent.addView(box);}
    private Spinner spinner(String[] labels){Spinner s=new Spinner(this);ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,labels);adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);s.setAdapter(adapter);s.setMinimumHeight(dp(52));s.setBackground(shape(PAPER,14));s.setPadding(dp(12),0,dp(12),0);return s;}
    private AdapterView.OnItemSelectedListener selection(Runnable action){return new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int i,long id){action.run();}public void onNothingSelected(AdapterView<?> p){}};}
    private TextWatcher watcher(Runnable action){return new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){action.run();}public void afterTextChanged(Editable e){}};}
    private void dateField(EditText e){e.setFocusable(false);e.setOnClickListener(v->{LocalDate date;try{date=LocalDate.parse(e.getText());}catch(Exception x){date=LocalDate.now();}new DatePickerDialog(this,(picker,y,m,d)->e.setText(LocalDate.of(y,m+1,d).toString()),date.getYear(),date.getMonthValue()-1,date.getDayOfMonth()).show();});e.setOnLongClickListener(v->{e.setText("");toast("日期已清除");return true;});}
    private void number(EditText e){e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);}
    private String value(String key){EditText field=fields.get(key);return field==null?"":field.getText().toString().trim();}
    private String formValue(String key,String fallback){return restoredForm==null?fallback:restoredForm.optString(key,fallback);}
    private int dp(int x){return Math.round(x*getResources().getDisplayMetrics().density);}
    private void showError(String message){errorView.setText(message);errorView.setVisibility(View.VISIBLE);if(screen.equals("garden")&&gardenDrawer!=null)gardenDrawer.setExpanded(true,true);errorView.announceForAccessibility(message);toast(message);}
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
