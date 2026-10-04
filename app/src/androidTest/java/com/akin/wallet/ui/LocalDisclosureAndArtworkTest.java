package com.akin.wallet.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.os.Parcelable;
import android.os.SystemClock;
import android.util.SparseArray;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.test.platform.app.InstrumentationRegistry;

import com.akin.wallet.AkinWallet;
import com.akin.wallet.R;
import com.akin.wallet.util.Ui;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class LocalDisclosureAndArtworkTest {
    @Test
    public void sensitiveInputDoesNotEnterSavedHierarchyState() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            LinearLayout root = new LinearLayout(context);
            EditText input = new EditText(context);
            input.setId(View.generateViewId());
            input.setText("synthetic-private-credential");
            root.addView(input);
            Ui.protectViewTree(root);
            SparseArray<Parcelable> saved = new SparseArray<>();
            root.saveHierarchyState(saved);
            assertTrue(saved.indexOfKey(input.getId()) < 0);
            assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS,
                    input.getImportantForAutofill());
        });
    }

    @Test
    public void largePublicLogoIsSampledAndReusedWithoutRecyclingVisibleBitmap() {
        AkinWallet wallet = (AkinWallet) InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getApplicationContext();
        AtomicReference<ImageView> image = new AtomicReference<>();
        AtomicReference<Bitmap> loaded = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            image.set(new ImageView(wallet));
            wallet.platformIcons().bind(image.get(), R.drawable.gcash);
        });
        for (int attempt = 0; attempt < 200 && loaded.get() == null; attempt++) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                if (image.get().getDrawable() instanceof BitmapDrawable) {
                    loaded.set(((BitmapDrawable) image.get().getDrawable()).getBitmap());
                }
            });
            if (loaded.get() == null) SystemClock.sleep(25);
        }
        Bitmap bitmap = loaded.get();
        assertNotNull("Logo decode timed out", bitmap);
        assertTrue(bitmap.getWidth() <= Ui.dp(wallet, 64) * 2);
        assertTrue(bitmap.getAllocationByteCount() < 1_000_000);
        android.os.Bundle measurement = new android.os.Bundle();
        measurement.putString("stream", "\nSampled GCash logo: " + bitmap.getWidth() + "x"
                + bitmap.getHeight() + ", " + bitmap.getAllocationByteCount() + " bytes\n");
        InstrumentationRegistry.getInstrumentation().sendStatus(0, measurement);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ImageView second = new ImageView(wallet);
            wallet.platformIcons().bind(second, R.drawable.gcash);
            assertSame(bitmap, ((BitmapDrawable) second.getDrawable()).getBitmap());
            wallet.platformIcons().trim();
            assertFalse(bitmap.isRecycled());
        });
    }
}
