package com.akin.wallet.activity;

import android.content.*;
import android.os.SystemClock;
import android.widget.EditText;
import android.view.View;

import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;

import com.akin.wallet.*;
import com.akin.wallet.security.AppLockManager;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import org.junit.*;

import static org.junit.Assert.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;

public class VaultWorkflowTest {
    private AkinWallet wallet;

    @Before
    public void authenticateFixture() throws Exception {
        wallet = (AkinWallet) InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        assertTrue(wallet instanceof FixtureWallet);
        CountDownLatch ready = new CountDownLatch(1);
        AppLockManager.execute(() -> {
            AppLockManager.setPin(wallet, "1234");
            ready.countDown();
        });
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(wallet::onUnlocked);
    }

    private <T extends BaseVaultActivity> void awaitForm(ActivityScenario<T> scenario) {
        for (int attempt = 0; attempt < 100; attempt++) {
            AtomicBoolean bound = new AtomicBoolean();
            scenario.onActivity(activity -> bound.set(activity.findViewById(R.id.form_primary_action).hasOnClickListeners()));
            if (bound.get()) return;
            SystemClock.sleep(50);
        }
        fail("Editor did not bind");
    }

    private void verifyStored(Runnable assertion) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        wallet.getVaultStore().execute(() -> {
            try {
                assertion.run();
            } catch (Throwable failure) {
                error.set(failure);
            } finally {
                done.countDown();
            }
        }, failure -> {
            error.set(failure);
            done.countDown();
        });
        assertTrue(done.await(10, TimeUnit.SECONDS));
        if (error.get() != null) throw new AssertionError(error.get());
    }

    @Test
    public void socialDraftSurvivesRecreationAndCommitsCredentials() throws Exception {
        String username = "fixture-" + UUID.randomUUID();
        try (ActivityScenario<SocialAccountActivity> screen = ActivityScenario.launch(SocialAccountActivity.class)) {
            awaitForm(screen);
            onView(withId(R.id.social_account_username_field)).perform(replaceText(username), closeSoftKeyboard());
            onView(withId(R.id.social_account_password_field)).perform(replaceText("synthetic-password"), closeSoftKeyboard());
            screen.recreate();
            awaitForm(screen);
            onView(withId(R.id.social_account_username_field)).check(matches(withText(username)));
            onView(withId(R.id.social_account_password_field)).check(matches(withText("synthetic-password")));
            onView(withId(R.id.form_primary_action)).perform(scrollTo(), click());
            verifyStored(() -> {
                com.akin.wallet.model.SocialAccountModel row = wallet.getDbHelper().getAllSocialAccounts().stream()
                        .filter(item -> username.equals(item.getUsername())).findFirst().orElseThrow();
                assertEquals("synthetic-password", wallet.getDbHelper().getSocialAccountById(row.getId()).getPassword());
            });
        }
    }

    @Test
    public void pendingSaveCannotBeRepeatedAfterRecreation() throws Exception {
        String username = "pending-" + UUID.randomUUID();
        CountDownLatch blockerEntered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (ActivityScenario<SocialAccountActivity> screen = ActivityScenario.launch(SocialAccountActivity.class)) {
            awaitForm(screen);
            onView(withId(R.id.social_account_username_field)).perform(replaceText(username), closeSoftKeyboard());
            wallet.getVaultStore().execute(() -> {
                blockerEntered.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }, failure -> blockerEntered.countDown());
            assertTrue(blockerEntered.await(5, TimeUnit.SECONDS));
            onView(withId(R.id.form_primary_action)).perform(scrollTo(), click());
            screen.recreate();
            screen.onActivity(activity -> assertFalse(activity.findViewById(R.id.form_primary_action).isEnabled()));
            release.countDown();
            verifyStored(() -> assertEquals(1, wallet.getDbHelper().getAllSocialAccounts().stream()
                    .filter(item -> username.equals(item.getUsername())).count()));
        } finally {
            release.countDown();
        }
    }

    @Test
    public void bankDraftRestoresTypeAndNetworkSelectors() {
        try (ActivityScenario<BankCardActivity> screen = ActivityScenario.launch(BankCardActivity.class)) {
            awaitForm(screen);
            onView(withId(R.id.bank_card_type_selector)).perform(click());
            onView(withText("Credit")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click());
            onView(withId(R.id.bank_card_network_selector)).perform(click());
            onView(withText("MasterCard")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click());
            screen.recreate();
            awaitForm(screen);
            onView(withId(R.id.bank_card_type_label)).check(matches(withText("Credit")));
            onView(withId(R.id.bank_card_network_label)).check(matches(withText("MasterCard")));
        }
    }

    @Test
    public void bankCardInputCapsAtSixteenDigitsAndRejectsShortNumbers() {
        try (ActivityScenario<BankCardActivity> screen = ActivityScenario.launch(BankCardActivity.class)) {
            awaitForm(screen);
            onView(withId(R.id.bank_card_number_field))
                    .perform(scrollTo(), replaceText("4111111111111111111"), closeSoftKeyboard());
            onView(withId(R.id.bank_card_number_field)).check(matches(withText("4111 1111 1111 1111")));
            screen.recreate();
            awaitForm(screen);
            onView(withId(R.id.bank_card_number_field)).check(matches(withText("4111 1111 1111 1111")));
            onView(withId(R.id.bank_card_number_field))
                    .perform(scrollTo(), replaceText("411111111111111"), closeSoftKeyboard());
            onView(withId(R.id.form_primary_action)).perform(scrollTo(), click());
            screen.onActivity(activity -> {
                EditText number = activity.findViewById(R.id.bank_card_number_field);
                assertEquals("Card number must be exactly 16 digits", number.getError().toString());
            });
        }
    }

    @Test
    public void changePinRecreationRequiresCurrentPinAgain() {
        Intent intent = new Intent(wallet, LockActivity.class).putExtra(LockActivity.EXTRA_MODE, LockActivity.MODE_CHANGE);
        try (ActivityScenario<LockActivity> screen = ActivityScenario.launch(intent)) {
            onView(withId(R.id.lock_keypad_digit_1)).perform(click());
            onView(withId(R.id.lock_keypad_digit_2)).perform(click());
            onView(withId(R.id.lock_keypad_digit_3)).perform(click());
            onView(withId(R.id.lock_keypad_digit_4)).perform(click());
            for (int attempt = 0; attempt < 100; attempt++) {
                try {
                    onView(withText(R.string.lock_step_new_pin)).check(matches(isDisplayed()));
                    break;
                } catch (AssertionError | androidx.test.espresso.NoMatchingViewException waiting) {
                    SystemClock.sleep(50);
                }
            }
            onView(withText(R.string.lock_step_new_pin)).check(matches(isDisplayed()));
            screen.recreate();
            onView(withText(R.string.lock_step_old_pin)).check(matches(isDisplayed()));
        }
    }
}
