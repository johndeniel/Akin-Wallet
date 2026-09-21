package com.akin.wallet.activity;

import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.text.Editable;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.akin.wallet.R;
import com.akin.wallet.adapter.BankCardDesignAdapter;
import com.akin.wallet.model.BankCardModel;
import com.akin.wallet.util.Ui;

public class BankCardActivity extends BaseVaultActivity {

    public static final String EXTRA_ID = "extra_id";

    /**
     * Intent that opens this screen to edit an existing card. Carries the row
     * id only — the screen re-queries the vault, so secrets never travel as
     * Intent extras and edits always start current.
     */
    public static Intent editIntent(@NonNull Context context, @NonNull BankCardModel item) {
        return new Intent(context, BankCardActivity.class)
                .putExtra(EXTRA_ID, item.getId());
    }

    // Fixed option sets. Order doubles as the persisted design/type index, so
    // never reorder without a DB migration.
    private static final String[] CARD_TYPES = {"Debit", "Credit", "Prepaid"};
    private static final String[] CARD_NETWORKS = {"Visa", "MasterCard"};

    // Validation rules. Card number range follows ISO/IEC 7812 (13-19 digits);
    // CVV is 3 digits because only Visa/MasterCard are offered (no Amex 4-digit).
    // PIN follows the common 4-6 digit ATM convention.
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
    private static final java.util.regex.Pattern NON_DIGITS = java.util.regex.Pattern.compile("\\D");
    private static final java.util.regex.Pattern HOLDER_NAME =
            java.util.regex.Pattern.compile("\\p{L}[\\p{L} .'-]*");

    // Rotation keys. EditTexts restore their own text; the pickers do not, so
    // the selected indices are saved explicitly.
    private static final String KEY_SELECTED_TYPE = "selected_type";
    private static final String KEY_SELECTED_NETWORK = "selected_network";
    private static final String KEY_SELECTED_DESIGN = "selected_design";

    // Form state. Plain ints (not single-element arrays): bindForm runs once per
    // creation, and lambdas capture the activity, so no effectively-final hack.
    private boolean isEdit;
    private BankCardModel editingItem;
    private int selectedType;
    private int selectedNetwork;
    private int selectedDesign;

    // Cached views. Looked up once to keep bindForm readable and avoid repeated
    // traversal on every keystroke/preview refresh.
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
    /** True once the initial scroll-to-design has settled; onScrolled before
     * that is layout noise that must not overwrite the restored design. */
    private boolean carouselSettled;

    // Reentrancy guards for the formatting watchers. Without these, setText
    // inside afterTextChanged would recurse until a stack overflow.
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
        }

        int pendingId = getIntent().getIntExtra(EXTRA_ID, -1);
        if (pendingId == -1) {
            bindForm(null);
            return;
        }
        if (savedInstanceState != null) {
            // Rotation with in-progress picks: keep picks, fetch row off UI.
            final int id = pendingId;
            final int gen = nextLoadGeneration();
            vaultIo(() -> {
                final BankCardModel item = db().getBankCardById(id);
                runOnUiThread(() -> {
                    if (!isCurrentGeneration(gen) || isFinishing() || isDestroyed()) return;
                    if (item == null) {
                        finish();
                        return;
                    }
                    bindFormWithId(item);
                });
            });
            return;
        }
        // Fresh launch: single-row decrypt off UI to avoid first-draw jank.
        final int id = pendingId;
        final int gen = nextLoadGeneration();
        // Bind empty shell synchronously so layout exists, then rebind.
        vaultIo(() -> {
            final BankCardModel item = db().getBankCardById(id);
            runOnUiThread(() -> {
                if (!isCurrentGeneration(gen) || isFinishing() || isDestroyed()) return;
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
    }

    /**
     * Rebuilds the editing item from the vault by id. Returns null for add
     * mode (no id extra), which drives every isEdit branch downstream. A row
     * deleted elsewhere resolves to null id-side and finishes in onCreate.
     *
     * @param restored true when pickers were already restored from rotation and
     *                 must not be overwritten by stored defaults
     */
    @Nullable
    private BankCardModel resolveEditingItem(boolean restored) {
        int id = getIntent().getIntExtra(EXTRA_ID, -1);
        if (id == -1) {
            return null;
        }
        BankCardModel item = db().getBankCardById(id);
        if (item == null) {
            finish();
            return null;
        }
        if (!restored) {
            // Fresh launch: seed pickers from the stored card; rotation keeps
            // the user's in-progress picks instead.
            selectedType = sanitizeIndex(Ui.indexOfIgnoreCase(CARD_TYPES, item.getCardType()),
                    CARD_TYPES.length);
            selectedNetwork = sanitizeIndex(Ui.indexOfIgnoreCase(CARD_NETWORKS, item.getCardNetwork()),
                    CARD_NETWORKS.length);
            selectedDesign = item.getDesign();
        }
        return item;
    }

    /** Rotation path: pickers already restored, just bind the fetched row. */
    private void bindFormWithId(@NonNull BankCardModel item) {
        clampDesignFor(item.getDesign());
        bindForm(item);
    }

    private void clampDesignFor(int design) {
        selectedDesign = design;
    }

    /** Entry point: wires every section in dependency order. */
    private void bindForm(@Nullable BankCardModel existing) {
        isEdit = existing != null;
        editingItem = existing;

        cacheViews();
        setupDesignCarousel();
        // Clamp once the adapter (and its page count) exists, covering both
        // restored add-mode picks and stale stored designs.
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

    /** Single findViewById pass; all later code uses these fields. */
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

    /** Horizontal snap carousel shared with the dashboard (same XML + ratio). */
    private void setupDesignCarousel() {
        designAdapter = new BankCardDesignAdapter();
        designLayoutManager = new LinearLayoutManager(
                this, LinearLayoutManager.HORIZONTAL, false);
        designCarousel.setLayoutManager(designLayoutManager);
        designCarousel.setAdapter(designAdapter);
        designCarousel.setHasFixedSize(true);
        designCarousel.setItemViewCacheSize(4);

        // Same 12dp inter-card gap as the dashboard carousel (computed once;
        // getItemOffsets runs per child per layout pass).
        final int carouselGapPx = Ui.dp(this, 12);
        designCarousel.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(@NonNull Rect outRect, @NonNull View child,
                                       @NonNull RecyclerView parent,
                                       @NonNull RecyclerView.State state) {
                int position = parent.getChildAdapterPosition(child);
                if (position != RecyclerView.NO_POSITION
                        && position < state.getItemCount() - 1) {
                    outRect.right = carouselGapPx;
                }
            }
        });
        designSnapHelper = new PagerSnapHelper();
        designSnapHelper.attachToRecyclerView(designCarousel);

        createDots(dotsContainer, designAdapter.getDesignCount());
        updateDots(dotsContainer, selectedDesign);

        // PagerSnapHelper reports the centered page; that page is the design.
        // Ignored until the initial scroll settles so layout noise at
        // position 0 cannot clobber the restored pick.
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
                        updateDots(dotsContainer, pos);
                    }
                }
            }
        });
    }

    /** Fills every field from the stored card; clamps a stale design index. */
    private void prefillEditMode(@NonNull BankCardModel existing) {
        primaryActionLabel.setText(R.string.action_update);
        cardTypeLabel.setText(CARD_TYPES[selectedType]);
        cardNetworkLabel.setText(CARD_NETWORKS[selectedNetwork]);

        if (selectedDesign < 0 || selectedDesign >= designAdapter.getDesignCount()) {
            selectedDesign = 0;
        }
        bankNameField.setText(existing.getBankName());
        holderNameField.setText(existing.getHolderName());
        // Stored values are raw digits; the formatting watchers add
        // grouping (card spaces) and the expiry slash on setText.
        cardNumberField.setText(existing.getCardNumber());
        expiryField.setText(existing.getExpiry());
        cvvField.setText(existing.getCvv());
        cardPinField.setText(existing.getPin());

        final int scrollTo = selectedDesign;
        designCarousel.post(() -> {
            designCarousel.scrollToPosition(scrollTo);
            updateDots(dotsContainer, scrollTo);
            designCarousel.post(() -> carouselSettled = true);
        });
    }

    /**
     * Add mode keeps a single full-width Save button. The delete view is GONE,
     * so its row margin is dropped to avoid a trailing 8dp gap.
     */
    private void applyAddModeLayout() {
        primaryActionLabel.setText(R.string.action_save);
        Ui.makeSaveButtonFullWidth(primaryAction);
        designCarousel.post(() -> carouselSettled = true);
    }

    /** Live card-face preview; also clears stale errors as the user types. */
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

        clearErrorOnChange(bankNameField);
        clearErrorOnChange(holderNameField);
        clearErrorOnChange(cardNumberField);
        clearErrorOnChange(expiryField);
        clearErrorOnChange(cvvField);
        clearErrorOnChange(cardPinField);

        refreshPreview();
    }

    /** Pushes trimmed field values into the carousel's placeholder face. */
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

    /** Groups card digits in 4s and expiry as MM/YY while preserving cursor. */
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
                showChoiceDialog("Card Type", CARD_TYPES, selectedType, selectedPosition -> {
                    selectedType = selectedPosition;
                    cardTypeLabel.setText(CARD_TYPES[selectedPosition]);
                    refreshPreview();
                }));

        findViewById(R.id.bank_card_network_selector).setOnClickListener(v ->
                showChoiceDialog("Card Network", CARD_NETWORKS, selectedNetwork, selectedPosition -> {
                    selectedNetwork = selectedPosition;
                    cardNetworkLabel.setText(CARD_NETWORKS[selectedPosition]);
                    refreshPreview();
                }));
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
            // Digits were validated; strip formatting once for storage so the
            // DB always holds canonical raw values (no spaces or slashes).
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
                        cardDigits,
                        expDigits,
                        cvvDigits,
                        pinDigits,
                        selectedDesign,
                        editingItem.getCreatedAt(), 0);
                vaultIo(() -> {
                    db().updateBankCard(updated);
                    cache().invalidate();
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        app().notifyOnReturn(R.string.msg_updated);
                        setResult(RESULT_OK);
                        finish();
                    });
                });
            } else {
                final BankCardModel fresh = new BankCardModel(
                        CARD_TYPES[selectedType],
                        CARD_NETWORKS[selectedNetwork],
                        bankNameField.getText().toString().trim(),
                        holderNameField.getText().toString().trim(),
                        cardDigits,
                        expDigits,
                        cvvDigits,
                        pinDigits,
                        selectedDesign);
                vaultIo(() -> {
                    db().insertBankCard(fresh);
                    cache().invalidate();
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        app().notifyOnReturn(R.string.msg_card_saved);
                        setResult(RESULT_OK);
                        finish();
                    });
                });
            }
        });
    }

    /** Delete on this screen (no bank tab); edit mode shows it in-row. Soft-deletes to Trash behind a normal delete dialog. */
    private void setupDeleteAction() {
        if (!isEdit) {
            return;
        }
        destructiveAction.setVisibility(View.VISIBLE);
        destructiveAction.setOnClickListener(v -> confirmDeleteToTrash(
                "Delete Card",
                "Are you sure you want to delete this card?",
                () -> {
                    final int id = editingItem.getId();
                    vaultIo(() -> {
                        db().moveBankCardToTrash(id);
                        cache().invalidate();
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) return;
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

    /**
     * Validates every field, marks each offender with setError, and focuses the
     * first invalid input. All fields are checked (not fail-fast) so the user
     * sees the full scope in one pass.
     *
     * @return true when every field is persistable
     */
    private boolean validateForm() {
        View bankOffender = validateBankName(bankNameField.getText().toString().trim());
        View holderOffender = validateHolderName(holderNameField.getText().toString().trim());
        View numberOffender = validateCardNumber(
                extractDigits(cardNumberField.getText().toString()));
        View expiryOffender = validateExpiry(
                extractDigits(expiryField.getText().toString()));
        View cvvOffender = validateCvv(
                extractDigits(cvvField.getText().toString()));
        View pinOffender = validatePin(
                extractDigits(cardPinField.getText().toString()));

        View firstInvalid = firstOffender(
                bankOffender, holderOffender, numberOffender,
                expiryOffender, cvvOffender, pinOffender);
        if (firstInvalid != null) {
            firstInvalid.requestFocus();
            return false;
        }
        return true;
    }

    /** First non-null offender, preserving form order. */
    private static View firstOffender(View... offenders) {
        for (View offender : offenders) {
            if (offender != null) {
                return offender;
            }
        }
        return null;
    }

    /** Returns the field when invalid (error already set), null when valid. */
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

    /** Returns the field when invalid (error already set), null when valid. */
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

    /** Returns the field when invalid (error already set), null when valid. */
    private View validateCardNumber(String cardDigits) {
        if (cardDigits.length() < CARD_NUMBER_MIN_LEN || cardDigits.length() > CARD_NUMBER_MAX_LEN) {
            cardNumberField.setError(
                    "Card number must be " + CARD_NUMBER_MIN_LEN + "-" + CARD_NUMBER_MAX_LEN + " digits");
            return cardNumberField;
        }
        return null;
    }

    /** Returns the field when invalid (error already set), null when valid. */
    private View validateExpiry(String expDigits) {
        if (expDigits.length() != EXPIRY_DIGITS_LEN) {
            expiryField.setError("Expiry must be exactly 4 digits (MMYY)");
            return expiryField;
        }
        return null;
    }

    /** Returns the field when invalid (error already set), null when valid. */
    private View validateCvv(String cvvDigits) {
        if (cvvDigits.length() != CVV_LEN) {
            cvvField.setError("CVV must be exactly " + CVV_LEN + " digits");
            return cvvField;
        }
        return null;
    }

    /** Returns the field when invalid (error already set), null when valid. */
    private View validatePin(String pinDigits) {
        if (pinDigits.length() < PIN_MIN_LEN || pinDigits.length() > PIN_MAX_LEN) {
            cardPinField.setError("PIN must be " + PIN_MIN_LEN + "-" + PIN_MAX_LEN + " digits");
            return cardPinField;
        }
        return null;
    }

    /** Clears a stale setError as soon as the user edits the field again. */
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
    // Small pure helpers (unit-testable, no Android state)
    // ------------------------------------------------------------------

    private static String extractDigits(String raw) {
        return raw == null ? "" : NON_DIGITS.matcher(raw).replaceAll("");
    }

    /** Groups raw digits for display: "12345678" -> "1234 5678". */
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

    /** Formats raw expiry digits for display: "1" -> "1", "122" -> "12/2". */
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

    private void showChoiceDialog(String title, String[] options, int checkedPosition, OnChoiceListener listener) {
        trackDialog(Ui.singleChoice(this, title, options, checkedPosition, listener::onChoice));
    }

    /** Builds dots once; use updateDots() on scroll to avoid view churn. */
    private void createDots(LinearLayout container, int count) {
        container.removeAllViews();
        int size = Ui.dp(this, 8);
        int margin = Ui.dp(this, 4);
        for (int i = 0; i < count; i++) {
            View dot = new View(this);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
            params.setMargins(margin, 0, margin, 0);
            dot.setLayoutParams(params);
            dot.setBackgroundResource(R.drawable.bg_dot);
            container.addView(dot);
        }
    }

    /** Flips dot alpha in place; cheaper than rebuilding on every scroll tick. */
    private static void updateDots(LinearLayout container, int selected) {
        for (int i = 0; i < container.getChildCount(); i++) {
            container.getChildAt(i).setAlpha(i == selected ? 1f : 0.3f);
        }
    }

    private interface OnChoiceListener {
        void onChoice(int selectedPosition);
    }
}
