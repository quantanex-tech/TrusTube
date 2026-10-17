// SPDX-License-Identifier: GPL-3.0-or-later
package dev.trustedvideos.proof.core;

import java.net.URI;

public final class ChannelInputs {
    private ChannelInputs() {}
    public static String normalize(String input) {
        String value = input.trim();
        if (PermissionPolicy.validChannelId(value)) value = "https://www.youtube.com/channel/" + value;
        else if (value.matches("@[A-Za-z0-9_.-]+")) value = "https://www.youtube.com/" + value;
        URI uri;
        try { uri = URI.create(value); } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Use a YouTube channel URL or @handle.");
        }
        String host = uri.getHost(), path = uri.getPath();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null
                || uri.getPort() != -1 || path == null || host == null
                || !(host.equalsIgnoreCase("youtube.com") || host.equalsIgnoreCase("www.youtube.com")
                || host.equalsIgnoreCase("m.youtube.com"))
                || !(path.matches("/@[A-Za-z0-9_.-]+/?")
                || path.matches("/channel/UC[A-Za-z0-9_-]{22}/?")
                || path.matches("/(?:c|user)/[A-Za-z0-9_.-]+/?"))) {
            throw new IllegalArgumentException("Use a YouTube channel URL or @handle.");
        }
        return "https://www.youtube.com" + path.replaceAll("/$", "");
    }
    public static String canonicalChannel(String url) {
        try {
            URI uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || !(uri.getHost().equalsIgnoreCase("youtube.com")
                    || uri.getHost().equalsIgnoreCase("www.youtube.com"))
                    || uri.getUserInfo() != null || uri.getPort() != -1) return "";
            String path = uri.getPath();
            if (path == null) return "";
            java.util.regex.Matcher match = java.util.regex.Pattern.compile(
                    "/channel/(UC[A-Za-z0-9_-]{22})(?:/(?:videos|shorts|streams|featured|playlists))?/?").matcher(path);
            return match.matches() ? match.group(1) : "";
        } catch (RuntimeException e) { return ""; }
    }
    public static String videoId(String url) {
        try {
            URI uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || !(uri.getHost().equalsIgnoreCase("youtube.com")
                    || uri.getHost().equalsIgnoreCase("www.youtube.com"))
                    || uri.getUserInfo() != null || uri.getPort() != -1
                    || !"/watch".equals(uri.getPath())) return "";
            if (uri.getRawQuery() == null) return "";
            for (String part : uri.getRawQuery().split("&")) {
                if (part.startsWith("v=") && PermissionPolicy.validVideoId(part.substring(2)))
                    return part.substring(2);
            }
        } catch (RuntimeException ignored) {}
        return "";
    }
}
