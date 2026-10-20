package dev.trustedvideos.proof;

import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.view.WindowManager;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.BaseMediaSource;
import androidx.media3.exoplayer.source.MediaPeriod;
import androidx.media3.exoplayer.upstream.Allocator;
import java.time.Duration;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class PlayerScreenAwakeTest {
    private static final String ID = "abcdefghijk", CHANNEL = "UC513PdAP2-jWkJunTh5kXRw";
    private ProofState state;
    private ActivityController<PlayerActivity> controller;
    private PlayerActivity activity;
    private ExoPlayer player;

    @Before public void setUp() {
        ProofApplication app = (ProofApplication) RuntimeEnvironment.getApplication();
        app.getSharedPreferences("proof", Context.MODE_PRIVATE).edit().clear()
                .putStringSet("channels", Set.of(CHANNEL))
                .putString("catalog", "[{\"id\":\"" + ID + "\",\"channel\":\"" + CHANNEL
                        + "\",\"title\":\"Rocket\",\"channelName\":\"Test\",\"duration\":120}]").commit();
        state = new ProofState(app); state.setPin("582901");
        ReflectionHelpers.setField(app, "state", state);
        controller = Robolectric.buildActivity(PlayerActivity.class,
                new Intent(app, PlayerActivity.class).putExtra("videoId", ID)).create();
        activity = controller.get();
        player = ReflectionHelpers.getField(activity, "player");
        // A source that waits for metadata keeps the real player buffering without network or codecs.
        player.setMediaSource(new BaseMediaSource() {
            @Override public MediaItem getMediaItem() { return MediaItem.fromUri("asset:///waiting"); }
            @Override protected void prepareSourceInternal(TransferListener listener) {}
            @Override public void maybeThrowSourceInfoRefreshError() {}
            @Override public MediaPeriod createPeriod(MediaPeriodId id, Allocator allocator, long startPositionUs) {
                throw new AssertionError("Waiting source has no periods");
            }
            @Override public void releasePeriod(MediaPeriod period) {}
            @Override protected void releaseSourceInternal() {}
        });
        controller.start().resume();
        idle();
        assertFalse(keepsScreenOn());
    }

    @After public void tearDown() {
        if (controller != null) controller.close();
        if (state != null) { state.worker.shutdownNow(); state.credentials.shutdownNow(); }
    }

    private void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    private boolean keepsScreenOn() {
        return (activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0;
    }
    private void startBuffering() {
        player.prepare(); player.play(); idle();
        assertEquals(Player.STATE_BUFFERING, player.getPlaybackState());
        assertFalse(player.isPlaying());
        assertTrue("Foreground playback must prevent screen timeout, including buffering", keepsScreenOn());
    }

    @Test public void playAndPauseWhileBufferingControlScreenTimeout() {
        startBuffering();
        player.pause(); idle();
        assertFalse(keepsScreenOn());
        player.play(); idle();
        assertTrue(keepsScreenOn());
        player.stop(); idle();
        assertTrue(player.getPlayWhenReady());
        assertFalse("An idle player must allow screen timeout even with play requested", keepsScreenOn());
    }

    @Test public void backgroundClearsImmediatelyAndReturnStaysPaused() {
        startBuffering();
        controller.pause();
        assertFalse("Release before queued player events are delivered", keepsScreenOn());
        idle();
        assertFalse(keepsScreenOn());
        controller.resume(); idle();
        assertFalse(player.getPlayWhenReady());
        assertFalse(keepsScreenOn());
        player.play(); idle();
        assertTrue(keepsScreenOn());
    }

    @Test public void blockedPlaybackReleasesScreenAndReturnsToListings() {
        startBuffering();
        state.blockVideo(ID); idle();
        assertTrue(activity.isFinishing());
        assertEquals(0, player.getMediaItemCount());
        assertFalse(keepsScreenOn());
    }

    @Test public void expiredAppTimerStopsPlaybackAndReleasesScreen() {
        startBuffering();
        state.testLimit(false, 1);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertEquals(0, player.getMediaItemCount());
        assertFalse(keepsScreenOn());
    }
}
