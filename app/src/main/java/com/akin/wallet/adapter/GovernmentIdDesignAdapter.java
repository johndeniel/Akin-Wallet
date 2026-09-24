package com.akin.wallet.adapter;

import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.akin.wallet.R;
import com.akin.wallet.util.Ui;
import com.akin.wallet.model.GovernmentIDModel;
import com.akin.wallet.util.GovernmentIdFaceRenderer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Government ID type picker. Swiping selects the type, typing updates live. */
public class GovernmentIdDesignAdapter extends RecyclerView.Adapter<GovernmentIdDesignAdapter.FaceViewHolder> {

    public interface OnTypePageListener {
        void onTypePageSelected(int typeIndex);
    }

    private final List<GovernmentIDModel.IdType> idTypes = GovernmentIDModel.getAllTypes();
    private final OnTypePageListener listener;
    private Map<String, String> draftFields = new LinkedHashMap<>();

    public GovernmentIdDesignAdapter(OnTypePageListener listener) {
        this.listener = listener;
    }

    public int getTypeCount() {
        return idTypes.size();
    }

    private String typeNameAt(int position) {
        int clamped = Math.max(0, Math.min(position, idTypes.size() - 1));
        return idTypes.get(clamped).name;
    }

    public void updatePreview(Map<String, String> fields) {
        Map<String, String> next =
                fields != null ? new LinkedHashMap<>(fields) : new LinkedHashMap<>();
        if (next.equals(this.draftFields)) {
            return;
        }
        this.draftFields = next;
        notifyItemRangeChanged(0, getItemCount());
    }

    @NonNull
    @Override
    public FaceViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new FaceViewHolder(Ui.inflateCarouselPage(parent, R.layout.item_government_id_card), listener);
    }

    @Override
    public void onBindViewHolder(@NonNull FaceViewHolder holder, int position) {
        String pageType = typeNameAt(position);
        GovernmentIDModel.IdType spec = GovernmentIDModel.forName(pageType);
        Map<String, String> pageFields = new LinkedHashMap<>();
        for (GovernmentIDModel.IdField field : spec.fields) {
            String value = draftFields.get(field.key);
            pageFields.put(field.key, value != null ? value : "");
        }
        GovernmentIdFaceRenderer.render(holder.face, spec, pageType, pageType, pageFields);
    }

    @Override
    public int getItemCount() {
        return getTypeCount();
    }

    public static class FaceViewHolder extends RecyclerView.ViewHolder {
        final GovernmentIdFaceRenderer.FaceViews face;

        FaceViewHolder(@NonNull View itemView, OnTypePageListener listener) {
            super(itemView);
            face = GovernmentIdFaceRenderer.FaceViews.bind(itemView);
            face.cardRoot.setOnClickListener(v -> {
                int clicked = getBindingAdapterPosition();
                if (clicked != RecyclerView.NO_POSITION && listener != null) {
                    listener.onTypePageSelected(clicked);
                }
            });
        }
    }
}
