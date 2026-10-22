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
    public List<String> update(String today,long newSeed,List<String> youtube,List<String> shows,boolean reshuffle) {
        if(reshuffle || !today.equals(day)) { day=today; seed=newSeed; order.clear(); }
        Set<String> available=new HashSet<>(youtube); available.addAll(shows);
        // Retain hidden slots so a temporary source outage does not rewrite today's order.
        List<String> a=remaining(youtube),b=remaining(shows);
        int i=0,j=0; boolean tv=(order.size()%2==0)==((seed&1)==0);
        while(i<a.size() || j<b.size()) {
            if((tv && j<b.size()) || i>=a.size()) order.add(b.get(j++)); else order.add(a.get(i++));
            tv=!tv;
        }
        List<String> visible=new ArrayList<>(order); visible.removeIf(key->!available.contains(key)); return visible;
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
