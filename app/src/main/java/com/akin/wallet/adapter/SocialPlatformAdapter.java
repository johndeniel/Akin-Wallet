package com.akin.wallet.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.akin.wallet.R;
import com.akin.wallet.model.SocialPlatformModel;
import com.akin.wallet.util.Ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Platform picker rows (icon + name + URL). Tap selects the platform. */
public class SocialPlatformAdapter extends RecyclerView.Adapter<SocialPlatformAdapter.PlatformViewHolder> implements Filterable {

    public interface OnPlatformSelectedListener {
        void onPlatformSelected(int iconRes, String name, String url);
    }

    private final List<SocialPlatformModel.Option> visiblePlatforms;
    private final List<SocialPlatformModel.Option> allPlatforms;
    private final OnPlatformSelectedListener listener;

    public SocialPlatformAdapter(List<SocialPlatformModel.Option> platforms, OnPlatformSelectedListener listener) {
        this.visiblePlatforms = platforms != null ? new ArrayList<>(platforms) : new ArrayList<>();
        this.allPlatforms = new ArrayList<>(this.visiblePlatforms);
        this.listener = listener;
    }

    @NonNull
    @Override
    public PlatformViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_platform_row, parent, false);
        PlatformViewHolder holder = new PlatformViewHolder(view);
        holder.action.setVisibility(View.GONE);
        holder.itemView.setOnClickListener(v -> {
            if (listener == null) {
                return;
            }
            int pos = holder.getBindingAdapterPosition();
            if (pos < 0 || pos >= visiblePlatforms.size()) {
                return;
            }
            SocialPlatformModel.Option platform = visiblePlatforms.get(pos);
            listener.onPlatformSelected(platform.getIconRes(), platform.getName(), platform.getUrl());
        });
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull PlatformViewHolder holder, int position) {
        SocialPlatformModel.Option platform = visiblePlatforms.get(position);
        holder.icon.setImageResource(platform.getIconRes());
        holder.name.setText(platform.getName());
        holder.url.setText(platform.getUrl());
    }

    @Override
    public int getItemCount() {
        return visiblePlatforms.size();
    }

    @Override
    public Filter getFilter() {
        return platformFilter;
    }

    private final Filter platformFilter = new Filter() {
        @Override
        protected FilterResults performFiltering(CharSequence constraint) {
            FilterResults results = new FilterResults();
            results.values = filterPlatforms(constraint);
            return results;
        }

        @Override
        @SuppressWarnings("unchecked")
        protected void publishResults(CharSequence constraint, FilterResults results) {
            Object rawValues = results != null ? results.values : null;
            final List<SocialPlatformModel.Option> nextOptions =
                    rawValues instanceof List ? (List<SocialPlatformModel.Option>) rawValues : new ArrayList<>();
            DiffUtil.DiffResult diff = Ui.calculateDiff(
                    visiblePlatforms, nextOptions,
                    (a, b) -> a.getName().equals(b.getName()),
                    (a, b) -> a.getIconRes() == b.getIconRes()
                            && a.getName().equals(b.getName())
                            && a.getUrl().equals(b.getUrl()));
            visiblePlatforms.clear();
            visiblePlatforms.addAll(nextOptions);
            diff.dispatchUpdatesTo(SocialPlatformAdapter.this);
        }
    };

    private List<SocialPlatformModel.Option> filterPlatforms(CharSequence constraint) {
        List<SocialPlatformModel.Option> filteredOptions = new ArrayList<>();
        if (constraint == null || constraint.length() == 0) {
            filteredOptions.addAll(allPlatforms);
            return filteredOptions;
        }
        String filterPattern = constraint.toString().toLowerCase(Locale.ROOT).trim();
        for (SocialPlatformModel.Option platform : allPlatforms) {
            if (matchesPlatform(platform, filterPattern)) {
                filteredOptions.add(platform);
            }
        }
        return filteredOptions;
    }

    private static boolean matchesPlatform(SocialPlatformModel.Option platform, String filterPattern) {
        return Ui.matchesFilter(platform.getName(), filterPattern)
                || Ui.matchesFilter(platform.getUrl(), filterPattern);
    }

    public static class PlatformViewHolder extends RecyclerView.ViewHolder {
        ImageView icon;
        TextView name;
        TextView url;
        View action;

        PlatformViewHolder(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.platform_row_icon);
            name = itemView.findViewById(R.id.platform_row_title);
            url = itemView.findViewById(R.id.platform_row_subtitle);
            action = itemView.findViewById(R.id.platform_row_action);
        }
    }
}
