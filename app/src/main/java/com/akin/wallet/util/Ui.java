package com.akin.wallet.util;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.akin.wallet.R;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * Stateless UI helpers shared by the form activities. Each replaces 3+
 * near-identical private copies that had already started to drift.
 *
 * <p>Spell checking is off for this file: it names Material components
 * (Snackbar) that the IDE dictionary does not know.
 */
@SuppressWarnings("SpellCheckingInspection")
public final class Ui {

    private Ui() {
    }

    /** Density-independent pixels for programmatic layouts and dividers. */
    public static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    /** Eye-icon flip for password/PIN/CVV fields, preserving cursor position. */
    public static void togglePasswordVisibility(EditText input) {
        int start = input.getSelectionStart();
        int end = input.getSelectionEnd();
        if (input.getTransformationMethod() == PasswordTransformationMethod.getInstance()) {
            input.setTransformationMethod(HideReturnsTransformationMethod.getInstance());
        } else {
            input.setTransformationMethod(PasswordTransformationMethod.getInstance());
        }
        int length = input.getText().length();
        input.setSelection(Math.min(Math.max(start, 0), length),
                Math.min(Math.max(end, 0), length));
    }

    /**
     * Case-insensitive option lookup for select dialogs (card type/network,
     * ID dropdowns). Returns -1 when absent so callers apply their own
     * fallback (first item, stored index, …).
     */
    public static int indexOfIgnoreCase(String[] options, String value) {
        if (options == null || value == null) {
            return -1;
        }
        String needle = value.trim();
        for (int i = 0; i < options.length; i++) {
            if (options[i].equalsIgnoreCase(needle)) {
                return i;
            }
        }
        return -1;
    }

    // -- Shared chrome (one definition, every activity looks identical) ----

    /**
     * System bars stay visible on every screen (no immersive mode): status bar
     * in the theme color, transparent navigation bar kept shown. AndroidX
     * compat, no version branches, safe back to minSdk.
     *
     * <p>Suppressed deprecation: these setters are the only way to paint the
     * bars below API 35; on 35+ edge-to-edge takes over they are harmless
     * no-ops.
     */
    @SuppressWarnings("deprecation")
    public static void applySystemBars(Activity activity) {
        activity.getWindow().setStatusBarColor(
                ContextCompat.getColor(activity, R.color.dashboard_bg_start));
        activity.getWindow().setNavigationBarColor(Color.TRANSPARENT);
        WindowCompat.getInsetsController(
                        activity.getWindow(), activity.getWindow().getDecorView())
                .show(WindowInsetsCompat.Type.navigationBars());
    }

    /** Back-chevron toolbar wiring shared by every form screen. */
    public static void setupBackToolbar(Activity activity, int toolbarId) {
        MaterialToolbar toolbar = activity.findViewById(toolbarId);
        if (toolbar != null) {
            toolbar.setNavigationOnClickListener(v -> activity.finish());
        }
    }

    /** Dismisses an owned dialog without leaking windows across rotation. */
    public static void dismissOwnedDialog(AlertDialog dialog) {
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
    }

    // -- M3 dialogs (one definition, every confirm looks identical) --------
    // Builders return the shown dialog so owners can dismiss it in
    // onDestroy — otherwise a rotation with a dialog up leaks the window.

    /** "Delete <thing>?" confirm; runs {@code onDelete} on Delete. */
    public static AlertDialog confirmDelete(Context context, String title, String message,
                                            Runnable onDelete) {
        return new MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("Delete", (d, which) -> onDelete.run())
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Single-choice option picker shared by the bank and ID dropdowns. */
    public interface OnChoice {
        void onChoice(int which);
    }

    public static AlertDialog singleChoice(Context context, String title, String[] options,
                                           int checked, @NonNull OnChoice onChoice) {
        return new MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setSingleChoiceItems(options, checked, (d, which) -> {
                    onChoice.onChoice(which);
                    d.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Add mode keeps a single full-width Save button: drops the trailing
     * margin left for the (GONE) delete view.
     */
    public static void makeSaveButtonFullWidth(View saveButton) {
        ViewGroup.LayoutParams params = saveButton.getLayoutParams();
        if (params instanceof LinearLayout.LayoutParams) {
            ((LinearLayout.LayoutParams) params).setMarginEnd(0);
            saveButton.setLayoutParams(params);
        }
    }

    /**
     * TextWatcher with empty defaults so call sites override only the phase
     * they need instead of repeating three empty overrides per listener.
     */
    public abstract static class SimpleTextWatcher implements TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence text, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence text, int start, int before, int count) {
        }

        @Override
        public void afterTextChanged(Editable text) {
        }
    }
}
