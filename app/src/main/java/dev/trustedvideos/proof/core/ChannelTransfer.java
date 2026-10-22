// SPDX-License-Identifier: GPL-3.0-or-later
package dev.trustedvideos.proof.core;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/** Portable subscriptions only. Never contains credentials, timers, usage or media. */
public final class ChannelTransfer {
    public static final int MAX_BYTES = 1024 * 1024, MAX_CHANNELS = 2000;
    public static final String FORMAT = "trustube.channels";
    private ChannelTransfer() {}

    public static final class Channel {
        public final String id, name;
        public final boolean blocked;
        public Channel(String id, String name, boolean blocked) {
            if (!PermissionPolicy.validChannelId(id) || name == null || name.length() > 256
                    || hasControlCharacters(name))
                throw new IllegalArgumentException("The file contains an invalid channel ID or name.");
            this.id = id; this.name = name; this.blocked = blocked;
        }
    }

    private static boolean hasControlCharacters(String value) {
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i))) return true;
        return false;
    }

    public static List<Channel> read(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192]; int count;
        while ((count = input.read(buffer)) != -1) {
            if (bytes.size() + count > MAX_BYTES) throw new IOException("Channel files must be smaller than 1 MB.");
            bytes.write(buffer, 0, count);
        }
        return decode(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray())).toString());
    }

    public static List<Channel> decode(String text) {
        if (text == null || text.length() > MAX_BYTES || text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new IllegalArgumentException("Channel files must be smaller than 1 MB.");
        // Bound nesting before entering a recursive JSON parser, including untrusted files.
        int depth = 0; boolean quoted = false, escape = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (escape) escape = false;
                else if (c == '\\') escape = true;
                else if (c == '"') quoted = false;
            } else if (c == '"') quoted = true;
            else if (c == '{' || c == '[') {
                if (++depth > 8) throw new IllegalArgumentException("Channel file nesting is invalid.");
            } else if (c == '}' || c == ']') depth--;
        }
        try {
            JsonObject root = JsonParser.object().from(text);
            if (!FORMAT.equals(root.getString("format")) || !(root.get("version") instanceof Number)
                    || root.getNumber("version").doubleValue() != 1)
                throw new IllegalArgumentException("This is not a supported TrusTube channel file (version 1).");
            JsonArray rows = root.getArray("channels");
            if (rows == null || rows.size() > MAX_CHANNELS)
                throw new IllegalArgumentException("A channel file can contain up to 2,000 channels.");
            LinkedHashMap<String, Channel> unique = new LinkedHashMap<>();
            for (Object row : rows) {
                if (!(row instanceof JsonObject item) || !item.isString("id") || !item.isString("name")
                        || !item.isBoolean("blocked"))
                    throw new IllegalArgumentException("Each channel needs an ID, name and blocked flag.");
                Channel channel = new Channel(item.getString("id"), item.getString("name"), item.getBoolean("blocked"));
                Channel previous = unique.get(channel.id);
                unique.put(channel.id, previous == null ? channel
                        : new Channel(previous.id, previous.name, previous.blocked || channel.blocked));
            }
            return new ArrayList<>(unique.values());
        } catch (com.grack.nanojson.JsonParserException | ClassCastException error) {
            throw new IllegalArgumentException("This is not a valid TrusTube channel file.", error);
        }
    }

    public static String encode(List<Channel> channels) {
        if (channels.size() > MAX_CHANNELS) throw new IllegalArgumentException("Too many channels to export.");
        List<Channel> sorted = new ArrayList<>(channels); sorted.sort(Comparator.comparing(c -> c.id));
        JsonArray rows = new JsonArray();
        for (Channel channel : sorted) {
            JsonObject row = new JsonObject(); row.put("id", channel.id); row.put("name", channel.name);
            row.put("blocked", channel.blocked); rows.add(row);
        }
        JsonObject root = new JsonObject(); root.put("format", FORMAT); root.put("version", 1); root.put("channels", rows);
        String text = JsonWriter.string(root);
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new IllegalArgumentException("Too many channels to export.");
        return text;
    }

    /** Caller must obtain parent approval. Import can add blocks, but cannot remove them. */
    public static int merge(PermissionPolicy policy, List<Channel> channels) {
        synchronized (policy) {
        java.util.Set<String> merged = policy.channels();
        merged.removeIf(id -> !PermissionPolicy.validChannelId(id));
        for (Channel channel : channels) merged.add(channel.id);
        if (merged.size() > MAX_CHANNELS) throw new IllegalArgumentException("Import would exceed 2,000 subscribed channels.");
        int added = 0;
        for (Channel channel : channels) {
            if (!policy.channels().contains(channel.id)) added++;
            policy.approve(channel.id);
            if (channel.blocked) policy.blockChannel(channel.id);
        }
        return added;
        }
    }
}
