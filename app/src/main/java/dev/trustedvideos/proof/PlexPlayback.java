// SPDX-License-Identifier: GPL-3.0-or-later
package dev.trustedvideos.proof;

import android.app.Activity;
import androidx.media3.common.*;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import dev.trustedvideos.proof.core.MediaKey;
import dev.trustedvideos.proof.core.VideoEntry;
import dev.trustedvideos.proof.plex.*;
import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Per-foreground playback contexts; all writes capture the exact child, item and decoded position. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
final class PlexPlayback {
    interface Host { void loaded(); void failed(String reason); void info(String message); }
    private final Activity activity;
    private final ProofState state;
    private final ExoPlayer player;
    private final Host host;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private volatile Run current;
    private volatile long epoch;
    private volatile boolean closed;
    private static final class Run {
        final PlexApi.Session session;
        final VideoEntry video;
        final String context=UUID.randomUUID().toString();
        final java.util.function.BooleanSupplier initialGrant;
        boolean initial=true;
        final AtomicBoolean reporting=new AtomicBoolean();
        volatile PlexApi.Entry entry;
        volatile ProgressGuard guard;
        volatile boolean failed,ended;
        boolean hls,decoded,awaiting;
        long position,lastReport;
        volatile long conversion;
        Run(PlexApi.Session s,VideoEntry v,java.util.function.BooleanSupplier grant) { session=s; video=v; initialGrant=grant; }
    }
    PlexPlayback(Activity activity,ProofState state,ExoPlayer player,Host host) {
        this.activity=activity; this.state=state; this.player=player; this.host=host;
    }
    boolean hasRun() { return current!=null; }
    void start(VideoEntry video,boolean play,java.util.function.BooleanSupplier grant) {
        stop(); if(closed) return;
        PlexApi.Session s=state.plex.session();
        MediaKey.Plex key=MediaKey.plex(video.id);
        if(s==null || key==null || !key.child().equals(s.childId()) || !key.server().equals(s.serverId())) { host.failed("Plex needs a Parent connection."); return; }
        Run run=new Run(s,video,grant); current=run; long ticket=++epoch;
        io.execute(()-> {
            try {
                state.plex.store.check(s,key.item());
                PlexApi.Entry e=state.plex.api.metadata(s,key.item()); validate(run,e);
                run.entry=e; run.guard=new ProgressGuard(e.offset(),e.views());
                run.position=e.offset(); run.hls=!(e.container().equals("mp4") && (e.videoCodec().equals("h264")||e.videoCodec().equals("avc")) && e.streams().stream().noneMatch(t->t.type()==3 && t.selected()) && e.streams().stream().filter(t->t.type()==2).count()<=1);
                state.plex.verified(s);
                URI uri=state.plex.api.playback(s,e,run.hls,UUID.randomUUID().toString(),e.offset(),run.context);
                activity.runOnUiThread(()-> { if(live(run,ticket)) prepare(run,uri,e.offset(),play); });
            } catch(Exception error) { failure(run,ticket,"Plex could not authorize or load this episode. Retry or reconnect in Parent.",true); }
        });
    }
    private void validate(Run run,PlexApi.Entry e) throws IOException {
        state.plex.store.check(run.session,e.id());
        if(!e.type().equals("episode") || !e.id().equals(MediaKey.plex(run.video.id).item())
                || !PlexLibrary.key(run.session,"show",e.showId()).equals(run.video.channelId)) throw new IOException("Episode identity changed.");
    }
    private boolean live(Run run,long ticket) { return !closed && current==run && ticket==epoch && !run.failed && !activity.isDestroyed(); }
    private void prepare(Run run,URI uri,long position,boolean play) {
        try {
            state.plex.store.check(run.session,run.entry.id());
            if(run.initial && !run.initialGrant.getAsBoolean()) throw new IOException("Approval changed while loading.");
            run.initial=false; run.awaiting=true;
            DefaultMediaSourceFactory factory=new DefaultMediaSourceFactory(()->new PlexMediaSource(state.plex.store,run.session,run.entry.id(),state.plex.store.clientId(),run.context));
            MediaItem.Builder item=new MediaItem.Builder().setUri(uri.toString()).setMediaId(run.video.id);
            if(run.hls) item.setMimeType(MimeTypes.APPLICATION_M3U8);
            player.setMediaSource(factory.createMediaSource(item.build()),Math.max(0,position)); player.prepare(); player.setPlayWhenReady(play);
            host.loaded();
        } catch(Exception e) { failure(run,epoch,"Plex playback permission changed.",true); }
    }
    void playing(boolean playing) {
        Run run=current;
        if(run==null || run.entry==null || run.failed) return;
        if(playing && player.getCurrentMediaItem()!=null && run.video.id.equals(player.getCurrentMediaItem().mediaId)) {
            run.decoded=true; run.awaiting=false; run.position=Math.max(0,player.getCurrentPosition()); run.guard.played();
        }
        if(!playing) report(run,"paused",false);
    }
    void tick() {
        Run run=current; if(run==null || run.entry==null || run.failed) return;
        if(player.isPlaying()) playing(true);
        if(android.os.SystemClock.elapsedRealtime()-run.lastReport>=10_000) {
            run.lastReport=android.os.SystemClock.elapsedRealtime();
            report(run,player.isPlaying()?"playing":player.getPlaybackState()==Player.STATE_BUFFERING?"buffering":"paused",false);
        }
    }
    void ended() { Run run=current; if(run!=null) { report(run,"stopped",true); run.ended=true; } }
    void seek(long position) {
        Run run=current; if(run==null || run.entry==null || run.failed) return;
        boolean play=player.getPlayWhenReady();
        if(player.isPlaying()) { run.position=player.getCurrentPosition(); run.guard.played(); }
        report(run,"paused",false); run.awaiting=true;
        if(!run.hls) { player.seekTo(position); return; }
        convert(run,position,play,null,null);
    }
    private void convert(Run run,long position,boolean play,String audio,String subtitle) {
        long ticket=epoch,conversion=++run.conversion,revision=state.policy.revision(); player.pause(); player.stop();
        io.execute(()-> {
            try {
                if(current!=run || closed || run.conversion!=conversion) return;
                PlexApi.Entry exact=state.plex.api.metadata(run.session,run.entry.id()); validate(run,exact); state.plex.verified(run.session);
                if(audio!=null || subtitle!=null) state.plex.api.selectStream(run.session,exact,audio,subtitle);
                URI uri=state.plex.api.playback(run.session,exact,true,UUID.randomUUID().toString(),position,run.context);
                activity.runOnUiThread(()-> { if(live(run,ticket) && run.conversion==conversion) { if(revision!=state.policy.revision()) { host.failed("Approval changed while loading."); return; } run.entry=exact; run.hls=true; prepare(run,uri,position,play); } });
            } catch(Exception e) { failure(run,ticket,"Plex could not authorize this playback change. Retry from the saved Plex position.",true); }
        });
    }
    boolean fallback(PlaybackException error) {
        Run run=current;
        if(run==null || run.hls || run.failed || run.entry==null) return false;
        int c=error.errorCode;
        if(c!=PlaybackException.ERROR_CODE_DECODER_INIT_FAILED && c!=PlaybackException.ERROR_CODE_DECODING_FAILED
                && c!=PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED && c!=PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES
                && c!=PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED) return false;
        run.hls=true; convert(run,run.position,player.getPlayWhenReady(),null,null); return true;
    }
    void failed() { if(current!=null) current.failed=true; }
    List<PlexApi.Stream> streams(int type) {
        Run run=current; if(run==null || run.entry==null) return List.of();
        return run.entry.streams().stream().filter(t->t.type()==type).toList();
    }
    void select(String audio,String subtitle) {
        Run run=current; if(run==null || run.entry==null) return;
        long position=player.getCurrentPosition(); boolean play=player.getPlayWhenReady();
        if(player.isPlaying()) { run.position=position; run.guard.played(); }
        report(run,"paused",false); convert(run,position,play,audio,subtitle);
    }
    private void report(Run run,String status,boolean ended) {
        // No timeline can assert a seek target before decoded playback reaches it.
        if(run.failed || run.entry==null) return;
        boolean ownsSlot=run.reporting.compareAndSet(false,true);
        if(!ownsSlot && !status.equals("stopped")) return;
        final long position=run.position; final long ticket=epoch;
        io.execute(()-> {
            try {
                if(run.failed) return;
                validate(run,run.entry);
                PlexApi.Entry before=state.plex.api.metadata(run.session,run.entry.id()); validate(run,before); state.plex.verified(run.session);
                ProgressGuard.Decision d=run.guard.observe(before.offset(),before.views());
                if(d==ProgressGuard.Decision.CONFLICT) throw new ProgressConflict();
                if(d==ProgressGuard.Decision.WRITE && run.decoded) {
                    state.plex.api.timeline(run.session,run.entry,status,position,run.context);
                    run.guard.accepted(position);
                    PlexApi.Entry after=state.plex.api.metadata(run.session,run.entry.id()); validate(run,after);
                    state.plex.observed(run.session,after);
                    if(run.guard.observe(after.offset(),after.views())==ProgressGuard.Decision.CONFLICT) throw new ProgressConflict();
                }
                state.plex.invalidateTargets();
            } catch(ProgressConflict e) { failure(run,ticket,"Plex progress changed elsewhere. Reopen the episode to use its latest position.",false); }
            catch(Exception e) { failure(run,ticket,"Plex progress or permission could not be verified. Reopen the episode when connected.",true); }
            finally { if(ownsSlot) run.reporting.set(false); }
        });
    }
    private static final class ProgressConflict extends IOException { }
    private void failure(Run run,long ticket,String message,boolean invalidate) {
        run.failed=true;
        activity.runOnUiThread(()-> {
            if(current!=run || ticket!=epoch || closed) return;
            if(invalidate) state.plex.fail("Plex needs verification. Retry in Parent.");
            host.failed(message);
        });
    }
    void stop() {
        Run run=current; current=null; epoch++;
        if(run!=null) { if(player.isPlaying() && !run.awaiting) { run.position=player.getCurrentPosition(); run.guard.played(); } report(run,"stopped",false); }
    }
    void close() { stop(); closed=true; io.shutdown(); }
}
