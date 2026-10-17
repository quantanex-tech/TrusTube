package dev.trustedvideos.proof.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class PlaybackGateTest {
    private static final String CHANNEL = "UC_x5XG1OV2P6uZZ5FSM9Ttw";
    private static final String OTHER = "UCaaaaaaaaaaaaaaaaaaaaaa";
    private final PermissionPolicy policy = new PermissionPolicy();
    private final PlaybackGate gate = new PlaybackGate(policy);
    private final VideoEntry first = new VideoEntry("M7lc1UVf-VE", CHANNEL, "c", "a", "", 60);
    private final VideoEntry next = new VideoEntry("abcdefghijk", CHANNEL, "c", "b", "", 60);

    @Test public void rejectsUnapprovedEntryBeforeExtraction() {
        assertThrows(SecurityException.class, () -> gate.begin(first, true));
    }
    @Test public void verifiesBothActualVideoAndCanonicalCreator() {
        policy.approve(CHANNEL); policy.approve(OTHER);
        PlaybackGate.Ticket ticket = gate.begin(first, true);
        assertFalse(gate.accept(ticket, next.id, CHANNEL, true));
        assertFalse(gate.accept(ticket, first.id, OTHER, true));
        assertFalse(gate.accept(ticket, first.id, "", true));
        assertTrue(gate.accept(ticket, first.id, CHANNEL, true));
    }
    @Test public void latePreviousSelectionCannotReplaceNewSelection() {
        policy.approve(CHANNEL);
        PlaybackGate.Ticket old = gate.begin(first, true);
        PlaybackGate.Ticket current = gate.begin(next, true);
        assertFalse(gate.accept(old, first.id, CHANNEL, true));
        assertTrue(gate.accept(current, next.id, CHANNEL, true));
    }
    @Test public void blockWhileExtractingAndRetryRemainDenied() {
        policy.approve(CHANNEL);
        PlaybackGate.Ticket ticket = gate.begin(first, true);
        policy.blockVideo(first.id);
        assertFalse(gate.accept(ticket, first.id, CHANNEL, true));
        assertThrows(SecurityException.class, () -> gate.begin(first, true));
        policy.restoreVideo(first.id);
        assertFalse(gate.accept(ticket, first.id, CHANNEL, true));
        PlaybackGate.Ticket retry = gate.begin(first, true);
        assertTrue(gate.accept(retry, first.id, CHANNEL, true));
    }
    @Test public void removedOrBlockedChannelDeniesPendingAndFuturePlayback() {
        policy.approve(CHANNEL);
        PlaybackGate.Ticket ticket = gate.begin(first, true);
        policy.remove(CHANNEL);
        assertFalse(gate.accept(ticket, first.id, CHANNEL, true));
        assertThrows(SecurityException.class, () -> gate.begin(first, true));
        policy.approve(CHANNEL); policy.blockChannel(CHANNEL);
        assertThrows(SecurityException.class, () -> gate.begin(first, true));
    }
    @Test public void expiryDuringExtractionAndLifecycleCancellationDenyCompletion() {
        policy.approve(CHANNEL);
        PlaybackGate.Ticket ticket = gate.begin(first, true);
        assertFalse(gate.accept(ticket, first.id, CHANNEL, false));
        gate.cancel();
        assertFalse(gate.accept(ticket, first.id, CHANNEL, true));
        assertThrows(SecurityException.class, () -> gate.begin(first, false));
    }
}
