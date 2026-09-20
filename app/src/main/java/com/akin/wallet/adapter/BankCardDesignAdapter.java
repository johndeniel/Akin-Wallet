package com.akin.wallet.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.akin.wallet.R;

/**
 * Bank Card design picker for the Bank Card screen: the five authentic
 * faces in the shared dashboard card, swiped and snapped like Home. Typing
 * previews live on every face.
 */

public class BankCardDesignAdapter extends RecyclerView.Adapter<BankCardDesignAdapter.CardViewHolder> {

    private String bankName = "";
    private String holderName = "";
    private String last4 = "";
    private String expiry = "";
    private String cardType = "Debit";
    private String cardNetwork = "Visa";

    public int getDesignCount() {
        return BankCardAdapter.BACKGROUNDS.length;
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
            // Typing that changes nothing visible (e.g. beyond max length)
            // skips the full carousel rebind.
            return;
        }
        this.bankName = nextBank;
        this.holderName = nextHolder;
        this.last4 = nextLast4;
        this.expiry = nextExpiry;
        this.cardType = nextType;
        this.cardNetwork = nextNetwork;
        // Every face shows the same preview text: repaint the fixed page set,
        // not the whole list pipeline.
        notifyItemRangeChanged(0, getDesignCount());
    }

    @NonNull
    @Override
    public CardViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // Reuse the exact bank card item (item_bank_card) so the form picker looks identical to the
        // dashboard carousel. Same 0.68 page-width ratio for same size + peek.
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_bank_card, parent, false);
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
        return new CardViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull CardViewHolder holder, int position) {
        applyCardOutline(holder.cardRoot);
        holder.cardRoot.setBackgroundResource(BankCardAdapter.BACKGROUNDS[position]);
        holder.bank.setText(bankName.isEmpty() ? "YOUR BANK" : bankName.toUpperCase(java.util.Locale.ROOT));
        holder.cardholder.setText(holderName.isEmpty() ? "CARDHOLDER NAME" : holderName.toUpperCase(java.util.Locale.ROOT));
        android.content.Context context = holder.itemView.getContext();
        holder.number.setText(context.getString(R.string.mask_card_number,
                last4.isEmpty() ? context.getString(R.string.mask_pin) : last4));
        holder.expiry.setText(expiry.isEmpty() ? "MM/YY" : expiry);
        applyNetworkLogo(holder.network, cardNetwork);
        holder.type.setText(cardType.isEmpty() ? "DEBIT" : cardType.toUpperCase(java.util.Locale.ROOT));
    }

    @Override
    public int getItemCount() {
        return BankCardAdapter.BACKGROUNDS.length;
    }

    /** One outline provider for every card: radius resolves per view, no per-bind allocation. */
    private static final android.view.ViewOutlineProvider CARD_OUTLINE =
            new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    float density = view.getResources().getDisplayMetrics().density;
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                            16 * density);
                }
            };

    /** Shared rounded-clip outline for every card face (dashboard, pickers, ID faces). */
    public static void applyCardOutline(View cardRoot) {
        if (cardRoot.getOutlineProvider() != CARD_OUTLINE) {
            cardRoot.setOutlineProvider(CARD_OUTLINE);
            cardRoot.setClipToOutline(true);
        }
    }

    /** Network keys are lowercase: names are lowercased before comparison. */
    private static final String NETWORK_VISA = "visa";

    static void applyNetworkLogo(ImageView logoView, String network) {
        String name = network != null ? network.trim().toLowerCase(java.util.Locale.ROOT) : NETWORK_VISA;
        int icon;
        int heightDp;
        if ("MasterCard".equalsIgnoreCase(name)) {
            icon = R.drawable.master_card;
            heightDp = 20;
        } else {
            icon = R.drawable.visa;
            heightDp = 11;
        }
        // setLayoutParams triggers a full measure/layout pass: only pay it
        // when the logo or its height actually changed.
        Object tag = logoView.getTag(com.akin.wallet.R.id.tag_network);
        int key = icon * 100 + heightDp;
        if (Integer.valueOf(key).equals(tag)) {
            return;
        }
        logoView.setImageResource(icon);
        float density = logoView.getResources().getDisplayMetrics().density;
        android.view.ViewGroup.LayoutParams params = logoView.getLayoutParams();
        params.height = Math.round(heightDp * density);
        logoView.setLayoutParams(params);
        logoView.setTag(com.akin.wallet.R.id.tag_network, key);
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
            bank = itemView.findViewById(R.id.bank_preview_bank);
            network = itemView.findViewById(R.id.bank_preview_network);
            type = itemView.findViewById(R.id.bank_preview_type);
            number = itemView.findViewById(R.id.bank_preview_number);
            cardholder = itemView.findViewById(R.id.bank_preview_holder);
            expiry = itemView.findViewById(R.id.bank_preview_expiry);
        }
    }
}
