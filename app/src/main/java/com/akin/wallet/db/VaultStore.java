package com.akin.wallet.db;

import android.content.Context;

import com.akin.wallet.model.SocialPlatformModel;
import com.akin.wallet.security.DbKeyManager;
import com.akin.wallet.security.VaultSession;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Application-owned, authenticated SQLCipher executor. Does not retain user rows.
 */
public final class VaultStore {
    public enum Section { GOVERNMENT_IDS, BANK_CARDS, SOCIAL_ACCOUNTS }

    private final Context context;
    private final VaultSession session;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ThreadLocal<Long> taskSession = new ThreadLocal<>();
    private final AtomicLong dataGeneration = new AtomicLong();
    private final AtomicLong[] sectionGenerations = {
            new AtomicLong(), new AtomicLong(), new AtomicLong()
    };
    private AppDatabaseHelper helper;
    private final List<SocialPlatformModel.Option> catalog = List.copyOf(SocialPlatformModel.catalog());

    public VaultStore(Context context, VaultSession session) {
        this.context = context.getApplicationContext();
        this.session = session;
    }

    public List<SocialPlatformModel.Option> catalog() {
        return catalog;
    }

    public long dataGeneration() {
        return dataGeneration.get();
    }

    public void invalidate() {
        for (AtomicLong generation : sectionGenerations) generation.incrementAndGet();
        dataGeneration.incrementAndGet();
    }

    public void invalidate(Section section) {
        sectionGenerations[section.ordinal()].incrementAndGet();
        dataGeneration.incrementAndGet();
    }

    public long sectionGeneration(Section section) {
        return sectionGenerations[section.ordinal()].get();
    }

    public long taskGeneration() {
        Long token = taskSession.get();
        return token == null ? session.generation() : token;
    }

    public AppDatabaseHelper helper() {
        if (taskSession.get() == null || (!session.isCurrent(taskSession.get()) || session.shouldLock(android.os.SystemClock.elapsedRealtime()))) {
            throw new IllegalStateException("Vault is locked");
        }
        if (helper == null) helper = new AppDatabaseHelper(context);
        return helper;
    }

    public void execute(Runnable operation, Consumer<RuntimeException> onError) {
        long token = session.generation();
        executor.execute(() -> {
            taskSession.set(token);
            try {
                if (!session.isCurrent(token) || session.shouldLock(android.os.SystemClock.elapsedRealtime()))
                    throw new IllegalStateException("Vault is locked");
                operation.run();
            } catch (RuntimeException failure) {
                onError.accept(failure);
            } finally {
                taskSession.remove();
            }
        });
    }

    /**
     * Call after invalidating the session; queued cleanup follows any running transaction.
     */
    public void closeOnLock() {
        invalidate();
        executor.execute(() -> {
            try {
                if (helper != null) helper.close();
            } finally {
                helper = null;
                DbKeyManager.clearMemory();
            }
        });
    }
}
