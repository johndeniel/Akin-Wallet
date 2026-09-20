package com.akin.wallet.util;

/**
 * Bank-card face text rules shared by the dashboard carousel and trash.
 * Single source so masked numbers and expiry render identically
 * everywhere.
 */
public final class CardText {

    private CardText() {
    }

    private static final java.util.regex.Pattern NON_DIGITS =
            java.util.regex.Pattern.compile("\\D");

    /** Trimmed value or the fallback when blank (face placeholders). */
    public static String safe(String value, String fallback) {
        return value != null && !value.trim().isEmpty() ? value.trim() : fallback;
    }

    /** Last 4 digits of a raw card number, masked fallback when absent. */
    public static String last4(String number) {
        if (number == null) {
            return "••••";
        }
        String digits = NON_DIGITS.matcher(number).replaceAll("");
        if (digits.isEmpty()) {
            return "••••";
        }
        return digits.length() > 4 ? digits.substring(digits.length() - 4) : digits;
    }

    /** Raw MMYY digits as display MM/YY; placeholder when absent. */
    public static String formatExpiry(String expiry) {
        if (expiry == null) {
            return "MM/YY";
        }
        String digits = NON_DIGITS.matcher(expiry).replaceAll("");
        if (digits.length() == 4) {
            return digits.substring(0, 2) + "/" + digits.substring(2);
        }
        return digits.isEmpty() ? "MM/YY" : digits;
    }
}
