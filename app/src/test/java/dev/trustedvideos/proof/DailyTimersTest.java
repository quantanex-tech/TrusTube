// SPDX-License-Identifier: GPL-3.0-or-later
package dev.trustedvideos.proof;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import dev.trustedvideos.proof.core.TimerSchedule;
import dev.trustedvideos.proof.core.TimerSchedule.Type;
import java.time.ZoneId;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk = 23)
public class DailyTimersTest {
    private SharedPreferences prefs() {
        SharedPreferences prefs = RuntimeEnvironment.getApplication().getSharedPreferences("timer-test", Context.MODE_PRIVATE);
        prefs.edit().clear().commit(); return prefs;
    }
    private TimerSchedule schedule(Type type) {
        return new TimerSchedule(type.name(), type.name(), type, true, new int[]{30,30,30,30,30,30,30});
    }
    @Test public void todayOverridesReplaceScheduleExpireAndRestoreUnlimitedWhenNoScheduleExists() {
        SharedPreferences prefs = prefs(); DailyTimers timers = new DailyTimers(prefs, () -> {});
        timers.save(schedule(Type.WATCHING)); timers.today(Type.WATCHING, 60); timers.today(Type.APP, 15);
        assertEquals(60, timers.allowance(Type.WATCHING)); assertEquals(15, timers.allowance(Type.APP));
        long next = java.time.LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()+1000;
        timers.tick(next, SystemClock.elapsedRealtime()+1000, ZoneId.systemDefault(), false, false);
        assertEquals(30, timers.allowance(Type.WATCHING)); assertEquals(-1, timers.allowance(Type.APP));
        assertFalse(timers.hasToday(Type.APP));
    }
    @Test public void unlimitedTodayIsExplicitAndCanBeRemovedWithoutEditingWeeklyRules() {
        DailyTimers timers = new DailyTimers(prefs(), () -> {}); timers.save(schedule(Type.APP));
        timers.today(Type.APP, -1); assertEquals(-1, timers.allowance(Type.APP));
        timers.today(Type.APP, null); assertEquals(30, timers.allowance(Type.APP));
        assertEquals(30, timers.schedules().get(0).minutes(0));
    }
    @Test public void limitsAndUsageSurviveRestartAndEditsNeverResetSpentTime() {
        SharedPreferences prefs = prefs(); DailyTimers timers = new DailyTimers(prefs, () -> {});
        timers.save(schedule(Type.WATCHING)); timers.save(schedule(Type.APP));
        long wall = System.currentTimeMillis(), elapsed = SystemClock.elapsedRealtime();
        timers.tick(wall, elapsed, ZoneId.systemDefault(), true, true);
        timers.tick(wall+60000, elapsed+60000, ZoneId.systemDefault(), false, false);
        assertEquals(29*60000L, timers.remaining(Type.WATCHING));
        timers.save(schedule(Type.APP).enabled(false));
        assertEquals(60000, timers.used(Type.APP));
        DailyTimers restored = new DailyTimers(prefs, () -> {});
        assertEquals(2, restored.schedules().size()); assertEquals(60000, restored.used(Type.WATCHING));
        assertEquals(29*60000L, restored.remaining(Type.WATCHING)); assertEquals(-1, restored.remaining(Type.APP));
        restored.delete(Type.APP.name()); assertEquals(1, new DailyTimers(prefs, () -> {}).schedules().size());
    }
    @Test public void bothExpiriesNotifyEvenWhenOtherTypeAlreadyExpired() {
        int[] notifications = {0}; DailyTimers timers = new DailyTimers(prefs(), () -> notifications[0]++);
        timers.today(Type.WATCHING, 0); timers.today(Type.APP, 1);
        long wall = System.currentTimeMillis(), elapsed = SystemClock.elapsedRealtime();
        timers.tick(wall, elapsed, ZoneId.systemDefault(), true, false); int before = notifications[0];
        timers.tick(wall+60000, elapsed+60000, ZoneId.systemDefault(), true, false);
        assertTrue(timers.expired(Type.WATCHING)); assertTrue(timers.expired(Type.APP)); assertTrue(notifications[0]>before);
    }
    @Test public void malformedSavedRulesFailClosed() {
        SharedPreferences prefs = prefs(); prefs.edit().putString("dailySchedules", "not-json").commit();
        DailyTimers timers = new DailyTimers(prefs, () -> {}); assertTrue(timers.unreadable()); assertTrue(timers.expired(Type.APP));
        timers.save(schedule(Type.APP)); assertFalse(timers.unreadable()); assertFalse(timers.expired(Type.APP));
    }
}
