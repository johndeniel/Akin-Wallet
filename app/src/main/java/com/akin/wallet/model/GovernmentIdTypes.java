package com.akin.wallet.model;

import android.text.InputType;

import com.akin.wallet.R;

import java.util.*;

/**
 * Fixed V1 document field contracts and catalog order.
 */
public final class GovernmentIdTypes {
    private GovernmentIdTypes() {
    }
    // ---- Type specs (one per document; storage stays JSON, UI stays dynamic) ----

    public static final String TYPE_NATIONAL_ID = "National ID";
    public static final String TYPE_DRIVING_LICENSE = "Driver's License";
    public static final String TYPE_PASSPORT = "Passport";
    public static final String TYPE_SSS = "SSS";
    public static final String TYPE_PHIL_HEALTH = "PhilHealth ID";
    public static final String TYPE_TIN = "TIN ID";

    private static final String[] SEX_OPTIONS =
            {"Male", "Female"};
    private static final String[] BLOOD_OPTIONS =
            {"Unknown", "A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-"};
    private static final String[] CIVIL_STATUS_OPTIONS =
            {"Single", "Married", "Widowed", "Divorced", "Separated"};
    private static final String[] PHIL_HEALTH_MEMBER_OPTIONS =
            {"Formal Economy", "Informal Economy", "Indigent", "Sponsored",
                    "Senior Citizen", "Lifetime Member"};


    /**
     * One fixed field definition. User labels/hints live in Android resources.
     */
    public static final class IdField {
        public final String key;
        private final int labelRes, hintRes;
        private final String customLabel;
        public final boolean required, sensitive, pairWithNext;
        public final String[] options;
        public final int inputType, maxLength;

        private IdField(String key, int labelRes, int hintRes, String customLabel,
                        boolean required, boolean sensitive, String[] options,
                        int inputType, int maxLength, boolean pairWithNext) {
            this.key = key;
            this.labelRes = labelRes;
            this.hintRes = hintRes;
            this.customLabel = customLabel;
            this.required = required;
            this.sensitive = sensitive;
            this.options = options == null ? null : options.clone();
            this.inputType = inputType;
            this.maxLength = maxLength;
            this.pairWithNext = pairWithNext;
        }

        public String label(android.content.Context context) {
            return labelRes == 0 ? customLabel : context.getString(labelRes);
        }

        public String hint(android.content.Context context) {
            return hintRes == 0 ? "" : context.getString(hintRes);
        }

        public IdField pairedWithNext() {
            return new IdField(key, labelRes, hintRes, customLabel, required, sensitive, options, inputType, maxLength, true);
        }

        public boolean isDropdown() {
            return options != null && options.length > 0;
        }

        public static IdField text(String key, int label, int hint, boolean required, boolean sensitive, int maxLength) {
            return new IdField(key, label, hint, null, required, sensitive, null,
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS
                            | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, maxLength, false);
        }

        public static IdField number(String key, int label, int hint, boolean required, boolean sensitive, int maxLength) {
            return new IdField(key, label, hint, null, required, sensitive, null, InputType.TYPE_CLASS_NUMBER, maxLength, false);
        }

        public static IdField date(String key, int label, int hint, boolean required, int maxLength) {
            return new IdField(key, label, hint, null, required, false, null,
                    InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_DATE, maxLength, false);
        }

        public static IdField dropdown(String key, int label, boolean required, String[] options) {
            return new IdField(key, label, R.string.hint_dropdown_select, null, required, false, options, InputType.TYPE_CLASS_TEXT, 0, false);
        }

        private static IdField customText(String key, String label) {
            return new IdField(key, 0, 0, label, false, false, null,
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, 0, false);
        }
    }

    /**
     * One ID type definition.
     */
    public static final class IdType {
        public final String name;
        public final String numberKey; // primary number shown on the face
        public final List<IdField> fields;

        IdType(String name, String numberKey, List<IdField> fields) {
            this.name = name;
            this.numberKey = numberKey;
            this.fields = List.copyOf(fields);
        }
    }

    private static final List<IdType> TYPES;

    static {
        TYPES = List.of(buildNationalIdType(), buildDrivingLicenseType(), buildPassportType(), buildSssType(), buildPhilHealthType(), buildTinType());
    }

    // ---- Type builders (one per document; keeps the static block flat) ----

    // National ID fields.
    private static IdType buildNationalIdType() {
        return new IdType(TYPE_NATIONAL_ID, "psn", List.of(
                IdField.number("psn", R.string.id_field_psn_philsys_number, R.string.id_hint_1234567890123456, true, true, 16),
                IdField.text("full_name", R.string.id_field_full_name, R.string.hint_person_name, true, false, 80).pairedWithNext(),
                IdField.dropdown("sex", R.string.id_field_sex, true, SEX_OPTIONS),
                IdField.date("birth_date", R.string.id_field_date_of_birth, R.string.id_hint_yyyymmdd, true, 8),
                IdField.date("issue_date", R.string.id_field_date_of_issue, R.string.id_hint_yyyymmdd, true, 8),
                IdField.dropdown("blood_type", R.string.id_field_blood_type, true, BLOOD_OPTIONS),
                IdField.dropdown("marital_status", R.string.id_field_marital_status, true, CIVIL_STATUS_OPTIONS),
                IdField.text("place_of_birth", R.string.id_field_place_of_birth, R.string.id_hint_manila_ph, true, false, 80).pairedWithNext(),
                IdField.text("present_address", R.string.id_field_present_address, R.string.id_hint_street_city, true, false, 120)
        ));
    }

    // Driver license fields.
    private static IdType buildDrivingLicenseType() {
        return new IdType(TYPE_DRIVING_LICENSE, "license_no", List.of(
                IdField.text("license_no", R.string.id_field_license_no, R.string.id_hint_n0123456789, true, true, 11).pairedWithNext(),
                IdField.text("agency_code", R.string.id_field_agency_code, R.string.id_hint_e_g_n01, true, false, 10),
                IdField.text("full_name", R.string.id_field_full_name, R.string.hint_person_name, true, false, 80),
                IdField.text("address", R.string.id_field_address, R.string.id_hint_street_city, true, false, 120).pairedWithNext(),
                IdField.text("nationality", R.string.id_field_nationality, R.string.id_hint_filipino, true, false, 40),
                IdField.dropdown("sex", R.string.id_field_sex, true, SEX_OPTIONS),
                IdField.dropdown("blood_type", R.string.id_field_blood_type, true, BLOOD_OPTIONS),
                IdField.date("birth_date", R.string.id_field_date_of_birth, R.string.id_hint_yyyymmdd, true, 8),
                IdField.date("expiry_date", R.string.id_field_expiry_date, R.string.id_hint_yyyymmdd, true, 8),
                IdField.text("weight", R.string.id_field_weight, R.string.id_hint_e_g_70_kg, true, false, 10).pairedWithNext(),
                IdField.text("height", R.string.id_field_height, R.string.id_hint_e_g_170_cm, true, false, 10),
                IdField.text("eye_color", R.string.id_field_eye_color, R.string.id_hint_e_g_brown, true, false, 20).pairedWithNext(),
                IdField.number("serial_no", R.string.id_field_serial_no, R.string.id_hint_e_g_123456, true, false, 20),
                IdField.text("dl_code", R.string.id_field_dl_code, R.string.id_hint_e_g_b, true, false, 20).pairedWithNext(),
                IdField.text("conditions", R.string.id_field_conditions, R.string.id_hint_e_g_a, true, false, 20)
        ));
    }

    // Passport fields.
    private static IdType buildPassportType() {
        return new IdType(TYPE_PASSPORT, "passport_no", List.of(
                IdField.text("passport_no", R.string.id_field_passport_no, R.string.id_hint_p1234567a, true, true, 9).pairedWithNext(),
                IdField.text("issuing_authority", R.string.id_field_issuing_authority, R.string.id_hint_dfa_manila, true, false, 60),
                IdField.text("full_name", R.string.id_field_full_name, R.string.hint_person_name, true, false, 80),
                IdField.text("nationality", R.string.id_field_nationality, R.string.id_hint_filipino, true, false, 40).pairedWithNext(),
                IdField.dropdown("sex", R.string.id_field_sex, true, SEX_OPTIONS),
                IdField.date("birth_date", R.string.id_field_date_of_birth, R.string.id_hint_yyyymmdd, true, 8),
                IdField.date("issue_date", R.string.id_field_date_of_issue, R.string.id_hint_yyyymmdd, true, 8),
                IdField.text("place_of_birth", R.string.id_field_place_of_birth, R.string.id_hint_manila_ph, true, false, 80).pairedWithNext(),
                IdField.date("expiry_date", R.string.id_field_date_of_expiry, R.string.id_hint_yyyymmdd, true, 8)
        ));
    }

    // SSS fields.
    private static IdType buildSssType() {
        return new IdType(TYPE_SSS, "ss_number", List.of(
                IdField.number("ss_number", R.string.id_field_ss_number, R.string.id_hint_3412345678, true, true, 10),
                IdField.text("full_name", R.string.id_field_full_name, R.string.hint_person_name, true, false, 80),
                IdField.date("birth_date", R.string.id_field_date_of_birth, R.string.id_hint_yyyymmdd, true, 8),
                IdField.dropdown("sex", R.string.id_field_sex, true, SEX_OPTIONS),
                IdField.text("address", R.string.id_field_address, R.string.id_hint_street_city, true, false, 120)
        ));
    }

    // PhilHealth fields.
    private static IdType buildPhilHealthType() {
        return new IdType(TYPE_PHIL_HEALTH, "philHealthNumber", List.of(
                IdField.number("philHealthNumber", R.string.id_field_philhealth_no, R.string.id_hint_123456789012, true, true, 12),
                IdField.dropdown("membership", R.string.id_field_membership, true, PHIL_HEALTH_MEMBER_OPTIONS),
                IdField.text("fullName", R.string.id_field_full_name, R.string.hint_person_name, true, false, 80),
                IdField.text("address", R.string.id_field_address, R.string.id_hint_street_city, true, false, 120),
                IdField.date("dateOfBirth", R.string.id_field_date_of_birth, R.string.id_hint_yyyymmdd, true, 8),
                IdField.dropdown("sex", R.string.id_field_sex, true, SEX_OPTIONS)
        ));
    }

    // TIN fields.
    private static IdType buildTinType() {
        return new IdType(TYPE_TIN, "tinNumber", List.of(
                IdField.number("tinNumber", R.string.id_field_tin, R.string.id_hint_123456789012, true, true, 12),
                IdField.text("fullName", R.string.id_field_full_name, R.string.hint_person_name, true, false, 80),
                IdField.text("address", R.string.id_field_address, R.string.id_hint_street_city, true, false, 120),
                IdField.date("dateOfBirth", R.string.id_field_date_of_birth, R.string.id_hint_yyyymmdd, true, 8),
                IdField.date("dateOfIssue", R.string.id_field_date_of_issue, R.string.id_hint_yyyymmdd, true, 8)
        ));
    }

    // ---- Catalog lookups ----

    public static List<IdType> getAllTypes() {
        return TYPES;
    }

    public static String[] getTypeNames() {
        String[] typeNames = new String[TYPES.size()];
        for (int i = 0; i < TYPES.size(); i++) {
            typeNames[i] = TYPES.get(i).name;
        }
        return typeNames;
    }

    public static IdType forName(String typeName) {
        if (typeName != null) {
            String normalizedName = typeName.trim();
            for (IdType type : TYPES) {
                if (type.name.equalsIgnoreCase(normalizedName)) {
                    return type;
                }
            }
        }
        return TYPES.get(0);
    }

    public static int indexOf(String typeName) {
        if (typeName != null) {
            String normalizedName = typeName.trim();
            for (int i = 0; i < TYPES.size(); i++) {
                if (TYPES.get(i).name.equalsIgnoreCase(normalizedName)) {
                    return i;
                }
            }
        }
        return 0;
    }

    public static boolean isKnownType(String typeName) {
        if (typeName == null) {
            return false;
        }
        String normalizedName = typeName.trim();
        for (IdType type : TYPES) {
            if (type.name.equalsIgnoreCase(normalizedName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Fallback spec for data whose type is no longer known (forward-compat).
     * Every stored key becomes a free-text field so edit never loses data.
     */
    public static IdType genericType(String typeName, Map<String, String> storedFields) {
        List<IdField> fallbackFields = new ArrayList<>();
        if (storedFields != null && !storedFields.isEmpty()) {
            for (String storedKey : storedFields.keySet()) {
                fallbackFields.add(IdField.customText(storedKey, toFieldLabel(storedKey)));
            }
        } else {
            fallbackFields.add(IdField.text("full_name", R.string.id_field_full_name, R.string.hint_person_name, true, false, 80));
            fallbackFields.add(IdField.text("id_number", R.string.id_field_id_number, R.string.id_hint_, true, true, 40));
        }
        String safeTypeName = hasText(typeName) ? typeName.trim() : "Government ID";
        String primaryNumberKey;
        if (storedFields != null && storedFields.containsKey("id_number")) {
            primaryNumberKey = "id_number";
        } else if (fallbackFields.size() > 1) {
            primaryNumberKey = fallbackFields.get(1).key;
        } else {
            primaryNumberKey = fallbackFields.get(0).key;
        }
        return new IdType(safeTypeName, primaryNumberKey, fallbackFields);
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

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
