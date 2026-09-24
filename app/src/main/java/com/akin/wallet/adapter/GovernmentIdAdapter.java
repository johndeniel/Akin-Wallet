package com.akin.wallet.adapter;

import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.akin.wallet.R;
import com.akin.wallet.util.Ui;
import com.akin.wallet.model.GovernmentIDModel;
import com.akin.wallet.util.GovernmentIdFaceRenderer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Government ID carousel. Tap opens the editor. */
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
        List<GovernmentIDModel> next = Ui.nonNullList(newIdCards);
        List<GovernmentIDModel> old = new ArrayList<>(idCards);
        idCards.clear();
        idCards.addAll(next);
        Ui.calculateDiff(old, next,
                (a, b) -> a.getId() == b.getId(),
                Object::equals).dispatchUpdatesTo(this);
    }

    @NonNull
    @Override
    public IdCardViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new IdCardViewHolder(Ui.inflateCarouselPage(parent, R.layout.item_government_id_card), listener);
    }

    @Override
    public void onBindViewHolder(@NonNull IdCardViewHolder holder, int position) {
        GovernmentIDModel idCard = idCards.get(position);
        holder.bound = idCard;
        Map<String, String> fields = idCard.getFields();
        String idType = idCard.getIdType().trim();
        GovernmentIDModel.IdType spec = GovernmentIDModel.isKnownType(idType)
                ? GovernmentIDModel.forName(idType)
                : GovernmentIDModel.genericType(idType, fields);
        GovernmentIdFaceRenderer.render(holder.face,
                spec,
                idType,
                idType.isEmpty() ? "GOVERNMENT ID" : idType,
                fields);
    }

    @Override
    public int getItemCount() {
        return idCards.size();
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
