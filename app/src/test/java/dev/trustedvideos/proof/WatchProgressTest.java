package dev.trustedvideos.proof;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.Intent;
import android.view.View;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import dev.trustedvideos.proof.core.VideoEntry;
import java.lang.reflect.Proxy;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class WatchProgressTest {
    private static final String ID = "abcdefghijk", CHANNEL = "UC513PdAP2-jWkJunTh5kXRw";
    private SharedPreferences prefs() {
        SharedPreferences prefs = RuntimeEnvironment.getApplication().getSharedPreferences("watch-progress", Context.MODE_PRIVATE);
        prefs.edit().clear().commit(); return prefs;
    }
    @Test public void persistsLatestPositionIncludingBackwardSeeksAndClearsCompletedVideos() {
        SharedPreferences prefs = prefs(); WatchProgress progress = new WatchProgress(prefs);
        progress.save(ID, 45000, 120000, false);
        assertEquals(45000, new WatchProgress(prefs).get(ID).positionMs);
        progress.save(ID, 12000, 120000, false);
        assertEquals(.1, new WatchProgress(prefs).get(ID).fraction(), .0001);
        progress.save(ID, 118000, 120000, false); assertNull(new WatchProgress(prefs).get(ID));
        progress.save(ID, 45000, 120000, false);
        progress.save(ID, 0, -1, true); assertNull(new WatchProgress(prefs).get(ID));
        progress.save(ID, 45000, 120000, false);
        progress.save(ID, 1000, 120000, false); assertNull(new WatchProgress(prefs).get(ID));
    }
    @Test public void unknownDurationsDoNotEraseProgressAndTinyOpeningsAreIgnored() {
        WatchProgress progress = new WatchProgress(prefs());
        progress.save(ID, 1000, 120000, false); assertNull(progress.get(ID));
        progress.save(ID, 45000, 120000, false);
        progress.save(ID, 0, -1, false); progress.save(ID, -1, 120000, false);
        assertEquals(45000, progress.get(ID).positionMs);
    }
    @Test public void boundedToTwoHundredMostRecentlyUpdatedRecords() throws Exception {
        SharedPreferences prefs = prefs(); WatchProgress progress = new WatchProgress(prefs);
        for (int i = 0; i < 200; i++) progress.save(String.format("%011d", i), 15000, 120000, false);
        progress.save("00000000000", 20000, 120000, false);
        progress.save("00000000200", 30000, 120000, false);
        WatchProgress restored = new WatchProgress(prefs);
        assertNull(restored.get("00000000001")); assertNotNull(restored.get("00000000000"));
        assertNotNull(restored.get("00000000200"));
        assertEquals(200, new org.json.JSONArray(prefs.getString("entries", "")).length());
        assertTrue(prefs.getString("entries", "").length() < 10000);
    }
    @Test public void malformedRecordsAreIsolatedAndUnchangedCheckpointsDoNotWrite() {
        SharedPreferences prefs = prefs();
        prefs.edit().putString("entries", "[null,[\"bad\",5000,9000],[\"abcdefghijk\",30000,120000],[\"lmnopqrstuv\",50000,-1]]").commit();
        WatchProgress progress = new WatchProgress(prefs);
        assertEquals(30000, progress.get(ID).positionMs); assertNull(progress.get("lmnopqrstuv"));
        String before = prefs.getString("entries", "");
        progress.save(ID, 30000, 120000, false); assertEquals(before, prefs.getString("entries", ""));
        prefs.edit().putString("entries", "broken").commit();
        assertNull(new WatchProgress(prefs).get(ID));
    }
    private ProofState fixture() {
        prefs();
        ProofApplication app = (ProofApplication) RuntimeEnvironment.getApplication();
        app.getSharedPreferences("proof", Context.MODE_PRIVATE).edit().clear()
                .putLong("datedCatalogRefresh", System.currentTimeMillis())
                .putStringSet("channels", Set.of(CHANNEL))
                .putString("catalog", "[{\"id\":\"" + ID + "\",\"channel\":\"" + CHANNEL
                        + "\",\"title\":\"Rocket\",\"channelName\":\"Test\",\"duration\":120}]").commit();
        ProofState state = new ProofState(app); state.setPin("582901");
        state.progress.save(ID, 30000, 120000, false);
        ReflectionHelpers.setField(app, "state", state); return state;
    }
    @Test public void favoritesAndAllCardSizesShowProgressButBlockedCardsCannotResume() {
        ProofState state = fixture(); VideoEntry entry = state.find(ID);
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            state.toggleFavorite(ID);
            for (boolean compact : new boolean[]{true, false}) {
                View card = Ui.card(controller.get(), state, entry, compact, () -> {});
                assertNotNull(card.findViewWithTag("watch-progress"));
                assertTrue(card.getContentDescription().toString().contains("Resume at 0:30"));
            }
            state.toggleFavorite(ID); assertEquals(30000, state.resumePosition(entry));
            state.blockVideo(ID); assertEquals(0, state.resumePosition(entry));
            assertNull(Ui.card(controller.get(), state, entry, false, () -> {}).findViewWithTag("watch-progress"));
        } finally { state.worker.shutdownNow(); state.credentials.shutdownNow(); }
    }
    @Test public void activityOpensAtCheckpointAndSavesOnBackgroundWithoutErasingDuringLoading() {
        ProofState state = fixture();
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), PlayerActivity.class).putExtra("videoId", ID);
        try (ActivityController<PlayerActivity> controller = Robolectric.buildActivity(PlayerActivity.class, intent).create()) {
            PlayerActivity activity = controller.get();
            assertEquals(30000L, (long) ReflectionHelpers.getField(activity, "resumeAt"));
            ExoPlayer original = ReflectionHelpers.getField(activity, "player");
            ExoPlayer fake = (ExoPlayer) Proxy.newProxyInstance(ExoPlayer.class.getClassLoader(), new Class[]{ExoPlayer.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "getCurrentPosition": return 55000L;
                            case "getDuration": return 120000L;
                            case "getMediaItemCount": return 1;
                            case "getPlaybackState": return Player.STATE_READY;
                        }
                        Class<?> type = method.getReturnType();
                        if (type == boolean.class) return false;
                        if (type == int.class) return 0;
                        if (type == long.class) return 0L;
                        return null;
                    });
            ReflectionHelpers.setField(activity, "player", fake); original.release();
            controller.start().resume();
            assertEquals(30000, state.resumePosition(state.find(ID))); // Loading has no valid position.
            ReflectionHelpers.setField(activity, "progressReady", true);
            controller.pause();
            assertEquals(55000, state.resumePosition(state.find(ID)));
            assertEquals(55000, new WatchProgress(RuntimeEnvironment.getApplication()
                    .getSharedPreferences("watch-progress", Context.MODE_PRIVATE)).get(ID).positionMs);
        } finally { state.worker.shutdownNow(); state.credentials.shutdownNow(); }
    }
}
