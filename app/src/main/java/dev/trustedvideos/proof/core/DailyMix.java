// SPDX-License-Identifier: GPL-3.0-or-later
package dev.trustedvideos.proof.core;

import java.util.*;

/** Stable identity sequence. Metadata is deliberately absent; changes cannot freeze resume targets. */
public final class DailyMix {
    private String day;
    private long seed;
    private final List<String> order=new ArrayList<>();
    public DailyMix(String day,long seed,List<String> saved) {
        this.day=day; this.seed=seed; for(String key:saved) if(!order.contains(key)) order.add(key);
    }
    public List<String> update(String today,long newSeed,List<String> youtube,List<String> episodes,boolean reshuffle) {
        if(reshuffle || !today.equals(day)) { day=today; seed=newSeed; order.clear(); }
        Set<String> available=new HashSet<>(youtube); available.addAll(episodes);
        // Retain hidden slots so a temporary source outage does not rewrite today's order.
        List<String> a=remaining(youtube),b=remaining(episodes);
        int i=0,j=0; boolean preferTv=(seed&1)==0;
        List<String> balanced=new ArrayList<>(); Boolean previous=null;
        Set<String> yt=new HashSet<>(youtube),tvKeys=new HashSet<>(episodes);
        for(String key:order) {
            Boolean tv=source(key,yt,tvKeys);
            if(balanced.isEmpty() && tv!=null && tv!=preferTv) {
                if(preferTv && j<b.size()) { balanced.add(b.get(j++)); previous=true; }
                else if(!preferTv && i<a.size()) { balanced.add(a.get(i++)); previous=false; }
            }
            // Late provider results fill gaps without changing existing identities' relative order.
            if(tv!=null && tv.equals(previous)) {
                if(tv && i<a.size()) balanced.add(a.get(i++));
                else if(!tv && j<b.size()) balanced.add(b.get(j++));
            }
            balanced.add(key); previous=tv;
        }
        order.clear(); order.addAll(balanced);
        boolean tv=previous==null?preferTv:!previous;
        while(i<a.size() || j<b.size()) {
            if((tv && j<b.size()) || i>=a.size()) order.add(b.get(j++)); else order.add(a.get(i++));
            tv=!tv;
        }
        List<String> visible=new ArrayList<>(order); visible.removeIf(key->!available.contains(key)); return visible;
    }
    private static Boolean source(String key,Set<String> youtube,Set<String> episodes) {
        if(episodes.contains(key) || MediaKey.plex(key)!=null) return true;
        if(youtube.contains(key) || PermissionPolicy.validVideoId(key)) return false;
        return null;
    }
    private List<String> remaining(List<String> items) {
        List<String> result=new ArrayList<>(new LinkedHashSet<>(items)); result.removeAll(order);
        result.sort(Comparator.comparingLong((String s)->rank(s,seed)).thenComparing(s->s)); return result;
    }
    private static long rank(String key,long seed) {
        long n=seed; for(int i=0;i<key.length();i++) { n^=key.charAt(i); n*=0x100000001b3L; }
        n^=n>>>33; n*=0xff51afd7ed558ccdL; n^=n>>>33; return n;
    }
    public List<String> savedOrder() { return List.copyOf(order); }
    public String day() { return day; }
    public long seed() { return seed; }
}
