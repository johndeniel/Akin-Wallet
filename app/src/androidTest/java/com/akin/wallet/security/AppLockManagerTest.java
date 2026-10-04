package com.akin.wallet.security;

import android.content.*;
import android.os.SystemClock;

import androidx.test.platform.app.InstrumentationRegistry;

import java.io.File;
import java.util.UUID;

import org.junit.*;

import static org.junit.Assert.*;

/**
 * Authentication fixtures isolate preferences and database paths from any installed vault.
 */
public class AppLockManagerTest {
    private Context isolated;
    private Context base;
    private String prefix;

    @Before
    public void create() {
        base = InstrumentationRegistry.getInstrumentation().getTargetContext();
        prefix = "test-lock-" + UUID.randomUUID();
        isolated = new ContextWrapper(base) {
            @Override
            public Context getApplicationContext() {
                return this;
            }

            @Override
            public SharedPreferences getSharedPreferences(String name, int mode) {
                return base.getSharedPreferences(prefix + "-" + name, mode);
            }

            @Override
            public File getDatabasePath(String name) {
                return base.getDatabasePath(prefix + "-" + name);
            }
        };
    }

    @After
    public void cleanup() {
        base.deleteSharedPreferences(prefix + "-akin_app_lock");
    }

    private SharedPreferences state() {
        return isolated.getSharedPreferences("akin_app_lock", Context.MODE_PRIVATE);
    }

    @Test
    public void verifierIsNotThePinAndCorrectPinClearsReservedAttempt() {
        AppLockManager.setPin(isolated, "1234");
        assertNotEquals("1234", state().getString("pin_hash_v2", ""));
        assertTrue(AppLockManager.verifyAndRecord(isolated, "1234").verified);
        assertEquals(0, state().getInt("failed_attempts", 0));
    }

    @Test
    public void fiveFailuresStartCooldownAndBlockEvenCorrectPin() {
        AppLockManager.setPin(isolated, "1234");
        for (int i = 0; i < 5; i++)
            assertFalse(AppLockManager.verifyAndRecord(isolated, "9999").verified);
        assertTrue(AppLockManager.isLockedOut(isolated));
        assertFalse(AppLockManager.verifyAndRecord(isolated, "1234").verified);
        assertTrue(AppLockManager.lockoutRemainingSeconds(isolated) > 0);
        assertTrue(AppLockManager.lockoutRemainingSeconds(isolated) <= 30);
    }

    @Test
    public void cooldownEscalatesAfterPreviousDeadlineExpires() {
        AppLockManager.setPin(isolated, "1234");
        for (int i = 0; i < 5; i++) AppLockManager.verifyAndRecord(isolated, "9999");
        assertTrue(state().edit().putLong("lockout_until", SystemClock.elapsedRealtime() - 1).commit());
        for (int i = 0; i < 5; i++) AppLockManager.verifyAndRecord(isolated, "9999");
        assertTrue(AppLockManager.lockoutRemainingSeconds(isolated) > 30);
        assertTrue(AppLockManager.lockoutRemainingSeconds(isolated) <= 60);
    }

    @Test
    public void changedBootRestartsPendingBoundedCooldown() {
        AppLockManager.setPin(isolated, "1234");
        for (int i = 0; i < 5; i++) AppLockManager.verifyAndRecord(isolated, "9999");
        assertTrue(state().edit().putInt("lockout_boot", -1).putLong("lockout_until", 1).commit());
        assertTrue(AppLockManager.isLockedOut(isolated));
        assertTrue(AppLockManager.lockoutRemainingSeconds(isolated) <= 30);
    }

    @Test
    public void partialVerifierNeverOpensSetup() {
        assertTrue(state().edit().putString("pin_hash_v2", "broken").commit());
        assertTrue(AppLockManager.isPinSet(isolated));
        assertFalse(AppLockManager.verifyPin(isolated, "1234"));
    }
}
