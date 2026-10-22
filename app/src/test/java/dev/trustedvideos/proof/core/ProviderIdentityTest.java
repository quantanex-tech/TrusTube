package dev.trustedvideos.proof.core;
import org.junit.Test;
import java.util.Set;
import static org.junit.Assert.*;

public class ProviderIdentityTest {
    @Test public void grantsAreTypedChildScopedAndNeverRestoredFromSavedMetadata() {
        String show=MediaKey.key("2","server","show","10"),episode=MediaKey.key("2","server","episode","42");
        PermissionPolicy p=new PermissionPolicy(); p.restore(Set.of(show),Set.of(),Set.of());
        assertFalse(p.allows(episode,show)); p.verifyEpisode(episode); assertTrue(p.allows(episode,show));
        assertFalse(p.allows(MediaKey.key("3","server","episode","42"),show));
        assertFalse(p.allows(MediaKey.key("2","other","episode","42"),show));
        assertFalse(p.allows(show,show)); assertFalse(p.allows("42",show));
        p.clearPlex(); assertFalse(p.allows(episode,show));
    }
    @Test public void episodeAndFutureShowBlocksAreIndependentAndStaleTicketsFail() {
        String show=MediaKey.key("2","server","show","10"),id=MediaKey.key("2","server","episode","42");
        PermissionPolicy p=new PermissionPolicy(); p.approve(show); p.verifyEpisode(id);
        PlaybackGate gate=new PlaybackGate(p); var ticket=gate.begin(new VideoEntry(id,show,"Show","Episode","",60),true);
        p.blockChannel(show); assertFalse(gate.accept(ticket,id,show,true));
        String future=MediaKey.key("2","server","episode","43"); p.verifyEpisode(future); assertFalse(p.allows(future,show));
        p.restoreChannel(show); p.blockVideo(id); assertFalse(p.allows(id,show)); assertTrue(p.allows(future,show));
    }
    @Test public void malformedKeysCannotCollideWithYouTubeIdsOrInventProviders() {
        for(String s:new String[]{"plex:2:server:movie:42","plex:2:server:episode:../42","plex:admin:server:episode:42","plex:2:a:b:episode:42"}) assertNull(MediaKey.plex(s));
        assertTrue(MediaKey.playable("abcdefghijk")); assertNull(MediaKey.plex("abcdefghijk"));
    }
}
