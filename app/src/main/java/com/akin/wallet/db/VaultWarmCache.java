package com.akin.wallet.db;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.akin.wallet.model.BankCardModel;
import com.akin.wallet.model.GovernmentIDModel;
import com.akin.wallet.model.SocialAccountModel;
import com.akin.wallet.model.SocialPlatformModel;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * App-scoped vault warm cache: removes the un-lock -&gt; dashboard empty flash
 * without adding any loading UI.
 *
 * <p>Split by authentication boundary (the vault key is not PIN-derived, so
 * plaintext must never be cached pre-auth):
 *
 * <ul>
 *   <li>Pre-auth ({@link #warmConnectionAsync} + {@link #catalog()}): native
 *       SQLCipher library, Keystore unseal via {@code DbKeyManager}, DB
 *       connection open, and the static platform catalog. No row data.</li>
 *   <li>Post-auth ({@link #preloadPostAuthAsync}): the 6 dashboard/trash
 *       queries. Call only from {@code LockActivity.unlockSuccess()} /
 *       biometric success, or from an already-unlocked screen.</li>
 * </ul>
 *
 * <p>Memory: one immutable {@link Snapshot} holding list copies (wallet-scale:
 * tens to hundreds of rows, kilobytes). Models are immutable value objects,
 * so sharing across activities is safe; every accessor returns a copy to
 * guard the cached lists against adapter mutation. Non-sensitive
 * {@link SocialPlatformModel.Option} catalog is kept separately and is never
 * cleared. {@link #clearSensitiveOnLock()} drops row data when the session
 * re-locks (background grace expiry), so plaintext never lingers while
 * locked.
 *
 * <p>Threading: single-thread I/O keeps SQLCipher access ordered. The
 * snapshot reference is volatile; publish/invalidate paths are synchronized.
 */
public final class VaultWarmCache {

    private static final String TAG = "VaultWarm";
    private static volatile VaultWarmCache sInstance;

    /** App-scoped singleton. Holds the application context only. */
    @NonNull
    public static VaultWarmCache get(@NonNull Context context) {
        VaultWarmCache cached = sInstance;
        if (cached != null) {
            return cached;
        }
        synchronized (VaultWarmCache.class) {
            if (sInstance == null) {
                sInstance = new VaultWarmCache(context.getApplicationContext());
            }
            return sInstance;
        }
    }

    /** Immutable point-in-time copy of every list the home surfaces need. */
    public static final class Snapshot {
        public final List<GovernmentIDModel> activeIds;
        public final List<BankCardModel> activeCards;
        public final List<SocialAccountModel> activeAccounts;
        public final List<GovernmentIDModel> trashedIds;
        public final List<BankCardModel> trashedCards;
        public final List<SocialAccountModel> trashedAccounts;
        public final long loadedAt;

        Snapshot(List<GovernmentIDModel> activeIds,
                 List<BankCardModel> activeCards,
                 List<SocialAccountModel> activeAccounts,
                 List<GovernmentIDModel> trashedIds,
                 List<BankCardModel> trashedCards,
                 List<SocialAccountModel> trashedAccounts,
                 long loadedAt) {
            this.activeIds = activeIds != null
                    ? List.copyOf(activeIds)
                    : null;
            this.activeCards = activeCards != null
                    ? List.copyOf(activeCards)
                    : null;
            this.activeAccounts = activeAccounts != null
                    ? List.copyOf(activeAccounts)
                    : null;
            this.trashedIds = trashedIds != null
                    ? List.copyOf(trashedIds)
                    : null;
            this.trashedCards = trashedCards != null
                    ? List.copyOf(trashedCards)
                    : null;
            this.trashedAccounts = trashedAccounts != null
                    ? List.copyOf(trashedAccounts)
                    : null;
            this.loadedAt = loadedAt;
        }

        /** True when the dashboard masters are present (search + carousels + link pool). */
        public boolean hasActive() {
            return activeIds != null && activeCards != null && activeAccounts != null;
        }

        /** True when the trash lists are present. */
        public boolean hasTrash() {
            return trashedIds != null && trashedCards != null && trashedAccounts != null;
        }
    }

    private final Context appContext;
    /**
     * The single funnel for ALL vault I/O (warm-up, preload, dashboard/trash
     * refreshes, bulk writes). One thread keeps every access to the shared
     * connection strictly ordered, so reads and write transactions can never
     * interleave and close/open races are impossible by construction.
     */
    private final ExecutorService vaultIo = Executors.newSingleThreadExecutor();
    private final AtomicBoolean connectionWarmed = new AtomicBoolean(false);
    private final AtomicBoolean preloadInFlight = new AtomicBoolean(false);

    private volatile AppDatabaseHelper helper;
    private volatile Snapshot snapshot;
    private volatile List<SocialPlatformModel.Option> platformCatalog;

    private VaultWarmCache(@NonNull Context appContext) {
        this.appContext = appContext;
    }

    /**
     * Shared helper for every activity. One open connection removes the
     * per-screen Keystore + SQLCipher open cost. Never close it per-screen;
     * it lives with the process.
     */
    @NonNull
    public synchronized AppDatabaseHelper helper() {
        if (helper == null) {
            helper = new AppDatabaseHelper(appContext);
        }
        return helper;
    }

    /** Last published snapshot, or null when nothing is cached. */
    @Nullable
    public Snapshot snapshot() {
        return snapshot;
    }

    /**
     * Runs vault work on the shared serialized I/O thread. Activities must
     * use this instead of owning an executor, so background reads/writes stay
     * ordered against warm-up and preload.
     */
    public void executeVaultIo(@NonNull Runnable task) {
        vaultIo.execute(task);
    }

    /**
     * Pre-auth warm: loads the native library (static init), unseals the
     * Keystore passphrase, and opens the DB connection. Touches no rows,
     * so it is safe to run while the PIN/biometric prompt is visible.
     */
    public void warmConnectionAsync() {
        if (!connectionWarmed.compareAndSet(false, true)) {
            return;
        }
        vaultIo.execute(() -> {
            try {
                helper().getReadableDatabase();
            } catch (RuntimeException e) {
                // Next touch retries: allow a later warm/preload to re-attempt.
                connectionWarmed.set(false);
                android.util.Log.w(TAG, "connection warm failed", e);
            }
        });
    }

    /**
     * Non-sensitive platform catalog (43 static entries). Cached once,
     * never cleared on lock. Cheap enough to build inline on first use.
     */
    @NonNull
    public List<SocialPlatformModel.Option> catalog() {
        List<SocialPlatformModel.Option> cached = platformCatalog;
        if (cached != null) {
            return new ArrayList<>(cached);
        }
        List<SocialPlatformModel.Option> fresh = SocialPlatformModel.catalog();
        platformCatalog = List.copyOf(fresh);
        return new ArrayList<>(fresh);
    }

    /** Pre-builds the catalog off the UI thread (called alongside connection warm). */
    public void warmCatalogAsync() {
        vaultIo.execute(this::catalog);
    }

    /**
     * Post-auth preload: dashboard masters + trash lists in one ordered pass.
     * Call only after PIN/biometric success (or from an unlocked screen).
     * Overlapping calls collapse into one; failures leave the previous
     * snapshot intact so consumers fall back to direct queries.
     */
    public void preloadPostAuthAsync() {
        if (!preloadInFlight.compareAndSet(false, true)) {
            return;
        }
        vaultIo.execute(() -> {
            try {
                AppDatabaseHelper db = helper();
                List<GovernmentIDModel> ids = db.getAllIdCards();
                List<BankCardModel> cards = db.getAllBankCards();
                List<SocialAccountModel> accounts = db.getAllSocialAccounts();
                List<GovernmentIDModel> trashIds = db.getTrashedIdCards();
                List<BankCardModel> trashCards = db.getTrashedBankCards();
                List<SocialAccountModel> trashAccounts = db.getTrashedSocialAccounts();
                publish(ids, cards, accounts, trashIds, trashCards, trashAccounts);
            } catch (RuntimeException e) {
                Log.w(TAG, "post-auth preload failed", e);
            } finally {
                preloadInFlight.set(false);
            }
        });
    }

    /** Dashboard refresh path publishes its fresh masters (also refreshes search + link pool). */
    public synchronized void publishActive(@Nullable List<GovernmentIDModel> ids,
                                           @Nullable List<BankCardModel> cards,
                                           @Nullable List<SocialAccountModel> accounts) {
        Snapshot current = snapshot;
        snapshot = new Snapshot(
                ids != null ? ids : (current != null ? current.activeIds : null),
                cards != null ? cards : (current != null ? current.activeCards : null),
                accounts != null ? accounts : (current != null ? current.activeAccounts : null),
                current != null ? current.trashedIds : null,
                current != null ? current.trashedCards : null,
                current != null ? current.trashedAccounts : null,
                SystemClock.elapsedRealtime());
    }

    /** Trash refresh path publishes its fresh lists. */
    public synchronized void publishTrash(@Nullable List<GovernmentIDModel> ids,
                                          @Nullable List<BankCardModel> cards,
                                          @Nullable List<SocialAccountModel> accounts) {
        Snapshot current = snapshot;
        snapshot = new Snapshot(
                current != null ? current.activeIds : null,
                current != null ? current.activeCards : null,
                current != null ? current.activeAccounts : null,
                ids != null ? ids : (current != null ? current.trashedIds : null),
                cards != null ? cards : (current != null ? current.trashedCards : null),
                accounts != null ? accounts : (current != null ? current.trashedAccounts : null),
                SystemClock.elapsedRealtime());
    }

    private synchronized void publish(List<GovernmentIDModel> ids,
                                      List<BankCardModel> cards,
                                      List<SocialAccountModel> accounts,
                                      List<GovernmentIDModel> trashIds,
                                      List<BankCardModel> trashCards,
                                      List<SocialAccountModel> trashAccounts) {
        snapshot = new Snapshot(ids, cards, accounts,
                trashIds, trashCards, trashAccounts, SystemClock.elapsedRealtime());
    }

    /** Drops cached rows after any write (save/restore/delete). Next, resume re-queries. */
    public synchronized void invalidate() {
        snapshot = null;
    }

    /** Drops row data on re-lock; catalog survives (no user data). */
    public synchronized void clearSensitiveOnLock() {
        invalidate();
    }
}
