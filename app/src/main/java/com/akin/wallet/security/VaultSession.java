package com.akin.wallet.security;

/**
 * Process-only authentication boundary. Times are elapsed realtime milliseconds.
 */
public final class VaultSession {
    public static final long GRACE_MS = 60_000L;
    private boolean unlocked;
    private long generation;
    private long backgroundedAt = -1;

    public synchronized void unlock() {
        generation++;
        unlocked = true;
        backgroundedAt = -1;
    }

    public synchronized void lock() {
        generation++;
        unlocked = false;
    }

    public synchronized long generation() {
        return generation;
    }

    public synchronized boolean isCurrent(long token) {
        return unlocked && token == generation;
    }

    public synchronized boolean isUnlocked() {
        return unlocked;
    }

    public synchronized void background(long now) {
        backgroundedAt = now;
    }

    public synchronized boolean shouldLock(long now) {
        return !unlocked || (backgroundedAt >= 0 && now - backgroundedAt >= GRACE_MS);
    }

    /**
     * Evaluate the previous background period before resetting it.
     */
    public synchronized boolean foreground(long now) {
        boolean expired = shouldLock(now);
        if (expired && unlocked) lock();
        backgroundedAt = -1;
        return expired;
    }
}
