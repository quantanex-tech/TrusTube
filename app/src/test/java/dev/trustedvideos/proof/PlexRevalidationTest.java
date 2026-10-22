package dev.trustedvideos.proof;

import android.content.Context;
import android.os.Looper;
import android.os.SystemClock;
import dev.trustedvideos.proof.core.MediaKey;
import dev.trustedvideos.proof.core.VideoEntry;
import dev.trustedvideos.proof.plex.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28,shadows=PlexRevalidationTest.SavedStore.class) @LooperMode(LooperMode.Mode.PAUSED)
public class PlexRevalidationTest {
    @Implements(value=PlexStore.class,isInAndroidSdk=false)
    public static class SavedStore {
        static PlexApi.Session value;
        @Implementation protected PlexApi.Session load() { return value; }
    }
    private ProofState state;
    private final PlexApi.Session saved=new PlexApi.Session("2","Child","ACCOUNT","server","SERVER","https://media.example",1);
    private final String show=MediaKey.key("2","server","show","10"),episode=MediaKey.key("2","server","episode","42");
    @Before public void setup() {
        Context c=RuntimeEnvironment.getApplication();
        for(String name:List.of("proof","plex-connection-v1")) c.getSharedPreferences(name,Context.MODE_PRIVATE).edit().clear().commit();
        state=new ProofState(c);
        SavedStore.value=saved;
        PlexStore store=state.plex.store;
        store.preferences().edit().putString("cipher","test-fixture-not-a-credential").putLong("generation",1).commit();
        ReflectionHelpers.setField(state.plex,"session",saved);
        ReflectionHelpers.setField(state.plex,"validatedAt",SystemClock.elapsedRealtime()-70_000);
        state.plexShow(show,"Show",PlexLibrary.art(show));
        state.plexEpisode(new VideoEntry(episode,show,"Show","Episode","",120));
    }
    @After public void close() { state.worker.shutdownNow(); state.credentials.shutdownNow(); state.plexImages.close(); }
    private void api(PlexApi.Transport transport) {
        PlexApi api=new PlexApi("fixture",transport); api.guard(state.plex.store::checkConnection);
        ReflectionHelpers.setField(state.plex,"api",api);
    }
    private void settle() throws Exception {
        long deadline=System.nanoTime()+5_000_000_000L;
        while(state.plex.loading() && System.nanoTime()<deadline) { Shadows.shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5); }
        assertFalse("Connection check must finish",state.plex.loading());
    }
    @Test public void idleExpiryHidesOldGrantsThenAutomaticallyRevalidatesTheSavedChild() throws Exception {
        List<String> requests=new ArrayList<>();
        api((method,uri,h,body)->{
            requests.add(uri.getPath()+":"+h.getOrDefault("X-Plex-Token","NONE"));
            return switch(uri.getPath()) {
                case "/api/v2/user" -> new PlexApi.Reply(200,"{\"id\":2,\"restricted\":true}");
                case "/identity" -> new PlexApi.Reply(200,"{\"MediaContainer\":{\"machineIdentifier\":\"server\"}}");
                case "/library/sections" -> new PlexApi.Reply(200,"{\"MediaContainer\":{\"Directory\":[{\"key\":1,\"type\":\"show\"}]}}");
                case "/library/sections/1/all" -> new PlexApi.Reply(200,"{\"MediaContainer\":{\"Metadata\":[{\"ratingKey\":10,\"type\":\"show\",\"title\":\"Show\"}],\"totalSize\":1}}");
                default -> throw new AssertionError(uri.getPath());
            };
        });
        state.plex.checkExpiry(); assertNull(state.plex.session()); assertFalse(state.policy.allows(episode,show));
        state.plex.refresh(false); settle();
        assertEquals(saved,state.plex.session()); assertNotNull(state.plex.show(show));
        // Discovering the show cannot resurrect an old episode grant without exact metadata.
        assertFalse(state.policy.allows(episode,show));
        assertEquals(List.of("/api/v2/user:ACCOUNT","/identity:NONE","/library/sections:SERVER","/library/sections/1/all:SERVER"),requests);
    }
    @Test public void deniedSavedChildStaysHiddenAndRequiresExplicitParentRetry() throws Exception {
        AtomicInteger requests=new AtomicInteger();
        api((method,uri,h,body)->{ requests.incrementAndGet(); assertEquals("ACCOUNT",h.get("X-Plex-Token")); return new PlexApi.Reply(401,""); });
        state.plex.checkExpiry(); state.plex.refresh(false); settle();
        assertNull(state.plex.session()); assertFalse(state.policy.allows(episode,show));
        state.plex.refresh(false); assertFalse(state.plex.loading()); assertEquals(1,requests.get());
        state.plex.refresh(true); settle(); assertEquals(2,requests.get());
    }
    @Test public void backgroundPlayerRefreshesChildAndPublicIdentityBeforeUsingOldSession() throws Exception {
        List<String> requests=new ArrayList<>();
        api((method,uri,h,body)->{
            requests.add(uri.getPath()+":"+h.getOrDefault("X-Plex-Token","NONE"));
            return new PlexApi.Reply(200,uri.getPath().equals("/api/v2/user")?"{\"id\":2,\"restricted\":true}":"{\"MediaContainer\":{\"machineIdentifier\":\"server\"}}");
        });
        state.plex.revalidate(saved);
        assertEquals(List.of("/api/v2/user:ACCOUNT","/identity:NONE"),requests);
        state.plex.revalidate(saved); assertEquals(2,requests.size());
        state.plex.store.disconnect(); assertThrows(IOException.class,()->state.plex.revalidate(saved));
        assertEquals(2,requests.size());
    }
}
