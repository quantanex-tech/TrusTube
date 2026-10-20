// SPDX-License-Identifier: GPL-3.0-or-later
package dev.trustedvideos.proof;

import android.content.Context;
import android.os.Looper;
import dev.trustedvideos.proof.core.VideoEntry;
import dev.trustedvideos.proof.source.SourceRepository;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk = 28)
public class IncrementalLoadingTest {
    private static final String A = "UCaaaaaaaaaaaaaaaaaaaaaa", B = "UCbbbbbbbbbbbbbbbbbbbbbb";
    @Test public void firstPageIsVisibleAndPersistedWhileAnotherChannelIsStillLoading() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences("proof", 0).edit().clear().putStringSet("channels", Set.of(A, B)).commit();
        CountDownLatch secondStarted = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        ProofState state = new ProofState(app, (id, more) -> {
            assertFalse("A refresh must not prefetch second pages", more);
            int n = calls.incrementAndGet();
            if (n == 2) { secondStarted.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); }
            return new SourceRepository.ChannelBatch(id, id, "", List.of(new VideoEntry(
                    n == 1 ? "abcdefghijk" : "lmnopqrstuv", id, id, "Page " + n, "", 60, n)), true);
        });
        try {
            state.refreshAllowedIfNeeded();
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue(state.loadingMore());
            assertEquals(1, state.allowedEntries().size());
            ProofState restored = new ProofState(app);
            assertEquals("abcdefghijk", restored.allowedEntries().get(0).id);
            restored.worker.shutdownNow(); restored.credentials.shutdownNow();
            // Revocation during the slow response must reject that page and not reapprove it.
            state.blockChannel(A); state.blockChannel(B);
            release.countDown(); state.worker.shutdown();
            assertTrue(state.worker.awaitTermination(5, TimeUnit.SECONDS));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            state.restoreChannel(A); state.restoreChannel(B);
            assertEquals(1, state.allowedEntries().size());
            assertFalse(state.loadingMore()); assertEquals(2, calls.get());
        } finally { release.countDown(); state.worker.shutdownNow(); state.credentials.shutdownNow(); }
    }
    @Test public void cursorReadsDoNotWaitBehindSlowExtractionLock() throws Exception {
        SourceRepository source = new SourceRepository();
        CountDownLatch holding = new CountDownLatch(1), release = new CountDownLatch(1);
        java.util.concurrent.ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            threads.submit(() -> { synchronized (source) {
                holding.countDown(); try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }});
            assertTrue(holding.await(5, TimeUnit.SECONDS));
            assertTrue(threads.submit(() -> source.hasMore(A) && !source.hasCursor(A)).get(1, TimeUnit.SECONDS));
        } finally { release.countDown(); threads.shutdownNow(); }
    }
}
