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
        if (!batch.videos.isEmpty()) System.out.println("newestUploadMs=" + batch.videos.get(0).uploadedAtMs);
        if (batch.hasMore) {
            var second = source.loadMore(batch.id);
            System.out.println("secondPageVideos=" + second.videos.size() + " datedUploads="
                    + second.videos.stream().filter(video -> video.uploadedAtMs > 0).count());
        }
        if (args.length > 2 && Boolean.parseBoolean(args[2])) return;
        String id = args[1].isEmpty() && !batch.videos.isEmpty() ? batch.videos.get(0).id : ChannelInputs.videoId(args[1]);
        if (id.isEmpty()) throw new IllegalArgumentException("Provide a canonical YouTube watch URL.");
        VideoEntry sample = new VideoEntry(id, batch.id, batch.name, "probe", "", 0);
        for (boolean split : new boolean[]{false, true}) {
            ResolvedPlayback resolved = source.resolve(sample, split);
            System.out.println("verifiedVideo=" + resolved.videoId + " mode=" + resolved.kind
                    + " videoDash=" + (resolved.video.dashManifest != null)
                    + " audioDash=" + (resolved.audio != null && resolved.audio.dashManifest != null));
            System.out.println("audioChoices=" + resolved.audioOptions.size());
            for (var option : resolved.audioOptions) System.out.println("audio=" + option.label
                    + " selected=" + option.id.equals(resolved.selectedAudio));
            if (split && resolved.audioOptions.size() > 1) {
                var alternative = resolved.audioOptions.stream().filter(option -> !option.id.equals(resolved.selectedAudio)).findFirst().orElseThrow();
                ResolvedPlayback switched = source.resolve(sample, true, alternative.id);
                System.out.println("explicitAudio=" + alternative.label + " selected=" + switched.selectedAudio.equals(alternative.id)
                        + " sameVideo=" + switched.videoId.equals(sample.id));
            }
        }
        System.out.println("Metadata/source resolution succeeded; decoded playback is not tested here.");
    }
}
