package com.watchunlock.app;

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

/**
 * 锁屏 PIN 的落盘加密。
 * 密钥生成在 Android Keystore（不可导出，绑定本机本应用 uid），
 * 磁盘上只保存 iv + 密文的 Base64；旧版的明文 "pin" 键会在首次读取时迁移并删除。
 */
final class SecurePin {

    private static final String STORE = "wu";
    private static final String ALIAS = "wu_pin_v1";
    private static final String KEY_ENC = "pin_enc";
    private static final String KEY_OLD = "pin";
    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    /** 内存缓存：界面每次刷新都会读 PIN，避免反复走 Keystore */
    private static String cache;
    private static boolean cacheValid;

    private SecurePin() {
    }

    static synchronized String load(Context c) {
        if (cacheValid) return cache == null ? "" : cache;
        SharedPreferences sp = sp(c);
        String out = decrypt(sp.getString(KEY_ENC, ""));
        String old = sp.getString(KEY_OLD, "");
        if (old != null && !old.isEmpty()) {
            // 迁移旧版明文，随后立即删除
            out = old;
            if (encryptTo(sp, old)) sp.edit().remove(KEY_OLD).apply();
        }
        cache = out;
        cacheValid = true;
        return out;
    }

    static synchronized void save(Context c, String pin) {
        SharedPreferences sp = sp(c);
        String v = pin == null ? "" : pin.trim();
        if (v.isEmpty()) {
            sp.edit().remove(KEY_ENC).remove(KEY_OLD).apply();
        } else if (encryptTo(sp, v)) {
            sp.edit().remove(KEY_OLD).apply();
        } else {
            // 加密不可用时宁可丢失也不写明文
            sp.edit().remove(KEY_ENC).remove(KEY_OLD).apply();
            v = "";
        }
        cache = v;
        cacheValid = true;
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(STORE, Context.MODE_PRIVATE);
    }

    private static boolean encryptTo(SharedPreferences sp, String plain) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE, key(true));
            byte[] iv = cipher.getIV();
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] all = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, all, 0, iv.length);
            System.arraycopy(ct, 0, all, iv.length, ct.length);
            sp.edit().putString(KEY_ENC, Base64.encodeToString(all, Base64.NO_WRAP)).apply();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String decrypt(String enc) {
        if (enc == null || enc.isEmpty()) return "";
        try {
            byte[] all = Base64.decode(enc, Base64.NO_WRAP);
            if (all.length <= IV_BYTES) return "";
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE, key(false),
                    new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            byte[] pt = cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            // 密钥被清除 / 系统迁移 / 数据被恢复：当作没有设置过 PIN
            return "";
        }
    }

    private static SecretKey key(boolean create) throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(ALIAS)) return (SecretKey) ks.getKey(ALIAS, null);
        if (!create) return null;
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build());
        return kg.generateKey();
    }
}
