package dev.trustedvideos.proof;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import dev.trustedvideos.proof.core.*;
import dev.trustedvideos.proof.plex.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28) @LooperMode(LooperMode.Mode.PAUSED)
public class PlexIntegrationTest {
    private static final String YT="UC513PdAP2-jWkJunTh5kXRw",SHOW=MediaKey.key("2","server","show","10"),EP=MediaKey.key("2","server","episode","42");
    private final List<ProofState> states=new ArrayList<>();
    private ProofState state() { ProofState s=new ProofState(RuntimeEnvironment.getApplication()); states.add(s); return s; }
    @Before public void clear() {
        Context c=RuntimeEnvironment.getApplication();
        for(String name:List.of("proof","plex-connection-v1","watch-progress")) c.getSharedPreferences(name,Context.MODE_PRIVATE).edit().clear().commit();
    }
    @After public void close() { for(ProofState s:states) { s.worker.shutdownNow(); s.credentials.shutdownNow(); s.plexImages.close(); } }
    private void youtube(ProofState s) {
        s.add(new dev.trustedvideos.proof.source.SourceRepository.ChannelBatch(YT,"Zoo channel","",List.of(new VideoEntry("abcdefghijk",YT,"Zoo channel","Rocket","",120)),false));
        s.setPin("582901");
    }
    @Test public void additiveUpgradeKeepsYouTubePinResumeBlocksFavoritesHistoryAndUsageWhilePlexGrantsExpire() {
        RuntimeEnvironment.getApplication().getSharedPreferences("proof",Context.MODE_PRIVATE).edit()
                .putString("dailyDate",java.time.LocalDate.now().toString()).putLong("dailyAppMs",120000).putLong("dailyWatchMs",61000)
                .putLong("foregroundMs",123456).putLong("playingMs",65432).commit();
        ProofState s=state(); youtube(s); s.timers.today(TimerSchedule.Type.WATCHING,30);
        s.toggleFavorite("abcdefghijk"); s.recordWatched("abcdefghijk");
        s.progress.save("abcdefghijk",55000,120000,false); s.blockVideo("lmnopqrstuv");
        s.plexShow(SHOW,"A show",PlexLibrary.art(SHOW));
        s.plexEpisode(new VideoEntry(EP,SHOW,"A show","Episode","",120)); s.toggleFavorite(EP); s.recordWatched(EP);
        s.blockChannel(MediaKey.key("2","server","show","99"));
        var before=s.homeMix(false);
        ProofState restored=state(); assertTrue(restored.verifyPin("582901"));
        assertTrue(restored.isFavorite("abcdefghijk")); assertTrue(restored.isFavorite(EP));
        assertEquals(120000,restored.timers.used(TimerSchedule.Type.APP));
        assertEquals(61000,restored.timers.used(TimerSchedule.Type.WATCHING)); assertEquals(30,restored.timers.allowance(TimerSchedule.Type.WATCHING));
        assertEquals(123456,restored.meter.foregroundMs()); assertEquals(65432,restored.meter.playingMs());
        assertEquals(55000,restored.resumePosition(restored.find("abcdefghijk")));
        assertTrue(restored.policy.blockedVideos().contains("lmnopqrstuv"));
        assertEquals(List.of("abcdefghijk"),restored.historyEntries().stream().map(e->e.id).toList());
        assertFalse(restored.policy.allows(EP,SHOW));
        restored.plexShow(SHOW,"A show",PlexLibrary.art(SHOW)); restored.plexEpisode(restored.find(EP));
        assertEquals(before,restored.homeMix(false));
        assertEquals(List.of(EP,"abcdefghijk"),restored.historyEntries().stream().map(e->e.id).toList());
        var channels=ChannelTransfer.decode(restored.exportChannels()); assertEquals(1,channels.size()); assertEquals(YT,channels.get(0).id);
    }
    @Test public void channelsMixAlphabeticallyUseShowArtworkAndOpenShowRatherThanYouTube() {
        ProofState s=state(); youtube(s); s.plexShow(SHOW,"A show",PlexLibrary.art(SHOW));
        ReflectionHelpers.setField((ProofApplication)RuntimeEnvironment.getApplication(),"state",s);
        // Keep browsing deterministic without a live connection.
        try(var controller=Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity a=controller.get(); View root=a.getWindow().getDecorView(); root.findViewWithTag(MainActivity.Tab.CHANNELS).performClick();
            List<String> names=new ArrayList<>(); collect(root,names); assertTrue(names.indexOf("A show")<names.indexOf("Zoo channel"));
            TextView show=find(root,"A show"); assertNotNull(show); ViewGroup tile=(ViewGroup)show.getParent();
            assertTrue(((ImageView)tile.getChildAt(0)).getClipToOutline());
            assertEquals(PlexLibrary.art(SHOW),tile.getChildAt(0).getTag());
            tile.performClick(); assertEquals(SHOW,ReflectionHelpers.getField(a,"selectedChannel"));
            assertNotNull(find(root,"Browse episodes"));
        }
    }
    @Test public void protectedArtworkCannotUseMemoryAfterBlockDisconnectOrAnotherChild() {
        ProofState s=state(); youtube(s); var session=new PlexApi.Session("2","Child","ACCOUNT","server","SERVER","https://media.example",1);
        ReflectionHelpers.setField(s.plex,"session",session);
        s.plexShow(SHOW,"A show",PlexLibrary.art(SHOW));
        assertTrue(s.plexImages.accepts(PlexLibrary.art(SHOW)));
        s.blockChannel(SHOW); assertFalse(s.plexImages.accepts(PlexLibrary.art(SHOW)));
        s.restoreChannel(SHOW); assertTrue(s.plexImages.accepts(PlexLibrary.art(SHOW)));
        assertFalse(s.plexImages.accepts(PlexLibrary.art(MediaKey.key("3","server","show","10"))));
        s.plex.disconnect(); assertFalse(s.plexImages.accepts(PlexLibrary.art(SHOW)));
        assertFalse(ImageCache.allowed("https://media.example/library/metadata/10/thumb"));
    }
    private static TextView find(View v,String text) {
        if(v instanceof TextView t && t.getText().toString().equals(text)) return t;
        if(v instanceof ViewGroup g) for(int i=0;i<g.getChildCount();i++) { TextView r=find(g.getChildAt(i),text); if(r!=null) return r; }
        return null;
    }
    private static void collect(View v,List<String> names) {
        if(v instanceof TextView t) names.add(t.getText().toString());
        if(v instanceof ViewGroup g) for(int i=0;i<g.getChildCount();i++) collect(g.getChildAt(i),names);
    }
}
