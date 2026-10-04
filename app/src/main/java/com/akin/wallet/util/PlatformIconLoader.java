package com.akin.wallet.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import com.akin.wallet.R;

import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.Executors;

/**
 * Application-owned cache of public catalog artwork; never contains vault data.
 */
public final class PlatformIconLoader {
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final LruCache<Integer, Bitmap> cache = new LruCache<Integer, Bitmap>(2 * 1024 * 1024) {
        @Override
        protected int sizeOf(Integer key, Bitmap bitmap) {
            return bitmap.getAllocationByteCount();
        }
    };
    // At most the fixed catalog's resource count is admitted; simultaneous binds share one decode.
    private final Map<Integer, List<WeakReference<ImageView>>> pending = new HashMap<>();

    public PlatformIconLoader(Context context) {
        this.context = context.getApplicationContext();
    }

    public void bind(ImageView view, int resource) {
        view.setTag(R.id.platform_icon_request, resource);
        Bitmap bitmap = cache.get(resource);
        if (bitmap != null) {
            view.setImageBitmap(bitmap);
            return;
        }
        view.setImageResource(R.drawable.ic_social);
        List<WeakReference<ImageView>> targets = pending.get(resource);
        if (targets != null) {
            targets.removeIf(reference -> reference.get() == null || reference.get() == view);
            targets.add(new WeakReference<>(view));
            return;
        }
        targets = new ArrayList<>();
        targets.add(new WeakReference<>(view));
        pending.put(resource, targets);
        decoder.execute(() -> {
            Bitmap decoded = null;
            try {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeResource(context.getResources(), resource, options);
                int target = Ui.dp(context, 64);
                options.inSampleSize = 1;
                while (options.outWidth / options.inSampleSize > target * 2
                        || options.outHeight / options.inSampleSize > target * 2)
                    options.inSampleSize *= 2;
                options.inJustDecodeBounds = false;
                decoded = BitmapFactory.decodeResource(context.getResources(), resource, options);
                if (decoded != null) cache.put(resource, decoded);
            } catch (android.content.res.Resources.NotFoundException ignored) {
            }
            final Bitmap result = decoded;
            main.post(() -> {
                List<WeakReference<ImageView>> waiting = pending.remove(resource);
                if (waiting == null) return;
                for (WeakReference<ImageView> reference : waiting) {
                    ImageView targetView = reference.get();
                    if (targetView != null && Integer.valueOf(resource).equals(targetView.getTag(R.id.platform_icon_request))) {
                        if (result != null) targetView.setImageBitmap(result);
                        else targetView.setImageResource(R.drawable.ic_social);
                    }
                }
            });
        });
    }

    public void trim() {
        cache.evictAll();
    }
}
