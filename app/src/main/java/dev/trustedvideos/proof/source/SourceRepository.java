// SPDX-License-Identifier: GPL-3.0-or-later
package dev.trustedvideos.proof.source;

import dev.trustedvideos.proof.core.ChannelInputs;
import dev.trustedvideos.proof.core.PermissionPolicy;
import dev.trustedvideos.proof.core.VideoEntry;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.channel.ChannelInfo;
import org.schabi.newpipe.extractor.channel.ChannelInfoItem;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler;
import org.schabi.newpipe.extractor.search.SearchInfo;
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.AudioTrackType;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.Stream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeProgressiveDashManifestCreator;

public final class SourceRepository {
    private static boolean initialized;
    private final Map<String, Cursor> cursors = new ConcurrentHashMap<>();

    public SourceRepository() {
        synchronized (SourceRepository.class) {
            if (!initialized) {
                Locale locale = Locale.getDefault();
                NewPipe.init(new HttpDownloader(), new Localization(locale.getLanguage(), locale.getCountry()));
                initialized = true;
            }
        }
    }
    public static final class ChannelBatch {
        public final String id, name, avatar;
        public final List<VideoEntry> videos;
        public final boolean hasMore;
        public ChannelBatch(String id, String name, String avatar, List<VideoEntry> videos, boolean hasMore) {
            this.id = id; this.name = name; this.avatar = avatar; this.videos = videos; this.hasMore = hasMore;
        }
    }
    private static final class Cursor {
        final ChannelInfo channel;
        final ListLinkHandler tab;
        final Page page;
        Cursor(ChannelInfo channel, ListLinkHandler tab, Page page) {
            this.channel = channel; this.tab = tab; this.page = page;
        }
    }
    public static final class ChannelResult {
        public final String id, name, url, avatar, description;
        public ChannelResult(String id, String name, String avatar, String description) {
            if (!PermissionPolicy.validChannelId(id)) throw new IllegalArgumentException("Invalid channel identity.");
            this.id = id; this.name = name; this.avatar = avatar; this.description = description;
            this.url = "https://www.youtube.com/channel/" + id;
        }
    }
    public static final class SearchBatch {
        public final List<ChannelResult> channels;
        public final boolean hasMore;
        private final SearchQueryHandler query;
        private final Page next;
        public SearchBatch(List<ChannelResult> channels) { this(channels, null, null); }
        public SearchBatch(List<ChannelResult> channels, SearchQueryHandler query, Page next) {
            this.channels = List.copyOf(channels); this.query = query; this.next = next;
            this.hasMore = query != null && Page.isValid(next);
        }
    }
    /** Discovery returns metadata only; it never changes approvals or loads playable sources. */
    public SearchBatch searchChannels(String input, SearchBatch previous) throws Exception {
        String term = input.trim();
        if (term.isEmpty() || term.length() > 200) throw new IllegalArgumentException("Use 1–200 characters.");
        if (previous != null) {
            if (!previous.hasMore || !term.equals(previous.query.getSearchString()))
                throw new IllegalArgumentException("Start a new channel search.");
            ListExtractor.InfoItemsPage<InfoItem> page = SearchInfo.getMoreItems(ServiceList.YouTube, previous.query, previous.next);
            return new SearchBatch(channelResults(page.getItems()), previous.query, page.getNextPage());
        }
        if (term.startsWith("@") || term.contains("://") || PermissionPolicy.validChannelId(term)) {
            String url = ChannelInputs.normalize(term);
            ChannelInfo info = ChannelInfo.getInfo(ServiceList.YouTube, url);
            verifyChannelIdentity(url, info.getId());
            return new SearchBatch(List.of(new ChannelResult(info.getId(), info.getName(), image(info.getAvatars()), info.getDescription())));
        }
        SearchQueryHandler query = ServiceList.YouTube.getSearchQHFactory()
                .fromQuery(term, List.of(YoutubeSearchQueryHandlerFactory.CHANNELS), "");
        SearchInfo info = SearchInfo.getInfo(ServiceList.YouTube, query);
        return new SearchBatch(channelResults(info.getRelatedItems()), query, info.getNextPage());
    }
    static List<ChannelResult> channelResults(List<InfoItem> items) {
        Map<String, ChannelResult> channels = new LinkedHashMap<>();
        for (InfoItem item : items) {
            if (!(item instanceof ChannelInfoItem channel)) continue;
            String id = ChannelInputs.canonicalChannel(channel.getUrl());
            if (!PermissionPolicy.validChannelId(id)) continue;
            channels.putIfAbsent(id, new ChannelResult(id, channel.getName(), image(channel.getThumbnails()), channel.getDescription()));
        }
        return new ArrayList<>(channels.values());
    }
    static void verifyChannelIdentity(String url, String resolvedId) {
        if (!PermissionPolicy.validChannelId(resolvedId))
            throw new SecurityException("The channel identity could not be verified.");
        String expected = ChannelInputs.canonicalChannel(url);
        if (!expected.isEmpty() && !expected.equals(resolvedId))
            throw new SecurityException("The selected channel identity changed. Search again before adding it.");
    }
    public synchronized ChannelBatch loadChannel(String input) throws Exception {
        String url = ChannelInputs.normalize(input);
        ChannelInfo channel = ChannelInfo.getInfo(ServiceList.YouTube, url);
        verifyChannelIdentity(url, channel.getId());
        ListLinkHandler tab = channel.getTabs().stream()
                .filter(item -> item.getContentFilters().contains(ChannelTabs.VIDEOS)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("This channel has no standard video tab."));
        ChannelTabInfo info = ChannelTabInfo.getInfo(ServiceList.YouTube, tab);
        cursors.put(channel.getId(), new Cursor(channel, tab, info.getNextPage()));
        return batch(channel, info.getRelatedItems(), info.getNextPage());
    }
    public synchronized ChannelBatch loadMore(String channelId) throws Exception {
        Cursor cursor = cursors.get(channelId);
        if (cursor == null || !Page.isValid(cursor.page))
            throw new IllegalStateException("Refresh the channel before loading another page.");
        ListExtractor.InfoItemsPage<InfoItem> page =
                ChannelTabInfo.getMoreItems(ServiceList.YouTube, cursor.tab, cursor.page);
        cursors.put(channelId, new Cursor(cursor.channel, cursor.tab, page.getNextPage()));
        return batch(cursor.channel, page.getItems(), page.getNextPage());
    }
    public synchronized boolean hasCursor(String channelId) { return cursors.containsKey(channelId); }
    public synchronized boolean hasMore(String channelId) {
        Cursor cursor = cursors.get(channelId);
        return cursor == null || Page.isValid(cursor.page);
    }
    private ChannelBatch batch(ChannelInfo channel, List<InfoItem> items, Page next) {
        List<VideoEntry> entries = new ArrayList<>();
        for (InfoItem item : items) {
            if (!(item instanceof StreamInfoItem stream) || stream.getStreamType() != StreamType.VIDEO_STREAM)
                continue;
            String id = ChannelInputs.videoId(stream.getUrl());
            String owner = ChannelInputs.canonicalChannel(stream.getUploaderUrl());
            if (!PermissionPolicy.validVideoId(id) || !channel.getId().equals(owner)) continue;
            entries.add(new VideoEntry(id, owner, channel.getName(), stream.getName(),
                    image(stream.getThumbnails()), stream.getDuration(),
                    stream.getUploadDate() == null ? 0 : stream.getUploadDate().getInstant().toEpochMilli()));
        }
        return new ChannelBatch(channel.getId(), channel.getName(), image(channel.getAvatars()),
                entries, Page.isValid(next));
    }
    public ResolvedPlayback resolve(VideoEntry expected, boolean forceSplit) throws Exception {
        return resolve(expected, forceSplit, null);
    }
    public ResolvedPlayback resolve(VideoEntry expected, boolean forceSplit, String requestedAudio) throws Exception {
        StreamInfo info = StreamInfo.getInfo(ServiceList.YouTube,
                "https://www.youtube.com/watch?v=" + expected.id);
        String owner = ChannelInputs.canonicalChannel(info.getUploaderUrl());
        if (!expected.id.equals(info.getId()) || !expected.channelId.equals(owner)
                || !PermissionPolicy.validChannelId(owner))
            throw new SecurityException("This video's creator could not be verified against its approved channel.");
        if (info.getStreamType() != StreamType.VIDEO_STREAM || info.getAgeLimit() > 0)
            throw new IllegalArgumentException("This proof supports ordinary, unrestricted videos only.");
        VideoStream combined = selectVideo(info.getVideoStreams());
        VideoStream video = selectVideo(info.getVideoOnlyStreams());
        AudioStream audio = selectAudio(info.getAudioStreams(), requestedAudio);
        List<ResolvedPlayback.AudioOption> options = audioOptions(info.getAudioStreams());
        // Prefer verified original metadata over a combined stream with unspecified audio.
        if (!forceSplit && requestedAudio == null && combined != null
                && (audio == null || audio.getAudioTrackType() == null)) {
            if (audio == null && !options.isEmpty())
                throw new IllegalArgumentException("Original audio is unavailable. No translated track was substituted.");
            return new ResolvedPlayback(info.getId(), owner, info.getName(), track(combined, info.getDuration()), null,
                    options, audio == null ? "" : audioKey(audio));
        }
        if (video == null || audio == null)
            throw new IllegalArgumentException(forceSplit
                    ? "A separate audio/video pair is unavailable for this video."
                    : "A playable video and audio pair is unavailable.");
        return new ResolvedPlayback(info.getId(), owner, info.getName(),
                track(video, info.getDuration()), track(audio, info.getDuration()), options, audioKey(audio));
    }
    static AudioStream selectAudio(List<AudioStream> streams) {
        return selectAudio(streams, null);
    }
    static AudioStream selectAudio(List<AudioStream> streams, String requestedAudio) {
        // Language takes priority over codec/bitrate: a dubbed AAC track must not
        // beat the original audio. Untagged streams support older/single-track
        // uploads; known translations and alternate tracks are never substituted.
        return streams.stream().filter(SourceRepository::usable)
                .filter(item -> requestedAudio != null ? requestedAudio.equals(audioKey(item))
                        : item.getAudioTrackType() == AudioTrackType.ORIGINAL || item.getAudioTrackType() == null)
                .max(Comparator.comparingInt((AudioStream item) ->
                        item.getAudioTrackType() == AudioTrackType.ORIGINAL ? 1 : 0)
                        .thenComparingInt(item -> item.getCodec() == null
                                || !item.getCodec().toLowerCase(Locale.ROOT).contains("opus") ? 1 : 0)
                        .thenComparingInt(AudioStream::getAverageBitrate)).orElse(null);
    }
    static String audioKey(AudioStream stream) {
        if (stream.getAudioTrackId() != null && !stream.getAudioTrackId().isEmpty()) return stream.getAudioTrackId();
        return (stream.getAudioLocale() == null ? "und" : stream.getAudioLocale().toLanguageTag())
                + ":" + stream.getAudioTrackType() + ":" + stream.getAudioTrackName();
    }
    static List<ResolvedPlayback.AudioOption> audioOptions(List<AudioStream> streams) {
        Map<String, ResolvedPlayback.AudioOption> result = new LinkedHashMap<>();
        for (AudioStream stream : streams) {
            if (!usable(stream)) continue;
            String name = stream.getAudioTrackName();
            if (name == null || name.isBlank()) name = stream.getAudioLocale() == null ? "Default audio"
                    : stream.getAudioLocale().getDisplayName(Locale.getDefault());
            if (stream.getAudioTrackType() == AudioTrackType.ORIGINAL) name += " · Original";
            else if (stream.getAudioTrackType() != null) name += " · " + stream.getAudioTrackType().name().toLowerCase(Locale.ROOT);
            String key = audioKey(stream); result.putIfAbsent(key, new ResolvedPlayback.AudioOption(key, name,
                    stream.getAudioTrackType() == AudioTrackType.ORIGINAL));
        }
        List<ResolvedPlayback.AudioOption> options = new ArrayList<>(result.values());
        options.sort(Comparator.comparing((ResolvedPlayback.AudioOption option) -> !option.original)
                .thenComparing(option -> option.label, String.CASE_INSENSITIVE_ORDER));
        return options;
    }
    private static VideoStream selectVideo(List<VideoStream> streams) {
        return streams.stream().filter(SourceRepository::usable)
                .filter(item -> item.getHeight() <= 720)
                .max(Comparator.comparingInt((VideoStream item) -> {
                    String codec = item.getCodec();
                    return (codec != null && (codec.startsWith("avc") || codec.contains("h264")) ? 10000 : 0)
                            + Math.max(0, item.getHeight());
                })).orElse(null);
    }
    private static boolean usable(Stream stream) {
        if (!stream.isUrl() || stream.getDeliveryMethod() == DeliveryMethod.HLS) return false;
        try {
            URI uri = URI.create(stream.getContent());
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && (uri.getHost().equals("googlevideo.com") || uri.getHost().endsWith(".googlevideo.com"))
                    && uri.getUserInfo() == null;
        } catch (RuntimeException e) { return false; }
    }
    private static ResolvedPlayback.Track track(Stream stream, long duration) throws Exception {
        String manifest = null;
        boolean separate = stream instanceof AudioStream
                || stream instanceof VideoStream && ((VideoStream) stream).isVideoOnly();
        if (separate && stream.getItagItem() != null
                && stream.getItagItem().getIndexStart() >= 0 && stream.getItagItem().getIndexEnd() > 0)
            manifest = YoutubeProgressiveDashManifestCreator.fromProgressiveStreamingUrl(
                    stream.getContent(), stream.getItagItem(), duration);
        return new ResolvedPlayback.Track(stream.getContent(), manifest);
    }
    private static String image(List<Image> images) {
        return images.isEmpty() ? "" : images.get(images.size() - 1).getUrl();
    }
}
