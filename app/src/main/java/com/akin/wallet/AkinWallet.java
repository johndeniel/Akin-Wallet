package com.akin.wallet;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.AppCompatDelegate;

import com.akin.wallet.activity.LockActivity;
import com.akin.wallet.db.AppDatabaseHelper;
import com.akin.wallet.db.VaultStore;
import com.akin.wallet.security.VaultSession;
import com.google.android.material.snackbar.Snackbar;

/**
 * Process authentication and I/O owner. Never retains user rows.
 */
public class AkinWallet extends Application {
    private final VaultSession session = new VaultSession();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable expireSession = this::lockAndClear;
    private VaultStore store;
    private com.akin.wallet.util.PlatformIconLoader platformIcons;
    private int startedActivities;
    private int pendingMessage;
    private final java.util.List<Runnable> lockListeners = new java.util.ArrayList<>();

    public void addLockListener(Runnable listener) {
        lockListeners.add(listener);
    }

    public void removeLockListener(Runnable listener) {
        lockListeners.remove(listener);
    }

    public com.akin.wallet.util.PlatformIconLoader platformIcons() {
        if (platformIcons == null)
            platformIcons = new com.akin.wallet.util.PlatformIconLoader(this);
        return platformIcons;
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (platformIcons != null) platformIcons.trim();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (BuildConfig.DEBUG) {
            android.os.StrictMode.setThreadPolicy(new android.os.StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads().detectDiskWrites().detectNetwork().detectCustomSlowCalls().penaltyLog().build());
            android.os.StrictMode.setVmPolicy(new android.os.StrictMode.VmPolicy.Builder()
                    .detectLeakedSqlLiteObjects().detectLeakedClosableObjects().penaltyLog().build());
        }
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        store = new VaultStore(this, session);
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityStarted(Activity a) {
                if (startedActivities++ == 0) {
                    handler.removeCallbacks(expireSession);
                    if (session.foreground(SystemClock.elapsedRealtime())) clearLockedState();
                }
            }

            @Override
            public void onActivityStopped(Activity a) {
                if (--startedActivities <= 0) {
                    startedActivities = 0;
                    session.background(SystemClock.elapsedRealtime());
                    handler.removeCallbacks(expireSession);
                    handler.postDelayed(expireSession, VaultSession.GRACE_MS);
                }
            }

            @Override
            public void onActivityCreated(Activity a, Bundle b) {
            }

            @Override
            public void onActivityResumed(Activity a) {
            }

            @Override
            public void onActivityPaused(Activity a) {
            }


            @Override
            public void onActivitySaveInstanceState(Activity a, Bundle b) {
            }

            @Override
            public void onActivityDestroyed(Activity a) {
            }
        });
    }

    public VaultSession getSession() {
        return session;
    }

    public VaultStore getVaultStore() {
        return store;
    }

    public AppDatabaseHelper getDbHelper() {
        return store.helper();
    }

    public static String versionName() {
        return BuildConfig.VERSION_NAME;
    }

    public void onUnlocked() {
        session.unlock();
        handler.removeCallbacks(expireSession);
    }

    public void lockAndClear() {
        session.lock();
        clearLockedState();
    }

    private void clearLockedState() {
        store.closeOnLock();
        for (Runnable listener : new java.util.ArrayList<>(lockListeners)) listener.run();
    }

    public boolean shouldReLock() {
        return session.shouldLock(SystemClock.elapsedRealtime());
    }

    public void requireUnlock(Activity host, ActivityResultLauncher<Intent> launcher) {
        if (shouldReLock()) {
            lockAndClear();
            launcher.launch(new Intent(host, LockActivity.class).putExtra(LockActivity.EXTRA_MODE, LockActivity.MODE_VERIFY));
        }
    }

    public void notifyOnReturn(int message) {
        pendingMessage = message;
    }

    public void showPendingMessage(Activity activity) {
        if (shouldReLock() || activity.isFinishing() || activity.isDestroyed()) return;
        int message = pendingMessage;
        pendingMessage = 0;
        View content = activity.findViewById(android.R.id.content);
        if (message != 0 && content != null)
            Snackbar.make(content, message, Snackbar.LENGTH_SHORT).show();
    }
}
