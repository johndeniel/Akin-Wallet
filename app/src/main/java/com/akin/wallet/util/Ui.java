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
import androidx.recyclerview.widget.DiffUtil;

import android.view.LayoutInflater;

import com.akin.wallet.R;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * Shared UI helpers for form activities.
 */
@SuppressWarnings("SpellCheckingInspection")
public final class Ui {

    private Ui() {
    }

    /**
     * Density-independent pixels for programmatic layouts and dividers.
     */
    public static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    /**
     * Eye-icon flip for password/PIN/CVV fields, preserving cursor position.
     */
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

    public static void togglePasswordVisibility(EditText input, View action,
                                                int showDescription, int hideDescription) {
        togglePasswordVisibility(input);
        boolean hidden = input.getTransformationMethod() == PasswordTransformationMethod.getInstance();
        action.setContentDescription(action.getContext().getString(hidden ? showDescription : hideDescription));
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
     * Edge-to-edge bars; fixed screens get cutout padding via applyAdaptiveInsets.
     */
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
        protectViewTree(activity.findViewById(android.R.id.content));
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

    /**
     * Dismisses an owned dialog without leaking windows across rotation.
     */
    public static void dismissOwnedDialog(AlertDialog dialog) {
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
    }

    /**
     * Disable OS persistence/disclosure for vault views, including dynamically created inputs.
     */
    public static void protectViewTree(android.view.View view) {
        if (view == null) return;
        if (view.getId() == R.id.form_primary_action || view.getId() == R.id.form_destructive_action) {
            view.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override
                public void onInitializeAccessibilityNodeInfo(View host,
                                                              android.view.accessibility.AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setClassName(android.widget.Button.class.getName());
                    if (host instanceof ViewGroup) {
                        ViewGroup group = (ViewGroup) host;
                        for (int i = 0; i < group.getChildCount(); i++) {
                            if (group.getChildAt(i) instanceof android.widget.TextView) {
                                info.setText(((android.widget.TextView) group.getChildAt(i)).getText());
                                break;
                            }
                        }
                    }
                }
            });
        }
        view.setSaveEnabled(false);
        view.setSaveFromParentEnabled(false);
        view.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            view.setImportantForContentCapture(android.view.View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS);
        }
        if (view instanceof EditText) {
            EditText input = (EditText) view;
            input.setImeOptions(input.getImeOptions() | android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) protectViewTree(group.getChildAt(i));
        }
    }

    public static void clearSensitiveInputs(android.view.View view) {
        if (view instanceof EditText) ((EditText) view).getText().clear();
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++)
                clearSensitiveInputs(group.getChildAt(i));
        }
    }

    private static AlertDialog showSecure(AlertDialog dialog) {
        if (dialog.getWindow() != null)
            dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        dialog.show();
        if (dialog.getWindow() != null) protectViewTree(dialog.getWindow().getDecorView());
        return dialog;
    }

    // Dialogs: owners dismiss in onDestroy to survive rotation.

    /**
     * "Delete <thing>?" confirm; runs {@code onDelete} on Delete.
     */
    public static AlertDialog confirmDelete(Context context, String title, String message,
                                            Runnable onDelete) {
        return showSecure(new MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(R.string.action_delete, (d, which) -> onDelete.run())
                .setNegativeButton(android.R.string.cancel, null)
                .create());
    }

    /**
     * Single-choice option picker shared by the bank and ID dropdowns.
     */
    public interface OnChoice {
        void onChoice(int which);
    }

    public static AlertDialog singleChoice(Context context, String title, String[] options,
                                           int checked, @NonNull OnChoice onChoice) {
        return showSecure(new MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setSingleChoiceItems(options, checked, (d, which) -> {
                    onChoice.onChoice(which);
                    d.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create());
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
     * Shared gap decoration for carousel lists.
     */
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

    /**
     * Shared carousel dots: 8dp dots, alpha-selected.
     */
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

    /**
     * Canonical carousel page ratio: viewport * 0.68 with peek.
     */
    public static final float CAROUSEL_PAGE_RATIO = 0.68f;
    public static final float CARD_ASPECT_RATIO = 1.586f;

    /**
     * Inflate a carousel page sized to the viewport with a peek of the next card.
     */
    public static View inflateCarouselPage(android.view.ViewGroup parent, int layoutRes) {
        View view = LayoutInflater.from(parent.getContext()).inflate(layoutRes, parent, false);
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (parent instanceof com.akin.wallet.ui.PortraitCardCarousel) {
            com.akin.wallet.ui.CardPreviewLayout preview =
                    new com.akin.wallet.ui.CardPreviewLayout(parent.getContext());
            preview.setLayoutParams(lp);
            preview.addView(view, new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            view = preview;
        }
        int parentWidth = parent.getMeasuredWidth();
        if (parentWidth <= 0) {
            parentWidth = parent.getResources().getDisplayMetrics().widthPixels
                    - parent.getPaddingStart() - parent.getPaddingEnd();
        }
        if (lp != null && parentWidth > 0) {
            lp.width = (int) (parentWidth * CAROUSEL_PAGE_RATIO);
            int heightLimit = parent.getLayoutParams().height;
            if (heightLimit > 0) {
                lp.width = Math.min(lp.width, Math.round(heightLimit * CARD_ASPECT_RATIO));
            }
            view.setLayoutParams(lp);
        }
        return view;
    }

    private static final android.view.ViewOutlineProvider CARD_OUTLINE =
            new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    float density = view.getResources().getDisplayMetrics().density;
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                            16 * density);
                }
            };

    /**
     * Shared rounded-clip outline for every card face.
     */
    public static void applyCardOutline(View cardRoot) {
        if (cardRoot.getOutlineProvider() != CARD_OUTLINE) {
            cardRoot.setOutlineProvider(CARD_OUTLINE);
            cardRoot.setClipToOutline(true);
        }
    }

    /**
     * Item identity/content check for DiffUtil.
     */
    public interface ItemSame<T> {
        boolean same(T a, T b);
    }

    /**
     * Diff of two lists with caller-supplied identity rules.
     */
    public static <T> DiffUtil.DiffResult calculateDiff(java.util.List<T> oldList,
                                                        java.util.List<T> newList,
                                                        ItemSame<T> itemsSame,
                                                        ItemSame<T> contentsSame) {
        return DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return oldList.size();
            }

            @Override
            public int getNewListSize() {
                return newList.size();
            }

            @Override
            public boolean areItemsTheSame(int oldPos, int newPos) {
                return itemsSame.same(oldList.get(oldPos), newList.get(newPos));
            }

            @Override
            public boolean areContentsTheSame(int oldPos, int newPos) {
                return contentsSame.same(oldList.get(oldPos), newList.get(newPos));
            }
        });
    }

    /**
     * Copy dropping null rows.
     */
    public static <T> java.util.List<T> nonNullList(java.util.List<T> input) {
        java.util.List<T> out = new java.util.ArrayList<>();
        if (input != null) {
            for (T item : input) {
                if (item != null) {
                    out.add(item);
                }
            }
        }
        return out;
    }

    /**
     * Case-insensitive contains after trim. Null-safe on both sides.
     */
    public static boolean matchesFilter(String value, String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return true;
        }
        if (value == null) {
            return false;
        }
        return value.toLowerCase(java.util.Locale.ROOT).contains(pattern);
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
