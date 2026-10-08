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
    private final SharedPreferences.OnSharedPreferenceChangeListener reminderSyncListener = (preferences, key) -> {
        if ("last_sync".equals(key)) ui.post(this::refreshVisibleReminderTimes);
    };
    private Session session;
    private AppUpdater updater;
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
    private final ClarificationFlow clarificationFlow = new ClarificationFlow();
    private JSONObject aiQuestion;
    private String aiAnswerText = "", aiReferenceTime = "";
    private boolean editing = false, manualVisible = false, taskBusy = false;
    private boolean taskLoading;
    private String taskNotice="";
    private boolean rulesExpanded = false, advancedExpanded = false, aiExpanded = true;
    private boolean editorSetupPromptPending;
    private SeedCompanionView editorCompanion;
    private Rect editorCompanionEntrance;
    private LinearLayout editorFlowersPreview,editorPlanContent,editorConfirmBar;
    private Runnable editorDialogueTick;
    private boolean editorConfirmed;
    private String reminderSettingsBack = "settings";
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
    private AlertDialog profileDialog;
    private boolean motionHostActive;
    private long codeAvailableAt;
    private String authMode = "login", authEmail = "", homeNotice = "";
    private boolean authBusy, automaticLoginAttempted, credentialWritePending;
    private int authRequest;
    private final Map<String,String> waterRequests = new HashMap<>();
    private String plantRequestId = "";
    private String plantTitle = "";
    private String gardenFocusPlantId = "";
    private boolean gardenCareBusy;

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
            restoreClarification(state);
            aiText = state.getString("aiText", ""); aiWarning = state.getString("aiWarning", ""); completionText = state.getString("completionText", "");
            String p = state.getString("photo", ""), c = state.getString("camera", "");
            if (!p.isEmpty()) photoFile = new File(p); if (!c.isEmpty()) cameraFile = new File(c);
            codeAvailableAt = state.getLong("codeAvailableAt");
            authMode=state.getString("authMode","login");authEmail=state.getString("authEmail","");
            automaticLoginAttempted=state.getBoolean("automaticLoginAttempted");homeNotice=state.getString("homeNotice","");
            reminderSettingsBack=state.getString("reminderSettingsBack","settings");editorSetupPromptPending=state.getBoolean("editorSetupPromptPending");
            plantRequestId=state.getString("plantRequestId","");plantTitle=state.getString("plantTitle","");gardenFocusPlantId=state.getString("gardenFocusPlantId","");
            try{JSONObject pending=new JSONObject(state.getString("waterRequests","{}"));for(Iterator<String> keys=pending.keys();keys.hasNext();){String key=keys.next();waterRequests.put(key,pending.optString(key));}}catch(JSONException ignored){}
        }
        if (session.base.isEmpty()) screen = "server";
        else if (session.token.isEmpty() && !screen.equals("server") && !screen.equals("login")) screen = "home";
        getSharedPreferences("reminder_execution_v1", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(reminderSyncListener);
        ReminderCoordinator.attach(this,session);
        updater = new AppUpdater(this, session);
        render();
        openReminderIntent(getIntent());
        if(screen.equals("home")&&session.token.isEmpty()&&session.autoLogin&&session.hasCredentials()&&!automaticLoginAttempted)autoLogin();
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);openReminderIntent(intent);}
    private void openReminderIntent(Intent intent){
        if(intent==null||session.token.isEmpty()||!ReminderCoordinator.acceptsIntent(session,intent.getStringExtra("reminder_scope")))return;
        String task=intent.getStringExtra("reminder_task_id");if(task==null||task.isEmpty())return;
        intent.removeExtra("reminder_task_id");intent.removeExtra("reminder_scope");
        run(null,a->a.json("GET","/api/v1/tasks/"+Uri.encode(task),null),result->{selected=result;detailBack="home";navigate("detail");});
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        if(!editorConfirmed)captureTransient();
        out.putString("screen", editorConfirmed?(editing?"detail":"home"):screen); out.putString("selected", selected.toString()); out.putString("draft", draft.toString());
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
        saveClarification(out);
        out.putString("aiText", aiText); out.putString("aiWarning", aiWarning); out.putString("completionText", completionText);
        out.putString("photo", photoFile == null ? "" : photoFile.getAbsolutePath()); out.putString("camera", cameraFile == null ? "" : cameraFile.getAbsolutePath());
        out.putLong("codeAvailableAt", codeAvailableAt); super.onSaveInstanceState(out);
        out.putString("authMode",authMode);out.putString("authEmail",authEmail);out.putString("homeNotice",homeNotice);
        out.putString("reminderSettingsBack",reminderSettingsBack);out.putBoolean("editorSetupPromptPending",editorSetupPromptPending);
        out.putBoolean("automaticLoginAttempted",automaticLoginAttempted&&!authBusy);
        out.putString("plantRequestId",plantRequestId);out.putString("plantTitle",plantTitle);out.putString("gardenFocusPlantId",gardenFocusPlantId);out.putString("waterRequests",new JSONObject(waterRequests).toString());
    }
    @Override protected void onResume() { super.onResume(); setMotionHostActive(true); if(session!=null)ReminderCoordinator.onResume(this,session); if(updater!=null)updater.resume(); }
    @Override protected void onPause() { if(updater!=null)updater.pause(); setMotionHostActive(false); super.onPause(); }
    @Override public void onDestroy() { if(updater!=null)updater.close(); getSharedPreferences("reminder_execution_v1", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(reminderSyncListener); dismissProfileDialog(); stopPageMotion(); super.onDestroy(); workers.shutdownNow(); photoRequests.close(); ui.removeCallbacksAndMessages(null); }
    private void refreshVisibleReminderTimes() {
        if (isDestroyed() || !motionHostActive || session == null || session.token.isEmpty() || !screen.equals("home")) return;
        if (!ReminderCoordinator.acceptsIntent(session, getSharedPreferences("reminder_execution_v1", MODE_PRIVATE).getString("scope", ""))) return;
        loadTasks(null);
    }
    private void setMotionHostActive(boolean active) {
        motionHostActive=active;
        if(growthView!=null)growthView.setHostActive(active);
        if(editorCompanion!=null)editorCompanion.setHostActive(active);
        if(!active&&editorDialogueTick!=null)ui.removeCallbacks(editorDialogueTick);
        if(active&&editorDialogueTick!=null){ui.removeCallbacks(editorDialogueTick);ui.post(editorDialogueTick);}
        if(gardenScene!=null)gardenScene.setHostActive(active);
        if(!active&&body!=null){body.animate().cancel();body.setAlpha(1f);body.setTranslationY(0f);}
    }
    private void stopPageMotion() {
        if(editorCompanion!=null){editorCompanion.setHostActive(false);editorCompanion=null;}
        editorFlowersPreview=null;editorPlanContent=null;editorConfirmBar=null;
        if(editorDialogueTick!=null){ui.removeCallbacks(editorDialogueTick);editorDialogueTick=null;}
        if(growthView!=null){growthView.setOnPhaseChangedListener(null);growthView.setHostActive(false);growthView=null;}
        if(gardenScene!=null){gardenScene.setHostActive(false);gardenScene=null;}
        if(body!=null)body.animate().cancel();
    }
    @Override public void onBackPressed() {
        if(!canLeaveAuthentication())return;
        if(screen.equals("plant") || screen.equals("grown") && completedRecord.optBoolean("_watering")){navigate("garden");return;}
        if(screen.equals("garden")&&gardenMemoriesExpanded&&gardenDrawer!=null){gardenDrawer.setExpanded(false,true);return;}
        if (screen.equals("home") || (screen.equals("server") && session.base.isEmpty())) { super.onBackPressed(); return; }
        if (screen.equals("editor")) {
            if(taskBusy){toast("正在处理提醒，请稍等");return;}
            confirmAction("离开编辑？","还没有保存的修改将丢弃。","继续编辑","离开",false,()->navigate(editing?"detail":"home"));return;
        }
        navigate(screen.equals("reminders") ? reminderSettingsBack : screen.equals("photo") ? photoBack : screen.equals("record") ? recordBack : screen.equals("detail") ? detailBack : screen.equals("complete") ? "detail" : "home");
    }
    private void navigate(String next) {
        if(!canLeaveAuthentication())return;
        authRequest++;authBusy=false;
        if(next.equals("reminders"))reminderSettingsBack=screen.equals("editor")?"editor":screen.equals("home")?"home":"settings";
        boolean changed=!screen.equals(next);captureTransient();if(changed)gardenCareBusy=false;screen=next;render();
        if(changed&&motionHostActive&&!next.equals("grown")&&ValueAnimator.areAnimatorsEnabled()){
            body.setAlpha(0f);body.setTranslationY(dp(5));body.animate().alpha(1f).translationY(0f).setDuration(180)
                    .setInterpolator(new android.view.animation.PathInterpolator(.22f,1f,.36f,1f)).start();
        }
    }
    private void captureTransient() {
        if (screen.equals("editor")&&!editorConfirmed) {
            restoredForm = new JSONObject();
            try {
                for (Map.Entry<String, EditText> entry : fields.entrySet()) restoredForm.put(entry.getKey(), entry.getValue().getText().toString());
                if (kindPicker != null) restoredForm.put("kind_index", kindPicker.getSelectedItemPosition());
                if (modePicker != null) restoredForm.put("mode_index", modePicker.getSelectedItemPosition());
                JSONArray days = new JSONArray(); if (weekdays != null) for (int i=0;i<7;i++) if (weekdays[i].isChecked()) days.put(i+1);
                restoredForm.put("weekdays", days);
            } catch (Exception ignored) {}
            if (fields.containsKey("ai")) aiText = value("ai");
            if (fields.containsKey("ai_answer")) aiAnswerText = value("ai_answer");
        }
        if (screen.equals("complete") && fields.containsKey("completion")) completionText = value("completion");
        if (screen.equals("login") && fields.containsKey("email")) authEmail=value("email");
        if (screen.equals("plant") && fields.containsKey("plant_title")) plantTitle=value("plant_title");
    }
    private void render() {
        stopPageMotion();
        photoRequests.invalidate();
        dismissProfileDialog();
        GardenFlowers.configure(this,session.base,session.email);
        if(session.token.isEmpty()&&!Arrays.asList("home","login","server").contains(screen))screen="home";
        pageEpoch++; clarificationFlow.failed(); taskBusy = false; fields.clear(); kindPicker = null; modePicker = null; weekdays = null;
        boolean edgeDecoration=screen.equals("record")||screen.equals("photo")||screen.equals("editor");
        boolean fixedMail=isFixedMailRecord();
        boolean recordFooter=screen.equals("record")&&hasRecordFooter()&&!fixedMail;
        boolean sowingConfirmation=screen.equals("editor")&&manualVisible;
        // Only decoration enters system-bar space; interactive content keeps its safe insets.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(),!edgeDecoration&&Build.VERSION.SDK_INT<35);
        getWindow().setStatusBarColor(edgeDecoration?Color.TRANSPARENT:BG);
        getWindow().setNavigationBarColor(edgeDecoration?Color.TRANSPARENT:BG);
        if(Build.VERSION.SDK_INT>=29)getWindow().setNavigationBarContrastEnforced(!edgeDecoration);
        root = column();root.setBackgroundColor(edgeDecoration?Color.TRANSPARENT:BG);
        if(edgeDecoration){
            FrameLayout page=new FrameLayout(this);page.setBackgroundColor(BG);
            if(fixedMail||screen.equals("editor")&&!sowingConfirmation){
                ImageView grass=new ImageView(this);
                grass.setImageBitmap(GardenArtCache.backdrop(getResources(),R.drawable.sowing_footer_foliage));
                // Scale against the viewport width, not the drawable's wrap-content height.
                grass.setScaleType(ImageView.ScaleType.MATRIX);
                grass.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{
                    android.graphics.drawable.Drawable drawable=grass.getDrawable();
                    if(drawable==null||r<=l||b<=t)return;
                    float scale=(r-l)/(float)drawable.getIntrinsicWidth();
                    Matrix matrix=new Matrix();matrix.setScale(scale,scale);
                    matrix.postTranslate(0,(b-t)-drawable.getIntrinsicHeight()*scale);
                    grass.setImageMatrix(matrix);
                });
                grass.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                page.addView(grass,new FrameLayout.LayoutParams(-1,-1));
            }
            ImageView leaves=new ImageView(this);
            leaves.setImageBitmap(GardenArtCache.backdrop(getResources(),R.drawable.sowing_corner_leaves));
            leaves.setScaleType(ImageView.ScaleType.FIT_START);leaves.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            FrameLayout.LayoutParams leavesLp=new FrameLayout.LayoutParams(dp(196),dp(131),Gravity.TOP|Gravity.END);
            page.addView(leaves,leavesLp);
            page.addView(root,new FrameLayout.LayoutParams(-1,-1));setContentView(page);
        }else setContentView(root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if(Build.VERSION.SDK_INT>=30&&(edgeDecoration||Build.VERSION.SDK_INT>=35)){
                android.graphics.Insets i=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()|WindowInsets.Type.ime());
                boolean keyboard=insets.isVisible(WindowInsets.Type.ime());
                v.setPadding(i.left,i.top,i.right,(recordFooter||sowingConfirmation)&&!keyboard?0:i.bottom);
                if(sowingConfirmation&&editorConfirmBar!=null)editorConfirmBar.setPadding(dp(16),dp(12),dp(16),dp(10)+(keyboard?0:i.bottom));
            }else if(edgeDecoration){
                int bottom=insets.getSystemWindowInsetBottom();
                v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),(recordFooter||sowingConfirmation)&&bottom<dp(100)?0:bottom);
                if(sowingConfirmation&&editorConfirmBar!=null)editorConfirmBar.setPadding(dp(16),dp(12),dp(16),dp(10)+(bottom<dp(100)?bottom:0));
            }
            return insets;
        });
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false); scroll.setVerticalScrollBarEnabled(false);
        if(!fixedMail&&!screen.equals("garden"))root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        body = column(); body.setPadding(dp(16), dp(12), dp(16), dp(20));body.setClipChildren(false);body.setClipToPadding(false);
        if(sowingConfirmation){body.setFocusableInTouchMode(true);body.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);body.requestFocus();}
        if(recordFooter){body.setPadding(dp(16),dp(12),dp(16),0);body.setClipToPadding(false);}
        if(screen.equals("editor"))body.setPadding(dp(16),dp(4),dp(16),0);
        if((screen.equals("home")&&!session.token.isEmpty())||screen.equals("garden")||screen.equals("history")||screen.equals("settings"))body.setPadding(0,0,0,0);
        if(fixedMail||screen.equals("garden"))root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        else scroll.addView(body,new ScrollView.LayoutParams(-1,-2));
        gardenContent=null; gardenPreview=null; gardenSelection=null; gardenDrawer=null;
        if (!Arrays.asList("home", "garden", "history", "settings", "login", "server").contains(screen)) {
            if(screen.equals("editor")||screen.equals("record")||screen.equals("photo")||screen.equals("grown")){
                LinearLayout back=row();back.setMinimumHeight(dp(48));back.addView(new GardenIconView(this,"back",INK),new LinearLayout.LayoutParams(dp(20),dp(20)));gapHorizontal(back,5);back.addView(text("返回",14,INK,false));back.setContentDescription("返回");back.setFocusable(true);back.setOnClickListener(v->onBackPressed());
                if(sowingConfirmation){back.setPadding(dp(16),0,0,0);root.addView(back,0,new LinearLayout.LayoutParams(dp(96),dp(48)));}else body.addView(back,new LinearLayout.LayoutParams(dp(80),dp(48)));
            }else if(!screen.equals("detail")&&!screen.equals("complete")){
                TextView back = text("‹  返回", 15, BLUE, true); back.setMinHeight(dp(48)); back.setGravity(Gravity.CENTER_VERTICAL); back.setOnClickListener(v -> onBackPressed()); body.addView(back);
            }
        }
        errorView = text("", 14, RED, false); errorView.setPadding(dp(14), dp(12), dp(14), dp(12)); errorView.setBackground(shape(0xffffeeee, 14)); errorView.setVisibility(View.GONE); body.addView(errorView);
        switch (screen) {
            case "server": renderServer(); break;
            case "login": renderLogin(); break;
            case "history": renderHistory(); break;
            case "garden": renderGarden(); break;
            case "plant": renderPlantShop(); break;
            case "reminders": renderReminderSettings(); break;
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
    private void backLink(String caption,Runnable action){
        LinearLayout back=row();back.setMinimumHeight(dp(48));back.addView(new GardenIconView(this,"back",INK),new LinearLayout.LayoutParams(dp(20),dp(20)));gapHorizontal(back,6);back.addView(text(caption,14,INK,false));back.setContentDescription(caption);back.setFocusable(true);back.setOnClickListener(v->action.run());body.addView(back,new LinearLayout.LayoutParams(-2,dp(48)));
    }
    private void illustratedHeading(String title,String subtitle,int picture){
        LinearLayout heading=row(),words=column();words.addView(homeTitle(title,24));gap(words,8);words.addView(text(subtitle,13,INK,false));heading.addView(words,new LinearLayout.LayoutParams(0,-2,1));gapHorizontal(heading,8);heading.addView(art(picture,86));body.addView(heading,new LinearLayout.LayoutParams(-1,-2));gap(body,20);
    }
    private void botanicalFooter(int picture,int height){
        botanicalFooter(picture,height,0);
    }
    private void botanicalFooter(int picture,int height,int accent){
        body.addView(new Space(this),new LinearLayout.LayoutParams(1,0,1));FrameLayout scene=new FrameLayout(this);ImageView plants=scenery(picture);plants.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);plants.setScaleType(ImageView.ScaleType.CENTER_CROP);if(picture==R.drawable.sowing_footer_foliage){plants.setScaleType(ImageView.ScaleType.MATRIX);plants.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{android.graphics.drawable.Drawable drawable=plants.getDrawable();if(drawable==null||r==l||b==t)return;float scale=Math.max((r-l)/(float)drawable.getIntrinsicWidth(),(b-t)/(float)drawable.getIntrinsicHeight());Matrix matrix=new Matrix();matrix.setScale(scale,scale);matrix.postTranslate(((r-l)-drawable.getIntrinsicWidth()*scale)/2f,(b-t)-drawable.getIntrinsicHeight()*scale);plants.setImageMatrix(matrix);});}scene.addView(plants,new FrameLayout.LayoutParams(-1,-1));
        if(accent!=0){ImageView detail=art(accent,Math.min(height,112));detail.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);scene.addView(detail,new FrameLayout.LayoutParams(dp(Math.min(height,112)),dp(Math.min(height,112)),Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL));}
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(height));lp.setMargins(-dp(16),dp(12),-dp(16),-dp(20));body.addView(scene,lp);
    }
    private Button linkButton(String label){Button link=smallButton(label);link.setBackground(new RippleDrawable(ColorStateList.valueOf(0x18527a58),shape(Color.TRANSPARENT,10),null));link.setLayoutParams(new LinearLayout.LayoutParams(-2,-2));return link;}
    private void moveErrorHere(LinearLayout parent){((ViewGroup)errorView.getParent()).removeView(errorView);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(12);parent.addView(errorView,lp);}
    private void infoStrip(LinearLayout parent,String icon,String caption,boolean warning){
        LinearLayout strip=row();strip.setPadding(dp(12),dp(10),dp(12),dp(10));strip.setBackground(shape(warning?0xfffbefe3:0xffeef3e8,12));strip.addView(new GardenIconView(this,icon,warning?YELLOW:BLUE),new LinearLayout.LayoutParams(dp(20),dp(20)));gapHorizontal(strip,10);strip.addView(text(caption,12,warning?YELLOW:INK,false),new LinearLayout.LayoutParams(0,-2,1));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(12);parent.addView(strip,lp);
    }
    private void adornField(EditText input,String icon,boolean secret){
        LinearLayout parent=(LinearLayout)input.getParent();int position=parent.indexOfChild(input);parent.removeView(input);
        LinearLayout container=row();GradientDrawable outline=shape(PAPER,14);outline.setStroke(dp(1),LINE);container.setBackground(outline);container.setPadding(dp(12),0,0,0);container.addView(new GardenIconView(this,icon,INK),new LinearLayout.LayoutParams(dp(20),dp(20)));
        input.setBackgroundColor(Color.TRANSPARENT);container.addView(input,new LinearLayout.LayoutParams(0,-2,1));parent.addView(container,position,new LinearLayout.LayoutParams(-1,-2));
        if(secret){
            FrameLayout toggle=new FrameLayout(this);toggle.setMinimumHeight(dp(48));toggle.setMinimumWidth(dp(48));toggle.setFocusable(true);toggle.setContentDescription("显示密码");toggle.setBackground(new RippleDrawable(ColorStateList.valueOf(0x18527a58),shape(Color.TRANSPARENT,12),null));
            toggle.addView(new GardenIconView(this,"eye",INK),new FrameLayout.LayoutParams(dp(21),dp(21),Gravity.CENTER));container.addView(toggle,new LinearLayout.LayoutParams(dp(48),dp(48)));
            toggle.setOnClickListener(v->{boolean visible=input.getTransformationMethod()!=null;int selection=input.getSelectionStart();input.setTransformationMethod(visible?null:android.text.method.PasswordTransformationMethod.getInstance());input.setSelection(Math.max(0,Math.min(selection,input.length())));toggle.setContentDescription(visible?"隐藏密码":"显示密码");toggle.removeAllViews();toggle.addView(new GardenIconView(this,visible?"eye_off":"eye",INK),new FrameLayout.LayoutParams(dp(21),dp(21),Gravity.CENTER));});
        }
    }
    private void header(String overline, String title, String subtitle) {
        if (!overline.isEmpty()) { TextView over = text(overline, 12, BLUE, true); over.setLetterSpacing(.09f); body.addView(over); gap(body, 8); }
        body.addView(text(title, 22, INK, true));
        if (!subtitle.isEmpty()) { gap(body,8); body.addView(text(subtitle, 14, MUTED, false)); }
        gap(body,16);
    }
    private void renderServer() {
        backLink("返回",()->navigate(session.token.isEmpty()?"home":"settings"));
        illustratedHeading(session.base.isEmpty()?"连接你的小花园":"更换服务器",session.base.isEmpty()?"先找到念念的提醒服务":"连接到另一座小花园",session.base.isEmpty()?R.drawable.garden_sprout_v3:R.drawable.garden_watering);
        field(body,"server","服务器地址",session.base,"https://你的域名 或 http://局域网IP:56507",1);
        note(body,"填入你的念念服务地址");
        moveErrorHere(body);
        TextView connection=text("",13,GREEN,false);connection.setVisibility(View.GONE);connection.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);body.addView(connection);
        Button test = button("测试连接", false); body.addView(test); test.setOnClickListener(v -> {
            connection.setVisibility(View.GONE);
            String address;
            try { address = Api.normalize(value("server")); } catch (Exception e) { showError(e.getMessage()); return; }
            run(test, a -> {try{return new Api(address, "").json("GET", "/health", null);}catch(Api.Failure e){throw new IOException(e.getMessage());}}, data -> {
                if (!"ok".equals(data.optString("status"))) { showError("地址可访问，但不是念念提醒服务"); return; }
                connection.setText("连接正常 · "+data.optString("version","服务正常"));connection.setPadding(0,0,0,dp(12));connection.setVisibility(View.VISIBLE);
            },e->{test.setText("重试连接");showError(failureMessage(e));});
        }); gap(body,12);
        Button save = button(session.base.isEmpty() ? "连接并继续" : "保存服务器地址", true); body.addView(save);
        save.setOnClickListener(v -> {
            String address;
            try { address = Api.normalize(value("server")); } catch (Exception e) { showError(e.getMessage()); return; }
            try {
                String previous=session.base;
                if(!previous.equals(address))ReminderCoordinator.clear(this);
                session.switchServer(address);
                if(!previous.equals(session.base)){accountEpoch++;clearAccountData();codeAvailableAt=0;if(updater!=null)updater.serverChanged();}
                homeNotice="";
                if(!session.token.isEmpty()){navigate("home");toast("服务器地址已保存");}
                else if(session.hasCredentials()){automaticLoginAttempted=false;autoLogin();}
                else{homeNotice="服务器地址已保存，请先登录。已有账号可验证邮箱设置一次密码。";navigate("home");}
            }catch(Exception e){ReminderCoordinator.attach(this,session);showError("服务器地址未保存，请重试");}
        });
        gap(body,16);infoStrip(body,"info",session.base.isEmpty()?"公网连接建议使用 HTTPS，保护账号与记录。":"同一地址保留登录；更换地址后重新验证账号。",false);
        Button debug=linkButton("本地调试说明  ›");body.addView(debug);
        TextView help=text("手机和电脑连接同一个 Wi-Fi，填写电脑的局域网 IP。手机上的 localhost 指向手机自己。",13,MUTED,false);help.setVisibility(View.GONE);body.addView(help);debug.setOnClickListener(v->{boolean open=help.getVisibility()!=View.VISIBLE;help.setVisibility(open?View.VISIBLE:View.GONE);debug.setText(open?"收起调试说明  ⌃":"本地调试说明  ›");});
        botanicalFooter(R.drawable.garden_home_hero_v3,152);
    }
    private void renderLogin() {
        boolean passwordLogin=authMode.equals("login"),reset=authMode.equals("reset");
        backLink(passwordLogin?"返回首页":"返回登录",()->{if(passwordLogin)navigate("home");else openAuth("login");});
        illustratedHeading(passwordLogin?"欢迎来到念念":reset?"重新设置密码":"种下你的第一份期待",passwordLogin?"你的小花园，在等你":reset?"验证邮箱，回到你的小花园":"注册 / 首次设置密码",passwordLogin?R.drawable.garden_flower_tulip:reset?R.drawable.garden_sprout_v3:R.drawable.sowing_seed_mascot);
        EditText email=field(body,"email","登录邮箱",authEmail.isEmpty()?session.email:authEmail,"你的邮箱地址",1);
        email.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        adornField(email,"mail",false);
        if(!passwordLogin){
            label(body,"邮箱验证码");LinearLayout codeRow=row();body.addView(codeRow);LinearLayout codeBox=column();codeRow.addView(codeBox,new LinearLayout.LayoutParams(0,-2,1));
            EditText code=field(codeBox,"code","","","输入 6 位验证码",1);code.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);code.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});code.setSaveEnabled(false);adornField(code,"circle_check",false);
            gapHorizontal(codeRow,8);Button send=button("发送验证码",false);LinearLayout.LayoutParams sendLp=new LinearLayout.LayoutParams(-2,dp(48));sendLp.bottomMargin=dp(10);codeRow.addView(send,sendLp);updateCodeButton(send);
            send.setOnClickListener(v->{String address=value("email");if(!android.util.Patterns.EMAIL_ADDRESS.matcher(address).matches()){email.setError("请填写有效的邮箱地址");return;}
                run(send,a->new Api(a.base,"").json("POST","/api/v1/auth/code",object("email",address)),r->{codeAvailableAt=System.currentTimeMillis()+60000;updateCodeButton(send);toast("验证码已发送，请查看邮箱和垃圾邮件");});});gap(body,12);
        }
        EditText password=field(body,"password",passwordLogin?"登录密码":reset?"新密码":"设置密码",passwordLogin?session.password:"","至少 8 个字符",1);
        password.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);password.setSaveEnabled(false);
        password.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);password.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        adornField(password,"lock",true);
        if(passwordLogin){
            if(!email.getText().toString().trim().equalsIgnoreCase(session.email))password.setText("");
            boolean[] remembered={password.getText().toString().equals(session.password)};
            email.addTextChangedListener(watcher(()->{if(remembered[0]&&!email.getText().toString().trim().equalsIgnoreCase(session.email)){remembered[0]=false;password.setText("");}}));
            password.addTextChangedListener(watcher(()->remembered[0]=false));
        }
        infoStrip(body,passwordLogin?"lock":"garden",passwordLogin?"账号密码加密保存在这台手机，下次自动登录。":reset?"提醒和成长记录都会保留。":"已有账号首次设密，原来的记录继续保留。",false);
        moveErrorHere(body);
        Button login=button(passwordLogin?"登录念念":reset?"重置并登录":"注册并登录",true);body.addView(login);
        login.setOnClickListener(v->{
            String address=value("email"),secret=password.getText().toString(),digits=passwordLogin?"":value("code");
            if(!android.util.Patterns.EMAIL_ADDRESS.matcher(address).matches()){email.setError("请填写有效的邮箱地址");return;}
            if(secret.codePointCount(0,secret.length())<8||secret.getBytes(StandardCharsets.UTF_8).length>128){password.setError("密码至少 8 个字符，最多 128 字节");return;}
            if(!passwordLogin&&digits.length()!=6){fields.get("code").setError("请输入 6 位验证码");return;}
            startAuthentication(login,passwordLogin?"/api/v1/auth/login":reset?"/api/v1/auth/password/reset":"/api/v1/auth/register",address,secret,digits,false);
        });gap(body,10);
        LinearLayout links=row();Button primaryLink=linkButton(passwordLogin?"注册 / 首次设置密码":"已有密码，返回登录");links.addView(primaryLink,new LinearLayout.LayoutParams(0,-2,1));primaryLink.setOnClickListener(v->openAuth(passwordLogin?"register":"login"));
        if(passwordLogin){Button forgot=linkButton("忘记密码");links.addView(forgot);forgot.setOnClickListener(v->openAuth("reset"));}body.addView(links);
        if(passwordLogin){gap(body,12);label(body,"当前服务器");LinearLayout server=row();server.addView(new GardenIconView(this,"server",MUTED),new LinearLayout.LayoutParams(dp(20),dp(20)));gapHorizontal(server,8);TextView address=text(session.base,11,MUTED,false);address.setMaxLines(2);address.setEllipsize(TextUtils.TruncateAt.MIDDLE);server.addView(address,new LinearLayout.LayoutParams(0,-2,1));Button change=linkButton("更换服务器");change.setTextSize(12);server.addView(change);change.setOnClickListener(v->navigate("server"));body.addView(server);}
        if(passwordLogin){gap(body,12);TextView promise=text("小事交给念念，留一点时间给生活",12,MUTED,false);promise.setGravity(Gravity.CENTER);body.addView(promise);}
        botanicalFooter(R.drawable.sowing_footer_foliage,126,reset?R.drawable.garden_watering:R.drawable.garden_seed_pot_v3);
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
                        ReminderCoordinator.clear(this);session.login(token,email,password);accountEpoch++;clearAccountData();ReminderCoordinator.attach(this,session);authEmail=email;homeNotice="";authMode="login";
                        if(!automatic)automaticLoginAttempted=false;navigate("home");openReminderIntent(getIntent());return;
                    }catch(Exception ignored){ReminderCoordinator.attach(this,session);authenticationFailed(automatic,"无法保存登录信息，请重试");return;}
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
        ReminderCoordinator.clear(this);try{session.clear();}catch(Exception e){showError("无法更新登录状态，请重试");return;}
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
        ImageView courtyard=scenery(R.drawable.garden_home_hero_v3);LinearLayout.LayoutParams heroLp=new LinearLayout.LayoutParams(-1,dp(Math.min(280,Math.round(getResources().getConfiguration().screenHeightDp*.34f))));heroLp.setMargins(-dp(16),-dp(12),-dp(16),0);body.addView(courtyard,heroLp);gap(body,14);
        TextView title=homeTitle("小事，交给念念安排",27);title.setGravity(Gravity.CENTER);body.addView(title);gap(body,8);
        LinearLayout welcome=column();welcome.setGravity(Gravity.CENTER_HORIZONTAL);body.addView(welcome);
        TextView caption=text(authBusy?"正在自动登录":"你的小花园，还在等你",18,INK,true);caption.setGravity(Gravity.CENTER);welcome.addView(caption);gap(welcome,10);
        TextView description=text(homeNotice.isEmpty()?"AI 按你授权的方式跟进，完成后赚水滴、养小花。":homeNotice,14,homeNotice.isEmpty()?MUTED:RED,false);description.setGravity(Gravity.CENTER);welcome.addView(description);
        if(authBusy){gap(welcome,12);ProgressBar progress=new ProgressBar(this);welcome.addView(progress,new LinearLayout.LayoutParams(dp(28),dp(28)));}
        gap(body,8);Button login=button(authBusy?"改用手动登录":"登录我的花园",true);body.addView(login);login.setOnClickListener(v->openAuth("login"));gap(body,10);
        Button register=button("注册 / 首次设置密码",false);body.addView(register);register.setOnClickListener(v->openAuth("register"));gap(body,14);
        Button change=linkButton("更换服务器  ›");LinearLayout.LayoutParams changeLp=new LinearLayout.LayoutParams(-2,dp(48));changeLp.gravity=Gravity.CENTER_HORIZONTAL;body.addView(change,changeLp);change.setOnClickListener(v->navigate("server"));
        botanicalFooter(R.drawable.sowing_footer_foliage,120,R.drawable.garden_seed_pot_v3);
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
        Button agent=arrowButton("让念念帮你跟进 · 提醒方式","chevron_right");agent.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);content.addView(agent,new LinearLayout.LayoutParams(-1,dp(48)));agent.setOnClickListener(v->navigate("reminders"));
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
    private Button arrowButton(String caption,String kind){
        Button button=smallButton(caption);button.setTextColor(INK);button.setBackground(shape(Color.TRANSPARENT,12));
        int size=dp(16);GardenIconView icon=new GardenIconView(this,kind,INK);
        icon.measure(View.MeasureSpec.makeMeasureSpec(size,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(size,View.MeasureSpec.EXACTLY));icon.layout(0,0,size,size);
        Bitmap bitmap=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888);icon.draw(new Canvas(bitmap));
        android.graphics.drawable.BitmapDrawable drawable=new android.graphics.drawable.BitmapDrawable(getResources(),bitmap);drawable.setBounds(0,0,size,size);
        button.setCompoundDrawablesRelative(null,null,drawable,null);button.setCompoundDrawablePadding(dp(6));return button;
    }
    private ImageView scenery(int resource){
        ImageView image=new ImageView(this);image.setImageBitmap(GardenArtCache.backdrop(getResources(),resource));image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);return image;
    }
    private void loadTasks(Button trigger) {
        int request=++taskLoadRequest;taskLoading=true;taskNotice="";drawTasks();
        run(trigger, a -> a.json("GET","/api/v1/tasks",null), result->{if(request!=taskLoadRequest)return;taskLoading=false;taskData=result.optJSONArray("tasks"); if(taskData==null)taskData=new JSONArray();drawTasks();},failure->{if(request!=taskLoadRequest)return;taskLoading=false;taskNotice=failureMessage(failure);drawTasks();});
    }
    private void drawTasks() {
        if(taskList==null)return;taskList.removeAllViews();int visible=0;
        if(taskLoading&&taskData.length()==0){note(taskList,"正在整理你的小事…");loadingRows(taskList);return;}
        if(!taskNotice.isEmpty()){infoStrip(taskList,"info",taskNotice+(taskData.length()>0?"\n下方是上次读取的小事，暂未更新。":""),true);Button retry=button("重新读取小事",false);taskList.addView(retry);retry.setOnClickListener(v->loadTasks(retry));gap(taskList,12);if(taskData.length()==0)return;}
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
            if(filter.equals("全部")||filter.equals("已暂停"))addReminderSwitch(line,task,true);
            card.addView(line);card.setContentDescription(task.optString("title")+"，"+taskStatusLabel(task)+"，"+summary+"，查看详情");
            card.setOnClickListener(v->{selected=task;detailBack="home";navigate("detail");});
        }
        if(visible==0){empty(taskList,!search.isEmpty()?"没有找到这件小事":taskData.length()==0?"从一颗小小的种子开始":"这里暂时没有提醒",!search.isEmpty()?"换个关键词试试，或者清除搜索。":taskData.length()==0?"把想做的小事交给 AI 安排。完成赚水滴，再去花园浇花。":"换个筛选条件，或者播下一颗新种子。");if(!search.isEmpty()){Button clear=button("清除搜索",false);taskList.addView(clear);clear.setOnClickListener(v->{search="";homeSearchExpanded=false;render();});}else if(taskData.length()==0){Button create=button("播下第一颗种子",true);taskList.addView(create);create.setOnClickListener(v->openEditor(null));}}
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
            if(!gardenFocusPlantId.isEmpty()){
                JSONArray all=result.optJSONArray("plants");List<String> ids=new ArrayList<>();if(all!=null)for(int i=0;i<all.length();i++)if(all.optJSONObject(i)!=null)ids.add(GardenFlowers.id(all.optJSONObject(i)));
                Collections.sort(ids);int position=ids.indexOf(gardenFocusPlantId);if(position>=0){gardenPage=position/12;gardenSelectedPlantId=gardenFocusPlantId;}gardenFocusPlantId="";
            }
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
        ordered.sort(Comparator.comparing(GardenFlowers::id));
        int total=ordered.size(),pages=Math.max(1,(total+11)/12);gardenPage=Math.max(0,Math.min(gardenPage,pages-1));
        int from=gardenPage*12,to=Math.min(total,from+12);List<JSONObject> bed=ordered.subList(from,to);
        JSONObject picked=null;for(JSONObject plant:bed)if(GardenFlowers.id(plant).equals(gardenSelectedPlantId))picked=plant;
        if(picked==null&&!bed.isEmpty())picked=bed.get(0);if(gardenData!=null)gardenSelectedPlantId=picked==null?"":GardenFlowers.id(picked);
        FrameLayout stage=new FrameLayout(this);GardenPlotView scene=new GardenPlotView(this);gardenScene=scene;
        scene.setPlants(bed);scene.setSelectedPlantId(gardenSelectedPlantId);scene.setHostActive(motionHostActive);
        stage.addView(scene,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout heading=column();heading.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView gardenTitle=text("我的花园",24,INK,true);gardenTitle.setGravity(Gravity.CENTER);heading.addView(gardenTitle);gap(heading,8);
        String census=gardenData==null?(gardenLoading?"正在打开你的花园…":"花园等待重新连接"):"已种 "+gardenData.optInt("total_plants")+" 株 · "+(gardenData.optBoolean("truncated")?"已加载中开花 ":"开花 ")+stages[3]+" 株";
        TextView censusView=text(census,12,INK,true);censusView.setGravity(Gravity.CENTER);heading.addView(censusView);
        FrameLayout.LayoutParams headingLp=new FrameLayout.LayoutParams(-1,-2,Gravity.TOP);headingLp.setMargins(dp(16),dp(46),dp(16),0);stage.addView(heading,headingLp);
        Button create=homeSeedButton();create.setText("＋ 种花");create.setOnClickListener(v->openPlantShop());FrameLayout.LayoutParams createLp=new FrameLayout.LayoutParams(-2,dp(48),Gravity.TOP|Gravity.END);createLp.setMargins(0,dp(6),dp(16),0);stage.addView(create,createLp);
        addGardenSign(stage);
        LinearLayout controls=column();controls.setPadding(dp(16),dp(8),dp(16),dp(4));GradientDrawable sheet=shape(PAPER,22);sheet.setCornerRadii(new float[]{dp(22),dp(22),dp(22),dp(22),0,0,0,0});controls.setBackground(sheet);
        LinearLayout drawerHeader=column();controls.addView(drawerHeader);
        FrameLayout grip=new FrameLayout(this);View handle=new View(this);handle.setBackground(shape(LINE,3));grip.addView(handle,new FrameLayout.LayoutParams(dp(36),dp(4),Gravity.CENTER));drawerHeader.addView(grip,new LinearLayout.LayoutParams(-1,dp(12)));grip.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        if(gardenData!=null&&gardenData.has("wallet")){
            LinearLayout wallet=row();wallet.addView(art(R.drawable.garden_watering,27));gapHorizontal(wallet,7);
            wallet.addView(text("水滴 "+waterBalance()+" · 完成小事 +3",13,BLUE,true),new LinearLayout.LayoutParams(0,-2,1));
            Button earn=linkButton("赚水滴");earn.setTextSize(12);wallet.addView(earn);earn.setOnClickListener(v->navigate("home"));drawerHeader.addView(wallet,new LinearLayout.LayoutParams(-1,dp(42)));
        }
        gardenSelection=column();drawerHeader.addView(gardenSelection,new LinearLayout.LayoutParams(-1,dp(64)));drawGardenSelection(picked);
        if(picked==null){TextView status=text(!gardenNotice.isEmpty()?"花园暂未更新，展开查看":gardenData==null?"正在打开你的花园…":"从一颗小小的种子开始",15,INK,true);status.setGravity(Gravity.CENTER_VERTICAL);gardenSelection.addView(status,new LinearLayout.LayoutParams(-1,-1));}
        scene.setOnPlantClickListener((plant,trigger)->{gardenTaskRequest++;gardenSelectedPlantId=GardenFlowers.id(plant);scene.setSelectedPlantId(gardenSelectedPlantId);drawGardenSelection(plant);});
        if(gardenData!=null&&total==0){
            LinearLayout welcome=column();welcome.setGravity(Gravity.CENTER);welcome.setPadding(dp(24),0,dp(24),0);
            welcome.addView(art(R.drawable.sowing_seed_mascot,128));gap(welcome,12);
            FrameLayout.LayoutParams welcomeLp=new FrameLayout.LayoutParams(-1,-2,Gravity.CENTER);welcomeLp.topMargin=dp(90);stage.addView(welcome,welcomeLp);
        }
        LinearLayout paging=row();Button prev=iconButton("back","上一片花圃"),next=iconButton("chevron_right","下一片花圃");prev.setEnabled(gardenPage>0);next.setEnabled(gardenPage+1<pages);prev.setAlpha(gardenPage>0?1f:.35f);next.setAlpha(gardenPage+1<pages?1f:.35f);paging.addView(prev);
        Button picker=arrowButton("第 "+(gardenPage+1)+" / "+pages+" 片","chevron_down");picker.setContentDescription("选择花圃，当前第 "+(gardenPage+1)+" 片，共 "+pages+" 片");paging.addView(picker,new LinearLayout.LayoutParams(0,dp(48),1));paging.addView(next);
        picker.setOnClickListener(v->showGardenBedPicker(picker,pages));prev.setOnClickListener(v->{gardenPage--;drawGarden();});next.setOnClickListener(v->{gardenPage++;drawGarden();});
        if(total==0){prev.setVisibility(View.GONE);next.setVisibility(View.GONE);picker.setVisibility(View.GONE);Button start=smallButton("种下第一朵花");paging.addView(start,new LinearLayout.LayoutParams(0,dp(48),1));start.setOnClickListener(v->openPlantShop());}
        Button memories=iconButton("chevron_up","展开花园抽屉");paging.addView(memories);drawerHeader.addView(paging,new LinearLayout.LayoutParams(-1,dp(48)));
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
            gardenMemoriesExpanded=expanded;memories.setRotation(expanded?180f:0f);memories.setContentDescription(expanded?"收起花园抽屉":"展开花园抽屉");
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
        note(diary,"本周完成 "+gardenData.optInt("week_completions")+" 次 · 累计完成 "+gardenData.optInt("total_completions")+" 次");
        if(gardenLoading)note(diary,"正在更新花园…");
        if(gardenData.optBoolean("truncated"))note(diary,"植物仅展示已加载部分；水滴与完成统计取全部记录。");
        LinearLayout milestones=row();int[] images={R.drawable.garden_seed_v3,R.drawable.garden_sprout_v3,R.drawable.garden_leaf_v3,R.drawable.garden_bloom};
        String[] names={"种子","发芽","长叶","开花"};int[] counts={0,1,3,7};
        for(int i=0;i<4;i++){
            LinearLayout step=column();step.setGravity(Gravity.CENTER);step.addView(art(images[i],54));gap(step,6);
            TextView label=text(names[i]+"\n"+counts[i]+" 点",12,INK,false);label.setGravity(Gravity.CENTER);step.addView(label);step.setContentDescription("成长 "+counts[i]+" 点："+names[i]);
            milestones.addView(step,new LinearLayout.LayoutParams(0,-2,1));
        }
        diary.addView(milestones);gap(diary,12);note(diary,gardenData.has("wallet")?"每次完成赚 3 滴水，浇给任何一株花。每滴水增加 1 点成长，7 点开花，漏做也不会枯萎。":"当前服务仍按任务完成次数成长，升级到 2.0.0 后可自由浇水。");
        if(gardenData.has("wallet")){
            int[] flowerPictures=GardenFlowers.resources();String[] flowerNames=GardenFlowers.names();boolean[] collected=new boolean[flowerPictures.length];JSONArray collection=gardenData.optJSONArray("plants");
            if(collection!=null)for(int i=0;i<collection.length();i++){JSONObject p=collection.optJSONObject(i);if(p!=null&&"bloom".equals(p.optString("stage")))collected[GardenFlowers.indexFor(p)]=true;}
            int varieties=0;for(boolean value:collected)if(value)varieties++;
            gap(diary,8);diary.addView(text("花朵图鉴 · "+varieties+" / "+collected.length+(gardenData.optBoolean("truncated")?"（已加载范围）":""),16,INK,true));gap(diary,8);LinearLayout album=row();
            for(int i=0;i<collected.length;i++){LinearLayout item=column();item.setGravity(Gravity.CENTER);ImageView flower=art(flowerPictures[i],46);flower.setAlpha(collected[i]?1f:.3f);item.addView(flower);TextView caption=text(flowerNames[i]+"\n"+(collected[i]?"已开花":"等待绽放"),11,collected[i]?BLUE:MUTED,false);caption.setGravity(Gravity.CENTER);item.addView(caption);album.addView(item,new LinearLayout.LayoutParams(dp(82),-2));}HorizontalScrollView albumScroll=new HorizontalScrollView(this);albumScroll.setHorizontalScrollBarEnabled(false);albumScroll.addView(album);diary.addView(albumScroll);gap(diary,18);
        }
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
            TextView note=text(message.isEmpty()?(hasPhoto?"完成留念 · "+photos.length()+" 张照片":"完成留念 · 每一份努力都记得"):message,12,MUTED,false);note.setMaxLines(2);note.setEllipsize(TextUtils.TruncateAt.END);copy.addView(note);line.addView(copy,new LinearLayout.LayoutParams(0,-2,1));
            Runnable open=()->{selectedRecord=copy(record);try{selectedRecord.put("_type","completion");selectedRecord.put("_at",record.optString("created_at"));selectedRecord.put("state","completed");}catch(JSONException ignored){}recordBack="garden";navigate("record");};
            line.setFocusable(true);line.setOnClickListener(v->open.run());
            if(hasPhoto){gapHorizontal(line,8);RecordPhotoView photo=new RecordPhotoView(this,false,true);line.addView(photo,new LinearLayout.LayoutParams(dp(68),dp(72)));loadRecordPhoto(photo,photos.optString(0),open);}
        }
        gap(diary,8);Button all=button("去日历回看更多  ›",false);diary.addView(all);all.setOnClickListener(v->navigate("history"));
    }
    private void drawGardenSelection(JSONObject plant){
        if(gardenSelection==null)return;gardenSelection.removeAllViews();if(plant==null)return;
        LinearLayout line=row();line.addView(art(plantResource(GardenFlowers.id(plant)),40));gapHorizontal(line,10);
        LinearLayout copy=column();TextView title=text(plant.optString("title"),14,INK,true);title.setSingleLine(true);title.setEllipsize(TextUtils.TruncateAt.END);copy.addView(title);
        int count=plant.optInt("growth_count",plant.optInt("completion_count"));
        TextView progress=text(GardenFlowers.nameFor(plant)+" · "+Math.min(count,7)+" / 7 成长",11,MUTED,false);progress.setSingleLine(true);progress.setEllipsize(TextUtils.TruncateAt.END);copy.addView(progress);line.addView(copy,new LinearLayout.LayoutParams(0,-2,1));
        if(gardenData!=null&&gardenData.has("wallet")){
            Button water=smallButton(count>=7?"已开花":"浇水 · 1滴");water.setTextSize(12);water.setEnabled(count<7&&!gardenCareBusy);line.addView(water);water.setOnClickListener(v->waterPlant(plant,water));
            if(!plant.optString("task_id").isEmpty()){Button open=iconButton("chevron_right","查看这株花原来的小事");line.addView(open);open.setOnClickListener(v->openGardenTask(plant,open));}
        }else{Button open=smallButton("查看小事");line.addView(open);open.setOnClickListener(v->openGardenTask(plant,open));}
        gardenSelection.addView(line);
    }
    private int waterBalance(){JSONObject wallet=gardenData==null?null:gardenData.optJSONObject("wallet");return wallet==null?0:wallet.optInt("balance");}
    private void openPlantShop(){selectedFlowerIndex=0;plantTitle="";plantRequestId=UUID.randomUUID().toString();navigate("plant");}
    private void renderPlantShop(){
        illustratedHeading("为花园添一朵花","完成的小事不同，也能一起养大它",R.drawable.garden_seed_pot_v3);
        field(body,"plant_title","给这株花起个名字",plantTitle,"例如：我的第一朵春天",1);addFlowerPreview(body);
        note(body,"种下种子不花水滴。选中花园里的植物，再用完成任务赚来的水滴浇灌。");gap(body,12);
        Button sow=button("种进我的花园",true);body.addView(sow);moveErrorHere(body);
        sow.setOnClickListener(v->{if(gardenCareBusy)return;String title=value("plant_title");if(title.isEmpty())title=GardenFlowers.names()[Math.max(0,selectedFlowerIndex)];
            if(title.codePointCount(0,title.length())>100){showError("花的名字请控制在 100 字以内");return;}if(plantRequestId.isEmpty())plantRequestId=UUID.randomUUID().toString();
            JSONObject payload=object("title",title,"flower_type",GardenFlowers.typeFor(selectedFlowerIndex),"request_id",plantRequestId);gardenCareBusy=true;
            run(sow,a->a.json("POST","/api/v1/garden/plants",payload),result->{gardenCareBusy=false;JSONObject plant=result.optJSONObject("plant");if(plant!=null){gardenSelectedPlantId=GardenFlowers.id(plant);gardenFocusPlantId=gardenSelectedPlantId;GardenFlowers.remember(gardenSelectedPlantId,GardenFlowers.indexFor(plant));}plantRequestId="";gardenData=null;gardenPage=0;toast("种好啦，去完成小事赚水滴吧");navigate("garden");},failure->{gardenCareBusy=false;showError(failure instanceof Api.Failure&&((Api.Failure)failure).status==404?"独立种花需要更新到 2.0.0 后端。":failureMessage(failure));});
        });botanicalFooter(R.drawable.sowing_footer_foliage,100);
    }
    private void waterPlant(JSONObject plant,Button trigger){
        if(gardenCareBusy)return;
        if(waterBalance()<=0){showWaterNeeded();return;}
        String id=GardenFlowers.id(plant);String request=waterRequests.computeIfAbsent(id,key->UUID.randomUUID().toString());gardenCareBusy=true;
        run(trigger,a->a.json("POST","/api/v1/garden/plants/"+Uri.encode(id)+"/water",object("request_id",request)),result->{
            gardenCareBusy=false;waterRequests.remove(id);JSONObject updated=result.optJSONObject("plant");
            completedRecord=object("id",request,"task_id",id,"task_title",plant.optString("title"),"growth",result.optJSONObject("growth"),"_watering",true);
            if(updated!=null)GardenFlowers.remember(id,GardenFlowers.indexFor(updated));invalidateCalendar();navigate("grown");
        },failure->{gardenCareBusy=false;String message=failureMessage(failure);if(failure instanceof Api.Failure&&message.contains("水滴")&&(message.contains("不足")||message.contains("不够"))){loadGarden();showWaterNeeded();}else{confirmAction("这次还没浇上水",message,"先逛逛","重试",false,()->waterPlant(plant,trigger));}});
    }
    private void showWaterNeeded(){
        confirmAction("还差一滴水", "浇一次花需要 1 滴水。完成一件小事就能获得 3 滴，再回来照料这朵花吧。", "先逛逛", "去完成小事", false, ()->{filter="待完成";search="";navigate("home");});
    }
    private void addGardenStage(LinearLayout parent,int resource,String name,int count){LinearLayout group=row();group.setGravity(Gravity.CENTER);group.addView(art(resource,27));gapHorizontal(group,2);TextView label=text(name+" "+count,11,MUTED,false);label.setIncludeFontPadding(false);group.addView(label);parent.addView(group,new LinearLayout.LayoutParams(0,dp(36),1));}
    private void openGardenTask(JSONObject plant,View trigger){int request=++gardenTaskRequest;trigger.setEnabled(false);run(null,a->a.json("GET","/api/v1/tasks/"+plant.optString("task_id"),null),result->{trigger.setEnabled(true);if(request!=gardenTaskRequest)return;selected=result;detailBack="garden";navigate("detail");},failure->{trigger.setEnabled(true);if(request!=gardenTaskRequest)return;showError(failure.getMessage()==null?"这株植物的提醒暂时打不开，请重试。":failure.getMessage());});}
    private int stageIndex(String stage){switch(stage){case "sprout":return 1;case "leaf":return 2;case "bloom":return 3;default:return 0;}}
    private int stageResource(String stage){switch(stage){case "sprout":return R.drawable.garden_sprout_v3;case "leaf":return R.drawable.garden_leaf_v3;case "bloom":return R.drawable.garden_bloom;default:return R.drawable.garden_seed_v3;}}
    private String stageLabel(String stage){switch(stage){case "sprout":return "小芽冒头";case "leaf":return "枝叶舒展";case "bloom":return "已经开花";default:return "刚刚播种";}}
    private String growthHint(int count){if(count==0)return "用完成任务赚来的水滴，唤醒小芽";if(count<3)return "再浇 "+(3-count)+" 滴水，就会长出新叶";if(count<7)return "再浇 "+(7-count)+" 滴水，就会开花";return "这朵花已绽放，可以再种一株喜欢的花";}
    private int plantResource(String taskId){
        if(gardenData!=null){JSONArray plants=gardenData.optJSONArray("plants");if(plants!=null)for(int i=0;i<plants.length();i++){
            JSONObject plant=plants.optJSONObject(i);if(plant==null||!taskId.equals(GardenFlowers.id(plant)))continue;
            if(plant.optString("stage").equals("bloom"))return GardenFlowers.resourceFor(plant);
            return stageResource(plant.optString("stage"));
        }}
        return R.drawable.garden_watering;
    }
    private ImageView art(int resource,int size){
        ImageView image=new ImageView(this);image.setImageBitmap(GardenArtCache.load(getResources(),resource));image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);image.setLayoutParams(new LinearLayout.LayoutParams(dp(size),dp(size)));return image;
    }
    private void renderGrown(){
        if(completedRecord.optJSONObject("reward")!=null){renderReward();return;}
        boolean watering=completedRecord.optBoolean("_watering");
        JSONObject receipt=completedRecord.optJSONObject("growth");String completionId=completedRecord.optString("id");
        int from=receipt==null?-1:receipt.optInt("from_count",-1),to=receipt==null?-1:receipt.optInt("to_count",-1);
        boolean added=receipt!=null&&receipt.optBoolean("added");
        boolean known=receipt!=null&&receipt.opt("added") instanceof Boolean&&receipt.opt("from_count") instanceof Number&&receipt.opt("to_count") instanceof Number&&GrowthAnimationState.validReceipt(completionId,from,to,added);
        gap(body,8);TextView overline=text(watering?"把努力，浇灌成花":"一件小事，认真完成",12,BLUE,true);overline.setGravity(Gravity.CENTER);body.addView(overline);gap(body,12);
        String celebration=!known||!added?(watering?"这次浇水，已经记下":"这次完成，已经记下"):to==1?"小芽，探出头啦":to==3?"新叶，慢慢舒展开来":to==7?"你的小花，绽放了":"又添了一份小小的努力";
        TextView title=text(celebration,23,INK,true);title.setGravity(Gravity.CENTER);body.addView(title);gap(body,8);TextView message=text(completedRecord.optString("task_title",selected.optString("title")),16,INK,true);message.setGravity(Gravity.CENTER);body.addView(message);gap(body,8);
        GardenGrowthView animation=new GardenGrowthView(this);growthView=animation;animation.setTaskId(completedRecord.optString("task_id",selected.optString("id")));animation.setHostActive(motionHostActive);LinearLayout.LayoutParams sceneLp=new LinearLayout.LayoutParams(-1,dp(252));sceneLp.setMargins(-dp(16),0,-dp(16),0);body.addView(animation,sceneLp);
        TextView phase=text("",13,MUTED,false);phase.setGravity(Gravity.CENTER);phase.setMinHeight(dp(42));body.addView(phase);int page=pageEpoch;
        animation.setOnPhaseChangedListener(caption->{if(page==pageEpoch&&screen.equals("grown"))phase.setText(caption);});
        if(known){
            animation.showGrowth(from,to,added,growthAnimationState.consume(completionId,from,to,added));
            TextView count=text(added?(watering?"水滴 −1 · 成长 ":"养分 +1 · 成长 ")+to+" / 7":"成长 "+to+" / 7",12,BLUE,true);count.setGravity(Gravity.CENTER);body.addView(count);
            if(added){gap(body,6);Button replay=smallButton("再看一次生长");replay.setEnabled(ValueAnimator.areAnimatorsEnabled());LinearLayout replayRow=row();replayRow.setGravity(Gravity.CENTER);replayRow.addView(replay);body.addView(replayRow);replay.setOnClickListener(v->{if(growthView!=null)growthView.replay();});}
        }else{animation.showRecorded();TextView compatibility=text("完成记录已保存。\n更新提醒服务后，可查看这株植物的真实生长。",12,MUTED,false);compatibility.setGravity(Gravity.CENTER);body.addView(compatibility);}
        if(known&&!added){TextView duplicate=text(watering?"这次浇水已处理，没有重复扣水滴。":"这次完成已记录，不会重复增加养分。",13,MUTED,false);duplicate.setGravity(Gravity.CENTER);body.addView(duplicate);}
        gap(body,18);
        Button garden=button("去看看我的花园",true);body.addView(garden);garden.setOnClickListener(v->navigate("garden"));gap(body,10);Button next=button("回到今天的小事",false);body.addView(next);next.setOnClickListener(v->navigate("home"));gap(body,16);
        if(!watering)addCompletionMemoryLink();
        botanicalFooter(R.drawable.sowing_footer_foliage,68);
    }
    private void renderReward(){
        JSONObject reward=completedRecord.optJSONObject("reward");int drops=reward.optInt("water_drops");
        gap(body,20);TextView title=homeTitle(drops>0?"小事完成，水滴入袋":"这件小事，已经记下",24);title.setGravity(Gravity.CENTER);body.addView(title);gap(body,10);
        TextView name=text(completedRecord.optString("task_title",selected.optString("title")),16,INK,true);name.setGravity(Gravity.CENTER);body.addView(name);gap(body,24);
        ImageView can=art(R.drawable.garden_watering,164);LinearLayout.LayoutParams picture=new LinearLayout.LayoutParams(dp(164),dp(164));picture.gravity=Gravity.CENTER_HORIZONTAL;body.addView(can,picture);gap(body,16);
        TextView earned=text(drops>0?"＋"+drops+" 滴水":"没有重复领取水滴",23,BLUE,true);earned.setGravity(Gravity.CENTER);body.addView(earned);gap(body,10);
        TextView balance=text("水滴余额 "+reward.optInt("balance")+"\n可以浇给花园里的任何一株花",14,MUTED,false);balance.setGravity(Gravity.CENTER);balance.setLineSpacing(dp(8),1);body.addView(balance);gap(body,24);
        Button garden=button("去花园浇花",true);body.addView(garden);garden.setOnClickListener(v->navigate("garden"));gap(body,10);
        Button next=button("继续今天的小事",false);body.addView(next);next.setOnClickListener(v->navigate("home"));gap(body,14);addCompletionMemoryLink();botanicalFooter(R.drawable.sowing_footer_foliage,90);
    }
    private void addCompletionMemoryLink(){
        Button memory=linkButton("回看这次完成记录");body.addView(memory,new LinearLayout.LayoutParams(-1,-2));memory.setOnClickListener(v->{selectedRecord=copy(completedRecord);try{selectedRecord.put("_type","completion");selectedRecord.put("_at",completedRecord.optString("created_at"));selectedRecord.put("state","completed");}catch(JSONException ignored){}recordBack="grown";navigate("record");});
    }
    private void renderReminderSettings(){
        illustratedHeading("念念怎样提醒你","时间交给 AI 安排，方式由你授权",R.drawable.garden_watering);
        body.addView(ReminderCoordinator.channelSettings(this,session,()->{}));
        if(reminderSettingsBack.equals("editor")){gap(body,16);Button resume=button("返回，继续安排",false);body.addView(resume);resume.setOnClickListener(v->navigate("editor"));}
    }
    private void renderDetail() {
        boolean archived=selected.optBoolean("archived");
        sceneHeader(false);
        LinearLayout plantHeading=row(),words=column();plantHeading.addView(art(plantResource(selected.optString("id")),76));gapHorizontal(plantHeading,14);words.addView(homeTitle(selected.optString("title"),24));gap(words,8);
        TextView badge=text(archived?"已归档":taskStatusLabel(selected),12,archived?MUTED:statusColor(selected.optString("status")),true);badge.setPadding(dp(12),dp(5),dp(12),dp(5));badge.setBackground(shape(archived?LINE:selected.optString("status").equals("expired")?0xfff9e5df:PALE,20));words.addView(badge,new LinearLayout.LayoutParams(-2,-2));plantHeading.addView(words,new LinearLayout.LayoutParams(0,-2,1));body.addView(plantHeading);gap(body,16);
        if(archived)infoStrip(body,"garden","提醒已停止，完成文字、照片与邮件记录仍然保留。",false);
        LinearLayout info=card(body);JSONObject detailRule=selected.optJSONObject("rule");
        if(detailRule!=null){
            String kind=detailRule.optString("kind"),mode=detailRule.optString("mode");Map<String,String> data=new HashMap<>();for(String key:new String[]{"start_date","end_date","end_at","month_start","month_end","every_days","max_reminders","timezone","repeat_minutes"})data.put(key,detailRule.optString(key));data.put("times",join(detailRule.optJSONArray("times")==null?new JSONArray():detailRule.optJSONArray("times"),"、"));
            if(kind.equals("minute_interval"))try{ZoneId zone=ZoneId.of(detailRule.optString("timezone","Asia/Shanghai"));data.put("first_date",MinuteIntervalForm.date(detailRule.optString("first_at"),zone));data.put("first_time",MinuteIntervalForm.time(detailRule.optString("first_at"),zone));}catch(Exception ignored){}
            List<Integer> days=new ArrayList<>();JSONArray chosen=detailRule.optJSONArray("weekdays");if(chosen!=null)for(int i=0;i<chosen.length();i++)days.add(chosen.optInt(i));String[] labels=ReminderSummary.labels(kind),values=ReminderSummary.values(kind,mode,data,days);String[] icons={"clock","cycle","circle_check"};
            for(int i=0;i<labels.length;i++){recordInfoRow(info,icons[i],labels[i],values[i]);if(i<2)calendarDivider(info);}String extra=ReminderSummary.note(kind,mode,data);if(!extra.isEmpty()){gap(info,6);note(info,extra);}
        }else recordInfoRow(info,"history","提醒规则",selected.optString("summary","规则信息暂不可用"));
        if(!archived){calendarDivider(info);recordInfoRow(info,"clock","下一次提醒",selected.optString("next_reminder").isEmpty()?"当前没有待发送的提醒":formatFull(selected.optString("next_reminder")));}
        if(archived){if(!selected.optString("description").isEmpty()){section(body,"这件小事");body.addView(text(selected.optString("description"),14,INK,false));}botanicalFooter(R.drawable.sowing_footer_foliage,112);return;}
        String status=selected.optString("status");
        if(status.equals("pending")) {
            Button done=button("这件事已完成",true);body.addView(done);done.setOnClickListener(v->openComplete());gap(body,10);
            Button later=button("稍后再提醒我",false);body.addView(later);later.setOnClickListener(v->showSnooze(later));gap(body,16);
        } else {
            JSONObject rule=selected.optJSONObject("rule");String kind=rule==null?"":rule.optString("kind"),mode=rule==null?"":rule.optString("mode");
            String doneCopy=kind.equals("ongoing")||kind.equals("minute_interval")||kind.equals("once")||kind.equals("date_range")?"这次安排已经结束，所有记录都会保留。":"本期已经结束，下一期会按规则重新开始。";
            if(mode.equals("notify_once"))doneCopy="本期已经提醒，不需要再点击完成。";
            infoStrip(body,"info",status.equals("done")?doneCopy:status.equals("upcoming")?"还没有到本期时间，开始后再记录完成。":status.equals("expired")?"这条提醒已过截止时间，记录依然保留。":"已暂停提醒，可以随时恢复。",status.equals("expired"));
        }
        addReminderStrategy(body,selected);
        if(!selected.optString("description").isEmpty()){note(body,selected.optString("description"));}
        LinearLayout actions=column();GradientDrawable outline=shape(PAPER,14);outline.setStroke(dp(1),LINE);actions.setBackground(outline);body.addView(actions);
        ProfileAction edit=new ProfileAction(actions,"edit","编辑提醒","");edit.setOnClickListener(v->openEditor(selected));
        ProfileAction manage=new ProfileAction(actions,"cycle","到全部小事管理提醒开关","");manage.setOnClickListener(v->{filter="全部";search="";navigate("home");});
        ProfileAction records=new ProfileAction(actions,"history","查看日历安排","");records.setOnClickListener(v->{LocalDate next=RecordDates.localDate(selected.optString("next_reminder"),ZoneId.systemDefault());calendarDate=next==null?LocalDate.now():next;calendarMonth=YearMonth.from(calendarDate);recordPage=0;navigate("history");});gap(body,12);
        Button archive=linkButton("归档这条提醒");archive.setTextColor(RED);body.addView(archive,new LinearLayout.LayoutParams(-1,-2));archive.setOnClickListener(v->confirmAction("归档这条提醒？","归档后停止发送提醒，已完成的文字、照片和邮件记录会保留。","再想想","归档",true,()->{
            String id=selected.optString("id");run(archive,a->mutateTask(a,"DELETE","/api/v1/tasks/"+id,null,id),r->{taskData=new JSONArray();invalidateCalendar();toast("已归档，记录已保留");navigate(detailBack.equals("garden")||detailBack.equals("record")&&recordBack.equals("garden")?"garden":calendarDetailBack()?"history":"home");});
        }));botanicalFooter(R.drawable.sowing_footer_foliage,76);
    }
    private void addReminderStrategy(LinearLayout parent,JSONObject task){
        JSONObject strategy=task.optJSONObject("reminder_strategy");
        if(strategy==null)return;
        LinearLayout panel=card(parent);LinearLayout heading=row();
        heading.addView(text("接下来怎样提醒",16,INK,true),new LinearLayout.LayoutParams(0,-2,1));
        String state=strategy.optString("state"),source="ready".equals(state)?("ai".equals(strategy.optString("source"))?"AI 安排":"按规则安排"):"awaiting_sync".equals(state)?"更新中":"待开通";
        heading.addView(text(source,11,MUTED,false));panel.addView(heading);gap(panel,12);
        JSONArray steps=strategy.optJSONArray("steps");
        if(steps==null||steps.length()==0){
            if("awaiting_sync".equals(state)){
                note(panel,"跟进流程正在更新，同步后即可查看。");Button refresh=button("刷新流程",false);panel.addView(refresh);refresh.setOnClickListener(v->reloadSelected());return;
            }
            note(panel,"开通可用的提醒方式后，念念会为这件事确定跟进流程。");
            Button setup=button("去设置提醒方式",false);panel.addView(setup);setup.setOnClickListener(v->{reminderSettingsBack="home";navigate("reminders");});return;
        }
        for(int i=0;i<steps.length();i++){
            JSONObject step=steps.optJSONObject(i);if(step==null)continue;
            LinearLayout line=row();TextView index=text(String.valueOf(i+1),12,BLUE,true);index.setGravity(Gravity.CENTER);index.setBackground(shape(PALE,12));line.addView(index,new LinearLayout.LayoutParams(dp(24),dp(24)));gapHorizontal(line,10);
            String wait=i==0?"到约定时间": "仍未完成，再等 "+step.optInt("wait_minutes")+" 分钟";
            LinearLayout copy=column();copy.addView(text(notificationChannel(step),14,INK,true));gap(copy,3);copy.addView(text(wait,12,MUTED,false));line.addView(copy,new LinearLayout.LayoutParams(0,-2,1));panel.addView(line);if(i<steps.length()-1)gap(panel,12);
        }
        gap(panel,12);JSONObject rule=task.optJSONObject("rule");boolean once=rule!=null&&"notify_once".equals(rule.optString("mode"));
        note(panel,once?"每期只成功提醒一次。":"上一步有执行结果或等待超时后，才开始下一步。末步后逐渐延长间隔；遵守次数上限，完成、暂停或到期即停止本期提醒。");
        String reason=strategy.optString("reason");
        if(!reason.isEmpty()){
            Button why=linkButton("为什么这样安排");panel.addView(why);LinearLayout explanation=column();explanation.setVisibility(View.GONE);note(explanation,reason);
            for(int i=0;i<steps.length();i++){JSONObject step=steps.optJSONObject(i);if(step!=null&&!step.optString("reason").isEmpty())note(explanation,(i+1)+". "+step.optString("reason"));}
            panel.addView(explanation);why.setOnClickListener(v->{boolean open=explanation.getVisibility()!=View.VISIBLE;explanation.setVisibility(open?View.VISIBLE:View.GONE);why.setText(open?"收起说明":"为什么这样安排");});
        }
    }
    private void sceneHeader(boolean completion){
        FrameLayout scene=new FrameLayout(this);scene.addView(scenery(R.drawable.calendar_header),new FrameLayout.LayoutParams(-1,-1));
        LinearLayout content=column();content.setPadding(dp(16),0,dp(16),0);LinearLayout back=row();back.addView(new GardenIconView(this,"back",INK),new LinearLayout.LayoutParams(dp(20),dp(20)));gapHorizontal(back,5);back.addView(text("返回",14,INK,false));back.setContentDescription("返回");back.setFocusable(true);back.setOnClickListener(v->onBackPressed());content.addView(back,new LinearLayout.LayoutParams(dp(82),dp(48)));
        if(completion){ImageView can=art(R.drawable.garden_watering,86);content.addView(can,new LinearLayout.LayoutParams(dp(86),dp(86)));}
        scene.addView(content,new FrameLayout.LayoutParams(-1,-1));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(completion?160:124));lp.setMargins(-dp(16),-dp(12),-dp(16),dp(12));body.addView(scene,lp);
    }
    private AlertDialog gardenDialog(LinearLayout panel){
        dismissProfileDialog();ScrollView scroll=new ScrollView(this);scroll.addView(panel);AlertDialog dialog=new AlertDialog.Builder(this).create();profileDialog=dialog;dialog.setView(scroll,0,0,0,0);dialog.setOnDismissListener(d->{if(profileDialog==dialog)profileDialog=null;});dialog.show();Window window=dialog.getWindow();if(window!=null){window.setBackgroundDrawable(shape(PAPER,22));window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);window.setDimAmount(.42f);window.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels-dp(40),dp(360)),-2);}return dialog;
    }
    private LinearLayout dialogPanel(String title,String message){
        LinearLayout panel=column();panel.setPadding(dp(22),dp(24),dp(22),dp(20));panel.setBackground(shape(PAPER,22));TextView heading=homeTitle(title,21);heading.setGravity(Gravity.CENTER);panel.addView(heading);gap(panel,12);TextView copy=text(message,14,MUTED,false);copy.setGravity(Gravity.CENTER);panel.addView(copy);gap(panel,20);return panel;
    }
    private void confirmAction(String title,String message,String cancelLabel,String confirmLabel,boolean destructive,Runnable action){
        LinearLayout panel=dialogPanel(title,message),actions=row();Button cancel=button(cancelLabel,false),confirm=button(confirmLabel,true);actions.addView(cancel,new LinearLayout.LayoutParams(0,-2,1));gapHorizontal(actions,10);actions.addView(confirm,new LinearLayout.LayoutParams(0,-2,1));panel.addView(actions);if(destructive)confirm.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22ffffff),shape(RED,12),null));AlertDialog dialog=gardenDialog(panel);int page=pageEpoch,account=accountEpoch;cancel.setOnClickListener(v->dialog.dismiss());confirm.setOnClickListener(v->{dialog.dismiss();if(page==pageEpoch&&account==accountEpoch)action.run();});
    }
    private void showSnooze(Button trigger){
        LinearLayout panel=dialogPanel("延后提醒","不能跨过本期或精确截止时间");int[] minutes={15,30,60,180};String[] captions={"15 分钟","30 分钟","1 小时","3 小时"};AlertDialog[] dialog={null};String id=selected.optString("id");int page=pageEpoch;
        for(int i=0;i<minutes.length;i++){int duration=minutes[i];Button choice=button(captions[i],false);panel.addView(choice);gap(panel,8);choice.setOnClickListener(v->{dialog[0].dismiss();if(page!=pageEpoch)return;run(trigger,a->mutateTask(a,"POST","/api/v1/tasks/"+id+"/snooze",object("minutes",duration),id),r->{toast("已安排稍后提醒");reloadSelected();});});}dialog[0]=gardenDialog(panel);
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
                try{updated=mutateTask(api,"PUT","/api/v1/tasks/"+id+"/paused",object("paused",!checked),id);}catch(Exception error){failure=error;}
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
        editing=task!=null;draft=task==null?new JSONObject():copy(task);restoredForm=null;manualVisible=editing;rulesExpanded=editing;advancedExpanded=false;aiExpanded=!editing;aiText="";aiWarning="";clearClarification();
        selectedFlowerIndex=editing?GardenFlowers.indexFor(task):-1;
        editorSetupPromptPending=true;
        screen="editor";render();
    }
    private void offerReminderSetup(){
        if(!editorSetupPromptPending)return;
        editorSetupPromptPending=false;
        final int page=pageEpoch,account=accountEpoch;
        body.post(()->{
            if(isDestroyed()||page!=pageEpoch||account!=accountEpoch||!screen.equals("editor"))return;
            String message=ReminderCoordinator.setupPrompt(this,session);
            if(message.isEmpty()){
                if(ReminderBackgroundAccess.needsSetup(this))showReminderBackgroundSetup();
                return;
            }
            LinearLayout panel=dialogPanel("让念念能提醒到你",message);gap(panel,12);
            Button setup=button("去设置提醒方式",true),later=linkButton("先安排，稍后设置");panel.addView(setup);panel.addView(later,new LinearLayout.LayoutParams(-1,-2));
            profileDialog=gardenDialog(panel);AlertDialog dialog=profileDialog;
            setup.setOnClickListener(v->{dialog.dismiss();if(page==pageEpoch&&account==accountEpoch)navigate("reminders");});later.setOnClickListener(v->dialog.dismiss());
        });
    }
    private void showReminderBackgroundSetup(){
        LinearLayout panel=dialogPanel("退出后，也能提醒你",ReminderBackgroundAccess.instructions(this));gap(panel,12);
        Button setup=button("去设置后台权限",true),done=linkButton("我已检查，继续安排");
        panel.addView(setup);panel.addView(done,new LinearLayout.LayoutParams(-1,-2));
        profileDialog=gardenDialog(panel);AlertDialog dialog=profileDialog;
        setup.setOnClickListener(v->{dialog.dismiss();ReminderBackgroundAccess.openSettings(this);});
        done.setOnClickListener(v->{ReminderBackgroundAccess.reviewed(this);dialog.dismiss();});
    }
    private void clearClarification() {
        clarificationFlow.clear(); aiQuestion=null; aiAnswerText="";aiReferenceTime="";
    }
    private JSONArray clarificationAnswers(boolean pending) {
        JSONArray result=new JSONArray();
        for(ClarificationFlow.Answer answer:pending?clarificationFlow.requestAnswers():clarificationFlow.answers())
            result.put(object("question",answer.question,"answer",answer.answer,"answered_at",answer.answeredAt));
        return result;
    }
    private JSONObject clarificationPayload() {
        return object("text",clarificationFlow.original(),"supports_clarification",true,"answers",clarificationAnswers(true),"reference_time",aiReferenceTime);
    }
    private void saveClarification(Bundle out) {
        out.putString("clarificationScope",session.base+"\n"+session.email);
        out.putString("clarificationOriginal",clarificationFlow.original());
        out.putString("clarificationAnswers",clarificationAnswers(false).toString());
        out.putString("clarificationQuestion",aiQuestion==null?"":aiQuestion.toString());
        out.putString("clarificationInput",aiAnswerText);
        out.putString("clarificationReferenceTime",aiReferenceTime);
    }
    private void restoreClarification(Bundle state) {
        clearClarification();
        if(!state.getString("clarificationScope","").equals(session.base+"\n"+session.email)||session.token.isEmpty())return;
        try {
            clarificationFlow.start(state.getString("clarificationOriginal",""));
            JSONArray answers=new JSONArray(state.getString("clarificationAnswers","[]"));
            for(int i=0;i<answers.length();i++){JSONObject answer=answers.getJSONObject(i);clarificationFlow.stage(answer.getString("question"),answer.getString("answer"));clarificationFlow.accepted(answer.optString("answered_at"));}
            String question=state.getString("clarificationQuestion","");
            if(!question.isEmpty())aiQuestion=new JSONObject(question);
            aiAnswerText=state.getString("clarificationInput","");
            aiReferenceTime=state.getString("clarificationReferenceTime","");
        } catch(Exception invalid){clearClarification();}
    }
    private JSONObject parseReminder(Api api,JSONObject payload) throws Exception {
        JSONObject result=api.json("POST","/api/v1/ai/parse",payload);
        if("needs_clarification".equals(result.optString("status"))) {
            JSONObject question=result.optJSONObject("clarification");
            if(question==null||question.optString("question").trim().isEmpty()||result.has("rule"))throw new Exception("追问内容不完整，请重试");
        } else if(result.optJSONObject("rule")==null)throw new Exception("安排内容不完整，请重试");
        return result;
    }
    private void acceptReminderAnalysis(JSONObject result) {
        clarificationFlow.accepted(result.optString("answered_at")); taskBusy=false; aiAnswerText="";restoredForm=null;
        if(editorCompanion!=null)editorCompanionEntrance=editorCompanion.screenBounds();
        if("needs_clarification".equals(result.optString("status"))) {
            aiReferenceTime=result.optString("reference_time");aiQuestion=result.optJSONObject("clarification");draft=new JSONObject();manualVisible=false;aiWarning="";
        } else {
            aiQuestion=null;draft=result;manualVisible=true;rulesExpanded=false;advancedExpanded=false;aiExpanded=false;
            JSONArray warnings=result.optJSONArray("warnings");aiWarning=warnings==null?"":join(warnings,"\n");
        }
        render();
    }
    private void renderClarification() {
        body.addView(text("你想安排的事",12,MUTED,false));gap(body,6);
        body.addView(text(clarificationFlow.original(),14,INK,false));gap(body,12);
        for(ClarificationFlow.Answer answer:clarificationFlow.answers()) {
            body.addView(text(answer.question+"\n你："+answer.answer,12,MUTED,false));gap(body,8);
        }
        LinearLayout question=card(body);question.setBackground(shape(PALE,18));
        question.addView(text("还差一点信息",12,HOME_TITLE,true));gap(question,8);
        question.addView(text(aiQuestion.optString("question"),18,INK,true));gap(question,8);
        boolean canAnswer=clarificationFlow.canAnswer();
        if(canAnswer) {
            JSONArray options=aiQuestion.optJSONArray("options");
            if(options!=null)for(int i=0;i<Math.min(4,options.length());i++) {
                JSONObject option=options.optJSONObject(i);if(option==null)continue;
                String answer=option.optString("value"),label=option.optString("label");if(answer.isEmpty()||label.isEmpty())continue;
                Button choice=button(label,false);question.addView(choice);gap(question,6);
                choice.setOnClickListener(v->submitClarification(answer));
            }
            note(question,options!=null&&options.length()>0?"点选即可继续，也可以自己补充。":"补充一下，我再接着安排。");
            EditText answer=new EditText(this);styleField(answer);answer.setText(aiAnswerText);
            answer.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            answer.setMinLines(2);answer.setMaxLines(5);answer.setHint(aiQuestion.optString("input_hint","输入日期、时间或其他补充"));
            fields.put("ai_answer",answer);question.addView(answer,new LinearLayout.LayoutParams(-1,-2));gap(question,10);
            Button send=button("补充后继续",true);question.addView(send);send.setOnClickListener(v->submitClarification(answer.getText().toString().trim()));
        } else note(question,"这件事还没说清楚，请把日期、时间一起重新描述，或手动填写。");
        gap(body,10);note(body,"信息补齐后会先给你看安排，确认才创建。");
        Button rephrase=linkButton("重新说这件事");body.addView(rephrase);rephrase.setOnClickListener(v->{if(taskBusy)return;clearClarification();restoredForm=null;aiExpanded=true;render();});
        Button manual=linkButton("我来手动填写");body.addView(manual);manual.setOnClickListener(v->{if(taskBusy)return;clearClarification();restoredForm=null;manualVisible=true;rulesExpanded=true;aiExpanded=true;render();});
        gap(body,100);
    }
    private void submitClarification(String answer) {
        if(taskBusy)return;
        captureTransient();aiAnswerText=answer;
        if(fields.containsKey("ai_answer"))fields.get("ai_answer").setText(answer);
        try {clarificationFlow.stage(aiQuestion.optString("question"),answer);}catch(Exception invalid){showError(failureMessage(invalid));return;}
        JSONObject payload=clarificationPayload();taskBusy=true;hideKeyboard(fields.get("ai_answer"));
        setClarificationEnabled(body,false);
        if(editorCompanion!=null)editorCompanion.setPose(SeedCompanionView.Pose.THINK);
        TextView progress=text("收到，正在接着安排…",14,HOME_TITLE,true);body.addView(progress,0);
        run(null,a->parseReminder(a,payload),this::acceptReminderAnalysis,failure->{clarificationFlow.failed();render();showError("补充内容已保留，请重试。\n"+failureMessage(failure));});
    }
    private void setClarificationEnabled(View view,boolean enabled) {
        if(view instanceof Button||view instanceof EditText)view.setEnabled(enabled);
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)setClarificationEnabled(((ViewGroup)view).getChildAt(i),enabled);
    }

    private void renderEditor() {
        editorConfirmed=false;
        addSowingHeading();
        offerReminderSetup();
        if (!editing && aiQuestion != null) { renderClarification(); return; }
        LinearLayout originalRequest=column();
        if(!editing){
            boolean compact=manualVisible&&!aiExpanded;
            LinearLayout inputArea=column();inputArea.setPadding(dp(10),dp(10),dp(10),dp(10));inputArea.setBackground(shape(0xffdeebd8,12));body.addView(inputArea);
            EditText sentence=new EditText(this);styleField(sentence);sentence.setBackground(shape(PAPER,8));sentence.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));sentence.setIncludeFontPadding(false);sentence.setTextSize(14);sentence.setPadding(dp(12),dp(10),dp(12),dp(10));sentence.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);sentence.setGravity(Gravity.TOP|Gravity.START);sentence.setMinLines(compact?1:2);sentence.setMaxLines(compact?3:5);sentence.setMinHeight(dp(compact?44:80));sentence.setText(aiText);sentence.setHint("等会儿提醒我搞卫生，时间你来安排");fields.put("ai",sentence);inputArea.addView(sentence,new LinearLayout.LayoutParams(-1,-2));
            LinearLayout actions=row();Button generate=button(manualVisible?"重新安排":"帮我安排",!manualVisible);
            if(manualVisible){generate.setBackground(shape(Color.TRANSPARENT,10));generate.setTextSize(12);generate.setTextColor(INK);actions.setGravity(Gravity.END);actions.addView(generate,new LinearLayout.LayoutParams(-2,dp(48)));TextView separator=text("|",12,LINE,false);actions.addView(separator);}
            else{gap(body,10);actions.addView(generate,new LinearLayout.LayoutParams(0,dp(48),1));}
            Button manual=smallButton("手动填写");manual.setBackground(shape(Color.TRANSPARENT,10));manual.setTextColor(INK);manual.setTextSize(manualVisible?12:14);actions.addView(manual);body.addView(actions);gap(body,manualVisible?2:16);
            if(manualVisible&&draft.optJSONObject("rule")!=null&&!aiExpanded&&!aiText.trim().isEmpty()){
                inputArea.setVisibility(View.GONE);actions.setVisibility(View.GONE);
                LinearLayout original=column(),originalHeader=row();original.setPadding(dp(14),dp(8),dp(14),dp(12));GradientDrawable requestSurface=shape(PAPER,14);requestSurface.setStroke(dp(1),LINE);original.setBackground(requestSurface);
                originalHeader.addView(text("你的原话",13,INK,true),new LinearLayout.LayoutParams(0,-2,1));Button rethink=linkButton("改一句");originalHeader.addView(rethink);original.addView(originalHeader);
                TextView request=text(aiText.trim(),13,MUTED,false);request.setMaxLines(3);request.setEllipsize(TextUtils.TruncateAt.END);original.addView(request);originalRequest.addView(original);
                rethink.setOnClickListener(v->{if(taskBusy||editorConfirmed)return;aiExpanded=true;original.setVisibility(View.GONE);inputArea.setVisibility(View.VISIBLE);actions.setVisibility(View.VISIBLE);if(editorCompanion!=null)editorCompanion.setPose(SeedCompanionView.Pose.LISTEN);sentence.requestFocus();sentence.post(()->sentence.requestRectangleOnScreen(new Rect(0,0,sentence.getWidth(),sentence.getHeight()),false));});
            }
            LinearLayout arranging=row();arranging.setGravity(Gravity.CENTER);arranging.addView(text("嗯，我想想怎么安排…",16,INK,false),new LinearLayout.LayoutParams(-2,-2));arranging.setPadding(dp(6),dp(8),dp(6),dp(14));arranging.setVisibility(View.GONE);body.addView(arranging);moveErrorHere(body);
            if(manualVisible&&(draft.optJSONObject("rule")==null||aiText.trim().isEmpty())){
                inputArea.setVisibility(View.GONE);actions.setVisibility(View.GONE);
                Button useAi=smallButton("用一句话让念念安排");useAi.setBackground(shape(Color.TRANSPARENT,10));body.addView(useAi);
                useAi.setOnClickListener(v->{if(taskBusy)return;boolean show=inputArea.getVisibility()!=View.VISIBLE;inputArea.setVisibility(show?View.VISIBLE:View.GONE);actions.setVisibility(show?View.VISIBLE:View.GONE);useAi.setText(show?"收起，继续手动填写":"用一句话让念念安排");});
            }
            manual.setOnClickListener(v->{if(taskBusy)return;captureTransient();manualVisible=true;rulesExpanded=true;aiExpanded=true;render();});
            generate.setOnClickListener(v->{if(taskBusy)return;String input=sentence.getText().toString().trim();if(input.length()<2){showError("先写下想让我提醒你的事");return;}captureTransient();aiText=input;clearClarification();clarificationFlow.start(input);hideKeyboard(sentence);taskBusy=true;arranging.setVisibility(View.VISIBLE);manual.setEnabled(false);sentence.setEnabled(false);inputArea.setVisibility(View.GONE);actions.setVisibility(View.GONE);if(editorFlowersPreview!=null)editorFlowersPreview.setVisibility(View.GONE);if(editorPlanContent!=null)editorPlanContent.setVisibility(View.GONE);if(editorCompanion!=null)editorCompanion.setPose(SeedCompanionView.Pose.THINK);
                JSONObject payload=clarificationPayload();
                run(generate,a->parseReminder(a,payload),this::acceptReminderAnalysis,failure->{arranging.setVisibility(View.GONE);manual.setEnabled(true);sentence.setEnabled(true);inputArea.setVisibility(View.VISIBLE);actions.setVisibility(View.VISIBLE);if(editorFlowersPreview!=null)editorFlowersPreview.setVisibility(View.VISIBLE);if(editorPlanContent!=null)editorPlanContent.setVisibility(View.VISIBLE);if(editorCompanion!=null)editorCompanion.setPose(SeedCompanionView.Pose.LISTEN);generate.setText("重新安排");showError("这次没有安排好，请重试。\n"+failureMessage(failure));});generate.setText("正在安排…");
            });
        }
        if(!manualVisible){editorFlowersPreview=column();body.addView(editorFlowersPreview);addFlowerPreview(editorFlowersPreview);gap(body,130);return;}
        JSONObject rule=draft.optJSONObject("rule");if(rule==null)rule=new JSONObject();
        LinearLayout planBody=column();editorPlanContent=planBody;body.addView(planBody);
        LinearLayout draftPanel=card(planBody);draftPanel.setPadding(dp(18),dp(16),dp(18),dp(8));GradientDrawable dialogueSurface=shape(PALE,18);dialogueSurface.setStroke(dp(1),0xffcfddc5);draftPanel.setBackground(dialogueSurface);
        LinearLayout dialogueCaption=row();dialogueCaption.addView(text("念念的安排",14,HOME_TITLE,true),new LinearLayout.LayoutParams(0,-2,1));dialogueCaption.addView(text("等你确认",11,BLUE,false));draftPanel.addView(dialogueCaption);gap(draftPanel,12);
        TextView dialogueLead=text("",23,HOME_TITLE,true);dialogueLead.setGravity(Gravity.START);dialogueLead.setLineSpacing(dp(4),1f);dialogueLead.setPadding(0,0,0,dp(4));dialogueLead.setMinHeight(dp(48));draftPanel.addView(dialogueLead);
        TextView dialogueFollow=text("",14,INK,false);dialogueFollow.setLineSpacing(dp(3),1f);gap(draftPanel,8);draftPanel.addView(dialogueFollow);
        TextView dialogueBounds=text("",12,MUTED,false);dialogueBounds.setLineSpacing(dp(3),1f);gap(draftPanel,6);draftPanel.addView(dialogueBounds);
        LinearLayout titleEditor=column();
        EditText title=editorRowField(titleEditor,"title","小事名称",formValue("title",draft.optString("title")),"例如：收衣服",null);title.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));
        LinearLayout detailContents=column();detailContents.setVisibility(rulesExpanded?View.VISIBLE:View.GONE);
        LinearLayout summaryBlock=column();detailContents.addView(summaryBlock);
        TextView[] summaryLabels=new TextView[3],summaryValues=new TextView[3];
        LinearLayout[] summaryRows=new LinearLayout[3];String[] symbols={"clock","cycle","circle_check"};
        for(int i=0;i<3;i++){
            LinearLayout row=row();row.setPadding(dp(5),dp(8),0,dp(8));row.setMinimumHeight(dp(48));row.setGravity(Gravity.CENTER_VERTICAL);
            GardenIconView icon=new GardenIconView(this,symbols[i],INK);row.addView(icon,new LinearLayout.LayoutParams(dp(22),dp(22)));gapHorizontal(row,14);
            boolean stacked=i==0||getResources().getConfiguration().screenWidthDp<360||getResources().getConfiguration().fontScale>1.2f;
            LinearLayout words=stacked?column():row();summaryLabels[i]=text("",12,MUTED,false);words.addView(summaryLabels[i],new LinearLayout.LayoutParams(stacked?-1:dp(76),-2));if(stacked)gap(words,4);
            summaryValues[i]=text("",i==0?21:14,i==0?HOME_TITLE:INK,i==0);words.addView(summaryValues[i],stacked?new LinearLayout.LayoutParams(-1,-2):new LinearLayout.LayoutParams(0,-2,1));row.addView(words,new LinearLayout.LayoutParams(0,-2,1));gapHorizontal(row,8);row.addView(new GardenIconView(this,"chevron_right",MUTED),new LinearLayout.LayoutParams(dp(16),dp(16)));
            summaryBlock.addView(row);summaryRows[i]=row;
            if(i<2){View divider=new View(this);divider.setBackgroundColor(LINE);summaryBlock.addView(divider,new LinearLayout.LayoutParams(-1,dp(1)));}
        }
        TextView previewNote=text("",12,MUTED,false);summaryBlock.addView(previewNote);gap(summaryBlock,12);
        LinearLayout deliveryHeading=row();deliveryHeading.addView(text("怎样提醒你",12,MUTED,false),new LinearLayout.LayoutParams(0,-2,1));Button channels=linkButton("设置");deliveryHeading.addView(channels);summaryBlock.addView(deliveryHeading);
        summaryBlock.addView(text(ReminderCoordinator.availableChannelsSummary(this,session),13,INK,false));channels.setOnClickListener(v->{if(!taskBusy)navigate("reminders");});
        TextView followUp=text("",12,MUTED,false);gap(summaryBlock,8);summaryBlock.addView(followUp);gap(draftPanel,4);
        gap(draftPanel,10);View dialogueDivider=new View(this);dialogueDivider.setBackgroundColor(0xffcfddc5);draftPanel.addView(dialogueDivider,new LinearLayout.LayoutParams(-1,dp(1)));
        LinearLayout adjustRow=row();Button adjust=smallButton(rulesExpanded?"收起提醒细节":"查看提醒细节");adjust.setTextColor(BLUE);adjust.setTextSize(13);adjust.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);adjust.setBackground(shape(Color.TRANSPARENT,10));adjustRow.addView(adjust,new LinearLayout.LayoutParams(0,dp(48),1));GardenIconView adjustArrow=new GardenIconView(this,"chevron_down",BLUE);adjustArrow.setRotation(rulesExpanded?180:0);adjustRow.addView(adjustArrow,new LinearLayout.LayoutParams(dp(16),dp(16)));draftPanel.addView(adjustRow);
        planBody.addView(detailContents);
        LinearLayout rules=column();rules.setPadding(dp(10),dp(4),dp(10),dp(8));GradientDrawable ruleSurface=shape(PAPER,12);ruleSurface.setStroke(dp(1),LINE);rules.setBackground(ruleSurface);LinearLayout.LayoutParams rulesLp=new LinearLayout.LayoutParams(-1,-2);rulesLp.topMargin=dp(8);detailContents.addView(rules,rulesLp);rules.addView(titleEditor);
        Runnable refreshDetails=()->{detailContents.setVisibility(rulesExpanded?View.VISIBLE:View.GONE);adjust.setText(rulesExpanded?"收起提醒细节":"查看提醒细节");adjustArrow.setRotation(rulesExpanded?180:0);adjust.setContentDescription(rulesExpanded?"收起提醒细节，已展开":"查看提醒细节，已收起");};
        View.OnClickListener toggleRules=v->{if(taskBusy||editorConfirmed)return;rulesExpanded=!rulesExpanded;refreshDetails.run();};adjust.setOnClickListener(toggleRules);adjustRow.setOnClickListener(toggleRules);
        View.OnClickListener openRules=v->{if(taskBusy||editorConfirmed)return;rulesExpanded=true;refreshDetails.run();rules.post(()->rules.requestRectangleOnScreen(new Rect(0,0,rules.getWidth(),dp(180)),false));};
        dialogueLead.setFocusable(true);dialogueLead.setBackground(new RippleDrawable(ColorStateList.valueOf(0x18527a58),shape(Color.TRANSPARENT,10),null));dialogueLead.setOnClickListener(openRules);
        for(LinearLayout summaryRow:summaryRows){summaryRow.setFocusable(true);summaryRow.setOnClickListener(openRules);}
        planBody.addView(originalRequest);if(originalRequest.getChildCount()>0)gap(planBody,12);
        if(!aiWarning.isEmpty())addArrangementNotes(planBody);

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
        LinearLayout advancedPanel=column();advancedPanel.setPadding(dp(10),0,dp(10),dp(8));GradientDrawable advancedSurface=shape(PAPER,12);advancedSurface.setStroke(dp(1),LINE);advancedPanel.setBackground(advancedSurface);LinearLayout.LayoutParams advancedLp=new LinearLayout.LayoutParams(-1,-2);advancedLp.topMargin=dp(8);detailContents.addView(advancedPanel,advancedLp);
        LinearLayout advancedHeading=row();advancedHeading.addView(new GardenIconView(this,"gear",INK),new LinearLayout.LayoutParams(dp(21),dp(21)));gapHorizontal(advancedHeading,8);
        Button advanced=smallButton("更多设置");advanced.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);advanced.setTextColor(INK);advanced.setBackground(shape(Color.TRANSPARENT,10));advancedHeading.addView(advanced,new LinearLayout.LayoutParams(0,dp(48),1));GardenIconView advancedArrow=new GardenIconView(this,"chevron_down",INK);advancedArrow.setRotation(advancedExpanded?180:0);advancedHeading.addView(advancedArrow,new LinearLayout.LayoutParams(dp(20),dp(20)));advancedPanel.addView(advancedHeading);
        LinearLayout extra=column();advancedPanel.addView(extra);extra.setVisibility(advancedExpanded?View.VISIBLE:View.GONE);
        View.OnClickListener toggleAdvanced=v->{advancedExpanded=!advancedExpanded;extra.setVisibility(advancedExpanded?View.VISIBLE:View.GONE);advancedArrow.setRotation(advancedExpanded?180:0);advanced.setContentDescription("更多设置，"+(advancedExpanded?"已展开":"已收起"));};advanced.setOnClickListener(toggleAdvanced);advancedHeading.setOnClickListener(toggleAdvanced);
        field(extra,"description","补充说明",formValue("description",draft.optString("description")),"可不填",2);
        LinearLayout[] settingsPair=pairedFields(extra);field(settingsPair[0],"max_reminders","提醒次数上限",formValue("max_reminders",String.valueOf(rule.optInt("max_reminders",0))),"0 表示不限",1);number(fields.get("max_reminders"));
        field(settingsPair[1],"timezone","时区",formValue("timezone",rule.optString("timezone","Asia/Shanghai")),"Asia/Shanghai",1);note(extra,"上限填 0 表示不限次数，提醒按所选时区发送。");
        Button zonePreview=smallButton(value("timezone"));zonePreview.setTextColor(INK);zonePreview.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);zonePreview.setBackground(shape(Color.TRANSPARENT,8));LinearLayout minuteZone=editorControlRow(rules,"时区","globe",zonePreview);
        zonePreview.setOnClickListener(v->{advancedExpanded=true;extra.setVisibility(View.VISIBLE);advancedArrow.setRotation(180);EditText zone=fields.get("timezone");zone.requestFocus();zone.post(()->zone.requestRectangleOnScreen(new Rect(0,0,zone.getWidth(),zone.getHeight()),false));});
        refreshMinuteFields("first_at");refreshMinuteFields("end_at");
        Runnable updatePreview=()->{updateEditorSummary(summaryLabels,summaryValues,previewNote);if(kindPicker!=null&&modePicker!=null){followUp.setText(ReminderSummary.followUp(KINDS[kindPicker.getSelectedItemPosition()],MODES[modePicker.getSelectedItemPosition()]));updateSowingDialogue(dialogueLead,dialogueFollow,dialogueBounds);}};
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

        final int dialoguePage=pageEpoch;
        editorDialogueTick=new Runnable(){@Override public void run(){if(dialoguePage!=pageEpoch||!screen.equals("editor")||isDestroyed()||editorConfirmed)return;updatePreview.run();if(motionHostActive)ui.postDelayed(this,15000);}};
        if(motionHostActive)ui.postDelayed(editorDialogueTick,15000);
        addCompactFlowerChoice(planBody);
        gap(planBody,20);LinearLayout confirmBar=column();editorConfirmBar=confirmBar;confirmBar.setPadding(dp(16),dp(12),dp(16),dp(10));confirmBar.setBackgroundColor(PAPER);root.addView(confirmBar,new LinearLayout.LayoutParams(-1,-2));root.requestApplyInsets();
        Button save=button(editing?"保存修改":"好，就这样提醒我",true);save.setTextSize(16);save.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));save.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22527a58),shape(0xff567e5d,10),null));confirmBar.addView(save,new LinearLayout.LayoutParams(-1,dp(50)));save.setOnClickListener(v->saveEditor(save));
        gap(confirmBar,6);TextView saveHint=text(editing?"只修改安排，不会标记完成":"确认后开始提醒，做完小事再领水滴",11,MUTED,false);saveHint.setGravity(Gravity.CENTER);confirmBar.addView(saveHint);
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
        Spinner choice=new Spinner(this){@Override public boolean performClick(){if(taskBusy)return false;showRuleChoice(this,labels);return true;}};choice.setMinimumHeight(dp(48));choice.setBackground(shape(PAPER,10));choice.setPadding(dp(6),0,dp(2),0);
        ArrayAdapter<String> adapter=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_item,labels){
            @Override public View getView(int position,View convert,ViewGroup parent){
                LinearLayout line=row();line.setMinimumHeight(dp(48));TextView caption=text(getItem(position),14,INK,false);caption.setMaxLines(2);caption.setEllipsize(TextUtils.TruncateAt.END);
                line.addView(caption,new LinearLayout.LayoutParams(0,-2,1));line.addView(new GardenIconView(MainActivity.this,"chevron_right",INK),new LinearLayout.LayoutParams(dp(16),dp(16)));return line;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);choice.setAdapter(adapter);return choice;
    }
    private void showRuleChoice(Spinner choice,String[] labels){
        boolean modes=labels==MODE_LABELS;LinearLayout panel=dialogPanel(modes?"完成后怎么提醒":"重复方式",modes?"选择完成之后的提醒安排":"按自己的节奏，选择一种安排");RadioGroup group=new RadioGroup(this);panel.addView(group);int[] selectedIndex={choice.getSelectedItemPosition()};
        int count=modes?labels.length:8;for(int i=0;i<count;i++){final int index=i;RadioButton option=new RadioButton(this);option.setId(View.generateViewId());option.setText(labels[i]);option.setTextSize(14);option.setTextColor(INK);option.setButtonTintList(ColorStateList.valueOf(BLUE));option.setMinHeight(dp(48));option.setPadding(dp(4),dp(4),dp(4),dp(4));group.addView(option,new RadioGroup.LayoutParams(-1,-2));option.setChecked(selectedIndex[0]==i);option.setOnCheckedChangeListener((v,checked)->{if(checked)selectedIndex[0]=index;});}
        gap(panel,14);LinearLayout actions=row();Button cancel=button("取消",false),apply=button("确定",true);actions.addView(cancel,new LinearLayout.LayoutParams(0,-2,1));gapHorizontal(actions,10);actions.addView(apply,new LinearLayout.LayoutParams(0,-2,1));panel.addView(actions);AlertDialog dialog=gardenDialog(panel);int page=pageEpoch;
        if(modes&&dialog.getWindow()!=null){dialog.getWindow().setGravity(Gravity.BOTTOM);dialog.getWindow().setLayout(-1,-2);}
        cancel.setOnClickListener(v->dialog.dismiss());apply.setOnClickListener(v->{if(selectedIndex[0]>=count){toast("请先选择一种重复方式");return;}dialog.dismiss();if(page==pageEpoch)choice.setSelection(selectedIndex[0]);});
    }
    private void addSowingHeading(){
        boolean response=manualVisible;
        if(response){
            LinearLayout scene=row(),copy=column();scene.setPadding(dp(16),0,dp(16),dp(8));scene.setMinimumHeight(dp(104));
            copy.addView(homeTitle(editing?"照料这颗种子":"和念念说定一件事",21));gap(copy,6);copy.addView(text(editing?"调整提醒，按自己的节奏来":"你去忙，我来帮你记着。",12,MUTED,false));scene.addView(copy,new LinearLayout.LayoutParams(0,-2,1));
            editorCompanion=new SeedCompanionView(this);editorCompanion.setHostActive(motionHostActive);editorCompanion.setPose(editing||aiExpanded?SeedCompanionView.Pose.LISTEN:SeedCompanionView.Pose.SPEAK);
            scene.addView(editorCompanion,new LinearLayout.LayoutParams(dp(88),dp(96)));root.addView(scene,1,new LinearLayout.LayoutParams(-1,-2));
            if(editorCompanionEntrance!=null){editorCompanion.enterFrom(editorCompanionEntrance);editorCompanionEntrance=null;}
            return;
        }
        LinearLayout heading=row(),copy=column();copy.addView(homeTitle(editing?"照料这颗种子":"播下一件小事",25));gap(copy,8);copy.addView(text(editing?"调整提醒，按自己的节奏来":"把要记住的事，交给念念",13,INK,false));heading.addView(copy,new LinearLayout.LayoutParams(0,-2,1));
        editorCompanion=new SeedCompanionView(this);editorCompanion.setHostActive(motionHostActive);
        heading.addView(editorCompanion,new LinearLayout.LayoutParams(dp(104),dp(124)));body.addView(heading);gap(body,12);
    }
    private void updateSowingDialogue(TextView lead,TextView follow,TextView bounds){
        if(kindPicker==null||modePicker==null)return;
        String kind=KINDS[kindPicker.getSelectedItemPosition()],mode=MODES[modePicker.getSelectedItemPosition()];
        Map<String,String> data=new HashMap<>();for(String key:fields.keySet())data.put(key,value(key));
        List<Integer> days=new ArrayList<>();if(weekdays!=null)for(int i=0;i<weekdays.length;i++)if(weekdays[i].isChecked())days.add(i+1);
        String when=SowingDialogue.time(kind,data,days,Instant.now());String name=value("title").trim();
        String sentence=when.startsWith("请")?"先把时间补全，\n我再帮你记住这件事。":when.startsWith("原定")?"首次提醒"+when+"，\n接下来按规则继续。":"我会在 "+when+"\n提醒你"+(name.isEmpty()?"这件小事":name)+"。";
        SpannableString styled=new SpannableString(sentence);int offset=sentence.indexOf(when);if(offset>=0){styled.setSpan(new android.text.style.ForegroundColorSpan(BLUE),offset,offset+when.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);styled.setSpan(new android.text.style.UnderlineSpan(),offset,offset+when.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);}
        lead.setText(styled);lead.setContentDescription(sentence+"，点按修改时间和规则");follow.setText(SowingDialogue.followUp(kind,mode,data));
        // Deadline conversion needs the real timezone; omit only its redundant label.
        String note=SowingDialogue.visibleNote(kind,mode,data,ZoneId.systemDefault());
        bounds.setText(note);bounds.setVisibility(note.isEmpty()?View.GONE:View.VISIBLE);
    }
    private void addArrangementNotes(LinearLayout parent){
        LinearLayout notes=card(parent);notes.setPadding(dp(14),dp(12),dp(14),dp(4));notes.addView(text("为什么这样安排",13,INK,true));gap(notes,6);
        String first=aiWarning.split("\\n",2)[0].trim();TextView brief=text(first,13,MUTED,false);brief.setMaxLines(3);brief.setEllipsize(TextUtils.TruncateAt.END);brief.setLineSpacing(dp(3),1f);notes.addView(brief);
        TextView full=text(aiWarning,13,INK,false);full.setLineSpacing(dp(4),1f);full.setVisibility(View.GONE);notes.addView(full);
        Button toggle=linkButton("查看安排依据与注意事项");toggle.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);notes.addView(toggle);toggle.setOnClickListener(v->{boolean show=full.getVisibility()!=View.VISIBLE;full.setVisibility(show?View.VISIBLE:View.GONE);brief.setVisibility(show?View.GONE:View.VISIBLE);toggle.setText(show?"收起说明":"查看安排依据与注意事项");});
    }
    private void addCompactFlowerChoice(LinearLayout parent){
        gap(parent,6);LinearLayout heading=row(),choices=column();ImageView thumbnail=art(selectedFlowerIndex<0?R.drawable.garden_seed_v3:GardenFlowers.resources()[selectedFlowerIndex],44);heading.addView(thumbnail);gapHorizontal(heading,10);
        TextView label=text("",14,INK,true);heading.addView(label,new LinearLayout.LayoutParams(0,-2,1));Button change=linkButton("选花");heading.addView(change);parent.addView(heading);parent.addView(choices);choices.setVisibility(View.GONE);
        Runnable refresh=()->{label.setText(selectedFlowerIndex<0?"这次种一朵什么花？":GardenFlowers.names()[selectedFlowerIndex]+" · 这次就种它");thumbnail.setImageBitmap(GardenArtCache.load(getResources(),selectedFlowerIndex<0?R.drawable.garden_seed_v3:GardenFlowers.resources()[selectedFlowerIndex]));};
        addFlowerPreview(choices,refresh);refresh.run();View.OnClickListener toggle=v->{boolean show=choices.getVisibility()!=View.VISIBLE;choices.setVisibility(show?View.VISIBLE:View.GONE);change.setText(show?"收起":"选花");};heading.setOnClickListener(toggle);change.setOnClickListener(toggle);
    }
    private void addFlowerPreview(LinearLayout parent){addFlowerPreview(parent,()->{});}
    private void addFlowerPreview(LinearLayout parent,Runnable changed){
        gap(parent,10);LinearLayout heading=row();TextView title=text("选一朵喜欢的花",16,INK,true);title.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));heading.addView(title,new LinearLayout.LayoutParams(0,-2,1));heading.addView(text(GardenFlowers.names().length+" 种 · 左右滑动",11,MUTED,false));parent.addView(heading);gap(parent,12);
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
            item.setOnClickListener(v->{if(taskBusy){toast("正在处理提醒，请稍等");return;}selectedFlowerIndex=choice;update.run();changed.run();});LinearLayout.LayoutParams slot=new LinearLayout.LayoutParams(dp(getResources().getConfiguration().fontScale>1.2f?104:84),-2);if(i>0)slot.leftMargin=dp(8);flowers.addView(item,slot);
        }
        update.run();HorizontalScrollView rail=new HorizontalScrollView(this);rail.setHorizontalScrollBarEnabled(false);rail.addView(flowers);parent.addView(rail);if(selectedFlowerIndex>=0){final int chosen=selectedFlowerIndex;rail.post(()->{if(chosen<choices.length)rail.scrollTo(Math.max(0,choices[chosen].getLeft()-dp(80)),0);});}gap(parent,10);note(parent,"完成赚水滴，再来浇花。7 滴水，就能开花。");
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
        values[0].setText(ReminderSummary.leadTime(kind,data));
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
        for(int i=0;i<times.length;i++){final int position=i;final String time=times[i];Button chip=smallButton(time);row.addView(chip);gapHorizontal(row,6);chip.setContentDescription(time+"，修改或移除时间");chip.setOnClickListener(v->{
            LinearLayout panel=dialogPanel(time,"调整这个提醒时间");Button edit=button("修改时间",false),remove=button("移除时间",false),cancel=linkButton("取消");remove.setTextColor(RED);panel.addView(edit);gap(panel,8);panel.addView(remove);panel.addView(cancel,new LinearLayout.LayoutParams(-1,-2));AlertDialog dialog=gardenDialog(panel);int page=pageEpoch;
            edit.setOnClickListener(action->{dialog.dismiss();if(page==pageEpoch)pickTime(position,time);});remove.setOnClickListener(action->{dialog.dismiss();if(page!=pageEpoch)return;List<String> updated=new ArrayList<>(Arrays.asList(times));updated.remove(position);fields.get("times").setText(TextUtils.join(", ",updated));});cancel.setOnClickListener(action->dialog.dismiss());
        });}
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
        if(taskBusy||editorConfirmed)return;
        JSONObject payload;try{payload=buildTask();if(selectedFlowerIndex>=0)payload.put("flower_type",GardenFlowers.typeFor(selectedFlowerIndex));}catch(Exception e){captureTransient();rulesExpanded=true;String message=failureMessage(e);if(message.contains("时区")||message.contains("100 之间"))advancedExpanded=true;render();showError(message);if(message.contains("事情名称")){fields.get("title").setError(message);fields.get("title").requestFocus();}else if(message.contains("星期")&&weekdays!=null){weekdays[0].setError(message);weekdays[0].requestFocus();}return;}String id=draft.optString("id");boolean isEditing=editing;
        taskBusy=true;
        if(editorCompanion!=null)editorCompanion.setPose(SeedCompanionView.Pose.THINK);
        int flower=selectedFlowerIndex;
        run(button,a->mutateTask(a,isEditing?"PUT":"POST",isEditing?"/api/v1/tasks/"+id:"/api/v1/tasks",payload,id),result->{taskBusy=false;boolean flowerSaved=flower<0||GardenFlowers.remember(result.optString("id"),flower);selected=result;taskData=new JSONArray();restoredForm=null;invalidateCalendar();if(!isEditing){detailBack="home";filter="待完成";search="";homeSearchExpanded=false;draft=new JSONObject();aiText="";aiWarning="";clearClarification();manualVisible=false;editorSetupPromptPending=false;}toast(result.optBoolean("_reminder_sync_pending")?"事项已保存，手机提醒待联网同步":!flowerSaved?(isEditing?"提醒已修改，花种未保存，请重试":"提醒已创建，花种未保存，可在调整规则里重选"):isEditing?"修改已保存":"已安排好，做完后再来领取水滴");editorConfirmed=true;taskBusy=true;button.setEnabled(false);button.setText(isEditing?"修改已保存":"好，说定啦");if(editorCompanion!=null)editorCompanion.setPose(SeedCompanionView.Pose.HAPPY);
            int savedPage=pageEpoch,savedAccount=accountEpoch;
            Runnable returnToDestination=()->{if(isDestroyed()||pageEpoch!=savedPage||accountEpoch!=savedAccount)return;taskBusy=false;editorConfirmed=false;screen=isEditing?"detail":"home";render();};
            if(motionHostActive&&ValueAnimator.areAnimatorsEnabled())ui.postDelayed(returnToDestination,700);else returnToDestination.run();
        },failure->{if(editorCompanion!=null)editorCompanion.setPose(SeedCompanionView.Pose.SPEAK);showError(failureMessage(failure));});
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
        sceneHeader(true);TextView title=homeTitle("把一件小事，认真完成",22);title.setGravity(Gravity.CENTER);body.addView(title);gap(body,8);TextView subtitle=text("完成后领取水滴，去养喜欢的花",13,MUTED,false);subtitle.setGravity(Gravity.CENTER);body.addView(subtitle);gap(body,16);
        LinearLayout task=card(body),taskRow=row();taskRow.addView(art(plantResource(selected.optString("id")),36));gapHorizontal(taskRow,12);taskRow.addView(text(selected.optString("title"),16,INK,true),new LinearLayout.LayoutParams(0,-2,1));task.addView(taskRow);
        field(body,"completion","",completionText,"写两句，也可以不写",3);
        if(photoFile!=null&&photoFile.isFile()){
            LinearLayout chosen=row();ImageView image=new ImageView(this);image.setContentDescription("本次选择的照片");image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setBackground(shape(PALE,16));image.setClipToOutline(true);chosen.addView(image,new LinearLayout.LayoutParams(0,dp(130),1));loadLocalImage(image,photoFile);gapHorizontal(chosen,12);LinearLayout controls=column();controls.addView(text("已选 1 张照片",12,INK,false));gap(controls,8);Button remove=linkButton("移除照片");controls.addView(remove);remove.setOnClickListener(v->{if(taskBusy)return;captureTransient();photoFile=null;render();});chosen.addView(controls);body.addView(chosen);gap(body,12);
        }
        LinearLayout photoRow=row();Button camera=button("拍照留档",false),gallery=button("选择照片",false);photoRow.addView(camera,new LinearLayout.LayoutParams(0,dp(52),1));gapHorizontal(photoRow,10);photoRow.addView(gallery,new LinearLayout.LayoutParams(0,dp(52),1));body.addView(photoRow);gap(body,16);
        if(photoFile!=null)gallery.setText("重新选择");
        camera.setOnClickListener(v->{if(taskBusy)return;captureTransient();takePhoto();});gallery.setOnClickListener(v->{if(taskBusy)return;captureTransient();Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("image/*");i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"image/jpeg","image/png","image/webp"});try{startActivityForResult(i,102);}catch(Exception e){showError("没有可用的相册应用");}});
        TextView optional=text("照片和文字都可选，不是必须的哦",12,MUTED,false);optional.setGravity(Gravity.CENTER);body.addView(optional);gap(body,16);moveErrorHere(body);
        Button save=button("完成，领取水滴",true);body.addView(save);save.setOnClickListener(v->{
            if(taskBusy)return;taskBusy=true;String text=value("completion"),id=selected.optString("id");File photo=photoFile;
            run(save,a->{JSONArray ids=new JSONArray();if(photo!=null)ids.put(a.upload(photo).getString("id"));return mutateTask(a,"POST","/api/v1/tasks/"+id+"/complete",object("text",text,"media_ids",ids),id);},result->{taskBusy=false;photoFile=null;completionText="";taskData=new JSONArray();completedRecord=copy(result);invalidateCalendar();navigate("grown");});
        });
        gap(body,16);body.addView(homeEncouragement());botanicalFooter(R.drawable.sowing_footer_foliage,68);
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
            try{if(!entry.optString("local_date").isEmpty())day=LocalDate.parse(entry.optString("local_date"));}catch(java.time.DateTimeException ignored){}
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
            hit.setContentDescription(date+"，待提醒或已提醒 "+counts[0]+" 件，已完成 "+counts[1]+" 件，未完成 "+counts[2]+" 件，已过期 "+counts[3]+" 件"+(isToday?"，今天":"")+(chosenDate?"，已选择":""));
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
        heading.addView(text(stale?"上次读取":calendarLoaded()?entries.size()+" 件小事":"",12,MUTED,false));
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
            String summary=calendarStateLabel(entry)+(planned?" · 当天安排":reminder?" · 提醒记录":" · 完成记录");
            if(entry.optInt("reminder_count")>0)summary+=" · 当天已提醒 "+entry.optInt("reminder_count")+" 次";
            if(entry.optInt("completion_count")>1)summary+=" · 完成 "+entry.optInt("completion_count")+" 次";
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
            gap(calendarRecords,14);TextView hint=text("同一件事每天只显示一次，点击查看详情",11,MUTED,false);hint.setGravity(Gravity.CENTER);calendarRecords.addView(hint);
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
    private void calendarSkeleton(){loadingRows(calendarRecords);}
    private void loadingRows(LinearLayout parent){
        for(int i=0;i<3;i++){
            LinearLayout row=row();row.setPadding(dp(12),dp(14),dp(12),dp(14));row.setBackground(shape(0xfff1f2ec,12));
            View icon=new View(this);icon.setBackground(shape(0xffe2e7dc,16));row.addView(icon,new LinearLayout.LayoutParams(dp(32),dp(32)));gapHorizontal(row,12);
            LinearLayout bars=column();View title=new View(this);title.setBackground(shape(0xffe2e7dc,5));bars.addView(title,new LinearLayout.LayoutParams(dp(108),dp(12)));gap(bars,9);
            View subtitle=new View(this);subtitle.setBackground(shape(0xffe7eadf,4));bars.addView(subtitle,new LinearLayout.LayoutParams(dp(156),dp(8)));row.addView(bars);
            row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(8);parent.addView(row,lp);
        }
    }
    private int calendarStateIndex(JSONObject entry){switch(entry.optString("state")){case "completed":return 1;case "pending":return 2;case "expired":return 3;default:return 0;}}
    private int calendarStateColor(JSONObject entry){return new int[]{INFO,GREEN,YELLOW,RED}[calendarStateIndex(entry)];}
    private String calendarStateLabel(JSONObject entry){switch(entry.optString("state")){case "completed":return "已完成";case "pending":return "未完成";case "expired":return "已过期";case "notified":return "已提醒";default:return "待提醒";}}
    private String notificationChannel(JSONObject item){switch(item.optString("channel","email")){case "notification":return "手机通知";case "alarm":return "闹钟";case "calendar":return "系统日历";default:return "邮件";}}
    private String notificationLabel(JSONObject item){String channel=notificationChannel(item);switch(item.optString("status")){case "sent":case "accepted":return channel+"已发送";case "delivered":return channel+(channel.equals("系统日历")?"已写入":"已触发");case "failed":return channel+"执行失败";case "scheduled":return channel+"已排程";case "cancelled":return channel+"已撤销";case "unconfirmed":return channel+"未收到触发回执";case "pending":case "sending":return channel+"等待执行";default:return channel+"："+item.optString("status","未知");}}
    private void renderRecord(){
        boolean reminder=selectedRecord.optString("_type").equals("notification");
        String title=selectedRecord.optString("task_title","提醒详情"),status=selectedRecord.optString("status");
        boolean sent=status.equals("sent")||status.equals("accepted")||status.equals("delivered"),failed=status.equals("failed"),waiting=status.equals("pending")||status.equals("sending")||status.equals("scheduled");
        gap(body,18);body.addView(homeTitle(reminder?"提醒记录":"完成记录",28));gap(body,10);
        body.addView(text(reminder?notificationLabel(selectedRecord):"这一件小事，已经完成啦",14,HOME_TITLE,false));gap(body,22);
        LinearLayout info=card(body),identity=row();
        identity.addView(art(calendarRecordArt(selectedRecord),52));gapHorizontal(identity,12);
        LinearLayout words=column();TextView taskTitle=homeTitle(title,20);
        if(isFixedMailRecord()){taskTitle.setMaxLines(2);taskTitle.setEllipsize(TextUtils.TruncateAt.END);taskTitle.setContentDescription(title);}
        words.addView(taskTitle);
        if(!reminder){gap(words,8);words.addView(text(formatFull(selectedRecord.optString("_at")),13,MUTED,false));}
        identity.addView(words,new LinearLayout.LayoutParams(0,-2,1));info.addView(identity);gap(info,12);
        String badge=reminder?notificationLabel(selectedRecord):"已完成";
        int color=reminder?(failed?RED:sent?INFO:YELLOW):GREEN;
        LinearLayout statusRow=row();statusRow.addView(recordBadge(badge,color,reminder?(failed?"info":sent?"send":"clock"):"circle_check"));
        if(reminder&&selectedRecord.has("state")){gapHorizontal(statusRow,10);statusRow.addView(text(calendarStateLabel(selectedRecord),12,calendarStateColor(selectedRecord),false));}
        info.addView(statusRow);
        if(selectedRecord.optInt("reminder_count")>0){gap(info,8);note(info,"当天已提醒 "+selectedRecord.optInt("reminder_count")+" 次，同一天的跟进已合并在这里。");}
        JSONArray dayRecords=selectedRecord.optJSONArray("completion_records");
        if(dayRecords!=null&&dayRecords.length()>1){
            gap(info,12);info.addView(text("当天的 "+dayRecords.length()+" 次完成",14,INK,true));
            for(int i=0;i<dayRecords.length();i++){
                JSONObject record=dayRecords.optJSONObject(i);if(record==null)continue;
                Button memory=linkButton(RecordDates.localTime(record.optString("at"),ZoneId.systemDefault())+" · 查看这次留念");info.addView(memory);
                memory.setOnClickListener(v->{selectedRecord=copy(record);try{selectedRecord.put("_type","completion");selectedRecord.put("_at",record.optString("at"));}catch(JSONException ignored){}navigate("record");});
            }
        }
        if(reminder){
            gap(info,14);calendarDivider(info);
            recordInfoRow(info,"clock","计划提醒",formatFull(selectedRecord.optString("scheduled_at")));
            calendarDivider(info);
            String actual=selectedRecord.optString("sent_at");
            recordInfoRow(info,"send","执行时间",!actual.isEmpty()?formatFull(actual):failed?"未执行成功":sent?"未记录执行时间":"尚未执行");
            if(!selectedRecord.optString("reason").isEmpty()){gap(info,8);note(info,("ai".equals(selectedRecord.optString("source"))?"AI 安排：":"按规则执行：")+selectedRecord.optString("reason"));}
            if(failed||waiting){
                gap(info,8);LinearLayout message=column();message.setPadding(dp(12),dp(12),dp(12),dp(12));message.setBackground(shape(failed?0xffffeee5:0xffedf3e7,12));
                if(failed){message.addView(text("发生了什么",14,RED,true));gap(message,6);}
                String error=selectedRecord.optString("error");
                message.addView(text(failed?(error.isEmpty()?"这次提醒未执行成功，请检查渠道权限。":error):"提醒已排程，执行结果以手机反馈为准。",13,failed?RED:HOME_TITLE,false));info.addView(message);
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
                TextView delivery=text(notificationChannel(selectedRecord).equals("邮件")?"服务器已发出邮件，收件情况请以邮箱为准。":"手机反馈已触发提醒，不代表你已经看到或完成任务。",13,HOME_TITLE,false);delivery.setGravity(Gravity.CENTER);delivery.setPadding(dp(16),dp(16),dp(16),dp(16));delivery.setBackground(shape(0xffedf3e7,14));body.addView(delivery);gap(body,24);
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
        return screen.equals("record")&&selectedRecord.optString("_type").equals("notification")&&selectedRecord.optString("status").equals("sent")&&selectedRecord.optInt("reminder_count")<=1;
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
        FrameLayout hero=new FrameLayout(this);
        hero.addView(scenery(R.drawable.profile_header),new FrameLayout.LayoutParams(-1,-1));
        LinearLayout heading=column();heading.addView(homeTitle("我的小天地",24));gap(heading,8);
        heading.addView(text("照料生活，也照顾自己",13,INK,false));
        FrameLayout.LayoutParams headingLp=new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER_VERTICAL|Gravity.START);headingLp.leftMargin=dp(24);hero.addView(heading,headingLp);
        TextView sign=text("慢一点\n也没关系",13,HOME_WOOD_TEXT,false);sign.setTypeface(getResources().getFont(R.font.garden_handwriting));sign.setGravity(Gravity.CENTER);
        sign.setBackgroundResource(R.drawable.garden_notice_board);sign.setPadding(dp(8),dp(6),dp(8),dp(18));sign.setRotation(5);sign.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams signLp=new FrameLayout.LayoutParams(dp(86),dp(64),Gravity.BOTTOM|Gravity.END);signLp.setMargins(0,0,dp(14),dp(7));hero.addView(sign,signLp);
        // Preserve room for enlarged native text instead of clipping it into the artwork.
        int headerHeight=Math.max(132,Math.round(112*getResources().getConfiguration().fontScale));
        body.addView(hero,new LinearLayout.LayoutParams(-1,dp(headerHeight)));
        if(getResources().getConfiguration().screenWidthDp<340||getResources().getConfiguration().fontScale>1.15f)sign.setVisibility(View.GONE);
        LinearLayout content=column();content.setPadding(dp(16),dp(6),dp(16),0);body.addView(content);
        ((ViewGroup)errorView.getParent()).removeView(errorView);content.addView(errorView);
        LinearLayout account=card(content);account.setPadding(dp(12),dp(12),dp(12),dp(12));
        LinearLayout accountRow=row();account.addView(accountRow);
        FrameLayout mailPlate=new FrameLayout(this);mailPlate.setBackground(shape(PALE,28));mailPlate.addView(new GardenIconView(this,"mail",HOME_TITLE),new FrameLayout.LayoutParams(dp(26),dp(26),Gravity.CENTER));
        accountRow.addView(mailPlate,new LinearLayout.LayoutParams(dp(48),dp(48)));gapHorizontal(accountRow,12);
        LinearLayout identity=column();accountRow.addView(identity,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout caption=row();caption.addView(text("接收提醒的邮箱",12,INK,false),new LinearLayout.LayoutParams(0,-2,1));
        TextView signedIn=text("● 已登录",11,GREEN,false);caption.addView(signedIn);identity.addView(caption);gap(identity,6);
        TextView email=text(session.email,16,INK,true);email.setTextIsSelectable(true);identity.addView(email);gap(identity,5);
        identity.addView(text("账号已安全保存在这台手机",11,MUTED,false));
        content.addView(homeTitle("连接与数据",17));gap(content,8);
        LinearLayout group=card(content);group.setPadding(0,0,0,0);
        LinearLayout addressRow=row();addressRow.setPadding(dp(16),dp(10),dp(16),dp(10));
        addressRow.addView(new GardenIconView(this,"globe",BLUE),new LinearLayout.LayoutParams(dp(24),dp(24)));gapHorizontal(addressRow,14);
        LinearLayout server=column();server.addView(text("当前服务器地址",12,INK,false));gap(server,4);
        TextView address=text(session.base,12,MUTED,false);address.setTextIsSelectable(true);server.addView(address);addressRow.addView(server,new LinearLayout.LayoutParams(0,-2,1));group.addView(addressRow);
        ProfileAction change=new ProfileAction(group,"server","更换服务器地址","");change.setOnClickListener(v->navigate("server"));
        ProfileAction check=new ProfileAction(group,"wifi","检查连接和登录状态","");
        check.setOnClickListener(v->{
            String expectedEmail=session.email;check.state("正在检查连接和登录状态…",true,false);
            run(null,a->{
                if(!"ok".equals(a.json("GET","/health",null).optString("status")))throw new IOException("地址可访问，但不是念念提醒服务");
                JSONObject me=a.json("GET","/api/v1/me",null);
                if(!expectedEmail.equalsIgnoreCase(me.optString("email").trim()))throw new IOException("登录账号不一致，请重新登录");
                return me;
            },r->check.state("连接正常 · 当前账号登录有效",false,false),e->check.state("检查失败，点击重试\n"+failureMessage(e),false,true));
        });
        ProfileAction reminders=new ProfileAction(group,"clock","AI 提醒方式","通知、日历、闹钟与可选邮件");reminders.setOnClickListener(v->navigate("reminders"));
        ProfileAction password=new ProfileAction(group,"lock",session.hasCredentials()?"修改登录密码":"设置密码，以后自动登录","");
        password.setOnClickListener(v->{authEmail=session.email;openAuth(session.hasCredentials()?"reset":"register");});
        ProfileAction export=new ProfileAction(group,"export","导出我的全部文字记录","包含提醒规则、完成文字和邮件记录\n照片不包含在此文件中");
        export.setOnClickListener(v->{
            export.state("正在准备文字记录…\n照片不包含在此文件中",true,false);
            run(null,a->{
                JSONObject data=a.json("GET","/api/v1/export",null);
                // Unique private directory, readable filename in the native system share sheet.
                File dir=new File(getCacheDir(),"exports/"+UUID.randomUUID());if(!dir.mkdirs())throw new IOException("无法创建导出文件");
                File file=new File(dir,"念念记录-"+LocalDate.now()+".json");
                try(OutputStream out=new FileOutputStream(file)){out.write(data.toString(2).getBytes(StandardCharsets.UTF_8));}return file;
            },file->{
                try{
                    Uri uri=FileProvider.getUriForFile(this,getPackageName()+".files",file);
                    Intent share=new Intent(Intent.ACTION_SEND);share.setType("application/json");share.putExtra(Intent.EXTRA_STREAM,uri);
                    share.putExtra(Intent.EXTRA_TITLE,file.getName());share.setClipData(ClipData.newRawUri("念念文字记录",uri));share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(share,"保存或分享记录"));
                    export.state("文字文件已准备好，可再次导出\n照片不包含在此文件中",false,false);
                }catch(ActivityNotFoundException|SecurityException e){export.state("无法打开系统分享，点击重试",false,true);}
            },e->export.state("导出失败，点击重试\n"+failureMessage(e),false,true));
        });
        content.addView(homeTitle("关于念念",17));gap(content,7);
        ProfileAction update = new ProfileAction(content,"info","检查新版本",updater.versionLabel());
        update.setOnClickListener(v -> updater.check(true));
        LinearLayout about=row();about.addView(art(R.drawable.garden_sprout_v3,32));gapHorizontal(about,10);
        about.addView(text("AI 帮你安排与跟进，完成小事赚水滴。\n浇灌喜欢的花，让努力慢慢绽放。",12,MUTED,false),new LinearLayout.LayoutParams(0,-2,1));content.addView(about);
        body.addView(new Space(this),new LinearLayout.LayoutParams(1,0,1));
        FrameLayout footer=new FrameLayout(this);ImageView grass=scenery(R.drawable.sowing_footer_foliage);grass.setScaleType(ImageView.ScaleType.FIT_END);footer.addView(grass,new FrameLayout.LayoutParams(-1,-1));
        Button logout=button("退出登录",true);logout.setTextColor(PAPER);logout.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22ffffff),shape(0xffb76d59,12),null));
        FrameLayout.LayoutParams logoutLp=new FrameLayout.LayoutParams(-1,-2,Gravity.TOP);logoutLp.setMargins(dp(56),dp(12),dp(56),0);footer.addView(logout,logoutLp);logout.setOnClickListener(v->showProfileLogout());
        body.addView(footer,new LinearLayout.LayoutParams(-1,dp(Math.max(90,Math.round(66*getResources().getConfiguration().fontScale)))));
    }
    private String failureMessage(Exception e){return e.getMessage()==null?"请稍后再试":e.getMessage();}
    private final class ProfileAction extends LinearLayout {
        private final TextView caption;
        private final ProgressBar progress;
        private final GardenIconView arrow;
        private final String title;
        ProfileAction(LinearLayout parent,String icon,String title,String detail){
            super(MainActivity.this);this.title=title;
            View divider=new View(MainActivity.this);divider.setBackgroundColor(LINE);parent.addView(divider,new LinearLayout.LayoutParams(-1,dp(1)));
            setOrientation(HORIZONTAL);setGravity(Gravity.CENTER_VERTICAL);setMinimumHeight(dp(48));setPadding(dp(16),dp(9),dp(12),dp(9));
            setBackground(new RippleDrawable(ColorStateList.valueOf(0x18527a58),shape(Color.TRANSPARENT,12),null));
            addView(new GardenIconView(MainActivity.this,icon,HOME_TITLE),new LinearLayout.LayoutParams(dp(24),dp(24)));gapHorizontal(this,14);
            LinearLayout words=column();words.addView(text(title,14,INK,false));caption=text("",11,MUTED,false);caption.setPadding(0,dp(4),0,0);words.addView(caption);
            words.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);addView(words,new LinearLayout.LayoutParams(0,-2,1));gapHorizontal(this,8);
            progress=new ProgressBar(MainActivity.this);progress.setIndeterminateTintList(ColorStateList.valueOf(BLUE));progress.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);addView(progress,new LinearLayout.LayoutParams(dp(20),dp(20)));
            arrow=new GardenIconView(MainActivity.this,"chevron_right",INK);addView(arrow,new LinearLayout.LayoutParams(dp(16),dp(16)));
            setFocusable(true);setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            setAccessibilityDelegate(new View.AccessibilityDelegate(){@Override public void onInitializeAccessibilityNodeInfo(View host,android.view.accessibility.AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(host,info);info.setClassName(Button.class.getName());}});
            parent.addView(this,new LinearLayout.LayoutParams(-1,-2));state(detail,false,false);
        }
        void state(String detail,boolean busy,boolean failed){
            setEnabled(!busy);caption.setText(detail);caption.setTextColor(failed?RED:MUTED);caption.setVisibility(detail.isEmpty()?View.GONE:View.VISIBLE);
            progress.setVisibility(busy?View.VISIBLE:View.GONE);arrow.setVisibility(busy?View.GONE:View.VISIBLE);setContentDescription(title+(detail.isEmpty()?"":"，"+detail));
        }
    }
    private void dismissProfileDialog(){if(profileDialog!=null){profileDialog.dismiss();profileDialog=null;}}
    private void showProfileLogout(){
        if(profileDialog!=null)return;
        int account=accountEpoch,page=pageEpoch;
        LinearLayout panel=column();panel.setPadding(dp(22),dp(22),dp(22),dp(18));panel.setGravity(Gravity.CENTER_HORIZONTAL);panel.setBackground(shape(PAPER,20));
        panel.addView(art(R.drawable.garden_sprout_v3,42));gap(panel,10);TextView title=homeTitle("退出当前账号？",21);title.setGravity(Gravity.CENTER);panel.addView(title);gap(panel,14);
        TextView explanation=text("退出后，这台手机的通知、闹钟和念念日历提醒会撤销；已开启的邮件仍继续。任务和花园会保留。",15,INK,false);explanation.setGravity(Gravity.CENTER);panel.addView(explanation);gap(panel,12);
        TextView later=text("以后可再次登录，继续照料小花园。",12,MUTED,false);later.setGravity(Gravity.CENTER);panel.addView(later);gap(panel,18);
        TextView failure=text("",12,RED,false);failure.setVisibility(View.GONE);failure.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);panel.addView(failure);
        LinearLayout actions=row();panel.addView(actions,new LinearLayout.LayoutParams(-1,-2));
        Button cancel=button("取消",false);GradientDrawable outline=shape(PAPER,12);outline.setStroke(dp(1),LINE);cancel.setBackground(new RippleDrawable(ColorStateList.valueOf(0x18527a58),outline,null));actions.addView(cancel,new LinearLayout.LayoutParams(0,-2,1));gapHorizontal(actions,12);
        Button exit=button("退出",true);exit.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22ffffff),shape(0xffb76d59,12),null));actions.addView(exit,new LinearLayout.LayoutParams(0,-2,1));
        // A scroll container keeps confirmation reachable with large accessibility fonts.
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.addView(panel);
        AlertDialog dialog=new AlertDialog.Builder(this).create();profileDialog=dialog;dialog.setView(scroll,0,0,0,0);
        dialog.setOnDismissListener(d->{if(profileDialog==dialog)profileDialog=null;});cancel.setOnClickListener(v->dialog.dismiss());
        exit.setOnClickListener(v->{
            if(account!=accountEpoch||page!=pageEpoch){dialog.dismiss();return;}
            Api old=new Api(session.base,session.token);
            ReminderCoordinator.clear(this);
            try{session.logout();}catch(Exception e){ReminderCoordinator.attach(this,session);failure.setText("退出登录未保存，请重试。");failure.setVisibility(View.VISIBLE);return;}
            dialog.dismiss();accountEpoch++;clearAccountData();homeNotice="已退出登录。账号信息仍记在这台手机上，点登录即可继续。";navigate("home");
            ReminderCoordinator.afterClear(()->{try{old.json("POST","/api/v1/auth/logout",object());}catch(Exception ignored){}});
        });
        dialog.show();Window window=dialog.getWindow();if(window!=null){window.setBackgroundDrawable(shape(PAPER,20));window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);window.setDimAmount(.42f);window.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels-dp(40),dp(360)),-2);}
    }
    private void clearAccountData(){editorSetupPromptPending=false;reminderSettingsBack="settings";selectedFlowerIndex=-1;waterRequests.clear();plantRequestId="";gardenFocusPlantId="";gardenCareBusy=false;taskLoading=false;taskNotice="";gardenSelection=null;homeSearchExpanded=false;growthAnimationState.clear();gardenPage=0;gardenSelectedPlantId="";gardenMemoriesExpanded=false;gardenContent=null;gardenPreview=null;recordBack="history";completedRecord=new JSONObject();taskData=new JSONArray();taskLoadRequest++;pauseInFlight.clear();selected=new JSONObject();draft=new JSONObject();selectedRecord=new JSONObject();invalidateCalendar();calendarDate=LocalDate.now();calendarMonth=YearMonth.now();recordPage=0;detailBack="home";photoBack="record";photoId="";photoTitle="";calendarGrid=null;calendarRecords=null;calendarFeedback=null;calendarCaption=null;calendarRefresh=null;restoredForm=null;photoFile=null;cameraFile=null;search="";filter="待完成";aiText="";aiWarning="";clearClarification();completionText="";taskList=null;manualVisible=false;rulesExpanded=false;advancedExpanded=false;aiExpanded=true;}
    private interface Job<T>{T run(Api api)throws Exception;}
    private JSONObject mutateTask(Api api,String method,String path,JSONObject payload,String taskId)throws Exception{
        JSONObject result=api.json(method,path,payload);String changedId=taskId.isEmpty()?result.optString("id"):taskId;
        // Cancellation uses the immutable connection snapshot and survives Activity destruction.
        ReminderCoordinator.taskChanged(getApplicationContext(),api.base,api.token,changedId);
        boolean savedSchedule=(method.equals("POST")&&path.equals("/api/v1/tasks"))
                ||(method.equals("PUT")&&path.equals("/api/v1/tasks/"+changedId));
        if(savedSchedule&&!ReminderCoordinator.awaitTaskSync(getApplicationContext(),api.base,api.token))
            result.put("_reminder_sync_pending",true);
        return result;
    }
    private interface Result<T>{void accept(T value);}
    private <T> void run(Button trigger,Job<T> job,Result<T> success){
        run(trigger,job,success,null);
    }
    private <T> void run(Button trigger,Job<T> job,Result<T> success,Result<Exception> failure){
        int account=accountEpoch,page=pageEpoch;Api api=new Api(session.base,session.token);CharSequence caption=trigger==null?"":trigger.getText();if(trigger!=null){trigger.setEnabled(false);if(caption.length()>0)trigger.setText("请稍等…");}errorView.setVisibility(View.GONE);
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
    private void empty(LinearLayout parent,String title,String description){boolean compact=parent==taskList&&!search.isEmpty();LinearLayout box=column();box.setGravity(Gravity.CENTER_HORIZONTAL);box.setPadding(dp(12),dp(compact?8:18),dp(12),dp(compact?12:20));box.addView(art(R.drawable.garden_seed_v3,compact?64:96));gap(box,12);TextView t=text(title,17,INK,true);t.setGravity(Gravity.CENTER);box.addView(t);gap(box,8);TextView hint=text(description,13,MUTED,false);hint.setGravity(Gravity.CENTER);box.addView(hint);parent.addView(box);}
    private Spinner spinner(String[] labels){Spinner s=new Spinner(this);ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,labels);adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);s.setAdapter(adapter);s.setMinimumHeight(dp(52));s.setBackground(shape(PAPER,14));s.setPadding(dp(12),0,dp(12),0);return s;}
    private AdapterView.OnItemSelectedListener selection(Runnable action){return new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int i,long id){action.run();}public void onNothingSelected(AdapterView<?> p){}};}
    private TextWatcher watcher(Runnable action){return new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){action.run();}public void afterTextChanged(Editable e){}};}
    private void dateField(EditText e){e.setFocusable(false);e.setOnClickListener(v->{LocalDate date;try{date=LocalDate.parse(e.getText());}catch(Exception x){date=LocalDate.now();}new DatePickerDialog(this,(picker,y,m,d)->e.setText(LocalDate.of(y,m+1,d).toString()),date.getYear(),date.getMonthValue()-1,date.getDayOfMonth()).show();});e.setOnLongClickListener(v->{e.setText("");toast("日期已清除");return true;});}
    private void number(EditText e){e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);}
    private String value(String key){EditText field=fields.get(key);return field==null?"":field.getText().toString().trim();}
    private String formValue(String key,String fallback){return restoredForm==null?fallback:restoredForm.optString(key,fallback);}
    private int dp(int x){return Math.round(x*getResources().getDisplayMetrics().density);}
    private void showError(String message){TextView notice=errorView;notice.setText(message);notice.setVisibility(View.VISIBLE);if(screen.equals("garden")&&gardenDrawer!=null)gardenDrawer.setExpanded(true,true);notice.announceForAccessibility(message);notice.post(()->{if(notice.isAttachedToWindow())notice.requestRectangleOnScreen(new Rect(0,0,notice.getWidth(),notice.getHeight()),false);});}
    private void toast(String message){Toast.makeText(this,message,Toast.LENGTH_LONG).show();}
    private static JSONObject object(Object... values){JSONObject o=new JSONObject();try{for(int i=0;i<values.length;i+=2)o.put((String)values[i],values[i+1]);}catch(JSONException e){throw new IllegalArgumentException(e);}return o;}
    private static JSONObject copy(JSONObject o){try{return new JSONObject(o.toString());}catch(Exception e){return new JSONObject();}}
    private static int indexOf(String[] values,String value){for(int i=0;i<values.length;i++)if(values[i].equals(value))return i;return 0;}
    private static boolean contains(JSONArray array,int value){if(array!=null)for(int i=0;i<array.length();i++)if(array.optInt(i)==value)return true;return false;}
    private static String join(JSONArray array,String separator){StringBuilder b=new StringBuilder();for(int i=0;i<array.length();i++){if(i>0)b.append(separator);b.append(array.optString(i));}return b.toString();}
    private String statusLabel(String status){switch(status){case "done":return "本期已完成";case "upcoming":return "尚未开始";case "paused":return "已暂停";case "expired":return "已结束";default:return "待完成";}}
    private String taskStatusLabel(JSONObject task){JSONObject rule=task.optJSONObject("rule");return task.optString("status").equals("done")&&rule!=null&&rule.optString("mode").equals("notify_once")?"本期已提醒":statusLabel(task.optString("status"));}
    private int statusColor(String status){switch(status){case "done":return GREEN;case "pending":return YELLOW;case "upcoming":return INFO;case "expired":return RED;default:return MUTED;}}
    private String formatFull(String raw){try{return OffsetDateTime.parse(raw).atZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy/M/d HH:mm",Locale.CHINA));}catch(Exception e){return raw;}}
    private String formatTime(String raw){try{ZonedDateTime t=OffsetDateTime.parse(raw).atZoneSameInstant(ZoneId.systemDefault());return (t.toLocalDate().equals(LocalDate.now())?"今天 ":t.format(DateTimeFormatter.ofPattern("M/d ")))+t.format(DateTimeFormatter.ofPattern("HH:mm"));}catch(Exception e){return raw;}}
}
