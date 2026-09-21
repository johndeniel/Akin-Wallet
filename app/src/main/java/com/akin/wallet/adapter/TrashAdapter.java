package com.akin.wallet.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.akin.wallet.R;
import com.akin.wallet.model.BankCardModel;
import com.akin.wallet.model.SocialAccountModel;
import com.akin.wallet.model.GovernmentIDModel;
import com.akin.wallet.model.SocialPlatformModel;
import com.akin.wallet.util.CardText;
import com.google.android.material.card.MaterialCardView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Trash rows as one uniform set of selectable tiles (centered icon well +
 * title + masked hint, paired two-per-row). No real card faces or sensitive
 * values are shown. Tapping a tile toggles its selection (check badge +
 * blue stroke); headers never select. The host reads
 * {@link #selectedEntries()} for bulk restore / delete.
 */
public class TrashAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public static final int TYPE_HEADER = 0;
    public static final int TYPE_TILE = 1;

    public static final int KIND_HEADER = -1;
    public static final int KIND_ID = 0;
    public static final int KIND_CARD = 1;
    public static final int KIND_SOCIAL = 2;

    /** One trash row: a section header or a selectable trashed item. */
    public static final class Entry {
        public final int kind;
        public final boolean header;
        public final String headerTitle;
        public final int headerCount;
        public final GovernmentIDModel idCard;
        public final BankCardModel bankCard;
        public final SocialAccountModel socialAccount;

        private Entry(int kind, boolean header, String headerTitle, int headerCount,
                      GovernmentIDModel idCard, BankCardModel bankCard, SocialAccountModel socialAccount) {
            this.kind = kind;
            this.header = header;
            this.headerTitle = headerTitle;
            this.headerCount = headerCount;
            this.idCard = idCard;
            this.bankCard = bankCard;
            this.socialAccount = socialAccount;
        }

        public static Entry header(String title, int count) {
            return new Entry(KIND_HEADER, true, title, count, null, null, null);
        }

        public static Entry id(GovernmentIDModel item) {
            return new Entry(KIND_ID, false, null, 0, item, null, null);
        }

        public static Entry card(BankCardModel item) {
            return new Entry(KIND_CARD, false, null, 0, null, item, null);
        }

        public static Entry social(SocialAccountModel item) {
            return new Entry(KIND_SOCIAL, false, null, 0, null, null, item);
        }

        public boolean isSelectable() {
            return !header;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Entry)) {
                return false;
            }
            Entry that = (Entry) o;
            if (header != that.header || kind != that.kind) {
                return false;
            }
            if (header) {
                return headerCount == that.headerCount
                        && java.util.Objects.equals(headerTitle, that.headerTitle);
            }
            switch (kind) {
                case KIND_ID:
                    return java.util.Objects.equals(idCard, that.idCard);
                case KIND_CARD:
                    return java.util.Objects.equals(bankCard, that.bankCard);
                case KIND_SOCIAL:
                    return java.util.Objects.equals(socialAccount, that.socialAccount);
                default:
                    return false;
            }
        }

        @Override
        public int hashCode() {
            if (header) {
                return java.util.Objects.hash(kind, headerTitle, headerCount);
            }
            Object item = kind == KIND_ID ? idCard : kind == KIND_CARD ? bankCard : socialAccount;
            return java.util.Objects.hash(kind, item);
        }

        /** Stable selection key across reloads (kind + row id). */
        public String key() {
            if (header) {
                return "header:" + headerTitle;
            }
            switch (kind) {
                case KIND_ID:
                    return "id:" + (idCard != null ? idCard.getId() : -1);
                case KIND_CARD:
                    return "card:" + (bankCard != null ? bankCard.getId() : -1);
                case KIND_SOCIAL:
                    return "social:" + (socialAccount != null ? socialAccount.getId() : -1);
                default:
                    return "header:" + headerTitle;
            }
        }
    }

    public interface OnSelectionChangedListener {
        void onSelectionChanged(int selectedCount, int selectableCount);
    }

    private final List<Entry> entries = new ArrayList<>();
    private final Set<String> selectedKeys = new HashSet<>();
    private OnSelectionChangedListener selectionListener;

    public void setOnSelectionChangedListener(OnSelectionChangedListener listener) {
        this.selectionListener = listener;
    }

    public void updateData(List<Entry> newEntries) {
        List<Entry> next = newEntries != null ? new ArrayList<>(newEntries) : new ArrayList<>();
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return entries.size();
            }

            @Override
            public int getNewListSize() {
                return next.size();
            }

            @Override
            public boolean areItemsTheSame(int oldPos, int newPos) {
                return entries.get(oldPos).key().equals(next.get(newPos).key());
            }

            @Override
            public boolean areContentsTheSame(int oldPos, int newPos) {
                return entries.get(oldPos).equals(next.get(newPos));
            }
        });
        entries.clear();
        entries.addAll(next);
        if (!selectedKeys.isEmpty()) {
            selectedKeys.retainAll(selectableKeys());
        }
        diff.dispatchUpdatesTo(this);
        emitSelection();
    }

    /** Selection keys for rotation save/restore. */
    @NonNull
    public ArrayList<String> saveSelection() {
        return new ArrayList<>(selectedKeys);
    }

    public void restoreSelection(List<String> keys) {
        selectedKeys.clear();
        if (keys != null) {
            for (String key : keys) {
                if (key != null) {
                    selectedKeys.add(key);
                }
            }
        }
        selectedKeys.retainAll(selectableKeys());
        for (int position = 0; position < entries.size(); position++) {
            if (selectedKeys.contains(entries.get(position).key())) {
                notifyItemChanged(position);
            }
        }
        emitSelection();
    }

    /** Currently selected entries, in display order. */
    public List<Entry> selectedEntries() {
        List<Entry> selected = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.isSelectable() && selectedKeys.contains(entry.key())) {
                selected.add(entry);
            }
        }
        return selected;
    }

    public int getSelectedCount() {
        return selectedKeys.size();
    }

    public int getSelectableCount() {
        int count = 0;
        for (Entry entry : entries) {
            if (entry.isSelectable()) {
                count++;
            }
        }
        return count;
    }

    public void clearSelection() {
        if (selectedKeys.isEmpty()) {
            return;
        }
        selectedKeys.clear();
        notifySelectableChanged();
        emitSelection();
    }

    public void selectAll() {
        for (Entry entry : entries) {
            if (entry.isSelectable()) {
                selectedKeys.add(entry.key());
            }
        }
        notifySelectableChanged();
        emitSelection();
    }

    private void notifySelectableChanged() {
        for (int position = 0; position < entries.size(); position++) {
            if (entries.get(position).isSelectable()) {
                notifyItemChanged(position);
            }
        }
    }

    private Set<String> selectableKeys() {
        Set<String> keys = new HashSet<>();
        for (Entry entry : entries) {
            if (entry.isSelectable()) {
                keys.add(entry.key());
            }
        }
        return keys;
    }

    private void emitSelection() {
        if (selectionListener != null) {
            selectionListener.onSelectionChanged(getSelectedCount(), getSelectableCount());
        }
    }

    @Override
    public int getItemViewType(int position) {
        if (position < 0 || position >= entries.size()) {
            return TYPE_HEADER;
        }
        return entries.get(position).header ? TYPE_HEADER : TYPE_TILE;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_HEADER) {
            return new HeaderHolder(inflater.inflate(R.layout.item_trash_header, parent, false));
        } else {
            return new TileHolder(inflater.inflate(R.layout.item_trash_tile, parent, false));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Entry entry = entries.get(position);
        if (holder instanceof HeaderHolder) {
            bindHeader((HeaderHolder) holder, entry);
        } else {
            bindTile((TileHolder) holder, entry);
        }
    }

    private static void bindHeader(HeaderHolder header, Entry entry) {
        header.title.setText(entry.headerTitle);
        header.count.setText(header.itemView.getContext()
                .getString(R.string.trash_header_count, entry.headerCount));
    }

    private void bindTile(TileHolder tile, Entry entry) {
        if (entry.kind == KIND_ID && entry.idCard != null) {
            bindIdCard(tile, entry.idCard);
        } else if (entry.kind == KIND_CARD && entry.bankCard != null) {
            bindBankCard(tile, entry.bankCard);
        } else if (entry.socialAccount != null) {
            bindSocialAccount(tile, entry.socialAccount);
        }
        applySelection(tile, entry);
    }

    private static void bindIdCard(TileHolder tile, GovernmentIDModel idCard) {
        Context context = tile.itemView.getContext();
        tile.icon.setImageResource(R.drawable.ic_person);
        String rawType = idCard.getIdType().trim();
        tile.title.setText(rawType.isEmpty()
                ? context.getString(R.string.trash_section_ids) : rawType);
        tile.sub.setText(GovernmentIDModel.displayNumber(faceSpec(idCard), idCard.getFields()));
    }

    private static GovernmentIDModel.IdType faceSpec(GovernmentIDModel idCard) {
        // Unknown types render through the generic face, never masquerading
        // as another document.
        return GovernmentIDModel.isKnownType(idCard.getIdType())
                ? GovernmentIDModel.forName(idCard.getIdType())
                : GovernmentIDModel.genericType(idCard.getIdType(), idCard.getFields());
    }

    private static void bindBankCard(TileHolder tile, BankCardModel bankCard) {
        tile.icon.setImageResource(R.drawable.chip);
        tile.title.setText(cardTitle(tile.itemView.getContext(), bankCard));
        tile.sub.setText(tile.itemView.getContext().getString(R.string.mask_card_last4,
                CardText.last4(bankCard.getCardNumber())));
    }

    private static void bindSocialAccount(TileHolder tile, SocialAccountModel socialAccount) {
        Context context = tile.itemView.getContext();
        String fallback = context.getString(R.string.label_social_account);
        String platform = socialAccount.getPlatform() != null
                && !socialAccount.getPlatform().trim().isEmpty()
                ? socialAccount.getPlatform().trim() : fallback;
        String username = socialAccount.getUsername() != null
                ? socialAccount.getUsername().trim() : "";
        tile.title.setText(platform);
        tile.sub.setText(username.isEmpty() ? fallback : username);
        SocialPlatformModel.bindIcon(tile.icon, socialAccount.getPlatform(),
                socialAccount.getIconRes());
    }

    private void applySelection(TileHolder tile, Entry entry) {
        MaterialCardView card = tile.card;
        boolean selected = selectedKeys.contains(entry.key());
        tile.badge.setVisibility(selected ? View.VISIBLE : View.GONE);
        androidx.core.view.ViewCompat.setStateDescription(card, selected
                ? card.getContext().getString(R.string.state_selected) : null);
        float density = card.getResources().getDisplayMetrics().density;
        if (selected) {
            card.setStrokeColor(card.getContext().getColor(R.color.brand_blue));
            card.setStrokeWidth(Math.round(2 * density));
        } else {
            card.setStrokeColor(card.getContext().getColor(R.color.dashboard_surface_border));
            card.setStrokeWidth(Math.round(1 * density));
        }
        card.setOnClickListener(v -> {
            // Bind-time positions go stale after reloads; resolve at click time.
            int clicked = tile.getBindingAdapterPosition();
            if (clicked < 0 || clicked >= entries.size()) {
                return;
            }
            String key = entries.get(clicked).key();
            if (selectedKeys.contains(key)) {
                selectedKeys.remove(key);
            } else {
                selectedKeys.add(key);
            }
            notifyItemChanged(clicked);
            emitSelection();
        });
    }

    @Override
    public int getItemCount() {
        return entries.size();
    }

    private static String cardTitle(Context context, BankCardModel card) {
        String bank = card.getBankName() != null ? card.getBankName().trim() : "";
        String type = card.getCardType() != null ? card.getCardType().trim() : "";
        if (!bank.isEmpty() && !type.isEmpty()) {
            return bank + " " + type;
        }
        if (!bank.isEmpty()) {
            return bank;
        }
        if (!type.isEmpty()) {
            return type;
        }
        return context.getString(R.string.trash_section_cards);
    }

    public static class HeaderHolder extends RecyclerView.ViewHolder {
        TextView title;
        TextView count;

        HeaderHolder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.trash_section_title);
            count = itemView.findViewById(R.id.trash_section_count);
        }
    }

    public static class TileHolder extends RecyclerView.ViewHolder {
        MaterialCardView card;
        View badge;
        ImageView icon;
        TextView title;
        TextView sub;

        TileHolder(@NonNull View itemView) {
            super(itemView);
            card = itemView.findViewById(R.id.trash_tile_card);
            badge = itemView.findViewById(R.id.trash_tile_selection_badge);
            icon = itemView.findViewById(R.id.trash_tile_icon);
            title = itemView.findViewById(R.id.trash_tile_title);
            sub = itemView.findViewById(R.id.trash_tile_subtitle);
        }
    }
}
