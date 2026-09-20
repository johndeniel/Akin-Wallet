package com.akin.wallet.activity;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import com.akin.wallet.R;
import com.akin.wallet.db.VaultWarmCache;
import com.akin.wallet.security.AppLockManager;
import com.akin.wallet.util.Ui;

/**
 * App lock — the launcher screen. First run walks 4-digit PIN setup
 * (create + confirm); afterward it is PIN entry or, only when the user
 * opted in from Settings, fingerprint unlock. Result taps reuse the same
 * unlock-success routing per launch mode:
 *
 * <ul>
 *   <li>{@link #MODE_START} (launcher): success opens DashboardActivity.</li>
 *   <li>{@link #MODE_VERIFY}: success just returns RESULT_OK.</li>
 *   <li>{@link #MODE_CHANGE}: verifies the current PIN, then sets a new one.</li>
 * </ul>
 *
 * <p>Secrets policy: password / PIN / CVV are never searchable here — this
 * screen only ever handles the 4-digit app PIN, verified as a salted hash.
 */
public class LockActivity extends AppCompatActivity {

    public static final String EXTRA_MODE = "extra_mode";
    public static final String MODE_START = "start";
    public static final String MODE_VERIFY = "verify";
    public static final String MODE_CHANGE = "change";

    private enum Screen { CREATE, CONFIRM, VERIFY, PIN }

    private String mode = MODE_START;
    private Screen screen = Screen.PIN;

    private final StringBuilder entry = new StringBuilder();
    private String firstPin;
    private boolean submitting;

    private static final String KEY_MODE = "lock_mode";
    private static final String KEY_SCREEN = "lock_screen";
    private static final String KEY_ENTRY = "lock_entry";
    private static final String KEY_FIRST_PIN = "lock_first_pin";

    /** Delayed biometric ask / entry submit, removable on pause/destroy. */
    private final Runnable autoBiometric = this::startBiometric;
    private final Runnable pendingEntry = this::processEntry;

    private TextView error;
    private TextView tagline;
    private View dotsRow;
    private final View[] dots = new View[AppLockManager.PIN_LENGTH];
    private LinearLayout keypad;
    private View bioKey;

    private BiometricPrompt biometricPrompt;
    private boolean promptActive;
    private boolean paused;

    private final Handler lockoutHandler = new Handler(Looper.getMainLooper());
    private final Runnable lockoutTicker = new Runnable() {
        @Override
        public void run() {
            if (isFinishing()) {
                return;
            }
            if (AppLockManager.isLockedOut(LockActivity.this)) {
                showLockout();
                lockoutHandler.postDelayed(this, 1000);
            } else {
                clearError();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_lock);
        Ui.applySystemBars(this);

        String extra = getIntent().getStringExtra(EXTRA_MODE);
        if (extra != null) {
            mode = extra;
        }

        error = findViewById(R.id.lock_error);
        tagline = findViewById(R.id.lock_tagline);
        dotsRow = findViewById(R.id.dots_row);
        keypad = findViewById(R.id.keypad);
        bioKey = findViewById(R.id.key_bio);
        int[] dotIds = {R.id.dot_0, R.id.dot_1, R.id.dot_2, R.id.dot_3};
        for (int i = 0; i < dotIds.length; i++) {
            dots[i] = findViewById(dotIds[i]);
        }

        wireKeypad();
        // Keypad fingerprint key — same design, just asks the system
        // prompt. The keypad never switches screens.
        if (bioKey != null) {
            bioKey.setOnClickListener(v -> startBiometric());
        }

        biometricPrompt = new BiometricPrompt(this,
                ContextCompat.getMainExecutor(this), biometricCallback());

        // Vault warm-up (pre-auth half): connection + catalog only, never rows.
        // Runs while the keypad inflates so the post-auth preload below starts
        // from an open connection. AkinApp already kicked this; redundant here
        // is intentional for process-warm re-entry.
        if (AppLockManager.isPinSet(this)) {
            VaultWarmCache.get(this).warmConnectionAsync();
            VaultWarmCache.get(this).warmCatalogAsync();
        }

        if (savedInstanceState != null) {
            // Rotation: resume exactly where the user was (prompt itself is
            // recreated below; the typed digits and setup progress survive).
            String savedMode = savedInstanceState.getString(KEY_MODE, null);
            if (savedMode != null) {
                mode = savedMode;
            }
            try {
                screen = Screen.valueOf(
                        savedInstanceState.getString(KEY_SCREEN, Screen.PIN.name()));
            } catch (IllegalArgumentException ignored) {
                screen = Screen.PIN;
            }
            entry.setLength(0);
            String savedEntry = savedInstanceState.getString(KEY_ENTRY, "");
            if (savedEntry.length() <= AppLockManager.PIN_LENGTH) {
                entry.append(savedEntry);
            }
            firstPin = savedInstanceState.getString(KEY_FIRST_PIN, null);
            submitting = false;
            restoreScreen();
            renderDots();
            return;
        }

        if (!AppLockManager.isPinSet(this)) {
            showCreateScreen();
        } else if (MODE_CHANGE.equals(mode)) {
            showVerifyScreen();
        } else {
            showPinScreen();
            // Biometric available: ask via system prompt over the same keypad.
            if (AppLockManager.canUseBiometric(this)) {
                keypad.postDelayed(autoBiometric, 400);
            }
        }
    }

    /** Re-renders the current screen after a rotation (no state reset). */
    private void restoreScreen() {
        // The show* calls reset entry/firstPin by design — snapshot first,
        // re-render, then put the in-progress typing back.
        String savedDigits = entry.toString();
        String savedFirst = firstPin;
        switch (screen) {
            case CREATE:
                showCreateScreen();
                break;
            case CONFIRM:
                showCreateScreen();
                firstPin = savedFirst;
                showConfirmScreen();
                break;
            case VERIFY:
                showVerifyScreen();
                break;
            case PIN:
            default:
                showPinScreen();
                break;
        }
        entry.setLength(0);
        if (savedDigits.length() <= AppLockManager.PIN_LENGTH) {
            entry.append(savedDigits);
        }
        submitting = false;
        renderDots();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(KEY_MODE, mode);
        outState.putString(KEY_SCREEN, screen.name());
        outState.putString(KEY_ENTRY, entry.toString());
        outState.putString(KEY_FIRST_PIN, firstPin);
    }

    @Override
    protected void onResume() {
        super.onResume();
        paused = false;
        // System-canceled prompts (backgrounding) re-ask automatically,
        // design stays on the keypad.
        if (screen == Screen.PIN
                && AppLockManager.canUseBiometric(this)
                && !AppLockManager.isLockedOut(this)
                && !MODE_CHANGE.equals(mode)
                && AppLockManager.isPinSet(this)) {
            startBiometric();
        }
    }

    @Override
    protected void onPause() {
        paused = true;
        cancelBiometric();
        if (keypad != null) {
            keypad.removeCallbacks(autoBiometric);
        }
        if (dotsRow != null) {
            dotsRow.removeCallbacks(pendingEntry);
        }
        submitting = false;
        lockoutHandler.removeCallbacks(lockoutTicker);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (keypad != null) {
            keypad.removeCallbacks(autoBiometric);
        }
        if (dotsRow != null) {
            dotsRow.removeCallbacks(pendingEntry);
        }
        lockoutHandler.removeCallbacks(lockoutTicker);
        super.onDestroy();
    }

    // ------------------------------------------------------------------
    // Screens
    // ------------------------------------------------------------------

    private void showCreateScreen() {
        screen = Screen.CREATE;
        firstPin = null;
        entry.setLength(0);
        submitting = false;
        if (MODE_CHANGE.equals(mode)) {
            setStepText(getString(R.string.lock_step_new_pin));
        } else {
            setStepText(getString(R.string.lock_sub_create));
        }
        showKeypadMode();
        clearError();
        setBioKeyVisible(false);
        renderDots();
    }

    private void showConfirmScreen() {
        screen = Screen.CONFIRM;
        entry.setLength(0);
        submitting = false;
        if (MODE_CHANGE.equals(mode)) {
            setStepText(getString(R.string.lock_step_confirm_pin));
        } else {
            setStepText(getString(R.string.lock_sub_confirm));
        }
        showKeypadMode();
        setBioKeyVisible(false);
        renderDots();
    }

    /** Change-PIN step 1: prove the current PIN before setting a new one. */
    private void showVerifyScreen() {
        screen = Screen.VERIFY;
        entry.setLength(0);
        submitting = false;
        setStepText(getString(R.string.lock_step_old_pin));
        showKeypadMode();
        clearError();
        setBioKeyVisible(false);
        renderDots();
    }

    private void showPinScreen() {
        screen = Screen.PIN;
        entry.setLength(0);
        submitting = false;
        cancelBiometric();
        resetTagline();
        showKeypadMode();
        clearError();
        setBioKeyVisible(AppLockManager.canUseBiometric(this) && !MODE_CHANGE.equals(mode));
        renderDots();
        if (AppLockManager.isLockedOut(this)) {
            showLockout();
        }
    }

    private void showKeypadMode() {
        keypad.setVisibility(View.VISIBLE);
        dotsRow.setVisibility(View.VISIBLE);
    }

    /** Keypad biometric key left of 0 — INVISIBLE (not GONE) to keep 0 centered. */
    private void setBioKeyVisible(boolean visible) {
        if (bioKey != null) {
            bioKey.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
        }
    }

    /** Tagline slot doubles as the step helper during setup / update. */
    private void setStepText(String text) {
        tagline.setText(text);
        tagline.setLetterSpacing(0.02f);
    }

    private void resetTagline() {
        tagline.setText(R.string.header_tagline);
        tagline.setLetterSpacing(0.22f);
    }

    // ------------------------------------------------------------------
    // Keypad input
    // ------------------------------------------------------------------

    private void wireKeypad() {
        int[] keyIds = {R.id.key_1, R.id.key_2, R.id.key_3, R.id.key_4, R.id.key_5,
                R.id.key_6, R.id.key_7, R.id.key_8, R.id.key_9, R.id.key_0};
        for (int i = 0; i < keyIds.length; i++) {
            // i = 0..8 -> '1'..'9', i = 9 -> '0'.
            final char digit = i == 9 ? '0' : (char) ('1' + i);
            View key = findViewById(keyIds[i]);
            if (key != null) {
                key.setOnClickListener(v -> onDigit(digit));
            }
        }
        View backKey = findViewById(R.id.key_back);
        if (backKey != null) {
            backKey.setOnClickListener(v -> onBackspace());
        }
    }

    private void onDigit(char digit) {
        if (submitting) {
            return;
        }
        if (AppLockManager.isLockedOut(this)) {
            showLockout();
            return;
        }
        if (entry.length() >= AppLockManager.PIN_LENGTH) {
            return;
        }
        clearError();
        entry.append(digit);
        renderDots();
        if (entry.length() == AppLockManager.PIN_LENGTH) {
            submitting = true;
            dotsRow.postDelayed(pendingEntry, 120);
        }
    }

    private void onBackspace() {
        if (submitting || entry.length() == 0) {
            return;
        }
        entry.deleteCharAt(entry.length() - 1);
        renderDots();
    }

    private void processEntry() {
        // Dropped when the screen went away mid-delay (back/rotate): the user
        // simply retypes; nothing half-applied ever runs on a dead screen.
        if (isFinishing() || paused) {
            submitting = false;
            return;
        }
        String pin = entry.toString();
        entry.setLength(0);
        switch (screen) {
            case CREATE:
                firstPin = pin;
                showConfirmScreen();
                break;
            case CONFIRM:
                if (pin.equals(firstPin)) {
                    AppLockManager.setPin(this, pin);
                    unlockSuccess();
                } else {
                    firstPin = null;
                    showCreateScreen();
                    showError(getString(R.string.lock_error_mismatch));
                    shakeDots();
                }
                break;
            case VERIFY:
            case PIN:
            default:
                if (AppLockManager.isLockedOut(this)) {
                    showLockout();
                } else if (AppLockManager.verifyPin(this, pin)) {
                    AppLockManager.resetFailures(this);
                    if (screen == Screen.VERIFY) {
                        showCreateScreen();
                    } else {
                        unlockSuccess();
                    }
                } else {
                    int left = AppLockManager.recordFailure(this);
                    if (left < 0) {
                        showLockout();
                    } else {
                        showError(getString(R.string.lock_error_wrong, left));
                        shakeDots();
                    }
                }
                break;
        }
        submitting = false;
        renderDots();
    }

    private void unlockSuccess() {
        AppLockManager.setSessionUnlocked(true);
        cancelBiometric();
        if (MODE_CHANGE.equals(mode)) {
            Ui.notifyOnReturn(R.string.lock_pin_updated);
            setResult(RESULT_OK);
            finish();
        } else {
            // Vault warm-up (post-auth half): dashboard/trash/link-pool rows.
            // Overlapped handoff — DashboardActivity binds from the snapshot
            // the moment it lands instead of querying from a cold open.
            // Skipped for MODE_CHANGE (returns to Settings, no vault lists).
            VaultWarmCache.get(this).preloadPostAuthAsync();
            if (MODE_VERIFY.equals(mode)) {
                setResult(RESULT_OK);
                finish();
            } else {
                startActivity(new Intent(this, DashboardActivity.class));
                finish();
            }
        }
    }

    // ------------------------------------------------------------------
    // Biometrics (system prompt; only reachable when opted in + supported)
    // ------------------------------------------------------------------

    private void startBiometric() {
        if (promptActive || paused || isFinishing()
                || !AppLockManager.canUseBiometric(this)) {
            return;
        }
        if (AppLockManager.isLockedOut(this)) {
            return;
        }
        // Only auto-ask on plain PIN unlock — never during setup / change.
        if (screen != Screen.PIN || MODE_CHANGE.equals(mode)) {
            return;
        }
        promptActive = true;
        BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.app_name))
                .setSubtitle(getString(R.string.lock_sub_enter))
                .setNegativeButtonText(getString(R.string.lock_use_pin))
                .build();
        biometricPrompt.authenticate(info);
    }

    private void cancelBiometric() {
        promptActive = false;
        if (biometricPrompt != null) {
            try {
                biometricPrompt.cancelAuthentication();
            } catch (RuntimeException ignored) {
            }
        }
    }

    private BiometricPrompt.AuthenticationCallback biometricCallback() {
        return new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationSucceeded(
                    @NonNull BiometricPrompt.AuthenticationResult result) {
                promptActive = false;
                if (!isFinishing() && !paused
                        && !AppLockManager.isLockedOut(LockActivity.this)) {
                    AppLockManager.resetFailures(LockActivity.this);
                    unlockSuccess();
                }
            }

            @Override
            public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                promptActive = false;
                if (isFinishing() || paused) {
                    return;
                }
                if (errorCode == BiometricPrompt.ERROR_USER_CANCELED
                        || errorCode == BiometricPrompt.ERROR_CANCELED
                        || errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                    // User dismissed — stay on the same keypad design.
                    return;
                }
                showError(getString(R.string.lock_error_fingerprint));
            }

            @Override
            public void onAuthenticationFailed() {
                if (isFinishing() || paused) {
                    return;
                }
                showError(getString(R.string.lock_error_fingerprint));
            }
        };
    }

    // ------------------------------------------------------------------
    // Small visuals
    // ------------------------------------------------------------------

    private void renderDots() {
        for (int i = 0; i < dots.length; i++) {
            dots[i].setBackgroundResource(i < entry.length()
                    ? R.drawable.bg_pin_dot_filled : R.drawable.bg_pin_dot_empty);
        }
    }

    private void showError(String message) {
        error.setText(message);
    }

    private void clearError() {
        error.setText("");
    }

    private void showLockout() {
        long seconds = AppLockManager.lockoutRemainingSeconds(this);
        showError(getString(R.string.lock_error_locked, Math.max(seconds, 1)));
        lockoutHandler.removeCallbacks(lockoutTicker);
        lockoutHandler.postDelayed(lockoutTicker, 1000);
    }

    private void shakeDots() {
        android.animation.ObjectAnimator shake =
                android.animation.ObjectAnimator.ofFloat(dotsRow, View.TRANSLATION_X, 0f, 12f, 0f, -12f, 0f);
        shake.setDuration(240);
        shake.start();
    }
}
