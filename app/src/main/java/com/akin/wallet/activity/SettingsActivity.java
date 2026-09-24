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
 * Settings — security preferences behind the app lock.
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

        TextView versionLabel = findViewById(R.id.settings_version_label);
        versionLabel.setText(
                getString(R.string.settings_version_format, AkinWallet.versionName()));

        biometricSwitch = findViewById(R.id.settings_biometric_switch);
        biometricStatus = findViewById(R.id.settings_biometric_status);

        refreshBiometric();

        biometricSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
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

    private void bindRow(int rowId, Runnable action) {
        findViewById(rowId).setOnClickListener(v -> action.run());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!confirming) {
            refreshBiometric();
        }
    }

    private void refreshBiometric() {
        biometricAvailable = AppLockManager.isBiometricAvailable(this);
        boolean enabled = AppLockManager.isBiometricEnabled(this) && biometricAvailable;
        biometricSwitch.setChecked(enabled);
        biometricSwitch.setEnabled(biometricAvailable);
        refreshBiometricStatus(biometricAvailable, enabled);
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

    /** Toggle sticks only when the system prompt succeeds, else it reverts. */
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
                        if (!isAlive()) {
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
                        if (!isAlive()) {
                            return;
                        }
                        biometricSwitch.setChecked(false);
                        refreshBiometricStatus(biometricAvailable, false);
                    }
                });
        confirmPrompt.authenticate(info);
    }
}
