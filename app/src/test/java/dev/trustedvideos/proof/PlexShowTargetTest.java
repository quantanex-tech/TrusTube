package dev.trustedvideos.proof;

import android.content.Context;
import android.os.Looper;
import dev.trustedvideos.proof.core.MediaKey;
import dev.trustedvideos.proof.plex.PlexApi;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28) @LooperMode(LooperMode.Mode.PAUSED)
public class PlexShowTargetTest {
    private ProofState state;
    private static final String SHOW=MediaKey.key("2","server","show","10");
    @Before public void setup() {
        var context=RuntimeEnvironment.getApplication(); context.getSharedPreferences("proof",Context.MODE_PRIVATE).edit().clear().commit();
        state=new ProofState(context);
        state.plex.store.preferences().edit().putString("cipher","test-fixture-not-a-credential").putLong("generation",1).commit();
        ReflectionHelpers.setField(state.plex,"session",new PlexApi.Session("2","Child","ACCOUNT","server","SERVER","https://media.example",1));
        ReflectionHelpers.setField(state.plex,"validatedAt",android.os.SystemClock.elapsedRealtime());
        state.plexShow(SHOW,"Show",PlexLibrary.art(SHOW));
    }
    @After public void close() { state.worker.shutdownNow(); state.credentials.shutdownNow(); state.plexImages.close(); }
    private static String episode(int id,int season,int number,int views,int offset) {
        return "{\"ratingKey\":"+id+",\"type\":\"episode\",\"title\":\"Episode "+number+"\",\"grandparentTitle\":\"Show\",\"grandparentRatingKey\":\"10\",\"parentRatingKey\":\"20\",\"parentIndex\":"+season+",\"index\":"+number+",\"duration\":1200000,\"viewCount\":"+views+",\"viewOffset\":"+offset+"}";
    }
    private PlexLibrary.Target resolve(int leaves,int watched,String onDeck,String all,String exact) throws Exception {
        PlexApi api=new PlexApi("fixture",(method,uri,headers,body)->{
            String rows;
            if(uri.getPath().startsWith("/hubs/")) return new PlexApi.Reply(200,"{\"MediaContainer\":{\"Metadata\":[]}}");
            if(uri.getPath().endsWith("allLeaves")) rows=all;
            else if(uri.getPath().equals("/library/metadata/10")) rows="{\"ratingKey\":10,\"type\":\"show\",\"leafCount\":"+leaves+",\"viewedLeafCount\":"+watched+(onDeck==null?"":",\"OnDeck\":{\"Metadata\":["+onDeck+"]}")+"}";
            else rows=exact;
            return new PlexApi.Reply(200,"{\"MediaContainer\":{\"Metadata\":["+rows+"],\"totalSize\":3}}");
        });
        api.guard(state.plex.store::checkConnection); ReflectionHelpers.setField(state.plex,"api",api);
        AtomicReference<PlexLibrary.Target> result=new AtomicReference<>(); state.plex.target(SHOW,result::set);
        long deadline=System.nanoTime()+5_000_000_000L;
        while(result.get()==null && System.nanoTime()<deadline) { Shadows.shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5); }
        assertNotNull(result.get()); return result.get();
    }
    @Test public void freshStartSkipsSpecialsAndNamesFirstRegularEpisode() throws Exception {
        String special=episode(41,0,1,0,0),first=episode(42,1,1,0,0);
        var t=resolve(3,0,null,special+","+first+","+episode(43,1,2,0,0),first);
        assertEquals("Start",t.action()); assertTrue(t.video().id.endsWith(":42")); assertTrue(t.video().title.contains("S1 E1"));
    }
    @Test public void fullyWatchedShowsStartAgainWithoutChangingPlexWatchedState() throws Exception {
        String first=episode(42,1,1,2,0); var t=resolve(2,2,null,first+","+episode(43,1,2,1,0),first);
        assertEquals("Start again",t.action()); assertTrue(t.video().id.endsWith(":42"));
    }
    @Test public void explicitPlexResumeMayContinueASpecial() throws Exception {
        String special=episode(41,0,1,0,65000); var t=resolve(3,1,special,"",special);
        assertEquals("Continue",t.action()); assertTrue(t.video().id.endsWith(":41"));
    }
    @Test public void blockedEpisodeCannotBecomeTheStartTarget() throws Exception {
        state.blockVideo(MediaKey.key("2","server","episode","42"));
        String second=episode(43,1,2,0,0); var t=resolve(2,0,null,episode(42,1,1,0,0)+","+second,second);
        assertTrue(t.video().id.endsWith(":43"));
    }
}
