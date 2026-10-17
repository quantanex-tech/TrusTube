// SPDX-License-Identifier: GPL-3.0-or-later
package dev.trustedvideos.proof.core;

/** Tickets bind an asynchronous extraction to the requested identity and current permissions. */
public final class PlaybackGate {
    private final PermissionPolicy policy;
    private long sequence;
    public PlaybackGate(PermissionPolicy policy) { this.policy = policy; }

    public static final class Ticket {
        public final VideoEntry entry;
        private final long sequence, revision;
        private Ticket(VideoEntry entry, long sequence, long revision) {
            this.entry = entry; this.sequence = sequence; this.revision = revision;
        }
    }
    public synchronized Ticket begin(VideoEntry entry, boolean timeAvailable) {
        sequence++;
        if (!timeAvailable || entry == null || !policy.allows(entry.id, entry.channelId)) {
            throw new SecurityException("This video is not currently permitted.");
        }
        return new Ticket(entry, sequence, policy.revision());
    }
    public synchronized boolean accept(Ticket ticket, String actualVideo, String actualChannel,
                                       boolean timeAvailable) {
        return ticket != null && timeAvailable && ticket.sequence == sequence
                && ticket.revision == policy.revision()
                && ticket.entry.id.equals(actualVideo)
                && ticket.entry.channelId.equals(actualChannel)
                && policy.allows(actualVideo, actualChannel);
    }
    public synchronized void cancel() { sequence++; }
}
