package com.akin.wallet.security;

import org.junit.Test;

import static org.junit.Assert.*;

public class VaultSessionTest {
    @Test
    public void coldProcessAlwaysLocks() {
        assertTrue(new VaultSession().shouldLock(0));
    }

    @Test
    public void fiftyNineSecondsPreservesSession() {
        VaultSession s = new VaultSession();
        s.unlock();
        long token = s.generation();
        s.background(100);
        assertFalse(s.foreground(59_100));
        assertTrue(s.isCurrent(token));
    }

    @Test
    public void sixtySecondsLocksBeforeForegroundReset() {
        VaultSession s = new VaultSession();
        s.unlock();
        long token = s.generation();
        s.background(100);
        assertTrue(s.foreground(60_100));
        assertFalse(s.isCurrent(token));
        assertTrue(s.shouldLock(60_101));
    }

    @Test
    public void sixtyOneSecondsLocks() {
        VaultSession s = new VaultSession();
        s.unlock();
        s.background(100);
        assertTrue(s.foreground(61_100));
    }

    @Test
    public void relockRejectsOldWorkAfterAnotherUnlock() {
        VaultSession s = new VaultSession();
        s.unlock();
        long token = s.generation();
        s.lock();
        s.unlock();
        assertFalse(s.isCurrent(token));
        assertTrue(s.isCurrent(s.generation()));
    }

    @Test
    public void activeTimeDoesNotExpire() {
        VaultSession s = new VaultSession();
        s.unlock();
        assertFalse(s.shouldLock(9_000_000));
    }

    @Test
    public void repeatedShortBackgroundPeriodsAreIndependent() {
        VaultSession s = new VaultSession();
        s.unlock();
        s.background(0);
        assertFalse(s.foreground(59_000));
        s.background(100_000);
        assertFalse(s.foreground(159_000));
    }

    @Test
    public void expiredBackgroundCannotAdmitWorkBeforeForeground() {
        VaultSession s = new VaultSession();
        s.unlock();
        s.background(0);
        assertTrue(s.shouldLock(60_000));
    }
}
