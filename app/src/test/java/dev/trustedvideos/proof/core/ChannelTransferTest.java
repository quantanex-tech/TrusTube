package dev.trustedvideos.proof.core;

import java.io.ByteArrayInputStream;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class ChannelTransferTest {
    private static final String A = "UCaaaaaaaaaaaaaaaaaaaaaa", B = "UCbbbbbbbbbbbbbbbbbbbbbb";
    @Test public void subscriptionsRoundTripUnicodeAndBlockedStatusWithoutOtherSettings() throws Exception {
        String text = ChannelTransfer.encode(List.of(new ChannelTransfer.Channel(A, "Unsere Kanäle 🎥", true)));
        var result = ChannelTransfer.read(new ByteArrayInputStream(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(A, result.get(0).id); assertEquals("Unsere Kanäle 🎥", result.get(0).name); assertTrue(result.get(0).blocked);
        assertFalse(text.contains("pin")); assertFalse(text.contains("timer"));
    }
    @Test public void duplicateRowsAndExistingBlocksNeverRestoreContent() {
        String encoded = ChannelTransfer.encode(List.of(new ChannelTransfer.Channel(A, "A", false),
                new ChannelTransfer.Channel(A, "A", true), new ChannelTransfer.Channel(B, "B", false)));
        var rows = ChannelTransfer.decode(encoded); assertEquals(2, rows.size());
        PermissionPolicy destination = new PermissionPolicy(); destination.approve(B); destination.blockChannel(B);
        destination.blockVideo("aaaaaaaaaaa");
        assertEquals(1, ChannelTransfer.merge(destination, rows));
        assertFalse(destination.allowsChannel(A)); assertFalse(destination.allowsChannel(B));
        assertTrue(destination.blockedVideos().contains("aaaaaaaaaaa"));
        assertEquals(0, ChannelTransfer.merge(destination, rows));
    }
    @Test public void rejectsUnsupportedVersionInvalidIdsWrongTypesAndExcessiveNesting() {
        String valid = ChannelTransfer.encode(List.of(new ChannelTransfer.Channel(A, "A", false)));
        for (String invalid : List.of(valid.replace("\"version\":1", "\"version\":2"),
                valid.replace(A, "https://evil.example/channel"), valid.replace("false", "\"false\""),
                "{\"a\":" + "[".repeat(20) + "0" + "]".repeat(20) + "}", valid + " trailing", "{}")) {
            assertThrows(IllegalArgumentException.class, () -> ChannelTransfer.decode(invalid));
        }
    }
    @Test public void rejectsOversizeAndMalformedUtf8() {
        assertThrows(java.io.IOException.class, () -> ChannelTransfer.read(new ByteArrayInputStream(new byte[ChannelTransfer.MAX_BYTES + 1])));
        assertThrows(java.io.IOException.class, () -> ChannelTransfer.read(new ByteArrayInputStream(new byte[]{(byte)0xc3, 0x28})));
    }
}
