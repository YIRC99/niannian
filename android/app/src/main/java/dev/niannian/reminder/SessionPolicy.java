package dev.niannian.reminder;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Pure account transitions shared by persistent storage and its regression checks. */
final class SessionPolicy {
    static final class State {
        final String base, email, token, password;
        final boolean autoLogin;
        State(String base, String email, String token, String password, boolean autoLogin) {
            this.base = base; this.email = email; this.token = token;
            this.password = password; this.autoLogin = autoLogin;
        }
        boolean hasCredentials() { return !email.isEmpty() && !password.isEmpty(); }
        State login(String token, String email, String password) {
            if (token == null || token.isEmpty() || email == null || email.trim().isEmpty())
                throw new IllegalArgumentException("服务器没有返回完整的登录信息，请重试");
            String account = email.trim().toLowerCase(Locale.ROOT);
            String savedPassword = password != null ? password
                    : account.equalsIgnoreCase(this.email) ? this.password : "";
            return new State(base, account, token, savedPassword, true);
        }
        State clear() { return new State(base, email, "", password, autoLogin); }
        State logout() { return new State(base, email, "", password, false); }
        State switchServer(String address) throws IOException {
            String destination = normalizeOrigin(address);
            String previous = "";
            try { if (!base.isEmpty()) previous = normalizeOrigin(base); }
            catch (IOException ignored) { /* Older versions accepted some invalid origins; allow replacing them. */ }
            if (destination.equals(previous)) return this;
            return new State(destination, email, "", password, autoLogin);
        }
    }
    static String normalizeOrigin(String raw) throws IOException {
        try {
            if (raw == null) throw new URISyntaxException("", "empty");
            URI uri = new URI(raw.trim());
            String scheme = uri.getScheme(), host = uri.getHost();
            int port = uri.getPort();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) || host == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || (uri.getPath() != null && !uri.getPath().isEmpty() && !uri.getPath().equals("/"))
                    || port == 0 || port > 65535 || uri.getRawAuthority().endsWith(":"))
                throw new IOException("请填写完整的服务器地址，例如 https://example.com 或 http://192.168.1.10:56507，不包含接口路径");
            scheme = scheme.toLowerCase(Locale.ROOT);
            host = host.toLowerCase(Locale.ROOT);
            if (host.indexOf(':') >= 0 && !host.startsWith("[")) host = "[" + host + "]";
            boolean defaultPort = port == -1 || (scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443);
            return scheme + "://" + host + (defaultPort ? "" : ":" + port);
        } catch (URISyntaxException e) {
            throw new IOException("服务器地址格式不正确，请检查 http:// 或 https://");
        }
    }
}
