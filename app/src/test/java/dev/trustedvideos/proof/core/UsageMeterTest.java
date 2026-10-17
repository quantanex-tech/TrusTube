package dev.trustedvideos.proof.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class UsageMeterTest {
    @Test public void pauseAndBufferingCountAppTimeWithoutChargingWatchTime() {
        UsageMeter meter = new UsageMeter(0, 0);
        meter.update(100, true, true);
        meter.update(1100, true, false);
        meter.update(3100, true, true);
        meter.update(4100, false, false);
        meter.update(10100, true, false);
        assertEquals(4000, meter.foregroundMs());
        assertEquals(2000, meter.playingMs());
        assertTrue(meter.expired(false, 3000));
        assertFalse(meter.expired(true, 3000));
    }
    @Test public void restoredCountsDoNotChargeTimeWhileTheAppWasClosed() {
        UsageMeter meter = new UsageMeter(3000, 2000);
        meter.update(900000, true, false);
        meter.update(901000, true, true);
        assertEquals(4000, meter.foregroundMs());
        assertEquals(2000, meter.playingMs());
    }
    @Test public void discontinuousClockNeverSubtractsUsage() {
        UsageMeter meter = new UsageMeter(0, 0);
        meter.update(1000, true, true);
        meter.update(500, false, false);
        assertEquals(0, meter.playingMs());
        meter.reset(600);
        assertEquals(0, meter.foregroundMs());
    }
}
