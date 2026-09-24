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
import com.akin.wallet.model.SocialAccountModel;
import com.akin.wallet.model.SocialPlatformModel;
import com.akin.wallet.util.Ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Associated account rows: current link set with remove, or pick mode with [+]. */
public class AssociatedAccountAdapter extends RecyclerView.Adapter<AssociatedAccountAdapter.AccountViewHolder> implements Filterable {

    public interface OnActionListener {
        void onAction(SocialAccountModel account, boolean removed);
    }

    /** Host-owned list: adapter shows it in place so save reads one list. */
    private final List<SocialAccountModel> visibleAccounts;
    private final List<SocialAccountModel> filterSource;
    private final boolean unlinkMode;
    private final OnActionListener listener;

    public AssociatedAccountAdapter(List<SocialAccountModel> accounts, boolean unlinkMode, OnActionListener listener) {
        this.visibleAccounts = accounts != null ? accounts : new ArrayList<>();
        this.filterSource = new ArrayList<>(this.visibleAccounts);
        this.unlinkMode = unlinkMode;
        this.listener = listener;
    }

    @NonNull
    @Override
    public AccountViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_platform_row, parent, false);
        return new AccountViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull AccountViewHolder holder, int position) {
        SocialAccountModel account = visibleAccounts.get(position);
        holder.name.setText(account.getPlatform());
        holder.username.setText(account.getUsername());
        SocialPlatformModel.bindIcon(holder.icon, account.getPlatform(), account.getIconRes());

        holder.action.setVisibility(View.VISIBLE);
        if (unlinkMode) {
            holder.action.setImageResource(R.drawable.ic_remove_circle);
            holder.action.setOnClickListener(v -> {
                int clicked = holder.getBindingAdapterPosition();
                if (clicked < 0 || clicked >= visibleAccounts.size()) {
                    return;
                }
                SocialAccountModel removed = visibleAccounts.get(clicked);
                visibleAccounts.remove(clicked);
                filterSource.remove(removed);
                notifyItemRemoved(clicked);
                if (listener != null) {
                    listener.onAction(removed, true);
                }
            });
            holder.itemView.setOnClickListener(null);
        } else {
            holder.action.setImageResource(R.drawable.ic_add_circle);
            holder.action.setOnClickListener(v -> {
                int clicked = holder.getBindingAdapterPosition();
                if (clicked < 0 || clicked >= visibleAccounts.size() || listener == null) {
                    return;
                }
                listener.onAction(visibleAccounts.get(clicked), false);
            });
            holder.itemView.setOnClickListener(v -> {
                int clicked = holder.getBindingAdapterPosition();
                if (clicked < 0 || clicked >= visibleAccounts.size() || listener == null) {
                    return;
                }
                listener.onAction(visibleAccounts.get(clicked), false);
            });
        }
    }

    @Override
    public int getItemCount() {
        return visibleAccounts.size();
    }

    public void onExternalAdd(SocialAccountModel account) {
        if (account == null) {
            return;
        }
        for (SocialAccountModel existing : filterSource) {
            if (existing.getId() == account.getId()) {
                return;
            }
        }
        filterSource.add(account);
    }

    public void onExternalRestore(@NonNull List<SocialAccountModel> restored) {
        List<SocialAccountModel> next = new ArrayList<>(restored);
        DiffUtil.DiffResult diff = Ui.calculateDiff(
                visibleAccounts, next,
                (a, b) -> a.getId() == b.getId(), Object::equals);
        visibleAccounts.clear();
        visibleAccounts.addAll(next);
        filterSource.clear();
        filterSource.addAll(next);
        diff.dispatchUpdatesTo(this);
    }

    @Override
    public Filter getFilter() {
        return accountFilter;
    }

    private final Filter accountFilter = new Filter() {
        @Override
        protected FilterResults performFiltering(CharSequence constraint) {
            FilterResults results = new FilterResults();
            results.values = filterAccounts(constraint);
            return results;
        }

        @Override
        @SuppressWarnings("unchecked")
        protected void publishResults(CharSequence constraint, FilterResults results) {
            Object rawValues = results != null ? results.values : null;
            List<SocialAccountModel> nextAccounts =
                    rawValues instanceof List ? (List<SocialAccountModel>) rawValues : new ArrayList<>();
            DiffUtil.DiffResult diff = Ui.calculateDiff(
                    visibleAccounts, nextAccounts,
                    (a, b) -> a.getId() == b.getId(), Object::equals);
            visibleAccounts.clear();
            visibleAccounts.addAll(nextAccounts);
            diff.dispatchUpdatesTo(AssociatedAccountAdapter.this);
        }
    };

    private List<SocialAccountModel> filterAccounts(CharSequence constraint) {
        List<SocialAccountModel> filteredAccounts = new ArrayList<>();
        if (constraint == null || constraint.length() == 0) {
            filteredAccounts.addAll(filterSource);
            return filteredAccounts;
        }
        String filterPattern = constraint.toString().toLowerCase(Locale.ROOT).trim();
        for (SocialAccountModel account : filterSource) {
            if (matchesAccount(account, filterPattern)) {
                filteredAccounts.add(account);
            }
        }
        return filteredAccounts;
    }

    private static boolean matchesAccount(SocialAccountModel account, String filterPattern) {
        return Ui.matchesFilter(account.getPlatform(), filterPattern)
                || Ui.matchesFilter(account.getUsername(), filterPattern);
    }

    public static class AccountViewHolder extends RecyclerView.ViewHolder {
        ImageView icon;
        TextView name;
        TextView username;
        ImageView action;

        AccountViewHolder(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.platform_row_icon);
            name = itemView.findViewById(R.id.platform_row_title);
            username = itemView.findViewById(R.id.platform_row_subtitle);
            action = itemView.findViewById(R.id.platform_row_action);
        }
    }
}
