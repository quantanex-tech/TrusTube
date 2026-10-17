// SPDX-License-Identifier: GPL-3.0-or-later
package dev.trustedvideos.proof;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import dev.trustedvideos.proof.core.PermissionPolicy;
import dev.trustedvideos.proof.core.PinHash;
import dev.trustedvideos.proof.core.UsageMeter;
import dev.trustedvideos.proof.core.VideoEntry;
import dev.trustedvideos.proof.source.SourceRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ProofState {
    public final PermissionPolicy policy = new PermissionPolicy();
    public final UsageMeter meter;
    public final SourceRepository sources = new SourceRepository();
    public final ExecutorService worker = Executors.newFixedThreadPool(3);
    public final ExecutorService credentials = Executors.newSingleThreadExecutor();
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, VideoEntry> catalog = new LinkedHashMap<>();
    private final Map<String, String> channelNames = new LinkedHashMap<>();
    private final Map<String, String> channelAvatars = new LinkedHashMap<>();
    private final Map<String, Long> favorites = new LinkedHashMap<>();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private boolean expiredNotified;
    private volatile long parentEpoch;
    private volatile boolean loadingMore;

    ProofState(Context context) {
        prefs = context.getSharedPreferences("proof", Context.MODE_PRIVATE);
        policy.restore(prefs.getStringSet("channels", Set.of()), prefs.getStringSet("blockedVideos", Set.of()),
                prefs.getStringSet("blockedChannels", Set.of()));
        meter = new UsageMeter(prefs.getLong("foregroundMs", 0), prefs.getLong("playingMs", 0));
        try {
            JSONObject names = new JSONObject(prefs.getString("channelNames", "{}"));
            names.keys().forEachRemaining(id -> channelNames.put(id, names.optString(id)));
            JSONObject avatars = new JSONObject(prefs.getString("channelAvatars", "{}"));
            avatars.keys().forEachRemaining(id -> channelAvatars.put(id, avatars.optString(id)));
            JSONArray entries = new JSONArray(prefs.getString("catalog", "[]"));
            for (int i = 0; i < entries.length(); i++) {
                JSONObject item = entries.getJSONObject(i);
                VideoEntry entry = new VideoEntry(item.getString("id"), item.getString("channel"),
                        item.optString("channelName"), item.getString("title"), item.optString("thumbnail"),
                        item.optLong("duration"), item.optLong("uploadedAt"));
                catalog.put(entry.id, entry);
            }
            JSONObject savedFavorites = new JSONObject(prefs.getString("favorites", "{}"));
            savedFavorites.keys().forEachRemaining(id -> favorites.put(id, savedFavorites.optLong(id)));
        } catch (Exception ignored) { catalog.clear(); }
    }
    public synchronized VideoEntry find(String id) { return catalog.get(id); }
    public synchronized List<VideoEntry> allowedEntries() {
        List<VideoEntry> result = new ArrayList<>();
        for (VideoEntry entry : catalog.values())
            if (policy.allows(entry.id, entry.channelId)) result.add(entry);
        result.sort(Comparator.comparingLong((VideoEntry entry) -> entry.uploadedAtMs).reversed());
        return result;
    }
    public synchronized boolean isFavorite(String id) { return favorites.containsKey(id); }
    public synchronized void toggleFavorite(String id) {
        if (favorites.containsKey(id)) favorites.remove(id);
        else {
            VideoEntry entry = catalog.get(id);
            if (entry == null || !policy.allows(entry.id, entry.channelId)) return;
            long newest = favorites.values().stream().mapToLong(Long::longValue).max().orElse(0);
            favorites.put(id, Math.max(System.currentTimeMillis(), newest + 1));
        }
        prefs.edit().putString("favorites", new JSONObject(favorites).toString()).apply();
        changed();
    }
    public synchronized List<VideoEntry> favoriteEntries() {
        List<VideoEntry> result = allowedEntries();
        result.removeIf(entry -> !favorites.containsKey(entry.id));
        result.sort(Comparator.comparingLong((VideoEntry entry) -> favorites.get(entry.id)).reversed());
        return result;
    }
    public synchronized Map<String, String> channels() { return new LinkedHashMap<>(channelNames); }
    public synchronized String avatar(String id) { return channelAvatars.getOrDefault(id, ""); }
    public long parentEpoch() { return parentEpoch; }
    public void invalidateParentActions() { parentEpoch++; }
    public synchronized String label(String id) {
        VideoEntry entry = catalog.get(id);
        return entry == null ? channelNames.getOrDefault(id, id) : entry.title;
    }
    public synchronized void add(SourceRepository.ChannelBatch batch) {
        policy.approve(batch.id);
        storeBatch(batch);
    }
    private synchronized void storeBatch(SourceRepository.ChannelBatch batch) {
        channelNames.put(batch.id, batch.name);
        channelAvatars.put(batch.id, batch.avatar);
        for (VideoEntry entry : batch.videos) catalog.put(entry.id, entry);
        saveCatalog(); changed();
    }
    public boolean loadingMore() { return loadingMore; }
    public boolean canLoadMore(String onlyChannel) {
        for (String id : policy.channels())
            if (policy.allowsChannel(id) && (onlyChannel == null || onlyChannel.equals(id)) && sources.hasMore(id)) return true;
        return false;
    }
    public synchronized boolean acceptAllowedBatch(SourceRepository.ChannelBatch batch, long revision) {
        if (policy.revision() != revision || !policy.allowsChannel(batch.id)) return false;
        storeBatch(batch); return true;
    }
    public void loadMoreAllowed(String onlyChannel, java.util.function.Consumer<String> done) {
        if (loadingMore || !canLoadMore(onlyChannel)) return;
        List<String> ids = new ArrayList<>(policy.channels());
        ids.removeIf(id -> !policy.allowsChannel(id) || onlyChannel != null && !onlyChannel.equals(id));
        if (ids.isEmpty()) return;
        loadingMore = true;
        final long revision = policy.revision();
        worker.execute(() -> {
            List<SourceRepository.ChannelBatch> batches = new ArrayList<>();
            int failures = 0;
            for (String id : ids) {
                if (!policy.allowsChannel(id) || policy.revision() != revision) break;
                try {
                    if (!sources.hasCursor(id)) batches.add(sources.loadChannel(id));
                    else if (sources.hasMore(id)) batches.add(sources.loadMore(id));
                } catch (Exception error) { failures++; }
            }
            final int errors = failures;
            main.post(() -> {
                loadingMore = false;
                for (SourceRepository.ChannelBatch batch : batches) acceptAllowedBatch(batch, revision);
                done.accept(errors > 0 ? "Some channels could not load. Scroll again to retry." : "");
            });
        });
    }
    public synchronized void removeChannel(String id) { policy.remove(id); saveCatalog(); changed(); }
    public synchronized void blockVideo(String id) { policy.blockVideo(id); saveCatalog(); changed(); }
    public synchronized void blockChannel(String id) { policy.blockChannel(id); saveCatalog(); changed(); }
    public synchronized void restoreVideo(String id) { policy.restoreVideo(id); saveCatalog(); changed(); }
    public synchronized void restoreChannel(String id) { policy.restoreChannel(id); saveCatalog(); changed(); }
    private synchronized void saveCatalog() {
        JSONArray entries = new JSONArray();
        for (VideoEntry entry : catalog.values()) {
            JSONObject item = new JSONObject();
            try {
                item.put("id", entry.id); item.put("channel", entry.channelId);
                item.put("channelName", entry.channelName); item.put("title", entry.title);
                item.put("thumbnail", entry.thumbnail); item.put("duration", entry.durationSeconds);
                item.put("uploadedAt", entry.uploadedAtMs);
                entries.put(item);
            } catch (Exception ignored) {}
        }
        prefs.edit().putStringSet("channels", policy.channels())
                .putStringSet("blockedVideos", policy.blockedVideos())
                .putStringSet("blockedChannels", policy.blockedChannels())
                .putString("catalog", entries.toString()).putString("channelNames", new JSONObject(channelNames).toString())
                .putString("channelAvatars", new JSONObject(channelAvatars).toString())
                .apply();
    }
    public void subscribe(Runnable listener) { listeners.add(listener); }
    public void unsubscribe(Runnable listener) { listeners.remove(listener); }
    private void changed() { main.post(() -> listeners.forEach(Runnable::run)); }
    public synchronized void updateUsage(boolean foreground, boolean playing) {
        meter.update(SystemClock.elapsedRealtime(), foreground, playing);
        prefs.edit().putLong("foregroundMs", meter.foregroundMs()).putLong("playingMs", meter.playingMs()).apply();
        if (expired() && !expiredNotified) { expiredNotified = true; changed(); }
    }
    public boolean expired() { return meter.expired(prefs.getBoolean("watchMode", true), prefs.getLong("limitMs", 0)); }
    public boolean forceSplit() { return prefs.getBoolean("forceSplit", false); }
    public long testRemainingMs() {
        long limit = prefs.getLong("limitMs", 0);
        return limit <= 0 ? -1 : Math.max(0, limit - (prefs.getBoolean("watchMode", true)
                ? meter.playingMs() : meter.foregroundMs()));
    }
    public void forceSplit(boolean split) { prefs.edit().putBoolean("forceSplit", split).apply(); }
    public void testLimit(boolean watch, int seconds) {
        prefs.edit().putBoolean("watchMode", watch).putLong("limitMs", seconds * 1000L).apply();
        resetUsage();
    }
    public void resetUsage() {
        meter.reset(SystemClock.elapsedRealtime()); expiredNotified = false;
        prefs.edit().putLong("foregroundMs", 0).putLong("playingMs", 0).apply(); changed();
    }
    public String measurements() {
        return "Watching " + meter.playingMs() / 1000 + "s · app " + meter.foregroundMs() / 1000 + "s";
    }
    public boolean pinSet() { return prefs.contains("pinHash"); }
    public void setPin(String pin) {
        if (!prefs.edit().putString("pinHash", PinHash.create(pin)).commit()) {
            prefs.edit().remove("pinHash").apply();
            throw new IllegalStateException("The PIN could not be saved. Check available storage and try again.");
        }
    }
    public long pinWaitMs() { return Math.max(0, prefs.getLong("pinUntil", 0) - System.currentTimeMillis()); }
    public boolean verifyPin(String pin) {
        if (pinWaitMs() > 0) return false;
        boolean valid = PinHash.verify(pin, prefs.getString("pinHash", ""));
        if (valid) prefs.edit().putInt("pinFailures", 0).remove("pinUntil").commit();
        else {
            int failures = prefs.getInt("pinFailures", 0) + 1;
            long until = failures >= 5 ? System.currentTimeMillis() + 30000L : 0;
            prefs.edit().putInt("pinFailures", failures).putLong("pinUntil", until).commit();
        }
        return valid;
    }
}
