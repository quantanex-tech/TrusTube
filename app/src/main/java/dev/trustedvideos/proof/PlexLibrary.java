// SPDX-License-Identifier: GPL-3.0-or-later
package dev.trustedvideos.proof;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import dev.trustedvideos.proof.core.MediaKey;
import dev.trustedvideos.proof.core.VideoEntry;
import dev.trustedvideos.proof.plex.*;
import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;

/** Runtime grants come only from the selected child's fresh responses, never saved catalog data. */
final class PlexLibrary {
    static final long VALID_MS=30_000;
    static final int HOME_SHOW_BATCH=10, HOME_EPISODE_PAGE=4;
    private record HomeCursor(int next,boolean more) { }
    private final Map<String,HomeCursor> homeCursors=new HashMap<>();
    private boolean homeLoading;
    final PlexStore store;
    final PlexApi api;
    private final ProofState state;
    private final Handler main=new Handler(Looper.getMainLooper());
    private volatile PlexApi.Session session;
    private volatile long validatedAt;
    private volatile long epoch;
    private volatile boolean loading;
    private String status="Plex is not connected";
    private final Map<String,PlexApi.Entry> shows=new LinkedHashMap<>();
    private final Map<String,PlexApi.Entry> episodes=new LinkedHashMap<>();
    private final Object hubLock=new Object();
    private java.util.concurrent.FutureTask<List<PlexApi.Entry>> hubLoad;
    private long hubLoadGeneration=-1,hubVersion,hubLoadVersion;
    private long hubAt,hubGeneration=-1;
    private List<PlexApi.Entry> hubRows=List.of();
    private final Map<String,Target> targets=new HashMap<>();
    private final Map<String,List<Consumer<Target>>> targetLoads=new HashMap<>();
    private final Map<String,List<PlexApi.Entry>> seasons=new HashMap<>();
    private final Map<String,PlexApi.Page> pages=new HashMap<>(),railPages=new HashMap<>();
    private final Set<String> railLoads=new HashSet<>();
    private final List<String[]> libraries=new ArrayList<>();
    private int libraryIndex,showOffset;
    private boolean more,newConnection,requiresParent;
    private final Map<String,Integer> loadedCounts=new HashMap<>();
    record Target(VideoEntry video,String action,long at,String error) { }
    PlexLibrary(Context context,ProofState state) {
        this.state=state; store=new PlexStore(context); api=new PlexApi(store.clientId()); api.guard(store::checkConnection);
        store.access((s,id)-> {
            VideoEntry entry=state.find(key(s,"episode",id));
            if(entry==null || session==null || s.generation()!=session.generation() || !state.policy.allows(entry.id,entry.channelId)
                    || android.os.SystemClock.elapsedRealtime()-validatedAt>VALID_MS*2) throw new IOException("Plex access needs a fresh check.");
        });
    }
    static String key(PlexApi.Session s,String type,String id) { return MediaKey.key(s.childId(),s.serverId(),type,id); }
    synchronized PlexApi.Session session() { return session; }
    synchronized String status() { return status; }
    synchronized boolean loading() { return loading || homeLoading; }
    synchronized boolean hasMore() { return more; }
    synchronized Set<String> homeCandidates() {
        Set<String> keys=new HashSet<>(); for(var row:episodes.entrySet()) if(row.getValue().season()>0) keys.add(row.getKey()); return keys;
    }
    synchronized boolean hasMoreHome() { return more || !homeShows().isEmpty(); }
    private List<String> homeShows() {
        List<String> result=new ArrayList<>();
        for(String show:shows.keySet()) {
            HomeCursor cursor=homeCursors.get(show);
            if(state.policy.allowsChannel(show) && (cursor==null || cursor.more())) result.add(show);
        }
        result.sort(Comparator.comparingInt((String k)->homeCursors.containsKey(k)?homeCursors.get(k).next():0)
                .thenComparing(k->shows.get(k).title(),String.CASE_INSENSITIVE_ORDER).thenComparing(k->k));
        return result;
    }
    synchronized Map<String,PlexApi.Entry> shows() { return new LinkedHashMap<>(shows); }
    synchronized PlexApi.Entry show(String key) { return shows.get(key); }
    synchronized boolean allowed(VideoEntry e) {
        return session!=null && episodes.containsKey(e.id) && android.os.SystemClock.elapsedRealtime()-validatedAt<=VALID_MS*2;
    }
    synchronized List<VideoEntry> rail(String showKey) {
        List<VideoEntry> rows=new ArrayList<>();
        for(PlexApi.Entry e:episodes.values()) if(key(session,"show",e.showId()).equals(showKey)) {
            VideoEntry v=video(session,e); if(state.policy.allows(v.id,v.channelId)) rows.add(v);
        }
        rows.sort(Comparator.comparingInt((VideoEntry v)->episodes.get(v.id).season()).thenComparingInt(v->episodes.get(v.id).episode()));
        return rows;
    }
    void checkExpiry() {
        if(session!=null && android.os.SystemClock.elapsedRealtime()-validatedAt>VALID_MS*2) suspend("Checking the saved Plex connection…",false);
    }
    void refresh(boolean force) {
        synchronized(this) {
            if(!store.configured() || loading || homeLoading || !force && requiresParent || !force && session!=null && android.os.SystemClock.elapsedRealtime()-validatedAt<VALID_MS) return;
            loading=true;
        }
        final long run=epoch;
        state.worker.execute(()-> {
            try {
                PlexApi.Session s=store.load();
                if(s==null) { main.post(()->{ if(run==epoch) { loading=false; } }); return; }
                api.client(store.clientId());
                api.verifyChild(new PlexApi.Child(s.childId(),s.childName(),s.childAccountToken()));
                // Public identity is verified again before sending the saved server credential.
                s=api.connect(new PlexApi.Child(s.childId(),s.childName(),s.childAccountToken()),
                        new PlexApi.Server(s.serverId(),"",s.serverToken(),List.of()),s.origin(),s.generation());
                List<String[]> ls=api.tvLibraries(s);
                Map<String,Integer> counts; synchronized(this) { counts=new HashMap<>(loadedCounts); }
                List<PlexApi.Entry> discovered=new ArrayList<>(); Map<String,Integer> freshCounts=new HashMap<>();
                int nextIndex=0,nextOffset=-1; boolean hasMore=false;
                for(int lib=0;lib<ls.size();lib++) {
                    int limit=counts.getOrDefault(ls.get(lib)[0],lib==0?40:0); if(limit==0) break;
                    PlexApi.Page p; int offset=0;
                    do { p=api.page(s,"/library/sections/"+ls.get(lib)[0]+"/all?type=2",offset,40);
                        discovered.addAll(p.items()); offset+=p.items().size();
                    } while(p.more() && offset<limit);
                    freshCounts.put(ls.get(lib)[0],offset); nextIndex=lib; nextOffset=p.more()?offset:-1;
                    hasMore=p.more() || lib+1<ls.size();
                }
                final PlexApi.Session ready=s; final int cursorIndex=nextIndex,cursorOffset=nextOffset; final boolean remaining=hasMore;
                // Revalidate saved episode bookmarks in bounded metadata batches, never trust disk grants.
                List<String> savedHome=state.savedHomePlexKeys(ProofState.HOME_RESTORE_LIMIT);
                List<String> allSavedHome=state.savedHomePlexKeys(Integer.MAX_VALUE);
                List<PlexApi.Entry> saved=new ArrayList<>(); List<String> ids=new ArrayList<>();
                for(VideoEntry e:state.savedPlexEntries()) { MediaKey.Plex k=MediaKey.plex(e.id);
                    if(k.child().equals(s.childId()) && k.server().equals(s.serverId())) ids.add(k.item()); }
                for(int from=0;from<ids.size();from+=20) {
                    try { saved.addAll(api.entries(s,"/library/metadata/"+String.join(",",ids.subList(from,Math.min(ids.size(),from+20))))); }
                    catch(Exception unavailable) { /* Missing or denied bookmarks stay hidden. */ }
                }
                main.post(()-> {
                    if(run!=epoch) return;
                    synchronized(this) {
                        state.clearPlexGrants(); shows.clear(); episodes.clear(); targets.clear(); targetLoads.clear(); pages.clear(); railPages.clear(); railLoads.clear(); seasons.clear();
                        session=ready; libraries.clear(); libraries.addAll(ls); libraryIndex=cursorIndex; showOffset=cursorOffset;
                        loadedCounts.clear(); loadedCounts.putAll(freshCounts);
                        validatedAt=android.os.SystemClock.elapsedRealtime(); more=remaining; loading=false; requiresParent=false;
                        status="Connected to Plex · "+ready.childName(); acceptShows(ready,discovered);
                        List<PlexApi.Entry> restored=new ArrayList<>();
                        for(PlexApi.Entry e:saved) if(e.type().equals("episode") && shows.containsKey(key(ready,"show",e.showId()))) restored.add(e);
                        try { acceptEpisodes(ready,restored,null); } catch(IOException ignored) { }
                        // A saved favorite alone must not strand pages loaded before Home was opened.
                        if(savedHome.stream().noneMatch(episodes::containsKey)) homeCursors.clear();
                        else for(String omitted:allSavedHome.subList(Math.min(savedHome.size(),allSavedHome.size()),allSavedHome.size())) {
                            VideoEntry entry=state.find(omitted);
                            if(entry!=null && !episodes.containsKey(omitted)) homeCursors.remove(entry.channelId);
                        }
                        if(newConnection) { newConnection=false; state.reshuffle(); }
                    }
                    state.plexChanged();
                    if(homeCursors.isEmpty()) loadHomeMore();
                });
            } catch(Exception e) { main.post(()-> { if(run==epoch) fail("Plex could not be verified. Reconnect or retry in Parent."); }); }
        });
    }
    synchronized void connected() {
        epoch++; validatedAt=0; loading=false; homeLoading=false; homeCursors.clear(); newConnection=true; requiresParent=false; loadedCounts.clear(); session=null;
        state.clearPlexGrants(); state.plexImages.clear(); refresh(true);
    }
    synchronized void disconnect() { store.disconnect(); fail("Plex is not connected"); }
    synchronized void fail(String message) { suspend(message,true); }
    private synchronized void suspend(String message,boolean parentRequired) {
        epoch++; session=null; validatedAt=0; loading=false; homeLoading=false; more=false; requiresParent=parentRequired; status=message;
        shows.clear(); episodes.clear(); targets.clear(); seasons.clear(); pages.clear(); railPages.clear(); railLoads.clear(); targetLoads.clear();
        state.clearPlexGrants(); state.plexChanged();
    }
    void revalidate(PlexApi.Session s) throws Exception {
        store.checkConnection(s);
        if(android.os.SystemClock.elapsedRealtime()-validatedAt<VALID_MS) return;
        api.verifyChild(new PlexApi.Child(s.childId(),s.childName(),s.childAccountToken()));
        api.connect(new PlexApi.Child(s.childId(),s.childName(),s.childAccountToken()),
                new PlexApi.Server(s.serverId(),"",s.serverToken(),List.of()),s.origin(),s.generation());
        verified(s);
    }
    synchronized void verified(PlexApi.Session s) throws IOException {
        if(session==null || s.generation()!=session.generation()) throw new IOException("Plex connection changed.");
        validatedAt=android.os.SystemClock.elapsedRealtime();
    }
    private void acceptShows(PlexApi.Session s,List<PlexApi.Entry> entries) {
        for(PlexApi.Entry e:entries) if(e.type().equals("show")) {
            String k=key(s,"show",e.id()); shows.put(k,e); state.plexShow(k,e.title(),art(k));
        }
    }
    void loadMore() {
        final PlexApi.Session s; final int lib,offset; final long run;
        synchronized(this) {
            if(loading || !more || session==null) return;
            loading=true; s=session; run=epoch;
            if(showOffset<0) { libraryIndex++; showOffset=0; }
            lib=libraryIndex; offset=showOffset;
        }
        state.worker.execute(()-> {
            try {
                PlexApi.Page p=api.page(s,"/library/sections/"+libraries.get(lib)[0]+"/all?type=2",offset,40);
                main.post(()-> { if(run!=epoch) return;
                    synchronized(this) { acceptShows(s,p.items()); loadedCounts.put(libraries.get(lib)[0],offset+p.items().size()); showOffset=p.more()?offset+p.items().size():-1;
                        more=p.more() || lib+1<libraries.size(); loading=false; }
                    state.plexChanged();
                    loadHomeMore();
                });
            } catch(Exception e) { main.post(()->{ if(run==epoch) fail("Plex is unavailable. Retry in Parent."); }); }
        });
    }
    /** Small episode pages rotate across shows before advancing any show's next page. */
    void loadHomeMore() {
        final PlexApi.Session s; final long run; final Map<String,HomeCursor> batch=new LinkedHashMap<>();
        synchronized(this) {
            if(loading || homeLoading || session==null || state.appExpired()) return;
            List<String> candidates=homeShows();
            if(candidates.isEmpty()) { if(more) loadMore(); return; }
            for(String show:candidates.subList(0,Math.min(HOME_SHOW_BATCH,candidates.size())))
                batch.put(show,homeCursors.getOrDefault(show,new HomeCursor(0,true)));
            homeLoading=true; s=session; run=epoch;
        }
        state.plexChanged();
        state.worker.execute(()-> {
            try {
                Map<String,PlexApi.Page> fetched=new LinkedHashMap<>();
                for(var row:batch.entrySet()) {
                    if(run!=epoch) return;
                    if(!state.policy.allowsChannel(row.getKey())) continue;
                    PlexApi.Page page=api.page(s,"/library/metadata/"+MediaKey.plex(row.getKey()).item()+"/allLeaves",row.getValue().next(),HOME_EPISODE_PAGE);
                    for(PlexApi.Entry e:page.items()) if(!e.type().equals("episode") || !key(s,"show",e.showId()).equals(row.getKey()))
                        throw new IOException("Plex Home episode identity changed.");
                    fetched.put(row.getKey(),page);
                }
                main.post(()-> {
                    if(run!=epoch) return;
                    try {
                        synchronized(this) {
                            for(var row:fetched.entrySet()) {
                                PlexApi.Page page=row.getValue();
                                homeCursors.put(row.getKey(),new HomeCursor(batch.get(row.getKey()).next()+page.items().size(),page.more()));
                                if(state.policy.allowsChannel(row.getKey())) acceptEpisodes(s,page.items(),row.getKey());
                            }
                            homeLoading=false;
                        }
                        state.plexChanged();
                    } catch(IOException denied) { fail("Plex Home episodes could not be verified. Retry in Parent."); }
                });
            } catch(Exception e) { main.post(()->{ if(run==epoch) fail("Plex Home episodes could not be verified. Retry in Parent."); }); }
        });
    }
    static String art(String key) { return "plex-art:"+key; }
    static VideoEntry video(PlexApi.Session s,PlexApi.Entry e) {
        return new VideoEntry(key(s,"episode",e.id()),key(s,"show",e.showId()),e.show(),e.label(),art(key(s,"episode",e.id())),e.duration()/1000);
    }
    private synchronized void acceptEpisodes(PlexApi.Session s,List<PlexApi.Entry> rows,String show) throws IOException {
        List<VideoEntry> accepted=new ArrayList<>();
        for(PlexApi.Entry e:rows) {
            String parent=key(s,"show",e.showId());
            if(!e.type().equals("episode") || (show!=null?!parent.equals(show):!shows.containsKey(parent))) throw new IOException("Plex episode identity mismatch.");
            accepted.add(video(s,e));
        }
        for(PlexApi.Entry e:rows) episodes.put(key(s,"episode",e.id()),e);
        state.plexEpisodes(accepted);
    }
    void target(String show,Consumer<Target> callback) {
        final PlexApi.Session s; final long run;
        synchronized(this) {
            Target cached=targets.get(show);
            if(cached!=null && System.currentTimeMillis()-cached.at<VALID_MS) { callback.accept(cached); return; }
            if(session==null || !state.policy.allowsChannel(show)) { callback.accept(new Target(null,"",0,"Show unavailable")); return; }
            List<Consumer<Target>> waiting=targetLoads.get(show);
            if(waiting!=null) { waiting.add(callback); return; }
            targetLoads.put(show,new ArrayList<>(List.of(callback))); s=session; run=epoch;
        }
        state.worker.execute(()-> {
            Target result;
            try {
                PlexApi.Entry container=api.show(s,MediaKey.plex(show).item());
                PlexApi.Entry chosen=container.onDeck();
                if(chosen==null) for(PlexApi.Entry candidate:continuing(s)) {
                    if(candidate.showId().equals(container.id()) && (candidate.season()>0 || candidate.offset()>0)
                            && !state.policy.blockedVideos().contains(key(s,"episode",candidate.id()))) { chosen=candidate; break; }
                }
                String action=container.viewedLeaves()>0 || chosen!=null && chosen.offset()>0?"Continue":"Start";
                if(chosen!=null && (chosen.season()==0 && chosen.offset()==0 || !chosen.showId().equals(container.id()) || state.policy.blockedVideos().contains(key(s,"episode",chosen.id())))) chosen=null;
                PlexApi.Entry first=null;
                if(chosen==null) {
                    int offset=0; boolean again=container.leaves()>0 && container.viewedLeaves()>=container.leaves();
                    do {
                        PlexApi.Page p=api.page(s,"/library/metadata/"+container.id()+"/allLeaves",offset,50);
                        for(PlexApi.Entry e:p.items()) if(e.season()>0 && e.type().equals("episode") && e.showId().equals(container.id())
                                && !state.policy.blockedVideos().contains(key(s,"episode",e.id()))) {
                            if(first==null) first=e;
                            if(e.offset()>0 || e.views()==0) { chosen=e; break; }
                        }
                        if(chosen!=null || again || !p.more()) break;
                        offset+=p.items().size();
                    } while(offset<10000 && run==epoch);
                    if(chosen==null) { chosen=first; action="Start again"; }
                    else action=chosen.offset()>0 || container.viewedLeaves()>0?"Continue":"Start";
                }
                if(chosen==null) throw new NoRegularEpisode();
                PlexApi.Entry exact=api.metadata(s,chosen.id());
                synchronized(this) { if(run!=epoch) return; acceptEpisodes(s,List.of(exact),show); }
                result=new Target(video(s,exact),action,System.currentTimeMillis(),"");
            } catch(NoRegularEpisode e) { result=new Target(null,"",System.currentTimeMillis(),"No regular episodes available. Browse seasons for specials."); }
            catch(Exception e) { main.post(()->{ if(run==epoch) fail("Plex TV could not be verified. Retry in Parent."); }); return; }
            Target done=result;
            main.post(()-> {
                if(run!=epoch) return;
                List<Consumer<Target>> callbacks;
                synchronized(this) { targets.put(show,done); callbacks=targetLoads.remove(show); }
                if(callbacks!=null) callbacks.forEach(c->c.accept(done));
            });
        });
    }
    private List<PlexApi.Entry> continuing(PlexApi.Session s) throws Exception {
        java.util.concurrent.FutureTask<List<PlexApi.Entry>> pending; boolean own; long version;
        synchronized(hubLock) {
            if(hubGeneration==s.generation() && System.currentTimeMillis()-hubAt<10_000) return hubRows;
            version=hubVersion; own=hubLoad==null || hubLoadGeneration!=s.generation() || hubLoadVersion!=version;
            if(own) { hubLoad=new java.util.concurrent.FutureTask<>(()->api.continuing(s)); hubLoadGeneration=s.generation(); hubLoadVersion=version; }
            pending=hubLoad;
        }
        if(own) pending.run();
        try {
            List<PlexApi.Entry> rows=pending.get();
            synchronized(hubLock) {
                if(hubLoad==pending && hubVersion==version) { hubRows=rows; hubAt=System.currentTimeMillis(); hubGeneration=s.generation(); }
            }
            return rows;
        } finally { if(own) synchronized(hubLock) { if(hubLoad==pending) hubLoad=null; } }
    }
    private void invalidateHub() { synchronized(hubLock) { hubAt=0; hubVersion++; } }
    private static final class NoRegularEpisode extends IOException { }
    synchronized void observed(PlexApi.Session s,PlexApi.Entry entry) throws IOException {
        if(session==null || session.generation()!=s.generation()) return;
        acceptEpisodes(s,List.of(entry),key(s,"show",entry.showId()));
    }
    PlexApi.Entry episode(String key) { synchronized(this) { return episodes.get(key); } }
    synchronized void invalidateTarget(String key) { targets.remove(key); invalidateHub(); }
    synchronized void invalidateTargets() { targets.clear(); invalidateHub(); }
    synchronized List<PlexApi.Entry> seasons(String show) { return seasons.get(show); }
    synchronized PlexApi.Page episodes(String season) { return pages.get(season); }
    void browse(String show,String season,boolean morePage,Consumer<String> done) {
        final PlexApi.Session s=session; final long run=epoch;
        if(s==null || !state.policy.allowsChannel(show)) return;
        state.worker.execute(()-> {
            try {
                if(season==null) {
                    List<PlexApi.Entry> rows=api.entries(s,"/library/metadata/"+MediaKey.plex(show).item()+"/children");
                    for(PlexApi.Entry e:rows) if(!e.type().equals("season") || !e.parentId().equals(MediaKey.plex(show).item())) throw new IOException("Season identity changed.");
                    main.post(()-> { if(run!=epoch) return; synchronized(this) { seasons.put(show,rows); } done.accept(""); });
                } else {
                    PlexApi.Page old; synchronized(this) { old=pages.get(season); }
                    int offset=morePage && old!=null?old.items().size():0;
                    PlexApi.Page p=api.page(s,"/library/metadata/"+PlexApi.id(season)+"/children",offset,50);
                    for(PlexApi.Entry e:p.items()) if(!e.parentId().equals(season)) throw new IOException("Season identity changed.");
                    synchronized(this) { if(run!=epoch) return; acceptEpisodes(s,p.items(),show); }
                    List<PlexApi.Entry> all=new ArrayList<>(); if(offset>0) all.addAll(old.items()); all.addAll(p.items());
                    main.post(()-> { if(run!=epoch) return; synchronized(this) { pages.put(season,new PlexApi.Page(all,p.total(),0)); } done.accept(""); });
                }
            } catch(Exception e) { main.post(()-> { if(run==epoch) { fail("Plex TV could not be verified. Retry in Parent."); done.accept("Plex TV is unavailable."); } }); }
        });
    }
    synchronized boolean canLoadRail(String show) { PlexApi.Page p=railPages.get(show); return p==null || p.more(); }
    void loadRail(String show,boolean morePage,Runnable done) {
        final PlexApi.Session s; final long run; final int offset; final PlexApi.Page old;
        synchronized(this) {
            old=railPages.get(show);
            if(session==null || !state.policy.allowsChannel(show) || railLoads.contains(show) || old!=null && (!morePage || !old.more())) return;
            s=session; run=epoch; offset=old==null?0:old.items().size(); railLoads.add(show);
        }
        state.worker.execute(()-> {
            try {
                PlexApi.Page p=api.page(s,"/library/metadata/"+MediaKey.plex(show).item()+"/allLeaves",offset,50);
                main.post(()-> {
                    if(run!=epoch) return;
                    try {
                        synchronized(this) {
                            acceptEpisodes(s,p.items(),show); List<PlexApi.Entry> rows=new ArrayList<>();
                            if(old!=null) rows.addAll(old.items()); rows.addAll(p.items());
                            railPages.put(show,new PlexApi.Page(rows,p.total(),0)); railLoads.remove(show);
                        }
                        done.run();
                    } catch(IOException denied) { fail("Plex episodes could not be verified. Retry in Parent."); }
                });
            } catch(Exception e) { main.post(()->{ if(run==epoch) fail("Plex episodes could not be verified. Retry in Parent."); }); }
        });
    }
    synchronized PlexApi.Entry artworkEntry(String key) { return shows.containsKey(key)?shows.get(key):episodes.get(key); }
}
