package dev.trustedvideos.proof;

import android.content.Context;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import dev.trustedvideos.proof.core.*;
import dev.trustedvideos.proof.plex.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28,shadows=PlexHomeEpisodesTest.SavedStore.class) @LooperMode(LooperMode.Mode.PAUSED)
public class PlexHomeEpisodesTest {
    @Implements(value=PlexStore.class,isInAndroidSdk=false)
    public static class SavedStore {
        static PlexApi.Session value;
        @Implementation protected PlexApi.Session load() { return value; }
    }
    private final List<ProofState> states=new ArrayList<>();
    private final List<String> episodeRequests=new CopyOnWriteArrayList<>();
    private final List<String> metadataRequests=new CopyOnWriteArrayList<>();
    private final Map<Integer,String> metadataRows=new java.util.concurrent.ConcurrentHashMap<>();
    private int episodeTotal=8;
    private final PlexApi.Session saved=new PlexApi.Session("2","Child","ACCOUNT","server","SERVER","https://media.example",1);
    private ProofState state;
    private boolean wrongShow;
    private static String show(int id) { return MediaKey.key("2","server","show",String.valueOf(id)); }
    private static String episode(int id) { return MediaKey.key("2","server","episode",String.valueOf(id)); }
    @Before public void setup() {
        Context c=RuntimeEnvironment.getApplication();
        for(String name:List.of("proof","plex-connection-v1","watch-progress")) c.getSharedPreferences(name,Context.MODE_PRIVATE).edit().clear().commit();
        SavedStore.value=saved; state=create();
        state.plex.store.preferences().edit().putString("cipher","test-fixture-not-a-credential").putLong("generation",1).commit();
    }
    private ProofState create() {
        ProofState s=new ProofState(RuntimeEnvironment.getApplication()); states.add(s);
        PlexApi api=new PlexApi("fixture",(method,uri,h,body)->{
            if(uri.getPath().equals("/api/v2/user")) { assertEquals("ACCOUNT",h.get("X-Plex-Token")); return new PlexApi.Reply(200,"{\"id\":2,\"restricted\":true}"); }
            if(uri.getPath().equals("/identity")) { assertNull(h.get("X-Plex-Token")); return new PlexApi.Reply(200,"{\"MediaContainer\":{\"machineIdentifier\":\"server\"}}"); }
            assertEquals("SERVER",h.get("X-Plex-Token"));
            if(uri.getPath().equals("/library/sections")) return new PlexApi.Reply(200,"{\"MediaContainer\":{\"Directory\":[{\"key\":1,\"type\":\"show\"}]}}");
            if(uri.getPath().equals("/library/sections/1/all")) {
                List<String> shows=new ArrayList<>(); for(int i=10;i<22;i++) shows.add("{\"ratingKey\":"+i+",\"type\":\"show\",\"title\":\"Show "+i+"\"}");
                return rows(shows,12);
            }
            String value=uri.getPath().substring("/library/metadata/".length());
            if(value.endsWith("/allLeaves")) {
                int id=Integer.parseInt(value.split("/")[0]); int offset=query(uri.getQuery(),"X-Plex-Container-Start");
                assertEquals(PlexLibrary.HOME_EPISODE_PAGE,query(uri.getQuery(),"X-Plex-Container-Size"));
                episodeRequests.add(id+":"+offset); List<String> entries=new ArrayList<>();
                for(int n=offset;n<Math.min(offset+4,episodeTotal);n++) entries.add(entry(id*100+n,wrongShow?99:id,1,n+1));
                return rows(entries,episodeTotal);
            }
            List<String> entries=new ArrayList<>(); for(String id:value.split(",")) { metadataRequests.add(id); int n=Integer.parseInt(id); entries.add(metadataRows.getOrDefault(n,entry(n,n/100,1,n%100+1))); }
            return rows(entries,entries.size());
        });
        api.guard(s.plex.store::checkConnection); ReflectionHelpers.setField(s.plex,"api",api); return s;
    }
    private static int query(String query,String key) { for(String part:query.split("&")) if(part.startsWith(key+"=")) return Integer.parseInt(part.substring(key.length()+1)); throw new AssertionError(key); }
    private static String entry(int id,int show,int season,int number) { return "{\"ratingKey\":"+id+",\"type\":\"episode\",\"title\":\"Episode "+number+"\",\"grandparentTitle\":\"Show "+show+"\",\"grandparentRatingKey\":"+show+",\"parentRatingKey\":500,\"parentIndex\":"+season+",\"index\":"+number+",\"duration\":120000}"; }
    private static PlexApi.Reply rows(List<String> values,int total) { return new PlexApi.Reply(200,"{\"MediaContainer\":{\"Metadata\":["+String.join(",",values)+"],\"totalSize\":"+total+"}}"); }
    private void settle(ProofState s) throws Exception {
        long deadline=System.nanoTime()+10_000_000_000L;
        while(s.plex.loading() && System.nanoTime()<deadline) { Shadows.shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5); }
        Shadows.shadowOf(Looper.getMainLooper()).idle(); assertFalse("Plex feed must finish",s.plex.loading());
    }
    @After public void close() { for(ProofState s:states) { s.worker.shutdownNow(); s.credentials.shutdownNow(); s.plexImages.close(); } }
    @Test public void homeDiscoversIndividualEpisodesInBoundedPagesAcrossShowsAndPreservesRestartOrder() throws Exception {
        state.plex.refresh(true); settle(state);
        List<String> first=state.homeMix(false); assertEquals(40,first.size());
        assertTrue(first.stream().allMatch(id->MediaKey.plex(id).type().equals("episode")));
        assertEquals(10,episodeRequests.size()); assertEquals(10,first.stream().map(id->state.find(id).channelId).distinct().count());
        state.plex.loadHomeMore(); settle(state); List<String> more=state.homeMix(false);
        assertEquals(80,more.size()); assertEquals(first,more.subList(0,first.size()));
        assertEquals(List.of("20:0","21:0"),episodeRequests.subList(10,12));
        ProofState restored=create(); assertTrue(restored.homeMix(false).isEmpty());
        restored.plex.refresh(true); settle(restored);
        assertEquals(more,restored.homeMix(false));
        state.blockVideo(first.get(0)); assertFalse(state.homeMix(false).contains(first.get(0)));
        String blockedShow=state.find(first.get(1)).channelId; state.blockChannel(blockedShow);
        assertTrue(state.homeMix(false).stream().noneMatch(id->state.find(id).channelId.equals(blockedShow)));
        state.plex.disconnect(); assertTrue(state.homeMix(false).isEmpty());
    }
    @Test public void idleBeforeOpeningHomeStillReloadsEpisodeCandidates() throws Exception {
        state.plex.refresh(true); settle(state); assertEquals(10,episodeRequests.size());
        ReflectionHelpers.setField(state.plex,"validatedAt",SystemClock.elapsedRealtime()-70_000);
        state.plex.checkExpiry(); assertNull(state.plex.session());
        state.plex.refresh(false); settle(state);
        assertEquals(40,state.homeMix(false).size()); assertEquals(20,episodeRequests.size());
    }
    @Test public void favoriteSpecialDoesNotStrandUnsavedHomePagesAfterRefresh() throws Exception {
        state.plex.refresh(true); settle(state);
        PlexApi.Entry special=new PlexApi.Entry("1999","Special","episode","Show 19",0,1,120000,0,0,"","19","500","",0,0,0,"","",List.of(),null);
        metadataRows.put(1999,entry(1999,19,0,1)); state.plex.observed(saved,special); state.toggleFavorite(episode(1999));
        state.plex.refresh(true); settle(state);
        assertEquals(40,state.homeMix(false).size()); assertEquals(20,episodeRequests.size());
        assertEquals(List.of(episode(1999)),state.favoriteEntries().stream().map(e->e.id).toList());
    }
    @Test public void routineRefreshBoundsHomeRestorationAndLoadMoreCanRecoverOmittedEpisodes() throws Exception {
        episodeTotal=20; state.plex.refresh(true); settle(state);
        for(int show=10;show<16;show++) for(int n=0;n<20;n++) {
            int id=show*100+n;
            state.plex.observed(saved,new PlexApi.Entry(String.valueOf(id),"Episode "+(n+1),"episode","Show "+show,1,n+1,120000,0,0,"",String.valueOf(show),"500","",0,0,0,"","",List.of(),null));
        }
        List<String> initial=state.homeMix(false); assertEquals(136,initial.size());
        String favorite=initial.get(134),history=initial.get(135); state.toggleFavorite(favorite); state.recordWatched(history);
        metadataRequests.clear(); state.plex.refresh(true); settle(state);
        assertEquals(ProofState.HOME_RESTORE_LIMIT+2,metadataRequests.size());
        assertTrue(state.policy.allows(favorite,state.find(favorite).channelId)); assertTrue(state.policy.allows(history,state.find(history).channelId));
        assertTrue(state.plex.hasMoreHome()); String omitted=initial.get(120);
        for(int n=0;n<20 && !state.policy.allows(omitted,state.find(omitted).channelId);n++) { state.plex.loadHomeMore(); settle(state); }
        assertTrue(state.homeMix(false).contains(omitted));
    }
    @Test public void oldSavedShowMixMigratesAndHomeRendersEpisodeCards() throws Exception {
        RuntimeEnvironment.getApplication().getSharedPreferences("proof",Context.MODE_PRIVATE).edit()
                .putString("homeMixDay",java.time.LocalDate.now().toString()).putString("homeMixOrder","[\""+show(10)+"\"]").commit();
        ProofState upgraded=create(); upgraded.plex.refresh(true); settle(upgraded); upgraded.setPin("582901");
        List<String> home=upgraded.homeMix(false); assertFalse(home.contains(show(10))); assertEquals(40,home.size());
        ReflectionHelpers.setField((ProofApplication)RuntimeEnvironment.getApplication(),"state",upgraded);
        try(var controller=Robolectric.buildActivity(MainActivity.class).setup()) {
            View root=controller.get().getWindow().getDecorView();
            assertNotNull(find(root,upgraded.find(home.get(0)).title)); assertNull(find(root,"Browse episodes"));
            root.findViewWithTag(MainActivity.Tab.CHANNELS).performClick(); assertNotNull(find(root,"Show 10"));
        }
    }
    @Test public void mismatchedEpisodeCannotGrantHomeAccess() throws Exception {
        wrongShow=true; state.plex.refresh(true); settle(state);
        assertNull(state.plex.session()); assertTrue(state.homeMix(false).isEmpty()); assertFalse(state.policy.allows(episode(1000),show(10)));
    }
    @Test public void specialsStayOutOfHomeAndBlockedShowsAreNotFetched() throws Exception {
        state.blockChannel(show(10)); state.plex.refresh(true); settle(state);
        assertTrue(episodeRequests.stream().noneMatch(s->s.startsWith("10:")));
        PlexApi.Entry special=new PlexApi.Entry("9999","Special","episode","Show 11",0,1,120000,0,0,"","11","500","",0,0,0,"","",List.of(),null);
        state.plex.observed(saved,special); assertFalse(state.homeMix(false).contains(episode(9999)));
    }
    private static TextView find(View v,String text) {
        if(v instanceof TextView t && t.getText().toString().equals(text)) return t;
        if(v instanceof ViewGroup g) for(int i=0;i<g.getChildCount();i++) { TextView r=find(g.getChildAt(i),text); if(r!=null) return r; }
        return null;
    }
}
