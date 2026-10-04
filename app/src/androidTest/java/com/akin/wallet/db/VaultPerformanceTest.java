package com.akin.wallet.db;

import android.content.Context;
import android.database.Cursor;
import android.os.Bundle;
import android.os.Debug;
import android.os.SystemClock;

import androidx.test.platform.app.InstrumentationRegistry;

import com.akin.wallet.model.BankCardModel;
import com.akin.wallet.model.GovernmentIDModel;
import com.akin.wallet.model.SocialAccountModel;
import com.akin.wallet.util.DashboardSearch;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.*;

/** Reproducible encrypted workload; never opens the user's database. */
public class VaultPerformanceTest {
    private static final int RECORDS_PER_SECTION = 1000;

    public static void seed(AppDatabaseHelper helper) {
        net.zetetic.database.sqlcipher.SQLiteDatabase database = helper.getWritableDatabase();
        database.beginTransaction();
        try {
            for (int position = 0; position < RECORDS_PER_SECTION; position++) {
                String username = "fixture-user-" + position;
                helper.saveSocialAccountWithLinks(new SocialAccountModel(0, "Facebook", username,
                        "fixture-secret", "0123", 0, 0, 0), List.of());
                helper.insertBankCard(new BankCardModel("Debit", "Visa", "Fixture Bank", username,
                        "0001222233334444", "1229", "123", "0123", position % 5));
                helper.insertIdCard(new GovernmentIDModel("Passport", Map.of(
                        "first_name", username, "last_name", "Fixture", "passport_no", "P" + position,
                        "birth_date", "2000-01-01", "nationality", "Filipino", "sex", "Male")));
            }
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    private static long heapBytes() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static long allocatedBytes() {
        String value = Debug.getRuntimeStat("art.gc.bytes-allocated");
        return value == null ? -1 : Long.parseLong(value);
    }

    private static void collect() {
        Runtime.getRuntime().gc();
        SystemClock.sleep(100);
        Runtime.getRuntime().gc();
    }

    public static void report(String name, JSONObject metrics) {
        Bundle status = new Bundle();
        status.putString("performance_" + name, metrics.toString());
        InstrumentationRegistry.getInstrumentation().sendStatus(0, status);
    }

    private static JSONObject timings(long[] samples) throws Exception {
        Arrays.sort(samples);
        return new JSONObject().put("samples", samples.length)
                .put("p50_ms", samples[samples.length / 2] / 1_000_000.0)
                .put("p95_ms", samples[(int) Math.ceil(samples.length * .95) - 1] / 1_000_000.0)
                .put("max_ms", samples[samples.length - 1] / 1_000_000.0);
    }

    @Test
    public void encryptedThreeThousandRecordWorkload() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String name = "performance-" + UUID.randomUUID() + ".db";
        byte[] key = "synthetic-performance-key".getBytes(StandardCharsets.UTF_8);
        AppDatabaseHelper helper = new AppDatabaseHelper(context, name, key.clone());
        try {
            seed(helper);
            helper.close();
            helper = new AppDatabaseHelper(context, name, key.clone());
            collect();
            long heapBefore = heapBytes();
            long openedAt = SystemClock.elapsedRealtimeNanos();
            List<GovernmentIDModel> ids = helper.getAllIdCards();
            List<BankCardModel> cards = helper.getAllBankCards();
            List<SocialAccountModel> accounts = helper.getAllSocialAccounts();
            double coldReadMs = (SystemClock.elapsedRealtimeNanos() - openedAt) / 1_000_000.0;
            long indexedAt = SystemClock.elapsedRealtimeNanos();
            DashboardSearch.Index index = DashboardSearch.buildIndex(ids, cards, accounts);
            double indexMs = (SystemClock.elapsedRealtimeNanos() - indexedAt) / 1_000_000.0;
            collect();
            long retainedHeapEstimate = Math.max(0, heapBytes() - heapBefore);
            assertEquals(RECORDS_PER_SECTION, ids.size());
            assertEquals(RECORDS_PER_SECTION, cards.size());
            assertEquals(RECORDS_PER_SECTION, accounts.size());
            assertEquals("", accounts.get(0).getPassword());
            assertEquals("", cards.get(0).getCvv());
            for (String table : List.of("social_accounts", "bank_cards", "id_cards")) {
                try (Cursor cursor = helper.getReadableDatabase().rawQuery(
                        "EXPLAIN QUERY PLAN SELECT id FROM " + table
                                + " WHERE deleted_at=0 ORDER BY updated_at DESC,id DESC", null)) {
                    assertTrue(cursor.moveToFirst());
                    assertTrue(cursor.getString(3), cursor.getString(3).contains("idx_" + table + "_active"));
                }
            }
            long[] warmRead = new long[15];
            long[] preparation = new long[15];
            long[] fullRefresh = new long[15];
            long allocationBeforeFull = allocatedBytes();
            for (int iteration = 0; iteration < warmRead.length; iteration++) {
                long start = SystemClock.elapsedRealtimeNanos();
                List<GovernmentIDModel> loadedIds = helper.getAllIdCards();
                List<BankCardModel> loadedCards = helper.getAllBankCards();
                List<SocialAccountModel> loadedAccounts = helper.getAllSocialAccounts();
                warmRead[iteration] = SystemClock.elapsedRealtimeNanos() - start;
                start = SystemClock.elapsedRealtimeNanos();
                assertNotNull(DashboardSearch.buildIndex(loadedIds, loadedCards, loadedAccounts));
                preparation[iteration] = SystemClock.elapsedRealtimeNanos() - start;
                fullRefresh[iteration] = warmRead[iteration] + preparation[iteration];
            }
            long allocationAfterFull = allocatedBytes();
            JSONObject searches = new JSONObject();
            long[] socialOnlyRead = new long[15];
            long[] socialOnlyPreparation = new long[15];
            long[] socialOnlyRefresh = new long[15];
            long allocationBeforeSocial = allocatedBytes();
            for (int iteration = 0; iteration < socialOnlyRead.length; iteration++) {
                long start = SystemClock.elapsedRealtimeNanos();
                List<SocialAccountModel> loadedAccounts = helper.getAllSocialAccounts();
                socialOnlyRead[iteration] = SystemClock.elapsedRealtimeNanos() - start;
                start = SystemClock.elapsedRealtimeNanos();
                DashboardSearch.Index updated = DashboardSearch.buildIndex(index,
                        index.idRows(), index.cardRows(), loadedAccounts);
                socialOnlyPreparation[iteration] = SystemClock.elapsedRealtimeNanos() - start;
                socialOnlyRefresh[iteration] = socialOnlyRead[iteration] + socialOnlyPreparation[iteration];
                assertSame(index.idRows(), updated.idRows());
                assertSame(index.cardRows(), updated.cardRows());
                assertNotSame(index.accountRows(), updated.accountRows());
            }
            long allocationAfterSocial = allocatedBytes();
            helper.touchIdCardUpdatedAt(ids.get(ids.size() - 1).getId());
            long[] governmentRead = new long[15];
            long[] governmentPreparation = new long[15];
            long[] fullGovernmentPreparation = new long[15];
            long governmentIncrementalAllocation = 0;
            long governmentFullAllocation = 0;
            for (int iteration = 0; iteration < governmentRead.length; iteration++) {
                long start = SystemClock.elapsedRealtimeNanos();
                List<GovernmentIDModel> loadedIds = helper.getAllIdCards();
                governmentRead[iteration] = SystemClock.elapsedRealtimeNanos() - start;
                long allocationBefore = allocatedBytes();
                start = SystemClock.elapsedRealtimeNanos();
                DashboardSearch.Index updated = DashboardSearch.buildIndex(index,
                        loadedIds, index.cardRows(), index.accountRows());
                governmentPreparation[iteration] = SystemClock.elapsedRealtimeNanos() - start;
                if (allocationBefore >= 0) governmentIncrementalAllocation += allocatedBytes() - allocationBefore;
                assertEquals(loadedIds, updated.idRows());
                assertSame(index.cardRows(), updated.cardRows());
                assertSame(index.accountRows(), updated.accountRows());
                allocationBefore = allocatedBytes();
                start = SystemClock.elapsedRealtimeNanos();
                assertNotNull(DashboardSearch.buildIndex(loadedIds, List.of(), List.of()));
                fullGovernmentPreparation[iteration] = SystemClock.elapsedRealtimeNanos() - start;
                if (allocationBefore >= 0) governmentFullAllocation += allocatedBytes() - allocationBefore;
            }
            for (String query : List.of("", "fixture", "fixture-user-999", "no-match", "unrelated 2222")) {
                DashboardSearch.Query normalized = DashboardSearch.normalizeQuery(query);
                for (int warmup = 0; warmup < 10; warmup++) DashboardSearch.search(index, normalized);
                long[] samples = new long[100];
                for (int iteration = 0; iteration < samples.length; iteration++) {
                    long start = SystemClock.elapsedRealtimeNanos();
                    DashboardSearch.Result result = DashboardSearch.search(index, normalized);
                    samples[iteration] = SystemClock.elapsedRealtimeNanos() - start;
                    if (query.equals("fixture")) {
                        assertEquals(RECORDS_PER_SECTION, result.ids.size());
                        assertEquals(RECORDS_PER_SECTION, result.cards.size());
                        assertEquals(RECORDS_PER_SECTION, result.accounts.size());
                    }
                }
                searches.put(query.isEmpty() ? "empty" : query, timings(samples));
            }
            Debug.MemoryInfo memory = new Debug.MemoryInfo();
            Debug.getMemoryInfo(memory);
            report("database", new JSONObject().put("records_per_section", RECORDS_PER_SECTION)
                    .put("records_total", RECORDS_PER_SECTION * 3)
                    .put("device", android.os.Build.MODEL).put("api", android.os.Build.VERSION.SDK_INT)
                    .put("cold_open_and_read_ms", coldReadMs).put("first_index_ms", indexMs)
                    .put("warm_read", timings(warmRead)).put("index_build", timings(preparation))
                    .put("full_refresh", timings(fullRefresh))
                    .put("social_only_read", timings(socialOnlyRead))
                    .put("social_only_index_update", timings(socialOnlyPreparation))
                    .put("social_only_refresh", timings(socialOnlyRefresh))
                    .put("government_only_read", timings(governmentRead))
                    .put("government_only_index_update", timings(governmentPreparation))
                    .put("government_full_index_build", timings(fullGovernmentPreparation))
                    .put("government_incremental_index_java_allocation_bytes_per_iteration",
                            governmentIncrementalAllocation / governmentRead.length)
                    .put("government_full_index_java_allocation_bytes_per_iteration",
                            governmentFullAllocation / governmentRead.length)
                    .put("full_refresh_java_allocation_bytes_per_iteration", allocationBeforeFull < 0 ? -1
                            : (allocationAfterFull - allocationBeforeFull) / warmRead.length)
                    .put("social_only_refresh_java_allocation_bytes_per_iteration", allocationBeforeSocial < 0 ? -1
                            : (allocationAfterSocial - allocationBeforeSocial) / socialOnlyRead.length)
                    .put("search", searches).put("retained_java_heap_estimate_bytes", retainedHeapEstimate)
                    .put("instrumented_process_pss_kib", memory.getTotalPss()));
        } finally {
            helper.close();
            Arrays.fill(key, (byte) 0);
            assertTrue(context.deleteDatabase(name));
        }
    }
}
