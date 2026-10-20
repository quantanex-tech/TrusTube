// SPDX-License-Identifier: GPL-3.0-or-later
package dev.trustedvideos.proof;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk = 28) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class LazyImagesTest {
    @Test public void scrollingLoadsOnlyVisibleArtworkAndReusesCacheWhenReturning() throws Exception {
        Set<String> fetched = ConcurrentHashMap.newKeySet();
        ImageCache cache = new ImageCache(directory(), url -> {
            fetched.add(url); return Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888);
        });
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            ScrollView scroll = new ScrollView(activity);
            LinearLayout column = new LinearLayout(activity); column.setOrientation(LinearLayout.VERTICAL);
            scroll.addView(column); activity.setContentView(scroll);
            List<ImageView> views = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                ImageView image = new ImageView(activity);
                image.setImageResource(R.drawable.ic_video_placeholder);
                column.addView(image, new LinearLayout.LayoutParams(300, 200));
                LazyImages.bind(activity, image, url(i), cache); views.add(image);
            }
            View root = activity.getWindow().getDecorView();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Set<String> expected = visible(views);
            assertFalse(expected.isEmpty()); assertTrue(expected.size() < 5);
            draw(root); await(() -> fetched.size() == expected.size());
            assertEquals(expected, fetched);
            scroll.scrollTo(0, 7800); draw(root);
            Set<String> union = new HashSet<>(expected); union.addAll(visible(views));
            await(() -> fetched.size() == union.size()); assertEquals(union, fetched);
            scroll.scrollTo(0, 0); draw(root); Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals("Returning to visible artwork must reuse the cache", union, fetched);
            assertTrue("Offscreen artwork must not be queued", fetched.size() < 10);
        } finally { cache.close(); }
    }
    @Test public void queuedRequestsAreCancelledAndOneSubscriberCannotCancelAnother() throws Exception {
        CountDownLatch started = new CountDownLatch(3), release = new CountDownLatch(1);
        AtomicInteger cancelledFetches = new AtomicInteger(), callbacks = new AtomicInteger();
        ImageCache cache = new ImageCache(directory(), url -> {
            if (url.equals(url(9))) cancelledFetches.incrementAndGet();
            started.countDown(); release.await(5, TimeUnit.SECONDS);
            return Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888);
        });
        try {
            ImageCache.Subscription one = cache.load(url(0), bitmap -> fail("Cancelled subscriber called"));
            cache.load(url(0), bitmap -> callbacks.incrementAndGet());
            one.cancel();
            cache.load(url(1), bitmap -> callbacks.incrementAndGet());
            cache.load(url(2), bitmap -> callbacks.incrementAndGet());
            assertTrue(started.await(5, TimeUnit.SECONDS));
            ImageCache.Subscription queued = cache.load(url(9), bitmap -> fail("Offscreen queue called"));
            queued.cancel(); release.countDown(); await(() -> callbacks.get() == 3);
            assertEquals(0, cancelledFetches.get());
        } finally { release.countDown(); cache.close(); }
    }
    @Test public void pausedWindowsDoNotFetchAndLateResultsCannotReplaceReboundArtwork() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1), releaseFirst = new CountDownLatch(1), firstFinished = new CountDownLatch(1);
        AtomicInteger fetches = new AtomicInteger();
        ImageCache cache = new ImageCache(directory(), address -> {
            fetches.incrementAndGet();
            if (address.equals(url(0))) {
                firstStarted.countDown();
                // Simulate a source that delivers a late response even after cancellation.
                while (releaseFirst.getCount() > 0) {
                    try { releaseFirst.await(5, TimeUnit.SECONDS); }
                    catch (InterruptedException ignored) { /* Deliberately late fixture. */ }
                }
                firstFinished.countDown();
            }
            Bitmap bitmap = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(address.equals(url(0)) ? Color.RED : Color.BLUE); return bitmap;
        });
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get(); FrameLayout content = new FrameLayout(activity);
            ImageView image = new ImageView(activity); content.addView(image, new FrameLayout.LayoutParams(100, 100));
            activity.setContentView(content); View root = activity.getWindow().getDecorView();
            root.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY)); root.layout(0, 0, 300, 400);
            LazyImages.bind(activity, image, url(0), cache);
            LazyImages.setActive(activity, false); draw(root); assertEquals(0, fetches.get());
            LazyImages.setActive(activity, true); draw(root); assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
            LazyImages.bind(activity, image, url(1), cache); draw(root);
            await(() -> image.getDrawable() instanceof BitmapDrawable
                    && ((BitmapDrawable) image.getDrawable()).getBitmap().getPixel(0, 0) == Color.BLUE);
            releaseFirst.countDown(); assertTrue(firstFinished.await(5, TimeUnit.SECONDS));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(Color.BLUE, ((BitmapDrawable) image.getDrawable()).getBitmap().getPixel(0, 0));
            LazyImages.setActive(activity, false); assertNull(image.getDrawable());
        } finally { releaseFirst.countDown(); cache.close(); }
    }
    @Test public void separateDialogWindowObservesHorizontalScrollingAndHiddenArtwork() throws Exception {
        Set<String> fetched = ConcurrentHashMap.newKeySet();
        ImageCache cache = new ImageCache(directory(), address -> {
            fetched.add(address); return Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888);
        });
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            HorizontalScrollView scroll = new HorizontalScrollView(activity);
            LinearLayout rail = new LinearLayout(activity); scroll.addView(rail);
            List<ImageView> images = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                ImageView image = new ImageView(activity); image.setImageResource(R.drawable.ic_video_placeholder);
                rail.addView(image, new LinearLayout.LayoutParams(200, 100));
                LazyImages.bind(activity, image, url(i), cache); images.add(image);
            }
            AlertDialog dialog = new AlertDialog.Builder(activity).setView(scroll).create(); dialog.show();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            View root = dialog.getWindow().getDecorView(); Set<String> expected = visible(images);
            assertFalse(expected.isEmpty()); assertTrue(expected.size() < 5);
            draw(root); await(() -> fetched.containsAll(expected)); assertEquals(expected, fetched);
            scroll.scrollTo(3800, 0);
            // Use the real window scroll observer without an artificial global-layout event.
            org.robolectric.util.ReflectionHelpers.callInstanceMethod(root.getViewTreeObserver(), "dispatchOnScrollChanged");
            root.getViewTreeObserver().dispatchOnPreDraw();
            Set<String> union = new HashSet<>(expected); union.addAll(visible(images));
            await(() -> fetched.containsAll(union)); assertEquals(union, fetched);
            rail.setVisibility(View.INVISIBLE); draw(root);
            for (ImageView image : images) assertFalse(image.getDrawable() instanceof BitmapDrawable);
            dialog.dismiss();
        } finally { cache.close(); }
    }
    private static String url(int i) { return "https://i.ytimg.com/vi/fixture" + i + "/default.jpg"; }
    private static File directory() {
        return new File(RuntimeEnvironment.getApplication().getCacheDir(), "lazy-" + System.nanoTime());
    }
    private static Set<String> visible(List<ImageView> views) {
        Set<String> urls = new HashSet<>();
        for (ImageView view : views) if (view.getGlobalVisibleRect(new Rect())) urls.add((String) view.getTag());
        return urls;
    }
    private static void draw(View root) {
        root.getViewTreeObserver().dispatchOnGlobalLayout();
        org.robolectric.util.ReflectionHelpers.callInstanceMethod(root.getViewTreeObserver(), "dispatchOnScrollChanged");
        root.getViewTreeObserver().dispatchOnPreDraw();
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10);
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle(); assertTrue(condition.getAsBoolean());
    }
}
