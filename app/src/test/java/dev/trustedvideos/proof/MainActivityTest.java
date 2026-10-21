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
        app.getSharedPreferences("watch-progress", Context.MODE_PRIVATE).edit().clear().commit();
        app.getSharedPreferences("proof", Context.MODE_PRIVATE).edit().clear()
                .putLong("datedCatalogRefresh", System.currentTimeMillis())
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
            root.findViewWithTag(MainActivity.Tab.LIBRARY).performClick();
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
            root.findViewWithTag(MainActivity.Tab.LIBRARY).performClick();
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
    @Test @Config(sdk = 28, qualifiers = "w360dp-h640dp-xxhdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void libraryKeepsFiveDestinationsAndSwitchesAccessibleFavoritesAndHistorySections() throws Exception {
        ProofState state = fixture();
        state.toggleFavorite("abcdefghijk");
        state.recordWatched("abcdefghijk"); state.recordWatched("lmnopqrstuv");
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            View root = controller.get().getWindow().getDecorView();
            View library = root.findViewWithTag(MainActivity.Tab.LIBRARY);
            ViewGroup navigation = (ViewGroup) library.getParent();
            assertEquals(5, navigation.getChildCount());
            assertEquals(5, MainActivity.Tab.values().length);
            for (MainActivity.Tab destination : MainActivity.Tab.values()) {
                View button = root.findViewWithTag(destination);
                assertNotNull(button); assertSame(navigation, button.getParent());
                assertTrue(button.isFocusable()); assertTrue(button.isClickable());
            }
            library.performClick();
            assertTrue(library.isSelected());
            TextView pageTitle = findText(root, "Library"); assertNotNull(pageTitle);
            assertNotSame("Library needs a page title as well as its navigation label", library, pageTitle.getParent());
            View favorites = root.findViewWithTag(MainActivity.LibrarySection.FAVORITES);
            View history = root.findViewWithTag(MainActivity.LibrarySection.HISTORY);
            assertTrue(favorites.isFocusable()); assertTrue(favorites.isClickable());
            assertTrue(history.isFocusable()); assertTrue(history.isClickable());
            assertTrue(favorites.isSelected()); assertFalse(history.isSelected());
            assertNotNull(findText(root, "Favorites")); assertNotNull(findText(root, "History"));
            assertNotNull(findText(root, "Build a tiny rocket"));
            assertNull(findText(root, "Explore a clever machine"));

            history.performClick();
            assertTrue(root.findViewWithTag(MainActivity.LibrarySection.HISTORY).isSelected());
            assertFalse(root.findViewWithTag(MainActivity.LibrarySection.FAVORITES).isSelected());
            assertTrue(library.isSelected());
            assertNotNull(findText(root, "Build a tiny rocket"));
            assertNotNull(findText(root, "Explore a clever machine"));
            List<String> visibleTitles = new java.util.ArrayList<>();
            collectVideoTitles(root, visibleTitles);
            assertEquals(List.of("Explore a clever machine", "Build a tiny rocket"), visibleTitles);
            assertNull(findText(root, "Forbidden fixture"));

            int width = 1080, height = 1920;
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, width, height);
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            root.draw(new Canvas(bitmap));
            File output = new File("../out/watch-history-preview.png"); output.getParentFile().mkdirs();
            try (FileOutputStream stream = new FileOutputStream(output)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); }

            root.findViewWithTag(MainActivity.LibrarySection.FAVORITES).performClick();
            assertTrue(root.findViewWithTag(MainActivity.LibrarySection.FAVORITES).isSelected());
            assertFalse(root.findViewWithTag(MainActivity.LibrarySection.HISTORY).isSelected());
            assertNotNull(findText(root, "Build a tiny rocket"));
            assertNull(findText(root, "Explore a clever machine"));
        }
        state.worker.shutdownNow(); state.credentials.shutdownNow();
    }
    @Test public void emptyHistoryExplainsWhereWatchedVideosWillAppear() {
        ProofState state = fixture(); state.toggleFavorite("abcdefghijk");
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            View root = controller.get().getWindow().getDecorView();
            root.findViewWithTag(MainActivity.Tab.LIBRARY).performClick();
            root.findViewWithTag(MainActivity.LibrarySection.HISTORY).performClick();
            assertTrue(root.findViewWithTag(MainActivity.LibrarySection.HISTORY).isSelected());
            assertNotNull(findText(root, "Videos you watch will appear here."));
            assertNull(findText(root, "Build a tiny rocket"));
            assertNull(findText(root, "Explore a clever machine"));
            root.findViewWithTag(MainActivity.LibrarySection.FAVORITES).performClick();
            assertNotNull(findText(root, "Build a tiny rocket"));
            assertNull(findText(root, "Videos you watch will appear here."));
        }
        state.worker.shutdownNow(); state.credentials.shutdownNow();
    }
    @Test public void blockedAndRemovedHistoryVideosDisappearAndStaleCardsCannotStartPlayback() {
        ProofState state = fixture();
        state.recordWatched("abcdefghijk"); state.recordWatched("lmnopqrstuv");
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get(); View root = activity.getWindow().getDecorView();
            root.findViewWithTag(MainActivity.Tab.LIBRARY).performClick();
            root.findViewWithTag(MainActivity.LibrarySection.HISTORY).performClick();
            View blockedCard = (View) findText(root, "Build a tiny rocket").getParent();
            state.blockVideo("abcdefghijk"); Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNull(findText(root, "Build a tiny rocket"));
            assertNotNull(findText(root, "Explore a clever machine"));
            blockedCard.performClick();
            assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
            View removedCard = (View) findText(root, "Explore a clever machine").getParent();
            state.removeChannel(CHANNEL); Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNull(findText(root, "Explore a clever machine"));
            assertNotNull(findText(root, "Videos you watch will appear here."));
            removedCard.performClick();
            assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
            assertTrue(root.findViewWithTag(MainActivity.LibrarySection.HISTORY).isSelected());
        }
        state.worker.shutdownNow(); state.credentials.shutdownNow();
    }
    @Test public void returningFromPlayerKeepsTheHistorySectionSelected() {
        ProofState state = fixture(); state.recordWatched("abcdefghijk");
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get(); View root = activity.getWindow().getDecorView();
            root.findViewWithTag(MainActivity.Tab.LIBRARY).performClick();
            root.findViewWithTag(MainActivity.LibrarySection.HISTORY).performClick();
            ((View) findText(root, "Build a tiny rocket").getParent()).performClick();
            android.content.Intent launched = Shadows.shadowOf(activity).getNextStartedActivity();
            assertNotNull(launched); assertEquals(PlayerActivity.class.getName(), launched.getComponent().getClassName());
            assertEquals("abcdefghijk", launched.getStringExtra("videoId"));
            controller.pause();
            activity.onActivityResult(1, Activity.RESULT_CANCELED, null);
            controller.resume(); Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue(root.findViewWithTag(MainActivity.Tab.LIBRARY).isSelected());
            assertTrue(root.findViewWithTag(MainActivity.LibrarySection.HISTORY).isSelected());
            assertFalse(root.findViewWithTag(MainActivity.LibrarySection.FAVORITES).isSelected());
            assertNotNull(findText(root, "Build a tiny rocket"));
        }
        state.worker.shutdownNow(); state.credentials.shutdownNow();
    }
    @Test public void openingPlayerDoesNotAddAVideoToHistoryBeforePlaybackStarts() {
        ProofState state = fixture();
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get(); View root = activity.getWindow().getDecorView();
            ((View) findText(root, "Build a tiny rocket").getParent()).performClick();
            android.content.Intent launched = Shadows.shadowOf(activity).getNextStartedActivity();
            assertNotNull(launched); assertEquals(PlayerActivity.class.getName(), launched.getComponent().getClassName());
            assertEquals("abcdefghijk", launched.getStringExtra("videoId"));
            assertTrue(state.historyEntries().isEmpty());
            root.findViewWithTag(MainActivity.Tab.LIBRARY).performClick();
            root.findViewWithTag(MainActivity.LibrarySection.HISTORY).performClick();
            assertNotNull(findText(root, "Videos you watch will appear here."));
        }
        state.worker.shutdownNow(); state.credentials.shutdownNow();
    }
    @Test public void clearingHistoryRequiresUnlockedParentsAndConfirmationAndRejectsStaleCompletion() throws Exception {
        ProofState state = fixture(); state.recordWatched("abcdefghijk");
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            View root = controller.get().getWindow().getDecorView();
            assertNull(findText(root, "Clear watch history"));
            root.findViewWithTag(MainActivity.Tab.LIBRARY).performClick();
            root.findViewWithTag(MainActivity.LibrarySection.HISTORY).performClick();
            assertNull(findText(root, "Clear watch history"));
            root.findViewWithTag(MainActivity.Tab.PARENT).performClick();
            assertNull(findText(root, "Clear watch history"));
            AlertDialog pin = ShadowAlertDialog.getLatestAlertDialog();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            findEdit(pin.getWindow().getDecorView()).setText("582901");
            pin.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            state.credentials.submit(() -> {}).get(5, TimeUnit.SECONDS);
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            TextView clear = findText(root, "Clear watch history"); assertNotNull(clear); clear.performClick();
            AlertDialog confirmation = ShadowAlertDialog.getLatestAlertDialog();
            confirmation.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(List.of("abcdefghijk"), state.historyEntries().stream()
                    .map(entry -> entry.id).collect(java.util.stream.Collectors.toList()));

            findText(root, "Clear watch history").performClick();
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue(state.historyEntries().isEmpty());
            ProofState restored = new ProofState(RuntimeEnvironment.getApplication());
            assertTrue(restored.historyEntries().isEmpty());
            restored.worker.shutdownNow(); restored.credentials.shutdownNow();

            findText(root, "Clear watch history").performClick();
            AlertDialog staleDialog = ShadowAlertDialog.getLatestAlertDialog();
            View staleConfirmation = staleDialog.getButton(AlertDialog.BUTTON_POSITIVE);
            root.findViewWithTag(MainActivity.Tab.HOME).performClick();
            assertFalse(staleDialog.isShowing());
            state.recordWatched("lmnopqrstuv");
            staleConfirmation.performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(List.of("lmnopqrstuv"), state.historyEntries().stream()
                    .map(entry -> entry.id).collect(java.util.stream.Collectors.toList()));
            root.findViewWithTag(MainActivity.Tab.LIBRARY).performClick();
            root.findViewWithTag(MainActivity.LibrarySection.HISTORY).performClick();
            assertNotNull(findText(root, "Explore a clever machine"));
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


    @Test public void playerControlsStayVisibleDuringMenusAndResumeTheirHideDelay() {
        ProofState state = fixture();
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            PlayerControls controls = new PlayerControls(controller.get(), state, new PlayerControls.Actions() {
                public void back() {} public void togglePlay() {} public void favorite() {} public void menu(View anchor) {}
                public void seek(long position) {} public void play(VideoEntry video) {} public void more() {}
            });
            controller.get().setContentView(controls); controller.visible();
            controller.windowFocusChanged(true);
            android.widget.FrameLayout overlay = ReflectionHelpers.getField(controls, "overlay");
            overlay.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS); overlay.clearFocus();
            controls.setPlaying(true); controls.holdMenu(true);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(8));
            assertTrue(ReflectionHelpers.getField(controls, "showing"));
            controls.holdMenu(false);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(6));
            assertFalse(ReflectionHelpers.getField(controls, "showing"));
        }
        state.worker.shutdownNow(); state.credentials.shutdownNow();
    }
    @Test public void expiredFullAppLocksBrowsingButParentCanStillManageTimers() throws Exception {
        ProofState state = fixture(); state.recordWatched("abcdefghijk");
        state.timers.today(dev.trustedvideos.proof.core.TimerSchedule.Type.APP, 0);
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            View root = controller.get().getWindow().getDecorView();
            assertNotNull(findText(root, "App time is up for today"));
            assertNull(findText(root, "Build a tiny rocket"));
            root.findViewWithTag(MainActivity.Tab.LIBRARY).performClick();
            assertTrue(root.findViewWithTag(MainActivity.Tab.LIBRARY).isSelected());
            assertNotNull(findText(root, "App time is up for today"));
            assertNull(root.findViewWithTag(MainActivity.LibrarySection.HISTORY));
            assertNull(findText(root, "Build a tiny rocket"));
            unlockParent(root, state);
            findText(root, "Manage timers").performClick();
            assertNotNull(findText(root, "Daily time limits"));
            root.findViewWithTag(MainActivity.Tab.HOME).performClick();
            assertNotNull(findText(root, "App time is up for today"));
        }
        state.worker.shutdownNow(); state.credentials.shutdownNow();
    }
    @Test public void watchExpiryAllowsBrowsingAndProofResetCannotResetDailyUsage() {
        ProofState state = fixture(); state.timers.today(dev.trustedvideos.proof.core.TimerSchedule.Type.WATCHING, 0);
        state.resetUsage(); assertTrue(state.expired()); assertFalse(state.appExpired());
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get(); View root = activity.getWindow().getDecorView();
            assertNotNull(findText(root, "Build a tiny rocket"));
            findDescription(root, "Build a tiny rocket · Test channel").performClick();
            assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
        }
        state.worker.shutdownNow(); state.credentials.shutdownNow();
    }
    @Test @Config(sdk = 28, qualifiers = "w400dp-h850dp-xxhdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void timerCrudTodayEditorAndBackgroundRelocking() throws Exception {
        ProofState state = fixture();
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get(); View root = activity.getWindow().getDecorView();
            unlockParent(root, state); findText(root, "Manage timers").performClick();
            findText(root, "+ Add timer").performClick(); findEdit(root).setText("School days");
            findText(root, "Weekdays").performClick();
            savePreview(root, "timer-weekly-preview.png", 1200, 2550);
            findText(root, "Save timer").performClick();
            assertEquals(1, state.timers.schedules().size());
            assertEquals("School days", state.timers.schedules().get(0).name);
            assertEquals(-1, state.timers.schedules().get(0).minutes(6));
            savePreview(root, "timer-management-preview.png", 1200, 2550);
            findText(root, "Today  ›").performClick(); findText(root, "Change today's limit").performClick();
            TimerPage page = ReflectionHelpers.getField(activity, "timers");
            com.google.android.material.bottomsheet.BottomSheetDialog sheet = ReflectionHelpers.getField(page, "sheet");
            View done = findText(sheet.getWindow().getDecorView(), "Done"); assertNotNull(done);
            savePreview(sheet.getWindow().getDecorView(), "timer-today-preview.png", 1200, 2550);
            long epoch = state.parentEpoch(); controller.pause();
            assertTrue(state.parentEpoch() > epoch); assertFalse(sheet.isShowing()); done.performClick();
            assertFalse(state.timers.hasToday(dev.trustedvideos.proof.core.TimerSchedule.Type.WATCHING));
            controller.resume(); assertNull(findText(root, "Manage timers"));
            assertNotNull(findText(root, "Unlock parent settings"));
        }
        state.worker.shutdownNow(); state.credentials.shutdownNow();
    }

    @Test public void durationSheetAppliesAllDaysAndTodaySelectorTargetsOnlyChosenType() throws Exception {
        ProofState state = fixture();
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get(); View root = activity.getWindow().getDecorView();
            unlockParent(root, state); findText(root, "Manage timers").performClick();
            findText(root, "+ Add timer").performClick(); findEdit(root).setText("Daily watching");
            findText(root, "1 h  ›").performClick();
            TimerPage page = ReflectionHelpers.getField(activity, "timers");
            com.google.android.material.bottomsheet.BottomSheetDialog sheet = ReflectionHelpers.getField(page, "sheet");
            View editor = sheet.getWindow().getDecorView();
            findText(editor, "+").performClick();
            assertNotNull(findText(editor, "1 h 5 min"));
            findText(editor, "−").performClick();
            assertNotNull(findText(editor, "1 h"));
            findText(editor, "−").performClick(); findText(editor, "−").performClick();
            findText(editor, "Apply to all days of the week").performClick(); findText(editor, "Done").performClick();
            findText(root, "Save timer").performClick();
            assertArrayEquals(new int[]{50,50,50,50,50,50,50}, state.timers.schedules().get(0).days());
            findText(root, "Today  ›").performClick(); findText(root, "Change today's limit").performClick();
            sheet = ReflectionHelpers.getField(page, "sheet"); editor = sheet.getWindow().getDecorView();
            findSpinner(editor).setSelection(1); Shadows.shadowOf(Looper.getMainLooper()).idle();
            findText(editor, "Daily limit").performClick();
            for (int i=0;i<12;i++) findText(editor, "−").performClick();
            findText(editor, "Done").performClick();
            assertEquals(0, state.timers.allowance(dev.trustedvideos.proof.core.TimerSchedule.Type.APP));
            assertEquals(50, state.timers.allowance(dev.trustedvideos.proof.core.TimerSchedule.Type.WATCHING));
            assertTrue(state.appExpired());
        }
        state.worker.shutdownNow(); state.credentials.shutdownNow();
    }
    private static android.widget.Spinner findSpinner(View root) {
        if (root instanceof android.widget.Spinner spinner) return spinner;
        if (root instanceof ViewGroup group) for (int i=0;i<group.getChildCount();i++) {
            android.widget.Spinner found = findSpinner(group.getChildAt(i)); if (found != null) return found;
        }
        return null;
    }
    @Test public void refreshWithMissingDatePreservesExistingUploadOrdering() {
        ProofState state = fixture();
        state.add(new SourceRepository.ChannelBatch(CHANNEL, "Test", "", List.of(
                new VideoEntry("abcdefghijk", CHANNEL, "Test", "Dated", "", 60, 123456)), false));
        state.add(new SourceRepository.ChannelBatch(CHANNEL, "Test", "", List.of(
                new VideoEntry("abcdefghijk", CHANNEL, "Test", "Refreshed", "", 60, 0)), false));
        assertEquals(123456, state.allowedEntries().get(0).uploadedAtMs);
        state.worker.shutdownNow(); state.credentials.shutdownNow();
    }
    @Test public void channelDocumentPickerRelocksParentsAndStaleImportConfirmationCannotApply() throws Exception {
        ProofState state = fixture();
        var incoming = List.of(new dev.trustedvideos.proof.core.ChannelTransfer.Channel("UCbbbbbbbbbbbbbbbbbbbbbb", "Another channel", false));
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get(); View root = activity.getWindow().getDecorView();
            unlockParent(root, state);
            findText(root, "Export subscribed channels").performClick();
            var started = Shadows.shadowOf(activity).getNextStartedActivityForResult();
            assertEquals(android.content.Intent.ACTION_CREATE_DOCUMENT, started.intent.getAction());
            assertEquals("application/json", started.intent.getType());
            controller.pause().resume();
            assertNotNull(findText(root, "Unlock parent settings"));
            assertNull(findText(root, "Import subscribed channels"));
            ReflectionHelpers.setField(activity, "pendingImport", incoming);
            root.findViewWithTag(MainActivity.Tab.HOME).performClick();
            unlockParent(root, state); findText(root, "Review channel import (1)").performClick();
            AlertDialog confirm = ShadowAlertDialog.getLatestAlertDialog();
            View stale = confirm.getButton(AlertDialog.BUTTON_POSITIVE);
            controller.pause(); stale.performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertFalse(state.policy.channels().contains("UCbbbbbbbbbbbbbbbbbbbbbb"));
            controller.resume();
            root.findViewWithTag(MainActivity.Tab.HOME).performClick();
            unlockParent(root, state); findText(root, "Review channel import (1)").performClick();
            // Avoid real network refresh in this deterministic import check.
            state.timers.today(dev.trustedvideos.proof.core.TimerSchedule.Type.APP, 0);
            state.toggleFavorite("lmnopqrstuv");
            state.blockChannel(CHANNEL); state.blockVideo("abcdefghijk");
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue(state.policy.allowsChannel("UCbbbbbbbbbbbbbbbbbbbbbb"));
            assertFalse(state.policy.allowsChannel(CHANNEL));
            assertTrue(state.policy.blockedVideos().contains("abcdefghijk"));
            assertTrue(state.isFavorite("lmnopqrstuv")); assertTrue(state.appExpired());
            assertTrue(state.verifyPin("582901"));
            ProofState restored = new ProofState(activity);
            assertTrue(restored.policy.allowsChannel("UCbbbbbbbbbbbbbbbbbbbbbb"));
            restored.worker.shutdownNow(); restored.credentials.shutdownNow();
        } finally { state.worker.shutdownNow(); state.credentials.shutdownNow(); }
    }
    private static void unlockParent(View root, ProofState state) throws Exception {
        root.findViewWithTag(MainActivity.Tab.PARENT).performClick();
        AlertDialog pin = ShadowAlertDialog.getLatestAlertDialog();
        Shadows.shadowOf(Looper.getMainLooper()).idle(); findEdit(pin.getWindow().getDecorView()).setText("582901");
        pin.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        state.credentials.submit(() -> {}).get(5, TimeUnit.SECONDS); Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    private static void savePreview(View root, String name, int width, int height) throws Exception {
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); root.draw(new Canvas(bitmap));
        File output = new File("../out/"+name); output.getParentFile().mkdirs();
        try (FileOutputStream stream = new FileOutputStream(output)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); }
    }
    private static View findDescription(View root, String description) {
        if (description.contentEquals(root.getContentDescription() == null ? "" : root.getContentDescription())) return root;
        if (root instanceof ViewGroup group)
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findDescription(group.getChildAt(i), description); if (found != null) return found;
            }
        return null;
    }
    private static void collectVideoTitles(View root, List<String> titles) {
        if (root instanceof TextView text && (text.getText().toString().equals("Build a tiny rocket")
                || text.getText().toString().equals("Explore a clever machine"))) titles.add(text.getText().toString());
        if (root instanceof ViewGroup group)
            for (int i = 0; i < group.getChildCount(); i++) collectVideoTitles(group.getChildAt(i), titles);
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
