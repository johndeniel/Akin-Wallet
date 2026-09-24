package com.akin.wallet.activity;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.akin.wallet.R;
import com.akin.wallet.adapter.GovernmentIdDesignAdapter;
import com.akin.wallet.model.GovernmentIDModel;
import com.akin.wallet.util.Ui;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import androidx.core.os.BundleCompat;

/**
 * Government ID create/edit screen. Add mode when no ID passed, edit otherwise.
 */
public class GovernmentIDActivity extends BaseVaultActivity {

    public static final String EXTRA_ID = "extra_id";

    public static Intent editIntent(@NonNull Context context, @NonNull GovernmentIDModel item) {
        return new Intent(context, GovernmentIDActivity.class)
                .putExtra(EXTRA_ID, item.getId());
    }

    // Rotation state. Inputs are built programmatically (no view ids).
    private static final String KEY_DRAFT = "draft_values";
    private static final String KEY_SELECTED_TYPE = "selected_type";
    private static final String KEY_DIALOG_FIELD = "dialog_field";
    private static final Pattern NON_DIGITS = Pattern.compile("\\D");
    private static final Pattern NON_ALNUM = Pattern.compile("[^A-Za-z0-9]");
    private static final Pattern HAS_LETTER = Pattern.compile(".*[A-Za-z].*");
    private static final Pattern DATE_8 = Pattern.compile("\\d{8}");
    private Map<String, String> savedDraft;
    private int savedSelectedType;
    private boolean hasSavedState;
    private String pendingDialogField;

    private boolean isEdit;
    private GovernmentIDModel editingItem;
    private int selectedType;
    private boolean knownType = true;
    private final Map<String, String> draftValues = new LinkedHashMap<>();
    private final Map<String, EditText> textInputs = new LinkedHashMap<>();
    private final Map<String, TextView> dropdownValues = new LinkedHashMap<>();
    private LinearLayout formContainer;
    private LinearLayout dotsContainer;
    private GovernmentIdDesignAdapter designAdapter;
    private RecyclerView designCarousel;
    private LinearLayoutManager designLayoutManager;
    private PagerSnapHelper designSnapHelper;
    private boolean carouselReady;

    private void refreshPreview() {
        if (designAdapter != null) {
            designAdapter.updatePreview(draftValues);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_government_id);
        applyChrome();

        int id = getIntent().getIntExtra(EXTRA_ID, -1);
        if (savedInstanceState != null) {
            hasSavedState = true;
            savedSelectedType = savedInstanceState.getInt(KEY_SELECTED_TYPE, 0);
            savedDraft = restoreDraft(savedInstanceState);
            pendingDialogField = savedInstanceState.getString(KEY_DIALOG_FIELD, null);
        }
        if (id == -1) {
            bindForm(null);
        } else {
            final int rowId = id;
            final int gen = nextLoadGeneration();
            vaultIo(() -> {
                final GovernmentIDModel stored = db().getIdCardById(rowId);
                runIfAlive(gen, () -> {
                    if (stored == null) {
                        finish();
                        return;
                    }
                    bindForm(stored);
                });
            });
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putSerializable(KEY_DRAFT, new LinkedHashMap<>(draftValues));
        outState.putInt(KEY_SELECTED_TYPE, selectedType);
        if (pendingDialogField != null) {
            outState.putString(KEY_DIALOG_FIELD, pendingDialogField);
        }
    }

    private static Map<String, String> restoreDraft(@NonNull Bundle savedState) {
        HashMap<?, ?> rawDraft =
                BundleCompat.getSerializable(savedState, KEY_DRAFT, HashMap.class);
        if (rawDraft == null) {
            return new LinkedHashMap<>();
        }
        return castStringMap(rawDraft);
    }

    private static Map<String, String> castStringMap(Map<?, ?> raw) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> rawEntry : raw.entrySet()) {
            if (rawEntry.getKey() instanceof String && rawEntry.getValue() instanceof String) {
                out.put((String) rawEntry.getKey(), (String) rawEntry.getValue());
            }
        }
        return out;
    }

    private void bindForm(@Nullable GovernmentIDModel existing) {
        isEdit = existing != null;
        editingItem = existing;

        formContainer = findViewById(R.id.government_id_form_container);
        View btnSave = findViewById(R.id.form_primary_action);
        TextView textSaveLabel = findViewById(R.id.form_primary_action_label);

        // Draft holds every typed value so switching types never loses input.
        draftValues.clear();
        textInputs.clear();
        dropdownValues.clear();
        if (isEdit) {
            draftValues.putAll(existing.getFields());
        }
        if (hasSavedState && savedDraft != null) {
            draftValues.putAll(savedDraft);
        }

        selectedType = 0;
        knownType = true;
        if (isEdit) {
            textSaveLabel.setText(R.string.action_update);
            if (GovernmentIDModel.isKnownType(existing.getIdType())) {
                selectedType = GovernmentIDModel.indexOf(existing.getIdType());
            } else {
                knownType = false;
            }
        } else {
            textSaveLabel.setText(R.string.action_save);
            Ui.makeSaveButtonFullWidth(btnSave);
        }
        if (hasSavedState && knownType) {
            String[] names = GovernmentIDModel.getTypeNames();
            if (savedSelectedType >= 0 && savedSelectedType < names.length) {
                selectedType = savedSelectedType;
            }
        }

        dotsContainer = findViewById(R.id.government_id_design_indicator);
        dotsContainer.setVisibility(View.VISIBLE);

        designAdapter = new GovernmentIdDesignAdapter(pos -> {
            if (isEdit && !knownType) {
                showMessage(R.string.msg_id_type_fixed);
                return;
            }
            if (pos != selectedType) {
                applyIdTypeSelection(pos);
            } else if (designCarousel != null) {
                designCarousel.smoothScrollToPosition(pos);
            }
        });
        designCarousel = findViewById(R.id.government_id_design_carousel);
        designLayoutManager =
                new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false);
        designCarousel.setLayoutManager(designLayoutManager);
        designCarousel.setAdapter(designAdapter);
        designCarousel.setHasFixedSize(false);
        designCarousel.setItemViewCacheSize(4);
        designCarousel.addItemDecoration(Ui.carouselGapDecoration(this));
        designSnapHelper = new PagerSnapHelper();
        designSnapHelper.attachToRecyclerView(designCarousel);

        Ui.buildDots(this, dotsContainer, designAdapter.getTypeCount(), selectedType);

        // Ignore carousel callbacks until the initial scroll settles.
        carouselReady = false;
        designCarousel.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                if (!carouselReady || !knownType) {
                    return;
                }
                if (newState != RecyclerView.SCROLL_STATE_IDLE) {
                    return;
                }
                View snapView = designSnapHelper.findSnapView(designLayoutManager);
                if (snapView != null) {
                    int pos = designLayoutManager.getPosition(snapView);
                    if (pos != RecyclerView.NO_POSITION && pos != selectedType) {
                        applyIdTypeSelection(pos);
                    }
                }
            }
        });
        final int scrollTo = selectedType;

        rebuildForm(currentSpec());
        refreshPreview();

        designCarousel.post(() -> {
            if (scrollTo != 0) {
                designCarousel.scrollToPosition(scrollTo);
            }
            designCarousel.post(() -> carouselReady = true);
        });

        btnSave.setOnClickListener(v -> {
            GovernmentIDModel.IdType spec = currentSpec();
            String typeName = currentTypeName(spec);

            for (Map.Entry<String, EditText> textInput : textInputs.entrySet()) {
                draftValues.put(textInput.getKey(), textInput.getValue().getText().toString().trim());
            }
            Map<String, String> filtered = filteredDraft(spec, typeName);

            if (!validate(spec, filtered)) {
                return;
            }
            btnSave.setEnabled(false);

            if (isEdit) {
                String finalType = knownType ? typeName : existing.getIdType();
                final GovernmentIDModel updated = new GovernmentIDModel(
                        existing.getId(), finalType, filtered,
                        existing.getCreatedAt(), 0);
                persistId(updated, R.string.msg_updated, () -> db().updateIdCard(updated));
            } else {
                final GovernmentIDModel newCard = new GovernmentIDModel(typeName, filtered);
                persistId(newCard, R.string.msg_id_saved, () -> db().insertIdCard(newCard));
            }
        });

        View btnDelete = findViewById(R.id.form_destructive_action);
        if (isEdit) {
            btnDelete.setVisibility(View.VISIBLE);
            btnDelete.setOnClickListener(v -> confirmDeleteToTrash(
                    getString(R.string.confirm_delete_id_title),
                    getString(R.string.confirm_delete_id_message, existing.getIdType()),
                    () -> {
                        final int rowId = existing.getId();
                        vaultIo(() -> {
                            db().moveIdCardToTrash(rowId);
                            cache().invalidate();
                            runOnUiThread(() -> {
                                if (!isAlive()) return;
                                setResult(RESULT_OK);
                                finish();
                                app().notifyOnReturn(R.string.msg_deleted);
                            });
                        });
                    }));
        }
        reopenPendingDropdown();
    }

    private void persistId(GovernmentIDModel model, int doneMessage, Runnable write) {
        vaultIo(() -> {
            write.run();
            cache().invalidate();
            runOnUiThread(() -> {
                if (!isAlive()) return;
                app().notifyOnReturn(doneMessage);
                setResult(RESULT_OK);
                finish();
            });
        });
    }

    private String currentTypeName(GovernmentIDModel.IdType spec) {
        if (!knownType && editingItem != null) {
            return editingItem.getIdType();
        }
        return spec != null ? spec.name : GovernmentIDModel.getTypeNames()[0];
    }

    private GovernmentIDModel.IdType currentSpec() {
        if (!knownType && editingItem != null) {
            return GovernmentIDModel.genericType(editingItem.getIdType(), editingItem.getFields());
        }
        String[] names = GovernmentIDModel.getTypeNames();
        int pos = selectedType < 0 || selectedType >= names.length ? 0 : selectedType;
        return GovernmentIDModel.forName(names[pos]);
    }

    private Map<String, String> filteredDraft(GovernmentIDModel.IdType spec, String typeName) {
        Map<String, String> out = new LinkedHashMap<>();
        GovernmentIDModel.IdType use = spec;
        if (use == null) {
            use = GovernmentIDModel.isKnownType(typeName)
                    ? GovernmentIDModel.forName(typeName)
                    : GovernmentIDModel.genericType(typeName, draftValues);
        }
        for (GovernmentIDModel.IdField specField : use.fields) {
            String draftValue = draftValues.get(specField.key);
            out.put(specField.key, draftValue != null ? draftValue : "");
        }
        return out;
    }

    private void rebuildForm(GovernmentIDModel.IdType spec) {
        formContainer.removeAllViews();
        textInputs.clear();
        dropdownValues.clear();
        List<GovernmentIDModel.IdField> fields = spec.fields;
        for (int i = 0; i < fields.size(); i++) {
            GovernmentIDModel.IdField field = fields.get(i);
            ensureDraftValue(field);
            GovernmentIDModel.IdField second = i + 1 < fields.size() ? fields.get(i + 1) : null;
            boolean datePair = hasInputClass(field, InputType.TYPE_CLASS_DATETIME)
                    && hasInputClass(second, InputType.TYPE_CLASS_DATETIME);
            boolean shortPair = second != null && second.isDropdown()
                    && (hasInputClass(field, InputType.TYPE_CLASS_DATETIME)
                    || hasInputClass(field, InputType.TYPE_CLASS_NUMBER)
                    || field.isDropdown());
            boolean flaggedPair = second != null && field.pairWithNext;
            if (datePair || shortPair || flaggedPair) {
                ensureDraftValue(second);
                formContainer.addView(buildPairRow(field, second));
                i++;
            } else {
                formContainer.addView(buildFieldView(field));
            }
        }
    }

    /** Guarantees a draft slot so switching types never loses typed input. */
    private void ensureDraftValue(GovernmentIDModel.IdField field) {
        if (!draftValues.containsKey(field.key)) {
            draftValues.put(field.key, "");
        }
    }

    private View buildFieldView(GovernmentIDModel.IdField field) {
        if (field.isDropdown()) {
            return buildDropdownField(field);
        }
        return buildTextField(field);
    }

    private static boolean hasInputClass(GovernmentIDModel.IdField field, int inputClass) {
        return field != null && (field.inputType & InputType.TYPE_MASK_CLASS) == inputClass;
    }

    /** Two short fields side by side with equal weight and a 6dp middle gap. */
    private View buildPairRow(GovernmentIDModel.IdField first, GovernmentIDModel.IdField second) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = Ui.dp(this, 16);
        row.setLayoutParams(rowParams);

        View left = buildFieldView(first);
        LinearLayout.LayoutParams leftParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        leftParams.setMarginEnd(Ui.dp(this, 6));
        left.setLayoutParams(leftParams);

        View right = buildFieldView(second);
        LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rightParams.setMarginStart(Ui.dp(this, 6));
        right.setLayoutParams(rightParams);

        row.addView(left);
        row.addView(right);
        return row;
    }

    private LinearLayout makeFieldWrap() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wrapParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wrapParams.topMargin = Ui.dp(this, 16);
        wrap.setLayoutParams(wrapParams);
        return wrap;
    }

    private TextView makeFieldLabel(GovernmentIDModel.IdField field) {
        TextView label = new TextView(this);
        label.setText(field.required ? field.label + " *" : field.label);
        label.setTextColor(getResources().getColor(R.color.dashboard_muted, null));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        label.setMaxLines(1);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return label;
    }

    private void styleInputBox(View box) {
        box.setBackgroundResource(R.drawable.bg_dashboard_card);
        int horizontalPadding = Ui.dp(this, 16);
        int verticalPadding = Ui.dp(this, 14);
        box.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding);
    }

    private void styleFieldText(TextView text) {
        text.setTextColor(getResources().getColor(R.color.text_primary, null));
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        text.setSingleLine(true);
        text.setEllipsize(android.text.TextUtils.TruncateAt.END);
    }

    private View buildTextField(GovernmentIDModel.IdField field) {
        LinearLayout wrap = makeFieldWrap();
        wrap.addView(makeFieldLabel(field));

        EditText input = new EditText(this);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inputParams.topMargin = Ui.dp(this, 8);
        input.setLayoutParams(inputParams);
        styleInputBox(input);
        input.setHint(field.hint);
        input.setHintTextColor(getResources().getColor(R.color.hint_text, null));
        styleFieldText(input);
        input.setInputType(field.inputType);
        if (field.maxLength > 0) {
            input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(field.maxLength)});
        }
        String current = draftValues.get(field.key);
        if (current != null && !current.isEmpty()) {
            input.setText(current);
        }
        input.addTextChangedListener(new Ui.SimpleTextWatcher() {
            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                draftValues.put(field.key, text.toString());
                refreshPreview();
            }
        });

        wrap.addView(input);
        textInputs.put(field.key, input);
        return wrap;
    }

    private View buildDropdownField(GovernmentIDModel.IdField field) {
        LinearLayout wrap = makeFieldWrap();
        wrap.addView(makeFieldLabel(field));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = Ui.dp(this, 8);
        row.setLayoutParams(rowParams);
        styleInputBox(row);
        row.setClickable(true);
        row.setFocusable(true);

        TextView valueView = new TextView(this);
        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        valueView.setLayoutParams(valueParams);
        styleFieldText(valueView);
        valueView.setMaxLines(1);
        valueView.setTag(field.key);
        valueView.setFocusable(true);
        valueView.setFocusableInTouchMode(true);

        String current = draftValues.get(field.key);
        if (current == null) {
            current = "";
        }
        if (current.isEmpty()) {
            valueView.setText(R.string.hint_dropdown_select);
            valueView.setAlpha(0.4f);
        } else {
            valueView.setText(current);
            valueView.setAlpha(1f);
        }

        ImageView chevron = new ImageView(this);
        chevron.setImageResource(R.drawable.ic_dropdown);
        LinearLayout.LayoutParams chevParams = new LinearLayout.LayoutParams(Ui.dp(this, 20), Ui.dp(this, 20));
        chevron.setLayoutParams(chevParams);
        chevron.setContentDescription(getString(R.string.cd_select_field, field.label));

        row.addView(valueView);
        row.addView(chevron);

        row.setOnClickListener(v -> showDropdownDialog(field, valueView));

        wrap.addView(row);

        dropdownValues.put(field.key, valueView);
        return wrap;
    }

    private void showDropdownDialog(GovernmentIDModel.IdField field, TextView valueView) {
        int checked = Ui.indexOfIgnoreCase(field.options, draftValues.get(field.key));
        pendingDialogField = field.key;
        AlertDialog dialog = Ui.singleChoice(this,
                field.label, field.options, checked, pos -> {
                    pendingDialogField = null;
                    String picked = field.options[pos];
                    draftValues.put(field.key, picked);
                    valueView.setText(picked);
                    valueView.setAlpha(1f);
                    hideFieldError(field.key);
                    refreshPreview();
                });
        dialog.setOnDismissListener(d -> {
            if (field.key.equals(pendingDialogField)) {
                pendingDialogField = null;
            }
        });
        trackDialog(dialog);
    }

    private void reopenPendingDropdown() {
        if (pendingDialogField == null || formContainer == null) {
            return;
        }
        String key = pendingDialogField;
        pendingDialogField = null;
        View tagged = formContainer.findViewWithTag(key);
        if (!(tagged instanceof TextView)) {
            return;
        }
        TextView valueView = (TextView) tagged;
        GovernmentIDModel.IdField target = findDropdownField(key);
        if (target == null) {
            return;
        }
        final GovernmentIDModel.IdField field = target;
        formContainer.post(() -> showDropdownDialog(field, valueView));
    }

    private static GovernmentIDModel.IdField findDropdownField(String key) {
        for (GovernmentIDModel.IdType type : GovernmentIDModel.getAllTypes()) {
            for (GovernmentIDModel.IdField f : type.fields) {
                if (key.equals(f.key) && f.isDropdown()) {
                    return f;
                }
            }
        }
        return null;
    }

    private boolean validate(GovernmentIDModel.IdType spec, Map<String, String> values) {
        // Document rules first so the user sees the document error, then required.
        FieldOffense offense = documentOffense(spec, values);
        if (offense == null) {
            offense = requiredFieldOffense(spec, values);
        }
        if (offense == null) {
            return true;
        }
        flagFieldError(offense.fieldKey, offense.message);
        return false;
    }

    private static FieldOffense documentOffense(GovernmentIDModel.IdType spec, Map<String, String> values) {
        if (spec == null) {
            return null;
        }
        if (GovernmentIDModel.TYPE_TIN.equalsIgnoreCase(spec.name)) {
            return validateTinFields(values);
        } else if (GovernmentIDModel.TYPE_PHIL_HEALTH.equalsIgnoreCase(spec.name)) {
            return validatePhilHealthFields(values);
        } else if (GovernmentIDModel.TYPE_NATIONAL_ID.equalsIgnoreCase(spec.name)) {
            return validateNationalIdFields(values);
        } else if (GovernmentIDModel.TYPE_PASSPORT.equalsIgnoreCase(spec.name)) {
            return validatePassportFields(values);
        } else if (GovernmentIDModel.TYPE_DRIVING_LICENSE.equalsIgnoreCase(spec.name)) {
            return validateDrivingLicenseFields(values);
        } else if (GovernmentIDModel.TYPE_SSS.equalsIgnoreCase(spec.name)) {
            return validateSssFields(values);
        }
        return null;
    }

    private static final int MIN_SENSITIVE_ALNUM = 4;

    /** Required/sensitive pass over the spec, null when clean. */
    private static FieldOffense requiredFieldOffense(GovernmentIDModel.IdType spec, Map<String, String> values) {
        for (GovernmentIDModel.IdField specField : spec.fields) {
            String fieldValue = trimmed(values.get(specField.key));
            if (specField.required && fieldValue.isEmpty()) {
                return new FieldOffense(specField.key, specField.label + " is required");
            }
            if (specField.sensitive && !fieldValue.isEmpty()
                    && NON_ALNUM.matcher(fieldValue).replaceAll("").length() < MIN_SENSITIVE_ALNUM) {
                return new FieldOffense(specField.key, specField.label + " looks too short");
            }
        }
        return null;
    }

    /** One validation failure: field plus message. */
    private static final class FieldOffense {
        final String fieldKey;
        final String message;

        FieldOffense(String fieldKey, String message) {
            this.fieldKey = fieldKey;
            this.message = message;
        }
    }

    private static FieldOffense validateTinFields(Map<String, String> values) {
        String tinError = exactDigitNumberError("TIN", values.get("tinNumber"), 12);
        if (tinError != null) {
            return new FieldOffense("tinNumber", tinError);
        }

        String dobError = numericDateError("Date of Birth", values.get("dateOfBirth"));
        if (dobError != null) {
            return new FieldOffense("dateOfBirth", dobError);
        }
        String issueError = numericDateError("Date of Issue", values.get("dateOfIssue"));
        if (issueError != null) {
            return new FieldOffense("dateOfIssue", issueError);
        }
        return null;
    }

    private static FieldOffense validatePhilHealthFields(Map<String, String> values) {
        String numberError = exactDigitNumberError("PhilHealth No.", values.get("philHealthNumber"), 12);
        if (numberError != null) {
            return new FieldOffense("philHealthNumber", numberError);
        }

        String dobError = numericDateError("Date of Birth", values.get("dateOfBirth"));
        if (dobError != null) {
            return new FieldOffense("dateOfBirth", dobError);
        }
        return null;
    }

    private static FieldOffense validateNationalIdFields(Map<String, String> values) {
        String psnError = exactDigitNumberError("PSN", values.get("psn"), 16);
        if (psnError != null) {
            return new FieldOffense("psn", psnError);
        }

        String dobError = numericDateError("Date of Birth", values.get("birth_date"));
        if (dobError != null) {
            return new FieldOffense("birth_date", dobError);
        }
        String issueError = numericDateError("Date of Issue", values.get("issue_date"));
        if (issueError != null) {
            return new FieldOffense("issue_date", issueError);
        }
        return null;
    }

    private static FieldOffense validatePassportFields(Map<String, String> values) {
        String dobError = numericDateError("Date of Birth", values.get("birth_date"));
        if (dobError != null) {
            return new FieldOffense("birth_date", dobError);
        }
        String issueError = numericDateError("Date of Issue", values.get("issue_date"));
        if (issueError != null) {
            return new FieldOffense("issue_date", issueError);
        }
        String expiryError = numericDateError("Date of Expiry", values.get("expiry_date"));
        if (expiryError != null) {
            return new FieldOffense("expiry_date", expiryError);
        }
        return null;
    }

    private static FieldOffense validateDrivingLicenseFields(Map<String, String> values) {
        String dobError = numericDateError("Date of Birth", values.get("birth_date"));
        if (dobError != null) {
            return new FieldOffense("birth_date", dobError);
        }
        String expiryError = numericDateError("Expiry Date", values.get("expiry_date"));
        if (expiryError != null) {
            return new FieldOffense("expiry_date", expiryError);
        }
        String serialRaw = trimmed(values.get("serial_no"));
        if (!serialRaw.isEmpty() && HAS_LETTER.matcher(serialRaw).matches()) {
            return new FieldOffense("serial_no", "Serial number must contain numbers only (no letters)");
        }
        return null;
    }
    private static FieldOffense validateSssFields(Map<String, String> values) {
        String ssError = exactDigitNumberError("SS Number", values.get("ss_number"), 10);
        if (ssError != null) {
            return new FieldOffense("ss_number", ssError);
        }

        String dobError = numericDateError("Date of Birth", values.get("birth_date"));
        if (dobError != null) {
            return new FieldOffense("birth_date", dobError);
        }
        return null;
    }
    private static String exactDigitNumberError(String label, String rawValue, int digits) {
        String raw = trimmed(rawValue);
        if (raw.isEmpty()) {
            return label + " is required";
        }
        if (HAS_LETTER.matcher(raw).matches()) {
            return label + " must contain numbers only (no letters)";
        }
        if (NON_DIGITS.matcher(raw).replaceAll("").length() != digits) {
            return label + " must be exactly " + digits + " digits";
        }
        return null;
    }

    /** Date shape: exactly 8 digits (YYYYMMDD). Empty is valid. */
    private static String numericDateError(String label, String rawValue) {
        String raw = trimmed(rawValue);
        if (raw.isEmpty()) {
            return null;
        }
        if (!DATE_8.matcher(raw).matches()) {
            return label + " must be 8 digits (YYYYMMDD)";
        }
        return null;
    }

    /** Flags one field invalid with focus. */
    private void flagFieldError(String key, String message) {
        EditText input = textInputs.get(key);
        if (input != null) {
            input.setError(message);
            input.requestFocus();
            return;
        }
        TextView value = dropdownValues.get(key);
        if (value != null) {
            value.setError(message);
            value.requestFocus();
            return;
        }
        showMessage(message);
    }

    /** Clears a dropdown's setError once the user picks a value. */
    private void hideFieldError(String key) {
        TextView value = dropdownValues.get(key);
        if (value != null) {
            value.setError(null);
        }
    }

    private static String trimmed(String value) {
        return value != null ? value.trim() : "";
    }

    private void applyIdTypeSelection(int pos) {
        if (pos < 0 || pos >= GovernmentIDModel.getTypeNames().length) {
            return;
        }
        selectedType = pos;
        rebuildForm(currentSpec());
        if (dotsContainer != null && designAdapter != null) {
            Ui.buildDots(this, dotsContainer, designAdapter.getTypeCount(), pos);
        }
        refreshPreview();
    }
}