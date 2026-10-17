// SPDX-License-Identifier: GPL-3.0-or-later
package dev.trustedvideos.proof.source;

import java.util.LinkedHashSet;
import java.util.Set;

/** Opt-in metadata-only discovery probe; never approves channels or prints stream URLs. */
public final class ChannelSearchProbe {
    public static void main(String[] args) throws Exception {
        SourceRepository source = new SourceRepository();
        String term = args.length == 0 ? "checker tobi" : args[0];
        SourceRepository.SearchBatch page = null;
        Set<String> ids = new LinkedHashSet<>();
        for (int number = 1; number <= 2; number++) {
            page = source.searchChannels(term, page);
            System.out.println("page=" + number + " channels=" + page.channels.size() + " hasMore=" + page.hasMore);
            for (SourceRepository.ChannelResult channel : page.channels) {
                ids.add(channel.id); System.out.println(channel.name + " · " + channel.id);
            }
            if (!page.hasMore) break;
        }
        if (term.equalsIgnoreCase("checker tobi")) {
            SourceRepository.ChannelResult expected = source.searchChannels("@checkerwelt", null).channels.get(0);
            boolean found = ids.contains(expected.id);
            System.out.println("checkerweltFound=" + found + " canonicalId=" + expected.id);
            if (!found) throw new IllegalStateException("The expected channel was not found in the first two pages.");
        }
        System.out.println("Metadata only; no approvals changed and no playback attempted.");
    }
}
