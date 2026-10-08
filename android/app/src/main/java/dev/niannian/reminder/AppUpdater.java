package dev.niannian.reminder;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Public update requests use an immutable server snapshot and never send login tokens. */
final class AppUpdater {
    private final Activity activity;
    private final Session session;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private boolean closed, foreground, checking, awaitingPermission, readyWhileAway;
    private volatile boolean downloading;
    private String checkedBase = "", prompted = "", pendingBase = "";
    private long checkedAt;
    private File pendingFile;
    private JSONObject pendingRelease;
    private AlertDialog dialog;
    private volatile HttpURLConnection downloadConnection;

    AppUpdater(Activity activity, Session session) { this.activity = activity; this.session = session; }
    String versionLabel() {
        try { PackageInfo p = installed(); return "当前版本 " + p.versionName + "（" + versionCode(p) + "）"; }
        catch (Exception e) { return "当前版本暂时无法读取"; }
    }
    private PackageInfo installed() throws Exception { return activity.getPackageManager().getPackageInfo(activity.getPackageName(), PackageManager.GET_SIGNATURES); }
    private static long versionCode(PackageInfo p) { return Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode; }
    private boolean active(String base) { return !closed && !activity.isFinishing() && !activity.isDestroyed() && base.equals(session.base); }
    void resume() {
        foreground = true;
        if (readyWhileAway && pendingFile != null && active(pendingBase)) { readyWhileAway = false; showReady("安装包已下载并校验，可以继续安装。"); return; }
        if (awaitingPermission) {
            awaitingPermission = false;
            if (pendingFile != null && active(pendingBase)) {
                if (activity.getPackageManager().canRequestPackageInstalls()) installReady();
                else showReady("尚未允许安装，可再次点击继续安装。");
                return;
            }
        }
        check(false);
    }
    void pause() { foreground = false; }
    void serverChanged() {
        if (dialog != null) dialog.dismiss();
        pendingFile = null; pendingRelease = null; awaitingPermission = false; readyWhileAway = false; prompted = ""; checkedBase = "";
        HttpURLConnection c = downloadConnection; if (c != null) c.disconnect();
        check(false);
    }
    void check(boolean manual) {
        final String base = session.base;
        if (closed || base.isEmpty()) return;
        if (manual && pendingFile != null && pendingFile.isFile() && base.equals(pendingBase)) { showReady("安装包已准备好，可以继续安装。"); return; }
        if (downloading || checking) { if (manual) toast(downloading ? "正在下载更新，请稍候" : "正在检查更新"); return; }
        long now = System.currentTimeMillis();
        if (!manual && base.equals(checkedBase) && now - checkedAt < 6 * 60 * 60 * 1000L) return;
        checking = true;
        if (manual) toast("正在检查新版本…");
        worker.execute(() -> {
            try {
                long current = versionCode(installed());
                JSONObject result = new Api(base, "").json("GET", "/api/v1/updates/latest?version_code=" + current, null);
                JSONObject release = result.optJSONObject("release");
                ui.post(() -> {
                    checking = false;
                    if (!active(base)) { check(false); return; }
                    if (!foreground) return;
                    checkedBase = base; checkedAt = System.currentTimeMillis();
                    if (result.optBoolean("available") && release != null && UpdatePolicy.newer(current, release.optLong("version_code"))) {
                        String key = base + ":" + release.optLong("version_code");
                        if (manual || !key.equals(prompted)) { prompted = key; offer(base, release); }
                    } else if (manual) toast("已是当前服务器发布的最新版本");
                });
            } catch (Exception e) { ui.post(() -> { checking = false; if (!base.equals(session.base)) { check(false); return; } if (manual && active(base)) toast(e instanceof Api.Failure && ((Api.Failure)e).status == 404 ? "服务器尚未启用版本检查，请先更新后端" : "检查更新失败：" + message(e)); }); }
        });
    }
    private void offer(String base, JSONObject release) {
        String notes = release.optString("notes").trim();
        if (notes.isEmpty()) notes = "有新的版本可供下载。";
        dialog = new AlertDialog.Builder(activity).setTitle("念念 " + release.optString("version_name") + " 可以更新了")
            .setMessage(versionLabel() + "\n安装包 " + String.format(java.util.Locale.CHINA, "%.1f MB", release.optLong("size") / 1048576.0) + "\n\n" + notes)
            .setNegativeButton("稍后再说", null).setPositiveButton("下载更新", (d, which) -> download(base, release)).show();
    }
    private void download(String base, JSONObject release) {
        if (!active(base) || downloading) return;
        String path = release.optString("download_path"), digest = release.optString("sha256"); long expected = release.optLong("size");
        if (!UpdatePolicy.validDownloadPath(path) || !UpdatePolicy.validDigest(digest) || expected < 1 || expected > 200L * 1024 * 1024) { toast("服务器返回的更新信息不完整，请联系管理员"); return; }
        downloading = true;
        dialog = new AlertDialog.Builder(activity).setTitle("正在下载更新").setMessage("0%\n下载完成后会校验安装包。可以切换应用，返回后继续安装。")
            .setNegativeButton("取消下载", (d, which) -> { HttpURLConnection c = downloadConnection; if (c != null) c.disconnect(); downloading = false; }).setCancelable(false).show();
        final AlertDialog progress = dialog;
        worker.execute(() -> {
            File file = null;
            try {
                File dir = new File(activity.getCacheDir(), "updates"); if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("无法创建下载目录");
                File[] old = dir.listFiles(); if (old != null) for (File f : old) if (f.getName().startsWith("update-")) f.delete();
                file = File.createTempFile("update-", ".apk", dir);
                HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection(); downloadConnection = c;
                c.setInstanceFollowRedirects(false); c.setConnectTimeout(15000); c.setReadTimeout(30000);
                if (c.getResponseCode() != 200) throw new IOException("版本已下架或下载失败，请重新检查更新");
                MessageDigest sha = MessageDigest.getInstance("SHA-256"); long count = 0; int lastPercent = -1;
                try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(file)) {
                    byte[] b = new byte[64 * 1024]; int n;
                    while ((n = in.read(b)) != -1) {
                        if (!downloading || Thread.currentThread().isInterrupted()) throw new IOException("下载已取消");
                        count += n; if (count > expected) throw new IOException("安装包大小与发布信息不一致");
                        out.write(b, 0, n); sha.update(b, 0, n);
                        int percent = (int)(count * 100 / expected);
                        if (percent != lastPercent) { lastPercent = percent; ui.post(() -> { if (active(base) && progress.isShowing()) progress.setMessage(percent + "%\n下载完成后会校验安装包。可以切换应用，返回后继续安装。"); }); }
                    }
                    out.getFD().sync();
                }
                if (count != expected || !digest.equals(hex(sha.digest()))) throw new IOException("安装包校验失败，请重新下载");
                verifyPackage(file, release);
                File ready = file;
                ui.post(() -> {
                    boolean cancelled = !downloading;
                    downloading = false; progress.dismiss();
                    if (!active(base) || cancelled) { ready.delete(); if (!base.equals(session.base)) check(false); return; }
                    pendingFile = ready; pendingBase = base; pendingRelease = release;
                    if (foreground) installReady(); else { readyWhileAway = true; toast("新版已下载，回到念念后可继续安装"); }
                });
            } catch (Exception e) {
                if (file != null) file.delete();
                ui.post(() -> { boolean cancelled = !downloading; downloading = false; progress.dismiss(); if (!base.equals(session.base)) { check(false); return; } if (active(base) && !cancelled && foreground) {
                    dialog = new AlertDialog.Builder(activity).setTitle("更新未完成").setMessage(message(e)).setNegativeButton("稍后", null).setPositiveButton("重试", (d, which) -> check(true)).show();
                } });
            } finally { HttpURLConnection c = downloadConnection; downloadConnection = null; if (c != null) c.disconnect(); }
        });
    }
    private void verifyPackage(File file, JSONObject release) throws Exception {
        PackageInfo apk = activity.getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(), PackageManager.GET_SIGNATURES);
        PackageInfo own = installed();
        if (apk == null || !activity.getPackageName().equals(apk.packageName) || versionCode(apk) != release.optLong("version_code") || !UpdatePolicy.newer(versionCode(own), versionCode(apk))) throw new IOException("安装包不属于念念，或版本信息不一致");
        if (apk.signatures == null || own.signatures == null || !signatures(apk.signatures).equals(signatures(own.signatures))) throw new IOException("安装包签名与当前应用不一致，请上传使用同一签名构建的正式版");
    }
    private static Set<String> signatures(Signature[] signatures) { Set<String> set = new HashSet<>(); for (Signature s : signatures) set.add(s.toCharsString()); return set; }
    private void installReady() {
        if (pendingFile == null || !pendingFile.isFile() || !active(pendingBase)) return;
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            dialog = new AlertDialog.Builder(activity).setTitle("允许安装念念更新")
                .setMessage("安装包已下载并校验。请在系统设置中允许念念安装应用，再返回继续。")
                .setNegativeButton("稍后", null).setPositiveButton("去设置", (d, which) -> {
                    try { awaitingPermission = true; activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.getPackageName()))); }
                    catch (Exception e) { awaitingPermission = false; toast("无法打开设置，请在系统设置中允许安装应用"); }
                }).show(); return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".files", pendingFile);
            Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(intent);
        } catch (Exception e) { showReady("无法打开系统安装器。请检查安装权限后重试。"); }
    }
    private void showReady(String message) { dialog = new AlertDialog.Builder(activity).setTitle("新版已下载").setMessage(message).setNegativeButton("稍后", null).setPositiveButton("继续安装", (d, which) -> installReady()).show(); }
    private void toast(String text) { Toast.makeText(activity, text, Toast.LENGTH_LONG).show(); }
    private static String message(Exception e) { return e.getMessage() == null ? "请检查网络后重试" : e.getMessage(); }
    private static String hex(byte[] b) { StringBuilder s = new StringBuilder(); for (byte v : b) s.append(String.format(java.util.Locale.ROOT, "%02x", v & 255)); return s.toString(); }
    void close() { closed = true; if (dialog != null) dialog.dismiss(); HttpURLConnection c = downloadConnection; if (c != null) c.disconnect(); worker.shutdownNow(); ui.removeCallbacksAndMessages(null); }
}
