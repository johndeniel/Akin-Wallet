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

import com.akin.wallet.AkinWallet;
import com.akin.wallet.R;
import com.akin.wallet.security.AppLockManager;
import com.akin.wallet.util.Ui;


/**
 * App lock — launcher screen. First run walks PIN setup (create + confirm);
 * afterward PIN entry or fingerprint (only when opted in).
 */
public class LockActivity extends AppCompatActivity {

    public static final String EXTRA_MODE = "extra_mode";
    public static final String MODE_START = "start";
    public static final String MODE_VERIFY = "verify";
    public static final String MODE_CHANGE = "change";

    private enum Screen {CREATE, CONFIRM, VERIFY, PIN}

    private String mode = MODE_START;
    private Screen screen = Screen.PIN;

    private final StringBuilder entry = new StringBuilder();
    private String firstPin;
    private boolean submitting;

    // Secrets never touch savedInstanceState: rotation clears in-progress digits.

    /**
     * Delayed biometric ask / entry submit, removable on pause/destroy.
     */
    private final Runnable autoBiometric = this::startBiometric;
    private final Runnable pendingEntry = this::processEntry;
    private volatile int authenticationGeneration;

    private TextView errorLabel;
    private TextView stepLabel;
    private View pinIndicatorRow;
    private final View[] pinIndicators = new View[AppLockManager.PIN_LENGTH];
    private LinearLayout keypadLayout;
    private View biometricKey;

    private BiometricPrompt biometricPrompt;
    private boolean promptActive;
    private boolean paused;

    private final Handler lockoutHandler = new Handler(Looper.getMainLooper());
    private final Runnable lockoutTicker = () -> {
        if (!isAlive()) {
            return;
        }
        if (AppLockManager.isLockedOut(LockActivity.this)) {
            showLockout();
            lockoutHandler.postDelayed(this.lockoutTicker, 1000);
        } else {
            clearError();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_lock);
        Ui.applySystemBars(this);

        String extra = getIntent().getStringExtra(EXTRA_MODE);
        if (MODE_VERIFY.equals(extra)) mode = MODE_VERIFY;
        if (MODE_CHANGE.equals(extra) && !app().shouldReLock()) mode = MODE_CHANGE;

        errorLabel = findViewById(R.id.lock_error_label);
        stepLabel = findViewById(R.id.lock_step_label);
        pinIndicatorRow = findViewById(R.id.lock_pin_indicator_row);
        keypadLayout = findViewById(R.id.lock_keypad);
        biometricKey = findViewById(R.id.lock_keypad_biometric);
        int[] pinIndicatorIds = {R.id.lock_pin_indicator_1, R.id.lock_pin_indicator_2, R.id.lock_pin_indicator_3, R.id.lock_pin_indicator_4};
        for (int i = 0; i < pinIndicatorIds.length; i++) {
            pinIndicators[i] = findViewById(pinIndicatorIds[i]);
        }

        wireKeypad();
        if (biometricKey != null) {
            biometricKey.setOnClickListener(v -> startBiometric());
        }

        if (!AppLockManager.isPinSet(this)) {
            showCreateScreen();
        } else if (MODE_CHANGE.equals(mode)) {
            showVerifyScreen();
        } else {
            showPinScreen();
            // Biometric available: ask via system prompt over the same keypad.
            if (AppLockManager.canUseBiometric(this)) {
                keypadLayout.postDelayed(autoBiometric, 400);
            }
        }
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
        authenticationGeneration++;
        cancelBiometric();
        cancelPending();
        // A cancelled delayed submit must not stay buffered.
        entry.setLength(0);
        submitting = false;
        lockoutHandler.removeCallbacks(lockoutTicker);
        if (pinIndicatorRow != null) {
            renderDots();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        cancelPending();
        lockoutHandler.removeCallbacks(lockoutTicker);
        super.onDestroy();
    }

    private void cancelPending() {
        if (keypadLayout != null) {
            keypadLayout.removeCallbacks(autoBiometric);
        }
        if (pinIndicatorRow != null) {
            pinIndicatorRow.removeCallbacks(pendingEntry);
        }
    }

    // ------------------------------------------------------------------
    // Screens
    // ------------------------------------------------------------------

    private void resetEntry() {
        entry.setLength(0);
        submitting = false;
        keypadLayout.setVisibility(View.VISIBLE);
        pinIndicatorRow.setVisibility(View.VISIBLE);
        renderDots();
    }

    private void showCreateScreen() {
        screen = Screen.CREATE;
        firstPin = null;
        resetEntry();
        if (MODE_CHANGE.equals(mode)) {
            setStepText(getString(R.string.lock_step_new_pin));
        } else {
            setStepText(getString(R.string.lock_sub_create));
        }
        clearError();
        setBioKeyVisible(false);
    }

    private void showConfirmScreen() {
        screen = Screen.CONFIRM;
        resetEntry();
        if (MODE_CHANGE.equals(mode)) {
            setStepText(getString(R.string.lock_step_confirm_pin));
        } else {
            setStepText(getString(R.string.lock_sub_confirm));
        }
        setBioKeyVisible(false);
    }

    /**
     * Change-PIN step 1: prove the current PIN before setting a new one.
     */
    private void showVerifyScreen() {
        screen = Screen.VERIFY;
        resetEntry();
        setStepText(getString(R.string.lock_step_old_pin));
        clearError();
        setBioKeyVisible(false);
    }

    private void showPinScreen() {
        screen = Screen.PIN;
        resetEntry();
        cancelBiometric();
        resetTagline();
        clearError();
        setBioKeyVisible(AppLockManager.canUseBiometric(this) && !MODE_CHANGE.equals(mode));
        if (AppLockManager.isLockedOut(this)) {
            showLockout();
        }
    }

    /**
     * Keypad biometric key left of 0 — INVISIBLE (not GONE) to keep 0 centered.
     */
    private void setBioKeyVisible(boolean visible) {
        if (biometricKey != null) {
            biometricKey.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
            biometricKey.setImportantForAccessibility(visible
                    ? View.IMPORTANT_FOR_ACCESSIBILITY_YES
                    : View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        }
    }

    /**
     * Tagline slot doubles as the step helper during setup / update.
     */
    private void setStepText(String text) {
        stepLabel.setText(text);
        stepLabel.setLetterSpacing(0.02f);
    }

    private void resetTagline() {
        stepLabel.setText(R.string.header_tagline);
        stepLabel.setLetterSpacing(0.22f);
    }

    // ------------------------------------------------------------------
    // Keypad input
    // ------------------------------------------------------------------

    private void wireKeypad() {
        int[] digitKeyIds = {R.id.lock_keypad_digit_1, R.id.lock_keypad_digit_2, R.id.lock_keypad_digit_3, R.id.lock_keypad_digit_4, R.id.lock_keypad_digit_5,
                R.id.lock_keypad_digit_6, R.id.lock_keypad_digit_7, R.id.lock_keypad_digit_8, R.id.lock_keypad_digit_9, R.id.lock_keypad_digit_0};
        for (int i = 0; i < digitKeyIds.length; i++) {
            final char digit = i == 9 ? '0' : (char) ('1' + i);
            View key = findViewById(digitKeyIds[i]);
            if (key != null) {
                key.setContentDescription(String.valueOf(digit));
                key.setOnClickListener(v -> onDigit(digit));
            }
        }
        View backKey = findViewById(R.id.lock_keypad_backspace);
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
            pinIndicatorRow.postDelayed(pendingEntry, 120);
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
        if (!isAlive() || paused) {
            entry.setLength(0);
            submitting = false;
            return;
        }
        final int requestToken = authenticationGeneration;
        final String pin = entry.toString();
        entry.setLength(0);
        final Screen current = screen;
        final String capturedFirst = firstPin;
        if (current == Screen.CREATE) {
            firstPin = pin;
            showConfirmScreen();
            submitting = false;
            renderDots();
            return;
        }
        if (current == Screen.CONFIRM) {
            if (pin.equals(capturedFirst)) {
                submitting = true;
                AppLockManager.execute(() -> {
                    try {
                        AppLockManager.setPin(LockActivity.this, pin);
                    } catch (RuntimeException e) {
                        runOnUiAlive(requestToken, () -> {
                            firstPin = null;
                            showCreateScreen();
                            showError(getString(R.string.lock_error_mismatch));
                            submitting = false;
                            renderDots();
                        });
                        return;
                    }
                    runOnUiAlive(requestToken, () -> {
                        unlockSuccess();
                        submitting = false;
                        renderDots();
                    });
                });
            } else {
                firstPin = null;
                showCreateScreen();
                showError(getString(R.string.lock_error_mismatch));
                submitting = false;
                renderDots();
            }
            return;
        }
        final int token = authenticationGeneration;
        AppLockManager.execute(() -> {
            try {
                AppLockManager.Verification result = AppLockManager.verifyAndRecord(getApplicationContext(), pin);
                runOnUiThread(() -> {
                    if (token != authenticationGeneration || !isAlive() || paused) return;
                    if (result.verified) {
                        if (current == Screen.VERIFY) showCreateScreen();
                        else unlockSuccess();
                    } else if (result.attemptsLeft < 0) showLockout();
                    else showError(getString(R.string.lock_error_wrong, result.attemptsLeft));
                    submitting = false;
                    renderDots();
                });
            } catch (RuntimeException failure) {
                runOnUiAlive(LockActivity.this::showPinIoError);
            }
        });
    }

    private void runOnUiAlive(int token, Runnable action) {
        runOnUiThread(() -> {
            if (token == authenticationGeneration && isAlive() && !paused) action.run();
        });
    }

    private void runOnUiAlive(Runnable action) {
        runOnUiThread(() -> {
            if (!isAlive() || paused) {
                submitting = false;
                return;
            }
            action.run();
        });
    }

    private void showPinIoError() {
        showFingerprintError();
        submitting = false;
        renderDots();
    }

    private void unlockSuccess() {
        cancelBiometric();
        if (MODE_CHANGE.equals(mode)) {
            app().onUnlocked();
            app().notifyOnReturn(R.string.lock_pin_updated);
            setResult(RESULT_OK);
            finish();
        } else {
            app().onUnlocked();
            if (MODE_VERIFY.equals(mode)) {
                setResult(RESULT_OK);
                finish();
            } else {
                startActivity(new Intent(this, DashboardActivity.class));
                finish();
            }
        }
    }

    private AkinWallet app() {
        return (AkinWallet) getApplication();
    }

    private boolean isAlive() {
        return !isFinishing() && !isDestroyed();
    }

    // ------------------------------------------------------------------
    // Biometrics (only reachable when opted in + supported)
    // ------------------------------------------------------------------

    private void startBiometric() {
        if (promptActive || paused || isFinishing()
                || !AppLockManager.canUseBiometric(this)) {
            return;
        }
        if (AppLockManager.isLockedOut(this)) {
            return;
        }
        if (screen != Screen.PIN || MODE_CHANGE.equals(mode)) {
            return;
        }
        promptActive = true;
        biometricPrompt = new BiometricPrompt(this, ContextCompat.getMainExecutor(this), biometricCallback(authenticationGeneration));
        BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                .setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG)
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

    private boolean isBiometricGuarded() {
        return !isAlive() || paused;
    }

    private void showFingerprintError() {
        showError(getString(R.string.lock_error_fingerprint));
    }

    private BiometricPrompt.AuthenticationCallback biometricCallback(int token) {
        return new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationSucceeded(
                    @NonNull BiometricPrompt.AuthenticationResult result) {
                if (!promptActive || token != authenticationGeneration || isBiometricGuarded())
                    return;
                promptActive = false;
                if (AppLockManager.isLockedOut(LockActivity.this)) {
                    showLockout();
                    return;
                }
                AppLockManager.execute(() -> {
                    try {
                        AppLockManager.resetFailures(getApplicationContext());
                        runOnUiThread(() -> {
                            if (token == authenticationGeneration && !isBiometricGuarded())
                                unlockSuccess();
                        });
                    } catch (RuntimeException failure) {
                        runOnUiAlive(LockActivity.this::showPinIoError);
                    }
                });
            }

            @Override
            public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                if (!promptActive || token != authenticationGeneration || isBiometricGuarded())
                    return;
                promptActive = false;
                if (errorCode == BiometricPrompt.ERROR_USER_CANCELED
                        || errorCode == BiometricPrompt.ERROR_CANCELED
                        || errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                    return;
                }
                showFingerprintError();
            }

            @Override
            public void onAuthenticationFailed() {
                if (isBiometricGuarded()) {
                    return;
                }
                showFingerprintError();
            }
        };
    }

    // ------------------------------------------------------------------
    // Small visuals
    // ------------------------------------------------------------------

    private void renderDots() {
        for (int i = 0; i < pinIndicators.length; i++) {
            pinIndicators[i].setBackgroundResource(i < entry.length()
                    ? R.drawable.bg_pin_dot_filled : R.drawable.bg_pin_dot_empty);
        }
    }

    private void showError(String message) {
        errorLabel.setText(message);
    }

    private void clearError() {
        errorLabel.setText("");
    }

    private void showLockout() {
        long seconds = AppLockManager.lockoutRemainingSeconds(this);
        showError(getString(R.string.lock_error_locked, Math.max(seconds, 1)));
        lockoutHandler.removeCallbacks(lockoutTicker);
        lockoutHandler.postDelayed(lockoutTicker, 1000);
    }
}
