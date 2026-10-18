// SPDX-License-Identifier: GPL-3.0-or-later
package dev.trustedvideos.proof;

import android.content.SharedPreferences;
import android.os.SystemClock;
import dev.trustedvideos.proof.core.DailyUsage;
import dev.trustedvideos.proof.core.TimerSchedule;
import dev.trustedvideos.proof.core.TimerSchedule.Type;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Persistent local-profile schedules and usage. Rule changes never reset counters. */
public final class DailyTimers {
    private final SharedPreferences prefs;
    private final List<TimerSchedule> schedules = new ArrayList<>();
    private final DailyUsage usage;
    private final Runnable changed;
    private JSONObject overrides;
    private boolean foreground, playing;
    private int expiredState;
    private boolean unreadable;

    DailyTimers(SharedPreferences prefs, Runnable changed) {
        this.prefs = prefs; this.changed = changed;
        LocalDate day;
        try { day = LocalDate.parse(prefs.getString("dailyDate", "")); }
        catch (Exception ignored) { day = LocalDate.now(); }
        usage = new DailyUsage(day, prefs.getLong("dailyAppMs", 0), prefs.getLong("dailyWatchMs", 0));
        try {
            overrides = new JSONObject(prefs.getString("dailyOverrides", "{}"));
            for (Type type : Type.values()) {
                if (!overrides.has(type.name())) continue;
                JSONObject item = overrides.getJSONObject(type.name());
                LocalDate.parse(item.getString("date")); TimerSchedule.validate(item.getInt("minutes"));
            }
            JSONArray saved = new JSONArray(prefs.getString("dailySchedules", "[]"));
            for (int i = 0; i < saved.length(); i++) {
                JSONObject item = saved.getJSONObject(i); JSONArray days = item.getJSONArray("days");
                int[] minutes = new int[7];
                for (int d = 0; d < 7; d++) minutes[d] = days.getInt(d);
                schedules.add(new TimerSchedule(item.getString("id"), item.getString("name"),
                        Type.valueOf(item.getString("type")), item.getBoolean("enabled"), minutes));
            }
        } catch (Exception error) { unreadable = true; overrides = new JSONObject(); }
        tick(false, false);
    }
    public synchronized List<TimerSchedule> schedules() { return List.copyOf(schedules); }
    public synchronized void save(TimerSchedule schedule) {
        tick(foreground, playing);
        List<TimerSchedule> next = new ArrayList<>(schedules);
        next.removeIf(old -> old.id.equals(schedule.id)); next.add(schedule);
        persist(next, overrides); schedules.clear(); schedules.addAll(next); changed.run();
    }
    public synchronized void delete(String id) {
        List<TimerSchedule> next = new ArrayList<>(schedules); next.removeIf(item -> item.id.equals(id));
        persist(next, overrides); schedules.clear(); schedules.addAll(next); changed.run();
    }
    public synchronized boolean hasToday(Type type) {
        JSONObject item = overrides.optJSONObject(type.name());
        return item != null && usage.day().toString().equals(item.optString("date"));
    }
    public synchronized void today(Type type, Integer minutes) {
        tick(foreground, playing);
        try {
            JSONObject next = new JSONObject(overrides.toString());
            if (minutes == null) next.remove(type.name());
            else {
                TimerSchedule.validate(minutes);
                next.put(type.name(), new JSONObject().put("date", usage.day().toString()).put("minutes", minutes));
            }
            persist(schedules, next); overrides = next; changed.run();
        } catch (org.json.JSONException error) { throw new IllegalStateException("Could not save today's limit.", error); }
    }
    private void persist(List<TimerSchedule> next, JSONObject nextOverrides) {
        try {
            JSONArray array = new JSONArray();
            for (TimerSchedule item : next) {
                JSONArray days = new JSONArray(); for (int value : item.days()) days.put(value);
                array.put(new JSONObject().put("id", item.id).put("name", item.name).put("type", item.type.name())
                        .put("enabled", item.enabled).put("days", days));
            }
            if (!prefs.edit().putString("dailySchedules", array.toString())
                    .putString("dailyOverrides", nextOverrides.toString()).commit())
                throw new IllegalStateException("The timer could not be saved. Check available storage.");
            unreadable = false;
        } catch (org.json.JSONException error) { throw new IllegalStateException("The timer could not be saved.", error); }
    }
    public synchronized void tick(boolean foreground, boolean playing) {
        tick(System.currentTimeMillis(), SystemClock.elapsedRealtime(), ZoneId.systemDefault(), foreground, playing);
    }
    // Clock arguments keep midnight/accounting behavior directly verifiable on the host.
    synchronized void tick(long wallMs, long elapsedMs, ZoneId zone, boolean foreground, boolean playing) {
        LocalDate previous = usage.day();
        usage.update(wallMs, elapsedMs, zone, foreground, playing);
        this.foreground = foreground; this.playing = playing;
        prefs.edit().putString("dailyDate", usage.day().toString())
                .putLong("dailyAppMs", usage.used(Type.APP)).putLong("dailyWatchMs", usage.used(Type.WATCHING)).apply();
        int expired = (expired(Type.APP) ? 1 : 0) | (expired(Type.WATCHING) ? 2 : 0);
        if (expired != expiredState || !previous.equals(usage.day())) { expiredState = expired; changed.run(); }
    }
    public synchronized int allowance(Type type) {
        if (unreadable) return 0; // Parent can replace malformed settings; never silently grant unlimited time.
        if (hasToday(type)) return overrides.optJSONObject(type.name()).optInt("minutes", 0);
        return TimerSchedule.allowance(schedules, type, usage.day());
    }
    public synchronized long used(Type type) { return usage.used(type); }
    public synchronized long remaining(Type type) {
        int limit = allowance(type);
        return limit < 0 ? -1 : Math.max(0, limit * 60000L - usage.used(type));
    }
    public synchronized boolean expired(Type type) { return remaining(type) == 0; }
    public synchronized String remainingLabel(Type type) {
        long remaining = remaining(type);
        return remaining < 0 ? "No daily limit" : Ui.duration(remaining) + " left today";
    }
    public synchronized boolean unreadable() { return unreadable; }
}
