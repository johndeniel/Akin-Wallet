package com.akin.wallet.activity;

import android.content.Intent;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.akin.wallet.AkinWallet;
import com.akin.wallet.R;
import com.akin.wallet.db.AppDatabaseHelper;
import com.akin.wallet.db.VaultWarmCache;
import com.akin.wallet.util.Ui;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared vault-screen base. Owns what every screen repeated:
 * system-bar paint (plus re-paint on focus), back-toolbar wiring, session
 * re-lock gating, return-screen messages, one load-generation counter, owned
 * dialogs, and the vault I/O shortcut.
 *
 * <p>Subclasses call {@link #applyChrome()} once after {@code setContentView}.
 * Auth-exempt screens (none currently — {@code LockActivity} stays outside
 * this base precisely to avoid re-locking itself) would override
 * {@link #requiresAuth()}.
 */
public abstract class BaseVaultActivity extends AppCompatActivity {

    private final List<AlertDialog> ownedDialogs = new ArrayList<>();
    private int loadGeneration;

    /**
     * Session re-lock: backing out of verify means "do not enter", so the
     * screen closes instead of sitting unlocked behind it.
     */
    protected final ActivityResultLauncher<Intent> verifyLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK) {
                    finish();
                }
            });

    /** False only for auth-exempt screens. All vault screens require unlock. */
    protected boolean requiresAuth() {
        return true;
    }

    protected AkinWallet app() {
        return (AkinWallet) getApplication();
    }

    protected VaultWarmCache cache() {
        return app().getVaultCache();
    }

    protected AppDatabaseHelper db() {
        return app().getDbHelper();
    }

    protected void vaultIo(Runnable task) {
        app().vaultIo(task);
    }

    /** Drops stale loads (rotation / rapid resume) — only the latest binds. */
    protected int nextLoadGeneration() {
        return ++loadGeneration;
    }

    protected boolean isCurrentGeneration(int generation) {
        return generation == loadGeneration;
    }

    /**
     * Paints system bars and wires the back toolbar when the layout has one.
     * Call once after {@code setContentView}. Each form uses its own
     * descriptive toolbar id (bank_card_toolbar, government_id_toolbar,
     * social_account_toolbar, trash_toolbar, settings_toolbar,
     * policy_toolbar); layouts without a toolbar (dashboard, lock) just get
     * the bars.
     */
    protected void applyChrome() {
        Ui.applySystemBars(this);
        MaterialToolbar toolbar = findToolbar();
        if (toolbar != null) {
            toolbar.setNavigationOnClickListener(v -> onNavigateBack());
        }
    }

    /** Resolves whichever per-screen toolbar the current layout owns, or null. */
    private MaterialToolbar findToolbar() {
        int[] toolbarIds = {
                R.id.bank_card_toolbar,
                R.id.government_id_toolbar,
                R.id.social_account_toolbar,
                R.id.trash_toolbar,
                R.id.settings_toolbar,
                R.id.policy_toolbar,
        };
        for (int id : toolbarIds) {
            MaterialToolbar toolbar = findViewById(id);
            if (toolbar != null) {
                return toolbar;
            }
        }
        return null;
    }

    /** Back/close action. Trash overrides to clear selection first. */
    protected void onNavigateBack() {
        finish();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (requiresAuth()) {
            app().requireUnlock(this, verifyLauncher);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        app().showPendingMessage(this);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            Ui.applySystemBars(this);
        }
    }

    @Override
    protected void onDestroy() {
        for (AlertDialog dialog : ownedDialogs) {
            Ui.dismissOwnedDialog(dialog);
        }
        ownedDialogs.clear();
        super.onDestroy();
    }

    /**
     * Tracks a shown dialog so rotation/finish cannot leak its window.
     */
    protected void trackDialog(AlertDialog dialog) {
        if (dialog != null) {
            ownedDialogs.add(dialog);
        }
    }

    /**
     * "Delete <thing>?" confirm, tracked for auto-dismiss. Runs {@code onDelete} on Delete.
     */
    protected void confirmDeleteToTrash(String title, String message, Runnable onDelete) {
        trackDialog(Ui.confirmDelete(this, title, message, onDelete));
    }

    protected void showMessage(@StringRes int messageRes) {
        View content = findViewById(android.R.id.content);
        if (content != null && !isFinishing()) {
            Snackbar.make(content, messageRes, Snackbar.LENGTH_SHORT).show();
        }
    }

    protected void showMessage(String message) {
        View content = findViewById(android.R.id.content);
        if (content != null && !isFinishing()) {
            Snackbar.make(content, message, Snackbar.LENGTH_SHORT).show();
        }
    }

    protected void showError(@StringRes int messageRes) {
        showMessage(messageRes);
    }
}
