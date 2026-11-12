package com.agendum.app;

import android.content.*;
import android.security.keystore.*;
import android.util.Base64;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** 令牌只存原生侧；备份不得把本机 Keystore 之外的密文误当有效凭据。 */
public final class Credentials {
    private final SharedPreferences prefs;
    public Credentials(Context context) { prefs = context.getSharedPreferences("schedule_connection", Context.MODE_PRIVATE); }
    private javax.crypto.SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (!store.containsAlias("agendum-device")) {
            KeyGenerator generator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder("agendum-device", KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return (javax.crypto.SecretKey)store.getKey("agendum-device", null);
    }
    public synchronized void save(String base, String server, String token) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key());
        String encrypted = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(cipher.doFinal(token.getBytes("UTF-8")), Base64.NO_WRAP);
        if (!prefs.edit().putString("base", base).putString("server", server).putString("token", encrypted).commit()) throw new Exception("连接信息未能保存，请重试");
    }
    public String base() { return prefs.getString("base", ""); }
    public String server() { return prefs.getString("server", ""); }
    public synchronized String token() {
        try {
            String[] parts = prefs.getString("token", "").split(":");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), "UTF-8");
        } catch (Exception ignored) { return ""; }
    }
    public synchronized void unpair() { prefs.edit().remove("token").commit(); }
}
