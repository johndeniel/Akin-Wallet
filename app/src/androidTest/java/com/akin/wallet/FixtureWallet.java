package com.akin.wallet;

import android.content.SharedPreferences;
import android.app.Activity;
import android.os.Bundle;
import android.view.WindowManager;

import java.io.File;
import java.util.UUID;

/**
 * Test-only Application: real screens use invocation-specific preferences and database paths.
 */
public final class FixtureWallet extends AkinWallet {
    private final String prefix = "v1fixture_" + UUID.randomUUID() + "_";

    @Override
    public void onCreate() {
        super.onCreate();
        // Scope awake-screen benchmarking to test windows, without changing phone settings.
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, Bundle state) {
                activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
            @Override public void onActivityStarted(Activity activity) { }
            @Override public void onActivityResumed(Activity activity) { }
            @Override public void onActivityPaused(Activity activity) { }
            @Override public void onActivityStopped(Activity activity) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
            @Override public void onActivityDestroyed(Activity activity) { }
        });
    }

    @Override
    public SharedPreferences getSharedPreferences(String name, int mode) {
        return super.getSharedPreferences(prefix + name, mode);
    }

    @Override
    public File getDatabasePath(String name) {
        return super.getDatabasePath(prefix + name);
    }

    @Override
    public boolean deleteDatabase(String name) {
        return super.deleteDatabase(prefix + name);
    }

    void eraseFixtures() {
        for (String name : super.databaseList())
            if (name.startsWith(prefix)) super.deleteDatabase(name);
        File directory = new File(getDataDir(), "shared_prefs");
        File[] files = directory.listFiles();
        if (files != null) for (File file : files)
            if (file.getName().startsWith(prefix) && file.getName().endsWith(".xml")) {
                super.deleteSharedPreferences(file.getName().substring(0, file.getName().length() - 4));
            }
    }
}
