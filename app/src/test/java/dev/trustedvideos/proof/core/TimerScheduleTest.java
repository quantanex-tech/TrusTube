// SPDX-License-Identifier: GPL-3.0-or-later
package dev.trustedvideos.proof.core;

import java.time.LocalDate;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;
import dev.trustedvideos.proof.core.TimerSchedule.Type;

public class TimerScheduleTest {
    private final LocalDate monday = LocalDate.of(2026, 10, 19);
    @Test public void weekdaysWeekendOverlapAndTypesUseIndependentSmallestLimits() {
        TimerSchedule weekdays = new TimerSchedule("a", "School days", Type.WATCHING, true, new int[]{30,30,30,30,30,-1,-1});
        TimerSchedule weekend = new TimerSchedule("b", "Weekend", Type.WATCHING, true, new int[]{-1,-1,-1,-1,-1,90,90});
        TimerSchedule app = new TimerSchedule("c", "App", Type.APP, true, new int[]{120,120,120,120,120,120,120});
        TimerSchedule stricter = new TimerSchedule("d", "Monday", Type.WATCHING, true, new int[]{15,-1,-1,-1,-1,-1,-1});
        List<TimerSchedule> schedules = List.of(weekdays, weekend, app, stricter);
        assertEquals(15, TimerSchedule.allowance(schedules, Type.WATCHING, monday));
        assertEquals(30, TimerSchedule.allowance(schedules, Type.WATCHING, monday.plusDays(1)));
        assertEquals(90, TimerSchedule.allowance(schedules, Type.WATCHING, monday.plusDays(5)));
        assertEquals(120, TimerSchedule.allowance(schedules, Type.APP, monday));
        assertEquals(-1, TimerSchedule.allowance(List.of(weekdays.enabled(false)), Type.WATCHING, monday));
    }
    @Test public void zeroBlocksAndScheduleOwnsItsDayArray() {
        int[] days = {0,-1,-1,-1,-1,-1,-1};
        TimerSchedule schedule = new TimerSchedule("a", "No Monday", Type.APP, true, days);
        days[0] = 60; schedule.days()[0] = 60;
        assertEquals(0, TimerSchedule.allowance(List.of(schedule), Type.APP, monday));
        assertEquals(-1, TimerSchedule.allowance(List.of(schedule), Type.APP, monday.plusDays(1)));
    }
    @Test(expected = IllegalArgumentException.class) public void oversizedLimitIsRejected() {
        new TimerSchedule("a", "Bad", Type.APP, true, new int[]{1441,1,1,1,1,1,1});
    }
}
