// SPDX-License-Identifier: GPL-3.0-or-later
package dev.trustedvideos.proof.plex;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSpec;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;

/** Authenticated byte requests cannot follow redirects or leave the selected origin. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
public final class PlexMediaSource extends BaseDataSource {
    private final PlexStore store; private final PlexApi.Session session; private final String episode,client,playbackSession;
    private HttpURLConnection connection; private InputStream input; private Uri uri; private long remaining; private boolean opened;
    public PlexMediaSource(PlexStore store,PlexApi.Session session,String episode,String client,String playbackSession) {
        super(true); this.store=store; this.session=session; this.episode=episode; this.client=client; this.playbackSession=playbackSession;
    }
    @Override public long open(DataSpec spec) throws IOException {
        store.check(session,episode); transferInitializing(spec);
        URI target=PlexApi.mediaUri(session,spec.uri.toString()); uri=spec.uri;
        connection=(HttpURLConnection)target.toURL().openConnection();
        connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(15000); connection.setReadTimeout(20000);
        for(var header:PlexApi.mediaHeaders(client,session.serverToken(),playbackSession).entrySet()) connection.setRequestProperty(header.getKey(),header.getValue());
        if(spec.position>0 || spec.length!=C.LENGTH_UNSET) connection.setRequestProperty("Range","bytes="+spec.position+"-"+(spec.length==C.LENGTH_UNSET?"":spec.position+spec.length-1));
        int status=connection.getResponseCode();
        if(status!=200 && status!=206) { connection.disconnect(); throw new IOException("Plex media denied/unavailable (HTTP "+status+")."); }
        input=connection.getInputStream();
        if(spec.position>0 && status==200) { input.close(); connection.disconnect(); throw new IOException("Server ignored a media range; seeking cannot be verified."); }
        long contentLength=C.LENGTH_UNSET;
        try { contentLength=Long.parseLong(connection.getHeaderField("Content-Length")); } catch(RuntimeException ignored) { }
        remaining=spec.length!=C.LENGTH_UNSET?spec.length:contentLength;
        if(remaining<0) remaining=C.LENGTH_UNSET;
        opened=true; transferStarted(spec); return remaining;
    }
    @Override public int read(byte[] buffer,int offset,int length) throws IOException {
        store.check(session,episode);
        if(length==0) return 0; if(remaining==0) return C.RESULT_END_OF_INPUT;
        int n=input.read(buffer,offset,remaining==C.LENGTH_UNSET?length:(int)Math.min(length,remaining));
        if(n<0) return C.RESULT_END_OF_INPUT; if(remaining!=C.LENGTH_UNSET) remaining-=n; bytesTransferred(n); return n;
    }
    @Override public Uri getUri() { return uri; }
    @Override public void close() throws IOException {
        try { if(input!=null) input.close(); } finally { input=null; if(connection!=null) connection.disconnect(); connection=null; if(opened) { opened=false; transferEnded(); } }
    }
}
