// SPDX-License-Identifier: GPL-3.0-or-later
package dev.trustedvideos.proof.core;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** Monotonic foreground/playing accounting, divided at local midnight. */
public final class DailyUsage {
    private LocalDate day;
    private long appMs, watchMs, lastElapsed = -1;
    private boolean foreground, playing;

    public DailyUsage(LocalDate day, long appMs, long watchMs) {
        this.day = day; this.appMs = Math.max(0, appMs); this.watchMs = Math.max(0, watchMs);
    }
    public synchronized void update(long wallMs, long elapsedMs, ZoneId zone, boolean newForeground, boolean newPlaying) {
        LocalDate today = Instant.ofEpochMilli(wallMs).atZone(zone).toLocalDate();
        long delta = lastElapsed < 0 ? 0 : Math.max(0, elapsedMs - lastElapsed);
        if (!today.equals(day)) {
            day = today; appMs = 0; watchMs = 0;
            long sinceMidnight = Math.max(0, wallMs - today.atStartOfDay(zone).toInstant().toEpochMilli());
            delta = Math.min(delta, sinceMidnight);
        }
        if (foreground) {
            appMs += delta;
            if (playing) watchMs += delta;
        }
        lastElapsed = elapsedMs; foreground = newForeground; playing = newPlaying;
    }
    public synchronized LocalDate day() { return day; }
    public synchronized long used(TimerSchedule.Type type) { return type == TimerSchedule.Type.WATCHING ? watchMs : appMs; }
}
