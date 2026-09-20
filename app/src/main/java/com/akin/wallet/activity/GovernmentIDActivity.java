package com.akin.wallet.activity;

import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
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
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.akin.wallet.R;
import com.akin.wallet.adapter.GovermentIdDesignAdapter;
import com.akin.wallet.db.AppDatabaseHelper;
import com.akin.wallet.db.VaultWarmCache;
import com.akin.wallet.model.GovernmentIDModel;
import com.akin.wallet.util.Ui;
import com.google.android.material.snackbar.Snackbar;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Government ID creation/edit screen as a full screen. Add mode when no ID is
 * passed; edit mode otherwise. The type-picker carousel lives in the screen
 * content; dropdowns stay alert dialogs. Callers refresh in onResume;
 * RESULT_OK is set on save.
 */
public class GovernmentIDActivity extends AppCompatActivity {

    public static final String EXTRA_ID = "extra_id";

    /**
     * Intent that opens this screen to edit an existing ID. Carries the row id
     * only — the screen re-queries the vault, so document data never travels
     * as Intent extras and edits always start current.
     */
    public static Intent editIntent(@NonNull Context context, @NonNull GovernmentIDModel item) {
        return new Intent(context, GovernmentIDActivity.class)
                .putExtra(EXTRA_ID, item.getId());
    }

    private AppDatabaseHelper dbHelper;
    /** Owned dialogs: dismissed in onDestroy so rotation cannot leak windows. */
    private androidx.appcompat.app.AlertDialog activeDialog;

    // Rotation state. Inputs are built programmatically (no view ids), so the
    // draft + selected type are saved explicitly; bindForm seeds from them.
    private static final String KEY_DRAFT = "draft_values";
    private static final String KEY_SELECTED_TYPE = "selected_type";
    private Map<String, String> savedDraft;
    private int savedSelectedType;
    private boolean hasSavedState;
    // Live references for onSaveInstanceState (bindForm owns the locals).
    private Map<String, String> currentDraft;
    private int[] currentSelected;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_government_id);
        Ui.applySystemBars(this);

        dbHelper = VaultWarmCache.get(this).helper();

        Ui.setupBackToolbar(this, R.id.toolbar);

        int id = getIntent().getIntExtra(EXTRA_ID, -1);
        if (savedInstanceState != null) {
            // Rotation: re-seed below from the user's in-progress draft.
            hasSavedState = true;
            savedSelectedType = savedInstanceState.getInt(KEY_SELECTED_TYPE, 0);
            savedDraft = restoreDraft(savedInstanceState);
        }
        if (id == -1) {
            bindForm(null);
        } else {
            GovernmentIDModel stored = dbHelper.getIdCardById(id);
            if (stored == null) {
                finish();
                return;
            }
            bindForm(stored);
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (currentDraft != null) {
            outState.putSerializable(KEY_DRAFT, new LinkedHashMap<>(currentDraft));
        }
        if (currentSelected != null) {
            outState.putInt(KEY_SELECTED_TYPE, currentSelected[0]);
        }
    }

    private static Map<String, String> restoreDraft(@NonNull Bundle savedState) {
        java.util.HashMap<?, ?> rawDraft =
                androidx.core.os.BundleCompat.getSerializable(
                        savedState, KEY_DRAFT, java.util.HashMap.class);
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

    @Override
    protected void onDestroy() {
        Ui.dismissOwnedDialog(activeDialog);
        activeDialog = null;
        // Shared helper lives with the process (VaultWarmCache); never close per-screen.
        dbHelper = null;
        super.onDestroy();
    }

    private void bindForm(@Nullable GovernmentIDModel existing) {
        final boolean isEdit = existing != null;


        LinearLayout formContainer = findViewById(R.id.form_container);
        // Action row mirrors the bank card screen: btn_save is the outline
        // container, text_save_label carries the Save/Update caption.
        View btnSave = findViewById(R.id.btn_save);
        TextView textSaveLabel = findViewById(R.id.text_save_label);

        // Draft holds EVERY typed value (even for hidden types) so switching
        // ID type back and forth never loses input; save filters to active spec.
        final Map<String, String> draftValues = new LinkedHashMap<>();
        if (isEdit) {
            draftValues.putAll(existing.getFields());
        }
        // Rotation: the user's in-progress draft wins over stored values.
        if (hasSavedState && savedDraft != null) {
            draftValues.putAll(savedDraft);
        }
        currentDraft = draftValues;
        final Map<String, EditText> textInputs = new LinkedHashMap<>();
        // Dropdown value texts, registered for setError like text inputs so a
        // failed save flags the value itself — same behavior as Date of Birth.
        final Map<String, TextView> dropdownValues = new LinkedHashMap<>();

        final int[] selectedType = {0};
        currentSelected = selectedType;
        final boolean[] knownType = {true};

        if (isEdit) {
            textSaveLabel.setText(R.string.action_update);
            if (GovernmentIDModel.isKnownType(existing.getIdType())) {
                selectedType[0] = GovernmentIDModel.indexOf(existing.getIdType());
            } else {
                knownType[0] = false;
                selectedType[0] = 0;
            }
        } else {
            // Add mode keeps a single full-width Save button, same as the bank
            // form. The delete view is GONE, so its row margin is dropped to
            // avoid a trailing 8dp gap.
            textSaveLabel.setText(R.string.action_save);
            LinearLayout.LayoutParams saveParams =
                    (LinearLayout.LayoutParams) btnSave.getLayoutParams();
            saveParams.setMarginEnd(0);
            btnSave.setLayoutParams(saveParams);
        }
        if (hasSavedState) {
            String[] names = GovernmentIDModel.getTypeNames();
            if (savedSelectedType >= 0 && savedSelectedType < names.length
                    && knownType[0]) {
                selectedType[0] = savedSelectedType;
            }
        }

        final GovermentIdDesignAdapter[] adapterRef = new GovermentIdDesignAdapter[1];
        final RecyclerView[] carouselRef = new RecyclerView[1];
        final LinearLayout dotsContainer = findViewById(R.id.dots_container);
        dotsContainer.setVisibility(View.VISIBLE);

        Runnable refreshPreview = () -> {
            if (adapterRef[0] != null) {
                adapterRef[0].updatePreview(draftValues);
            }
        };

        // Bank-style picker: each carousel page IS an ID type's authentic face.
        // Swiping (or tapping) a page selects that type and rebuilds the form.
        GovermentIdDesignAdapter designAdapter = new GovermentIdDesignAdapter(pos -> {
            if (isEdit && !knownType[0]) {
                Snackbar.make(findViewById(android.R.id.content),
                        "ID type is fixed for entries from a newer version",
                        Snackbar.LENGTH_SHORT).show();
                return;
            }
            if (pos != selectedType[0]) {
                applyIdTypeSelection(pos, formContainer, draftValues,
                        textInputs, dropdownValues, refreshPreview, dotsContainer, adapterRef[0],
                        selectedType);
            } else if (carouselRef[0] != null) {
                carouselRef[0].smoothScrollToPosition(pos);
            }
        });
        adapterRef[0] = designAdapter;
        RecyclerView recyclerDesign = findViewById(R.id.recycler_card_design);
        carouselRef[0] = recyclerDesign;
        LinearLayoutManager layoutManager =
                new LinearLayoutManager(GovernmentIDActivity.this, LinearLayoutManager.HORIZONTAL, false);
        recyclerDesign.setLayoutManager(layoutManager);
        recyclerDesign.setAdapter(designAdapter);
        // Same 12dp inter-card gap as the dashboard carousel so the form
        // picker spaces pages exactly like Home.
        recyclerDesign.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(@NonNull Rect outRect, @NonNull View child,
                                       @NonNull RecyclerView parent,
                                       @NonNull RecyclerView.State state) {
                int position = parent.getChildAdapterPosition(child);
                if (position != RecyclerView.NO_POSITION
                        && position < state.getItemCount() - 1) {
                    float density = parent.getResources().getDisplayMetrics().density;
                    outRect.right = (int) (12 * density);
                }
            }
        });
        PagerSnapHelper snapHelper = new PagerSnapHelper();
        snapHelper.attachToRecyclerView(recyclerDesign);

        setupDots(dotsContainer, designAdapter.getTypeCount(), selectedType[0]);

        // Guard: ignore carousel callbacks until the initial scroll to the
        // edited type has settled. Otherwise, the initial layout at position 0
        // fires onScrolled and rebuilds the form for the wrong type (empty/
        // wrong fields until the user swipes).
        final boolean[] carouselReady = {false};
        recyclerDesign.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                if (!carouselReady[0] || !knownType[0]) {
                    return;
                }
                if (newState != RecyclerView.SCROLL_STATE_IDLE) {
                    return;
                }
                View snapView = snapHelper.findSnapView(layoutManager);
                if (snapView != null) {
                    int pos = layoutManager.getPosition(snapView);
                    if (pos != RecyclerView.NO_POSITION && pos != selectedType[0]) {
                        applyIdTypeSelection(pos, formContainer, draftValues,
                                textInputs, dropdownValues, refreshPreview, dotsContainer, adapterRef[0],
                                selectedType);
                    }
                }
            }
        });
        final int scrollTo = selectedType[0];

        // Initial form for the selected (or edited) type. The header title is
        // gone by design — the carousel page itself names the type.
        rebuildForm(formContainer, currentSpec(selectedType[0], knownType[0],
                isEdit ? existing : null), draftValues, textInputs, dropdownValues,
                refreshPreview);
        refreshPreview.run();

        // Position the carousel on the edited type, then enable callbacks.
        // Nested post ensures the scroll layout pass has run before we listen.
        recyclerDesign.post(() -> {
            if (scrollTo != 0) {
                recyclerDesign.scrollToPosition(scrollTo);
            }
            recyclerDesign.post(() -> carouselReady[0] = true);
        });

        btnSave.setOnClickListener(v -> {
            String typeName = currentTypeName(selectedType[0], knownType[0],
                    isEdit ? existing.getIdType() : null);
            GovernmentIDModel.IdType spec = currentSpec(selectedType[0], knownType[0],
                    isEdit ? existing : null);

            // Pull latest text (watchers already keep draftValues live; this is a safety net).
            for (Map.Entry<String, EditText> textInput : textInputs.entrySet()) {
                draftValues.put(textInput.getKey(), textInput.getValue().getText().toString().trim());
            }
            Map<String, String> filtered = filteredDraft(typeName, draftValues);

            if (!validate(spec, filtered, textInputs, dropdownValues)) {
                return;
            }

            if (isEdit) {
                // createdAt rides along untouched (creation order is immutable);
                // updatedAt=0 tells the DB helper to stamp now on write.
                // Known types may have been switched via selector; use the new
                // name.
                String finalType = knownType[0] ? typeName : existing.getIdType();
                GovernmentIDModel updated = new GovernmentIDModel(
                        existing.getId(), finalType, filtered,
                        existing.getCreatedAt(), 0);
                dbHelper.updateIdCard(updated);
                Ui.notifyOnReturn(R.string.msg_updated);
            } else {
                GovernmentIDModel newCard = new GovernmentIDModel(typeName, filtered);
                dbHelper.insertIdCard(newCard);
                Ui.notifyOnReturn(R.string.msg_id_saved);
            }
            VaultWarmCache.get(GovernmentIDActivity.this).invalidate();
            setResult(RESULT_OK);
            finish();
        });

        // Delete on this screen (no IDs tab), shown in-row in edit mode
        // only — same placement and outline-red style as the bank card screen.
        // Soft-delete behind the scenes: the dialog reads as a normal delete
        // while the row moves to Trash (Settings). Copy and toasts stay ID-specific.
        View btnDelete = findViewById(R.id.btn_delete);
        if (isEdit) {
            btnDelete.setVisibility(View.VISIBLE);
            btnDelete.setOnClickListener(v -> {
                Ui.dismissOwnedDialog(activeDialog);
                activeDialog = Ui.confirmDelete(GovernmentIDActivity.this,
                        "Delete ID",
                        "Are you sure you want to delete this "
                                + existing.getIdType() + "?",
                        () -> {
                            dbHelper.moveIdCardToTrash(existing.getId());
                            VaultWarmCache.get(GovernmentIDActivity.this).invalidate();
                            setResult(RESULT_OK);
                            finish();
                            Ui.notifyOnReturn(R.string.msg_deleted);
                        });
            });
        }


    }

    private String currentTypeName(int selected, boolean known, @Nullable String existingType) {
        if (!known && existingType != null) {
            return existingType;
        }
        String[] names = GovernmentIDModel.getTypeNames();
        if (selected < 0 || selected >= names.length) {
            return names[0];
        }
        return names[selected];
    }

    private GovernmentIDModel.IdType currentSpec(int selected, boolean known,
                                          @Nullable GovernmentIDModel existing) {
        if (!known && existing != null) {
            return GovernmentIDModel.genericType(existing.getIdType(), existing.getFields());
        }
        String[] names = GovernmentIDModel.getTypeNames();
        if (selected < 0 || selected >= names.length) {
            selected = 0;
        }
        return GovernmentIDModel.forName(names[selected]);
    }

    private Map<String, String> filteredDraft(String typeName, Map<String, String> draft) {
        Map<String, String> out = new LinkedHashMap<>();
        GovernmentIDModel.IdType spec = GovernmentIDModel.isKnownType(typeName)
                ? GovernmentIDModel.forName(typeName)
                : GovernmentIDModel.genericType(typeName, draft);
        for (GovernmentIDModel.IdField specField : spec.fields) {
            String draftValue = draft.get(specField.key);
            out.put(specField.key, draftValue != null ? draftValue : "");
        }
        return out;
    }

    private void rebuildForm(LinearLayout container, GovernmentIDModel.IdType spec,
                             Map<String, String> draft, Map<String, EditText> textInputs,
                             Map<String, TextView> dropdownValues, Runnable onChanged) {
        container.removeAllViews();
        textInputs.clear();
        dropdownValues.clear();
        List<GovernmentIDModel.IdField> fields = spec.fields;
        for (int i = 0; i < fields.size(); i++) {
            GovernmentIDModel.IdField field = fields.get(i);
            ensureDraftValue(draft, field);
            // Row pairing, greedy left-to-right: generic short-field pairs
            // ((date, date), or a date/number/picker followed by a picker)
            // plus any explicit pairWithNext flag from the spec (custom rows
            // the generic rules can't infer, for example, two text fields). A flagged
            // last field has no next and simply stands alone.
            GovernmentIDModel.IdField second = i + 1 < fields.size() ? fields.get(i + 1) : null;
            boolean datePair = isDateField(field) && isDateField(second);
            boolean shortPair = second != null && second.isDropdown()
                    && (isDateField(field) || isNumberField(field) || field.isDropdown());
            boolean flaggedPair = second != null && field.pairWithNext;
            if (datePair || shortPair || flaggedPair) {
                ensureDraftValue(draft, second);
                container.addView(buildPairRow(field, second, draft, textInputs, dropdownValues, onChanged));
                i++;
            } else {
                container.addView(buildFieldView(field, draft, textInputs, dropdownValues, onChanged));
            }
        }
    }

    /** Guarantees a draft slot so switching types never loses typed input. */
    private static void ensureDraftValue(Map<String, String> draft, GovernmentIDModel.IdField field) {
        if (!draft.containsKey(field.key)) {
            draft.put(field.key, "");
        }
    }

    /** Dispatches to the dropdown or free-text builder for one field. */
    private View buildFieldView(GovernmentIDModel.IdField field, Map<String, String> draft,
                                Map<String, EditText> textInputs, Map<String, TextView> dropdownValues,
                                Runnable onChanged) {
        if (field.isDropdown()) {
            return buildDropdownField(field, draft, dropdownValues, onChanged);
        }
        return buildTextField(field, draft, textInputs, onChanged);
    }

    /** Date fields carry the datetime input class (see IdField date). */
    private static boolean isDateField(GovernmentIDModel.IdField field) {
        return field != null
                && (field.inputType & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_DATETIME;
    }

    /** Number fields carry the number input class (see IdField number). */
    private static boolean isNumberField(GovernmentIDModel.IdField field) {
        return field != null
                && (field.inputType & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_NUMBER;
    }

    /**
     * Two short fields side by side with equal weight and the bank form's 6dp
     * middle gap. Reuses the standard field builders, then swaps their
     * full-width params for weighted row params (replacing, not adding to,
     * the 16dp top margin the builders set).
     */
    private View buildPairRow(GovernmentIDModel.IdField first, GovernmentIDModel.IdField second,
                              Map<String, String> draft, Map<String, EditText> textInputs,
                              Map<String, TextView> dropdownValues, Runnable onChanged) {
        LinearLayout row = new LinearLayout(GovernmentIDActivity.this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = Ui.dp(this, 16);
        row.setLayoutParams(rowParams);

        View left = buildFieldView(first, draft, textInputs, dropdownValues, onChanged);
        LinearLayout.LayoutParams leftParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        leftParams.setMarginEnd(Ui.dp(this, 6));
        left.setLayoutParams(leftParams);

        View right = buildFieldView(second, draft, textInputs, dropdownValues, onChanged);
        LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rightParams.setMarginStart(Ui.dp(this, 6));
        right.setLayoutParams(rightParams);

        row.addView(left);
        row.addView(right);
        return row;
    }

    private View buildTextField(GovernmentIDModel.IdField field, Map<String, String> draft,
                                Map<String, EditText> textInputs, Runnable onChanged) {
        LinearLayout wrap = new LinearLayout(GovernmentIDActivity.this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wrapParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wrapParams.topMargin = Ui.dp(this, 16);
        wrap.setLayoutParams(wrapParams);

        TextView label = new TextView(GovernmentIDActivity.this);
        label.setText(field.required ? field.label + " *" : field.label);
        label.setTextColor(getResources().getColor(R.color.dashboard_muted, null));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        wrap.addView(label);

        EditText input = new EditText(GovernmentIDActivity.this);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inputParams.topMargin = Ui.dp(this, 8);
        input.setLayoutParams(inputParams);
        input.setBackgroundResource(R.drawable.bg_dashboard_card);
        input.setHint(field.hint);
        input.setHintTextColor(getResources().getColor(R.color.hint_text, null));
        input.setTextColor(getResources().getColor(R.color.text_primary, null));
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        input.setSingleLine(true);
        input.setInputType(field.inputType);
        if (field.maxLength > 0) {
            input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(field.maxLength)});
        }
        int horizontalPadding = Ui.dp(this, 16);
        int verticalPadding = Ui.dp(this, 14);
        input.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding);
        String current = draft.get(field.key);
        if (current != null && !current.isEmpty()) {
            input.setText(current);
        }
        input.addTextChangedListener(new Ui.SimpleTextWatcher() {
            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                draft.put(field.key, text.toString());
                onChanged.run();
            }
        });

        wrap.addView(input);
        textInputs.put(field.key, input);
        return wrap;
    }

    private View buildDropdownField(GovernmentIDModel.IdField field, Map<String, String> draft,
                                    Map<String, TextView> dropdownValues, Runnable onChanged) {
        LinearLayout wrap = new LinearLayout(GovernmentIDActivity.this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wrapParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wrapParams.topMargin = Ui.dp(this, 16);
        wrap.setLayoutParams(wrapParams);

        TextView label = new TextView(GovernmentIDActivity.this);
        label.setText(field.required ? field.label + " *" : field.label);
        label.setTextColor(getResources().getColor(R.color.dashboard_muted, null));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        wrap.addView(label);

        LinearLayout row = new LinearLayout(GovernmentIDActivity.this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = Ui.dp(this, 8);
        row.setLayoutParams(rowParams);
        row.setBackgroundResource(R.drawable.bg_dashboard_card);
        int horizontalPadding = Ui.dp(this, 16);
        int verticalRowPadding = Ui.dp(this, 14);
        row.setPadding(horizontalPadding, verticalRowPadding, horizontalPadding, verticalRowPadding);
        row.setClickable(true);
        row.setFocusable(true);

        TextView valueView = new TextView(GovernmentIDActivity.this);
        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        valueView.setLayoutParams(valueParams);
        valueView.setSingleLine(true);
        valueView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        valueView.setTextColor(getResources().getColor(R.color.text_primary, null));
        // Focusable so a failed save can show the same setError popup a text
        // field shows — dropdown errors behave exactly like date-of-birth
        // errors, with no extra label below the field.
        valueView.setFocusable(true);
        valueView.setFocusableInTouchMode(true);

        String current = draft.get(field.key);
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

        ImageView chevron = new ImageView(GovernmentIDActivity.this);
        chevron.setImageResource(R.drawable.ic_dropdown);
        LinearLayout.LayoutParams chevParams = new LinearLayout.LayoutParams(Ui.dp(this, 20), Ui.dp(this, 20));
        chevron.setLayoutParams(chevParams);
        chevron.setContentDescription(getString(R.string.cd_select_field, field.label));

        row.addView(valueView);
        row.addView(chevron);

        row.setOnClickListener(v -> {
            int checkedPosition = Ui.indexOfIgnoreCase(field.options, draft.get(field.key));
            Ui.dismissOwnedDialog(activeDialog);
            activeDialog = Ui.singleChoice(GovernmentIDActivity.this,
                    field.label, field.options, checkedPosition, selectedPosition -> {
                        String picked = field.options[selectedPosition];
                        draft.put(field.key, picked);
                        valueView.setText(picked);
                        valueView.setAlpha(1f);
                        hideFieldError(dropdownValues, field.key);
                        onChanged.run();
                    });
        });

        wrap.addView(row);

        // Registered for setError like a text input — a failed save flags the
        // value text itself, never a label below the field.
        dropdownValues.put(field.key, valueView);
        return wrap;
    }

    private boolean validate(GovernmentIDModel.IdType spec, Map<String, String> values,
                             Map<String, EditText> textInputs, Map<String, TextView> dropdownValues) {
        // Document-specific contracts run before the generic pass so the user
        // sees the document rule first: exactly-12-digit numeric TIN / PIN and
        // numeric date shapes (presence itself is covered by the generic
        // required check below).
        FieldOffense offense = documentOffense(spec, values);
        if (offense == null) {
            offense = requiredFieldOffense(spec, values);
        }
        if (offense == null) {
            return true;
        }
        flagFieldError(textInputs, dropdownValues, offense.fieldKey, offense.message);
        return false;
    }

    /** Document-specific offense for the active type, null when clean. */
    private static FieldOffense documentOffense(GovernmentIDModel.IdType spec, Map<String, String> values) {
        if (spec != null && GovernmentIDModel.TYPE_TIN.equalsIgnoreCase(spec.name)) {
            return validateTinFields(values);
        } else if (spec != null && GovernmentIDModel.TYPE_PHIL_HEALTH.equalsIgnoreCase(spec.name)) {
            return validatePhilHealthFields(values);
        } else if (spec != null && GovernmentIDModel.TYPE_NATIONAL_ID.equalsIgnoreCase(spec.name)) {
            return validateNationalIdFields(values);
        } else if (spec != null && GovernmentIDModel.TYPE_PASSPORT.equalsIgnoreCase(spec.name)) {
            return validatePassportFields(values);
        } else if (spec != null && GovernmentIDModel.TYPE_DRIVING_LICENSE.equalsIgnoreCase(spec.name)) {
            return validateDrivingLicenseFields(values);
        } else if (spec != null && GovernmentIDModel.TYPE_SSS.equalsIgnoreCase(spec.name)) {
            return validateSssFields(values);
        }
        return null;
    }

    /** Generic required/sensitive pass over the spec, null when clean. */
    private static FieldOffense requiredFieldOffense(GovernmentIDModel.IdType spec, Map<String, String> values) {
        for (GovernmentIDModel.IdField specField : spec.fields) {
            String fieldValue = trimmed(values.get(specField.key));
            if (specField.required && fieldValue.isEmpty()) {
                return new FieldOffense(specField.key, specField.label + " is required");
            }
            if (specField.sensitive && !fieldValue.isEmpty() && fieldValue.replaceAll("[^A-Za-z0-9]", "").length() < 4) {
                return new FieldOffense(specField.key, specField.label + " looks too short");
            }
        }
        return null;
    }

    /** One validation failure: which field plus what to tell the user. */
    private static final class FieldOffense {
        final String fieldKey;
        final String message;

        FieldOffense(String fieldKey, String message) {
            this.fieldKey = fieldKey;
            this.message = message;
        }
    }

    /**
     * TIN-specific document rules. Fail-fast in field order (TIN, birth, issue)
     * so the first offender gets focus, matching the generic pass behavior.
     *
     * <p>BIR TINs are exactly 12 digits with no alphabet (digit count is what
     * matters). Dates are exactly 8 digits (YYYYMMDD) — no calendar computation.
     */
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

    /**
     * PhilHealth document rules: 12-digit PhilHealth No. (grouped 3-3-3-3 on
     * the face, same presentational rule as the TIN) plus the shared numeric
     * date shape for birth. Fail-fast in field order, matching the TIN pass.
     */
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

    /**
     * National ID document rules: 16-digit numeric PSN (grouped 4-4-4-4 on
     * the face) plus the shared 8-digit shapes for birth and issue. Fail-fast
     * in field order, matching the other passes.
     */
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

    /**
     * Passport document rules: the shared 8-digit shapes for birth, issue and
     * expiry (number presence is covered by the generic required pass).
     * Fail-fast in field order, matching the other passes.
     */
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

    /**
     * Driver license document rules: the shared 8-digit shapes for birth and
     * expiry plus numeric-only serial (number presence is covered by the
     * generic required pass). Fail-fast in field order, matching the passes.
     */
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
        if (!serialRaw.isEmpty() && serialRaw.matches(".*[A-Za-z].*")) {
            return new FieldOffense("serial_no", "Serial number must contain numbers only (no letters)");
        }
        return null;
    }
    /**
     * SSS document rules: 10-digit numeric SS number plus the shared 8-digit
     * birth shape (other presence is covered by the generic required pass).
     * Fail-fast in field order, matching the other passes.
     */
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
    /**
     * Shared exact-length document-number rule (16-digit PSN, 12-digit TIN /
     * PhilHealth No., 10-digit SS number): no alphabet, exactly the expected
     * digit count. Returns the error message, or null when acceptable.
     */
    private static String exactDigitNumberError(String label, String rawValue, int digits) {
        String raw = trimmed(rawValue);
        if (raw.isEmpty()) {
            return label + " is required";
        }
        if (raw.matches(".*[A-Za-z].*")) {
            return label + " must contain numbers only (no letters)";
        }
        if (raw.replaceAll("\\D", "").length() != digits) {
            return label + " must be exactly " + digits + " digits";
        }
        return null;
    }

    /**
     * Numeric date-shape check: exactly 8 digits (YYYYMMDD) — the field caps
     * at 8 chars so dashes can't be typed. Empty is valid here — presence is
     * governed by the field's required flag, not format. No calendar math is
     * computed. Returns the error message, or null when acceptable.
     */
    private static String numericDateError(String label, String rawValue) {
        String raw = trimmed(rawValue);
        if (raw.isEmpty()) {
            return null;
        }
        // Exactly 8 digits, nothing else. This single check rejects alphabet,
        // month names, dashes, and wrong lengths with one message.
        if (!raw.matches("\\d{8}")) {
            return label + " must be 8 digits (YYYYMMDD)";
        }
        return null;
    }

    /**
     * Flags one field invalid with focus. Text inputs and dropdown
     * values both use setError plus focus, so every field — including Blood
     * Type — behaves exactly like Date of Birth. The toast survives only as a
     * last resort for a key bound to neither, which should never happen while
     * builders register every field.
     */
    private void flagFieldError(Map<String, EditText> textInputs, Map<String, TextView> dropdownValues,
                                String key, String message) {
        EditText input = textInputs.get(key);
        if (input != null) {
            input.setError(message);
            input.requestFocus();
            return;
        }
        TextView value = dropdownValues != null ? dropdownValues.get(key) : null;
        if (value != null) {
            value.setError(message);
            value.requestFocus();
            return;
        }
        Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_SHORT).show();
    }

    /** Clears a dropdown's setError once the user picks or clears a value. */
    private static void hideFieldError(Map<String, TextView> dropdownValues, String key) {
        TextView value = dropdownValues != null ? dropdownValues.get(key) : null;
        if (value != null) {
            value.setError(null);
        }
    }

    private static String trimmed(String value) {
        return value != null ? value.trim() : "";
    }

    private void applyIdTypeSelection(int pos,
                                      LinearLayout formContainer,
                                      Map<String, String> draft, Map<String, EditText> textInputs,
                                      Map<String, TextView> dropdownValues,
                                      Runnable refreshPreview, LinearLayout dotsContainer,
                                      GovermentIdDesignAdapter designAdapter, int[] selectedType) {
        if (pos < 0 || pos >= GovernmentIDModel.getTypeNames().length) {
            return;
        }
        selectedType[0] = pos;
        rebuildForm(formContainer, currentSpec(pos, true, null),
                draft, textInputs, dropdownValues, refreshPreview);
        if (dotsContainer != null && designAdapter != null) {
            setupDots(dotsContainer, designAdapter.getTypeCount(), pos);
        }
        refreshPreview.run();
    }

    private void setupDots(LinearLayout container, int count, int selected) {
        container.removeAllViews();
        int size = Ui.dp(this, 8);
        int margin = Ui.dp(this, 4);
        for (int i = 0; i < count; i++) {
            View dot = new View(GovernmentIDActivity.this);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
            params.setMargins(margin, 0, margin, 0);
            dot.setLayoutParams(params);
            dot.setBackgroundResource(R.drawable.bg_dot);
            dot.setAlpha(i == selected ? 1f : 0.3f);
            container.addView(dot);
        }
    }
}
