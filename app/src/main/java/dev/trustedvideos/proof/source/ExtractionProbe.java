// SPDX-License-Identifier: GPL-3.0-or-later
package dev.trustedvideos.proof.source;

import dev.trustedvideos.proof.core.ChannelInputs;
import dev.trustedvideos.proof.core.VideoEntry;

/** Host-side metadata probe. Stream URLs/tokens are deliberately not logged. */
public final class ExtractionProbe {
    public static void main(String[] args) throws Exception {
        SourceRepository source = new SourceRepository();
        SourceRepository.ChannelBatch batch = source.loadChannel(args[0]);
        System.out.println("channel=" + batch.id + " name=" + batch.name);
        System.out.println("firstPageVideos=" + batch.videos.size() + " hasMore=" + batch.hasMore);
        System.out.println("datedUploads=" + batch.videos.stream().filter(video -> video.uploadedAtMs > 0).count());
        if (batch.hasMore)
            System.out.println("secondPageVideos=" + source.loadMore(batch.id).videos.size());
        String id = args[1].isEmpty() && !batch.videos.isEmpty() ? batch.videos.get(0).id : ChannelInputs.videoId(args[1]);
        if (id.isEmpty()) throw new IllegalArgumentException("Provide a canonical YouTube watch URL.");
        VideoEntry sample = new VideoEntry(id, batch.id, batch.name, "probe", "", 0);
        for (boolean split : new boolean[]{false, true}) {
            ResolvedPlayback resolved = source.resolve(sample, split);
            System.out.println("verifiedVideo=" + resolved.videoId + " mode=" + resolved.kind
                    + " videoDash=" + (resolved.video.dashManifest != null)
                    + " audioDash=" + (resolved.audio != null && resolved.audio.dashManifest != null));
        }
        System.out.println("Metadata/source resolution succeeded; decoded playback is not tested here.");
    }
}
