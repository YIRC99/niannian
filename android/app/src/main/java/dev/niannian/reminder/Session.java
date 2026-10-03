package dev.niannian.reminder;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

final class Session {
    private static final String RECORD_KEY = "account_v1";
    private static final byte[] RECORD_AAD = "niannian.account.v1".getBytes(StandardCharsets.UTF_8);
    private final SharedPreferences prefs;
    String base, email, token, password;
    boolean autoLogin;
    Session(Context ctx) {
        prefs = ctx.getSharedPreferences("connection", Context.MODE_PRIVATE);
        base = prefs.getString("base", "");
        try { if (!base.isEmpty()) base = Api.normalize(base); } catch (IOException ignored) { }
        email = ""; token = ""; password = ""; autoLogin = true;
        String record = prefs.getString(RECORD_KEY, "");
        if (!record.isEmpty()) {
            try {
                JSONObject account = new JSONObject(decrypt(record, true));
                if (account.optInt("version", 0) != 1) throw new IOException("Unsupported account record");
                // The origin lives in the same authenticated record as its token.
                SessionPolicy.State saved = new SessionPolicy.State(account.optString("base", base),
                        account.optString("email", ""), account.optString("token", ""),
                        account.optString("password", ""), account.optBoolean("auto_login", true));
                use(saved);
                return;
            } catch (Exception ignored) {
                // Keep an unreadable record intact; a later successful sign-in can replace it.
                // Never reuse a token whose authenticated account record could not be read.
                return;
            }
        }
        // Upgrade an existing installation without turning a valid token into a logout.
        email = prefs.getString("email", "");
        try {
            String encrypted = prefs.getString("token", "");
            if (!encrypted.isEmpty()) token = decrypt(encrypted, false);
        } catch (Exception ignored) { }
        if (!email.isEmpty() || !token.isEmpty()) {
            try { persist(state()); }
            catch (Exception ignored) { /* Retain the old record and the valid in-memory session on migration failure. */ }
        }
    }
    private SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        if (!ks.containsAlias("niannian-session")) {
            KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            gen.init(new KeyGenParameterSpec.Builder("niannian-session", KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            gen.generateKey();
        }
        return (SecretKey) ks.getKey("niannian-session", null);
    }
    private String encrypt(String value) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key());
        c.updateAAD(RECORD_AAD);
        byte[] encrypted = c.doFinal(value.getBytes(StandardCharsets.UTF_8));
        byte[] all = new byte[c.getIV().length + encrypted.length];
        System.arraycopy(c.getIV(), 0, all, 0, c.getIV().length); System.arraycopy(encrypted, 0, all, c.getIV().length, encrypted.length);
        return Base64.encodeToString(all, Base64.NO_WRAP);
    }
    private String decrypt(String value, boolean authenticatedRecord) throws Exception {
        byte[] all = Base64.decode(value, Base64.NO_WRAP);
        if (all.length < 28) throw new IOException("Invalid encrypted account record");
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, all, 0, 12));
        if (authenticatedRecord) c.updateAAD(RECORD_AAD);
        return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
    }
    private SessionPolicy.State state() {
        return new SessionPolicy.State(base, email, token, password, autoLogin);
    }
    private void use(SessionPolicy.State next) {
        base = next.base; email = next.email; token = next.token;
        password = next.password; autoLogin = next.autoLogin;
    }
    private void persist(SessionPolicy.State next) throws Exception {
        JSONObject account = new JSONObject().put("version", 1).put("base", next.base)
                .put("email", next.email).put("token", next.token).put("password", next.password)
                .put("auto_login", next.autoLogin);
        String encrypted = encrypt(account.toString());
        if (!prefs.edit().putString(RECORD_KEY, encrypted).putString("base", next.base)
                .remove("token").remove("email").commit())
            throw new IOException("无法保存登录信息，请检查设备存储空间后重试");
    }
    private void save(SessionPolicy.State next) {
        try { persist(next); use(next); }
        catch (Exception e) { throw new IllegalStateException("无法保存登录信息，请重试", e); }
    }
    synchronized void login(String value, String address, String secret) throws Exception {
        SessionPolicy.State next = state().login(value, address, secret);
        persist(next);
        use(next);
    }
    synchronized void login(String value, String address) throws Exception { login(value, address, null); }
    synchronized boolean hasCredentials() { return state().hasCredentials(); }
    synchronized void clear() { save(state().clear()); }
    synchronized void logout() { save(state().logout()); }
    synchronized void switchServer(String address) {
        try {
            SessionPolicy.State current = state();
            SessionPolicy.State next = current.switchServer(address);
            if (next != current) save(next);
        } catch (IOException e) { throw new IllegalArgumentException(e.getMessage(), e); }
    }
}
