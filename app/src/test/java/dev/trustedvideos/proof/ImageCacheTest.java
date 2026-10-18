// SPDX-License-Identifier: GPL-3.0-or-later
package dev.trustedvideos.proof;

import android.graphics.Bitmap;
import android.os.Looper;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk = 28) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ImageCacheTest {
    @Test public void repeatedViewsShareFetchAndNewCacheReadsDiskOffline() throws Exception {
        File directory = new File(RuntimeEnvironment.getApplication().getCacheDir(), "images-"+System.nanoTime());
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger fetches = new AtomicInteger(), callbacks = new AtomicInteger();
        String url = "https://i.ytimg.com/vi/fixture/default.jpg";
        ImageCache first = new ImageCache(directory, ignored -> {
            fetches.incrementAndGet(); entered.countDown(); release.await(3, TimeUnit.SECONDS);
            return Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888);
        });
        try {
            first.load(url, bitmap -> callbacks.incrementAndGet()); assertTrue(entered.await(3, TimeUnit.SECONDS));
            first.load(url, bitmap -> callbacks.incrementAndGet()); release.countDown(); waitFor(callbacks, 2);
            first.load(url, bitmap -> callbacks.incrementAndGet()); assertEquals(3, callbacks.get()); assertEquals(1, fetches.get());
        } finally { release.countDown(); first.close(); }
        ImageCache offline = new ImageCache(directory, ignored -> { throw new java.io.IOException("Offline"); });
        try { offline.load(url, bitmap -> callbacks.incrementAndGet()); waitFor(callbacks, 4); }
        finally { offline.close(); }
    }
    @Test public void unsupportedImageHostsNeverReachFetcher() {
        AtomicInteger fetches = new AtomicInteger();
        ImageCache cache = new ImageCache(RuntimeEnvironment.getApplication().getCacheDir(), ignored -> { fetches.incrementAndGet(); return null; });
        try {
            for (String url : new String[]{"http://i.ytimg.com/a", "https://evil.com/a", "https://i.ytimg.com.evil.com/a", "https://user@i.ytimg.com/a"})
                cache.load(url, bitmap -> fail("Invalid host loaded"));
            assertEquals(0, fetches.get());
        } finally { cache.close(); }
    }
    private static void waitFor(AtomicInteger value, int target) throws Exception {
        long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while (value.get()<target && System.nanoTime()<deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10);
        }
        assertEquals(target, value.get());
    }
}
