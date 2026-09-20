package com.akin.wallet.model;

import android.text.InputType;

import androidx.annotation.NonNull;

import com.akin.wallet.R;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Government ID entry plus the single source of truth for every supported
 * government ID type.
 *
 * <p>Entry: variable per-type fields are stored as a JSON object in SQLite
 * (fields_json column) so National ID, Passport, SSS, GSIS, etc. all share
 * one table and one card UI. Audit timestamps ({@code createdAt}/
 * {@code updatedAt}) are first-class columns sibling to {@code id} — never
 * keys inside the JSON document. Keeping them out of the JSON preserves the
 * per-type field contract and lets recency ordering reuse the same
 * {@code updated_at DESC, id DESC} pattern as bank cards and social accounts.
 *
 * <p>No {@code toString()} by design: document fields are sensitive and must
 * never reach logcat through an implicit string conversion.
 *
 * <p>Spec: adding another ID type later = append one {@link IdType} below.
 * No DB migration, no new XML, no adapter change needed because storage is
 * JSON ({@code fields_json}) and both the list card and the form are rendered
 * dynamically from these specs.
 */
public final class GovernmentIDModel {

    /** Row id for drafts that have never been persisted. */
    public static final int UNSET_ID = -1;

    private static final java.util.regex.Pattern NON_DIGITS =
            java.util.regex.Pattern.compile("\\D");
    private static final java.util.regex.Pattern SEPARATORS =
            java.util.regex.Pattern.compile("[\\s-]");
    private final int id;
    private final String idType;
    private final Map<String, String> fields;
    private final long createdAt;
    private final long updatedAt;

    /** Unsaved draft; the database assigns the id and timestamps on insert. */
    public GovernmentIDModel(String idType, Map<String, String> fields) {
        this(UNSET_ID, idType, fields, 0, 0);
    }

    /**
     * Full constructor carrying audit timestamps alongside the row identity.
     * New entries pass {@code 0, 0} and let the DB stamp {@code now};
     * updates preserve {@code createdAt} and pass {@code 0} for
     * {@code updatedAt} so the DB bumps recency.
     */
    public GovernmentIDModel(int id, String idType, Map<String, String> fields,
                             long createdAt, long updatedAt) {
        this.id = id;
        this.idType = idType != null ? idType : "";
        this.fields = fields != null
                ? new LinkedHashMap<>(fields)
                : new LinkedHashMap<>();
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public int getId() {
        return id;
    }

    @NonNull
    public String getIdType() {
        return idType;
    }

    /** Defensive copy — callers cannot mutate internal state. */
    @NonNull
    public Map<String, String> getFields() {
        return new LinkedHashMap<>(fields);
    }

    /**
     * Read-only view for hot paths (bind/search). No copy — caller must not
     * mutate. Use {@link #getFields()} when a mutable snapshot is needed.
     */
    @NonNull
    public Map<String, String> getFieldsRef() {
        return java.util.Collections.unmodifiableMap(fields);
    }

    /** Row creation time, epoch millis. 0 when unset. */
    public long getCreatedAt() {
        return createdAt;
    }

    /** Last recency bump, epoch millis. Drives newest-first ordering. */
    public long getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Serialize document fields to JSON for SQLite storage.
     * Timestamps are deliberately excluded: they live in their own columns.
     */
    public String getFieldsJson() {
        JSONObject jsonDocument = new JSONObject();
        for (Map.Entry<String, String> field : fields.entrySet()) {
            putField(jsonDocument, field.getKey(), field.getValue());
        }
        return jsonDocument.toString();
    }

    /**
     * JSONObject.put only throws for null keys or non-finite numbers; our keys
     * are non-null strings and values are normalized to "", so this is
     * provably non-throwing and intentionally silent.
     */
    private static void putField(JSONObject jsonDocument, String key, String value) {
        try {
            jsonDocument.put(key, value != null ? value : "");
        } catch (JSONException impossible) {
            throw new AssertionError("String keys never fail JSONObject.put", impossible);
        }
    }

    /** Parse JSON string from SQLite. Never throws — returns empty map on bad input. */
    public static Map<String, String> parseFieldsJson(String jsonPayload) {
        Map<String, String> parsedFields = new LinkedHashMap<>();
        if (jsonPayload == null || jsonPayload.trim().isEmpty()) {
            return parsedFields;
        }
        try {
            JSONObject jsonDocument = new JSONObject(jsonPayload);
            Iterator<String> keys = jsonDocument.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                parsedFields.put(key, jsonDocument.optString(key, ""));
            }
        } catch (JSONException malformed) {
            // Forward-compat: a corrupt row degrades to an empty form rather
            // than crashing the list. The error is deliberate silence, not
            // swallowed diagnostics — there is nothing actionable to log.
            parsedFields.clear();
        }
        return parsedFields;
    }

    /** Value equality across every column (backs DiffUtil content checks). */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GovernmentIDModel)) {
            return false;
        }
        GovernmentIDModel that = (GovernmentIDModel) o;
        return id == that.id
                && createdAt == that.createdAt
                && updatedAt == that.updatedAt
                && Objects.equals(idType, that.idType)
                && Objects.equals(fields, that.fields);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, idType, fields, createdAt, updatedAt);
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

    private static final String EMPTY_FACE_VALUE = "—";

    /** One input field definition. */
    public static class IdField {
        public final String key;
        public final String label;
        public final String hint;
        public final boolean required;
        public final boolean sensitive;
        public final String[] options; // non-null => dropdown instead of free text
        public final int inputType;
        public final int maxLength; // 0 = no limit
        // Explicit row pairing: when true, this field shares its form row
        // with the next field regardless of their types. Used for custom rows
        // the generic rules can't infer (e.g. two full-width text fields).
        // Generic pairs (date/date, short-field/picker) need no flag.
        public final boolean pairWithNext;

        public IdField(String key, String label, String hint, boolean required,
                       boolean sensitive, String[] options, int inputType, int maxLength) {
            this(key, label, hint, required, sensitive, options, inputType, maxLength, false);
        }

        public IdField(String key, String label, String hint, boolean required,
                       boolean sensitive, String[] options, int inputType, int maxLength,
                       boolean pairWithNext) {
            this.key = key;
            this.label = label;
            this.hint = hint;
            this.required = required;
            this.sensitive = sensitive;
            this.options = options;
            this.inputType = inputType;
            this.maxLength = maxLength;
            this.pairWithNext = pairWithNext;
        }

        /**
         * Copy of this field that shares its form row with the next field.
         * The flag is consumed in spec order by the form builder; a flagged
         * last field is simply ignored.
         */
        public IdField pairedWithNext() {
            return new IdField(key, label, hint, required, sensitive, options,
                    inputType, maxLength, true);
        }

        public boolean isDropdown() {
            return options != null && options.length > 0;
        }

        public static IdField text(String key, String label, String hint,
                                   boolean required, boolean sensitive, int maxLength) {
            return new IdField(key, label, hint, required, sensitive, null,
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS, maxLength);
        }

        public static IdField number(String key, String label, String hint,
                                     boolean required, boolean sensitive, int maxLength) {
            return new IdField(key, label, hint, required, sensitive, null,
                    InputType.TYPE_CLASS_NUMBER, maxLength);
        }

        /**
         * Date entry with a date-optimized keyboard (numeric with separators).
         * No picker is imposed; the 8-digit shape (YYYYMMDD) is enforced in
         * form validation. Callers cap length at 8 so dashes can't be typed.
         */
        public static IdField date(String key, String label, String hint,
                                   boolean required, int maxLength) {
            return new IdField(key, label, hint, required, false, null,
                    InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_DATE,
                    maxLength);
        }

        public static IdField dropdown(String key, String label,
                                       boolean required, String[] options) {
            return new IdField(key, label, "Select", required, false,
                    options, InputType.TYPE_CLASS_TEXT, 0);
        }
    }

    /** One ID type definition. */
    public static class IdType {
        public final String name;
        public final String numberKey; // primary number shown on the face
        public final List<IdField> fields;

        IdType(String name, String numberKey, List<IdField> fields) {
            this.name = name;
            this.numberKey = numberKey;
            this.fields = Collections.unmodifiableList(fields);
        }
    }

    private static final List<IdType> TYPES;

    static {
        TYPES = List.of(buildNationalIdType(), buildDrivingLicenseType(), buildPassportType(), buildSssType(), buildPhilHealthType(), buildTinType());
    }

    // ---- Type builders (one per document; keeps the static block flat) ----

    // National ID layout: PSN, then paired rows (name + sex, birth +
    // issue, blood type + marital status, birthplace + address). The
    // date/date and picker/picker rows pair by generic rule; name + sex
    // and birthplace + address pair by explicit flag since two full-width
    // text-led rows can't be inferred. Every field required. The PSN is
    // exactly 16 digits capped at 16 chars and regrouped 4-4-4-4 on the
    // face; both dates are exactly 8 digits (YYYYMMDD) capped at 8 chars.
    private static IdType buildNationalIdType() {
        return new IdType(TYPE_NATIONAL_ID, "psn", List.of(
                IdField.number("psn", "PSN (PhilSys Number)", "1234567890123456", true, true, 16),
                IdField.text("full_name", "Full Name", "Juan Dela Cruz", true, false, 80).pairedWithNext(),
                IdField.dropdown("sex", "Sex", true, SEX_OPTIONS),
                IdField.date("birth_date", "Date of Birth", "YYYYMMDD", true, 8),
                IdField.date("issue_date", "Date of Issue", "YYYYMMDD", true, 8),
                IdField.dropdown("blood_type", "Blood Type", true, BLOOD_OPTIONS),
                IdField.dropdown("marital_status", "Marital Status", true, CIVIL_STATUS_OPTIONS),
                IdField.text("place_of_birth", "Place of Birth", "Manila, PH", true, false, 80).pairedWithNext(),
                IdField.text("present_address", "Present Address", "Street, City", true, false, 120)
        ));
    }

    // Driver license layout: license number + agency code share the top
    // row, then name, address + nationality, and paired rows (sex + blood
    // type, birth + expiry, weight + height, eye color + serial number,
    // DL code + conditions). Every field required. The license number caps
    // at 11 chars and regroups N01-23-456789 (3-2-6) on the face; the
    // serial number is numeric-only; both dates are exactly 8 digits
    // (YYYYMMDD) capped at 8 chars and dashed as YYYY-MM-DD on the face;
    // text-led pairs use the explicit flag.
    private static IdType buildDrivingLicenseType() {
        return new IdType(TYPE_DRIVING_LICENSE, "license_no", List.of(
                IdField.text("license_no", "License No.", "N0123456789", true, true, 11).pairedWithNext(),
                IdField.text("agency_code", "Agency Code", "e.g. N01", true, false, 10),
                IdField.text("full_name", "Full Name", "Juan Dela Cruz", true, false, 80),
                IdField.text("address", "Address", "Street, City", true, false, 120).pairedWithNext(),
                IdField.text("nationality", "Nationality", "Filipino", true, false, 40),
                IdField.dropdown("sex", "Sex", true, SEX_OPTIONS),
                IdField.dropdown("blood_type", "Blood Type", true, BLOOD_OPTIONS),
                IdField.date("birth_date", "Date of Birth", "YYYYMMDD", true, 8),
                IdField.date("expiry_date", "Expiry Date", "YYYYMMDD", true, 8),
                IdField.text("weight", "Weight", "e.g. 70 kg", true, false, 10).pairedWithNext(),
                IdField.text("height", "Height", "e.g. 170 cm", true, false, 10),
                IdField.text("eye_color", "Eye Color", "e.g. Brown", true, false, 20).pairedWithNext(),
                IdField.number("serial_no", "Serial No.", "e.g. 123456", true, false, 20),
                IdField.text("dl_code", "DL Code", "e.g. B", true, false, 20).pairedWithNext(),
                IdField.text("conditions", "Conditions", "e.g. A", true, false, 20)
        ));
    }

    // Passport layout: number + issuing authority share the top row, name,
    // then paired rows (nationality + sex, birth + issue, birthplace +
    // expiry). Every field required. All three dates are exactly 8 digits
    // (YYYYMMDD) capped at 8 chars and dashed as YYYY-MM-DD on the face.
    private static IdType buildPassportType() {
        return new IdType(TYPE_PASSPORT, "passport_no", List.of(
                IdField.text("passport_no", "Passport No.", "P1234567A", true, true, 9).pairedWithNext(),
                IdField.text("issuing_authority", "Issuing Authority", "DFA Manila", true, false, 60),
                IdField.text("full_name", "Full Name", "Juan Dela Cruz", true, false, 80),
                IdField.text("nationality", "Nationality", "Filipino", true, false, 40).pairedWithNext(),
                IdField.dropdown("sex", "Sex", true, SEX_OPTIONS),
                IdField.date("birth_date", "Date of Birth", "YYYYMMDD", true, 8),
                IdField.date("issue_date", "Date of Issue", "YYYYMMDD", true, 8),
                IdField.text("place_of_birth", "Place of Birth", "Manila, PH", true, false, 80).pairedWithNext(),
                IdField.date("expiry_date", "Date of Expiry", "YYYYMMDD", true, 8)
        ));
    }

    // SSS layout: number, name, then the paired birth + sex row, address
    // stacked. Every field required. The SS number is exactly 10 digits
    // capped at 10 chars on a numeric keyboard; birth is exactly 8 digits
    // (YYYYMMDD) capped at 8 chars and dashed as YYYY-MM-DD on the face.
    private static IdType buildSssType() {
        return new IdType(TYPE_SSS, "ss_number", List.of(
                IdField.number("ss_number", "SS Number", "3412345678", true, true, 10),
                IdField.text("full_name", "Full Name", "Juan Dela Cruz", true, false, 80),
                IdField.date("birth_date", "Date of Birth", "YYYYMMDD", true, 8),
                IdField.dropdown("sex", "Sex", true, SEX_OPTIONS),
                IdField.text("address", "Address", "Street, City", true, false, 120)
        ));
    }

    // PhilHealth field contract: every field required; the short
    // identifiers share the top row (PhilHealth No. + Membership), then
    // full name with address directly below it, then the birth + sex row.
    // The number is exactly 12 digits capped at 12 chars and regrouped
    // 111-111-111-111 on the face; birth is exactly 8 digits (YYYYMMDD).
    private static IdType buildPhilHealthType() {
        return new IdType(TYPE_PHIL_HEALTH, "philHealthNumber", List.of(
                IdField.number("philHealthNumber", "PhilHealth No.", "123456789012", true, true, 12),
                IdField.dropdown("membership", "Membership", true, PHIL_HEALTH_MEMBER_OPTIONS),
                IdField.text("fullName", "Full Name", "Juan Dela Cruz", true, false, 80),
                IdField.text("address", "Address", "Street, City", true, false, 120),
                IdField.date("dateOfBirth", "Date of Birth", "YYYYMMDD", true, 8),
                IdField.dropdown("sex", "Sex", true, SEX_OPTIONS)
        ));
    }

    // TIN field contract: every field required, 12-digit numeric TIN capped
    // at 12 chars, dates as exactly 8 digits (YYYYMMDD) capped at 8 chars.
    // The face regroups the bare digits as 111-111-111-111 and dashes
    // plain-digit dates for display.
    private static IdType buildTinType() {
        return new IdType(TYPE_TIN, "tinNumber", List.of(
                IdField.number("tinNumber", "TIN", "123456789012", true, true, 12),
                IdField.text("fullName", "Full Name", "Juan Dela Cruz", true, false, 80),
                IdField.text("address", "Address", "Street, City", true, false, 120),
                IdField.date("dateOfBirth", "Date of Birth", "YYYYMMDD", true, 8),
                IdField.date("dateOfIssue", "Date of Issue", "YYYYMMDD", true, 8)
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
                fallbackFields.add(IdField.text(storedKey, toFieldLabel(storedKey), "", false, false, 0));
            }
        } else {
            fallbackFields.add(IdField.text("full_name", "Full Name", "Juan Dela Cruz", true, false, 80));
            fallbackFields.add(IdField.text("id_number", "ID Number", "", true, true, 40));
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

    /** Face label for the primary number, per type (PSN, LICENSE NO., …). */
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
        return toFieldLabel(numberKey).toUpperCase();
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
                    R.color.tin_ink, R.color.tin_muted, R.color.tin_rule,
                    R.color.tin_ink, R.color.tin_muted,
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

    /** Fourth face slot: the holder's sex, beside date of birth. */
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

    /** Card-face holder line: the type's full-name field, uppercased. */
    public static String displayName(Map<String, String> documentFields) {
        Map<String, String> safeFields = documentFields != null ? documentFields : new LinkedHashMap<>();
        // Name key varies by type ("fullName" vs "full_name"); first non-blank wins.
        String holderName = firstNonEmpty(safeFields.get("fullName"), safeFields.get("full_name"));
        return hasText(holderName) ? holderName.trim().toUpperCase() : "FULL NAME";
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
        return primaryNumber.trim().toUpperCase();
    }

    /** 12-digit document numbers (TIN, PhilHealth No.) group 3-3-3-3 on the face. */
    private static boolean usesGrouped12Display(String typeName) {
        return TYPE_TIN.equalsIgnoreCase(typeName)
                || TYPE_PHIL_HEALTH.equalsIgnoreCase(typeName);
    }

    /** The 16-digit PSN groups 4-4-4-4 on the face. */
    private static boolean usesGrouped16Display(String typeName) {
        return TYPE_NATIONAL_ID.equalsIgnoreCase(typeName);
    }

    /** The 10-digit SS number groups 2-7-1 (34-1234567-8) on the face. */
    private static boolean usesSssDisplay(String typeName) {
        return TYPE_SSS.equalsIgnoreCase(typeName);
    }

    /** The license number groups 3-2-6 (N01-23-456789) on the face. */
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
        String unseparated = SEPARATORS.matcher(compactNumber).replaceAll("").toUpperCase();
        if (unseparated.length() == 11) {
            return unseparated.substring(0, 3) + "-" + unseparated.substring(3, 5)
                    + "-" + unseparated.substring(5, 11);
        }
        return compactNumber.toUpperCase();
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
    private static String firstNonEmpty(String... candidates) {
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
