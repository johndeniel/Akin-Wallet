package com.akin.wallet.activity;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.view.ViewCompat;
import androidx.core.widget.NestedScrollView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;

import com.akin.wallet.AkinWallet;
import com.akin.wallet.FixtureWallet;
import com.akin.wallet.R;
import com.akin.wallet.security.AppLockManager;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class PolicyScreenTest {
    @Test
    public void allNoticesRenderReadableSectionsAndReachTheirLastSection() throws Exception {
        AkinWallet wallet = (AkinWallet) InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getApplicationContext();
        assertTrue(wallet instanceof FixtureWallet);
        CountDownLatch authenticated = new CountDownLatch(1);
        AppLockManager.execute(() -> {
            AppLockManager.setPin(wallet, "1234");
            authenticated.countDown();
        });
        assertTrue(authenticated.await(10, TimeUnit.SECONDS));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(wallet::onUnlocked);

        for (String type : new String[]{PolicyActivity.TYPE_PRIVACY, PolicyActivity.TYPE_TERMS,
                PolicyActivity.TYPE_ABOUT}) {
            Intent intent = new Intent(wallet, PolicyActivity.class).putExtra(PolicyActivity.EXTRA_TYPE, type);
            try (ActivityScenario<PolicyActivity> screen = ActivityScenario.launch(intent)) {
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                screen.onActivity(activity -> {
                    LinearLayout sections = activity.findViewById(R.id.policy_sections);
                    assertTrue(type + " missing sections", sections.getChildCount() >= 20);
                    StringBuilder rendered = new StringBuilder();
                    for (int index = 0; index < sections.getChildCount(); index++) {
                        TextView text = (TextView) sections.getChildAt(index);
                        assertFalse(text.getText().toString().trim().isEmpty());
                        assertTrue(type + " text has no height", text.getHeight() > 0);
                        if (index % 2 == 0) assertTrue(ViewCompat.isAccessibilityHeading(text));
                        rendered.append(text.getText()).append('\n');
                    }
                    assertFalse("Unexpanded version", rendered.toString().contains("{version}"));
                    assertFalse("Escaped newlines visible", rendered.toString().contains("\\n"));
                    assertTrue(rendered.toString().contains("johndenieldelapena97@gmail.com"));
                    View root = activity.findViewById(android.R.id.content);
                    savePreview(wallet, root, type);
                    NestedScrollView scroll = (NestedScrollView) sections.getParent().getParent();
                    scroll.fullScroll(View.FOCUS_DOWN);
                });
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                screen.onActivity(activity -> {
                    LinearLayout sections = activity.findViewById(R.id.policy_sections);
                    NestedScrollView scroll = (NestedScrollView) sections.getParent().getParent();
                    // Smooth scrolling may still be settling; verify the real bottom directly.
                    scroll.scrollTo(0, scroll.getChildAt(0).getHeight());
                    assertTrue("Notice is not scrollable", scroll.getScrollY() > 0);
                    assertFalse("Last section inaccessible", scroll.canScrollVertically(1));
                });
            }
        }
    }

    private void savePreview(AkinWallet wallet, View root, String name) {
        File directory = new File(wallet.getCacheDir(), "social-policy-previews");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        Bitmap bitmap = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".png"))) {
            root.draw(new Canvas(bitmap));
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        } finally {
            bitmap.recycle();
        }
    }
}
