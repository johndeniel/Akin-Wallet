package com.akin.wallet.marketing;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;

import com.akin.wallet.AkinWallet;
import com.akin.wallet.FixtureWallet;
import com.akin.wallet.R;
import com.akin.wallet.activity.DashboardActivity;
import com.akin.wallet.security.AppLockManager;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Captures only synthetic fixture views; never uses device screenshots or removes FLAG_SECURE. */
public final class MarketingCaptureTest {
    private static final int SWIPE_FRAMES = 18;
    private AkinWallet wallet;
    private File directory;
    private final JSONObject metadata = new JSONObject();

    private void worker(Runnable operation) throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        wallet.getVaultStore().execute(() -> {
            try { operation.run(); }
            catch (Throwable failure) { error.set(failure); }
            finally { completed.countDown(); }
        }, failure -> { error.set(failure); completed.countDown(); });
        assertTrue("Fixture operation timed out", completed.await(30, TimeUnit.SECONDS));
        if (error.get() != null) throw new AssertionError(error.get());
    }

    private void awaitDashboard(ActivityScenario<DashboardActivity> screen) {
        AtomicReference<AssertionError> failure = new AtomicReference<>();
        for (int attempt = 0; attempt < 200; attempt++) {
            screen.onActivity(activity -> {
                try {
                    assertEquals(View.VISIBLE, activity.findViewById(R.id.dashboard_content).getVisibility());
                    int[] listIds = {R.id.dashboard_government_id_carousel,
                            R.id.dashboard_bank_card_carousel, R.id.dashboard_social_account_list};
                    for (int index = 0; index < listIds.length; index++) {
                        RecyclerView rows = activity.findViewById(listIds[index]);
                        assertEquals(index == 2 ? 4 : 2, rows.getAdapter().getItemCount());
                        assertTrue(rows.getHeight() > 0);
                        assertNotNull(rows.getLayoutManager().findViewByPosition(0));
                        if (index == 2) {
                            for (int child = 0; child < rows.getChildCount(); child++) {
                                ImageView icon = rows.getChildAt(child).findViewById(R.id.social_account_row_icon);
                                assertTrue("Wait for the real local platform artwork", icon.getDrawable() instanceof BitmapDrawable);
                            }
                        }
                    }
                    assertEquals(View.GONE, activity.findViewById(R.id.dashboard_social_account_empty_state).getVisibility());
                    failure.set(null);
                } catch (AssertionError error) { failure.set(error); }
            });
            if (failure.get() == null) return;
            SystemClock.sleep(25);
        }
        throw failure.get();
    }

    private void capture(String name, View view) {
        assertTrue(wallet instanceof FixtureWallet);
        assertTrue("Capture view is not laid out", view.getWidth() > 0 && view.getHeight() > 0);
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        try (FileOutputStream stream = new FileOutputStream(new File(directory, name + ".png"))) {
            view.draw(new Canvas(bitmap));
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
        } catch (java.io.IOException failure) { throw new AssertionError(failure); }
        finally { bitmap.recycle(); }
    }

    private JSONObject bounds(View root, View view) {
        int[] rootLocation = new int[2];
        int[] location = new int[2];
        root.getLocationInWindow(rootLocation);
        view.getLocationInWindow(location);
        try {
            return new JSONObject().put("x", location[0] - rootLocation[0])
                    .put("y", location[1] - rootLocation[1])
                    .put("width", view.getWidth()).put("height", view.getHeight());
        } catch (org.json.JSONException impossible) { throw new AssertionError(impossible); }
    }

    @Test
    public void captureFictionalDashboardAndActualCarouselMotion() throws Exception {
        org.junit.Assume.assumeTrue("Marketing capture is explicitly opt-in",
                "true".equals(InstrumentationRegistry.getArguments().getString("marketingCapture")));
        wallet = (AkinWallet) InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        assertTrue("Refusing capture outside invocation-isolated fixture", wallet instanceof FixtureWallet);
        CountDownLatch authenticated = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        AppLockManager.execute(() -> {
            try { AppLockManager.setPin(wallet, "1234"); }
            catch (Throwable failure) { error.set(failure); }
            finally { authenticated.countDown(); }
        });
        assertTrue(authenticated.await(30, TimeUnit.SECONDS));
        if (error.get() != null) throw new AssertionError(error.get());
        InstrumentationRegistry.getInstrumentation().runOnMainSync(wallet::onUnlocked);
        worker(() -> {
            MarketingDemoSeeder.reseed(wallet);
            MarketingDemoSeeder.reseed(wallet); // Repeat proves deterministic counts/no duplicate rows.
        });
        directory = new File(wallet.getCacheDir(), "marketing-demo");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        metadata.put("fictional_demo_only", true).put("production_data_accessed", false)
                .put("production_validation_modified", false).put("secure_window_flag_preserved", true)
                .put("source", "Real DashboardActivity and production adapters, invocation-isolated encrypted SQLite")
                .put("profile_name", MarketingDemoSeeder.NAME).put("profile_is_fictional", true)
                .put("device_model", Build.MODEL).put("device_manufacturer", Build.MANUFACTURER)
                .put("android_api", Build.VERSION.SDK_INT).put("android_version", Build.VERSION.RELEASE)
                .put("local_social_artwork_loaded", true)
                .put("government_id_count", 2).put("bank_card_count", 2).put("social_account_count", 4)
                .put("seeded_twice_without_duplicates", true).put("schema_version", 1)
                .put("card_numbers", new JSONArray().put("DEMO-CARD-4821").put("DEMO-CARD-7314"))
                .put("government_identifiers", new JSONArray().put("DEMO-PH-ID-001").put("DEMO-DL-002"))
                .put("social_username", "juan.demo@example.invalid")
                .put("secrets", "All card CVVs/PINs/expiry and social passwords/PINs are empty")
                .put("swipe_frame_count", SWIPE_FRAMES).put("swipe_playback_fps", 30)
                .put("swipe_method", "Production RecyclerView.scrollBy at deterministic eased offsets; actual View.draw frames");

        try (ActivityScenario<DashboardActivity> screen = ActivityScenario.launch(DashboardActivity.class)) {
            awaitDashboard(screen);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            screen.onActivity(activity -> {
                assertTrue((activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
                View root = activity.findViewById(android.R.id.content);
                RecyclerView ids = activity.findViewById(R.id.dashboard_government_id_carousel);
                RecyclerView cards = activity.findViewById(R.id.dashboard_bank_card_carousel);
                capture("dashboard", root);
                capture("government-id-national", ids.getLayoutManager().findViewByPosition(0));
                capture("bank-card-demo", cards.getLayoutManager().findViewByPosition(0));
                capture("social-accounts", activity.findViewById(R.id.dashboard_social_account_card));
                try {
                    metadata.put("capture", bounds(root, root))
                            .put("government_carousel", bounds(root, ids)).put("bank_carousel", bounds(root, cards))
                            .put("social_container", bounds(root, activity.findViewById(R.id.dashboard_social_account_card)))
                            .put("density", root.getResources().getDisplayMetrics().density)
                            .put("font_scale", root.getResources().getConfiguration().fontScale);
                } catch (org.json.JSONException impossible) { throw new AssertionError(impossible); }
            });
            final int[] travel = {0};
            screen.onActivity(activity -> {
                RecyclerView cards = activity.findViewById(R.id.dashboard_bank_card_carousel);
                View second = cards.getLayoutManager().findViewByPosition(1);
                assertNotNull("Second card must be attached before swipe", second);
                travel[0] = Math.max(0, cards.getLayoutManager().getDecoratedRight(second)
                        - cards.getWidth() + cards.getPaddingRight());
                assertTrue("Two cards need a real horizontal swipe", travel[0] > 0);
            });
            int previous = 0;
            for (int frame = 0; frame < SWIPE_FRAMES; frame++) {
                double progress = frame / (double) (SWIPE_FRAMES - 1);
                double eased = progress * progress * (3 - 2 * progress);
                int offset = (int) Math.round(travel[0] * eased);
                final int delta = offset - previous;
                final String name = String.format(Locale.ROOT, "swipe-%03d", frame);
                screen.onActivity(activity -> {
                    RecyclerView cards = activity.findViewById(R.id.dashboard_bank_card_carousel);
                    cards.scrollBy(delta, 0);
                    capture(name, activity.findViewById(android.R.id.content));
                });
                previous = offset;
            }
            screen.onActivity(activity -> {
                RecyclerView cards = activity.findViewById(R.id.dashboard_bank_card_carousel);
                assertFalse("Swipe must end on the last card", cards.canScrollHorizontally(1));
                capture("dashboard-bank-second", activity.findViewById(android.R.id.content));
                capture("bank-card-sample", cards.getLayoutManager().findViewByPosition(1));
                RecyclerView ids = activity.findViewById(R.id.dashboard_government_id_carousel);
                ids.scrollBy(ids.getWidth(), 0);
                capture("government-id-license", ids.getLayoutManager().findViewByPosition(1));
                capture("dashboard-second-cards", activity.findViewById(android.R.id.content));
            });
        }
        try (FileOutputStream stream = new FileOutputStream(new File(directory, "capture-metadata.json"))) {
            stream.write(metadata.toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }
}
