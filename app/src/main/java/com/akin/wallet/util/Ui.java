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
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.RecyclerView;
import com.akin.wallet.R;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * Shared UI helpers for form activities.
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

    /** Edge-to-edge bars; fixed screens get cutout padding via applyAdaptiveInsets. */
    @SuppressWarnings("deprecation")
    public static void applySystemBars(Activity activity) {
        // Production hardening: block Recents thumbnails + screenshots for
        // every vault screen. Single choke point — all activities call this.
        activity.getWindow().setFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE);
        // Edge-to-edge: draw behind bars, then pad content via insets listener.
        WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false);
        activity.getWindow().setStatusBarColor(Color.TRANSPARENT);
        activity.getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            activity.getWindow().getAttributes().layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        WindowCompat.getInsetsController(
                        activity.getWindow(), activity.getWindow().getDecorView())
                .show(WindowInsetsCompat.Type.navigationBars());
        applyAdaptiveInsets(activity);
    }

    /**
     * Adds live system-bar + cutout insets to android.R.id.content, preserving
     * the layout's own dimens padding. Fixed screens keep their gutter tokens;
     * insets shrink the viewport the compact budget already absorbs.
     */
    private static void applyAdaptiveInsets(Activity activity) {
        android.view.View content = activity.findViewById(android.R.id.content);
        if (content == null) {
            return;
        }
        if (!(content.getTag(R.id.tag_window_insets_initial) instanceof int[])) {
            content.setTag(R.id.tag_window_insets_initial,
                    new int[]{content.getPaddingLeft(), content.getPaddingTop(),
                            content.getPaddingRight(), content.getPaddingBottom()});
        }
        final int[] initial = (int[]) content.getTag(R.id.tag_window_insets_initial);
        ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(initial[0] + bars.left, initial[1] + bars.top,
                    initial[2] + bars.right, initial[3] + bars.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(content);
    }

    /** Dismisses an owned dialog without leaking windows across rotation. */
    public static void dismissOwnedDialog(AlertDialog dialog) {
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
    }

    // Dialogs: owners dismiss in onDestroy to survive rotation.

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

    /** Shared gap decoration for carousel lists. */
    public static RecyclerView.ItemDecoration carouselGapDecoration(Context context) {
        final int gapPx = dp(context, 12);
        return new RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(@NonNull android.graphics.Rect outRect, @NonNull View child,
                                       @NonNull RecyclerView parent,
                                       @NonNull RecyclerView.State state) {
                int position = parent.getChildAdapterPosition(child);
                if (position != RecyclerView.NO_POSITION
                        && position < state.getItemCount() - 1) {
                    outRect.right = gapPx;
                }
            }
        };
    }

    /** Shared carousel dots: 8dp dots, alpha-selected. */
    public static void buildDots(Context context, LinearLayout container, int count, int selected) {
        container.removeAllViews();
        int size = dp(context, 8);
        int margin = dp(context, 4);
        for (int i = 0; i < count; i++) {
            View dot = new View(context);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
            params.setMargins(margin, 0, margin, 0);
            dot.setLayoutParams(params);
            dot.setBackgroundResource(R.drawable.bg_dot);
            dot.setAlpha(i == selected ? 1f : 0.3f);
            container.addView(dot);
        }
    }

    public static void updateDots(LinearLayout container, int selected) {
        for (int i = 0; i < container.getChildCount(); i++) {
            container.getChildAt(i).setAlpha(i == selected ? 1f : 0.3f);
        }
    }

    /** Canonical carousel page ratio: viewport * 0.68 with peek. */
    public static final float CAROUSEL_PAGE_RATIO = 0.68f;

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
