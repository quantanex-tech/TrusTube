// SPDX-License-Identifier: GPL-3.0-or-later
package dev.trustedvideos.proof.source;

public final class ResolvedPlayback {
    public final String videoId, channelId, title;
    public final Track video, audio;
    public final String kind;
    public final java.util.List<AudioOption> audioOptions;
    public final String selectedAudio;
    public ResolvedPlayback(String videoId, String channelId, String title, Track video, Track audio) {
        this(videoId, channelId, title, video, audio, java.util.List.of(), "");
    }
    public ResolvedPlayback(String videoId, String channelId, String title, Track video, Track audio,
                            java.util.List<AudioOption> audioOptions, String selectedAudio) {
        this.videoId = videoId; this.channelId = channelId; this.title = title;
        this.video = video; this.audio = audio; this.kind = audio == null ? "combined" : "split audio/video";
        this.audioOptions = java.util.List.copyOf(audioOptions); this.selectedAudio = selectedAudio;
    }
    public static final class AudioOption {
        public final String id, label;
        public final boolean original;
        public AudioOption(String id, String label, boolean original) { this.id = id; this.label = label; this.original = original; }
    }
    public static final class Track {
        public final String url, dashManifest;
        public Track(String url, String dashManifest) { this.url = url; this.dashManifest = dashManifest; }
    }
}
