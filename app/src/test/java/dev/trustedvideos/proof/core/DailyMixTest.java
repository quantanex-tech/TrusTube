package dev.trustedvideos.proof.core;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class DailyMixTest {
    private List<String> list(String prefix,int count) { List<String> r=new ArrayList<>(); for(int i=0;i<count;i++) r.add(prefix+i); return r; }
    @Test public void balancedFirstScreenAndStableAcrossMetadataChangesAndRestart() {
        List<String> yt=list("yt",40),tv=list("tv",40);
        DailyMix mix=new DailyMix("",0,List.of()); List<String> first=mix.update("2026-10-22",11,yt,tv,false);
        assertEquals(80,new HashSet<>(first).size());
        for(int i=0;i<40;i+=2) assertNotEquals(first.get(i).startsWith("tv"),first.get(i+1).startsWith("tv"));
        Collections.reverse(yt); Collections.reverse(tv);
        assertEquals(first,mix.update("2026-10-22",567,yt,tv,false));
        DailyMix restored=new DailyMix(mix.day(),mix.seed(),mix.savedOrder());
        assertEquals(first,restored.update("2026-10-22",123,yt,tv,false));
    }
    @Test public void appendingPagesDoesNotMoveSurvivorsAndOutageDoesNotRewriteOrder() {
        DailyMix mix=new DailyMix("",0,List.of()); var yt=list("yt",6); var tv=list("tv",6);
        var first=mix.update("day",1,yt,tv,false);
        var withoutTv=mix.update("day",1,yt,List.of(),false);
        assertEquals(first.stream().filter(s->s.startsWith("yt")).toList(),withoutTv);
        assertEquals(first,mix.update("day",1,yt,tv,false));
        yt.add("nextYT"); tv.add("nextShow"); yt.add("nextYT");
        var more=mix.update("day",1,yt,tv,false); assertEquals(first,more.subList(0,first.size())); assertEquals(14,more.size());
        tv.remove(first.stream().filter(s->s.startsWith("tv")).findFirst().get());
        var removed=mix.update("day",1,yt,tv,false); assertEquals(more.stream().filter(k->yt.contains(k)||tv.contains(k)).toList(),removed);
    }
    @Test public void latePlexEpisodesFillYouTubeGapsWithoutReorderingExistingVideos() {
        DailyMix mix=new DailyMix("",0,List.of()); var yt=list("yt",40); var tv=list("tv",40);
        var first=mix.update("day",2,yt,List.of(),false);
        var mixed=mix.update("day",2,yt,tv,false);
        assertEquals(first,mixed.stream().filter(k->k.startsWith("yt")).toList());
        for(int i=0;i<mixed.size();i+=2) { assertTrue(mixed.get(i).startsWith("tv")); assertTrue(mixed.get(i+1).startsWith("yt")); }
        assertEquals(mixed,mix.update("day",99,yt,tv,false));
    }
    @Test public void newDayAndExplicitReshuffleChangeOrderWithoutChangingCandidates() {
        DailyMix mix=new DailyMix("",0,List.of()); var yt=list("yt",15); var tv=list("tv",15);
        var first=mix.update("day",1,yt,tv,false); var tomorrow=mix.update("tomorrow",2,yt,tv,false);
        var reshuffled=mix.update("tomorrow",3,yt,tv,true);
        assertNotEquals(first,tomorrow); assertNotEquals(tomorrow,reshuffled);
        assertEquals(new HashSet<>(first),new HashSet<>(reshuffled));
        assertEquals(yt.size(),mix.update("tomorrow",3,yt,List.of(),false).size());
    }
}
