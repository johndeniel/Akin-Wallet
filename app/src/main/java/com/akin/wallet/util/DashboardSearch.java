package com.akin.wallet.util;

import com.akin.wallet.model.BankCardModel;
import com.akin.wallet.model.GovernmentIDModel;
import com.akin.wallet.model.SocialAccountModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Dashboard search index: masters are normalized once per refresh,
 * each keystroke scans pre-lowered strings. Secrets never indexed.
 */
public final class DashboardSearch {

    private DashboardSearch() {
    }

    private static final Pattern NON_DIGITS = Pattern.compile("\\D");

    /** Normalized query: lowered text plus digit-only form for card numbers. */
    public static final class Query {
        public final String text;
        public final String digits;
        public final boolean empty;

        Query(String text, String digits) {
            this.text = text;
            this.digits = digits;
            this.empty = text.isEmpty();
        }
    }

    /** Normalizes raw user input once per keystroke (not once per row). */
    public static Query normalizeQuery(String raw) {
        if (raw == null) {
            return new Query("", "");
        }
        String text = raw.trim().toLowerCase(Locale.US);
        String digits = NON_DIGITS.matcher(text).replaceAll("");
        return new Query(text, digits);
    }

    /** Lowered, trimmed haystack or "" when blank (never null). */
    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        return trimmed.toLowerCase(Locale.US);
    }

    static String digitsOf(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return NON_DIGITS.matcher(value).replaceAll("");
    }

    /** One account + its pre-lowered searchable fields (platform, username). */
    public static final class PreparedAccount {
        final SocialAccountModel model;
        final String platform;
        final String username;

        PreparedAccount(SocialAccountModel model) {
            this.model = model;
            this.platform = normalize(model != null ? model.getPlatform() : null);
            this.username = normalize(model != null ? model.getUsername() : null);
        }
    }

    /** One card + pre-lowered fields. Card number stored digits-only. */
    public static final class PreparedCard {
        final BankCardModel model;
        final String bank;
        final String holder;
        final String type;
        final String network;
        final String numberDigits;

        PreparedCard(BankCardModel model) {
            this.model = model;
            this.bank = normalize(model != null ? model.getBankName() : null);
            this.holder = normalize(model != null ? model.getHolderName() : null);
            this.type = normalize(model != null ? model.getCardType() : null);
            this.network = normalize(model != null ? model.getCardNetwork() : null);
            this.numberDigits = digitsOf(model != null ? model.getCardNumber() : null);
        }
    }

    /** One ID + pre-lowered type and field values. */
    public static final class PreparedId {
        final GovernmentIDModel model;
        final String type;
        final List<String> fieldValues;

        PreparedId(GovernmentIDModel model) {
            this.model = model;
            this.type = normalize(model != null ? model.getIdType() : null);
            if (model != null) {
                Map<String, String> fields;
                try {
                    fields = model.getFieldsRef();
                } catch (RuntimeException e) {
                    fields = Collections.emptyMap();
                }
                List<String> lowered = new ArrayList<>(fields.size());
                for (String value : fields.values()) {
                    String n = normalize(value);
                    if (!n.isEmpty()) {
                        lowered.add(n);
                    }
                }
                this.fieldValues = Collections.unmodifiableList(lowered);
            } else {
                this.fieldValues = Collections.emptyList();
            }
        }
    }

    /** Immutable snapshot built once per masters refresh. */
    public static final class Index {
        final List<PreparedId> ids;
        final List<PreparedCard> cards;
        final List<PreparedAccount> accounts;
        final List<GovernmentIDModel> rawIds;
        final List<BankCardModel> rawCards;
        final List<SocialAccountModel> rawAccounts;

        Index(List<PreparedId> ids,
              List<PreparedCard> cards,
              List<PreparedAccount> accounts,
              List<GovernmentIDModel> rawIds,
              List<BankCardModel> rawCards,
              List<SocialAccountModel> rawAccounts) {
            this.ids = ids;
            this.cards = cards;
            this.accounts = accounts;
            this.rawIds = rawIds;
            this.rawCards = rawCards;
            this.rawAccounts = rawAccounts;
        }
    }

    public static final Index EMPTY_INDEX = new Index(
            Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
            Collections.emptyList(), Collections.emptyList(), Collections.emptyList());

    /** Builds the index; null lists become empty. Never throws on bad rows. */
    public static Index buildIndex(List<GovernmentIDModel> ids,
                                   List<BankCardModel> cards,
                                   List<SocialAccountModel> accounts) {
        List<GovernmentIDModel> safeIds = ids != null ? ids : Collections.emptyList();
        List<BankCardModel> safeCards = cards != null ? cards : Collections.emptyList();
        List<SocialAccountModel> safeAccounts = accounts != null ? accounts : Collections.emptyList();

        List<PreparedId> pIds = new ArrayList<>(safeIds.size());
        List<GovernmentIDModel> cleanIds = new ArrayList<>(safeIds.size());
        for (GovernmentIDModel id : safeIds) {
            if (id == null) {
                continue;
            }
            try {
                pIds.add(new PreparedId(id));
                cleanIds.add(id);
            } catch (RuntimeException ignored) {
                // One corrupt row never poisons the whole index.
            }
        }
        List<PreparedCard> pCards = new ArrayList<>(safeCards.size());
        List<BankCardModel> cleanCards = new ArrayList<>(safeCards.size());
        for (BankCardModel card : safeCards) {
            if (card == null) {
                continue;
            }
            try {
                pCards.add(new PreparedCard(card));
                cleanCards.add(card);
            } catch (RuntimeException ignored) {
            }
        }
        List<PreparedAccount> pAccounts = new ArrayList<>(safeAccounts.size());
        List<SocialAccountModel> cleanAccounts = new ArrayList<>(safeAccounts.size());
        for (SocialAccountModel account : safeAccounts) {
            if (account == null) {
                continue;
            }
            try {
                pAccounts.add(new PreparedAccount(account));
                cleanAccounts.add(account);
            } catch (RuntimeException ignored) {
            }
        }
        return new Index(
                Collections.unmodifiableList(pIds),
                Collections.unmodifiableList(pCards),
                Collections.unmodifiableList(pAccounts),
                Collections.unmodifiableList(cleanIds),
                Collections.unmodifiableList(cleanCards),
                Collections.unmodifiableList(cleanAccounts));
    }

    /** Filtered models for one query (new lists, original order preserved). */
    public static final class Result {
        public final List<GovernmentIDModel> ids;
        public final List<BankCardModel> cards;
        public final List<SocialAccountModel> accounts;

        Result(List<GovernmentIDModel> ids,
               List<BankCardModel> cards,
               List<SocialAccountModel> accounts) {
            this.ids = ids;
            this.cards = cards;
            this.accounts = accounts;
        }
    }

    /**
     * Filters the index. Empty query returns all rows newest-first (input
     * order). Non-secret fields only: password/PIN/CVV are not in the index.
     */
    public static Result search(Index index, Query query) {
        if (index == null) {
            return new Result(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }
        if (query == null || query.empty) {
            return new Result(
                    new ArrayList<>(index.rawIds),
                    new ArrayList<>(index.rawCards),
                    new ArrayList<>(index.rawAccounts));
        }
        String q = query.text;
        String digits = query.digits;

        List<GovernmentIDModel> outIds = new ArrayList<>();
        for (PreparedId entry : index.ids) {
            if (entry.type.contains(q)) {
                outIds.add(entry.model);
                continue;
            }
            for (String field : entry.fieldValues) {
                if (field.contains(q)) {
                    outIds.add(entry.model);
                    break;
                }
            }
        }

        List<BankCardModel> outCards = new ArrayList<>();
        boolean hasDigits = !digits.isEmpty();
        for (PreparedCard entry : index.cards) {
            if (entry.bank.contains(q)
                    || entry.holder.contains(q)
                    || entry.type.contains(q)
                    || entry.network.contains(q)
                    || (hasDigits && !entry.numberDigits.isEmpty()
                        && entry.numberDigits.contains(digits))) {
                outCards.add(entry.model);
            }
        }

        List<SocialAccountModel> outAccounts = new ArrayList<>();
        for (PreparedAccount entry : index.accounts) {
            if (entry.platform.contains(q) || entry.username.contains(q)) {
                outAccounts.add(entry.model);
            }
        }
        return new Result(outIds, outCards, outAccounts);
    }
}
