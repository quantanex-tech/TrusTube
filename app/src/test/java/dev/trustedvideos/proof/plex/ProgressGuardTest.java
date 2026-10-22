package dev.trustedvideos.proof.plex;
import org.junit.Test;
import static org.junit.Assert.*;
import static dev.trustedvideos.proof.plex.ProgressGuard.Decision.*;

public class ProgressGuardTest {
    @Test public void noDecodedPlaybackCannotWriteAndSeekTargetsNeedAcceptedReports() {
        ProgressGuard g=new ProgressGuard(1000,0); assertEquals(NO_PLAY,g.observe(1000,0));
        g.played(); assertEquals(WRITE,g.observe(1000,0)); g.accepted(2000);
        assertEquals(WRITE,g.observe(2000,0)); assertEquals(CONFLICT,g.observe(100000,0));
        assertEquals(CONFLICT,g.observe(2000,0));
    }
    @Test public void serverCompletionAtAnyThresholdOrCreditsIsTerminalAndCannotBeOverwritten() {
        for(long position:new long[]{900_000,600_000,300_000}) {
            ProgressGuard g=new ProgressGuard(0,2); g.played(); g.accepted(position);
            assertEquals(TERMINAL,g.observe(0,3)); assertEquals(TERMINAL,g.observe(0,3));
            assertEquals(CONFLICT,g.observe(120_000,3));
        }
    }
    @Test public void externalRewindAndMarkUnwatchedStopReporting() {
        ProgressGuard g=new ProgressGuard(120000,4); g.played();
        assertEquals(CONFLICT,g.observe(10000,4));
        g=new ProgressGuard(0,4); g.played(); assertEquals(CONFLICT,g.observe(0,3));
    }
}
