package com.akin.wallet;

import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.os.Looper;

import androidx.test.runner.AndroidJUnitRunner;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Isolate every instrumentation invocation from installed debug/release vaults.
 */
public final class FixtureTestRunner extends AndroidJUnitRunner {
    @Override
    public Application newApplication(ClassLoader loader, String name, Context context)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        return super.newApplication(loader, FixtureWallet.class.getName(), context);
    }

    @Override
    public void finish(int resultCode, Bundle results) {
        Application application = (Application) getTargetContext().getApplicationContext();
        if (application instanceof FixtureWallet) {
            FixtureWallet wallet = (FixtureWallet) application;
            if (Looper.myLooper() == Looper.getMainLooper()) wallet.lockAndClear();
            else runOnMainSync(wallet::lockAndClear);
            CountDownLatch closed = new CountDownLatch(1);
            wallet.getVaultStore().execute(closed::countDown, error -> closed.countDown());
            try {
                if (closed.await(5, TimeUnit.SECONDS)) wallet.eraseFixtures();
                else results.putString("fixture_cleanup", "Timed out; isolated files preserved");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        super.finish(resultCode, results);
    }
}
