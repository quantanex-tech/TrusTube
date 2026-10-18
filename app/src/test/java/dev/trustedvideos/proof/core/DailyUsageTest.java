// SPDX-License-Identifier: GPL-3.0-or-later
package dev.trustedvideos.proof.core;

import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.Test;
import static org.junit.Assert.*;
import dev.trustedvideos.proof.core.TimerSchedule.Type;

public class DailyUsageTest {
    private final ZoneId zone = ZoneId.of("Europe/Berlin");
    private final LocalDate date = LocalDate.of(2026, 10, 18);
    private long start(LocalDate day) { return day.atStartOfDay(zone).toInstant().toEpochMilli(); }
    @Test public void pausesBufferingAndBackgroundDoNotSpendWatchingTime() {
        DailyUsage usage = new DailyUsage(date, 0, 0); long wall = start(date) + 3600000;
        usage.update(wall, 0, zone, true, true);
        usage.update(wall+10000, 10000, zone, true, false);
        usage.update(wall+20000, 20000, zone, false, false);
        usage.update(wall+90000, 90000, zone, true, false);
        usage.update(wall+100000, 100000, zone, false, false);
        assertEquals(10000, usage.used(Type.WATCHING)); assertEquals(30000, usage.used(Type.APP));
    }
    @Test public void foregroundIntervalAcrossMidnightOnlyChargesNewDaysPortion() {
        DailyUsage usage = new DailyUsage(date, 90000, 50000); long midnight = start(date.plusDays(1));
        usage.update(midnight-500, 100, zone, true, true);
        usage.update(midnight+500, 1100, zone, true, false);
        assertEquals(date.plusDays(1), usage.day());
        assertEquals(500, usage.used(Type.APP)); assertEquals(500, usage.used(Type.WATCHING));
    }
    @Test public void processRestartAndOvernightBackgroundDoNotInventUsage() {
        DailyUsage usage = new DailyUsage(date, 10000, 8000);
        usage.update(start(date)+3600000, 100, zone, false, false);
        assertEquals(10000, usage.used(Type.APP));
        usage.update(start(date.plusDays(2))+5000, 100000000, zone, true, true);
        assertEquals(0, usage.used(Type.APP)); assertEquals(0, usage.used(Type.WATCHING));
    }
    @Test public void daylightSavingDayUsesLocalMidnightAndMonotonicTime() {
        LocalDate autumn = LocalDate.of(2026, 10, 25); // 25-hour local day
        DailyUsage usage = new DailyUsage(autumn, 0, 0); long midnight = start(autumn.plusDays(1));
        usage.update(midnight-2000, 0, zone, true, true);
        usage.update(midnight+1000, 3000, zone, false, false);
        assertEquals(1000, usage.used(Type.WATCHING));
    }
}
