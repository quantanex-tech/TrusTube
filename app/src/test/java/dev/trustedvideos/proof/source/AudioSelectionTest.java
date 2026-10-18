// SPDX-License-Identifier: GPL-3.0-or-later
package dev.trustedvideos.proof.source;

import java.util.List;
import org.junit.Test;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.services.youtube.ItagItem;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.AudioTrackType;
import static org.junit.Assert.*;

public class AudioSelectionTest {
    private static AudioStream audio(String id, AudioTrackType type, boolean opus, int bitrate) throws Exception {
        ItagItem itag = ItagItem.getItag(opus ? 251 : 140);
        itag.setCodec(opus ? "opus" : "mp4a.40.2");
        return new AudioStream.Builder().setId(id)
                .setContent("https://r1.googlevideo.com/videoplayback", true)
                .setMediaFormat(opus ? MediaFormat.WEBMA_OPUS : MediaFormat.M4A)
                .setAverageBitrate(bitrate).setAudioTrackType(type).setItagItem(itag).build();
    }
    @Test public void originalBeatsDubbedAndUntaggedEvenAtLowerBitrate() throws Exception {
        AudioStream original = audio("original", AudioTrackType.ORIGINAL, true, 64);
        AudioStream dub = audio("dubbed", AudioTrackType.DUBBED, false, 256);
        AudioStream untagged = audio("untagged", null, false, 256);
        assertSame(original, SourceRepository.selectAudio(List.of(dub, untagged, original)));
        assertSame(original, SourceRepository.selectAudio(List.of(original, untagged, dub)));
    }
    @Test public void untaggedFallbackStillPrefersCompatibleCodecThenBitrate() throws Exception {
        AudioStream aac = audio("aac", null, false, 128);
        AudioStream lower = audio("aac-low", null, false, 64);
        AudioStream opus = audio("opus", null, true, 256);
        assertSame(aac, SourceRepository.selectAudio(List.of(opus, lower, aac)));
    }
    @Test public void knownAlternateTracksCannotReplaceMissingOriginal() throws Exception {
        assertNull(SourceRepository.selectAudio(List.of(
                audio("dubbed", AudioTrackType.DUBBED, false, 128),
                audio("descriptive", AudioTrackType.DESCRIPTIVE, false, 128),
                audio("secondary", AudioTrackType.SECONDARY, false, 128))));
    }
    @Test public void explicitChoiceCanSelectDubButMissingChoiceNeverFallsBack() throws Exception {
        AudioStream original = audio("original", AudioTrackType.ORIGINAL, false, 64);
        AudioStream dub = audio("dub", AudioTrackType.DUBBED, false, 128);
        assertSame(dub, SourceRepository.selectAudio(List.of(original, dub), SourceRepository.audioKey(dub)));
        assertNull(SourceRepository.selectAudio(List.of(original, dub), "gone-track"));
        assertSame(original, SourceRepository.selectAudio(List.of(dub, original), null));
    }
    @Test public void codecsForOneLanguageBecomeOneChoiceAndOriginalIsLabelled() throws Exception {
        AudioStream original = audio("original", AudioTrackType.ORIGINAL, false, 64);
        AudioStream originalOpus = audio("original-opus", AudioTrackType.ORIGINAL, true, 128);
        AudioStream dub = audio("dub", AudioTrackType.DUBBED, false, 128);
        var choices = SourceRepository.audioOptions(List.of(original, originalOpus, dub));
        assertEquals(2, choices.size()); assertTrue(choices.get(0).label.contains("Original"));
    }

}
