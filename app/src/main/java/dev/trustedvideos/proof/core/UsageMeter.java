// SPDX-License-Identifier: GPL-3.0-or-later
package dev.trustedvideos.proof.core;

/** Uses monotonic time; each interval belongs to the state recorded at its start. */
public final class UsageMeter {
    private long last = -1, foregroundMs, playingMs;
    private boolean foreground, playing;
    public UsageMeter(long foregroundMs, long playingMs) {
        this.foregroundMs = foregroundMs; this.playingMs = playingMs;
    }
    public synchronized void update(long now, boolean newForeground, boolean newPlaying) {
        if (last >= 0) {
            long delta = Math.max(0, now - last);
            if (foreground) foregroundMs += delta;
            if (foreground && playing) playingMs += delta;
        }
        last = now;
        foreground = newForeground;
        playing = newPlaying;
    }
    public synchronized long foregroundMs() { return foregroundMs; }
    public synchronized long playingMs() { return playingMs; }
    public synchronized void reset(long now) { last = now; foregroundMs = 0; playingMs = 0; }
    public synchronized boolean expired(boolean watchMode, long limitMs) {
        return limitMs > 0 && (watchMode ? playingMs : foregroundMs) >= limitMs;
    }
}
