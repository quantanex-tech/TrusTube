// SPDX-License-Identifier: GPL-3.0-or-later
package dev.trustedvideos.proof;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import dev.trustedvideos.proof.core.PermissionPolicy;
import dev.trustedvideos.proof.core.MediaKey;
import dev.trustedvideos.proof.core.DailyMix;
import dev.trustedvideos.proof.core.ChannelTransfer;
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
    public static final int WATCH_HISTORY_LIMIT = 50;
    static final int HOME_RESTORE_LIMIT = 100;
    final PlexLibrary plex;
    final ImageCache plexImages;
    private final DailyMix dailyMix;
    public final PermissionPolicy policy = new PermissionPolicy();
    public final UsageMeter meter;
    public final DailyTimers timers;
    final WatchProgress progress;
    public final SourceRepository sources = new SourceRepository();
    public final ExecutorService worker = Executors.newFixedThreadPool(3);
    public final ExecutorService credentials = Executors.newSingleThreadExecutor();
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, VideoEntry> catalog = new LinkedHashMap<>();
    private final Map<String, String> channelNames = new LinkedHashMap<>();
    private final Map<String, String> channelAvatars = new LinkedHashMap<>();
    private final Map<String, Long> favorites = new LinkedHashMap<>();
    private final List<String> watchHistory = new ArrayList<>();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private boolean expiredNotified;
    private volatile long parentEpoch;
    private volatile boolean loadingMore;
    interface ChannelLoader {
        SourceRepository.ChannelBatch load(String id, boolean more) throws Exception;
    }
    private final ChannelLoader channelLoader;

    ProofState(Context context) { this(context, null); }
    ProofState(Context context, ChannelLoader loader) {
        channelLoader = loader != null ? loader
                : (id, more) -> more ? sources.loadMore(id) : sources.loadChannel(id);
        prefs = context.getSharedPreferences("proof", Context.MODE_PRIVATE);
        progress = new WatchProgress(context.getSharedPreferences("watch-progress", Context.MODE_PRIVATE));
        policy.restore(prefs.getStringSet("channels", Set.of()), prefs.getStringSet("blockedVideos", Set.of()),
                prefs.getStringSet("blockedChannels", Set.of()));
        policy.clearPlex(); // Saved metadata never grants Plex access after restart.
        meter = new UsageMeter(prefs.getLong("foregroundMs", 0), prefs.getLong("playingMs", 0));
        timers = new DailyTimers(prefs, this::changed);
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
        try {
            JSONArray history = new JSONArray(prefs.getString("watchHistory", "[]"));
            for (int i = 0; i < history.length() && watchHistory.size() < WATCH_HISTORY_LIMIT; i++) {
                Object item = history.opt(i);
                if (item instanceof String && MediaKey.playable((String) item)
                        && !watchHistory.contains((String) item)) watchHistory.add((String) item);
            }
        } catch (Exception ignored) { watchHistory.clear(); }
            List<String> mixOrder = new ArrayList<>();
        try { JSONArray a=new JSONArray(prefs.getString("homeMixOrder","[]")); for(int i=0;i<a.length();i++) mixOrder.add(a.getString(i)); } catch(Exception ignored) { }
        // Old mixes contained show identities. Rebuild once with exact episode identities.
        boolean episodeMix=prefs.getInt("homeMixVersion",0)>=2;
        dailyMix=new DailyMix(episodeMix?prefs.getString("homeMixDay",""):"",prefs.getLong("homeMixSeed",0),episodeMix?mixOrder:List.of());
        plex=new PlexLibrary(context,this);
        plexImages=PlexArtwork.cache(context,this);
    }
    public synchronized VideoEntry find(String id) { return catalog.get(id); }
    WatchProgress.Entry progress(VideoEntry entry) {
        if(entry==null || !policy.allows(entry.id,entry.channelId)) return null;
        if(entry.isPlex()) {
            var e=plex.episode(entry.id); return e!=null && e.offset()>0 && e.duration()>e.offset()?new WatchProgress.Entry(e.offset(),e.duration()):null;
        }
        return progress.get(entry.id);
    }
    long resumePosition(VideoEntry entry) {
        WatchProgress.Entry saved = progress(entry);
        return saved == null ? 0 : saved.positionMs;
    }
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
    public synchronized void recordWatched(String id) {
        VideoEntry entry = catalog.get(id);
        if (entry == null || !policy.allows(entry.id, entry.channelId)) return;
        watchHistory.remove(id);
        watchHistory.add(0, id);
        if (watchHistory.size() > WATCH_HISTORY_LIMIT) watchHistory.remove(watchHistory.size() - 1);
        saveHistory(); changed();
    }
    public synchronized List<VideoEntry> historyEntries() {
        List<VideoEntry> result = new ArrayList<>();
        for (String id : watchHistory) {
            VideoEntry entry = catalog.get(id);
            if (entry != null && policy.allows(entry.id, entry.channelId)) result.add(entry);
        }
        return result;
    }
    public synchronized void clearHistory() {
        watchHistory.clear(); saveHistory(); changed();
    }
    private void saveHistory() {
        prefs.edit().putString("watchHistory", new JSONArray(watchHistory).toString()).apply();
    }
    public synchronized Map<String, String> channels() { return new LinkedHashMap<>(channelNames); }
    public synchronized String avatar(String id) { return channelAvatars.getOrDefault(id, ""); }
    public synchronized String exportChannels() {
        List<ChannelTransfer.Channel> rows = new ArrayList<>();
        for (String id : policy.channels()) if(PermissionPolicy.validChannelId(id)) rows.add(new ChannelTransfer.Channel(id, label(id), policy.blockedChannels().contains(id)));
        return ChannelTransfer.encode(rows);
    }
    public synchronized int importChannels(List<ChannelTransfer.Channel> rows) {
        int added = ChannelTransfer.merge(policy, rows);
        for (ChannelTransfer.Channel row : rows) channelNames.putIfAbsent(row.id, row.name.isBlank() ? row.id : row.name);
        prefs.edit().remove("datedCatalogRefresh").apply();
        saveCatalog(); changed(); return added;
    }
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
        for (VideoEntry entry : batch.videos) {
            VideoEntry previous = catalog.get(entry.id);
            if (entry.uploadedAtMs <= 0 && previous != null && previous.uploadedAtMs > 0)
                entry = new VideoEntry(entry.id, entry.channelId, entry.channelName, entry.title,
                        entry.thumbnail, entry.durationSeconds, previous.uploadedAtMs);
            catalog.put(entry.id, entry);
        }
        saveCatalog(); changed();
    }
    public boolean loadingMore() { return loadingMore; }
    /** Refresh every subscription on upgrade/after six hours so old undated caches can interleave. */
    public void refreshAllowedIfNeeded() {
        if (loadingMore || appExpired() || System.currentTimeMillis() - prefs.getLong("datedCatalogRefresh", 0) < 6 * 3600000L) return;
        List<String> ids = new ArrayList<>(policy.channels()); ids.removeIf(id -> !PermissionPolicy.validChannelId(id) || !policy.allowsChannel(id));
        if (ids.isEmpty()) return;
        loadingMore = true; final long revision = policy.revision();
        changed();
        worker.execute(() -> {
            boolean failed = false;
            for (String id : ids) {
                if (policy.revision() != revision) break;
                try {
                    SourceRepository.ChannelBatch first = channelLoader.load(id, false);
                    main.post(() -> acceptAllowedBatch(first, revision));
                } catch (Exception error) { failed = true; }
            }
            final boolean complete = !failed;
            main.post(() -> {
                loadingMore = false;
                if (complete && policy.revision() == revision)
                    prefs.edit().putLong("datedCatalogRefresh", System.currentTimeMillis()).apply();
                changed();
            });
        });
    }
    public boolean canLoadMore(String onlyChannel) {
        for (String id : policy.channels())
            if (PermissionPolicy.validChannelId(id) && policy.allowsChannel(id) && (onlyChannel == null || onlyChannel.equals(id)) && sources.hasMore(id)) return true;
        return false;
    }
    public synchronized boolean acceptAllowedBatch(SourceRepository.ChannelBatch batch, long revision) {
        if (policy.revision() != revision || !policy.allowsChannel(batch.id)) return false;
        storeBatch(batch); return true;
    }
    public void loadMoreAllowed(String onlyChannel, java.util.function.Consumer<String> done) {
        if (loadingMore || !canLoadMore(onlyChannel)) return;
        List<String> ids = new ArrayList<>(policy.channels());
        ids.removeIf(id -> !PermissionPolicy.validChannelId(id) || !policy.allowsChannel(id) || onlyChannel != null && !onlyChannel.equals(id));
        if (ids.isEmpty()) return;
        loadingMore = true;
        changed();
        final long revision = policy.revision();
        worker.execute(() -> {
            int failures = 0;
            for (String id : ids) {
                if (!policy.allowsChannel(id) || policy.revision() != revision) break;
                try {
                    SourceRepository.ChannelBatch batch = !sources.hasCursor(id) ? channelLoader.load(id, false)
                            : sources.hasMore(id) ? channelLoader.load(id, true) : null;
                    if (batch != null) main.post(() -> acceptAllowedBatch(batch, revision));
                } catch (Exception error) { failures++; }
            }
            final int errors = failures;
            main.post(() -> {
                loadingMore = false;
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
        prefs.edit().putInt("catalogVersion", 2).putStringSet("channels", policy.channels())
                .putStringSet("blockedVideos", policy.blockedVideos())
                .putStringSet("blockedChannels", policy.blockedChannels())
                .putString("catalog", entries.toString()).putString("channelNames", new JSONObject(channelNames).toString())
                .putString("channelAvatars", new JSONObject(channelAvatars).toString())
                .apply();
    }
    synchronized void plexShow(String id,String title,String art) {
        channelNames.put(id,title); channelAvatars.put(id,art); policy.approve(id);
    }
    synchronized void plexEpisode(VideoEntry entry) { plexEpisodes(List.of(entry)); }
    synchronized void plexEpisodes(List<VideoEntry> entries) {
        for(VideoEntry entry:entries) { catalog.put(entry.id,entry); policy.verifyEpisode(entry.id); }
        if(!entries.isEmpty()) saveCatalog();
    }
    void clearPlexGrants() { policy.clearPlex(); }
    void plexChanged() { if(plex.session()==null) plexImages.clear(); changed(); }
    List<String> homeMix(boolean reshuffle) {
        Set<String> candidates=plex.homeCandidates();
        synchronized(this) {
            List<String> yt=new ArrayList<>(),tv=new ArrayList<>();
            for(VideoEntry e:allowedEntries()) { if(e.isPlex()) { if(candidates.contains(e.id)) tv.add(e.id); } else yt.add(e.id); }
            List<String> order=dailyMix.update(java.time.LocalDate.now().toString(),java.util.concurrent.ThreadLocalRandom.current().nextLong(),yt,tv,reshuffle);
            prefs.edit().putInt("homeMixVersion",2).putString("homeMixDay",dailyMix.day()).putLong("homeMixSeed",dailyMix.seed()).putString("homeMixOrder",new JSONArray(dailyMix.savedOrder()).toString()).apply();
            return order;
        }
    }
    synchronized List<VideoEntry> savedPlexEntries() {
        java.util.LinkedHashSet<String> ids=new java.util.LinkedHashSet<>(watchHistory); ids.addAll(favorites.keySet()); ids.addAll(savedHomePlexKeys(HOME_RESTORE_LIMIT));
        List<VideoEntry> rows=new ArrayList<>();
        for(String id:ids) { VideoEntry e=catalog.get(id); if(e!=null && e.isPlex()) rows.add(e); }
        return rows;
    }
    synchronized List<String> savedHomePlexKeys(int limit) {
        List<String> keys=new ArrayList<>();
        for(String key:dailyMix.savedOrder()) if(MediaKey.plex(key)!=null && MediaKey.playable(key)) {
            if(keys.size()>=limit) break; keys.add(key);
        }
        return keys;
    }
    void reshuffle() { homeMix(true); changed(); }
    public void subscribe(Runnable listener) { listeners.add(listener); }
    public void unsubscribe(Runnable listener) { listeners.remove(listener); }
    private void changed() { main.post(() -> listeners.forEach(Runnable::run)); }
    public synchronized void updateUsage(boolean foreground, boolean playing) {
        meter.update(SystemClock.elapsedRealtime(), foreground, playing);
        timers.tick(foreground, playing);
        prefs.edit().putLong("foregroundMs", meter.foregroundMs()).putLong("playingMs", meter.playingMs()).apply();
        if (expired() && !expiredNotified) { expiredNotified = true; changed(); }
    }
    public boolean expired() { return appExpired() || timers.expired(dev.trustedvideos.proof.core.TimerSchedule.Type.WATCHING)
            || meter.expired(prefs.getBoolean("watchMode", true), prefs.getLong("limitMs", 0)); }
    public boolean appExpired() { return timers.expired(dev.trustedvideos.proof.core.TimerSchedule.Type.APP); }
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
