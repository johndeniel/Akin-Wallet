package com.akin.wallet.model;

import java.util.Objects;

/**
 * Bank card entry (number, holder, expiry, security code, design).
 *
 * <p>Immutable value object shared by the dashboard carousel, the bank-card
 * form, and {@code bank_cards} persistence. Unsaved drafts carry
 * {@code id = -1} and {@code 0} timestamps; the database stamps real values
 * on insert.
 */
public final class BankCardModel {
    public static final int CARD_NUMBER_DIGITS = 16;

    /** Stored card numbers contain exactly 16 ASCII digits, including leading zeros. */
    public static boolean isValidCardNumber(String cardNumber) {
        if (cardNumber == null || cardNumber.length() != CARD_NUMBER_DIGITS) return false;
        for (int index = 0; index < cardNumber.length(); index++) {
            char digit = cardNumber.charAt(index);
            if (digit < '0' || digit > '9') return false;
        }
        return true;
    }

    /**
     * Row id for drafts that have never been persisted.
     */
    public static final int UNSET_ID = -1;

    private final long id;
    private final String cardType;
    private final String cardNetwork;
    private final String bankName;
    private final String holderName;
    private final String cardNumber;
    private final String expiry;
    private final String cvv;
    private final String pin;
    private final int design;
    private final long createdAt;
    private final long updatedAt;

    /**
     * Unsaved draft; the database assigns the id and timestamps on insert.
     */
    public BankCardModel(String cardType, String cardNetwork, String bankName, String holderName,
                         String cardNumber, String expiry, String cvv, String pin,
                         int design) {
        this(UNSET_ID, cardType, cardNetwork, bankName, holderName, cardNumber,
                expiry, cvv, pin, design, 0, 0);
    }

    /**
     * Stored row with its database identity and audit timestamps.
     */
    public BankCardModel(long id, String cardType, String cardNetwork, String bankName,
                         String holderName, String cardNumber, String expiry,
                         String cvv, String pin, int design,
                         long createdAt, long updatedAt) {
        this.id = id;
        this.cardType = cardType;
        this.cardNetwork = cardNetwork;
        this.bankName = bankName;
        this.holderName = holderName;
        this.cardNumber = cardNumber;
        this.expiry = expiry;
        this.cvv = cvv;
        this.pin = pin;
        this.design = design;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public long getId() {
        return id;
    }

    public String getCardType() {
        return cardType;
    }

    public String getCardNetwork() {
        return cardNetwork;
    }

    public String getBankName() {
        return bankName;
    }

    public String getHolderName() {
        return holderName;
    }

    public String getCardNumber() {
        return cardNumber;
    }

    public String getExpiry() {
        return expiry;
    }

    public String getCvv() {
        return cvv;
    }

    public String getPin() {
        return pin;
    }

    public int getDesign() {
        return design;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Value equality across every column (backs DiffUtil content checks).
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BankCardModel)) {
            return false;
        }
        BankCardModel that = (BankCardModel) o;
        return id == that.id
                && design == that.design
                && createdAt == that.createdAt
                && updatedAt == that.updatedAt
                && Objects.equals(cardType, that.cardType)
                && Objects.equals(cardNetwork, that.cardNetwork)
                && Objects.equals(bankName, that.bankName)
                && Objects.equals(holderName, that.holderName)
                && Objects.equals(cardNumber, that.cardNumber)
                && Objects.equals(expiry, that.expiry)
                && Objects.equals(cvv, that.cvv)
                && Objects.equals(pin, that.pin);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, cardType, cardNetwork, bankName, holderName,
                cardNumber, expiry, cvv, pin, design, createdAt, updatedAt);
    }
}
