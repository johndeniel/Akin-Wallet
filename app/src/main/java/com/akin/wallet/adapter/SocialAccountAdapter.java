package com.akin.wallet.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.akin.wallet.R;
import com.akin.wallet.model.SocialAccountModel;
import com.akin.wallet.model.SocialPlatformModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Social Account list — one row per account (platform icon + username).
 * Tapping a row opens its editor.
 */
public class SocialAccountAdapter extends RecyclerView.Adapter<SocialAccountAdapter.AccountViewHolder> {

    public interface OnAccountClickListener {
        void onAccountClick(SocialAccountModel item);
    }

    private final List<SocialAccountModel> accounts = new ArrayList<>();
    private final OnAccountClickListener listener;

    public SocialAccountAdapter(OnAccountClickListener listener) {
        this.listener = listener;
    }

    public void updateData(List<SocialAccountModel> newAccounts) {
        // Drops null rows; a null would NPE inside areItemsTheSame.
        List<SocialAccountModel> next = new ArrayList<>();
        if (newAccounts != null) {
            for (SocialAccountModel account : newAccounts) {
                if (account != null) {
                    next.add(account);
                }
            }
        }
        // Roll back on failed dispatch so adapter and RecyclerView stay
        // in agreement (previously crashed the 2nd search).
        List<SocialAccountModel> old = new ArrayList<>(accounts);
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
                    if (!old.get(oldPos).equals(next.get(newPos))) {
                        return false;
                    }
                    // Divider visibility is positional (hidden on the last row).
                    return (oldPos == old.size() - 1) == (newPos == next.size() - 1);
                }
            });
        } catch (RuntimeException e) {
            return;
        }
        accounts.clear();
        accounts.addAll(next);
        try {
            diff.dispatchUpdatesTo(this);
        } catch (RuntimeException e) {
            accounts.clear();
            accounts.addAll(old);
        }
    }

    @NonNull
    @Override
    public AccountViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_social_account, parent, false);
        return new AccountViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull AccountViewHolder holder, int position) {
        // RecyclerView may bind a stale position while removals animate.
        if (position < 0 || position >= accounts.size()) {
            return;
        }
        // A missing row view (rename drift, bad inflation) renders nothing:
        // hide the row and drop its click so recycled content never shows
        // stale data with a live tap target.
        if (holder.icon == null || holder.title == null || holder.sub == null) {
            holder.itemView.setVisibility(View.GONE);
            holder.itemView.setOnClickListener(null);
            return;
        }
        holder.itemView.setVisibility(View.VISIBLE);
        SocialAccountModel account = accounts.get(position);
        try {
            String fallback = holder.itemView.getContext().getString(R.string.label_social_account);
            String platform = account.getPlatform() != null && !account.getPlatform().trim().isEmpty()
                    ? account.getPlatform().trim() : fallback;
            String username = account.getUsername() != null ? account.getUsername().trim() : "";
            holder.title.setText(platform);
            holder.sub.setText(username.isEmpty() ? fallback : username);
            SocialPlatformModel.bindIcon(holder.icon, account.getPlatform(), account.getIconRes());
        } catch (RuntimeException e) {
            // One bad row must never close the app.
        }
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onAccountClick(account);
            }
        });
        if (holder.divider != null) {
            holder.divider.setVisibility(
                    position == getItemCount() - 1 ? View.GONE : View.VISIBLE);
        }
    }

    @Override
    public int getItemCount() {
        return accounts.size();
    }

    public static class AccountViewHolder extends RecyclerView.ViewHolder {
        ImageView icon;
        TextView title;
        TextView sub;
        View divider;

        AccountViewHolder(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.social_account_row_icon);
            title = itemView.findViewById(R.id.social_account_row_title);
            sub = itemView.findViewById(R.id.social_account_row_subtitle);
            divider = itemView.findViewById(R.id.social_account_row_divider);
        }
    }
}
