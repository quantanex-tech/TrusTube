// SPDX-License-Identifier: GPL-3.0-or-later
package dev.trustedvideos.proof.plex;

import java.util.ArrayDeque;
import java.util.Deque;

/** Conservative observation, not an atomic compare-and-set. Server completion always wins. */
public final class ProgressGuard {
    public enum Decision { WRITE, NO_PLAY, TERMINAL, CONFLICT }
    private final Deque<Long> accepted=new ArrayDeque<>();
    private final int views;
    private boolean played,terminal,conflict;
    public ProgressGuard(long offset,int views) { accepted.add(offset); this.views=views; }
    public synchronized void played() { played=true; }
    public synchronized Decision observe(long remoteOffset,int remoteViews) {
        if(conflict) return Decision.CONFLICT;
        // PMS decides played status using its configured threshold/credits. Never undo
        // a cleared resume point, regardless of which client caused the transition.
        if(remoteViews>views && remoteOffset==0) { terminal=true; return Decision.TERMINAL; }
        if(terminal || remoteViews!=views) { conflict=true; return Decision.CONFLICT; }
        for(long position:accepted) if(Math.abs(position-remoteOffset)<=3000) {
            // Once PMS confirms a newer checkpoint, retire older offsets so a later
            // rewind cannot hide behind the original start position indefinitely.
            while(accepted.size()>1 && accepted.peekFirst()!=position) accepted.removeFirst();
            return played?Decision.WRITE:Decision.NO_PLAY;
        }
        conflict=true; return Decision.CONFLICT;
    }
    public synchronized void accepted(long offset) {
        accepted.add(offset); while(accepted.size()>12) accepted.remove();
    }
}
