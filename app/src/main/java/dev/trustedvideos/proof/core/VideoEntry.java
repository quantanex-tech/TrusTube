// SPDX-License-Identifier: GPL-3.0-or-later
package dev.trustedvideos.proof.core;

public final class VideoEntry {
    public final String id, channelId, channelName, title, thumbnail;
    public final long durationSeconds;
    public final long uploadedAtMs;

    public VideoEntry(String id, String channelId, String channelName, String title,
                      String thumbnail, long durationSeconds) {
        this(id, channelId, channelName, title, thumbnail, durationSeconds, 0);
    }
    public VideoEntry(String id, String channelId, String channelName, String title,
                      String thumbnail, long durationSeconds, long uploadedAtMs) {
        this.id = id;
        this.channelId = channelId;
        this.channelName = channelName;
        this.title = title;
        this.thumbnail = thumbnail;
        this.durationSeconds = durationSeconds;
        this.uploadedAtMs = uploadedAtMs;
    }
}
