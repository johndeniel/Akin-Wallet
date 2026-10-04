package com.akin.wallet.security;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.os.SystemClock;
import android.provider.Settings;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * App-lock vault: 4-digit PIN setup/verification plus opt-in biometric
 * unlock. The PIN itself is never stored — only a salted PBKDF2-HMAC-SHA256
 * hash in private SharedPreferences. Biometrics stay disabled until the
 * user turns them on in Settings (and the device actually supports them).
 *
 * <p>Brute-force protection: {@link #MAX_ATTEMPTS} wrong PINs trigger a
 * {@link #LOCKOUT_DURATION_MS} cooldown measured with elapsed time and boot identity. The in-memory session flag dies
 * with the process, so a cold start always re-locks.
 */
public final class AppLockManager {

    private static final String PREFS = "akin_app_lock";
    private static final String KEY_PIN_SALT = "pin_salt";
    private static final String KEY_PIN_HASH = "pin_hash_v2";
    private static final String KEY_PIN_ITER = "pin_iter";
    private static final String KEY_BIOMETRIC_ENABLED = "biometric_enabled";
    private static final String KEY_FAILED_ATTEMPTS = "failed_attempts";
    private static final String KEY_LOCKOUT_UNTIL = "lockout_until";
    private static final String KEY_LOCKOUT_CYCLES = "lockout_cycles";

    /**
     * PBKDF2 work factor. Stored alongside the hash for future agility.
     */
    private static final int HASH_ITERATIONS = 120_000;
    private static final int HASH_BITS = 256;

    /**
     * PIN length, attempt policy and background re-lock grace.
     */
    public static final int PIN_LENGTH = 4;
    public static final int MAX_ATTEMPTS = 5;
    public static final long LOCKOUT_DURATION_MS = 30_000L;
    /**
     * Escalation cap: 30s, 60s, 120s … capped at 30 min.
     */
    public static final long LOCKOUT_MAX_MS = 30 * 60_000L;

    private static final String KEY_LOCKOUT_BOOT = "lockout_boot";
    private static final String KEY_LOCKOUT_DURATION = "lockout_duration";
    private static volatile boolean persistenceFailed;
    private static final ExecutorService AUTH_EXECUTOR = Executors.newSingleThreadExecutor();

    public static void execute(Runnable operation) {
        AUTH_EXECUTOR.execute(operation);
    }

    public static final class Verification {
        public final boolean verified;
        public final int attemptsLeft;

        Verification(boolean verified, int attemptsLeft) {
            this.verified = verified;
            this.attemptsLeft = attemptsLeft;
        }
    }

    /**
     * Authentication mutations run on AUTH_EXECUTOR; UI reads never wait for PBKDF2.
     */
    public static Verification verifyAndRecord(Context context, String pin) {
        if (persistenceFailed)
            throw new IllegalStateException("Authentication storage unavailable");
        if (isLockedOut(context)) return new Verification(false, -1);
        int left = recordFailure(context); // Reserve durably, including process interruption.
        boolean verified = verifyPin(context, pin);
        if (verified) resetFailures(context);
        return new Verification(verified, verified ? MAX_ATTEMPTS : left);
    }

    private static synchronized void commit(SharedPreferences.Editor edit) {
        if (persistenceFailed || !edit.commit()) {
            persistenceFailed = true;
            throw new IllegalStateException("Authentication state not persisted");
        }
    }

    private AppLockManager() {
    }

    private static SharedPreferences prefs(@NonNull Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * True once the user has completed the first-run PIN setup.
     */
    public static boolean isPinSet(@NonNull Context context) {
        SharedPreferences preferences = prefs(context);
        return preferences.contains(KEY_PIN_HASH) || preferences.contains(KEY_PIN_SALT)
                || context.getDatabasePath("akin_wallet.db").exists();
    }

    /**
     * Atomic verifier and counter persistence; called on the authentication worker.
     */
    public static void setPin(Context context, String pin) {
        if (pin == null || !pin.matches("[0-9]{4}"))
            throw new IllegalArgumentException("Invalid PIN");
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        try {
            commit(prefs(context).edit()
                    .putString(KEY_PIN_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                    .putString(KEY_PIN_HASH, derivePinHash(pin, salt, HASH_ITERATIONS))
                    .putInt(KEY_PIN_ITER, HASH_ITERATIONS)
                    .remove(KEY_FAILED_ATTEMPTS).remove(KEY_LOCKOUT_UNTIL)
                    .remove(KEY_LOCKOUT_CYCLES).remove(KEY_LOCKOUT_BOOT).remove(KEY_LOCKOUT_DURATION));
        } finally {
            java.util.Arrays.fill(salt, (byte) 0);
        }
    }

    /**
     * Hash comparison — the raw PIN never touches disk.
     */
    public static boolean verifyPin(@NonNull Context context, @NonNull String pin) {
        SharedPreferences preferences = prefs(context);
        String saltB64 = preferences.getString(KEY_PIN_SALT, "");
        String expected = preferences.getString(KEY_PIN_HASH, "");
        if (saltB64.isEmpty() || expected.isEmpty()) {
            return false;
        }
        byte[] salt = Base64.decode(saltB64, Base64.NO_WRAP);
        try {
            int iterations = preferences.getInt(KEY_PIN_ITER, HASH_ITERATIONS);
            if (salt.length != 16 || iterations != HASH_ITERATIONS) {
                throw new IllegalStateException("Unsupported PIN verifier");
            }
            return slowEquals(derivePinHash(pin, salt, iterations), expected);
        } finally {
            java.util.Arrays.fill(salt, (byte) 0);
        }
    }

    private static boolean slowEquals(@NonNull String a, @NonNull String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }

    private static String derivePinHash(@NonNull String pin, @NonNull byte[] salt, int iterations) {
        char[] characters = pin.toCharArray();
        try {
            PBEKeySpec keySpec = new PBEKeySpec(
                    characters, salt, iterations, HASH_BITS);
            try {
                SecretKeyFactory keyFactory =
                        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
                byte[] digest = keyFactory.generateSecret(keySpec).getEncoded();
                try {
                    return Base64.encodeToString(digest, Base64.NO_WRAP);
                } finally {
                    java.util.Arrays.fill(digest, (byte) 0);
                }
            } finally {
                keySpec.clearPassword();
            }
        } catch (Exception e) {
            throw new IllegalStateException("PIN hash unavailable", e);
        } finally {
            java.util.Arrays.fill(characters, '\0');
        }
    }

    // ------------------------------------------------------------------
    // Biometrics (opt-in from Settings, capability-gated)
    // ------------------------------------------------------------------

    /**
     * Device can actually do strong biometric auth (any API level).
     */
    public static boolean isBiometricAvailable(@NonNull Context context) {
        return BiometricManager.from(context.getApplicationContext())
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                == BiometricManager.BIOMETRIC_SUCCESS;
    }

    /**
     * User toggle from Settings. Never true unless explicitly enabled.
     */
    public static boolean isBiometricEnabled(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_BIOMETRIC_ENABLED, false);
    }

    public static synchronized void setBiometricEnabled(@NonNull Context context, boolean enabled) {
        commit(prefs(context).edit().putBoolean(KEY_BIOMETRIC_ENABLED, enabled));
    }

    /**
     * Usable on the lock screen only when opted in AND supported.
     */
    public static boolean canUseBiometric(@NonNull Context context) {
        return isBiometricEnabled(context) && isBiometricAvailable(context);
    }

    // ------------------------------------------------------------------
    // Persisted monotonic cooldowns. Reboot restarts only a pending bounded duration.

    private static synchronized long lockoutUntil(Context context) {
        SharedPreferences state = prefs(context);
        long deadline = state.getLong(KEY_LOCKOUT_UNTIL, 0);
        if (deadline == 0) return 0;
        int boot = Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, 0);
        if (state.getInt(KEY_LOCKOUT_BOOT, -1) != boot) {
            long duration = Math.max(0, Math.min(LOCKOUT_MAX_MS, state.getLong(KEY_LOCKOUT_DURATION, LOCKOUT_DURATION_MS)));
            deadline = SystemClock.elapsedRealtime() + duration;
            commit(state.edit().putInt(KEY_LOCKOUT_BOOT, boot).putLong(KEY_LOCKOUT_UNTIL, deadline));
        }
        if (deadline <= SystemClock.elapsedRealtime()) {
            commit(state.edit().remove(KEY_LOCKOUT_UNTIL).remove(KEY_LOCKOUT_BOOT).remove(KEY_LOCKOUT_DURATION));
            return 0;
        }
        return deadline;
    }

    public static boolean isLockedOut(@NonNull Context context) {
        return SystemClock.elapsedRealtime() < lockoutUntil(context);
    }

    /**
     * Seconds left on the cooldown, 0 when not locked out.
     */
    public static long lockoutRemainingSeconds(@NonNull Context context) {
        long remainingMs = lockoutUntil(context) - SystemClock.elapsedRealtime();
        return remainingMs > 0 ? (remainingMs + 999) / 1000 : 0;
    }

    /**
     * Reserves a PIN attempt before hashing. Returns attempts left, or -1 when
     * this failure just triggered the cooldown. Persisted in the background;
     * attempts are human-paced with a slow hash between them, so the counter
     * cannot meaningfully race. Lockout escalates exponentially per cycle.
     */
    private static int recordFailure(Context context) {
        SharedPreferences state = prefs(context);
        int attempts = state.getInt(KEY_FAILED_ATTEMPTS, 0) + 1;
        if (attempts >= MAX_ATTEMPTS) {
            int cycles = Math.max(0, state.getInt(KEY_LOCKOUT_CYCLES, 0));
            long duration = Math.min(LOCKOUT_DURATION_MS << Math.min(cycles, 6), LOCKOUT_MAX_MS);
            int boot = Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, 0);
            commit(state.edit().remove(KEY_FAILED_ATTEMPTS)
                    .putLong(KEY_LOCKOUT_UNTIL, SystemClock.elapsedRealtime() + duration)
                    .putLong(KEY_LOCKOUT_DURATION, duration).putInt(KEY_LOCKOUT_BOOT, boot)
                    .putInt(KEY_LOCKOUT_CYCLES, (int) Math.min(cycles + 1L, 63L)));
            return -1;
        }
        commit(state.edit().putInt(KEY_FAILED_ATTEMPTS, attempts));
        return MAX_ATTEMPTS - attempts;
    }

    public static synchronized void resetFailures(Context context) {
        commit(prefs(context).edit().remove(KEY_FAILED_ATTEMPTS).remove(KEY_LOCKOUT_UNTIL)
                .remove(KEY_LOCKOUT_CYCLES).remove(KEY_LOCKOUT_BOOT).remove(KEY_LOCKOUT_DURATION));
    }
}
