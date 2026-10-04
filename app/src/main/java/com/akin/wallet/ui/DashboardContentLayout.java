package com.akin.wallet.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.recyclerview.widget.RecyclerView;

import com.akin.wallet.R;
import com.akin.wallet.util.Ui;

/**
 * Fixed portrait dashboard: reserve social content, then fit horizontal card faces.
 */
public final class DashboardContentLayout extends LinearLayout {
    private boolean sizing, samplingEmpty, emptyReferenceDirty = true;
    private int emptyWidthSpec, emptyHeightSpec, emptyMaximum, emptyCarouselHeight;

    public DashboardContentLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public void requestLayout() {
        if (!sizing) emptyReferenceDirty = true;
        super.requestLayout();
    }

    private void measureEmptyReference(View card, int widthSpec, int heightSpec) {
        if (!emptyReferenceDirty && emptyWidthSpec == widthSpec && emptyHeightSpec == heightSpec) return;
        View empty = findViewById(R.id.dashboard_social_account_empty_state);
        int cardVisibility = card.getVisibility(), emptyVisibility = empty.getVisibility();
        samplingEmpty = true;
        try {
            // Let the existing weighted empty-state layout establish the actual
            // limit. Intrinsic child estimates do not include LinearLayout's allocation.
            card.setVisibility(GONE);
            empty.setVisibility(VISIBLE);
            measureContent(widthSpec, heightSpec);
            emptyMaximum = empty.getMeasuredHeight();
            emptyCarouselHeight = ((LayoutParams) findViewById(R.id.dashboard_government_id_carousel)
                    .getLayoutParams()).height;
        } finally {
            empty.setVisibility(emptyVisibility);
            card.setVisibility(cardVisibility);
            samplingEmpty = false;
        }
        emptyWidthSpec = widthSpec;
        emptyHeightSpec = heightSpec;
        emptyReferenceDirty = false;
    }

    private boolean carousel(View child) {
        int id = child.getId();
        return id == R.id.dashboard_government_id_carousel || id == R.id.dashboard_bank_card_carousel
                || id == R.id.dashboard_search_government_id_list || id == R.id.dashboard_search_bank_card_list;
    }

    private void compactEmptyStates(View view) {
        if (view instanceof DashboardEmptyStateLayout) {
            ((DashboardEmptyStateLayout) view).useCompactArrangement();
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++)
                compactEmptyStates(group.getChildAt(index));
        }
    }

    private RecyclerView socialList(View child) {
        if (child.getId() == R.id.dashboard_social_account_card) {
            return child.findViewById(R.id.dashboard_social_account_list);
        }
        if (child.getId() == R.id.dashboard_search_social_account_card) {
            return child.findViewById(R.id.dashboard_search_social_account_list);
        }
        return null;
    }

    private void measureSocialCard(View card, int widthSpec, int maximumHeight) {
        LayoutParams params = (LayoutParams) card.getLayoutParams();
        params.height = LayoutParams.WRAP_CONTENT;
        params.weight = 0;
        card.measure(getChildMeasureSpec(widthSpec,
                        getPaddingLeft() + getPaddingRight() + params.leftMargin + params.rightMargin,
                        params.width),
                MeasureSpec.makeMeasureSpec(Math.max(0, maximumHeight), MeasureSpec.AT_MOST));
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        measureContent(widthSpec, heightSpec);
    }

    private void measureContent(int widthSpec, int heightSpec) {
        boolean previousSizing = sizing;
        sizing = true;
        try {
            if (MeasureSpec.getMode(heightSpec) != MeasureSpec.UNSPECIFIED) {
                // Screen qualifiers include system bars; base compactness on actual content space.
                if (MeasureSpec.getSize(heightSpec) / getResources().getDisplayMetrics().density < 640)
                    compactEmptyStates(this);
                int fixed = getPaddingTop() + getPaddingBottom(), count = 0;
                int buttonClearance = getId() == R.id.dashboard_content
                        ? getResources().getDimensionPixelSize(R.dimen.dashboard_fab_size)
                        + getResources().getDimensionPixelSize(R.dimen.fab_margin) : 0;
                fixed += buttonClearance;
                View socialCard = null;
                RecyclerView socialRows = null;
                int availableWidth = Math.max(0, MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight());
                int natural = Math.round(availableWidth * Ui.CAROUSEL_PAGE_RATIO / Ui.CARD_ASPECT_RATIO);
                for (int i = 0; i < getChildCount(); i++) {
                    View child = getChildAt(i);
                    if (child.getVisibility() == GONE) continue;
                    LayoutParams params = (LayoutParams) child.getLayoutParams();
                    fixed += params.topMargin + params.bottomMargin;
                    RecyclerView rows = socialList(child);
                    if (rows != null) {
                        socialCard = child;
                        socialRows = rows;
                        continue;
                    }
                    if (carousel(child)) {
                        count++;
                        continue;
                    }
                    if (params.weight > 0) {
                        // Reserve a visible social row; empty-state actions need their intrinsic height.
                        if (child.getId() == R.id.dashboard_social_account_empty_state) {
                            params.height = LayoutParams.WRAP_CONTENT;
                            measureChildWithMargins(child, widthSpec, 0, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), 0);
                            params.height = 0;
                            fixed += child.getMeasuredHeight();
                        } else
                            fixed += getResources().getDimensionPixelSize(R.dimen.dashboard_social_min_height);
                    } else {
                        measureChildWithMargins(child, widthSpec, 0, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), 0);
                        fixed += child.getMeasuredHeight();
                    }
                }
                int available = Math.max(0, MeasureSpec.getSize(heightSpec) - fixed);
                boolean dashboardSocial = socialCard != null && getId() == R.id.dashboard_content;
                int socialReserve = 0;
                if (dashboardSocial && !samplingEmpty) {
                    measureEmptyReference(socialCard, widthSpec, heightSpec);
                } else if (socialRows != null && count > 0) {
                    // Search has no weighted social empty tile. Keep room for rows
                    // when government-ID and bank-card matches are also visible.
                    RecyclerView.Adapter<?> adapter = socialRows.getAdapter();
                    int rows = adapter == null ? 0 : Math.min(2, adapter.getItemCount());
                    socialReserve = Math.min(available, Math.round(rows
                            * (getResources().getDimensionPixelSize(R.dimen.dashboard_social_min_height)
                            + getResources().getDimensionPixelSize(R.dimen.spacing_2dp))
                            * getResources().getConfiguration().fontScale));
                }
                int cardHeight = dashboardSocial ? emptyCarouselHeight : count == 0 ? natural
                        : Math.min(natural, Math.max(0, (available - socialReserve) / count));
                for (int i = 0; i < getChildCount(); i++) {
                    View child = getChildAt(i);
                    if (!carousel(child)) continue;
                    // Re-evaluate after any window/inset/font change; never post a resize loop.
                    LayoutParams params = (LayoutParams) child.getLayoutParams();
                    params.height = cardHeight;
                }
                if (socialCard != null) {
                    int viewportHeight = dashboardSocial ? emptyMaximum
                            : Math.max(0, available - cardHeight * count);
                    // Account count and scrolling must never change the row width.
                    if (socialRows.getPaddingEnd() != 0 || socialRows.getPaddingBottom() != 0)
                        socialRows.setPaddingRelative(socialRows.getPaddingStart(), socialRows.getPaddingTop(), 0, 0);
                    measureSocialCard(socialCard, widthSpec, viewportHeight);
                    ((LayoutParams) socialCard.getLayoutParams()).height = socialCard.getMeasuredHeight();
                }
            }
            super.onMeasure(widthSpec, heightSpec);
        } finally {
            sizing = previousSizing;
        }
    }
}
