package com.akin.wallet.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.widget.Toolbar;

import com.akin.wallet.R;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Fixed Settings prioritizes controls and text over optional inter-section spacing.
 */
public final class SettingsContentLayout extends LinearLayout {
    private final Map<View, int[]> margins = new IdentityHashMap<>();
    private int originalPaddingTop;
    private int originalPaddingBottom;

    public SettingsContentLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        originalPaddingTop = getPaddingTop();
        originalPaddingBottom = getPaddingBottom();
        if (getResources().getConfiguration().screenHeightDp < 640) moveVersionIntoToolbar();
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            LayoutParams params = (LayoutParams) child.getLayoutParams();
            margins.put(child, new int[]{params.topMargin, params.bottomMargin});
        }
    }

    private void moveVersionIntoToolbar() {
        TextView version = findViewById(R.id.settings_version_label);
        Toolbar toolbar = findViewById(R.id.toolbar);
        if (version != null && toolbar != null && version.getParent() == this) {
            removeView(version);
            margins.remove(version);
            toolbar.addView(version, new Toolbar.LayoutParams(LayoutParams.WRAP_CONTENT,
                    LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.CENTER_VERTICAL));
        }
    }

    private int fixedHeight() {
        int total = originalPaddingTop + originalPaddingBottom;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            LayoutParams params = (LayoutParams) child.getLayoutParams();
            if (params.weight == 0)
                total += child.getMeasuredHeight() + params.topMargin + params.bottomMargin;
        }
        return total;
    }

    private void fitVerticalPadding(int available) {
        int excess = Math.max(0, fixedHeight() - available);
        int removeTop = Math.min(originalPaddingTop, (excess + 1) / 2);
        int removeBottom = Math.min(originalPaddingBottom, excess - removeTop);
        removeTop = Math.min(originalPaddingTop, excess - removeBottom);
        int top = originalPaddingTop - removeTop, bottom = originalPaddingBottom - removeBottom;
        if (getPaddingTop() != top || getPaddingBottom() != bottom)
            setPadding(getPaddingLeft(), top, getPaddingRight(), bottom);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        for (Map.Entry<View, int[]> entry : margins.entrySet()) {
            LayoutParams params = (LayoutParams) entry.getKey().getLayoutParams();
            params.topMargin = entry.getValue()[0];
            params.bottomMargin = entry.getValue()[1];
        }
        // Measure intrinsic text/row heights before the finite parent can squeeze later rows.
        super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        if (MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED) return;
        int available = MeasureSpec.getSize(heightSpec), overflow = fixedHeight() - available;
        if (overflow <= 0) {
            fitVerticalPadding(available);
            super.onMeasure(widthSpec, heightSpec);
            return;
        }
        int removable = 0;
        for (int[] original : margins.values()) removable += original[0] + original[1];
        float fraction = removable == 0 ? 0 : Math.max(0, (removable - overflow) / (float) removable);
        for (Map.Entry<View, int[]> entry : margins.entrySet()) {
            LayoutParams params = (LayoutParams) entry.getKey().getLayoutParams();
            params.topMargin = (int) (entry.getValue()[0] * fraction);
            params.bottomMargin = (int) (entry.getValue()[1] * fraction);
        }
        super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        if (fixedHeight() > available) {
            moveVersionIntoToolbar();
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        }
        fitVerticalPadding(available);
        super.onMeasure(widthSpec, heightSpec);
    }
}
