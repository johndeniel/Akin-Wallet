package com.akin.wallet.activity;

import android.os.SystemClock;
import android.view.View;

import androidx.lifecycle.Lifecycle;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;

import com.akin.wallet.AkinWallet;
import com.akin.wallet.FixtureWallet;
import com.akin.wallet.R;
import com.akin.wallet.model.SocialAccountModel;
import com.akin.wallet.security.AppLockManager;

import org.junit.Test;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class DashboardEmptyTransitionTest {
    private AkinWallet wallet;

    private void mutate(Runnable operation) throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        wallet.getVaultStore().execute(() -> {
            try {
                operation.run();
                wallet.getVaultStore().invalidate();
            } catch (Throwable failure) {
                error.set(failure);
            } finally {
                completed.countDown();
            }
        }, failure -> {
            error.set(failure);
            completed.countDown();
        });
        assertTrue(completed.await(10, TimeUnit.SECONDS));
        if (error.get() != null) throw new AssertionError(error.get());
    }

    private long save(String username) {
        return wallet.getDbHelper().saveSocialAccountWithLinks(new SocialAccountModel(
                0, "Facebook", username, "", "", 0, 0, 0), Collections.emptyList());
    }

    private void awaitRows(ActivityScenario<DashboardActivity> screen, int expected) {
        AtomicBoolean ready = new AtomicBoolean();
        AtomicReference<String> observed = new AtomicReference<>();
        for (int attempt = 0; attempt < 100; attempt++) {
            screen.onActivity(activity -> {
                RecyclerView rows = activity.findViewById(R.id.dashboard_social_account_list);
                View card = activity.findViewById(R.id.dashboard_social_account_card);
                View empty = activity.findViewById(R.id.dashboard_social_account_empty_state);
                DashboardActivity.DashboardState state = new androidx.lifecycle.ViewModelProvider(activity)
                        .get(DashboardActivity.DashboardState.class);
                observed.set("rows=" + rows.getAdapter().getItemCount() + ", height=" + rows.getHeight()
                        + ", cardVisibility=" + card.getVisibility() + ", emptyVisibility=" + empty.getVisibility()
                        + ", children=" + rows.getChildCount() + ", stateRows="
                        + (state.accounts == null ? -1 : state.accounts.size()));
                ready.set(rows.getAdapter().getItemCount() == expected
                        && (expected == 0 ? card.getVisibility() == View.GONE
                        && empty.getVisibility() == View.VISIBLE : card.getVisibility() == View.VISIBLE
                        && empty.getVisibility() == View.GONE && rows.getHeight() > 0
                        && rows.getLayoutManager().findViewByPosition(0) != null)
                        && activity.findViewById(R.id.dashboard_content).getVisibility() == View.VISIBLE
                        && ((RecyclerView) activity.findViewById(R.id.dashboard_government_id_carousel))
                        .getAdapter().getItemCount() == 1
                        && ((RecyclerView) activity.findViewById(R.id.dashboard_bank_card_carousel))
                        .getAdapter().getItemCount() == 1);
            });
            if (ready.get()) return;
            SystemClock.sleep(50);
        }
        fail("Dashboard did not display " + expected + " social accounts after the database changed: " + observed.get());
    }

    @Test
    public void deletingEveryAccountThenAddingAgainRefreshesTheSameDashboard() throws Exception {
        wallet = (AkinWallet) InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        assertTrue(wallet instanceof FixtureWallet);
        CountDownLatch authenticated = new CountDownLatch(1);
        AppLockManager.execute(() -> {
            AppLockManager.setPin(wallet, "1234");
            authenticated.countDown();
        });
        assertTrue(authenticated.await(10, TimeUnit.SECONDS));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(wallet::onUnlocked);
        long[] ids = new long[4];
        mutate(() -> {
            wallet.getDbHelper().insertIdCard(new com.akin.wallet.model.GovernmentIDModel(
                    0, "National ID", java.util.Map.of("full_name", "Ada Example", "psn", "0000000000000000"), 1, 1));
            wallet.getDbHelper().insertBankCard(new com.akin.wallet.model.BankCardModel(
                    0, "Debit", "Visa", "Example Bank", "Ada Example", "4111111111111111", "12/30",
                    "", "", 0, 1, 1));
            for (SocialAccountModel account : wallet.getDbHelper().getAllSocialAccounts())
                wallet.getDbHelper().moveSocialAccountToTrash(account.getId());
            for (int index = 0; index < ids.length; index++) ids[index] = save("transition-" + index);
        });
        CountDownLatch releaseLoad = new CountDownLatch(1);
        wallet.getVaultStore().execute(() -> {
            try {
                assertTrue("Load gate timed out", releaseLoad.await(10, TimeUnit.SECONDS));
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
            }
        }, failure -> { throw new AssertionError(failure); });
        try (ActivityScenario<DashboardActivity> screen = ActivityScenario.launch(DashboardActivity.class)) {
            try {
                screen.onActivity(activity -> {
                    assertEquals("Dashboard exposed before data loaded", View.INVISIBLE,
                            activity.findViewById(R.id.dashboard_content).getVisibility());
                    assertEquals("Social container exposed before data loaded", View.GONE,
                            activity.findViewById(R.id.dashboard_social_account_card).getVisibility());
                });
            } finally {
                releaseLoad.countDown();
            }
            awaitRows(screen, 4);
            for (int index = 0; index < ids.length; index++) {
                long id = ids[index];
                screen.moveToState(Lifecycle.State.CREATED);
                mutate(() -> wallet.getDbHelper().moveSocialAccountToTrash(id));
                screen.moveToState(Lifecycle.State.RESUMED);
                awaitRows(screen, 3 - index);
            }
            for (int cycle = 0; cycle < 2; cycle++) {
                long[] added = new long[1];
                screen.moveToState(Lifecycle.State.CREATED);
                mutate(() -> added[0] = save("added-after-empty"));
                screen.moveToState(Lifecycle.State.RESUMED);
                awaitRows(screen, 1);
                screen.moveToState(Lifecycle.State.CREATED);
                mutate(() -> wallet.getDbHelper().moveSocialAccountToTrash(added[0]));
                screen.moveToState(Lifecycle.State.RESUMED);
                awaitRows(screen, 0);
            }
        } finally {
            releaseLoad.countDown();
        }
    }
}
