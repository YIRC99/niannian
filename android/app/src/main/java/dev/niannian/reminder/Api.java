package dev.niannian.reminder;

import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** An immutable connection snapshot; never reads mutable account preferences. */
final class Api {
    final String base, token;
    Api(String base, String token) { this.base = base; this.token = token; }
    static class Failure extends IOException {
        final int status;
        Failure(int status, String message) { super(message); this.status = status; }
    }
    static String normalize(String raw) throws IOException {
        return SessionPolicy.normalizeOrigin(raw);
    }
    JSONObject json(String method, String path, JSONObject data) throws Exception {
        int maxBytes = path.equals("/api/v1/export") ? 32 * 1024 * 1024 : 8 * 1024 * 1024;
        byte[] bytes = request(method, path, data == null ? null : data.toString().getBytes(StandardCharsets.UTF_8), "application/json; charset=utf-8", maxBytes);
        return bytes.length == 0 ? new JSONObject() : new JSONObject(new String(bytes, StandardCharsets.UTF_8));
    }
    byte[] media(String id) throws Exception { return request("GET", "/api/v1/media/" + id, null, null, 11 * 1024 * 1024); }
    JSONObject upload(File file) throws Exception {
        if (!file.isFile() || file.length() > 10 * 1024 * 1024) throw new IOException("照片需要小于 10 MB，请重新选择");
        String boundary = "Niannian" + System.nanoTime();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"photo.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        try (InputStream in = new FileInputStream(file)) { byte[] buffer = new byte[8192]; int n; while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n); }
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return new JSONObject(new String(request("POST", "/api/v1/media", out.toByteArray(), "multipart/form-data; boundary=" + boundary, 1024 * 1024), StandardCharsets.UTF_8));
    }
    private byte[] request(String method, String path, byte[] body, String contentType, int max) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(base + path).openConnection();
        conn.setConnectTimeout(15000); conn.setReadTimeout(path.contains("/ai/") ? 75000 : 25000);
        conn.setInstanceFollowRedirects(false);
        conn.setRequestMethod(method);
        conn.setRequestProperty("Accept", "application/json");
        if (!token.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + token);
        try {
            if (body != null) {
                conn.setDoOutput(true); conn.setRequestProperty("Content-Type", contentType); conn.setFixedLengthStreamingMode(body.length);
                try (OutputStream stream = conn.getOutputStream()) { stream.write(body); }
            }
            int code = conn.getResponseCode();
            InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            byte[] result;
            try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                if (in != null) {
                    byte[] buffer = new byte[8192]; int n;
                    while ((n = in.read(buffer)) != -1) { if (out.size() + n > max) throw new IOException("服务器返回的内容过大"); out.write(buffer, 0, n); }
                }
                result = out.toByteArray();
            }
            if (code < 200 || code >= 300) {
                String message = code == 401 ? "登录已过期，请重新登录" : "请求未完成（" + code + "），请稍后重试";
                try { message = new JSONObject(new String(result, StandardCharsets.UTF_8)).optString("error", message); } catch (Exception ignored) {}
                if (code >= 300 && code < 400) message = "服务器发生跳转，请将地址直接设置为最终的 HTTPS 地址";
                throw new Failure(code, message);
            }
            return result;
        } catch (SocketTimeoutException e) { throw new IOException("连接超时，请检查服务器地址和网络后重试"); }
        catch (UnknownHostException e) { throw new IOException("找不到服务器，请检查地址和网络"); }
        catch (ConnectException e) { throw new IOException("连接不上服务器，请确认服务已启动且端口可访问"); }
        finally { conn.disconnect(); }
    }
}
