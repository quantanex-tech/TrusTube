// SPDX-License-Identifier: GPL-3.0-or-later
package dev.trustedvideos.proof.source;

public final class ResolvedPlayback {
    public final String videoId, channelId, title;
    public final Track video, audio;
    public final String kind;
    public ResolvedPlayback(String videoId, String channelId, String title, Track video, Track audio) {
        this.videoId = videoId; this.channelId = channelId; this.title = title;
        this.video = video; this.audio = audio; this.kind = audio == null ? "combined" : "split audio/video";
    }
    public static final class Track {
        public final String url, dashManifest;
        public Track(String url, String dashManifest) { this.url = url; this.dashManifest = dashManifest; }
    }
}
