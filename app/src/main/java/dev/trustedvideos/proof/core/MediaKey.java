// SPDX-License-Identifier: GPL-3.0-or-later
package dev.trustedvideos.proof.core;

/** Version-one provider identities. Legacy YouTube keys remain byte-for-byte readable. */
public final class MediaKey {
    private MediaKey() { }
    public record Plex(String child,String server,String type,String item) { }
    public static Plex plex(String key) {
        if(key==null) return null;
        String[] p=key.split(":",-1);
        if(p.length!=5 || !p[0].equals("plex") || !p[1].matches("[0-9]{1,18}")
                || !p[2].matches("[A-Za-z0-9_-]{1,128}") || !p[4].matches("[0-9]{1,18}")
                || !(p[3].equals("show") || p[3].equals("episode"))) return null;
        return new Plex(p[1],p[2],p[3],p[4]);
    }
    public static String key(String child,String server,String type,String item) {
        String value="plex:"+child+":"+server+":"+type+":"+item;
        if(plex(value)==null) throw new IllegalArgumentException("Invalid Plex identity.");
        return value;
    }
    public static boolean pair(String episode,String show) {
        Plex a=plex(episode),b=plex(show);
        return a!=null && b!=null && a.type.equals("episode") && b.type.equals("show")
                && a.child.equals(b.child) && a.server.equals(b.server);
    }
    public static boolean playable(String key) {
        Plex p=plex(key); return PermissionPolicy.validVideoId(key) || p!=null && p.type.equals("episode");
    }
}
