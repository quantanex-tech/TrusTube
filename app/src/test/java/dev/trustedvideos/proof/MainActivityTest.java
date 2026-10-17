package dev.trustedvideos.proof;

import android.app.AlertDialog;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Set;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.util.ReflectionHelpers;
import dev.trustedvideos.proof.core.VideoEntry;
import dev.trustedvideos.proof.source.SourceRepository;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 23)
@LooperMode(LooperMode.Mode.PAUSED)
public class MainActivityTest {
    private static final String CHANNEL = "UC513PdAP2-jWkJunTh5kXRw";
    private ProofState fixture() {
        ProofApplication app = (ProofApplication) RuntimeEnvironment.getApplication();
        app.getSharedPreferences("proof", Context.MODE_PRIVATE).edit().clear()
                .putStringSet("channels", Set.of(CHANNEL))
                .putString("channelNames", "{\"" + CHANNEL + "\":\"Test channel\"}")
                .putString("catalog", "[{\"id\":\"abcdefghijk\",\"channel\":\"" + CHANNEL
                        + "\",\"channelName\":\"Test channel\",\"title\":\"Build a tiny rocket\",\"thumbnail\":\"\",\"duration\":120},"
                        + "{\"id\":\"lmnopqrstuv\",\"channel\":\"" + CHANNEL
                        + "\",\"channelName\":\"Test channel\",\"title\":\"Explore a clever machine\",\"thumbnail\":\"\",\"duration\":180},"
                        + "{\"id\":\"wxyzabcdefg\",\"channel\":\"UCaaaaaaaaaaaaaaaaaaaaaa\","
                        + "\"channelName\":\"Unapproved channel\",\"title\":\"Forbidden fixture\",\"thumbnail\":\"\"}]").commit();
        ProofState state = new ProofState(app); state.setPin("582901");
        ReflectionHelpers.setField(app, "state", state);
        return state;
    }
    @Test public void blockedAndUnknownVideosDisappearAndStaleCardsCannotStartPlayback() {
        ProofState state = fixture();
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();
            View root = activity.getWindow().getDecorView();
            assertNull(findText(root, "Forbidden fixture"));
            TextView title = findText(root, "Build a tiny rocket"); assertNotNull(title);
            View staleCard = (View) title.getParent();
            state.blockVideo("abcdefghijk");
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNull(findText(root, "Build a tiny rocket"));
            staleCard.performClick();
            assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
            assertNotNull(findText(root, "Explore a clever machine"));
        }
        state.worker.shutdownNow(); state.credentials.shutdownNow();
    }
    @Test public void parentAuthenticationCompletionIsCancelledWhenActivityLeavesForeground() throws Exception {
        ProofState state = fixture(); AtomicBoolean action = new AtomicBoolean();
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            Ui.parent(controller.get(), state, () -> action.set(true));
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            EditText field = findEdit(dialog.getWindow().getDecorView()); assertNotNull(field);
            field.setText("582901");
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            controller.pause();
            state.credentials.shutdown();
            assertTrue(state.credentials.awaitTermination(5, TimeUnit.SECONDS));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertFalse(action.get());
            assertFalse(dialog.isShowing());
        }
        state.worker.shutdownNow();
    }
    @Test @Config(sdk = 28, qualifiers = "w360dp-h640dp-xxhdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void approvedBrowserRendersWithKeyboardReachableParentControls() throws Exception {
        ProofState state = fixture();
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();
            View root = activity.getWindow().getDecorView();
            int width = 1080, height = 1920;
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, width, height);
            assertTrue(root.findViewWithTag(MainActivity.Tab.PARENT).isFocusable());
            assertNotNull(findText(root, "Build a tiny rocket"));
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            root.draw(new Canvas(bitmap));
            File output = new File("../out/proof-browser-preview.png"); output.getParentFile().mkdirs();
            try (FileOutputStream stream = new FileOutputStream(output)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); }
        }
        state.worker.shutdownNow(); state.credentials.shutdownNow();
    }
    @Test public void favoritesPersistInAdditionOrderAndRemainFilteredAfterBlocking() {
        ProofState state = fixture();
        state.toggleFavorite("abcdefghijk"); state.toggleFavorite("lmnopqrstuv");
        assertEquals("lmnopqrstuv", state.favoriteEntries().get(0).id);
        state.blockVideo("lmnopqrstuv");
        ProofState restored = new ProofState(RuntimeEnvironment.getApplication());
        assertTrue(restored.isFavorite("lmnopqrstuv"));
        assertEquals(1, restored.favoriteEntries().size());
        assertEquals("abcdefghijk", restored.favoriteEntries().get(0).id);
        restored.restoreVideo("lmnopqrstuv");
        assertEquals("lmnopqrstuv", restored.favoriteEntries().get(0).id);
        restored.toggleFavorite("lmnopqrstuv");
        assertFalse(restored.isFavorite("lmnopqrstuv"));
        state.worker.shutdownNow(); state.credentials.shutdownNow();
        restored.worker.shutdownNow(); restored.credentials.shutdownNow();
    }
    @Test public void childPaginationCannotReapproveRemovedChannelsOrAcceptStaleResponses() {
        ProofState state = fixture(); long revision = state.policy.revision();
        SourceRepository.ChannelBatch batch = new SourceRepository.ChannelBatch(CHANNEL, "Test channel", "",
                List.of(new VideoEntry("aaaaaaaaaaa", CHANNEL, "Test channel", "New upload", "", 60, 2000)), false);
        assertTrue(state.acceptAllowedBatch(batch, revision));
        assertEquals("aaaaaaaaaaa", state.allowedEntries().get(0).id);
        state.removeChannel(CHANNEL);
        assertFalse(state.acceptAllowedBatch(batch, revision));
        assertFalse(state.acceptAllowedBatch(batch, state.policy.revision()));
        assertFalse(state.policy.allowsChannel(CHANNEL));
        state.worker.shutdownNow(); state.credentials.shutdownNow();
    }
    @Test public void paginatedUploadsMergeAcrossChannelsAndPreserveDatesAfterRestart() {
        ProofState state = fixture(); String otherChannel = "UCbbbbbbbbbbbbbbbbbbbbbb";
        state.add(new SourceRepository.ChannelBatch(otherChannel, "Another channel", "", List.of(
                new VideoEntry("bbbbbbbbbbb", otherChannel, "Another channel", "Older", "", 60, 1000),
                new VideoEntry("ccccccccccc", otherChannel, "Another channel", "Newest", "", 60, 3000)), true));
        assertTrue(state.acceptAllowedBatch(new SourceRepository.ChannelBatch(CHANNEL, "Test channel", "", List.of(
                new VideoEntry("ddddddddddd", CHANNEL, "Test channel", "Middle", "", 60, 2000)), true), state.policy.revision()));
        assertEquals(List.of("ccccccccccc", "ddddddddddd", "bbbbbbbbbbb"),
                state.allowedEntries().subList(0, 3).stream().map(entry -> entry.id).collect(java.util.stream.Collectors.toList()));
        ProofState restored = new ProofState(RuntimeEnvironment.getApplication());
        assertEquals(3000, restored.allowedEntries().get(0).uploadedAtMs);
        assertEquals("ddddddddddd", restored.allowedEntries().get(1).id);
        state.worker.shutdownNow(); state.credentials.shutdownNow();
        restored.worker.shutdownNow(); restored.credentials.shutdownNow();
    }
    @Test public void blockingRequiresConfirmationAndPlayerReturnSelectsHome() {
        ProofState state = fixture();
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get(); View root = activity.getWindow().getDecorView();
            Ui.confirmBlock(activity, state, state.find("abcdefghijk"), false, null);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue(state.policy.allows("abcdefghijk", CHANNEL));
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue(state.policy.allows("abcdefghijk", CHANNEL));
            Ui.confirmBlock(activity, state, state.find("abcdefghijk"), false, null);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertFalse(state.policy.allows("abcdefghijk", CHANNEL));
            root.findViewWithTag(MainActivity.Tab.FAVORITES).performClick();
            activity.onActivityResult(1, Activity.RESULT_OK, new android.content.Intent().putExtra("returnHome", true));
            assertTrue(root.findViewWithTag(MainActivity.Tab.HOME).isSelected());
        }
        state.worker.shutdownNow(); state.credentials.shutdownNow();
    }
    @Test public void parentTabRelocksAndCancelsAuthenticationWhenAnotherTabIsSelected() throws Exception {
        ProofState state = fixture();
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            View root = controller.get().getWindow().getDecorView();
            root.findViewWithTag(MainActivity.Tab.PARENT).performClick();
            AlertDialog pin = ShadowAlertDialog.getLatestAlertDialog();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            findEdit(pin.getWindow().getDecorView()).setText("582901");
            pin.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            state.credentials.submit(() -> {}).get(5, TimeUnit.SECONDS);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNotNull(findText(root, "Add a trusted channel"));
            root.findViewWithTag(MainActivity.Tab.HOME).performClick();
            assertNull(findText(root, "Add a trusted channel"));
            root.findViewWithTag(MainActivity.Tab.PARENT).performClick();
            assertNull(findText(root, "Add a trusted channel"));
            assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing());
            root.findViewWithTag(MainActivity.Tab.HOME).performClick();
            assertFalse(ShadowAlertDialog.getLatestAlertDialog().isShowing());
        }
        state.worker.shutdownNow(); state.credentials.shutdownNow();
    }
    @Test public void favoritesAndSearchTabsDoNotExposeUnapprovedOrBlockedVideos() {
        ProofState state = fixture(); state.toggleFavorite("abcdefghijk");
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            View root = controller.get().getWindow().getDecorView();
            root.findViewWithTag(MainActivity.Tab.FAVORITES).performClick();
            assertNotNull(findText(root, "Build a tiny rocket"));
            assertNull(findText(root, "Explore a clever machine"));
            root.findViewWithTag(MainActivity.Tab.SEARCH).performClick();
            findEdit(root).setText("Forbidden");
            assertNull(findText(root, "Forbidden fixture"));
            findEdit(root).setText("rocket"); assertNotNull(findText(root, "Build a tiny rocket"));
            state.blockVideo("abcdefghijk"); Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNull(findText(root, "Build a tiny rocket"));
        }
        state.worker.shutdownNow(); state.credentials.shutdownNow();
    }
    @Test @Config(sdk = 28, qualifiers = "w740dp-h360dp-land-xxhdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void playerOverlayRendersSeparateRailAndTimelineAndFavoriteActionWorks() throws Exception {
        ProofState state = fixture(); VideoEntry entry = state.find("abcdefghijk");
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            PlayerControls controls = new PlayerControls(activity, state, new PlayerControls.Actions() {
                public void back() {} public void togglePlay() {} public void menu(View anchor) {}
                public void seek(long position) {} public void play(VideoEntry video) {} public void more() {}
                public void favorite() { state.toggleFavorite(entry.id); }
            });
            controls.setBackgroundColor(android.graphics.Color.BLACK);
            activity.setContentView(controls);
            controls.update(entry, 30000, 120000, false); controls.setVideos(state.allowedEntries());
            View root = activity.getWindow().getDecorView();
            root.measure(View.MeasureSpec.makeMeasureSpec(2220, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, 2220, 1080);
            assertNotNull(findText(root, "1:30"));
            View favorite = findDescription(root, "Add favorite"); assertNotNull(favorite);
            favorite.performClick(); assertTrue(state.isFavorite(entry.id));
            View timeline = findDescription(root, "Video position"); assertNotNull(timeline);
            View otherVideo = findDescription(root, "Explore a clever machine · Test channel");
            assertNotNull(otherVideo);
            android.graphics.Rect railBounds = new android.graphics.Rect(), timelineBounds = new android.graphics.Rect();
            otherVideo.getGlobalVisibleRect(railBounds); timeline.getGlobalVisibleRect(timelineBounds);
            assertTrue("Rail must not overlap seek controls", railBounds.bottom <= timelineBounds.top);
            Bitmap bitmap = Bitmap.createBitmap(2220, 1080, Bitmap.Config.ARGB_8888); root.draw(new Canvas(bitmap));
            File output = new File("../out/player-interface-preview.png"); output.getParentFile().mkdirs();
            try (FileOutputStream stream = new FileOutputStream(output)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); }
        }
        state.worker.shutdownNow(); state.credentials.shutdownNow();
    }
    private static View findDescription(View root, String description) {
        if (description.contentEquals(root.getContentDescription() == null ? "" : root.getContentDescription())) return root;
        if (root instanceof ViewGroup group)
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findDescription(group.getChildAt(i), description); if (found != null) return found;
            }
        return null;
    }
    private static TextView findText(View root, String value) {
        if (root instanceof TextView text && text.getText().toString().equals(value)) return text;
        if (root instanceof ViewGroup group)
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findText(group.getChildAt(i), value); if (found != null) return found;
            }
        return null;
    }
    private static EditText findEdit(View root) {
        if (root instanceof EditText edit) return edit;
        if (root instanceof ViewGroup group)
            for (int i = 0; i < group.getChildCount(); i++) {
                EditText found = findEdit(group.getChildAt(i)); if (found != null) return found;
            }
        return null;
    }
}
