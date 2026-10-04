package com.akin.wallet.db;

import androidx.test.platform.app.InstrumentationRegistry;

import com.akin.wallet.AkinWallet;
import com.akin.wallet.FixtureWallet;

import org.junit.Test;

import static org.junit.Assert.*;

public class VaultStoreTest {
    @Test
    public void sectionInvalidationAndLockInvalidateOnlyTheAppropriateRevisions() {
        AkinWallet wallet = (AkinWallet) InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getApplicationContext();
        assertTrue(wallet instanceof FixtureWallet);
        VaultStore store = wallet.getVaultStore();
        long all = store.dataGeneration();
        long ids = store.sectionGeneration(VaultStore.Section.GOVERNMENT_IDS);
        long cards = store.sectionGeneration(VaultStore.Section.BANK_CARDS);
        long accounts = store.sectionGeneration(VaultStore.Section.SOCIAL_ACCOUNTS);
        store.invalidate(VaultStore.Section.SOCIAL_ACCOUNTS);
        assertEquals(all + 1, store.dataGeneration());
        assertEquals(ids, store.sectionGeneration(VaultStore.Section.GOVERNMENT_IDS));
        assertEquals(cards, store.sectionGeneration(VaultStore.Section.BANK_CARDS));
        assertEquals(accounts + 1, store.sectionGeneration(VaultStore.Section.SOCIAL_ACCOUNTS));
        store.invalidate(VaultStore.Section.BANK_CARDS);
        store.invalidate(VaultStore.Section.GOVERNMENT_IDS);
        assertEquals(ids + 1, store.sectionGeneration(VaultStore.Section.GOVERNMENT_IDS));
        assertEquals(cards + 1, store.sectionGeneration(VaultStore.Section.BANK_CARDS));
        store.invalidate();
        assertEquals(ids + 2, store.sectionGeneration(VaultStore.Section.GOVERNMENT_IDS));
        assertEquals(cards + 2, store.sectionGeneration(VaultStore.Section.BANK_CARDS));
        assertEquals(accounts + 2, store.sectionGeneration(VaultStore.Section.SOCIAL_ACCOUNTS));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(wallet::lockAndClear);
        assertEquals(ids + 3, store.sectionGeneration(VaultStore.Section.GOVERNMENT_IDS));
        assertEquals(cards + 3, store.sectionGeneration(VaultStore.Section.BANK_CARDS));
        assertEquals(accounts + 3, store.sectionGeneration(VaultStore.Section.SOCIAL_ACCOUNTS));
    }
}
