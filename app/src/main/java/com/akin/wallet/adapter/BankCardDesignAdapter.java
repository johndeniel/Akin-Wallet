package com.akin.wallet.adapter;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.akin.wallet.R;
import com.akin.wallet.util.Ui;

import java.util.Locale;

/** Bank card design picker. Typing previews live on every face. */
public class BankCardDesignAdapter extends RecyclerView.Adapter<BankCardDesignAdapter.CardViewHolder> {

    private String bankName = "";
    private String holderName = "";
    private String last4 = "";
    private String expiry = "";
    private String cardType = "Debit";
    private String cardNetwork = "Visa";

    public int getDesignCount() {
        return BankCardAdapter.designCount();
    }

    public void updatePreview(String bankName, String holderName, String last4,
                              String expiry, String cardType, String cardNetwork) {
        String nextBank = bankName != null ? bankName : "";
        String nextHolder = holderName != null ? holderName : "";
        String nextLast4 = last4 != null ? last4 : "";
        String nextExpiry = expiry != null ? expiry : "";
        String nextType = cardType != null ? cardType : "";
        String nextNetwork = cardNetwork != null ? cardNetwork : "";
        if (nextBank.equals(this.bankName)
                && nextHolder.equals(this.holderName)
                && nextLast4.equals(this.last4)
                && nextExpiry.equals(this.expiry)
                && nextType.equals(this.cardType)
                && nextNetwork.equals(this.cardNetwork)) {
            return;
        }
        this.bankName = nextBank;
        this.holderName = nextHolder;
        this.last4 = nextLast4;
        this.expiry = nextExpiry;
        this.cardType = nextType;
        this.cardNetwork = nextNetwork;
        notifyItemRangeChanged(0, getItemCount());
    }

    @NonNull
    @Override
    public CardViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new CardViewHolder(Ui.inflateCarouselPage(parent, R.layout.item_bank_card));
    }

    @Override
    public void onBindViewHolder(@NonNull CardViewHolder holder, int position) {
        holder.cardRoot.setBackgroundResource(BankCardAdapter.backgroundAt(position));
        holder.bank.setText(bankName.isEmpty() ? "YOUR BANK" : bankName.toUpperCase(Locale.ROOT));
        holder.cardholder.setText(holderName.isEmpty() ? "CARDHOLDER NAME" : holderName.toUpperCase(Locale.ROOT));
        Context context = holder.itemView.getContext();
        holder.number.setText(context.getString(R.string.mask_card_number,
                last4.isEmpty() ? context.getString(R.string.mask_pin) : last4));
        holder.expiry.setText(expiry.isEmpty() ? "MM/YY" : expiry);
        applyNetworkLogo(holder.network, cardNetwork);
        holder.type.setText(cardType.isEmpty() ? "DEBIT" : cardType.toUpperCase(Locale.ROOT));
    }

    @Override
    public int getItemCount() {
        return getDesignCount();
    }

    public static void applyNetworkLogo(ImageView logoView, String network) {
        String name = network != null ? network.trim().toLowerCase(Locale.ROOT) : "visa";
        int icon;
        int heightDp;
        if ("mastercard".equals(name)) {
            icon = R.drawable.master_card;
            heightDp = 20;
        } else {
            icon = R.drawable.visa;
            heightDp = 11;
        }
        logoView.setImageResource(icon);
        float density = logoView.getResources().getDisplayMetrics().density;
        ViewGroup.LayoutParams params = logoView.getLayoutParams();
        params.height = Math.round(heightDp * density);
        logoView.setLayoutParams(params);
    }

    public static class CardViewHolder extends RecyclerView.ViewHolder {
        View cardRoot;
        TextView bank;
        ImageView network;
        TextView type;
        TextView number;
        TextView cardholder;
        TextView expiry;

        CardViewHolder(@NonNull View itemView) {
            super(itemView);
            cardRoot = itemView.findViewById(R.id.bank_card_root);
            bank = itemView.findViewById(R.id.bank_card_preview_bank_name);
            network = itemView.findViewById(R.id.bank_card_preview_network);
            type = itemView.findViewById(R.id.bank_card_preview_type);
            number = itemView.findViewById(R.id.bank_card_preview_number);
            cardholder = itemView.findViewById(R.id.bank_card_preview_holder_name);
            expiry = itemView.findViewById(R.id.bank_card_preview_expiry);
            Ui.applyCardOutline(cardRoot);
        }
    }
}
