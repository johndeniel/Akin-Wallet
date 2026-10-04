package com.akin.wallet.ui;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

import com.akin.wallet.R;

/** Fits a complete card face into a small carousel page without cropping its fields. */
public final class CardPreviewLayout extends FrameLayout {
    public CardPreviewLayout(Context context) {
        super(context);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        if (getChildCount() != 1 || MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED) {
            super.onMeasure(widthSpec, heightSpec);
            return;
        }
        int width = MeasureSpec.getSize(widthSpec);
        int minimum = Math.round(getResources().getDimension(R.dimen.card_preview_min_width)
                * Math.max(1f, getResources().getConfiguration().fontScale));
        View face = getChildAt(0);
        int faceWidth = Math.max(width, minimum);
        face.measure(MeasureSpec.makeMeasureSpec(faceWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        int faceHeight = face.getMeasuredHeight();
        float scale = width / (float) faceWidth;
        if (faceHeight > 0 && MeasureSpec.getMode(heightSpec) != MeasureSpec.UNSPECIFIED) {
            scale = Math.min(scale, MeasureSpec.getSize(heightSpec) / (float) faceHeight);
        }
        face.setPivotX(0);
        face.setPivotY(0);
        face.setScaleX(scale);
        face.setScaleY(scale);
        setMeasuredDimension(width, resolveSize(Math.round(faceHeight * scale), heightSpec));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        if (getChildCount() == 1) {
            View face = getChildAt(0);
            face.layout(0, 0, face.getMeasuredWidth(), face.getMeasuredHeight());
        }
    }
}
