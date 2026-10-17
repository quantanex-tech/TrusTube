package dev.trustedvideos.proof.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class ChannelInputsTest {
    @Test public void normalizesOnlyYouTubeChannelInputs() {
        assertEquals("https://www.youtube.com/@GoogleDevelopers", ChannelInputs.normalize("@GoogleDevelopers"));
        assertThrows(IllegalArgumentException.class, () -> ChannelInputs.normalize("https://youtube.com.evil.test/@good"));
        assertThrows(IllegalArgumentException.class, () -> ChannelInputs.normalize("https://youtube.com@evil.test/@good"));
        assertThrows(IllegalArgumentException.class, () -> ChannelInputs.normalize("http://youtube.com/@good"));
        assertThrows(IllegalArgumentException.class, () -> ChannelInputs.normalize("https://youtube.com/watch?v=M7lc1UVf-VE"));
    }
    @Test public void identityRequiresCanonicalPathAndTrustedHost() {
        assertEquals("UC_x5XG1OV2P6uZZ5FSM9Ttw",
                ChannelInputs.canonicalChannel("https://www.youtube.com/channel/UC_x5XG1OV2P6uZZ5FSM9Ttw"));
        assertEquals("", ChannelInputs.canonicalChannel("https://youtube.com/@GoogleDevelopers"));
        assertEquals("UC_x5XG1OV2P6uZZ5FSM9Ttw",
                ChannelInputs.canonicalChannel("https://www.youtube.com/channel/UC_x5XG1OV2P6uZZ5FSM9Ttw/videos"));
        assertEquals("", ChannelInputs.canonicalChannel("https://www.youtube.com/channel/UC_x5XG1OV2P6uZZ5FSM9Ttw/../other"));
        assertEquals("", ChannelInputs.canonicalChannel("https://youtube.com.evil.test/channel/UC_x5XG1OV2P6uZZ5FSM9Ttw"));
        assertEquals("", ChannelInputs.videoId("https://evil.test/watch?v=M7lc1UVf-VE"));
    }
}
