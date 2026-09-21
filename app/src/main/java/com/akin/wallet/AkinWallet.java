package com.akin.wallet;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatDelegate;

import com.akin.wallet.activity.LockActivity;
import com.akin.wallet.db.AppDatabaseHelper;
import com.akin.wallet.db.VaultWarmCache;
import com.akin.wallet.security.AppLockManager;
import com.google.android.material.snackbar.Snackbar;


/**
 * Process owner for vault-wide state. Locks the whole app to the dark theme
 * the vault UI is built for, owns the single {@link VaultWarmCache} (and its
 * I/O funnel), the auth boundary (pre-auth warm vs post-auth preload), the
 * background re-lock clock, and the return-screen message slot.
 *
 * <p>What lives here and why:
 * <ul>
 *   <li>Theme pin + vault warm-up: process-global, must run once per process.</li>
 *   <li>Cache / DB / executor accessors: every screen shared one connection;
 *       holding it here removes ~25 {@code VaultWarmCache.get(this)} lookups
 *       and 5 per-activity {@code dbHelper} fields.</li>
 *   <li>Session clock ({@code lastBackgroundedAt} + grace): previously tracked
 *       in {@code DashboardActivity} with wall-clock time and only for one
 *       screen. Monotonic clock here covers every vault screen uniformly.</li>
 *   <li>Return-screen message: previously a static in {@code Ui}; instance
 *       state dies with the process, which is the correct semantics.</li>
 * </ul>
 *
 * <p>What never lives here: row data (stays in {@link VaultWarmCache},
 * cleared on lock), PIN/biometric handling (stays in {@code LockActivity} /
 * {@code AppLockManager}), views/dialogs/activities (would leak windows).
 */
public class AkinWallet extends Application {

    private VaultWarmCache vaultCache;

    /** Stashed return-screen message (0 = none). Instance state: dies with the process. */
    private int pendingMessage;

    /**
     * Last time the whole app went to background, monotonic ms. 0 = in
     * foreground or never backgrounded this process. Written from lifecycle
     * callbacks, cleared on foreground + successful unlock, read by
     * {@link #shouldReLock()}.
     */
    private long lastBackgroundedAt;
    private int startedActivities;

    @Override
    public void onCreate() {
        super.onCreate();
        if (BuildConfig.DEBUG) {
            android.os.StrictMode.setThreadPolicy(new android.os.StrictMode.ThreadPolicy.Builder()
                    .detectCustomSlowCalls()
                    .penaltyLog()
                    .build());
            android.os.StrictMode.setVmPolicy(new android.os.StrictMode.VmPolicy.Builder()
                    .detectLeakedSqlLiteObjects()
                    .detectLeakedClosableObjects()
                    .penaltyLog()
                    .build());
        }
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        vaultCache = VaultWarmCache.get(this);
        warmPreAuth();
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityStarted(@NonNull Activity activity) {
                // Returning from background: grace restarts from the fresh
                // unlock, not from the stale background timestamp. Without
                // this every post-unlock navigation re-locks (RC1).
                if (startedActivities <= 0) {
                    lastBackgroundedAt = 0;
                }
                startedActivities++;
            }

            @Override
            public void onActivityStopped(@NonNull Activity activity) {
                startedActivities--;
                if (startedActivities <= 0) {
                    startedActivities = 0;
                    lastBackgroundedAt = SystemClock.elapsedRealtime();
                }
            }

            @Override public void onActivityCreated(@NonNull Activity a, @Nullable Bundle b) { }
            @Override public void onActivityResumed(@NonNull Activity a) { }
            @Override public void onActivityPaused(@NonNull Activity a) { }
            @Override public void onActivitySaveInstanceState(@NonNull Activity a, @NonNull Bundle b) { }
            @Override public void onActivityDestroyed(@NonNull Activity a) { }
        });
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // All UI hidden: drop row plaintext if we will re-lock anyway. Inside
        // the grace window the snapshot survives so a quick switch stays fast.
        if (level >= TRIM_MEMORY_UI_HIDDEN && vaultCache != null && shouldReLock()) {
            vaultCache.clearSensitiveOnLock();
        }
    }

    // ------------------------------------------------------------------
    // Vault accessors — single funnel, no per-screen fields
    // ------------------------------------------------------------------

    /** Canonical app-scoped cache. Prefer this over {@code VaultWarmCache.get()}. */
    @NonNull
    public VaultWarmCache getVaultCache() {
        return vaultCache;
    }

    /** Shared DB connection. Never close it per-screen; it lives with the process. */
    @NonNull
    public AppDatabaseHelper getDbHelper() {
        return vaultCache.helper();
    }

    /** Runs vault work on the shared serialized I/O thread. */
    public void vaultIo(@NonNull Runnable task) {
        vaultCache.executeVaultIo(task);
    }

    public static String versionName() {
        return BuildConfig.VERSION_NAME;
    }

    // ------------------------------------------------------------------
    // Auth boundary: pre-auth warm vs post-auth preload vs lock
    // ------------------------------------------------------------------

    /**
     * Pre-auth warm: native library + Keystore unseal + connection open +
     * catalog. Touches no rows; safe while the PIN prompt is visible.
     * Idempotent — overlapping calls collapse inside the cache.
     */
    public void warmPreAuth() {
        vaultCache.warmConnectionAsync();
        vaultCache.warmCatalogAsync();
    }

    /** Post-auth: marks the session unlocked and preloads masters + trash. */
    public void onUnlocked() {
        AppLockManager.setSessionUnlocked(true);
        // Fresh unlock restarts the grace window (RC1): the pre-unlock
        // background timestamp must not re-lock the next screen.
        lastBackgroundedAt = 0;
        vaultCache.preloadPostAuthAsync();
    }

    /** Re-lock: drops the session flag and row plaintext (catalog survives). */
    public void lockAndClear() {
        AppLockManager.setSessionUnlocked(false);
        vaultCache.clearSensitiveOnLock();
    }

    /**
     * Restarts the background-grace window without a full preload (PIN-change
     * return path). Same timestamp reset as {@link #onUnlocked()}.
     */
    public void resetGrace() {
        lastBackgroundedAt = 0;
    }

    /**
     * True when a vault screen must ask for unlock: no PIN yet is handled by
     * callers (first-run setup), otherwise unlocked flag missing or grace
     * expired. Monotonic clock — immune to wall-clock changes.
     */
    public boolean shouldReLock() {
        if (!AppLockManager.isSessionUnlocked()) {
            return true;
        }
        return lastBackgroundedAt > 0
                && SystemClock.elapsedRealtime() - lastBackgroundedAt
                > AppLockManager.SESSION_GRACE_MS;
    }

    /**
     * Enforces unlock from a vault screen's {@code onStart}. Launches the
     * verify screen when needed and returns true (caller does nothing else).
     * First run (no PIN) returns false — setup is owned by {@code LockActivity}.
     */
    public void requireUnlock(@NonNull Activity host,
                              @NonNull ActivityResultLauncher<Intent> verifyLauncher) {
        if (!AppLockManager.isPinSet(host)) {
            return;
        }
        if (!shouldReLock()) {
            return;
        }
        lockAndClear();
        verifyLauncher.launch(new Intent(host, LockActivity.class)
                .putExtra(LockActivity.EXTRA_MODE, LockActivity.MODE_VERIFY));
    }

    // ------------------------------------------------------------------
    // Return-screen messages (form saves finish() before a Snack-bar shows)
    // ------------------------------------------------------------------

    public void notifyOnReturn(@StringRes int messageRes) {
        pendingMessage = messageRes;
    }

    /** Takes the stashed message, if any (0 = none). */
    public int takePendingMessage() {
        int message = pendingMessage;
        pendingMessage = 0;
        return message;
    }

    /** Shows the stashed message on the return target, if any. */
    public void showPendingMessage(@Nullable Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            takePendingMessage();
            return;
        }
        int message = takePendingMessage();
        if (message != 0) {
            View content = activity.findViewById(android.R.id.content);
            if (content != null) {
                Snackbar.make(content, message, Snackbar.LENGTH_SHORT).show();
            }
        }
    }
}
