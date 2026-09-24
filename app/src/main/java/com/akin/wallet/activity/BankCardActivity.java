package com.akin.wallet.activity;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.akin.wallet.R;
import com.akin.wallet.adapter.BankCardDesignAdapter;
import com.akin.wallet.model.BankCardModel;
import com.akin.wallet.util.Ui;

import java.util.regex.Pattern;

public class BankCardActivity extends BaseVaultActivity {

    public static final String EXTRA_ID = "extra_id";

    public static Intent editIntent(@NonNull Context context, @NonNull BankCardModel item) {
        return new Intent(context, BankCardActivity.class)
                .putExtra(EXTRA_ID, item.getId());
    }

    // Order doubles as the persisted design/type index: never reorder without a DB migration.
    private static final String[] CARD_TYPES = {"Debit", "Credit", "Prepaid"};
    private static final String[] CARD_NETWORKS = {"Visa", "MasterCard"};

    // ISO/IEC 7812 card range is 13-19 digits; only Visa/MasterCard offered so CVV is 3.
    private static final int CARD_NUMBER_MIN_LEN = 16;
    private static final int CARD_NUMBER_MAX_LEN = 19;
    private static final int EXPIRY_DIGITS_LEN = 4;
    private static final int CVV_LEN = 3;
    private static final int PIN_MIN_LEN = 4;
    private static final int PIN_MAX_LEN = 6;
    private static final int BANK_NAME_MIN_LEN = 2;
    private static final int BANK_NAME_MAX_LEN = 50;
    private static final int HOLDER_NAME_MIN_LEN = 2;
    private static final int HOLDER_NAME_MAX_LEN = 50;
    private static final Pattern NON_DIGITS = Pattern.compile("\\D");
    private static final Pattern HOLDER_NAME =
            Pattern.compile("\\p{L}[\\p{L} .'-]*");

    // Rotation keys. Typed text is saved explicitly: async rebind runs after
    // view-state restore and would clobber in-progress edits.
    private static final String KEY_SELECTED_TYPE = "selected_type";
    private static final String KEY_SELECTED_NETWORK = "selected_network";
    private static final String KEY_SELECTED_DESIGN = "selected_design";
    private static final String KEY_BANK_NAME = "bank_name";
    private static final String KEY_HOLDER_NAME = "holder_name";
    private static final String KEY_CARD_NUMBER = "card_number";
    private static final String KEY_EXPIRY = "expiry";
    private static final String KEY_CVV = "cvv";
    private static final String KEY_CARD_PIN = "card_pin";
    // Choice-dialog survival: which picker was open + its checked index.
    private static final String KEY_DIALOG_KIND = "dialog_kind";

    // Form state.
    private boolean isEdit;
    private BankCardModel editingItem;
    private int selectedType;
    private int selectedNetwork;
    private int selectedDesign;
    /** True on rotation: saved draft wins over vault values in prefill. */
    private boolean hasSavedDraft;
    private String savedBankName;
    private String savedHolderName;
    private String savedCardNumber;
    private String savedExpiry;
    private String savedCvv;
    private String savedCardPin;
    private static final String DIALOG_CARD_TYPE = "kind_type";
    private static final String DIALOG_NETWORK = "kind_network";
    private String pendingDialogKind;

    private TextView cardTypeLabel;
    private TextView cardNetworkLabel;
    private EditText bankNameField;
    private EditText holderNameField;
    private EditText cardNumberField;
    private EditText expiryField;
    private EditText cvvField;
    private EditText cardPinField;
    private View primaryAction;
    private TextView primaryActionLabel;
    private View destructiveAction;

    private BankCardDesignAdapter designAdapter;
    private RecyclerView designCarousel;
    private LinearLayoutManager designLayoutManager;
    private PagerSnapHelper designSnapHelper;
    private LinearLayout dotsContainer;
    /** True once the initial scroll settles; earlier scrolls are layout noise. */
    private boolean carouselSettled;

    // Guards: setText inside afterTextChanged would recurse without these.
    private boolean isFormattingNumber;
    private boolean isFormattingExpiry;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bank_card);
        applyChrome();

        if (savedInstanceState != null) {
            // Restore picker state before binding; invalid values fall back to 0.
            selectedType = sanitizeIndex(
                    savedInstanceState.getInt(KEY_SELECTED_TYPE, 0), CARD_TYPES.length);
            selectedNetwork = sanitizeIndex(
                    savedInstanceState.getInt(KEY_SELECTED_NETWORK, 0), CARD_NETWORKS.length);
            selectedDesign = Math.max(0, savedInstanceState.getInt(KEY_SELECTED_DESIGN, 0));
            // Typed draft wins over vault values (async rebind would clobber).
            hasSavedDraft = savedInstanceState.containsKey(KEY_BANK_NAME)
                    || savedInstanceState.containsKey(KEY_CARD_NUMBER);
            savedBankName = savedInstanceState.getString(KEY_BANK_NAME, null);
            savedHolderName = savedInstanceState.getString(KEY_HOLDER_NAME, null);
            savedCardNumber = savedInstanceState.getString(KEY_CARD_NUMBER, null);
            savedExpiry = savedInstanceState.getString(KEY_EXPIRY, null);
            savedCvv = savedInstanceState.getString(KEY_CVV, null);
            savedCardPin = savedInstanceState.getString(KEY_CARD_PIN, null);
            pendingDialogKind = savedInstanceState.getString(KEY_DIALOG_KIND, null);
        }

        int pendingId = getIntent().getIntExtra(EXTRA_ID, -1);
        if (pendingId == -1) {
            bindForm(null);
            return;
        }
        if (savedInstanceState != null) {
            final int id = pendingId;
            final int gen = nextLoadGeneration();
            vaultIo(() -> {
                final BankCardModel item = db().getBankCardById(id);
                runIfAlive(gen, () -> {
                    if (item == null) {
                        finish();
                        return;
                    }
                    if (!hasSavedDraft) {
                        selectedDesign = item.getDesign();
                    }
                    clampDesign();
                    bindForm(item);
                });
            });
            return;
        }
        final int id = pendingId;
        final int gen = nextLoadGeneration();
        vaultIo(() -> {
            final BankCardModel item = db().getBankCardById(id);
            runIfAlive(gen, () -> {
                if (item == null) {
                    finish();
                    return;
                }
                selectedType = sanitizeIndex(Ui.indexOfIgnoreCase(CARD_TYPES, item.getCardType()),
                        CARD_TYPES.length);
                selectedNetwork = sanitizeIndex(Ui.indexOfIgnoreCase(CARD_NETWORKS, item.getCardNetwork()),
                        CARD_NETWORKS.length);
                selectedDesign = item.getDesign();
                bindForm(item);
            });
        });
    }

    private void clampDesign() {
        if (designAdapter != null) {
            selectedDesign = sanitizeIndex(selectedDesign, designAdapter.getDesignCount());
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(KEY_SELECTED_TYPE, selectedType);
        outState.putInt(KEY_SELECTED_NETWORK, selectedNetwork);
        outState.putInt(KEY_SELECTED_DESIGN, selectedDesign);
        // Views may not exist yet on early rotation; fall back to saved draft.
        saveField(outState, KEY_BANK_NAME, bankNameField, savedBankName);
        saveField(outState, KEY_HOLDER_NAME, holderNameField, savedHolderName);
        saveField(outState, KEY_CARD_NUMBER, cardNumberField, savedCardNumber);
        saveField(outState, KEY_EXPIRY, expiryField, savedExpiry);
        saveField(outState, KEY_CVV, cvvField, savedCvv);
        saveField(outState, KEY_CARD_PIN, cardPinField, savedCardPin);
        if (pendingDialogKind != null) {
            outState.putString(KEY_DIALOG_KIND, pendingDialogKind);
        }
    }

    private static void saveField(Bundle out, String key, EditText field, String saved) {
        if (field != null) {
            out.putString(key, field.getText().toString());
        } else if (saved != null) {
            out.putString(key, saved);
        }
    }

    private void bindForm(@Nullable BankCardModel existing) {
        isEdit = existing != null;
        editingItem = existing;

        cacheViews();
        setupDesignCarousel();
        clampDesign();
        if (isEdit) {
            prefillEditMode(existing);
        } else {
            applyAddModeLayout();
        }
        setupPreviewBinding();
        setupInputFormatting();
        setupPickers();
        setupVisibilityToggles();
        setupSaveAction();
        setupDeleteAction();
    }

    private void cacheViews() {
        cardTypeLabel = findViewById(R.id.bank_card_type_label);
        cardNetworkLabel = findViewById(R.id.bank_card_network_label);
        bankNameField = findViewById(R.id.bank_card_bank_name_field);
        holderNameField = findViewById(R.id.bank_card_holder_name_field);
        cardNumberField = findViewById(R.id.bank_card_number_field);
        expiryField = findViewById(R.id.bank_card_expiry_field);
        cvvField = findViewById(R.id.bank_card_cvv_field);
        cardPinField = findViewById(R.id.bank_card_pin_field);
        primaryAction = findViewById(R.id.form_primary_action);
        primaryActionLabel = findViewById(R.id.form_primary_action_label);
        destructiveAction = findViewById(R.id.form_destructive_action);
        designCarousel = findViewById(R.id.bank_card_design_carousel);
        dotsContainer = findViewById(R.id.bank_card_design_indicator);
    }

    /** Horizontal snap carousel for the card designs. */
    private void setupDesignCarousel() {
        designAdapter = new BankCardDesignAdapter();
        designLayoutManager = new LinearLayoutManager(
                this, LinearLayoutManager.HORIZONTAL, false);
        designCarousel.setLayoutManager(designLayoutManager);
        designCarousel.setAdapter(designAdapter);
        // wrap_content height: the carousel can resize with its content, so fixed-size must stay off.
        designCarousel.setHasFixedSize(false);
        designCarousel.setItemViewCacheSize(4);

        designCarousel.addItemDecoration(Ui.carouselGapDecoration(this));
        designSnapHelper = new PagerSnapHelper();
        designSnapHelper.attachToRecyclerView(designCarousel);

        Ui.buildDots(this, dotsContainer, designAdapter.getDesignCount(), selectedDesign);

        carouselSettled = false;
        designCarousel.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (!carouselSettled) {
                    return;
                }
                View snapView = designSnapHelper.findSnapView(designLayoutManager);
                if (snapView != null) {
                    int pos = designLayoutManager.getPosition(snapView);
                    if (pos != RecyclerView.NO_POSITION && pos != selectedDesign) {
                        selectedDesign = pos;
                        Ui.updateDots(dotsContainer, pos);
                    }
                }
            }
        });
    }

    /** Fills every field: rotation draft wins, else stored card. */
    private void prefillEditMode(@NonNull BankCardModel existing) {
        primaryActionLabel.setText(R.string.action_update);
        cardTypeLabel.setText(CARD_TYPES[selectedType]);
        cardNetworkLabel.setText(CARD_NETWORKS[selectedNetwork]);

        setOrStored(bankNameField, savedBankName, existing.getBankName());
        setOrStored(holderNameField, savedHolderName, existing.getHolderName());
        setOrStored(cardNumberField, savedCardNumber, existing.getCardNumber());
        setOrStored(expiryField, savedExpiry, existing.getExpiry());
        setOrStored(cvvField, savedCvv, existing.getCvv());
        setOrStored(cardPinField, savedCardPin, existing.getPin());

        final int scrollTo = selectedDesign;
        designCarousel.post(() -> {
            designCarousel.scrollToPosition(scrollTo);
            Ui.updateDots(dotsContainer, scrollTo);
            designCarousel.post(() -> carouselSettled = true);
        });
        restorePendingDialog();
    }

    private static void setOrStored(EditText field, String saved, String stored) {
        if (saved != null) {
            field.setText(saved);
        } else if (stored != null) {
            field.setText(stored);
        }
    }

    /** Add mode keeps a single full-width Save button. */
    private void applyAddModeLayout() {
        primaryActionLabel.setText(R.string.action_save);
        Ui.makeSaveButtonFullWidth(primaryAction);
        if (hasSavedDraft) {
            restoreDraftFields();
        }
        designCarousel.post(() -> carouselSettled = true);
        restorePendingDialog();
    }

    private void restoreDraftFields() {
        setIfPresent(bankNameField, savedBankName);
        setIfPresent(holderNameField, savedHolderName);
        setIfPresent(cardNumberField, savedCardNumber);
        setIfPresent(expiryField, savedExpiry);
        setIfPresent(cvvField, savedCvv);
        setIfPresent(cardPinField, savedCardPin);
    }

    private static void setIfPresent(EditText field, String saved) {
        if (saved != null) {
            field.setText(saved);
        }
    }

    private void setupPreviewBinding() {
        Ui.SimpleTextWatcher previewWatcher = new Ui.SimpleTextWatcher() {
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                refreshPreview();
            }
        };
        bankNameField.addTextChangedListener(previewWatcher);
        holderNameField.addTextChangedListener(previewWatcher);
        cardNumberField.addTextChangedListener(previewWatcher);
        expiryField.addTextChangedListener(previewWatcher);
        for (EditText field : new EditText[]{bankNameField, holderNameField,
                cardNumberField, expiryField, cvvField, cardPinField}) {
            clearErrorOnChange(field);
        }
        refreshPreview();
    }

    private void refreshPreview() {
        String digits = extractDigits(cardNumberField.getText().toString());
        String last4 = digits.length() > 4
                ? digits.substring(digits.length() - 4)
                : digits;
        String expDigits = extractDigits(expiryField.getText().toString());
        String expDisplay = expDigits.length() == EXPIRY_DIGITS_LEN
                ? expDigits.substring(0, 2) + "/" + expDigits.substring(2)
                : expDigits;
        designAdapter.updatePreview(
                bankNameField.getText().toString().trim(),
                holderNameField.getText().toString().trim(),
                last4,
                expDisplay,
                CARD_TYPES[selectedType],
                CARD_NETWORKS[selectedNetwork]);
    }

    private void setupInputFormatting() {
        cardNumberField.addTextChangedListener(new Ui.SimpleTextWatcher() {
            @Override public void afterTextChanged(Editable text) {
                if (isFormattingNumber) {
                    return;
                }
                isFormattingNumber = true;
                try {
                    int cursor = cardNumberField.getSelectionStart();
                    int beforeLen = text.length();
                    String digits = extractDigits(text.toString());
                    if (digits.length() > CARD_NUMBER_MAX_LEN) {
                        digits = digits.substring(0, CARD_NUMBER_MAX_LEN);
                    }
                    text.replace(0, text.length(), groupInFours(digits));
                    cardNumberField.setSelection(clampCursor(cursor + (text.length() - beforeLen), text.length()));
                } finally {
                    isFormattingNumber = false;
                }
            }
        });

        expiryField.addTextChangedListener(new Ui.SimpleTextWatcher() {
            @Override public void afterTextChanged(Editable text) {
                if (isFormattingExpiry) {
                    return;
                }
                isFormattingExpiry = true;
                try {
                    int cursor = expiryField.getSelectionStart();
                    int beforeLen = text.length();
                    String digits = extractDigits(text.toString());
                    if (digits.length() > EXPIRY_DIGITS_LEN) {
                        digits = digits.substring(0, EXPIRY_DIGITS_LEN);
                    }
                    text.replace(0, text.length(), formatExpiryInput(digits));
                    int newCursor = clampCursor(cursor + (text.length() - beforeLen), text.length());
                    // Typing the 4th digit inserts a slash before it, jumping
                    // the length 4 -> 5; pin the cursor to the end in that case.
                    if (newCursor == 3 && text.length() == 5 && beforeLen < text.length()) {
                        newCursor = 5;
                    }
                    expiryField.setSelection(newCursor);
                } finally {
                    isFormattingExpiry = false;
                }
            }
        });
    }

    private void setupPickers() {
        findViewById(R.id.bank_card_type_selector).setOnClickListener(v ->
                showChoiceDialog(DIALOG_CARD_TYPE, getString(R.string.field_card_type),
                        CARD_TYPES, selectedType, this::selectType));

        findViewById(R.id.bank_card_network_selector).setOnClickListener(v ->
                showChoiceDialog(DIALOG_NETWORK, getString(R.string.field_card_network),
                        CARD_NETWORKS, selectedNetwork, this::selectNetwork));
    }

    private void selectType(int pos) {
        selectedType = pos;
        cardTypeLabel.setText(CARD_TYPES[pos]);
        refreshPreview();
    }

    private void selectNetwork(int pos) {
        selectedNetwork = pos;
        cardNetworkLabel.setText(CARD_NETWORKS[pos]);
        refreshPreview();
    }

    /** Eye icons flip the transformation method without losing cursor. */
    private void setupVisibilityToggles() {
        findViewById(R.id.bank_card_cvv_visibility_toggle).setOnClickListener(v ->
                Ui.togglePasswordVisibility(cvvField));
        findViewById(R.id.bank_card_pin_visibility_toggle).setOnClickListener(v ->
                Ui.togglePasswordVisibility(cardPinField));
    }

    private void setupSaveAction() {
        primaryAction.setOnClickListener(v -> {
            if (!validateForm()) {
                return;
            }
            // DB holds canonical raw values (no spaces or slashes).
            String cardDigits = extractDigits(cardNumberField.getText().toString());
            String expDigits = extractDigits(expiryField.getText().toString());
            String cvvDigits = extractDigits(cvvField.getText().toString());
            String pinDigits = extractDigits(cardPinField.getText().toString());
            primaryAction.setEnabled(false);

            if (isEdit) {
                final BankCardModel updated = new BankCardModel(
                        editingItem.getId(),
                        CARD_TYPES[selectedType],
                        CARD_NETWORKS[selectedNetwork],
                        bankNameField.getText().toString().trim(),
                        holderNameField.getText().toString().trim(),
                        cardDigits, expDigits, cvvDigits, pinDigits,
                        selectedDesign,
                        editingItem.getCreatedAt(), 0);
                persistCard(updated, R.string.msg_updated, () -> db().updateBankCard(updated));
            } else {
                final BankCardModel fresh = new BankCardModel(
                        CARD_TYPES[selectedType],
                        CARD_NETWORKS[selectedNetwork],
                        bankNameField.getText().toString().trim(),
                        holderNameField.getText().toString().trim(),
                        cardDigits, expDigits, cvvDigits, pinDigits,
                        selectedDesign);
                persistCard(fresh, R.string.msg_card_saved, () -> db().insertBankCard(fresh));
            }
        });
    }

    private void persistCard(BankCardModel model, int doneMessage, Runnable write) {
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

    /** Edit mode only. Soft-deletes to Trash behind a delete dialog. */
    private void setupDeleteAction() {
        if (!isEdit) {
            return;
        }
        destructiveAction.setVisibility(View.VISIBLE);
        destructiveAction.setOnClickListener(v -> confirmDeleteToTrash(
                getString(R.string.confirm_delete_card_title),
                getString(R.string.confirm_delete_card_message),
                () -> {
                    final int id = editingItem.getId();
                    vaultIo(() -> {
                        db().moveBankCardToTrash(id);
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

    // ------------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------------

    private boolean validateForm() {
        View offender = firstInvalid(
                validateBankName(bankNameField.getText().toString().trim()),
                validateHolderName(holderNameField.getText().toString().trim()),
                validateCardNumber(extractDigits(cardNumberField.getText().toString())),
                validateExpiry(extractDigits(expiryField.getText().toString())),
                validateCvv(extractDigits(cvvField.getText().toString())),
                validatePin(extractDigits(cardPinField.getText().toString())));
        if (offender != null) {
            offender.requestFocus();
            return false;
        }
        return true;
    }

    private static View firstInvalid(View... offenders) {
        for (View offender : offenders) {
            if (offender != null) {
                return offender;
            }
        }
        return null;
    }

    private View validateBankName(String bank) {
        if (bank.isEmpty()) {
            bankNameField.setError("Bank name is required");
            return bankNameField;
        } else if (bank.length() < BANK_NAME_MIN_LEN) {
            bankNameField.setError("Bank name must be at least " + BANK_NAME_MIN_LEN + " characters");
            return bankNameField;
        } else if (bank.length() > BANK_NAME_MAX_LEN) {
            bankNameField.setError("Bank name must be under " + (BANK_NAME_MAX_LEN + 1) + " characters");
            return bankNameField;
        }
        return null;
    }

    private View validateHolderName(String holder) {
        if (holder.isEmpty()) {
            holderNameField.setError("Cardholder name is required");
            return holderNameField;
        } else if (holder.length() < HOLDER_NAME_MIN_LEN) {
            holderNameField.setError("Cardholder name must be at least " + HOLDER_NAME_MIN_LEN + " characters");
            return holderNameField;
        } else if (holder.length() > HOLDER_NAME_MAX_LEN) {
            holderNameField.setError("Cardholder name must be under " + (HOLDER_NAME_MAX_LEN + 1) + " characters");
            return holderNameField;
        } else if (!HOLDER_NAME.matcher(holder).matches()) {
            // Unicode-aware: allows accented names, denies digits/symbols.
            holderNameField.setError("Name can only contain letters, spaces, . ' -");
            return holderNameField;
        }
        return null;
    }

    private View validateCardNumber(String cardDigits) {
        if (cardDigits.length() < CARD_NUMBER_MIN_LEN || cardDigits.length() > CARD_NUMBER_MAX_LEN) {
            cardNumberField.setError(
                    "Card number must be " + CARD_NUMBER_MIN_LEN + "-" + CARD_NUMBER_MAX_LEN + " digits");
            return cardNumberField;
        }
        return null;
    }

    private View validateExpiry(String expDigits) {
        if (expDigits.length() != EXPIRY_DIGITS_LEN) {
            expiryField.setError("Expiry must be exactly 4 digits (MMYY)");
            return expiryField;
        }
        return null;
    }

    private View validateCvv(String cvvDigits) {
        if (cvvDigits.length() != CVV_LEN) {
            cvvField.setError("CVV must be exactly " + CVV_LEN + " digits");
            return cvvField;
        }
        return null;
    }

    private View validatePin(String pinDigits) {
        if (pinDigits.length() < PIN_MIN_LEN || pinDigits.length() > PIN_MAX_LEN) {
            cardPinField.setError("PIN must be " + PIN_MIN_LEN + "-" + PIN_MAX_LEN + " digits");
            return cardPinField;
        }
        return null;
    }

    private static void clearErrorOnChange(EditText input) {
        input.addTextChangedListener(new Ui.SimpleTextWatcher() {
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                if (input.getError() != null) {
                    input.setError(null);
                }
            }
        });
    }

    // ------------------------------------------------------------------
    // Pure helpers
    // ------------------------------------------------------------------

    private static String extractDigits(String raw) {
        return raw == null ? "" : NON_DIGITS.matcher(raw).replaceAll("");
    }

    private static String groupInFours(String digits) {
        StringBuilder groupedDigits = new StringBuilder(digits.length() + digits.length() / 4);
        for (int digitIndex = 0; digitIndex < digits.length(); digitIndex++) {
            if (digitIndex > 0 && digitIndex % 4 == 0) {
                groupedDigits.append(' ');
            }
            groupedDigits.append(digits.charAt(digitIndex));
        }
        return groupedDigits.toString();
    }

    private static String formatExpiryInput(String digits) {
        return digits.length() > 2
                ? digits.substring(0, 2) + "/" + digits.substring(2)
                : digits;
    }

    private static int clampCursor(int cursor, int length) {
        return Math.max(0, Math.min(cursor, length));
    }

    private static int sanitizeIndex(int index, int size) {
        return index < 0 || index >= size ? 0 : index;
    }

    private void showChoiceDialog(String kind, String title, String[] options, int checkedPosition, Ui.OnChoice listener) {
        pendingDialogKind = kind;
        AlertDialog dialog = Ui.singleChoice(this, title, options, checkedPosition, pos -> {
            pendingDialogKind = null;
            listener.onChoice(pos);
        });
        dialog.setOnDismissListener(d -> {
            if (kind.equals(pendingDialogKind)) {
                pendingDialogKind = null;
            }
        });
        trackDialog(dialog);
    }

    /** Re-shows the picker that was open across rotation. */
    private void restorePendingDialog() {
        if (pendingDialogKind == null) {
            return;
        }
        String kind = pendingDialogKind;
        pendingDialogKind = null;
        if (DIALOG_CARD_TYPE.equals(kind)) {
            findViewById(R.id.bank_card_type_selector).post(() ->
                    showChoiceDialog(DIALOG_CARD_TYPE, getString(R.string.field_card_type),
                            CARD_TYPES, selectedType, this::selectType));
        } else if (DIALOG_NETWORK.equals(kind)) {
            findViewById(R.id.bank_card_network_selector).post(() ->
                    showChoiceDialog(DIALOG_NETWORK, getString(R.string.field_card_network),
                            CARD_NETWORKS, selectedNetwork, this::selectNetwork));
        }
    }

}
