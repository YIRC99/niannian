package dev.niannian.reminder;

import java.io.IOException;

public final class SessionPolicyTest {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        same("https://example.com", SessionPolicy.normalizeOrigin(" HTTPS://EXAMPLE.COM:443/ "), "same HTTPS origin");
        same("http://example.com", SessionPolicy.normalizeOrigin("http://EXAMPLE.com:80"), "same HTTP origin");
        same("http://192.168.1.2:56507", SessionPolicy.normalizeOrigin("http://192.168.1.2:56507/"), "custom port retained");
        same("http://[2001:db8::1]", SessionPolicy.normalizeOrigin("HTTP://[2001:DB8::1]:80/"), "IPv6 retained");
        same("https://[::1]:56507", SessionPolicy.normalizeOrigin("https://[::1]:56507/"), "IPv6 custom port");
        for (String invalid : new String[]{null, "", "example.com", "ftp://example.com", "https://u:p@example.com",
                "https://example.com/api", "https://example.com/?q=1", "https://example.com/#a", "http://example.com:0",
                "http://example.com:65536", "http://example.com:", "http://example.com\\@elsewhere.com"}) {
            try { SessionPolicy.normalizeOrigin(invalid); throw new AssertionError("accepted invalid origin: " + invalid); }
            catch (IOException expected) { assertions++; }
        }
        SessionPolicy.State logged = new SessionPolicy.State("https://example.com", "me@example.com", "token-A", "password-A", true);
        yes(logged.hasCredentials(), "saved credentials available");
        SessionPolicy.State unchanged = logged.switchServer("HTTPS://EXAMPLE.COM:443/");
        yes(unchanged == logged, "equivalent address must not replace session");
        same("token-A", unchanged.token, "same server keeps token");
        SessionPolicy.State switched = logged.switchServer("https://other.example.com/");
        same("https://other.example.com", switched.base, "different server normalized");
        same("", switched.token, "old server token must not reach new server");
        same(logged.email, switched.email, "server switch preserves email");
        same(logged.password, switched.password, "server switch preserves password");
        yes(switched.autoLogin, "server switch preserves automatic login preference");
        for (String legacyOrigin : new String[]{"http://example.com:0", "http://example.com:65536", "http://example.com:"}) {
            SessionPolicy.State legacy = new SessionPolicy.State(legacyOrigin, logged.email, logged.token, logged.password, true);
            SessionPolicy.State repaired = legacy.switchServer("https://valid.example.com/");
            same("https://valid.example.com", repaired.base, "invalid legacy origin can be replaced");
            same("", repaired.token, "replacing invalid legacy origin clears old token");
            same(logged.email, repaired.email, "repair keeps saved account");
            same(logged.password, repaired.password, "repair keeps saved password");
            yes(repaired.autoLogin, "repair keeps automatic login preference");
        }
        SessionPolicy.State expired = logged.clear();
        same("", expired.token, "expired token cleared");
        same(logged.password, expired.password, "expiry retains credentials");
        yes(expired.autoLogin, "expiry allows one later automatic login");
        SessionPolicy.State out = logged.logout();
        same("", out.token, "logout clears token");
        yes(!out.autoLogin, "explicit logout prevents immediate automatic login");
        same(logged.password, out.password, "logout keeps default password fill");
        SessionPolicy.State switchedOut = out.switchServer("https://other.example.com");
        yes(!switchedOut.autoLogin, "address switch does not undo explicit logout preference");
        SessionPolicy.State signed = out.login("new-token", logged.email, "new-password");
        same("new-token", signed.token, "successful login keeps returned token");
        same("new-password", signed.password, "successful login replaces password");
        yes(signed.autoLogin, "successful login enables automatic login");
        SessionPolicy.State oldLogin = logged.login("otp-token", "ME@EXAMPLE.COM", null);
        same(logged.password, oldLogin.password, "same account legacy login retains password");
        SessionPolicy.State different = logged.login("other-token", "other@example.com", null);
        same("", different.password, "legacy account change must not reuse another password");
        yes(!different.hasCredentials(), "account without password cannot automatically login");
        yes(!new SessionPolicy.State("", "", "", "anything", true).hasCredentials(), "empty email has no credentials");
        System.out.println("SessionPolicyTest: " + assertions + " assertions passed");
    }
    private static void same(String expected, String actual, String label) {
        yes(expected.equals(actual), label + ": expected " + expected + ", got " + actual);
    }
    private static void yes(boolean value, String label) { assertions++; if (!value) throw new AssertionError(label); }
}
