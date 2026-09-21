package com.akin.wallet.activity;

import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import com.akin.wallet.AkinWallet;
import com.akin.wallet.R;
import com.akin.wallet.security.AppLockManager;
import com.google.android.material.materialswitch.MaterialSwitch;

/**
 * Settings — security preferences behind the app lock. Biometric unlock is
 * OFF by default and can only be turned on here: enabling runs a live
 * fingerprint check first, so a stored ON always means a working biometric.
 * Also hosts app-PIN change (current PIN is verified inside LockActivity).
 */
public class SettingsActivity extends BaseVaultActivity {

    private MaterialSwitch biometricSwitch;
    private TextView biometricStatus;
    private BiometricPrompt confirmPrompt;
    private boolean confirming;
    private boolean biometricAvailable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        applyChrome();

        // Version footer stamps the installed version.
        TextView versionLabel = findViewById(R.id.settings_version_label);
        if (versionLabel != null) {
            versionLabel.setText(
                    getString(R.string.settings_version_format, AkinWallet.versionName()));
        }

        biometricSwitch = findViewById(R.id.settings_biometric_switch);
        biometricStatus = findViewById(R.id.settings_biometric_status);
        if (biometricSwitch == null || biometricStatus == null) {
            return;
        }

        biometricAvailable = AppLockManager.isBiometricAvailable(this);
        boolean enabled = AppLockManager.isBiometricEnabled(this) && biometricAvailable;
        biometricSwitch.setChecked(enabled);
        biometricSwitch.setEnabled(biometricAvailable);
        refreshBiometricStatus(biometricAvailable, enabled);

        biometricSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            // Programmatic setChecked (e.g. reverting) must not re-trigger.
            if (!buttonView.isPressed()) {
                return;
            }
            if (isChecked) {
                confirmBiometric();
            } else {
                AppLockManager.setBiometricEnabled(this, false);
                refreshBiometricStatus(biometricAvailable, false);
            }
        });

        bindRow(R.id.settings_change_pin_row, () ->
                startActivity(new Intent(this, LockActivity.class)
                        .putExtra(LockActivity.EXTRA_MODE, LockActivity.MODE_CHANGE)));

        bindRow(R.id.settings_trash_row, () ->
                startActivity(new Intent(this, TrashActivity.class)));

        bindRow(R.id.settings_privacy_row, () ->
                startActivity(new Intent(this, PolicyActivity.class)
                        .putExtra(PolicyActivity.EXTRA_TYPE, PolicyActivity.TYPE_PRIVACY)));

        bindRow(R.id.settings_terms_row, () ->
                startActivity(new Intent(this, PolicyActivity.class)
                        .putExtra(PolicyActivity.EXTRA_TYPE, PolicyActivity.TYPE_TERMS)));

        bindRow(R.id.settings_about_row, () ->
                startActivity(new Intent(this, PolicyActivity.class)
                        .putExtra(PolicyActivity.EXTRA_TYPE, PolicyActivity.TYPE_ABOUT)));
    }

    /** Row taps are null-safe: a missing row is skipped, never an NPE. */
    private void bindRow(int rowId, Runnable action) {
        android.view.View row = findViewById(rowId);
        if (row != null) {
            row.setOnClickListener(v -> action.run());
        }
    }

    @Override
    protected void onPause() {
        if (confirmPrompt != null) {
            try {
                confirmPrompt.cancelAuthentication();
            } catch (RuntimeException ignored) {
            }
        }
        confirming = false;
        super.onPause();
    }

    private void refreshBiometricStatus(boolean isAvailable, boolean isEnabled) {
        if (!isAvailable) {
            biometricStatus.setText(R.string.settings_biometric_unavailable);
        } else if (isEnabled) {
            biometricStatus.setText(R.string.settings_biometric_on);
        } else {
            biometricStatus.setText(R.string.settings_biometric_off);
        }
    }

    /**
     * Enabling biometrics proves a working fingerprint first: the toggle
     * only sticks when the system prompt succeeds, otherwise it reverts.
     */
    private void confirmBiometric() {
        if (confirming) {
            return;
        }
        confirming = true;
        BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.settings_biometric))
                .setSubtitle(getString(R.string.lock_touch_sensor))
                .setNegativeButtonText(getString(android.R.string.cancel))
                .build();
        confirmPrompt = new BiometricPrompt(this, ContextCompat.getMainExecutor(this),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(
                            @NonNull BiometricPrompt.AuthenticationResult result) {
                        confirming = false;
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        AppLockManager.setBiometricEnabled(
                                SettingsActivity.this, true);
                        biometricSwitch.setChecked(true);
                        refreshBiometricStatus(biometricAvailable, true);
                    }

                    @Override
                    public void onAuthenticationError(
                            int errorCode, @NonNull CharSequence errString) {
                        confirming = false;
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        // Any cancel/failure reverts: ON is never stored blind.
                        biometricSwitch.setChecked(false);
                        refreshBiometricStatus(biometricAvailable, false);
                    }
                });
        confirmPrompt.authenticate(info);
    }
}
