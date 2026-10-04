package com.akin.wallet.adapter;

import com.akin.wallet.model.GovernmentIdTypes;
import com.akin.wallet.util.GovernmentIdFaceText;

import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.AsyncListDiffer;
import androidx.recyclerview.widget.DiffUtil;

import com.akin.wallet.R;
import com.akin.wallet.util.Ui;
import com.akin.wallet.model.GovernmentIDModel;
import com.akin.wallet.util.GovernmentIdFaceRenderer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Government ID carousel. Tap opens the editor.
 */
public class GovernmentIdAdapter extends RecyclerView.Adapter<GovernmentIdAdapter.IdCardViewHolder> {

    public interface OnIdClickListener {
        void onIdClick(GovernmentIDModel item);
    }

    private final AsyncListDiffer<GovernmentIDModel> differ = new AsyncListDiffer<>(this,
            new DiffUtil.ItemCallback<GovernmentIDModel>() {
                @Override
                public boolean areItemsTheSame(GovernmentIDModel a, GovernmentIDModel b) {
                    return a.getId() == b.getId();
                }

                @Override
                public boolean areContentsTheSame(GovernmentIDModel a, GovernmentIDModel b) {
                    return a.equals(b);
                }
            });
    private final OnIdClickListener listener;

    public GovernmentIdAdapter(OnIdClickListener listener) {
        this.listener = listener;
    }

    public void updateData(List<GovernmentIDModel> rows) {
        updateData(rows, null);
    }

    public void updateData(List<GovernmentIDModel> rows, Runnable committed) {
        differ.submitList(List.copyOf(Ui.nonNullList(rows)), committed);
    }

    @NonNull
    @Override
    public IdCardViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new IdCardViewHolder(Ui.inflateCarouselPage(parent, R.layout.item_government_id_card), listener);
    }

    @Override
    public void onBindViewHolder(@NonNull IdCardViewHolder holder, int position) {
        GovernmentIDModel idCard = differ.getCurrentList().get(position);
        holder.bound = idCard;
        Map<String, String> fields = idCard.getFieldsRef();
        String idType = idCard.getIdType().trim();
        GovernmentIdTypes.IdType spec = GovernmentIdTypes.isKnownType(idType)
                ? GovernmentIdTypes.forName(idType)
                : GovernmentIdTypes.genericType(idType, fields);
        GovernmentIdFaceRenderer.render(holder.face,
                spec,
                idType,
                idType.isEmpty() ? "GOVERNMENT ID" : idType,
                fields);
    }

    @Override
    public int getItemCount() {
        return differ.getCurrentList().size();
    }

    public static class IdCardViewHolder extends RecyclerView.ViewHolder {
        final GovernmentIdFaceRenderer.FaceViews face;
        GovernmentIDModel bound;

        IdCardViewHolder(@NonNull View itemView, OnIdClickListener listener) {
            super(itemView);
            face = GovernmentIdFaceRenderer.FaceViews.bind(itemView);
            itemView.setOnClickListener(v -> {
                if (listener != null && bound != null) {
                    listener.onIdClick(bound);
                }
            });
        }
    }
}
