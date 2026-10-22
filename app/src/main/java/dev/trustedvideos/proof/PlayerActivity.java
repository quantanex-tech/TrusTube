// SPDX-License-Identifier: GPL-3.0-or-later
package dev.trustedvideos.proof;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.PopupMenu;
import android.view.KeyEvent;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MergingMediaSource;
import androidx.media3.ui.PlayerView;
import dev.trustedvideos.proof.core.PlaybackGate;
import dev.trustedvideos.proof.core.VideoEntry;
import dev.trustedvideos.proof.source.ResolvedPlayback;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Future;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class PlayerActivity extends androidx.appcompat.app.AppCompatActivity {
    private ProofState state;
    private PlexPlayback plexPlayback;
    private PlaybackGate gate;
    private ExoPlayer player;
    private PlayerView playerView;
    private DefaultMediaSourceFactory sourceFactory;
    private LinearLayout messagePanel;
    private PlayerControls controls;
    private TextView message;
    private VideoEntry selected;
    private Future<?> pending;
    private boolean active, loading, historyRecorded;
    private long request;
    private String kind = "";
    private String requestedAudio, selectedAudio = "";
    private java.util.List<ResolvedPlayback.AudioOption> audioOptions = java.util.List.of();
    private long resumeAt;
    private boolean resumePlaying = true;
    private boolean progressReady;
    private long lastProgressSave;
    private android.app.AlertDialog audioDialog;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable changed = this::permissionsChanged;
    private final Runnable clock = new Runnable() {
        @Override public void run() {
            if (!active) return;
            state.updateUsage(true, player.isPlaying());
            if (android.os.SystemClock.elapsedRealtime() - lastProgressSave >= WatchProgress.SAVE_INTERVAL_MS)
                saveProgress();
            if(selected!=null && selected.isPlex() && player.getMediaItemCount()>0) state.plex.checkExpiry();
            plexPlayback.tick();
            updateControls();
            if (state.expired()) deny("Viewing time is up. Ask a parent for help.");
            handler.postDelayed(this, 1000);
        }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        state = ((ProofApplication) getApplication()).state();
        gate = new PlaybackGate(state.policy);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(Color.BLACK);
        playerView = new PlayerView(this); playerView.setUseController(false);
        root.addView(playerView, new FrameLayout.LayoutParams(-1, -1));
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(15000).setReadTimeoutMs(25000);
        sourceFactory = new DefaultMediaSourceFactory(new DefaultDataSource.Factory(this, http));
        player = new ExoPlayer.Builder(this).setMediaSourceFactory(sourceFactory).build();
        playerView.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override public void onEvents(Player player, Player.Events events) { updateKeepScreenOn(); }
            @Override public void onIsPlayingChanged(boolean playing) {
                state.updateUsage(active, playing);
                if(plexPlayback!=null && selected!=null && selected.isPlex()) plexPlayback.playing(playing);
                if (!playing) saveProgress();
                MediaItem current = player.getCurrentMediaItem();
                if (playing && active && !historyRecorded && selected != null && current != null
                        && selected.id.equals(current.mediaId) && state.pinSet() && !state.expired()
                        && state.policy.allows(selected.id, selected.channelId)) {
                    state.recordWatched(selected.id); historyRecorded = true;
                }
                if (controls != null) controls.setPlaying(player.getPlayWhenReady() && player.getPlaybackState() != Player.STATE_ENDED);
            }
            @Override public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_READY) progressReady = true;
                if (playbackState == Player.STATE_ENDED) {
                    if(selected!=null && selected.isPlex()) plexPlayback.ended();
                    saveProgress(); controls.setPlaying(false); controls.show(); updateControls();
                }
            }
            @Override public void onPlayerError(PlaybackException error) {
                if(selected!=null && selected.isPlex()) {
                    if(plexPlayback.fallback(error)) return;
                    plexPlayback.failed(); deny("Plex playback failed ("+error.getErrorCodeName()+"). Reopen the episode to use Plex's latest position."); return;
                }
                resumeAt = player.getCurrentPosition(); resumePlaying = player.getPlayWhenReady();
                deny("This stream could not play (" + error.getErrorCodeName() + "). Retry resolves the same approved video again.");
            }
        });
        plexPlayback=new PlexPlayback(this,state,player,new PlexPlayback.Host() {
            public void loaded() { loading=false; kind="Plex"; messagePanel.setVisibility(View.GONE); controls.show(); updateControls(); loadPlexRail(false); }
            public void failed(String reason) { deny(reason); }
            public void info(String text) { android.widget.Toast.makeText(PlayerActivity.this,text,android.widget.Toast.LENGTH_SHORT).show(); }
        });
        controls = new PlayerControls(this, state, new PlayerControls.Actions() {
            public void back() { finish(); }
            public void togglePlay() {
                if (selected == null || !state.policy.allows(selected.id, selected.channelId) || state.expired()) return;
                if (player.getPlayWhenReady()) player.pause();
                else if (player.getMediaItemCount() > 0) {
                    if (player.getPlaybackState() == Player.STATE_ENDED) player.seekTo(0);
                    player.play();
                }
                controls.setPlaying(player.getPlayWhenReady());
            }
            public void favorite() { saveProgress(); if (selected != null) state.toggleFavorite(selected.id); updateControls(); }
            public void menu(View anchor) { showMenu(anchor); }
            public void seek(long position) {
                if (active && selected != null && state.policy.allows(selected.id, selected.channelId) && !state.expired())
                    if(selected.isPlex()) plexPlayback.seek(position); else player.seekTo(position);
            }
            public void play(VideoEntry entry) { PlayerActivity.this.play(entry); }
            public void more() { loadMore(); }
        });
        root.addView(controls, new FrameLayout.LayoutParams(-1, -1));
        messagePanel = new LinearLayout(this); messagePanel.setOrientation(LinearLayout.VERTICAL);
        messagePanel.setPadding(Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 18));
        messagePanel.setBackground(Ui.background(0xF0FFFFFF, 18, this));
        message = Ui.text(this, "", 17, true); messagePanel.addView(message);
        messagePanel.addView(Ui.button(this, "Retry this video", this::retry));
        messagePanel.addView(Ui.button(this, "Back to approved videos", this::finish));
        FrameLayout.LayoutParams panel = new FrameLayout.LayoutParams(Ui.dp(this, 330), -2, Gravity.CENTER);
        root.addView(messagePanel, panel);
        setContentView(root);
        selected = state.find(saved == null ? getIntent().getStringExtra("videoId") : saved.getString("videoId"));
        resumeAt = state.resumePosition(selected);
        if (saved != null) {
            resumePlaying = false;
            historyRecorded = saved.getBoolean("historyRecorded", false);
        }
        updateControls(); refreshRail();
    }
    @Override protected void onSaveInstanceState(Bundle saved) {
        saveProgress();
        if (selected != null) saved.putString("videoId", selected.id);
        saved.putBoolean("historyRecorded", historyRecorded);
        super.onSaveInstanceState(saved);
    }
    @Override protected void onResume() {
        super.onResume(); active = true; state.subscribe(changed); controls.holdMenu(false);
        LazyImages.setActive(this, true);
        permissionsChanged(); if (isFinishing()) return; clock.run();
        updateKeepScreenOn();
        if (player.getMediaItemCount() == 0 && selected != null && !state.expired()) retry();
    }
    @Override protected void onPause() {
        saveProgress();
        if(selected!=null && selected.isPlex()) { plexPlayback.stop(); player.stop(); player.clearMediaItems(); }
        LazyImages.setActive(this, false);
        active = false; updateKeepScreenOn(); handler.removeCallbacks(clock);
        if (player.getMediaItemCount() > 0) resumeAt = player.getCurrentPosition();
        resumePlaying = false;
        state.updateUsage(false, false); player.pause();
        if (audioDialog != null) audioDialog.dismiss();
        controls.stop();
        gate.cancel(); request++;
        if (pending != null) pending.cancel(true);
        if (loading) { loading = false; message.setText("Loading paused. Retry when you return."); }
        state.unsubscribe(changed); super.onPause();
        state.invalidateParentActions();
    }
    @Override protected void onDestroy() {
        active = false; updateKeepScreenOn();
        handler.removeCallbacksAndMessages(null); controls.stop();
        plexPlayback.close(); playerView.setPlayer(null); player.release(); super.onDestroy();
    }
    private void updateKeepScreenOn() {
        int playbackState = player.getPlaybackState();
        // Keep protection through rebuffering, but release it when viewing stops or is suppressed.
        boolean keepAwake = active && !isFinishing() && player.getPlayWhenReady()
                && player.getPlaybackSuppressionReason() == Player.PLAYBACK_SUPPRESSION_REASON_NONE
                && (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING);
        if (keepAwake) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    private void updateControls() {
        if (selected == null) return;
        long duration = player.getDuration();
        if (duration < 0) duration = selected.durationSeconds * 1000L;
        controls.update(selected, Math.max(0, player.getCurrentPosition()), duration, state.isFavorite(selected.id));
    }
    private void refreshRail() {
        java.util.List<VideoEntry> rows;
        if(selected!=null && selected.isPlex()) rows=state.plex.rail(selected.channelId);
        else { rows=state.allowedEntries(); rows.removeIf(VideoEntry::isPlex); }
        controls.setVideos(rows,selected!=null && selected.isPlex()?state.plex.canLoadRail(selected.channelId):state.canLoadMore(null));
    }
    private void loadMore() {
        if(selected!=null && selected.isPlex()) { loadPlexRail(true); return; }
        state.loadMoreAllowed(null, error -> {
            if (!isDestroyed() && active) {
                refreshRail();
                if (!error.isEmpty()) android.widget.Toast.makeText(this, error, android.widget.Toast.LENGTH_SHORT).show();
            }
        });
    }
    private void loadPlexRail(boolean more) {
        if(selected==null || !selected.isPlex()) return;
        String id=selected.id,show=selected.channelId;
        state.plex.loadRail(show,more,()->{ if(active && !isDestroyed() && selected!=null && selected.id.equals(id)) refreshRail(); });
    }
    private void showMenu(View anchor) {
        if (selected == null) return;
        controls.holdMenu(true);
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("Time left today · Watching: " + state.timers.remainingLabel(
                dev.trustedvideos.proof.core.TimerSchedule.Type.WATCHING)).setEnabled(false);
        menu.getMenu().add("Time left today · Full app: " + state.timers.remainingLabel(
                dev.trustedvideos.proof.core.TimerSchedule.Type.APP)).setEnabled(false);
        menu.getMenu().add("Audio language").setOnMenuItemClickListener(item -> { chooseAudio(); return true; });
        if(selected.isPlex()) menu.getMenu().add("Subtitles").setOnMenuItemClickListener(item->{ choosePlexTrack(3); return true; });
        if (state.testRemainingMs() >= 0)
            menu.getMenu().add("Proof timer left: " + Ui.duration(state.testRemainingMs())).setEnabled(false);
        menu.getMenu().add(state.isFavorite(selected.id) ? "Remove favorite" : "Add favorite")
                .setOnMenuItemClickListener(item -> { saveProgress(); state.toggleFavorite(selected.id); return true; });
        menu.getMenu().add(selected.isPlex()?"Block this episode":"Block this video").setOnMenuItemClickListener(item -> {
            showBlock(false); return true;
        });
        menu.getMenu().add(selected.isPlex()?"Block this show":"Block this channel").setOnMenuItemClickListener(item -> {
            showBlock(true); return true;
        });
        menu.setOnDismissListener(ignored -> controls.holdMenu(audioDialog != null && audioDialog.isShowing())); menu.show();
    }
    private void showBlock(boolean channel) {
        audioDialog = Ui.confirmBlock(this, state, selected, channel, this::stopAndFinish);
        audioDialog.setOnDismissListener(dialog -> controls.holdMenu(false));
    }
    private void stopAndFinish() {
        saveProgress(); progressReady = false;
        active = false; gate.cancel(); request++; if (pending != null) pending.cancel(true);
        player.stop(); player.clearMediaItems();
        updateKeepScreenOn();
        setResult(RESULT_OK, new android.content.Intent().putExtra("returnHome", true)); finish();
    }
    private void permissionsChanged() {
        updateControls(); refreshRail();
        if (selected == null || !state.pinSet()) deny("This video is unavailable.");
        else if (!state.policy.allows(selected.id, selected.channelId)) stopAndFinish();
        else if (state.expired()) deny("Viewing time is up. Ask a parent for help.");
    }
    @Override public boolean onKeyDown(int key, KeyEvent event) {
        if (key == KeyEvent.KEYCODE_SPACE || key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            if (selected != null && !state.expired() && state.policy.allows(selected.id, selected.channelId)) {
                if (player.getPlayWhenReady()) player.pause(); else if (player.getMediaItemCount() > 0) player.play();
                controls.setPlaying(player.getPlayWhenReady()); controls.show();
            }
            return true;
        }
        controls.show(); return super.onKeyDown(key, event);
    }
    private void play(VideoEntry entry) {
        historyRecorded = false;
        startPlayback(entry, null, state.resumePosition(entry), true);
    }
    private void retry() { if (selected != null) startPlayback(selected, requestedAudio, resumeAt, resumePlaying); }
    private void chooseAudio() {
        if (!active || selected == null) return;
        if(selected.isPlex()) { choosePlexTrack(2); return; }
        if (audioOptions.isEmpty()) {
            audioDialog = new android.app.AlertDialog.Builder(this).setTitle("Audio language")
                    .setMessage("This video has one default audio track. Language information is unavailable.")
                    .setPositiveButton("OK", null).show();
            audioDialog.setOnDismissListener(dialog -> controls.holdMenu(false)); return;
        }
        String[] labels = audioOptions.stream().map(option -> option.label).toArray(String[]::new);
        int checked = -1;
        for (int i = 0; i < audioOptions.size(); i++) if (audioOptions.get(i).id.equals(selectedAudio)) checked = i;
        audioDialog = new android.app.AlertDialog.Builder(this).setTitle("Audio language")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    if (active && selected != null && !state.expired())
                        startPlayback(selected, audioOptions.get(which).id, player.getCurrentPosition(), player.getPlayWhenReady());
                    dialog.dismiss();
                }).setNegativeButton("Cancel", null).create();
        audioDialog.setOnDismissListener(dialog -> controls.holdMenu(false)); audioDialog.show();
    }
    private void choosePlexTrack(int type) {
        java.util.List<dev.trustedvideos.proof.plex.PlexApi.Stream> tracks=plexPlayback.streams(type);
        java.util.List<String> labels=new java.util.ArrayList<>(); if(type==3) labels.add("Off");
        tracks.forEach(t->labels.add(t.label()));
        if(labels.isEmpty()) { audioDialog=new android.app.AlertDialog.Builder(this).setTitle("Audio language").setMessage("The original audio track is selected.").setPositiveButton("OK",null).show(); return; }
        audioDialog=new android.app.AlertDialog.Builder(this).setTitle(type==3?"Subtitles":"Audio language")
                .setItems(labels.toArray(new String[0]),(d,w)->{
                    String id=type==3 && w==0?"0":tracks.get(type==3?w-1:w).id();
                    plexPlayback.select(type==2?id:null,type==3?id:null);
                }).setNegativeButton("Cancel",null).show();
        audioDialog.setOnDismissListener(d->controls.holdMenu(false));
    }
    private void startPlayback(VideoEntry entry, String audioId, long position, boolean shouldPlay) {
        if (!active || !state.pinSet()) return;
        saveProgress(); progressReady = false;
        plexPlayback.stop();
        requestedAudio = audioId; resumeAt = position; resumePlaying = shouldPlay;
        audioOptions = java.util.List.of(); selectedAudio = "";
        state.updateUsage(true, player.isPlaying()); player.stop(); player.clearMediaItems();
        if (pending != null) pending.cancel(true);
        final PlaybackGate.Ticket ticket;
        try { ticket = gate.begin(entry, !state.expired()); }
        catch (SecurityException error) { deny(error.getMessage()); return; }
        selected = entry;
        updateControls(); refreshRail(); controls.show(); loading = true; kind = ""; final long serial = ++request;
        message.setText("Loading " + entry.title + "…"); messagePanel.setVisibility(View.VISIBLE);
        if(entry.isPlex()) { plexPlayback.start(entry,shouldPlay,()->gate.accept(ticket,entry.id,entry.channelId,!state.expired())); return; }
        pending = state.worker.submit(() -> {
            try {
                ResolvedPlayback resolved = state.sources.resolve(entry, state.forceSplit(), audioId);
                runOnUiThread(() -> {
                    if (!active || serial != request || isDestroyed()) return;
                    state.updateUsage(true, false);
                    if (!gate.accept(ticket, resolved.videoId, resolved.channelId, !state.expired())) {
                        deny("Approval changed while loading. Choose an approved video again."); return;
                    }
                    try {
                        MediaSource video = mediaSource(resolved.video, entry.id);
                        MediaSource source = resolved.audio == null ? video
                                : new MergingMediaSource(true, true, video, mediaSource(resolved.audio, entry.id));
                        player.setMediaSource(source, Math.max(0, position)); player.prepare(); player.setPlayWhenReady(shouldPlay);
                        audioOptions = resolved.audioOptions; selectedAudio = resolved.selectedAudio;
                        kind = resolved.kind; loading = false; messagePanel.setVisibility(View.GONE);
                        controls.setPlaying(shouldPlay); controls.show();
                    } catch (Exception error) { deny("This approved source could not be prepared. Try again."); }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (active && serial == request && !isDestroyed())
                        deny(error instanceof SecurityException || error instanceof IllegalArgumentException
                                ? error.getMessage()
                                : "Unable to resolve this video (" + error.getClass().getSimpleName() + "). No other video was substituted.");
                });
            }
        });
    }
    private MediaSource mediaSource(ResolvedPlayback.Track track, String videoId) {
        MediaItem.Builder item = new MediaItem.Builder().setMediaId(videoId);
        if (track.dashManifest == null) item.setUri(track.url);
        else item.setUri("data:application/dash+xml;base64,"
                + Base64.encodeToString(track.dashManifest.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP))
                .setMimeType(MimeTypes.APPLICATION_MPD);
        return sourceFactory.createMediaSource(item.build());
    }
    private void deny(String reason) {
        saveProgress(); progressReady = false;
        plexPlayback.stop();
        gate.cancel(); request++; loading = false;
        if (pending != null) pending.cancel(true);
        player.stop(); player.clearMediaItems();
        updateKeepScreenOn();
        message.setText(reason); messagePanel.setVisibility(View.VISIBLE); controls.setPlaying(false); controls.show();
    }
    private void saveProgress() {
        if (!progressReady || selected == null || selected.isPlex() || player.getMediaItemCount() == 0 || player.isCurrentMediaItemLive()) return;
        long position = Math.max(0, player.getCurrentPosition()), duration = player.getDuration();
        state.progress.save(selected.id, position, duration, player.getPlaybackState() == Player.STATE_ENDED);
        resumeAt = position;
        lastProgressSave = android.os.SystemClock.elapsedRealtime();
    }
}
