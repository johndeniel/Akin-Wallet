package com.akin.wallet.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.akin.wallet.R;
import com.akin.wallet.model.SocialAccountModel;
import com.akin.wallet.model.SocialPlatformModel;
import com.akin.wallet.util.CardText;
import com.akin.wallet.util.Ui;

import java.util.ArrayList;
import java.util.List;

/** Social account list. Tap opens the editor. */
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
        List<SocialAccountModel> next = Ui.nonNullList(newAccounts);
        List<SocialAccountModel> old = new ArrayList<>(accounts);
        accounts.clear();
        accounts.addAll(next);
        Ui.calculateDiff(old, next,
                (a, b) -> a.getId() == b.getId(),
                Object::equals).dispatchUpdatesTo(this);
    }

    @NonNull
    @Override
    public AccountViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_social_account, parent, false);
        return new AccountViewHolder(view, listener);
    }

    @Override
    public void onBindViewHolder(@NonNull AccountViewHolder holder, int position) {
        SocialAccountModel account = accounts.get(position);
        holder.bound = account;
        String fallback = holder.itemView.getContext().getString(R.string.label_social_account);
        holder.title.setText(CardText.safe(account.getPlatform(), fallback));
        String username = account.getUsername() != null ? account.getUsername().trim() : "";
        holder.sub.setText(username.isEmpty() ? fallback : username);
        SocialPlatformModel.bindIcon(holder.icon, account.getPlatform(), account.getIconRes());
        holder.itemView.setContentDescription(
                holder.title.getText() + ", " + holder.sub.getText());
    }

    @Override
    public int getItemCount() {
        return accounts.size();
    }

    public static class AccountViewHolder extends RecyclerView.ViewHolder {
        ImageView icon;
        TextView title;
        TextView sub;
        SocialAccountModel bound;

        AccountViewHolder(@NonNull View itemView, OnAccountClickListener listener) {
            super(itemView);
            icon = itemView.findViewById(R.id.social_account_row_icon);
            title = itemView.findViewById(R.id.social_account_row_title);
            sub = itemView.findViewById(R.id.social_account_row_subtitle);
            itemView.setOnClickListener(v -> {
                if (listener != null && bound != null) {
                    listener.onAccountClick(bound);
                }
            });
        }
    }
}
