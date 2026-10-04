package com.akin.wallet.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import androidx.recyclerview.widget.RecyclerView;

import com.akin.wallet.util.Ui;

/**
 * Keeps attached/recycled card faces within their portrait height budget.
 */
public final class PortraitCardCarousel extends RecyclerView {
    public PortraitCardCarousel(Context context, AttributeSet attrs) {
        super(context, attrs);
        addOnChildAttachStateChangeListener(new OnChildAttachStateChangeListener() {
            @Override
            public void onChildViewAttachedToWindow(View view) {
                fit(view, getMeasuredWidth());
            }

            @Override
            public void onChildViewDetachedFromWindow(View view) {
            }
        });
    }

    private void fit(View view, int width) {
        int height = getLayoutParams().height;
        if (width <= 0 || height <= 0) return;
        int pageWidth = Math.min(Math.round(width * Ui.CAROUSEL_PAGE_RATIO), Math.round(height * Ui.CARD_ASPECT_RATIO));
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params.width != pageWidth) {
            params.width = pageWidth;
            view.setLayoutParams(params);
        }
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        for (int i = 0; i < getChildCount(); i++)
            fit(getChildAt(i), MeasureSpec.getSize(widthSpec));
        super.onMeasure(widthSpec, heightSpec);
    }
}
