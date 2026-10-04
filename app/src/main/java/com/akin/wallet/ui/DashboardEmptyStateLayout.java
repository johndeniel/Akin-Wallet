package com.akin.wallet.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;

import com.google.android.material.button.MaterialButton;
import com.akin.wallet.R;

/**
 * Compact empty states retain both text lines and a 48dp creation action.
 */
public final class DashboardEmptyStateLayout extends LinearLayout {
    public DashboardEmptyStateLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        if (getResources().getConfiguration().screenHeightDp < 640) useCompactArrangement();
    }

    void useCompactArrangement() {
        if (getChildCount() != 3 || !(getChildAt(2) instanceof MaterialButton)) return;
        View title = getChildAt(0), description = getChildAt(1);
        MaterialButton action = (MaterialButton) getChildAt(2);
        removeAllViews();
        setOrientation(HORIZONTAL);
        LinearLayout text = new LinearLayout(getContext());
        text.setOrientation(VERTICAL);
        text.addView(title);
        text.addView(description);
        addView(text, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        int size = getResources().getDimensionPixelSize(R.dimen.form_action_height);
        LayoutParams buttonParams = new LayoutParams(size, size);
        buttonParams.setMarginStart(getResources().getDimensionPixelSize(R.dimen.spacing_8dp));
        action.setContentDescription(action.getText());
        action.setText(null);
        action.setIconPadding(0);
        action.setMinWidth(size);
        action.setMinimumWidth(size);
        addView(action, buttonParams);
    }
}
