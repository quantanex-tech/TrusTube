// SPDX-License-Identifier: GPL-3.0-or-later
package dev.trustedvideos.proof.core;

import java.time.LocalDate;
import java.util.List;

/** Immutable daily allowances; -1 is unlimited, 0 means no time that day. */
public final class TimerSchedule {
    public enum Type { WATCHING, APP }
    public final String id, name;
    public final Type type;
    public final boolean enabled;
    private final int[] minutes; // Monday first

    public TimerSchedule(String id, String name, Type type, boolean enabled, int[] minutes) {
        if (id == null || id.isBlank() || name == null || name.trim().isEmpty() || name.length() > 60
                || type == null || minutes.length != 7) throw new IllegalArgumentException("Name your timer (1–60 characters).");
        for (int value : minutes) validate(value);
        this.id = id; this.name = name.trim(); this.type = type; this.enabled = enabled; this.minutes = minutes.clone();
    }
    public static void validate(int minutes) {
        if (minutes < -1 || minutes > 1440) throw new IllegalArgumentException("Use a limit from 0 minutes to 24 hours.");
    }
    public int minutes(int day) { return minutes[day]; }
    public int[] days() { return minutes.clone(); }
    public TimerSchedule enabled(boolean value) { return new TimerSchedule(id, name, type, value, minutes); }
    public static int allowance(List<TimerSchedule> schedules, Type type, LocalDate date) {
        int result = -1;
        for (TimerSchedule schedule : schedules) {
            int value = schedule.minutes(date.getDayOfWeek().getValue() - 1);
            if (schedule.enabled && schedule.type == type && value >= 0)
                result = result < 0 ? value : Math.min(result, value);
        }
        return result;
    }
}
