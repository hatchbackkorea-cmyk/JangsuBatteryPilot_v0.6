package com.seungjae.jangsu280battery;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class ReleaseSecureTokenStore {
    private static final String KEY_ALIAS = "battery_pilot_release_token_key_v1";
    private static final String PREFS = "secure_release_prefs";
    private static final String TOKEN_CT = "token_ct";
    private static final String TOKEN_IV = "token_iv";

    private final SharedPreferences prefs;

    ReleaseSecureTokenStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    void saveToken(String token) throws Exception {
        if (token == null || token.trim().isEmpty()) {
            prefs.edit().remove(TOKEN_CT).remove(TOKEN_IV).apply();
            return;
        }
        SecretKey key = getOrCreateKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
        prefs.edit()
                .putString(TOKEN_CT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString(TOKEN_IV, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .apply();
    }

    String loadToken() {
        try {
            String ct = prefs.getString(TOKEN_CT, null);
            String iv = prefs.getString(TOKEN_IV, null);
            if (ct == null || iv == null) return "";
            SecretKey key = getOrCreateKey();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
            byte[] plain = cipher.doFinal(Base64.decode(ct, Base64.NO_WRAP));
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "";
        }
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        KeyStore.Entry entry = ks.getEntry(KEY_ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }
        KeyGenerator keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        keyGenerator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return keyGenerator.generateKey();
    }
}
