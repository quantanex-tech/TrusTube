// SPDX-License-Identifier: GPL-3.0-or-later
package dev.trustedvideos.proof.source;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;

public final class HttpDownloader extends Downloader {
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS).build();

    @Override public Response execute(Request request) throws IOException {
        okhttp3.Request.Builder builder = new okhttp3.Request.Builder().url(request.url());
        request.headers().forEach((key, values) -> {
            builder.removeHeader(key);
            values.forEach(value -> builder.addHeader(key, value));
        });
        // Let OkHttp transparently decode gzip; callers receive text rather than compressed bytes.
        builder.removeHeader("Accept-Encoding");
        byte[] bytes = request.dataToSend();
        RequestBody body = bytes == null ? null
                : RequestBody.create(bytes, MediaType.parse("application/json"));
        builder.method(request.httpMethod(), body);
        try (okhttp3.Response response = client.newCall(builder.build()).execute()) {
            return new Response(response.code(), response.message(), response.headers().toMultimap(),
                    response.body() == null ? "" : response.body().string(),
                    response.request().url().toString());
        }
    }
}
