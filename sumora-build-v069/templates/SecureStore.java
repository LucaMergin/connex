package si.deliva.app;

import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class SecureStore {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "sumora.session.aes.v1";
    private static final String PREFIX = "secure.v1.";

    private final SharedPreferences prefs;

    SecureStore(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    synchronized boolean putString(String key, String value) {
        if (key == null || key.isEmpty()) return false;
        if (value == null || value.isEmpty()) {
            remove(key);
            return true;
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            byte[] encrypted = cipher.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return prefs.edit()
                    .putString(PREFIX + key + ".iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                    .putString(PREFIX + key + ".data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
                    .commit();
        } catch (Exception ignored) {
            return false;
        }
    }

    synchronized String getString(String key) {
        if (key == null || key.isEmpty()) return "";
        String ivText = prefs.getString(PREFIX + key + ".iv", "");
        String dataText = prefs.getString(PREFIX + key + ".data", "");
        if (ivText.isEmpty() || dataText.isEmpty()) return "";
        try {
            byte[] iv = Base64.decode(ivText, Base64.NO_WRAP);
            byte[] encrypted = Base64.decode(dataText, Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
            byte[] clear = cipher.doFinal(encrypted);
            return new String(clear, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            remove(key);
            return "";
        }
    }

    synchronized void remove(String key) {
        if (key == null || key.isEmpty()) return;
        prefs.edit()
                .remove(PREFIX + key + ".iv")
                .remove(PREFIX + key + ".data")
                .commit();
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        java.security.Key existing = store.getKey(ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;

        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}
