package com.akin.wallet.adapter;

import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.akin.wallet.R;
import com.akin.wallet.util.Ui;
import com.akin.wallet.model.BankCardModel;
import com.akin.wallet.util.CardText;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Bank card carousel. Tap opens the editor. */
public class BankCardAdapter extends RecyclerView.Adapter<BankCardAdapter.CardViewHolder> {

    /** Design index -> background. Order is persisted, never reorder. */
    private static final int[] BACKGROUNDS = {
            R.drawable.bg_bank_card_blue,
            R.drawable.bg_bank_card_purple,
            R.drawable.bg_bank_card_green,
            R.drawable.bg_bank_card_orange,
            R.drawable.bg_bank_card_slate
    };

    public static int designCount() {
        return BACKGROUNDS.length;
    }

    public static int backgroundAt(int design) {
        if (design < 0 || design >= BACKGROUNDS.length) {
            return BACKGROUNDS[0];
        }
        return BACKGROUNDS[design];
    }

    public interface OnCardClickListener {
        void onCardClick(BankCardModel item);
    }

    private final List<BankCardModel> cards = new ArrayList<>();
    private final OnCardClickListener listener;

    public BankCardAdapter(OnCardClickListener listener) {
        this.listener = listener;
    }

    public void updateData(List<BankCardModel> newCards) {
        List<BankCardModel> next = Ui.nonNullList(newCards);
        List<BankCardModel> old = new ArrayList<>(cards);
        cards.clear();
        cards.addAll(next);
        Ui.calculateDiff(old, next,
                (a, b) -> a.getId() == b.getId(),
                Object::equals).dispatchUpdatesTo(this);
    }

    @NonNull
    @Override
    public CardViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new CardViewHolder(Ui.inflateCarouselPage(parent, R.layout.item_bank_card), listener);
    }

    @Override
    public void onBindViewHolder(@NonNull CardViewHolder holder, int position) {
        holder.bind(cards.get(position));
    }

    @Override
    public int getItemCount() {
        return cards.size();
    }

    public static class CardViewHolder extends RecyclerView.ViewHolder {
        View cardRoot;
        TextView bank;
        ImageView network;
        TextView type;
        TextView number;
        TextView cardholder;
        TextView expiry;
        private BankCardModel bound;

        CardViewHolder(@NonNull View itemView, OnCardClickListener listener) {
            super(itemView);
            cardRoot = itemView.findViewById(R.id.bank_card_root);
            bank = itemView.findViewById(R.id.bank_card_preview_bank_name);
            network = itemView.findViewById(R.id.bank_card_preview_network);
            type = itemView.findViewById(R.id.bank_card_preview_type);
            number = itemView.findViewById(R.id.bank_card_preview_number);
            cardholder = itemView.findViewById(R.id.bank_card_preview_holder_name);
            expiry = itemView.findViewById(R.id.bank_card_preview_expiry);
            Ui.applyCardOutline(cardRoot);
            itemView.setOnClickListener(v -> {
                if (listener != null && bound != null) {
                    listener.onCardClick(bound);
                }
            });
        }

        void bind(BankCardModel card) {
            bound = card;
            int design = card.getDesign();
            if (design < 0 || design >= designCount()) {
                design = 0;
            }
            cardRoot.setBackgroundResource(backgroundAt(design));
            bank.setText(CardText.safe(card.getBankName(), "YOUR BANK").toUpperCase(Locale.ROOT));
            cardholder.setText(CardText.safe(card.getHolderName(), "CARDHOLDER NAME").toUpperCase(Locale.ROOT));
            number.setText(itemView.getContext().getString(R.string.mask_card_number,
                    CardText.last4(card.getCardNumber())));
            expiry.setText(CardText.formatExpiry(card.getExpiry()));
            BankCardDesignAdapter.applyNetworkLogo(network, card.getCardNetwork());
            type.setText(CardText.safe(card.getCardType(), "DEBIT").toUpperCase(Locale.ROOT));
        }
    }
}
