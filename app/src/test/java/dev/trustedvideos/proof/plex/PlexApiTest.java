package dev.trustedvideos.proof.plex;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class PlexApiTest {
    private PlexApi.Session session() { return new PlexApi.Session("2","Child","CHILD_ACCOUNT","server","CHILD_SERVER","https://media.example",7); }
    @Test public void hlsResumeAndSeekPassSecondsToPlexAndKeepPlaylistTimelinePosition() throws Exception {
        List<java.net.URI> seen=new ArrayList<>();
        PlexApi api=new PlexApi("client",(m,u,h,b)->{
            seen.add(u);
            if(u.getPath().endsWith("decision")) return new PlexApi.Reply(200,"{\"MediaContainer\":{\"generalDecisionCode\":1001}}");
            return new PlexApi.Reply(200,"{\"MediaContainer\":{\"Metadata\":[{\"ratingKey\":42,\"type\":\"episode\"}]}}");
        });
        var episode=new PlexApi.Entry("42","Episode","episode","Show",1,1,1200000,93847,0,"");
        assertTrue(api.playback(session(),episode,true,"resume").getQuery().contains("offset=93.847"));
        assertTrue(api.playback(session(),episode,true,"seek",301074).getQuery().contains("offset=301.074"));
        assertEquals(2,seen.stream().filter(u->u.getPath().endsWith("decision") && u.getQuery().contains("offset=")).count());
    }
    @Test public void negativeHlsProbeUsesPlaybackProfileAndNeverCallsA200PlaylistDenied() throws Exception {
        List<java.net.URI> seen=new ArrayList<>();
        PlexApi api=new PlexApi("client",(m,u,h,b)->{
            seen.add(u); assertEquals("CHILD_SERVER",h.get("X-Plex-Token"));
            assertEquals(PlexApi.PROFILE_EXTRA,h.get("X-Plex-Client-Profile-Extra"));
            assertEquals("bytes=0-4095",h.get("Range"));
            return new PlexApi.Reply(u.getPath().endsWith("start.m3u8")?200:404,"");
        });
        String result=api.negativeProbe(session(),"42","/library/parts/123/456/file.mkv");
        assertTrue(result.contains("metadata: HTTP 404 (denied)"));
        assertTrue(result.contains("HLS playlist: HTTP 200 (inspect; not a denial proof)"));
        var decision=seen.stream().filter(u->u.getPath().endsWith("decision")).findFirst().get();
        var playlist=seen.stream().filter(u->u.getPath().endsWith("start.m3u8")).findFirst().get();
        assertEquals(decision.getQuery(),playlist.getQuery());
        assertTrue(playlist.getQuery().contains("videoResolution=1280x720"));
        assertTrue(playlist.getQuery().contains("hasMDE=1"));
        assertEquals(5,seen.size());
    }
    @Test public void hlsDecisionAndByteRequestsShareExplicitMatchingProfile() throws Exception {
        List<Map<String,String>> decisions=new ArrayList<>();
        PlexApi api=new PlexApi("client",(m,u,h,b)->{
            if(u.getPath().endsWith("decision")) { decisions.add(h); return new PlexApi.Reply(200,"{\"MediaContainer\":{\"generalDecisionCode\":1001}}"); }
            return new PlexApi.Reply(200,"{\"MediaContainer\":{\"Metadata\":[{\"ratingKey\":42,\"type\":\"episode\",\"duration\":120000}]}}");
        });
        var uri=api.playback(session(),new PlexApi.Entry("42","Episode","episode","Show",1,1,120000,0,0,""),true,"session");
        Map<String,String> bytes=PlexApi.mediaHeaders("client","CHILD_SERVER","session");
        assertEquals("Generic",decisions.get(0).get("X-Plex-Client-Profile-Name"));
        for(String key:List.of("X-Plex-Client-Profile-Name","X-Plex-Client-Profile-Extra","X-Plex-Platform","X-Plex-Client-Identifier","X-Plex-Session-Identifier","X-Plex-Token"))
            assertEquals(key,decisions.get(0).get(key),bytes.get(key));
        assertTrue(uri.getQuery().contains("protocol=hls")); assertFalse(uri.getQuery().contains("X-Plex-Client-Profile-Extra"));
    }
    @Test public void pendingPinWithNullTokenDoesNotBecomeAnAuthenticatedAccount() throws Exception {
        PlexApi api=new PlexApi("client",(m,u,h,b)->new PlexApi.Reply(200,"{\"authToken\":null}"));
        assertEquals("",api.checkPin(new PlexApi.Pin("42","ABCD",false),""));
    }
    @Test public void acceptsJsonIdentityOnlyWhenSelectedServerMatches() throws Exception {
        PlexApi api=new PlexApi("client",(m,u,h,b)->new PlexApi.Reply(200,"{\"MediaContainer\":{\"machineIdentifier\":\"server\"}}"));
        var result=api.connect(new PlexApi.Child("2","Child","ACCOUNT"),new PlexApi.Server("server","Server","TOKEN",List.of()),"https://media.example",1);
        assertEquals("TOKEN",result.serverToken());
    }
    @Test public void childAccountAndServerTokensAreSeparateAndIdentityIsCheckedBeforeSendingServerToken() throws Exception {
        List<String> seen=new ArrayList<>();
        PlexApi api=new PlexApi("client",(method,uri,h,b)->{
            String path=uri.getPath(); seen.add(path+":"+h.getOrDefault("X-Plex-Token","NONE"));
            if(path.endsWith("/switch")) return new PlexApi.Reply(200,"<user id=\"2\" authenticationToken=\"CHILD_ACCOUNT\"/>");
            if(path.equals("/api/v2/user")) return new PlexApi.Reply(200,"{\"id\":2,\"restricted\":true}");
            if(path.equals("/api/v2/resources")) return new PlexApi.Reply(200,"[{\"provides\":\"server\",\"clientIdentifier\":\"server\",\"accessToken\":\"CHILD_SERVER\",\"connections\":[]}]");
            if(path.equals("/identity")) return new PlexApi.Reply(200,"<MediaContainer machineIdentifier=\"server\"/>");
            if(path.equals("/library/sections")) return new PlexApi.Reply(200,"{\"MediaContainer\":{\"Directory\":[]}}");
            throw new AssertionError(path);
        });
        var child=api.selectChild("OWNER",new PlexApi.HomeUser("2","Child",true,false,false),"");
        var server=api.servers(child).get(0);
        var session=api.connect(child,server,"https://media.example",7); api.tvLibraries(session);
        assertEquals(List.of("/api/home/users/2/switch:OWNER","/api/v2/user:CHILD_ACCOUNT","/api/v2/user:CHILD_ACCOUNT",
                "/api/v2/resources:CHILD_ACCOUNT","/identity:NONE","/library/sections:CHILD_SERVER"),seen);
    }
    @Test public void rejectsAdminSelectionWithoutMakingRequest() {
        PlexApi api=new PlexApi("client",(m,u,h,b)->{ throw new AssertionError("must not request"); });
        assertThrows(IOException.class,()->api.selectChild("OWNER",new PlexApi.HomeUser("1","Owner",false,true,false),""));
    }
    @Test public void deniedChildSessionNeverRetriesWithOwnerCredentials() {
        List<String> tokens=new ArrayList<>();
        PlexApi api=new PlexApi("client",(m,u,h,b)->{ tokens.add(h.get("X-Plex-Token")); return new PlexApi.Reply(401,""); });
        assertThrows(IOException.class,()->api.verifyChild(new PlexApi.Child("2","Child","CHILD")));
        assertEquals(List.of("CHILD"),tokens);
    }
    @Test public void rejectsOwnerIdentityMasqueradingAsChild() {
        PlexApi api=new PlexApi("client",(m,u,h,b)->new PlexApi.Reply(200,"{\"id\":1,\"restricted\":false}"));
        assertThrows(IOException.class,()->api.verifyChild(new PlexApi.Child("2","Child","TOKEN")));
    }
    @Test public void mismatchedServerNeverReceivesChildCredential() {
        List<Map<String,String>> headers=new ArrayList<>();
        PlexApi api=new PlexApi("client",(m,u,h,b)->{ headers.add(h); return new PlexApi.Reply(200,"<MediaContainer machineIdentifier=\"other\"/>"); });
        assertThrows(IOException.class,()->api.connect(new PlexApi.Child("2","Child","ACCOUNT"),new PlexApi.Server("server","Server","TOKEN",List.of()),"https://media.example",1));
        assertEquals(1,headers.size()); assertFalse(headers.get(0).containsKey("X-Plex-Token"));
    }
    @Test public void rejectsCrossOriginAndMalformedMediaPaths() throws Exception {
        for(String path:List.of("https://evil.example/library/parts/1/file","//evil.example/library/parts/1/file","/library/parts/../secret","/library/parts/%2e%2e/secret","/library/parts/a?X-Plex-Token=SECRET","/admin"))
            assertThrows(path,IOException.class,()->PlexApi.mediaUri(session(),path));
        assertEquals("https://media.example/library/parts/1/2/file.mp4",PlexApi.mediaUri(session(),"/library/parts/1/2/file.mp4").toString());
    }
    @Test public void refusesRedirectAndDoesNotFetchDestination() {
        List<String> requests=new ArrayList<>();
        PlexApi api=new PlexApi("client",(m,u,h,b)->{ requests.add(u.toString()); return new PlexApi.Reply(302,""); });
        assertThrows(IOException.class,()->api.metadata(session(),"42")); assertEquals(1,requests.size());
    }
    @Test public void refusesCrossOriginApiBeforeTransport() {
        PlexApi api=new PlexApi("client",(m,u,h,b)->{ throw new AssertionError(); });
        assertThrows(IOException.class,()->api.entries(session(),"https://evil.example/items"));
    }
    @Test public void metadataDenialAndIdentityMismatchFailClosed() {
        PlexApi denied=new PlexApi("client",(m,u,h,b)->new PlexApi.Reply(404,""));
        assertThrows(IOException.class,()->denied.metadata(session(),"42"));
        PlexApi mismatched=new PlexApi("client",(m,u,h,b)->new PlexApi.Reply(200,"{\"MediaContainer\":{\"Metadata\":[{\"ratingKey\":43,\"type\":\"episode\"}]}}"));
        assertThrows(IOException.class,()->mismatched.metadata(session(),"42"));
    }
    @Test public void xmlRejectsExternalEntitiesAndParsesPlexHomeFlags() throws Exception {
        assertThrows(IOException.class,()->PlexApi.xml("<!DOCTYPE x [<!ENTITY a SYSTEM 'file:///etc/passwd'>]><x>&a;</x>"));
        PlexApi api=new PlexApi("client",(m,u,h,b)->new PlexApi.Reply(200,"<MediaContainer><User id=\"2\" title=\"Child\" restricted=\"1\" admin=\"0\" protected=\"1\"/></MediaContainer>"));
        var user=api.users("OWNER").get(0); assertTrue(user.restricted()); assertFalse(user.admin()); assertTrue(user.protectedPin());
    }
    @Test public void timelineUsesChildServerTokenAndCorrectEpisodeAndPosition() throws Exception {
        PlexApi api=new PlexApi("client",(m,u,h,b)->{
            assertEquals("POST",m); assertEquals("CHILD_SERVER",h.get("X-Plex-Token"));
            assertEquals("playback",h.get("X-Plex-Session-Identifier"));
            assertTrue(u.getQuery().contains("ratingKey=42")); assertTrue(u.getQuery().contains("time=12345"));
            return new PlexApi.Reply(200,"");
        });
        api.timeline(session(),new PlexApi.Entry("42","Episode","episode","Show",1,1,120000,0,0,""),"paused",12345,"playback");
    }
    @Test public void seekConversionHasFreshTranscoderIdAndSamePlaybackContextAsItsBytesAndTimeline() throws Exception {
        List<String> seen=new ArrayList<>();
        PlexApi api=new PlexApi("client",(m,u,h,b)->{
            if(u.getPath().contains("decision") || u.getPath().equals("/:/timeline")) {
                seen.add(h.get("X-Plex-Session-Identifier"));
                if(u.getPath().contains("decision")) {
                    assertTrue(u.getQuery().contains("session=new-conversion"));
                    return new PlexApi.Reply(200,"{\"MediaContainer\":{\"generalDecisionCode\":1001}}");
                }
                assertFalse(u.getQuery().contains("X-Plex-Session-Identifier"));
                return new PlexApi.Reply(200,"");
            }
            return new PlexApi.Reply(200,"{\"MediaContainer\":{\"Metadata\":[{\"ratingKey\":42,\"type\":\"episode\"}]}}");
        });
        var episode=new PlexApi.Entry("42","Episode","episode","Show",1,1,1200000,0,0,"");
        api.playback(session(),episode,true,"new-conversion",134000,"foreground-playback");
        api.timeline(session(),episode,"playing",134000,"foreground-playback");
        assertEquals(List.of("foreground-playback","foreground-playback"),seen);
        assertEquals("foreground-playback",PlexApi.mediaHeaders("client","CHILD_SERVER","foreground-playback").get("X-Plex-Session-Identifier"));
    }
    @Test public void disconnectedSessionGuardStopsServerRequestsBeforeCredentialsReachTransport() {
        PlexApi api=new PlexApi("client",(m,u,h,b)->{ throw new AssertionError("No server request is allowed"); });
        api.guard(s->{throw new IOException("Disconnected");});
        assertThrows(IOException.class,()->api.metadata(session(),"42"));
        assertThrows(IOException.class,()->api.timeline(session(),new PlexApi.Entry("42","Episode","episode","Show",1,1,100000,0,0,""),"playing",5000,"run"));
    }
    @Test public void streamChoicesUseOnlySelectedChildAndExactMediaPart() throws Exception {
        List<String> seen=new ArrayList<>();
        PlexApi api=new PlexApi("client",(method,uri,headers,body)->{
            assertEquals("CHILD_SERVER",headers.get("X-Plex-Token")); seen.add(method+" "+uri.getPath()+"?"+uri.getQuery());
            return new PlexApi.Reply(200,"");
        });
        var episode=new PlexApi.Entry("42","Episode","episode","Show",1,1,100000,0,0,"/library/parts/123/456/file.mkv");
        api.selectStream(session(),episode,"55",null); api.selectStream(session(),episode,null,"0");
        assertTrue(seen.get(0).startsWith("PUT /library/parts/123?")); assertTrue(seen.get(0).contains("audioStreamID=55"));
        assertTrue(seen.get(1).contains("subtitleStreamID=0"));
        assertThrows(IOException.class,()->api.selectStream(session(),episode,"../owner",null));
        assertEquals(2,seen.size());
    }

    @Test public void aNewChildTokenMayRetryIdentityWithoutRepeatingSwitchOrUsingOwner() throws Exception {
        List<String> tokens=new ArrayList<>(); int[] checks={0};
        PlexApi api=new PlexApi("client",(m,u,h,b)->{
            tokens.add(h.get("X-Plex-Token"));
            if(u.getPath().endsWith("/switch")) return new PlexApi.Reply(200,"{\"id\":2,\"authToken\":\"CHILD\"}");
            return ++checks[0]==1?new PlexApi.Reply(401,""):new PlexApi.Reply(200,"{\"id\":2,\"restricted\":true}");
        });
        var child=api.selectChild("OWNER",new PlexApi.HomeUser("2","Child",true,false,false),"");
        assertEquals("CHILD",child.accountToken()); assertEquals(List.of("OWNER","CHILD","CHILD"),tokens);
    }
    @Test public void deniedSetupErrorsContainOnlyStageAndStatus() {
        PlexApi api=new PlexApi("client",(m,u,h,b)->new PlexApi.Reply(401,"Never expose PRIVATE_RESPONSE_OR_TOKEN"));
        Exception error=assertThrows(PlexApi.RequestFailure.class,()->api.verifyChild(new PlexApi.Child("2","Child","TOKEN")));
        assertTrue(error.getMessage().contains("child identity check")); assertTrue(error.getMessage().contains("401"));
        assertFalse(error.getMessage().contains("PRIVATE_RESPONSE")); assertFalse(error.getMessage().contains("TOKEN"));
    }

}
