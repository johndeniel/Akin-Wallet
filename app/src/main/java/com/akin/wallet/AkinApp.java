package com.akin.wallet;

import android.app.Application;

import androidx.appcompat.app.AppCompatDelegate;

/**
 * Locks the whole app to the dark theme the vault UI is built for. System
 * light mode otherwise leaks light M3 overlays (dialogs, menus, etc.)
 * over the navy screens, so night mode is pinned regardless of the toggle.
 *
 * <p>Also kicks the vault warm-up: connection open + platform catalog while
 * the lock screen inflates. Row data is never touched here — that waits for
 * post-auth preload in {@code LockActivity}.
 */
public class AkinApp extends Application {

    @Override
    public void onCreate() {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        super.onCreate();
        com.akin.wallet.db.VaultWarmCache cache =
                com.akin.wallet.db.VaultWarmCache.get(this);
        cache.warmConnectionAsync();
        cache.warmCatalogAsync();
    }
}
