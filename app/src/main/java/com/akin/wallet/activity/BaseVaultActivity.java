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
import com.akin.wallet.db.VaultStore;

import android.os.Bundle;

import androidx.lifecycle.ViewModel;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModelProvider;

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
    private boolean resumed;
    private boolean draftCapturedForLock;
    private DraftState editorState;
    private final Runnable lockListener = this::onVaultLocked;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        editorState = new ViewModelProvider(this).get(DraftState.class);
        editorState.completion.observe(this, event -> {
            if (event != null && event < 0 && isAlive()) {
                editorState.completion.setValue(0);
                enableActions(findViewById(android.R.id.content));
                showMessage(R.string.err_vault_operation);
            } else finishCommittedWrite();
        });
        app().addLockListener(lockListener);
    }

    protected void onVaultLocked() {
        loadGeneration++;
        View content = findViewById(android.R.id.content);
        if (content != null) {
            if (!draftCapturedForLock && editorState.committedMessage == 0) captureDraft();
            draftCapturedForLock = true;
            content.setVisibility(View.INVISIBLE);
            Ui.clearSensitiveInputs(content);
        }
        for (AlertDialog dialog : new ArrayList<>(ownedDialogs)) Ui.dismissOwnedDialog(dialog);
    }

    private final List<Runnable> deferredIo = new ArrayList<>();

    public static final class DraftState extends ViewModel {
        final Bundle state = new Bundle();
        final MutableLiveData<Integer> completion = new MutableLiveData<>();
        volatile int committedMessage;
        volatile boolean isWriting;

        @Override
        protected void onCleared() {
            state.clear();
        }
    }

    protected final Bundle draftState() {
        return editorState.state;
    }

    protected final Bundle restoredDraft() {
        return draftState().isEmpty() ? null : new Bundle(draftState());
    }

    /**
     * Subclasses capture fields into the retained memory-only draft.
     */
    protected void captureDraft() {
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (isAlive() && editorState.committedMessage == 0) captureDraft();
        super.onSaveInstanceState(outState);
    }

    /**
     * Record committed writes independently of the requesting Activity's lifetime.
     */
    protected final void commitWrite(Runnable write, @StringRes int message, VaultStore.Section section) {
        final DraftState completionState = editorState;
        if (completionState.isWriting || completionState.committedMessage != 0) return;
        if (app().shouldReLock()) {
            app().requireUnlock(this, verifyLauncher);
            return;
        }
        completionState.isWriting = true;
        final VaultStore vaultStore = store();
        vaultStore.execute(() -> {
            write.run();
            vaultStore.invalidate(section);
            completionState.committedMessage = message;
            completionState.isWriting = false;
            completionState.completion.postValue(message);
        }, failure -> {
            completionState.isWriting = false;
            completionState.completion.postValue(-1);
        });
    }

    private boolean finishCommittedWrite() {
        int message = editorState.committedMessage;
        if (message == 0 || !isAlive()) return false;
        editorState.state.clear();
        app().notifyOnReturn(message);
        setResult(RESULT_OK);
        finish();
        return true;
    }

    private void flushDeferredIo() {
        List<Runnable> pending = new ArrayList<>(deferredIo);
        deferredIo.clear();
        for (Runnable task : pending) vaultIo(task);
    }

    /**
     * Backing out of verify closes the screen instead of leaving it unlocked.
     */
    protected final ActivityResultLauncher<Intent> verifyLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK) {
                    finish();
                } else {
                    recreate();
                }
            });

    protected AkinWallet app() {
        return (AkinWallet) getApplication();
    }

    protected VaultStore store() {
        return app().getVaultStore();
    }

    protected AppDatabaseHelper db() {
        return app().getDbHelper();
    }

    protected void vaultIo(Runnable task) {
        if (app().shouldReLock()) {
            deferredIo.add(task);
            return;
        }
        long token = app().getSession().generation();
        store().execute(task, failure -> runOnUiThread(() -> {
            if (isAlive() && app().getSession().isCurrent(token)) {
                enableActions(findViewById(android.R.id.content));
                showMessage(R.string.err_vault_operation);
            }
        }));
    }

    /**
     * Drops stale loads (rotation / rapid resume) — only the latest binds.
     */
    protected int nextLoadGeneration() {
        return ++loadGeneration;
    }

    protected boolean isCurrentGeneration(int generation) {
        return generation == loadGeneration;
    }

    protected boolean isAlive() {
        return !isFinishing() && !isDestroyed() && !app().shouldReLock();
    }

    protected void runIfAlive(int generation, Runnable action) {
        long token = store().taskGeneration();
        runOnUiThread(() -> {
            if (app().getSession().isCurrent(token) && isCurrentGeneration(generation) && isAlive()) {
                action.run();
            }
        });
    }

    protected void runIfAlive(Runnable action) {
        long token = store().taskGeneration();
        runOnUiThread(() -> {
            if (app().getSession().isCurrent(token) && isAlive()) {
                action.run();
            }
        });
    }

    /**
     * Paints system bars and wires the back toolbar when the layout has one.
     */
    protected void applyChrome() {
        Ui.applySystemBars(this);
        View primary = findViewById(R.id.form_primary_action);
        if (primary != null) primary.setEnabled(!editorState.isWriting);
        MaterialToolbar toolbar = findToolbar();
        if (toolbar != null) {
            toolbar.setNavigationContentDescription(getString(R.string.cd_back));
            toolbar.setNavigationOnClickListener(v -> onNavigateBack());
        }
    }

    /**
     * Wires the back toolbar owned by the current layout, if it has one.
     */
    private MaterialToolbar findToolbar() {
        return findViewById(R.id.toolbar);
    }

    /**
     * Back action. Trash overrides to clear selection first.
     */
    protected void onNavigateBack() {
        finish();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (app().shouldReLock()) {
            findViewById(android.R.id.content).setVisibility(View.INVISIBLE);
            app().requireUnlock(this, verifyLauncher);
        } else {
            draftCapturedForLock = false;
            findViewById(android.R.id.content).setVisibility(View.VISIBLE);
            if (!finishCommittedWrite()) flushDeferredIo();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
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
    protected void onPause() {
        resumed = false;
        super.onPause();
    }

    protected final boolean isAuthenticatedAndResumed() {
        return resumed && isAlive();
    }

    @Override
    protected void onDestroy() {
        deferredIo.clear();
        app().removeLockListener(lockListener);
        for (AlertDialog dialog : new ArrayList<>(ownedDialogs)) {
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
            ownedDialogs.removeIf(owned -> !owned.isShowing());
            ownedDialogs.add(dialog);
            dialog.setOnDismissListener(ignored -> ownedDialogs.remove(dialog));
            if (dialog.getWindow() != null)
                dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        }
    }

    protected AlertDialog confirmDeleteToTrash(String title, String message, Runnable onDelete) {
        AlertDialog dialog = Ui.confirmDelete(this, title, message, onDelete);
        trackDialog(dialog);
        return dialog;
    }

    private static void enableActions(View view) {
        if (view == null) return;
        if (view.isClickable()) view.setEnabled(true);
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) enableActions(group.getChildAt(i));
        }
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
