package com.akin.wallet.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.akin.wallet.R;
import com.akin.wallet.model.GovernmentIDModel;
import com.akin.wallet.util.GovernmentIdFaceRenderer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Government ID type picker for the Government ID screen — each page is an ID
 * type rendered with the single shared dashboard item and face metrics,
 * driven by the shared draft. Same 0.68-page width, 12dp gap and snap as
 * the dashboard. Swiping pages selects the type; typing updates live.
 */
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
        // Clamp, don't fall back: every position here comes from the adapter
        // itself, so an out-of-range index is a bug that must stay visible
        // next to valid data instead of silently rendering another type.
        int clamped = Math.max(0, Math.min(position, idTypes.size() - 1));
        return idTypes.get(clamped).name;
    }

    public void updatePreview(Map<String, String> fields) {
        Map<String, String> next =
                fields != null ? new LinkedHashMap<>(fields) : new LinkedHashMap<>();
        if (next.equals(this.draftFields)) {
            // Keystroke changed nothing visible: skip the carousel repaint.
            return;
        }
        this.draftFields = next;
        // Every page shows the same draft: repaint the fixed page set, not
        // the whole list pipeline.
        notifyItemRangeChanged(0, getTypeCount());
    }

    @NonNull
    @Override
    public FaceViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // Single shared face: same XML + same 0.68 page-width ratio as the
        // dashboard carousel so the form picker looks identical to Home.
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_government_id_card, parent, false);
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        int parentWidth = parent.getMeasuredWidth();
        if (parentWidth <= 0) {
            // Pre-layout inflation: display width minus carousel padding, the
            // same viewport the dashboard measures pages against.
            parentWidth = parent.getResources().getDisplayMetrics().widthPixels
                    - parent.getPaddingStart() - parent.getPaddingEnd();
        }
        if (lp != null && parentWidth > 0) {
            lp.width = (int) (parentWidth * BankCardAdapter.PAGE_WIDTH_RATIO);
            view.setLayoutParams(lp);
        }
        return new FaceViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull FaceViewHolder holder, int position) {
        String pageType = typeNameAt(position);
        GovernmentIDModel.IdType spec = GovernmentIDModel.forName(pageType);

        // Each page shows its own type's values from the shared draft so the
        // user can compare faces while typing (common keys carry over).
        Map<String, String> pageFields = new LinkedHashMap<>();
        for (GovernmentIDModel.IdField field : spec.fields) {
            String value = draftFields.get(field.key);
            pageFields.put(field.key, value != null ? value : "");
        }
        GovernmentIdFaceRenderer.render(holder.face, spec, pageType, pageType, pageFields);

        holder.face.cardRoot.setOnClickListener(v -> {
            int clicked = holder.getBindingAdapterPosition();
            if (clicked != RecyclerView.NO_POSITION && listener != null) {
                listener.onTypePageSelected(clicked);
            }
        });
    }

    @Override
    public int getItemCount() {
        return idTypes.size();
    }

    public static class FaceViewHolder extends RecyclerView.ViewHolder {
        final GovernmentIdFaceRenderer.FaceViews face;

        FaceViewHolder(@NonNull View itemView) {
            super(itemView);
            face = GovernmentIdFaceRenderer.FaceViews.bind(itemView);
        }
    }
}
