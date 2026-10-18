// SPDX-License-Identifier: GPL-3.0-or-later
package dev.trustedvideos.proof;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import java.io.File;
import java.io.FileOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Sampled bitmaps in memory and a bounded, disposable disk LRU; one fetch per URL. */
final class ImageCache {
    private static final long MAX_DISK = 64L * 1024 * 1024, MAX_AGE = TimeUnit.DAYS.toMillis(14);
    private final File directory;
    private final LruCache<String, Bitmap> memory = new LruCache<>((int) Math.min(32L * 1024 * 1024,
            Math.max(8L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8))) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };
    private final Map<String, List<Consumer<Bitmap>>> pending = new HashMap<>();
    private final ExecutorService worker = Executors.newFixedThreadPool(3);
    private final Handler main = new Handler(Looper.getMainLooper());
    interface Fetcher { Bitmap fetch(String url) throws Exception; }
    private final Fetcher fetcher;
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .followRedirects(false).callTimeout(15, TimeUnit.SECONDS).build();

    ImageCache(Context context) { this(new File(context.getCacheDir(), "artwork-v1"), ImageCache::download); }
    ImageCache(File directory, Fetcher fetcher) { this.directory = directory; this.fetcher = fetcher; }
    static boolean allowed(String url) {
        if (url == null || url.isEmpty()) return false;
        try {
            URI uri = URI.create(url); String host = uri.getHost();
            return "https".equals(uri.getScheme()) && uri.getUserInfo() == null && host != null
                    && (host.endsWith(".ytimg.com") || host.endsWith(".ggpht.com") || host.endsWith(".googleusercontent.com"));
        } catch (RuntimeException error) { return false; }
    }
    void load(String url, Consumer<Bitmap> callback) {
        if (!allowed(url)) return;
        Bitmap cached = memory.get(url);
        if (cached != null) { callback.accept(cached); return; }
        synchronized (pending) {
            List<Consumer<Bitmap>> callbacks = pending.get(url);
            if (callbacks != null) { callbacks.add(callback); return; }
            pending.put(url, new ArrayList<>(List.of(callback)));
        }
        worker.execute(() -> {
            Bitmap bitmap = null;
            try {
                File file = new File(directory, key(url) + ".png");
                if (file.isFile() && System.currentTimeMillis() - file.lastModified() < MAX_AGE)
                    bitmap = BitmapFactory.decodeFile(file.getPath());
                if (bitmap == null) {
                    bitmap = fetcher.fetch(url);
                    if (bitmap != null) write(file, bitmap);
                } else file.setLastModified(System.currentTimeMillis());
                if (bitmap != null) memory.put(url, bitmap);
            } catch (Exception ignored) { /* Keep placeholders on network/storage failure. */ }
            final Bitmap result = bitmap;
            final List<Consumer<Bitmap>> callbacks;
            synchronized (pending) { callbacks = pending.remove(url); }
            if (result != null) main.post(() -> callbacks.forEach(listener -> listener.accept(result)));
        });
    }
    private void write(File file, Bitmap bitmap) {
        try {
            if (!directory.isDirectory() && !directory.mkdirs()) return;
            File temporary = new File(file.getPath() + ".tmp");
            try (FileOutputStream output = new FileOutputStream(temporary)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); }
            if (!temporary.renameTo(file)) temporary.delete();
            File[] files = directory.listFiles((dir, name) -> name.endsWith(".png"));
            if (files == null) return;
            Arrays.sort(files, Comparator.comparingLong(File::lastModified));
            long size = 0; for (File item : files) size += item.length();
            for (File item : files) {
                if (size <= MAX_DISK && System.currentTimeMillis() - item.lastModified() < MAX_AGE) break;
                long bytes = item.length(); if (item.delete()) size -= bytes;
            }
        } catch (Exception ignored) {}
    }
    private static String key(String url) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));
        StringBuilder key = new StringBuilder(); for (byte value : hash) key.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return key.toString();
    }
    private static Bitmap download(String url) throws Exception {
        try (Response response = CLIENT.newCall(new Request.Builder().url(url).build()).execute()) {
            if (!response.isSuccessful() || response.body() == null || response.body().contentLength() > 8 * 1024 * 1024) return null;
            byte[] bytes;
            try (java.io.InputStream input = response.body().byteStream(); java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > 8 * 1024 * 1024) return null;
                    output.write(buffer, 0, count);
                }
                bytes = output.toByteArray();
            }
            BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            options.inSampleSize = 1;
            while (options.outWidth / options.inSampleSize > 960 || options.outHeight / options.inSampleSize > 540)
                options.inSampleSize *= 2;
            options.inJustDecodeBounds = false;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        }
    }
    void close() { worker.shutdownNow(); }
}
