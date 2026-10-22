// SPDX-License-Identifier: GPL-3.0-or-later
package dev.trustedvideos.proof.plex;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Restricted personal-TV API. No owner credential is a media credential. */
public final class PlexApi {
    public static final String PRODUCT = "TrusTube";
    static final String PROFILE_EXTRA="add-transcode-target(type=videoProfile&context=streaming&protocol=hls&container=mpegts&videoCodec=h264&audioCodec=aac&replace=true)";
    private static final String CLOUD = "https://plex.tv";
    public interface Transport { Reply request(String method, URI uri, Map<String,String> headers, String body) throws Exception; }
    public record Reply(int status, String body) { }
    public static final class RequestFailure extends IOException {
        public final int status;
        RequestFailure(int status,String stage) { super("Plex "+stage+" denied or unavailable (HTTP "+status+")."); this.status=status; }
    }
    public static final class ConnectionFailure extends IOException {
        ConnectionFailure(String message,Throwable cause) { super(message,cause); }
    }
    public record Pin(String id, String code, boolean jwt) { }
    public record HomeUser(String id, String name, boolean restricted, boolean admin, boolean protectedPin) { }
    public record Child(String id, String name, String accountToken) { @Override public String toString() { return name; } }
    public record Server(String id, String name, String token, List<String> connections) { @Override public String toString() { return name; } }
    public record Entry(String id, String title, String type, String show, int season, int episode,
                        long duration, long offset, int views, String part,
                        String showId,String parentId,String thumb,int leaves,int viewedLeaves,long lastViewed,
                        String container,String videoCodec,List<Stream> streams,Entry onDeck) {
        public Entry(String id,String title,String type,String show,int season,int episode,long duration,long offset,int views,String part) {
            this(id,title,type,show,season,episode,duration,offset,views,part,"","","",0,0,0,"","",List.of(),null);
        }
        public String label() { return type.equals("episode") ? show + " · S" + season + " E" + episode + " · " + title : title; }
    }
    public record Stream(String id,int type,String label,String language,boolean selected) { }
    public record Page(List<Entry> items,int total,int offset) { public boolean more() { return !items.isEmpty() && offset+items.size()<total; } }
    public record Session(String childId, String childName, String childAccountToken, String serverId,
                          String serverToken, String origin, long generation) {
        @Override public String toString() { return "Plex session (credentials hidden)"; }
    }
    public interface SessionGuard { void check(Session session) throws IOException; }
    private volatile SessionGuard sessionGuard=s->{};
    public void guard(SessionGuard guard) { sessionGuard=guard; }
    private final Transport transport;
    private volatile String clientId;
    public void client(String value) { clientId=value; }
    public PlexApi(String clientId) { this(clientId, new HttpTransport()); }
    public PlexApi(String clientId, Transport transport) { this.clientId=clientId; this.transport=transport; }

    private Map<String,String> headers(String token) { return mediaHeaders(clientId,token); }
    public static Map<String,String> mediaHeaders(String clientId,String token) {
        return mediaHeaders(clientId,token,null);
    }
    public static Map<String,String> mediaHeaders(String clientId,String token,String playbackSession) {
        Map<String,String> h=new LinkedHashMap<>();
        h.put("Accept","application/json"); h.put("X-Plex-Product",PRODUCT);
        h.put("X-Plex-Client-Identifier",clientId); h.put("X-Plex-Version","0.1");
        h.put("X-Plex-Platform","Android"); h.put("X-Plex-Device-Name",PRODUCT);
        h.put("X-Plex-Client-Profile-Name","Generic"); h.put("X-Plex-Client-Profile-Extra",PROFILE_EXTRA);
        h.put("User-Agent","TrusTube/0.9");
        if (token!=null && !token.isEmpty()) h.put("X-Plex-Token",token);
        if (playbackSession!=null) h.put("X-Plex-Session-Identifier",playbackSession);
        return h;
    }
    private Reply request(String method, URI uri, String token, String body) throws Exception {
        return request(method,uri,token,body,null);
    }
    private Reply request(String method, URI uri, String token, String body,String playbackSession) throws Exception {
        if (!"https".equals(uri.getScheme()) || uri.getUserInfo()!=null || uri.getFragment()!=null)
            throw new IOException("HTTPS without embedded credentials is required.");
        Reply reply;
        try { reply=transport.request(method,uri,mediaHeaders(clientId,token,playbackSession),body); }
        catch(java.net.SocketTimeoutException e) { throw new ConnectionFailure("Connection timed out at "+uri.getHost()+" ("+stage(uri)+"). Confirm the normal HTTPS Plex route.",e); }
        if(reply.status<200 || reply.status>=300) throw new RequestFailure(reply.status,stage(uri));
        return reply;
    }
    private static String stage(URI uri) {
        String p=uri.getPath();
        if(p.equals("/identity")) return "server identity check";
        if(p.contains("/pins")) return "device-code authorization";
        if(p.contains("resources")) return "child server discovery";
        if(p.contains("/api/v2/user")) return "child identity check";
        if(p.contains("home/users")) return "Home profile selection";
        return "Plex request";
    }
    public static String query(Map<String,?> pairs) {
        StringBuilder out=new StringBuilder();
        for(var p:pairs.entrySet()) {
            if(out.length()>0) out.append('&');
            out.append(enc(p.getKey())).append('=').append(enc(String.valueOf(p.getValue())));
        }
        return out.toString();
    }
    private static String enc(String s) { return URLEncoder.encode(s,StandardCharsets.UTF_8); }
    public static String id(String s) throws IOException {
        if(s==null || !s.matches("[0-9]{1,18}")) throw new IOException("A numeric Plex item/profile ID is required.");
        return s;
    }
    public static URI origin(String value) throws IOException {
        try {
            URI u=URI.create(value);
            if(!"https".equals(u.getScheme()) || u.getHost()==null || u.getUserInfo()!=null
                    || u.getQuery()!=null || u.getFragment()!=null || !(u.getPath().isEmpty() || u.getPath().equals("/")))
                throw new IOException("Choose an HTTPS server origin, with no path or credentials.");
            return new URI("https",null,u.getHost().toLowerCase(java.util.Locale.ROOT),u.getPort(),null,null,null);
        } catch(java.net.URISyntaxException|IllegalArgumentException e) { throw new IOException("Invalid HTTPS server origin."); }
    }
    public static URI mediaUri(Session s,String path) throws IOException {
        URI base=origin(s.origin); URI u;
        try { u=base.resolve(path); } catch(IllegalArgumentException e) { throw new IOException("Invalid Plex path."); }
        if(!base.getScheme().equals(u.getScheme()) || !base.getRawAuthority().equals(u.getRawAuthority())
                || u.getUserInfo()!=null || u.getFragment()!=null || path.contains("\\")
                || path.toLowerCase(java.util.Locale.ROOT).contains("x-plex-token")
                || path.toLowerCase(java.util.Locale.ROOT).contains("%2e") || path.contains(".."))
            throw new IOException("Refused a media request outside the selected server.");
        String p=u.getPath();
        if(!(p.startsWith("/library/parts/") || p.startsWith("/video/:/transcode/universal/")))
            throw new IOException("Unexpected Plex media path.");
        return u;
    }
    private JSONObject json(Reply reply) throws Exception { return new JSONObject(reply.body); }
    static final class XmlNode {
        final String name; final Map<String,String> attributes=new LinkedHashMap<>(); final List<XmlNode> children=new ArrayList<>();
        XmlNode(String name) { this.name=name; }
        String getAttribute(String key) { return attributes.getOrDefault(key,""); }
    }
    static XmlNode xml(String body) throws Exception {
        if(body.contains("<!DOCTYPE") || body.contains("<!ENTITY")) throw new IOException("Plex XML cannot contain document declarations.");
        XmlPullParser p=XmlPullParserFactory.newInstance().newPullParser();
        p.setInput(new java.io.StringReader(body)); XmlNode root=null;
        java.util.Deque<XmlNode> stack=new java.util.ArrayDeque<>();
        for(int event=p.getEventType();event!=XmlPullParser.END_DOCUMENT;event=p.nextToken()) {
            if(event==XmlPullParser.DOCDECL) throw new IOException("Plex XML cannot contain document declarations.");
            if(event==XmlPullParser.START_TAG) {
                XmlNode node=new XmlNode(p.getName());
                for(int i=0;i<p.getAttributeCount();i++) node.attributes.put(p.getAttributeName(i),p.getAttributeValue(i));
                if(root==null) root=node; else if(!stack.isEmpty()) stack.peek().children.add(node);
                stack.push(node);
            } else if(event==XmlPullParser.END_TAG) stack.pop();
        }
        if(root==null) throw new IOException("Plex XML has no root element."); return root;
    }
    public Pin createPin(JSONObject jwk) throws Exception {
        JSONObject body=new JSONObject().put("strong",false);
        if(jwk!=null) body.put("jwk",jwk);
        JSONObject pin=json(request("POST",URI.create("https://clients.plex.tv/api/v2/pins"),null,body.toString()));
        return new Pin(String.valueOf(pin.get("id")),pin.getString("code"),jwk!=null);
    }
    public String checkPin(Pin pin,String deviceJwt) throws Exception {
        String path="https://clients.plex.tv/api/v2/pins/"+id(pin.id);
        if(pin.jwt) path+="?"+query(Map.of("deviceJWT",deviceJwt));
        JSONObject response=json(request("GET",URI.create(path),null,null));
        return response.isNull("authToken")?"":response.optString("authToken","");
    }
    public List<HomeUser> users(String ownerToken) throws Exception {
        String body=request("GET",URI.create(CLOUD+"/api/home/users"),ownerToken,null).body;
        List<HomeUser> out=new ArrayList<>();
        if(body.trim().startsWith("{")) {
            JSONObject root=new JSONObject(body); if(root.has("MediaContainer")) root=root.getJSONObject("MediaContainer");
            JSONArray rows=root.optJSONArray("users"); if(rows==null) rows=root.optJSONArray("User");
            if(rows==null) throw new IOException("Unrecognized Plex Home response.");
            for(int i=0;i<rows.length();i++) { JSONObject r=rows.getJSONObject(i);
                out.add(new HomeUser(String.valueOf(r.get("id")),r.optString("title",r.optString("username")),truth(r.opt("restricted")),truth(r.opt("admin")),truth(r.opt("protected"))));
            }
            return out;
        }
        XmlNode root=xml(body);
        for(XmlNode row:root.children) {
            if(!row.name.equals("User")) continue;
            out.add(new HomeUser(row.getAttribute("id"),row.getAttribute("title"),flag(row,"restricted"),flag(row,"admin"),flag(row,"protected")));
        }
        return out;
    }
    static boolean flag(XmlNode e,String name) { return e.getAttribute(name).equals("1") || e.getAttribute(name).equals("true"); }
    private static boolean truth(Object value) { return "1".equals(String.valueOf(value)) || "true".equals(String.valueOf(value)); }
    private static JSONObject attributes(String body) throws Exception {
        if(body.trim().startsWith("{")) {
            JSONObject root=new JSONObject(body);
            if(root.has("MediaContainer")) return root.getJSONObject("MediaContainer");
            if(root.optJSONObject("user")!=null) return root.getJSONObject("user"); return root;
        }
        JSONObject root=new JSONObject(); for(var a:xml(body).attributes.entrySet()) root.put(a.getKey(),a.getValue()); return root;
    }
    public Child selectChild(String ownerToken,HomeUser user,String pin) throws Exception {
        if(user.admin || !user.restricted) throw new IOException("TrusTube requires a restricted, non-admin child profile.");
        String url=CLOUD+"/api/home/users/"+id(user.id)+"/switch";
        if(!pin.isEmpty()) url+="?"+query(Map.of("pin",pin));
        JSONObject result=attributes(request("POST",URI.create(url),ownerToken,"{}").body);
        String childToken=result.optString("authToken","");
        if(childToken.isEmpty() || childToken.equals("null")) childToken=result.optString("authenticationToken","");
        if(!user.id.equals(result.optString("id")) || childToken.isEmpty() || childToken.equals("null")) throw new IOException("Plex child identity was not confirmed.");
        Child child=new Child(user.id,user.name,childToken);
        // A newly minted child token can briefly lag on Plex's identity endpoint.
        // Retry only that same selected child, never an owner or cached credential.
        for(int attempt=0;;attempt++) {
            try { verifyChild(child); break; }
            catch(RequestFailure e) {
                if(e.status!=401 || attempt>=2) throw e;
                Thread.sleep(750L*(attempt+1));
            }
        }
        return child;
    }
    public void verifyChild(Child child) throws Exception {
        JSONObject u=json(request("GET",URI.create(CLOUD+"/api/v2/user"),child.accountToken,null));
        if(!child.id.equals(String.valueOf(u.get("id"))) || !truth(u.opt("restricted")))
            throw new IOException("Saved account is not the selected restricted child. Reconnect in Parent.");
    }
    public List<Server> servers(Child child) throws Exception {
        verifyChild(child);
        JSONArray resources=new JSONArray(request("GET",URI.create("https://clients.plex.tv/api/v2/resources?includeHttps=1&includeRelay=1&includeIPv6=1"),child.accountToken,null).body);
        List<Server> result=new ArrayList<>();
        for(int i=0;i<resources.length();i++) {
            JSONObject r=resources.getJSONObject(i);
            if(!r.optString("provides").contains("server") || r.optString("accessToken").isEmpty()) continue;
            List<String> connections=new ArrayList<>(); JSONArray c=r.optJSONArray("connections");
            if(c!=null) for(int j=0;j<c.length();j++) {
                String url=c.getJSONObject(j).optString("uri");
                if(url.startsWith("https://")) connections.add(url);
            }
            result.add(new Server(r.getString("clientIdentifier"),r.optString("name","Plex server"),r.getString("accessToken"),connections));
        }
        return result;
    }
    public Session connect(Child child,Server server,String url,long generation) throws Exception {
        URI o=origin(url);
        JSONObject identity=attributes(request("GET",o.resolve("/identity"),null,null).body);
        if(!server.id.equals(identity.optString("machineIdentifier"))) throw new IOException("Server identity does not match the selected Plex resource.");
        return new Session(child.id,child.name,child.accountToken,server.id,server.token,o.toString(),generation);
    }
    public JSONObject server(Session s,String method,String path) throws Exception {
        return server(s,method,path,null);
    }
    private JSONObject server(Session s,String method,String path,String playbackSession) throws Exception {
        sessionGuard.check(s);
        URI base=origin(s.origin),target=base.resolve(path);
        if(!base.getScheme().equals(target.getScheme()) || !base.getRawAuthority().equals(target.getRawAuthority())
                || target.getUserInfo()!=null || target.getFragment()!=null) throw new IOException("Refused an API request outside the selected server.");
        Reply reply=request(method,target,s.serverToken,method.equals("POST") || method.equals("PUT")?"{}":null,playbackSession);
        sessionGuard.check(s);
        return reply.body.isBlank()?new JSONObject():json(reply);
    }
    public List<String[]> tvLibraries(Session s) throws Exception {
        JSONObject root=server(s,"GET","/library/sections").getJSONObject("MediaContainer");
        JSONArray a=root.optJSONArray("Directory"); List<String[]> rows=new ArrayList<>();
        if(a!=null) for(int i=0;i<a.length();i++) {
            JSONObject r=a.getJSONObject(i); if(r.optString("type").equals("show")) rows.add(new String[]{id(r.getString("key")),r.optString("title")});
        }
        return rows;
    }
    public List<Entry> entries(Session s,String path) throws Exception {
        JSONObject root=server(s,"GET",path).getJSONObject("MediaContainer");
        JSONArray rows=root.optJSONArray("Metadata"); List<Entry> result=new ArrayList<>();
        if(rows!=null) for(int i=0;i<rows.length();i++) result.add(entry(rows.getJSONObject(i)));
        return result;
    }
    public Entry metadata(Session s,String key) throws Exception {
        List<Entry> entries=entries(s,"/library/metadata/"+id(key)+"?includeMarkers=1");
        if(entries.size()!=1 || !entries.get(0).id.equals(key)) throw new IOException("Episode is unavailable to the selected child.");
        return entries.get(0);
    }
    public Page page(Session s,String path,int offset,int size) throws Exception {
        JSONObject root=server(s,"GET",path+(path.contains("?")?"&":"?")+"X-Plex-Container-Start="+Math.max(0,offset)+"&X-Plex-Container-Size="+Math.min(50,Math.max(1,size))).getJSONObject("MediaContainer");
        JSONArray rows=root.optJSONArray("Metadata"); List<Entry> result=new ArrayList<>();
        if(rows!=null) for(int i=0;i<rows.length();i++) result.add(entry(rows.getJSONObject(i)));
        return new Page(result,root.optInt("totalSize",offset+result.size()),offset);
    }
    public List<Entry> continuing(Session s) throws Exception {
        JSONObject root=server(s,"GET","/hubs/continueWatching?count=100").getJSONObject("MediaContainer");
        List<Entry> rows=new ArrayList<>(); addPersonalEpisodes(root.optJSONArray("Metadata"),rows);
        JSONArray hubs=root.optJSONArray("Hub");
        if(hubs!=null) for(int i=0;i<hubs.length();i++) addPersonalEpisodes(hubs.getJSONObject(i).optJSONArray("Metadata"),rows);
        return rows;
    }
    private static void addPersonalEpisodes(JSONArray items,List<Entry> rows) throws Exception {
        if(items==null) return;
        for(int i=0;i<items.length();i++) {
            JSONObject r=items.getJSONObject(i);
            if(r.optString("type").equals("episode") && r.optString("ratingKey").matches("[0-9]{1,18}")
                    && r.optString("grandparentRatingKey").matches("[0-9]{1,18}")) rows.add(entry(r));
        }
    }
    public Entry show(Session s,String key) throws Exception {
        List<Entry> rows=entries(s,"/library/metadata/"+id(key)+"?includeOnDeck=1");
        if(rows.size()!=1 || !rows.get(0).id.equals(key) || !rows.get(0).type.equals("show")) throw new IOException("This show is unavailable.");
        return rows.get(0);
    }
    static Entry entry(JSONObject r) throws Exception {
        String part="",container="",codec=""; List<Stream> streams=new ArrayList<>(); JSONArray media=r.optJSONArray("Media");
        if(media!=null && media.length()>0) {
            JSONObject m=media.getJSONObject(0); container=m.optString("container"); codec=m.optString("videoCodec");
            JSONArray parts=m.optJSONArray("Part");
            if(parts!=null && parts.length()>0) {
                JSONObject p=parts.getJSONObject(0); part=p.optString("key"); JSONArray ss=p.optJSONArray("Stream");
                if(ss!=null) for(int i=0;i<ss.length();i++) { JSONObject t=ss.getJSONObject(i);
                    streams.add(new Stream(id(String.valueOf(t.get("id"))),t.optInt("streamType"),
                        t.optString("displayTitle",t.optString("title",t.optString("language","Track "+(i+1)))),t.optString("languageCode"),truth(t.opt("selected"))));
                }
            }
        }
        Entry deck=null; JSONObject d=r.optJSONObject("OnDeck");
        if(d!=null && d.optJSONArray("Metadata")!=null && d.getJSONArray("Metadata").length()>0) deck=entry(d.getJSONArray("Metadata").getJSONObject(0));
        return new Entry(id(String.valueOf(r.get("ratingKey"))),r.optString("title"),r.optString("type"),
                r.optString("grandparentTitle"),r.optInt("parentIndex"),r.optInt("index"),r.optLong("duration"),r.optLong("viewOffset"),r.optInt("viewCount"),part,
                r.optString("grandparentRatingKey"),r.optString("parentRatingKey"),r.optString("thumb"),r.optInt("leafCount"),r.optInt("viewedLeafCount"),r.optLong("lastViewedAt"),container,codec,List.copyOf(streams),deck);
    }
    /** Only the selected child's part settings are changed; never server/library settings. */
    public void selectStream(Session s,Entry e,String audio,String subtitle) throws Exception {
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("^/library/parts/([0-9]+)/.*").matcher(e.part);
        if(!m.matches()) throw new IOException("Stream selection is unavailable for this media part.");
        Map<String,Object> q=new LinkedHashMap<>(); q.put("allParts",0);
        if(audio!=null) q.put("audioStreamID",id(audio));
        if(subtitle!=null) q.put("subtitleStreamID",id(subtitle));
        server(s,"PUT","/library/parts/"+m.group(1)+"?"+query(q));
    }
    public URI playback(Session s,Entry e,boolean transcode,String sessionId) throws Exception {
        return playback(s,e,transcode,sessionId,e.offset);
    }
    public URI playback(Session s,Entry e,boolean transcode,String sessionId,long position) throws Exception {
        return playback(s,e,transcode,sessionId,position,sessionId);
    }
    public URI playback(Session s,Entry e,boolean transcode,String sessionId,long position,String playbackSession) throws Exception {
        Entry current=metadata(s,e.id);
        if(!current.showId.equals(e.showId)) throw new IOException("Plex episode container changed.");
        if(!current.type.equals("episode")) throw new IOException("Only TV episodes belong in TrusTube.");
        if(!transcode) {
            if(current.part.isEmpty()) throw new IOException("No direct media part is available.");
            return mediaUri(s,current.part);
        }
        Map<String,Object> q=transcodeParameters(e.id,sessionId,position);
        JSONObject d=server(s,"GET","/video/:/transcode/universal/decision?"+query(q),playbackSession).getJSONObject("MediaContainer");
        int code=d.optInt("generalDecisionCode",2000);
        if(code<1000 || code>=2000) throw new IOException("Plex rejected transcoded playback (decision "+code+").");
        return mediaUri(s,"/video/:/transcode/universal/start.m3u8?"+query(q));
    }
    private static Map<String,Object> transcodeParameters(String key,String sessionId,long position) {
        Map<String,Object> q=new LinkedHashMap<>();
        q.put("path","/library/metadata/"+key); q.put("mediaIndex",0); q.put("partIndex",0);
        q.put("protocol","hls"); q.put("directPlay",0); q.put("directStream",1);
        q.put("videoResolution","1280x720"); q.put("maxVideoBitrate",2000); q.put("videoBitrate",2000);
        q.put("videoQuality",70); q.put("session",sessionId); q.put("hasMDE",1);
        q.put("offset",Math.max(0,position)/1000.0);
        return q;
    }
    public void timeline(Session s,Entry e,String state,long time,String sessionId) throws Exception {
        server(s,"POST","/:/timeline?"+query(Map.of("ratingKey",e.id,"key","/library/metadata/"+e.id,
                "state",state,"time",Math.max(0,time),"duration",e.duration)),sessionId);
    }
    public String negativeProbe(Session s,String key,String part) throws Exception {
        id(key); StringBuilder result=new StringBuilder();
        Map<String,String> paths=new LinkedHashMap<>();
        paths.put("metadata","/library/metadata/"+key);
        paths.put("artwork","/library/metadata/"+key+"/thumb");
        String probeSession=java.util.UUID.randomUUID().toString();
        String conversion=query(transcodeParameters(key,probeSession,0));
        paths.put("transcode decision","/video/:/transcode/universal/decision?"+conversion);
        paths.put("HLS playlist","/video/:/transcode/universal/start.m3u8?"+conversion);
        if(!part.isBlank()) paths.put("direct part",mediaUri(s,part).getRawPath());
        for(var p:paths.entrySet()) {
            Map<String,String> probeHeaders=mediaHeaders(clientId,s.serverToken,probeSession); probeHeaders.put("Range","bytes=0-4095");
            Reply reply=transport.request("GET",origin(s.origin).resolve(p.getValue()),probeHeaders,null);
            boolean denied=reply.status==401 || reply.status==403 || reply.status==404;
            result.append(p.getKey()).append(": HTTP ").append(reply.status).append(denied?" (denied)":" (inspect; not a denial proof)").append('\n');
        }
        return result.toString();
    }
    static final class HttpTransport implements Transport {
        @Override public Reply request(String method,URI uri,Map<String,String> headers,String body) throws Exception {
            HttpURLConnection c=(HttpURLConnection)uri.toURL().openConnection();
            c.setConnectTimeout(15000); c.setReadTimeout(20000); c.setInstanceFollowRedirects(false); c.setRequestMethod(method);
            for(var h:headers.entrySet()) c.setRequestProperty(h.getKey(),h.getValue());
            try {
                if(body!=null) {
                    c.setDoOutput(true); c.setRequestProperty("Content-Type","application/json");
                    try(var out=c.getOutputStream()) { out.write(body.getBytes(StandardCharsets.UTF_8)); }
                }
                int status=c.getResponseCode();
                InputStream input=status>=400?c.getErrorStream():c.getInputStream();
                String text="";
                if(input!=null) try(input; ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                    byte[] buffer=new byte[8192]; int n;
                    while((n=input.read(buffer))!=-1) { if(out.size()+n>4*1024*1024) throw new IOException("Plex response exceeds safe bounds."); out.write(buffer,0,n); if(headers.containsKey("Range")) break; }
                    text=out.toString(StandardCharsets.UTF_8.name());
                }
                return new Reply(status,text);
            } finally { c.disconnect(); }
        }
    }
}
