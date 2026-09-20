package com.akin.wallet.security;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import androidx.annotation.NonNull;

import java.io.ByteArrayOutputStream;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Vault database key: a random 256-bit key, never derived from the 4-digit
 * app PIN (10k guesses would fall instantly to offline brute force). The key
 * is sealed with an AES-GCM key that lives in the Android Keystore and only
 * the sealed blob touches disk (private SharedPreferences). The unwrapped
 * key lives in process memory only and dies with the process.
 *
 * <p>Threat model, stated plainly: this encrypts the database file at rest,
 * so raw file reads (lost-device flash dumps, backup snooping, adb pulls)
 * yield ciphertext. It does not stop code running as the app on a rooted
 * device, which can ask the Keystore to unwrap just like we do.
 */
public final class DbKeyManager {

    private static final String PREFS = "akin_vault_key";
    private static final String KEY_WRAPPED = "wrapped_db_key";
    private static final String KEYSTORE_ALIAS = "akin_vault_db_key";
    private static final int RAW_KEY_BYTES = 32;
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    /** Unwrapped hex passphrase, process memory only. Never persisted. */
    private static volatile char[] cachedPassphrase;

    private DbKeyManager() {
    }

    /**
     * Hex passphrase for SQLCipher. Creates and seals a fresh random key on
     * first run; unwraps via Keystore afterward. Never returns null; throws
     * instead of risking data loss — callers must not catch-and-recreate,
     * which would orphan the database.
     */
    @NonNull
    public static synchronized char[] getPassphrase(@NonNull Context context) {
        if (cachedPassphrase != null) {
            // Defensive copy: callers must not mutate the cached key.
            return cachedPassphrase.clone();
        }
        Context appContext = context.getApplicationContext();
        SharedPreferences preferences =
                appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String wrapped = preferences.getString(KEY_WRAPPED, "");
        byte[] keyBytes;
        if (wrapped.isEmpty()) {
            keyBytes = new byte[RAW_KEY_BYTES];
            new SecureRandom().nextBytes(keyBytes);
            // First seal must survive a crash: commit synchronously. A lost
            // write here orphans the DB created with this key.
            boolean stored = preferences.edit()
                    .putString(KEY_WRAPPED, seal(keyBytes)).commit();
            if (!stored) {
                Arrays.fill(keyBytes, (byte) 0);
                throw new IllegalStateException("Vault key seal not persisted");
            }
        } else {
            try {
                keyBytes = unseal(wrapped);
            } catch (IllegalStateException e) {
                if (isKeyInvalidated(e)) {
                    throw new KeyInvalidatedException(
                            "Device key invalidated — vault must be reset", e);
                }
                throw e;
            }
        }
        cachedPassphrase = toHex(keyBytes).toCharArray();
        // Best effort: drop the raw bytes as soon as the hex copy exists.
        Arrays.fill(keyBytes, (byte) 0);
        return cachedPassphrase.clone();
    }

    private static String seal(byte[] keyBytes) {
        try {
            SecretKey key = keystoreKey();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] iv = cipher.getIV();
            byte[] cipherText = cipher.doFinal(keyBytes);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(iv, 0, iv.length);
            out.write(cipherText, 0, cipherText.length);
            return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            throw new IllegalStateException("Vault key seal failed", e);
        }
    }

    private static byte[] unseal(String wrapped) {
        try {
            byte[] blob = Base64.decode(wrapped, Base64.NO_WRAP);
            if (blob.length <= GCM_IV_BYTES) {
                throw new IllegalStateException("Vault key blob truncated");
            }
            byte[] iv = Arrays.copyOfRange(blob, 0, GCM_IV_BYTES);
            byte[] cipherText = Arrays.copyOfRange(blob, GCM_IV_BYTES, blob.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keystoreKey(),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            return cipher.doFinal(cipherText);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            // Wrong key, tampered blob, or a Keystore that forgot the alias:
            // surface loudly. Recreating here would orphan the real database.
            throw new IllegalStateException("Vault key unreadable", e);
        }
    }

    private static SecretKey keystoreKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (!keyStore.containsAlias(KEYSTORE_ALIAS)) {
            KeyGenerator keyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            keyGenerator.init(new KeyGenParameterSpec.Builder(KEYSTORE_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build());
            return keyGenerator.generateKey();
        }
        return (SecretKey) keyStore.getKey(KEYSTORE_ALIAS, null);
    }

    private static String toHex(byte[] keyBytes) {
        StringBuilder hex = new StringBuilder(keyBytes.length * 2);
        for (byte keyByte : keyBytes) {
            hex.append(Character.forDigit((keyByte >> 4) & 0xF, 16));
            hex.append(Character.forDigit(keyByte & 0xF, 16));
        }
        return hex.toString();
    }

    /** True when the Keystore alias was invalidated (reset, enrollment change). */
    private static boolean isKeyInvalidated(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String name = t.getClass().getName();
            if (name.contains("KeyPermanentlyInvalidatedException")
                    || name.contains("UnrecoverableKeyException")) {
                return true;
            }
            String msg = t.getMessage();
            if (msg != null && (msg.contains("Key permanently invalidated")
                    || msg.contains("invalidated"))) {
                return true;
            }
        }
        return false;
    }

    /** Thrown when the Keystore alias is gone: caller must wipe + re-setup. */
    public static final class KeyInvalidatedException extends IllegalStateException {
        KeyInvalidatedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Aggressive-wipe reset for an invalidated key: deletes the sealed blob,
     * the SQLCipher file, journals and prefs so the next launch creates a
     * fresh vault instead of a permanently unreadable one. Call only after
     * explicit user consent — this destroys vault contents by design.
     */
    public static void wipeVault(@NonNull Context context) {
        Context appContext = context.getApplicationContext();
        cachedPassphrase = null;
        try {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
                keyStore.deleteEntry(KEYSTORE_ALIAS);
            }
        } catch (Exception ignored) {
        }
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().clear().commit();
        for (String name : new String[]{
                "akin_wallet.db", "akin_wallet.db-journal", "akin_wallet.db-wal"}) {
            java.io.File f = appContext.getDatabasePath("akin_wallet.db");
            java.io.File target = name.equals("akin_wallet.db")
                    ? f : new java.io.File(f.getParent(), name);
            //noinspection ResultOfMethodCallIgnored
            target.delete();
        }
    }
}
