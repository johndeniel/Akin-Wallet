package com.akin.wallet.security;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Base64;

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
 * {@link #LOCKOUT_DURATION_MS} cooldown measured on the monotonic clock, so
 * changing the wall clock cannot shorten it. The in-memory session flag dies
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

    /** PBKDF2 work factor. Stored alongside the hash for future agility. */
    private static final int HASH_ITERATIONS = 120_000;
    private static final int HASH_BITS = 256;

    /** PIN length, attempt policy and background re-lock grace. */
    public static final int PIN_LENGTH = 4;
    public static final int MAX_ATTEMPTS = 5;
    public static final long LOCKOUT_DURATION_MS = 30_000L;
    public static final long SESSION_GRACE_MS = 60_000L;
    /** Escalation cap: 30s, 60s, 120s … capped at 30 min. */
    public static final long LOCKOUT_MAX_MS = 30 * 60_000L;
    /** Aggressive posture: after this many lockout cycles the vault is wiped. */
    public static final int WIPE_AFTER_LOCKOUT_CYCLES = 10;

    private static volatile boolean sessionUnlocked = false;

    private AppLockManager() {
    }

    private static SharedPreferences prefs(@NonNull Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** True once the user has completed the first-run PIN setup. */
    public static boolean isPinSet(@NonNull Context context) {
        SharedPreferences preferences = prefs(context);
        return !preferences.getString(KEY_PIN_HASH, "").isEmpty()
                && !preferences.getString(KEY_PIN_SALT, "").isEmpty();
    }

    /** Persists a new PIN (hash + fresh salt) and clears attempt counters. */
    public static void setPin(@NonNull Context context, @NonNull String pin) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        String saltB64 = Base64.encodeToString(salt, Base64.NO_WRAP);
        SharedPreferences preferences = prefs(context);
        writePinHash(preferences, saltB64, pin);
        preferences.edit()
                .remove(KEY_FAILED_ATTEMPTS)
                .remove(KEY_LOCKOUT_UNTIL)
                .remove(KEY_LOCKOUT_CYCLES)
                .apply();
    }

    /** Stores the salted PBKDF2 hash. */
    private static void writePinHash(SharedPreferences preferences,
                                     String saltB64, @NonNull String pin) {
        byte[] salt = Base64.decode(saltB64, Base64.NO_WRAP);
        preferences.edit()
                .putString(KEY_PIN_SALT, saltB64)
                .putString(KEY_PIN_HASH, derivePinHash(pin, salt, HASH_ITERATIONS))
                .putInt(KEY_PIN_ITER, HASH_ITERATIONS)
                .apply();
    }

    /** Hash comparison — the raw PIN never touches disk. */
    public static boolean verifyPin(@NonNull Context context, @NonNull String pin) {
        SharedPreferences preferences = prefs(context);
        String saltB64 = preferences.getString(KEY_PIN_SALT, "");
        String expected = preferences.getString(KEY_PIN_HASH, "");
        if (saltB64.isEmpty() || expected.isEmpty()) {
            return false;
        }
        byte[] salt = Base64.decode(saltB64, Base64.NO_WRAP);
        int iterations = preferences.getInt(KEY_PIN_ITER, HASH_ITERATIONS);
        return slowEquals(derivePinHash(pin, salt, iterations), expected);
    }

    private static boolean slowEquals(@NonNull String a, @NonNull String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }

    private static String derivePinHash(@NonNull String pin, @NonNull byte[] salt, int iterations) {
        try {
            PBEKeySpec keySpec = new PBEKeySpec(
                    pin.toCharArray(), salt, iterations, HASH_BITS);
            try {
                SecretKeyFactory keyFactory =
                        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
                byte[] digest = keyFactory.generateSecret(keySpec).getEncoded();
                return Base64.encodeToString(digest, Base64.NO_WRAP);
            } finally {
                keySpec.clearPassword();
            }
        } catch (Exception e) {
            throw new IllegalStateException("PIN hash unavailable", e);
        }
    }

    // ------------------------------------------------------------------
    // Biometrics (opt-in from Settings, capability-gated)
    // ------------------------------------------------------------------

    /** Device can actually do strong biometric auth (any API level). */
    public static boolean isBiometricAvailable(@NonNull Context context) {
        return BiometricManager.from(context.getApplicationContext())
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                == BiometricManager.BIOMETRIC_SUCCESS;
    }

    /** User toggle from Settings. Never true unless explicitly enabled. */
    public static boolean isBiometricEnabled(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_BIOMETRIC_ENABLED, false);
    }

    public static void setBiometricEnabled(@NonNull Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_BIOMETRIC_ENABLED, enabled).apply();
    }

    /** Usable on the lock screen only when opted in AND supported. */
    public static boolean canUseBiometric(@NonNull Context context) {
        return isBiometricEnabled(context) && isBiometricAvailable(context);
    }

    // ------------------------------------------------------------------
    // Brute-force protection (monotonic clock: wall-clock immune)
    // ------------------------------------------------------------------

    /** Cooldown end as monotonic millis. 0 means no lockout. */
    private static long lockoutUntil(@NonNull Context context) {
        return prefs(context).getLong(KEY_LOCKOUT_UNTIL, 0);
    }

    public static boolean isLockedOut(@NonNull Context context) {
        return SystemClock.elapsedRealtime() < lockoutUntil(context);
    }

    /** Seconds left on the cooldown, 0 when not locked out. */
    public static long lockoutRemainingSeconds(@NonNull Context context) {
        long remainingMs = lockoutUntil(context) - SystemClock.elapsedRealtime();
        return remainingMs > 0 ? (remainingMs + 999) / 1000 : 0;
    }

    /**
     * Records a wrong PIN. Returns attempts left before lockout, or -1 when
     * this failure just triggered the cooldown. Persisted in the background;
     * attempts are human-paced with a slow hash between them, so the counter
     * cannot meaningfully race. Lockout escalates exponentially per cycle.
     */
    public static int recordFailure(@NonNull Context context) {
        SharedPreferences preferences = prefs(context);
        int attempts = preferences.getInt(KEY_FAILED_ATTEMPTS, 0) + 1;
        if (attempts >= MAX_ATTEMPTS) {
            int cycles = preferences.getInt(KEY_LOCKOUT_CYCLES, 0);
            long duration = Math.min(
                    LOCKOUT_DURATION_MS << Math.min(cycles, 6), LOCKOUT_MAX_MS);
            preferences.edit()
                    .remove(KEY_FAILED_ATTEMPTS)
                    .putLong(KEY_LOCKOUT_UNTIL,
                            SystemClock.elapsedRealtime() + duration)
                    .putInt(KEY_LOCKOUT_CYCLES, cycles + 1)
                    .apply();
            return -1;
        }
        preferences.edit().putInt(KEY_FAILED_ATTEMPTS, attempts).apply();
        return MAX_ATTEMPTS - attempts;
    }

    /** Completed lockout cycles (for exponential backoff + wipe policy). */
    public static int lockoutCycles(@NonNull Context context) {
        return prefs(context).getInt(KEY_LOCKOUT_CYCLES, 0);
    }

    /** True when the aggressive wipe threshold has been reached. */
    public static boolean shouldWipe(@NonNull Context context) {
        return lockoutCycles(context) >= WIPE_AFTER_LOCKOUT_CYCLES;
    }

    public static void resetFailures(@NonNull Context context) {
        prefs(context).edit()
                .remove(KEY_FAILED_ATTEMPTS)
                .remove(KEY_LOCKOUT_UNTIL)
                .remove(KEY_LOCKOUT_CYCLES)
                .apply();
    }

    // ------------------------------------------------------------------
    // Session (in-memory: cold start always re-locks)
    // ------------------------------------------------------------------

    public static boolean isSessionUnlocked() {
        return sessionUnlocked;
    }

    public static void setSessionUnlocked(boolean unlocked) {
        sessionUnlocked = unlocked;
    }
}
