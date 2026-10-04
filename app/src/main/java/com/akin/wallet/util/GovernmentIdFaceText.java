package com.akin.wallet.util;

import com.akin.wallet.R;

import java.util.*;

import static com.akin.wallet.model.GovernmentIdTypes.*;

/**
 * Document-face colors and display formatting, separate from stored entries.
 */
public final class GovernmentIdFaceText {
    private GovernmentIdFaceText() {
    }

    private static final String EMPTY_FACE_VALUE = "—";
    private static final java.util.regex.Pattern NON_DIGITS =
            java.util.regex.Pattern.compile("\\D");
    private static final java.util.regex.Pattern SEPARATORS =
            java.util.regex.Pattern.compile("[\\s-]");

    public static String previewSubtitle(String typeName) {
        if (matchesType(typeName, TYPE_NATIONAL_ID)) {
            return "PHILIPPINE IDENTIFICATION";
        }
        if (matchesType(typeName, TYPE_DRIVING_LICENSE)) {
            return "LAND TRANSPORT OFFICE";
        }
        if (matchesType(typeName, TYPE_PASSPORT)) {
            return "DEPARTMENT OF FOREIGN AFFAIRS";
        }
        if (matchesType(typeName, TYPE_SSS)) {
            return "SOCIAL SECURITY SYSTEM";
        }
        if (matchesType(typeName, TYPE_PHIL_HEALTH)) {
            return "PhilHealth".toUpperCase(java.util.Locale.ROOT);
        }
        if (matchesType(typeName, TYPE_TIN)) {
            return "BUREAU OF INTERNAL REVENUE";
        }
        return "GOVERNMENT ID";
    }

    /**
     * Face label for the primary number, per type (PSN, LICENSE NO., …).
     */
    public static String numberLabel(String typeName) {
        IdType spec = forName(typeName);
        String numberKey = spec.numberKey;
        if ("psn".equalsIgnoreCase(numberKey)) {
            return "PSN";
        }
        if ("license_no".equalsIgnoreCase(numberKey)) {
            return "LICENSE NO.";
        }
        if ("passport_no".equalsIgnoreCase(numberKey)) {
            return "PASSPORT NO.";
        }
        if ("ss_number".equalsIgnoreCase(numberKey)) {
            return "SS NUMBER";
        }
        if ("philHealthNumber".equalsIgnoreCase(numberKey)) {
            return "PhilHealth No.".toUpperCase(java.util.Locale.ROOT);
        }
        if ("tinNumber".equalsIgnoreCase(numberKey)) {
            return "TIN";
        }
        if ("id_number".equalsIgnoreCase(numberKey)) {
            return "ID NUMBER";
        }
        return toFieldLabel(numberKey).toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * Face theming for the single shared preview layout. The design (which
     * background + ink) varies per ID type, but the XML stays the same, so
     * adding a type never needs a new layout. Unknown types reuse National.
     */
    public static class FaceScheme {
        public final int backgroundRes;
        public final int titleColorRes;
        public final int subtitleColorRes;
        public final int ruleColorRes;
        public final int holderColorRes;
        public final int numberLabelColorRes;
        public final int numberColorRes;
        public final int photoBgRes;
        public final int photoBorderRes;
        public final int photoIconRes;

        FaceScheme(int backgroundRes, int titleColorRes, int subtitleColorRes,
                   int ruleColorRes, int holderColorRes, int numberLabelColorRes,
                   int numberColorRes,
                   int photoBgRes, int photoBorderRes, int photoIconRes) {
            this.backgroundRes = backgroundRes;
            this.titleColorRes = titleColorRes;
            this.subtitleColorRes = subtitleColorRes;
            this.ruleColorRes = ruleColorRes;
            this.holderColorRes = holderColorRes;
            this.numberLabelColorRes = numberLabelColorRes;
            this.numberColorRes = numberColorRes;
            this.photoBgRes = photoBgRes;
            this.photoBorderRes = photoBorderRes;
            this.photoIconRes = photoIconRes;
        }
    }

    public static FaceScheme faceScheme(String typeName) {
        if (matchesType(typeName, TYPE_DRIVING_LICENSE)) {
            return new FaceScheme(
                    R.drawable.bg_driving_license,
                    R.color.dl_ink, R.color.dl_accent, R.color.dl_bar,
                    R.color.dl_ink, R.color.dl_muted,
                    R.color.dl_ink,
                    R.color.photo_bg_dl, R.color.dl_bar, R.color.dl_ink);
        }
        if (matchesType(typeName, TYPE_PASSPORT)) {
            return new FaceScheme(
                    R.drawable.bg_passport,
                    R.color.passport_ink, R.color.passport_muted, R.color.passport_rule,
                    R.color.passport_ink, R.color.passport_muted,
                    R.color.passport_ink,
                    R.color.photo_bg_passport, R.color.passport_rule,
                    R.color.photo_icon_passport);
        }
        if (matchesType(typeName, TYPE_SSS)) {
            return new FaceScheme(
                    R.drawable.bg_sss,
                    R.color.text_primary, R.color.sss_muted, R.color.sss_muted,
                    R.color.text_primary, R.color.sss_muted,
                    R.color.text_primary,
                    R.color.photo_bg_sss, R.color.sss_bg_start, R.color.sss_bg_end);
        }
        if (matchesType(typeName, TYPE_PHIL_HEALTH)) {
            return new FaceScheme(
                    R.drawable.bg_phil_health,
                    R.color.phil_health_ink, R.color.phil_health_muted, R.color.phil_health_rule,
                    R.color.phil_health_ink, R.color.phil_health_muted,
                    R.color.phil_health_ink,
                    R.color.photo_bg_phil_health, R.color.phil_health_rule,
                    R.color.phil_health_ink);
        }
        if (matchesType(typeName, TYPE_TIN)) {
            return new FaceScheme(
                    R.drawable.bg_tin,
                    R.color.tin_ink, R.color.tin_ink, R.color.tin_rule,
                    R.color.tin_ink, R.color.tin_ink,
                    R.color.tin_ink,
                    R.color.photo_bg_tin, R.color.tin_rule, R.color.tin_ink);
        }
        return new FaceScheme(
                R.drawable.bg_national_id,
                R.color.national_ink, R.color.national_muted, R.color.national_rule,
                R.color.national_ink, R.color.national_muted,
                R.color.national_ink,
                R.color.photo_bg_national, R.color.national_rule, R.color.national_ink);
    }

    /**
     * Fourth face slot: the holder's sex, beside date of birth.
     */
    public static class FaceExtra {
        public final String label;
        public final String value;

        FaceExtra(String label, String value) {
            this.label = label;
            this.value = value;
        }
    }

    public static FaceExtra faceExtra(String typeName, Map<String, String> documentFields) {
        Map<String, String> safeFields = documentFields != null ? documentFields : new LinkedHashMap<>();
        // TIN and National ID have no sex slot: their compact faces pair birth
        // with issue, matching their side-by-side date rows. Key varies by
        // type ("dateOfIssue" vs "issue_date"); first non-blank wins.
        if (matchesType(typeName, TYPE_TIN)
                || matchesType(typeName, TYPE_NATIONAL_ID)) {
            return new FaceExtra("DATE OF ISSUE", displayDate(
                    firstNonEmpty(safeFields.get("dateOfIssue"), safeFields.get("issue_date"))));
        }
        // Passport has no sex slot: its compact face pairs birth with expiry.
        if (matchesType(typeName, TYPE_PASSPORT)) {
            return new FaceExtra("DATE OF EXPIRY", displayDate(safeFields.get("expiry_date")));
        }
        // Driver license has no sex slot either: birth pairs with expiry,
        // matching the form's side-by-side date row.
        if (matchesType(typeName, TYPE_DRIVING_LICENSE)) {
            return new FaceExtra("EXPIRY DATE", displayDate(safeFields.get("expiry_date")));
        }
        // PhilHealth shows membership beside birth (no sex slot on its face),
        // matching the form's PhilHealth No. + Membership top row.
        if (matchesType(typeName, TYPE_PHIL_HEALTH)) {
            return new FaceExtra("MEMBERSHIP", displayOrDash(safeFields.get("membership")));
        }
        return new FaceExtra("SEX", displayOrDash(safeFields.get("sex")));
    }

    private static String displayOrDash(String value) {
        if (!hasText(value)) {
            return EMPTY_FACE_VALUE;
        }
        return value.trim();
    }

    /**
     * Card-face holder line: the type's full-name field, uppercased.
     */
    public static String displayName(Map<String, String> documentFields) {
        Map<String, String> safeFields = documentFields != null ? documentFields : new LinkedHashMap<>();
        // Name key varies by type ("fullName" vs "full_name"); first non-blank wins.
        String holderName = firstNonEmpty(safeFields.get("fullName"), safeFields.get("full_name"));
        return hasText(holderName) ? holderName.trim().toUpperCase(java.util.Locale.ROOT) : "FULL NAME";
    }

    /**
     * Card-face primary number. 12-digit numbers (TIN, PhilHealth No.) regroup
     * as 111-111-111-111, the 16-digit PSN as 1111-1111-1111-1111, the SS
     * number as 34-1234567-8 (2-7-1), and the license number as N01-23-456789
     * (3-2-6); everything else renders uppercased as stored.
     */
    public static String displayNumber(IdType typeSpec, Map<String, String> documentFields) {
        Map<String, String> safeFields = documentFields != null ? documentFields : new LinkedHashMap<>();
        String numberKey = typeSpec != null ? typeSpec.numberKey : "";
        String primaryNumber = safeFields.get(numberKey);
        if (!hasText(primaryNumber)) {
            return EMPTY_FACE_VALUE;
        }
        if (typeSpec != null && usesGrouped12Display(typeSpec.name)) {
            return formatGrouped12(primaryNumber);
        }
        if (typeSpec != null && usesGrouped16Display(typeSpec.name)) {
            return formatGrouped16(primaryNumber);
        }
        if (typeSpec != null && usesSssDisplay(typeSpec.name)) {
            return formatSssNumber(primaryNumber);
        }
        if (typeSpec != null && usesLicenseDisplay(typeSpec.name)) {
            return formatLicenseNumber(primaryNumber);
        }
        // Document numbers render uppercase (passport "p1234567a" normalizes to
        // "P1234567A"); pure-digit numbers are unaffected. Display-only.
        return primaryNumber.trim().toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * 12-digit document numbers (TIN, PhilHealth No.) group 3-3-3-3 on the face.
     */
    private static boolean usesGrouped12Display(String typeName) {
        return TYPE_TIN.equalsIgnoreCase(typeName)
                || TYPE_PHIL_HEALTH.equalsIgnoreCase(typeName);
    }

    /**
     * The 16-digit PSN groups 4-4-4-4 on the face.
     */
    private static boolean usesGrouped16Display(String typeName) {
        return TYPE_NATIONAL_ID.equalsIgnoreCase(typeName);
    }

    /**
     * The 10-digit SS number groups 2-7-1 (34-1234567-8) on the face.
     */
    private static boolean usesSssDisplay(String typeName) {
        return TYPE_SSS.equalsIgnoreCase(typeName);
    }

    /**
     * The license number groups 3-2-6 (N01-23-456789) on the face.
     */
    private static boolean usesLicenseDisplay(String typeName) {
        return TYPE_DRIVING_LICENSE.equalsIgnoreCase(typeName);
    }

    /**
     * Face grouping for 12-digit numbers: 111-111-111-111. Storage holds the
     * bare 12 digits (the field caps at 12, validation counts digits), so
     * grouping is presentational only. Anything else passes through untouched;
     * validation guards new input.
     */
    private static String formatGrouped12(String rawNumber) {
        String digitsOnly = rawNumber != null ? NON_DIGITS.matcher(rawNumber).replaceAll("") : "";
        if (digitsOnly.length() == 12) {
            return digitsOnly.substring(0, 3) + "-" + digitsOnly.substring(3, 6)
                    + "-" + digitsOnly.substring(6, 9) + "-" + digitsOnly.substring(9, 12);
        }
        return hasText(rawNumber) ? rawNumber.trim() : EMPTY_FACE_VALUE;
    }

    /**
     * Face grouping for the 16-digit PSN: 1111-1111-1111-1111. Same contract
     * as the 12-digit grouping — storage holds bare digits, grouping is
     * presentational only, anything else passes through for validation to flag.
     */
    private static String formatGrouped16(String rawNumber) {
        String digitsOnly = rawNumber != null ? NON_DIGITS.matcher(rawNumber).replaceAll("") : "";
        if (digitsOnly.length() == 16) {
            return digitsOnly.substring(0, 4) + "-" + digitsOnly.substring(4, 8)
                    + "-" + digitsOnly.substring(8, 12) + "-" + digitsOnly.substring(12, 16);
        }
        return hasText(rawNumber) ? rawNumber.trim() : EMPTY_FACE_VALUE;
    }

    /**
     * Face grouping for the 10-digit SS number: 34-1234567-8 (2-7-1). Same
     * contract as the other groupings — storage holds bare digits, grouping
     * is presentational only, anything else passes through for validation.
     */
    private static String formatSssNumber(String rawNumber) {
        String digitsOnly = rawNumber != null ? NON_DIGITS.matcher(rawNumber).replaceAll("") : "";
        if (digitsOnly.length() == 10) {
            return digitsOnly.substring(0, 2) + "-" + digitsOnly.substring(2, 9)
                    + "-" + digitsOnly.charAt(9);
        }
        return hasText(rawNumber) ? rawNumber.trim() : EMPTY_FACE_VALUE;
    }

    /**
     * Face grouping for the driver's license number: N01-23-456789 (3-2-6).
     * Storage holds the bare 11 characters (the field caps at 11); grouping
     * is presentational only and uppercases letters. Anything else passes
     * through uppercased for validation to flag.
     */
    private static String formatLicenseNumber(String rawNumber) {
        String compactNumber = rawNumber != null ? rawNumber.trim() : "";
        if (compactNumber.isEmpty()) {
            return EMPTY_FACE_VALUE;
        }
        String unseparated = SEPARATORS.matcher(compactNumber).replaceAll("").toUpperCase(java.util.Locale.ROOT);
        if (unseparated.length() == 11) {
            return unseparated.substring(0, 3) + "-" + unseparated.substring(3, 5)
                    + "-" + unseparated.substring(5, 11);
        }
        return compactNumber.toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * Face date normalization: always renders YYYY-MM-DD. Plain 8-digit input
     * (YYYYMMDD, accepted for hyphen-less keyboards) is dashed here; empty
     * renders as a dash. Anything else passes through as stored.
     */
    public static String displayDate(String rawDate) {
        String trimmedDate = rawDate != null ? rawDate.trim() : "";
        if (trimmedDate.isEmpty()) {
            return EMPTY_FACE_VALUE;
        }
        String digitsOnly = NON_DIGITS.matcher(trimmedDate).replaceAll("");
        if (digitsOnly.length() == 8) {
            return digitsOnly.substring(0, 4) + "-" + digitsOnly.substring(4, 6)
                    + "-" + digitsOnly.substring(6, 8);
        }
        return trimmedDate;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static boolean matchesType(String typeName, String expectedType) {
        return typeName != null && typeName.trim().equalsIgnoreCase(expectedType);
    }

    /**
     * First non-blank candidate, in preference order. Used where a value lives
     * under different keys per type (e.g. dateOfBirth vs birth_date).
     */
    public static String firstNonEmpty(String... candidates) {
        if (candidates != null) {
            for (String candidate : candidates) {
                if (hasText(candidate)) {
                    return candidate;
                }
            }
        }
        return "";
    }

    private static String toFieldLabel(String fieldKey) {
        String[] labelParts = fieldKey.split("_");
        StringBuilder labelBuilder = new StringBuilder();
        for (String part : labelParts) {
            if (part.isEmpty()) {
                continue;
            }
            if (labelBuilder.length() > 0) {
                labelBuilder.append(' ');
            }
            labelBuilder.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                labelBuilder.append(part.substring(1));
            }
        }
        return labelBuilder.toString();
    }
}
