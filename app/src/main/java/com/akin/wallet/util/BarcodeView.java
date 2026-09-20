package com.akin.wallet.util;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;

/**
 * Tiny static decorative barcode for ID faces (sits under the avatar).
 * The bar pattern is fixed and identical on every card (it encodes
 * nothing) — use {@link #setBarColor(int)} to follow the face scheme.
 */
public final class BarcodeView extends View {

    private static final int[] BARS = {
            2, 1, 1, 3, 1, 2, 1, 1, 3, 2, 1, 2, 1, 3, 1, 2
    };

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int unitPx;
    private int lastColor = Integer.MIN_VALUE;

    public BarcodeView(Context context) {
        super(context);
        init(context);
    }

    public BarcodeView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public BarcodeView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        paint.setColor(0xFF000000);
        paint.setStyle(Paint.Style.FILL);
        unitPx = Math.max(1, (int) (1 * context.getResources().getDisplayMetrics().density));
    }

    public void setBarColor(int color) {
        if (color == lastColor) {
            return;
        }
        lastColor = color;
        paint.setColor(color);
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int total = 0;
        for (int w : BARS) {
            total += w;
        }
        total += BARS.length - 1;
        int width = total * unitPx + getPaddingLeft() + getPaddingRight();
        int height = MeasureSpec.getSize(heightMeasureSpec);
        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            height = (int) (12 * getResources().getDisplayMetrics().density);
        }
        height += getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(
                resolveSize(width, widthMeasureSpec),
                resolveSize(height, heightMeasureSpec));
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        float x = getPaddingLeft();
        float top = getPaddingTop();
        float bottom = getMeasuredHeight() - getPaddingBottom();
        for (int bar : BARS) {
            float w = bar * unitPx;
            canvas.drawRect(x, top, x + w, bottom, paint);
            x += w + unitPx;
        }
    }
}
