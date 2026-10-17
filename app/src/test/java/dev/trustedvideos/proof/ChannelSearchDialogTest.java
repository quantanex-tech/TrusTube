package dev.trustedvideos.proof;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import dev.trustedvideos.proof.source.SourceRepository.ChannelResult;
import dev.trustedvideos.proof.source.SourceRepository.SearchBatch;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.android.controller.ActivityController;
import static org.junit.Assert.*;

@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(sdk = 23)
@LooperMode(LooperMode.Mode.PAUSED)
public class ChannelSearchDialogTest {
    private static final ChannelResult CHANNEL = new ChannelResult("UC513PdAP2-jWkJunTh5kXRw", "Checker fixture", "", "Channel description");
    private ProofState fixture() {
        Context app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences("proof", Context.MODE_PRIVATE).edit().clear().commit();
        return new ProofState(app);
    }
    private static View text(View root, String value) {
        if (root instanceof TextView view && value.contentEquals(view.getText())) return root;
        if (root instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            View found = text(group.getChildAt(i), value); if (found != null) return found;
        }
        return null;
    }
    private static EditText input(View root) {
        if (root instanceof EditText edit) return edit;
        if (root instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            EditText found = input(group.getChildAt(i)); if (found != null) return found;
        }
        return null;
    }
    private static void idle(ProofState state) throws Exception {
        state.worker.submit(() -> {}).get(5, TimeUnit.SECONDS);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    private static void close(ProofState state) { state.worker.shutdownNow(); state.credentials.shutdownNow(); }
    @Test public void searchingDoesNotApproveUntilParentExplicitlySelectsAdd() throws Exception {
        ProofState state = fixture(); AtomicReference<String> selected = new AtomicReference<>();
        try (ActivityController<Activity> owner = Robolectric.buildActivity(Activity.class).setup()) {
            AlertDialog dialog = new ChannelSearchDialog(owner.get(), state, () -> true,
                    (term, previous) -> { assertEquals("checker tobi", term); return new SearchBatch(List.of(CHANNEL)); }, selected::set).show();
            View root = dialog.getWindow().getDecorView(); input(root).setText("checker tobi");
            text(root, "Search channels").performClick(); idle(state);
            assertNotNull(text(root, CHANNEL.name)); assertNull(selected.get());
            assertTrue(state.policy.channels().isEmpty());
            View stale = text(root, "Add " + CHANNEL.name); stale.performClick();
            assertEquals(CHANNEL.url, selected.get()); assertFalse(dialog.isShowing());
            selected.set(null); stale.performClick(); assertNull(selected.get());
        } finally { close(state); }
    }
    @Test public void staleResponseCannotReappearAfterParentRelocks() throws Exception {
        ProofState state = fixture(); AtomicBoolean authorized = new AtomicBoolean(true);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (ActivityController<Activity> owner = Robolectric.buildActivity(Activity.class).setup()) {
            AlertDialog dialog = new ChannelSearchDialog(owner.get(), state, authorized::get, (term, previous) -> {
                entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); return new SearchBatch(List.of(CHANNEL));
            }, ignored -> fail("A locked parent session cannot approve a channel.")).show();
            View root = dialog.getWindow().getDecorView(); input(root).setText("checker tobi");
            text(root, "Search channels").performClick(); assertTrue(entered.await(5, TimeUnit.SECONDS));
            authorized.set(false); state.invalidateParentActions(); release.countDown(); idle(state);
            assertNull(text(root, CHANNEL.name)); assertTrue(state.policy.channels().isEmpty()); dialog.dismiss();
        } finally { release.countDown(); close(state); }
    }
    @Test public void editingQueryInvalidatesOldAddButtonsAndSearchCanRetryAfterError() throws Exception {
        ProofState state = fixture(); AtomicInteger requests = new AtomicInteger(); AtomicReference<String> selected = new AtomicReference<>();
        try (ActivityController<Activity> owner = Robolectric.buildActivity(Activity.class).setup()) {
            AlertDialog dialog = new ChannelSearchDialog(owner.get(), state, () -> true, (term, previous) -> {
                if (requests.getAndIncrement() == 0) throw new IOException("offline fixture");
                return new SearchBatch(List.of(CHANNEL));
            }, selected::set).show();
            View root = dialog.getWindow().getDecorView(); input(root).setText("checker tobi");
            text(root, "Search channels").performClick(); idle(state);
            assertNotNull(text(root, "Couldn’t search YouTube. Check your connection and try again."));
            text(root, "Search channels").performClick(); idle(state);
            View stale = text(root, "Add " + CHANNEL.name); assertNotNull(stale);
            input(root).setText("another channel"); stale.performClick();
            assertNull(selected.get()); assertNull(text(root, CHANNEL.name)); dialog.dismiss();
        } finally { close(state); }
    }
    @Test public void emptyResultsDoNotCreateApprovalAndOfferAnotherSearch() throws Exception {
        ProofState state = fixture();
        try (ActivityController<Activity> owner = Robolectric.buildActivity(Activity.class).setup()) {
            AlertDialog dialog = new ChannelSearchDialog(owner.get(), state, () -> true,
                    (term, previous) -> new SearchBatch(List.of()), ignored -> fail()).show();
            View root = dialog.getWindow().getDecorView(); input(root).setText("no fixture results");
            text(root, "Search channels").performClick(); idle(state);
            assertNotNull(text(root, "No channels found. Try another name or a channel URL."));
            assertTrue(state.policy.channels().isEmpty()); dialog.dismiss();
        } finally { close(state); }
    }
    @Test public void paginationDeduplicatesAndEarlierResultsRemainSelectable() throws Exception {
        ProofState state = fixture(); AtomicReference<String> selected = new AtomicReference<>();
        ChannelResult other = new ChannelResult("UCaaaaaaaaaaaaaaaaaaaaaa", "Second channel", "", "");
        var query = org.schabi.newpipe.extractor.ServiceList.YouTube.getSearchQHFactory().fromQuery("checker tobi");
        SearchBatch first = new SearchBatch(List.of(CHANNEL), query, new org.schabi.newpipe.extractor.Page("https://www.youtube.com/results?continuation=fixture"));
        try (ActivityController<Activity> owner = Robolectric.buildActivity(Activity.class).setup()) {
            AlertDialog dialog = new ChannelSearchDialog(owner.get(), state, () -> true,
                    (term, previous) -> previous == null ? first : new SearchBatch(List.of(CHANNEL, other)), selected::set).show();
            View root = dialog.getWindow().getDecorView(); input(root).setText("checker tobi");
            text(root, "Search channels").performClick(); idle(state);
            View earlier = text(root, "Add " + CHANNEL.name);
            text(root, "More channels").performClick(); idle(state);
            assertSame(earlier, text(root, "Add " + CHANNEL.name));
            assertNotNull(text(root, "Add " + other.name));
            earlier.performClick(); assertEquals(CHANNEL.url, selected.get());
        } finally { close(state); }
    }
}
