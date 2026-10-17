package dev.trustedvideos.proof.source;

import java.util.List;
import org.junit.Test;
import org.schabi.newpipe.extractor.channel.ChannelInfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import static org.junit.Assert.*;

public class ChannelSearchTest {
    private static final String ID = "UC513PdAP2-jWkJunTh5kXRw";
    @Test public void discoveryFiltersVideosInvalidHostsAndDuplicateChannels() {
        ChannelInfoItem first = new ChannelInfoItem(0, "https://www.youtube.com/channel/" + ID, "A channel");
        ChannelInfoItem duplicate = new ChannelInfoItem(0, first.getUrl(), "Duplicate");
        ChannelInfoItem bad = new ChannelInfoItem(0, "https://example.com/channel/" + ID, "Untrusted URL");
        StreamInfoItem video = new StreamInfoItem(0, "https://www.youtube.com/watch?v=abcdefghijk", "A video", StreamType.VIDEO_STREAM);
        var results = SourceRepository.channelResults(List.of(first, duplicate, bad, video));
        assertEquals(1, results.size());
        assertEquals(ID, results.get(0).id);
        assertEquals("A channel", results.get(0).name);
        assertEquals(first.getUrl(), results.get(0).url);
    }
    @Test public void addingCanonicalResultCannotResolveToAnotherCreator() {
        String url = "https://www.youtube.com/channel/" + ID;
        SourceRepository.verifyChannelIdentity(url, ID);
        assertThrows(SecurityException.class, () -> SourceRepository.verifyChannelIdentity(url, "UCaaaaaaaaaaaaaaaaaaaaaa"));
        assertThrows(SecurityException.class, () -> SourceRepository.verifyChannelIdentity(url, "unverified"));
        assertThrows(IllegalArgumentException.class, () -> new SourceRepository.ChannelResult("bad", "Invalid", "", ""));
    }
}
