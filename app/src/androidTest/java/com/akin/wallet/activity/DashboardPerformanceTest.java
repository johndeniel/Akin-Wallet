package com.akin.wallet.activity;

import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.FrameMetrics;
import android.view.Window;
import android.widget.TextView;

import androidx.lifecycle.Lifecycle;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;

import com.akin.wallet.AkinWallet;
import com.akin.wallet.FixtureWallet;
import com.akin.wallet.R;
import com.akin.wallet.db.VaultPerformanceTest;
import com.akin.wallet.model.SocialAccountModel;
import com.akin.wallet.security.AppLockManager;
import com.google.android.material.search.SearchView;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.Assert.*;

/** Actual Dashboard + recycler/search workload against invocation-specific storage. */
public class DashboardPerformanceTest {
    private AkinWallet wallet;

    private void worker(Runnable operation) throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        wallet.getVaultStore().execute(() -> {
            try { operation.run(); }
            catch (Throwable failure) { error.set(failure); }
            finally { completed.countDown(); }
        }, failure -> { error.set(failure); completed.countDown(); });
        assertTrue("Vault worker timed out", completed.await(30, TimeUnit.SECONDS));
        if (error.get() != null) throw new AssertionError(error.get());
    }

    private void clearFixtureRows() {
        for (String table : List.of("account_links", "social_accounts", "bank_cards", "id_cards"))
            wallet.getDbHelper().getWritableDatabase().delete(table, null, null);
        wallet.getVaultStore().invalidate();
    }

    private void await(ActivityScenario<DashboardActivity> screen, Consumer<DashboardActivity> assertion) {
        AtomicReference<AssertionError> error = new AtomicReference<>();
        for (int attempt = 0; attempt < 500; attempt++) {
            screen.onActivity(activity -> {
                try { assertion.accept(activity); error.set(null); }
                catch (AssertionError failure) { error.set(failure); }
            });
            if (error.get() == null) return;
            SystemClock.sleep(10);
        }
        throw error.get();
    }

    private DashboardActivity.DashboardState state(DashboardActivity activity) {
        return new androidx.lifecycle.ViewModelProvider(activity).get(DashboardActivity.DashboardState.class);
    }

    private void ready(DashboardActivity activity) {
        for (int id : new int[]{R.id.dashboard_government_id_carousel,
                R.id.dashboard_bank_card_carousel, R.id.dashboard_social_account_list}) {
            RecyclerView rows = activity.findViewById(id);
            assertEquals(1000, rows.getAdapter().getItemCount());
            assertNotNull(rows.getLayoutManager().findViewByPosition(0));
            assertTrue("Only viewport rows should be attached", rows.getChildCount() < 30);
        }
    }

    @Test
    public void thousandRowsPerSectionLoadSearchScrollAndRetainOneSnapshot() throws Exception {
        wallet = (AkinWallet) InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        assertTrue(wallet instanceof FixtureWallet);
        CountDownLatch authenticated = new CountDownLatch(1);
        AtomicReference<Throwable> authenticationFailure = new AtomicReference<>();
        AppLockManager.execute(() -> {
            try { AppLockManager.setPin(wallet, "1234"); }
            catch (Throwable failure) { authenticationFailure.set(failure); }
            finally { authenticated.countDown(); }
        });
        assertTrue("Fixture PIN setup timed out", authenticated.await(30, TimeUnit.SECONDS));
        if (authenticationFailure.get() != null) throw new AssertionError(authenticationFailure.get());
        InstrumentationRegistry.getInstrumentation().runOnMainSync(wallet::onUnlocked);
        worker(() -> { clearFixtureRows(); VaultPerformanceTest.seed(wallet.getDbHelper()); wallet.getVaultStore().invalidate(); });
        List<Long> frames = Collections.synchronizedList(new ArrayList<>());
        List<Long> layoutFrames = Collections.synchronizedList(new ArrayList<>());
        List<Long> drawFrames = Collections.synchronizedList(new ArrayList<>());
        Window.OnFrameMetricsAvailableListener listener = (window, metrics, dropped) -> {
            frames.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION));
            layoutFrames.add(metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION));
            drawFrames.add(metrics.getMetric(FrameMetrics.DRAW_DURATION));
        };
        JSONObject measurements = new JSONObject().put("records_per_section", 1000).put("records_total", 3000);
        AtomicReference<DashboardActivity.DashboardState> retained = new AtomicReference<>();
        long start = SystemClock.elapsedRealtimeNanos();
        try (ActivityScenario<DashboardActivity> screen = ActivityScenario.launch(DashboardActivity.class)) {
            await(screen, this::ready);
            measurements.put("launch_to_first_visible_rows_ms", (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0);
            screen.onActivity(activity -> {
                retained.set(state(activity));
                activity.getWindow().addOnFrameMetricsAvailableListener(listener, new Handler(Looper.getMainLooper()));
            });
            final Object originalIndex = retained.get().index;
            start = SystemClock.elapsedRealtimeNanos();
            for (int repeat = 0; repeat < 10; repeat++) {
                screen.moveToState(Lifecycle.State.CREATED);
                screen.moveToState(Lifecycle.State.RESUMED);
                await(screen, this::ready);
                assertSame(originalIndex, retained.get().index);
            }
            measurements.put("ten_warm_resume_total_ms", (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0);
            AtomicReference<Object> idsBefore = new AtomicReference<>(retained.get().ids);
            AtomicReference<Object> cardsBefore = new AtomicReference<>(retained.get().cards);
            screen.moveToState(Lifecycle.State.CREATED);
            worker(() -> {
                SocialAccountModel row = wallet.getDbHelper().getAllSocialAccounts().get(999);
                wallet.getDbHelper().touchSocialAccountUpdatedAt(row.getId());
                wallet.getVaultStore().invalidate(com.akin.wallet.db.VaultStore.Section.SOCIAL_ACCOUNTS);
            });
            start = SystemClock.elapsedRealtimeNanos();
            screen.moveToState(Lifecycle.State.RESUMED);
            await(screen, activity -> { ready(activity); assertNotSame(originalIndex, state(activity).index); });
            measurements.put("one_social_change_to_visible_ms", (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0)
                    .put("unchanged_id_rows_reused", idsBefore.get() == retained.get().ids)
                    .put("unchanged_card_rows_reused", cardsBefore.get() == retained.get().cards);
            assertSame(idsBefore.get(), retained.get().ids);
            assertSame(cardsBefore.get(), retained.get().cards);
            Object changedIndex = retained.get().index;
            start = SystemClock.elapsedRealtimeNanos();
            screen.recreate();
            await(screen, this::ready);
            AtomicReference<Object> recreatedIndex = new AtomicReference<>();
            screen.onActivity(activity -> recreatedIndex.set(state(activity).index));
            assertSame(changedIndex, recreatedIndex.get());
            measurements.put("recreate_to_visible_ms", (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0);
            screen.onActivity(activity -> activity.getWindow().addOnFrameMetricsAvailableListener(listener, new Handler(Looper.getMainLooper())));
            screen.onActivity(activity -> {
                SearchView search = activity.findViewById(R.id.dashboard_search_view);
                search.show();
            });
            SystemClock.sleep(500);
            start = SystemClock.elapsedRealtimeNanos();
            screen.onActivity(activity -> ((SearchView) activity.findViewById(R.id.dashboard_search_view)).getEditText().setText("fixture-user-999"));
            await(screen, activity -> {
                RecyclerView rows = activity.findViewById(R.id.dashboard_search_social_account_list);
                assertEquals(1, rows.getAdapter().getItemCount());
                assertNotNull(rows.getLayoutManager().findViewByPosition(0));
            });
            measurements.put("single_match_search_to_visible_ms", (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0);
            long[] warmSearch = new long[10];
            for (int iteration = 0; iteration < warmSearch.length; iteration++) {
                String username = "fixture-user-" + (iteration % 2 == 0 ? 998 : 999);
                start = SystemClock.elapsedRealtimeNanos();
                screen.onActivity(activity -> ((SearchView) activity.findViewById(R.id.dashboard_search_view))
                        .getEditText().setText(username));
                await(screen, activity -> {
                    RecyclerView rows = activity.findViewById(R.id.dashboard_search_social_account_list);
                    assertEquals(1, rows.getAdapter().getItemCount());
                    android.view.View row = rows.getLayoutManager().findViewByPosition(0);
                    assertNotNull(row);
                    assertEquals(username, ((TextView) row.findViewById(R.id.social_account_row_subtitle)).getText().toString());
                });
                warmSearch[iteration] = SystemClock.elapsedRealtimeNanos() - start;
            }
            Arrays.sort(warmSearch);
            measurements.put("warm_single_match_search_p50_ms", warmSearch[5] / 1_000_000.0)
                    .put("warm_single_match_search_p95_ms", warmSearch[9] / 1_000_000.0);
            start = SystemClock.elapsedRealtimeNanos();
            screen.onActivity(activity -> ((SearchView) activity.findViewById(R.id.dashboard_search_view)).getEditText().setText("fixture"));
            await(screen, activity -> {
                RecyclerView rows = activity.findViewById(R.id.dashboard_search_social_account_list);
                assertEquals(1000, rows.getAdapter().getItemCount());
                assertTrue(rows.getChildCount() < 30);
                // DiffUtil preserves the visible anchor when earlier matches are inserted.
                assertTrue(rows.getChildCount() > 0);
            });
            measurements.put("thousand_match_search_to_visible_ms", (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0);
            screen.onActivity(activity -> {
                SearchView search = activity.findViewById(R.id.dashboard_search_view);
                for (String query : new String[]{"f", "fi", "fixture", "no-match", "fixture-user-999"})
                    search.getEditText().setText(query);
            });
            await(screen, activity -> assertEquals(1, ((RecyclerView) activity.findViewById(
                    R.id.dashboard_search_social_account_list)).getAdapter().getItemCount()));
            SystemClock.sleep(200);
            screen.onActivity(activity -> assertEquals(1, ((RecyclerView) activity.findViewById(
                    R.id.dashboard_search_social_account_list)).getAdapter().getItemCount()));
            screen.onActivity(activity -> ((SearchView) activity.findViewById(
                    R.id.dashboard_search_view)).getEditText().setText("fixture"));
            await(screen, activity -> assertEquals(1000, ((RecyclerView) activity.findViewById(
                    R.id.dashboard_search_social_account_list)).getAdapter().getItemCount()));
            screen.onActivity(activity -> {
                RecyclerView rows = activity.findViewById(R.id.dashboard_search_social_account_list);
                rows.scrollToPosition(999);
            });
            await(screen, activity -> {
                RecyclerView rows = activity.findViewById(R.id.dashboard_search_social_account_list);
                assertNotNull(rows.getLayoutManager().findViewByPosition(999));
                assertTrue(rows.getChildCount() < 30);
            });
            screen.onActivity(activity -> ((SearchView) activity.findViewById(R.id.dashboard_search_view)).hide());
            await(screen, activity -> {
                assertEquals(SearchView.TransitionState.HIDDEN,
                        ((SearchView) activity.findViewById(R.id.dashboard_search_view)).getCurrentTransitionState());
                ready(activity);
            });
            screen.onActivity(activity -> ((SearchView) activity.findViewById(R.id.dashboard_search_view)).show());
            await(screen, activity -> {
                assertEquals(SearchView.TransitionState.SHOWN,
                        ((SearchView) activity.findViewById(R.id.dashboard_search_view)).getCurrentTransitionState());
                RecyclerView rows = activity.findViewById(R.id.dashboard_search_social_account_list);
                assertEquals(1000, rows.getAdapter().getItemCount());
                assertNotNull(rows.getLayoutManager().findViewByPosition(999));
            });
            measurements.put("search_reopen_preserves_scroll_position", true);
            screen.onActivity(activity -> {
                ((SearchView) activity.findViewById(R.id.dashboard_search_view)).hide();
                wallet.lockAndClear();
                assertNull(state(activity).ids);
                assertNull(state(activity).cards);
                assertNull(state(activity).accounts);
                assertSame(com.akin.wallet.util.DashboardSearch.EMPTY_INDEX, state(activity).index);
            });
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(wallet::onUnlocked);
            worker(this::clearFixtureRows);
        }
        Debug.MemoryInfo memory = new Debug.MemoryInfo();
        Debug.getMemoryInfo(memory);
        long[] durations;
        synchronized (frames) { durations = frames.stream().mapToLong(Long::longValue).toArray(); }
        Arrays.sort(durations);
        int slowFrames = 0;
        for (long duration : durations) if (duration > 16_666_667) slowFrames++;
        measurements.put("frame_count", durations.length).put("frames_over_16_67_ms", slowFrames)
                .put("instrumented_process_pss_after_cleanup_kib", memory.getTotalPss());
        if (durations.length > 0) measurements.put("frame_p95_ms", durations[(int) Math.ceil(durations.length * .95) - 1] / 1_000_000.0);
        for (int phase = 0; phase < 2; phase++) {
            List<Long> samples = phase == 0 ? layoutFrames : drawFrames;
            long[] values;
            synchronized (samples) { values = samples.stream().mapToLong(Long::longValue).sorted().toArray(); }
            if (values.length > 0) measurements.put(phase == 0 ? "layout_measure_p95_ms" : "draw_p95_ms",
                    values[(int) Math.ceil(values.length * .95) - 1] / 1_000_000.0);
        }
        VaultPerformanceTest.report("dashboard", measurements);
    }
}
