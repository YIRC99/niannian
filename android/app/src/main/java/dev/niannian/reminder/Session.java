package dev.niannian.reminder;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class Session {
    private final SharedPreferences prefs;
    String base, email, token;
    Session(Context ctx) {
        prefs = ctx.getSharedPreferences("connection", Context.MODE_PRIVATE);
        base = prefs.getString("base", ""); email = prefs.getString("email", ""); token = "";
        try {
            String encrypted = prefs.getString("token", "");
            if (!encrypted.isEmpty()) {
                byte[] all = Base64.decode(encrypted, Base64.NO_WRAP);
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, all, 0, 12));
                token = new String(c.doFinal(all, 12, all.length - 12), java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) { prefs.edit().remove("token").apply(); }
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
    void login(String value, String address) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = c.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] all = new byte[c.getIV().length + encrypted.length];
        System.arraycopy(c.getIV(), 0, all, 0, c.getIV().length); System.arraycopy(encrypted, 0, all, c.getIV().length, encrypted.length);
        token = value; email = address;
        prefs.edit().putString("token", Base64.encodeToString(all, Base64.NO_WRAP)).putString("email", address).apply();
    }
    void clear() { token = ""; prefs.edit().remove("token").apply(); }
    void switchServer(String address) { clear(); base = address; email = ""; prefs.edit().putString("base", address).remove("email").apply(); }
}
