// SPDX-License-Identifier: GPL-3.0-or-later
package dev.trustedvideos.proof.core;

import java.util.HashSet;
import java.util.Set;

public final class PermissionPolicy {
    private final Set<String> channels = new HashSet<>();
    private final Set<String> blockedChannels = new HashSet<>();
    private final Set<String> blockedVideos = new HashSet<>();
    private long revision;

    public synchronized void restore(Set<String> allowed, Set<String> videos, Set<String> blocked) {
        channels.clear(); channels.addAll(allowed);
        blockedVideos.clear(); blockedVideos.addAll(videos);
        blockedChannels.clear(); blockedChannels.addAll(blocked);
        revision++;
    }
    public synchronized void approve(String channel) { channels.add(channel); revision++; }
    public synchronized void remove(String channel) { channels.remove(channel); revision++; }
    public synchronized void blockVideo(String video) { blockedVideos.add(video); revision++; }
    public synchronized void blockChannel(String channel) { blockedChannels.add(channel); revision++; }
    public synchronized void restoreVideo(String video) { blockedVideos.remove(video); revision++; }
    public synchronized void restoreChannel(String channel) { blockedChannels.remove(channel); revision++; }
    public synchronized long revision() { return revision; }
    public synchronized boolean allows(String video, String channel) {
        return validVideoId(video) && validChannelId(channel) && channels.contains(channel)
                && !blockedChannels.contains(channel) && !blockedVideos.contains(video);
    }
    public synchronized boolean allowsChannel(String channel) {
        return validChannelId(channel) && channels.contains(channel) && !blockedChannels.contains(channel);
    }
    public synchronized Set<String> channels() { return new HashSet<>(channels); }
    public synchronized Set<String> blockedVideos() { return new HashSet<>(blockedVideos); }
    public synchronized Set<String> blockedChannels() { return new HashSet<>(blockedChannels); }
    public static boolean validChannelId(String value) {
        return value != null && value.matches("UC[A-Za-z0-9_-]{22}");
    }
    public static boolean validVideoId(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{11}");
    }
}
