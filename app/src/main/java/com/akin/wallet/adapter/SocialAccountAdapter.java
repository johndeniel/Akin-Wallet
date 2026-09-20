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
        List<SocialAccountModel> next =
                newAccounts != null ? new ArrayList<>(newAccounts) : new ArrayList<>();
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return accounts.size();
            }

            @Override
            public int getNewListSize() {
                return next.size();
            }

            @Override
            public boolean areItemsTheSame(int oldPos, int newPos) {
                return accounts.get(oldPos).getId() == next.get(newPos).getId();
            }

            @Override
            public boolean areContentsTheSame(int oldPos, int newPos) {
                if (!accounts.get(oldPos).equals(next.get(newPos))) {
                    return false;
                }
                // Divider visibility is positional (hidden on the last row).
                return (oldPos == accounts.size() - 1) == (newPos == next.size() - 1);
            }
        });
        accounts.clear();
        accounts.addAll(next);
        diff.dispatchUpdatesTo(this);
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
        SocialAccountModel account = accounts.get(position);
        String fallback = holder.itemView.getContext().getString(R.string.label_social_account);
        String platform = account.getPlatform() != null && !account.getPlatform().trim().isEmpty()
                ? account.getPlatform().trim() : fallback;
        String username = account.getUsername() != null ? account.getUsername().trim() : "";
        holder.title.setText(platform);
        holder.sub.setText(username.isEmpty() ? fallback : username);
        SocialPlatformModel.bindIcon(holder.icon, account.getPlatform(), account.getIconRes());
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onAccountClick(account);
            }
        });
        holder.divider.setVisibility(
                position == getItemCount() - 1 ? View.GONE : View.VISIBLE);
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
            icon = itemView.findViewById(R.id.social_account_icon);
            title = itemView.findViewById(R.id.social_account_title);
            sub = itemView.findViewById(R.id.social_account_subtitle);
            divider = itemView.findViewById(R.id.social_account_divider);
        }
    }
}
