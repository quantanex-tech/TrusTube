// SPDX-License-Identifier: GPL-3.0-or-later
package dev.trustedvideos.proof;

import android.content.SharedPreferences;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;

/** Small device-local checkpoints, independent of the much larger catalog preferences. */
final class WatchProgress {
    static final int LIMIT = 200;
    static final long SAVE_INTERVAL_MS = 15000;
    static final class Entry {
        final long positionMs, durationMs;
        Entry(long positionMs, long durationMs) { this.positionMs = positionMs; this.durationMs = durationMs; }
        double fraction() { return (double) positionMs / durationMs; }
    }
    private final SharedPreferences prefs;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    WatchProgress(SharedPreferences prefs) {
        this.prefs = prefs;
        try {
            JSONArray rows = new JSONArray(prefs.getString("entries", "[]"));
            for (int i = Math.max(0, rows.length() - LIMIT); i < rows.length(); i++) {
                JSONArray row = rows.optJSONArray(i);
                if (row == null || row.length() != 3) continue;
                String id = row.optString(0);
                long position = row.optLong(1, -1), duration = row.optLong(2, -1);
                if (id.matches("[A-Za-z0-9_-]{11}") && unfinished(position, duration))
                    entries.put(id, new Entry(position, duration));
            }
        } catch (Exception ignored) { /* A damaged checkpoint must not affect browsing. */ }
    }
    Entry get(String id) { return entries.get(id); }
    private static boolean unfinished(long position, long duration) {
        return position >= 5000 && duration > 0 && position < duration - Math.min(10000, duration / 50);
    }
    void save(String id, long position, long duration, boolean ended) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{11}")) return;
        // Unknown/live/loading positions cannot erase an existing checkpoint.
        if (!ended && (duration <= 0 || position < 0)) return;
        Entry previous = entries.get(id);
        if (ended || !unfinished(position, duration)) {
            if (entries.remove(id) == null) return;
        } else {
            if (previous != null && previous.positionMs == position && previous.durationMs == duration) return;
            entries.remove(id);
            entries.put(id, new Entry(position, duration));
            while (entries.size() > LIMIT) entries.remove(entries.keySet().iterator().next());
        }
        JSONArray rows = new JSONArray();
        entries.forEach((key, value) -> rows.put(new JSONArray().put(key).put(value.positionMs).put(value.durationMs)));
        prefs.edit().putString("entries", rows.toString()).apply();
    }
}
