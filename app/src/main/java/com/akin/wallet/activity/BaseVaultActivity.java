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
 * Shared base for vault screens: chrome, re-lock gate, messages,
 * load generation guard, owned dialogs, vault I/O.
 */
public abstract class BaseVaultActivity extends AppCompatActivity {

    private final List<AlertDialog> ownedDialogs = new ArrayList<>();
    private int loadGeneration;

    /** Backing out of verify closes the screen instead of leaving it unlocked. */
    protected final ActivityResultLauncher<Intent> verifyLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK) {
                    finish();
                }
            });

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

    protected boolean isAlive() {
        return !isFinishing() && !isDestroyed();
    }

    protected void runIfAlive(int generation, Runnable action) {
        runOnUiThread(() -> {
            if (isCurrentGeneration(generation) && isAlive()) {
                action.run();
            }
        });
    }

    protected void runIfAlive(Runnable action) {
        runOnUiThread(() -> {
            if (isAlive()) {
                action.run();
            }
        });
    }

    /** Paints system bars and wires the back toolbar when the layout has one. */
    protected void applyChrome() {
        Ui.applySystemBars(this);
        MaterialToolbar toolbar = findToolbar();
        if (toolbar != null) {
            toolbar.setNavigationContentDescription(getString(R.string.cd_back));
            toolbar.setNavigationOnClickListener(v -> onNavigateBack());
        }
    }

    /** Wires the back toolbar owned by the current layout, if it has one. */
    private MaterialToolbar findToolbar() {
        return findViewById(R.id.toolbar);
    }

    /** Back action. Trash overrides to clear selection first. */
    protected void onNavigateBack() {
        finish();
    }

    @Override
    protected void onStart() {
        super.onStart();
        app().requireUnlock(this, verifyLauncher);
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

    /** Tracks a shown dialog so rotation/finish cannot leak its window. */
    protected void trackDialog(AlertDialog dialog) {
        if (dialog != null) {
            ownedDialogs.add(dialog);
        }
    }

    protected AlertDialog confirmDeleteToTrash(String title, String message, Runnable onDelete) {
        AlertDialog dialog = Ui.confirmDelete(this, title, message, onDelete);
        trackDialog(dialog);
        return dialog;
    }

    protected void showMessage(@StringRes int messageRes, Object... args) {
        showSnackbar(getString(messageRes, args));
    }

    protected void showMessage(String message) {
        showSnackbar(message);
    }

    private void showSnackbar(CharSequence message) {
        View content = findViewById(android.R.id.content);
        if (content != null && isAlive()) {
            Snackbar.make(content, message, Snackbar.LENGTH_SHORT).show();
        }
    }
}
