// SPDX-License-Identifier: GPL-3.0-or-later
package dev.trustedvideos.proof;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import dev.trustedvideos.proof.core.MediaKey;
import dev.trustedvideos.proof.plex.PlexApi;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;

/** Private memory-only artwork, scoped to the selected child's verified metadata and origin. */
final class PlexArtwork {
    static ImageCache cache(Context context,ProofState state) {
        return new ImageCache(new File(context.getCacheDir(),"unused-private-art"),url->fetch(state,url),true,
                url->allowed(state,url));
    }
    private static boolean allowed(ProofState state,String url) {
        if(url==null || !url.startsWith("plex-art:")) return false;
        String key=url.substring(9); MediaKey.Plex id=MediaKey.plex(key);
        PlexApi.Session s=state.plex.session();
        if(id==null || s==null || !id.child().equals(s.childId()) || !id.server().equals(s.serverId())) return false;
        try { check(state,s,key,id); return true; } catch(IOException denied) { return false; }
    }
    private static Bitmap fetch(ProofState state,String url) throws Exception {
        String key=url.substring(9); MediaKey.Plex id=MediaKey.plex(key);
        PlexApi.Session s=state.plex.session(); PlexApi.Entry item=state.plex.artworkEntry(key);
        if(s==null || item==null || !s.childId().equals(id.child()) || !s.serverId().equals(id.server())) return null;
        check(state,s,key,id);
        String path=item.thumb();
        if(!path.matches("/library/metadata/"+id.item()+"/thumb(?:/[0-9]+)?")) return null;
        URI uri=PlexApi.origin(s.origin()).resolve(path);
        HttpURLConnection c=(HttpURLConnection)uri.toURL().openConnection();
        c.setInstanceFollowRedirects(false); c.setConnectTimeout(10000); c.setReadTimeout(15000);
        for(var h:PlexApi.mediaHeaders(state.plex.store.clientId(),s.serverToken()).entrySet()) c.setRequestProperty(h.getKey(),h.getValue());
        try {
            if(c.getResponseCode()!=200) return null;
            byte[] data;
            try(InputStream in=c.getInputStream(); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                byte[] b=new byte[8192]; int n;
                while((n=in.read(b))!=-1) { check(state,s,key,id); if(out.size()+n>4*1024*1024) return null; out.write(b,0,n); }
                data=out.toByteArray();
            }
            check(state,s,key,id);
            BitmapFactory.Options o=new BitmapFactory.Options(); o.inJustDecodeBounds=true;
            BitmapFactory.decodeByteArray(data,0,data.length,o); o.inSampleSize=1;
            while(o.outWidth/o.inSampleSize>640 || o.outHeight/o.inSampleSize>640) o.inSampleSize*=2;
            o.inJustDecodeBounds=false; return BitmapFactory.decodeByteArray(data,0,data.length,o);
        } finally { c.disconnect(); }
    }
    private static void check(ProofState state,PlexApi.Session s,String key,MediaKey.Plex id) throws IOException {
        PlexApi.Session current=state.plex.session();
        if(current==null || current.generation()!=s.generation() || !(id.type().equals("show")
                ?state.policy.allowsChannel(key):state.find(key)!=null && state.policy.allows(key,state.find(key).channelId)))
            throw new IOException("Artwork permission changed.");
    }
}
