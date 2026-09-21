package com.akin.wallet.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.akin.wallet.R;
import com.akin.wallet.util.Ui;
import com.akin.wallet.model.GovernmentIDModel;
import com.akin.wallet.util.GovernmentIdFaceRenderer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Government ID carousel — the user's real IDs through the shared
 * {@link GovernmentIdFaceRenderer} face (compact mode for the 0.68-width page). Pages
 * are sized exactly like the Bank Card carousel. Tapping a card opens its
 * editor.
 */
public class GovernmentIdAdapter extends RecyclerView.Adapter<GovernmentIdAdapter.IdCardViewHolder> {

    public interface OnIdClickListener {
        void onIdClick(GovernmentIDModel item);
    }

    private final List<GovernmentIDModel> idCards = new ArrayList<>();
    private final OnIdClickListener listener;

    public GovernmentIdAdapter(OnIdClickListener listener) {
        this.listener = listener;
    }

    public void updateData(List<GovernmentIDModel> newIdCards) {
        // Drops null rows; a null would NPE inside areItemsTheSame.
        List<GovernmentIDModel> next = new ArrayList<>();
        if (newIdCards != null) {
            for (GovernmentIDModel id : newIdCards) {
                if (id != null) {
                    next.add(id);
                }
            }
        }
        // Roll back on failed dispatch so adapter and RecyclerView stay
        // in agreement (previously crashed the 2nd search).
        List<GovernmentIDModel> old = new ArrayList<>(idCards);
        DiffUtil.DiffResult diff;
        try {
            diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
                @Override
                public int getOldListSize() {
                    return old.size();
                }

                @Override
                public int getNewListSize() {
                    return next.size();
                }

                @Override
                public boolean areItemsTheSame(int oldPos, int newPos) {
                    return old.get(oldPos).getId() == next.get(newPos).getId();
                }

                @Override
                public boolean areContentsTheSame(int oldPos, int newPos) {
                    return old.get(oldPos).equals(next.get(newPos));
                }
            });
        } catch (RuntimeException e) {
            return;
        }
        idCards.clear();
        idCards.addAll(next);
        try {
            diff.dispatchUpdatesTo(this);
        } catch (RuntimeException e) {
            idCards.clear();
            idCards.addAll(old);
        }
    }

    @NonNull
    @Override
    public IdCardViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_government_id_card, parent, false);
        // Same page size as the bank-card carousel.
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        int parentWidth = parent.getMeasuredWidth();
        if (parentWidth <= 0) {
            // Pre-layout inflation: display width minus carousel padding, the
            // same viewport the dashboard measures pages against.
            parentWidth = parent.getResources().getDisplayMetrics().widthPixels
                    - parent.getPaddingStart() - parent.getPaddingEnd();
        }
        if (lp != null && parentWidth > 0) {
            lp.width = (int) (parentWidth * Ui.CAROUSEL_PAGE_RATIO);
            view.setLayoutParams(lp);
        }
        return new IdCardViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull IdCardViewHolder holder, int position) {
        // RecyclerView may bind a stale position while removals animate.
        if (position < 0 || position >= idCards.size()) {
            return;
        }
        GovernmentIDModel idCard = idCards.get(position);
        try {
            Map<String, String> fields = idCard.getFields();
            String idType = idCard.getIdType();
            String rawType = idType.trim();
            GovernmentIDModel.IdType spec = GovernmentIDModel.isKnownType(idType)
                    ? GovernmentIDModel.forName(idType)
                    : GovernmentIDModel.genericType(idType, fields);
            GovernmentIdFaceRenderer.render(holder.face,
                    spec,
                    idType,
                    rawType.isEmpty() ? "GOVERNMENT ID" : rawType,
                    fields);
        } catch (RuntimeException e) {
            // One bad row must never close the app.
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onIdClick(idCard);
            }
        });
    }

    @Override
    public int getItemCount() {
        return idCards.size();
    }

    public static class IdCardViewHolder extends RecyclerView.ViewHolder {
        final GovernmentIdFaceRenderer.FaceViews face;

        IdCardViewHolder(@NonNull View itemView) {
            super(itemView);
            face = GovernmentIdFaceRenderer.FaceViews.bind(itemView);
        }
    }
}
