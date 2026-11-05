// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Recipe pictures for the views: fetched on two low-priority threads of the plugin's own, decoded no larger than their
 * view needs, which matters on a 2 GB tablet, and kept in a small memory cache. What a new page asks for goes first, so a
 * recipe just opened does not wait behind a whole list, and a page's own pictures come from its top down. The same picture
 * is only fetched once at a time. A view gets its picture only if it still shows the same address, so fast scrolling
 * never puts the wrong picture in a row.
 */
final class Images {
    /** Recipe pictures are a few hundred KB. Anything larger is not a picture we want to decode. */
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    /**
     * What one picture may take once decoded, at four bytes a pixel since a picture with alpha ignores the smaller format.
     * Android refuses to draw a bitmap over 100 MB with an exception in the draw pass, which would end the app.
     */
    private static final long MAX_DECODED_BYTES = 16 * 1024 * 1024;

    private final Http http = new Http();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor pool = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new PriorityBlockingQueue<Runnable>(), task -> {
        Thread thread = new Thread(task, "cook-mode-images");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 2);
        return thread;
    });
    /** About a sixteenth of the app's memory, in KB. */
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>((int) Math.min(Integer.MAX_VALUE, Runtime.getRuntime().maxMemory() / 1024 / 16)) {
        @Override protected int sizeOf(String key, Bitmap bitmap) {
            return Math.max(1, bitmap.getByteCount() / 1024);
        }
    };
    /** Each picture being fetched. Main thread only. */
    private final Map<String, Job> jobs = new HashMap<>();
    private long batch;
    private long asked;
    private volatile boolean closed;

    /** One picture on its way, and who waits for it. */
    private static final class Job {
        final String url;
        final Map<String, String> headers;
        final int width;
        final List<ImageView> views = new ArrayList<>();
        Fetch fetch;
        long batch;
        /** Asked for while on its way, perhaps because the server has just come back. */
        boolean askedAgain;

        Job(String url, Map<String, String> headers, int width) {
            this.url = url;
            this.headers = headers;
            this.width = width;
        }
    }

    /** What is asked for from here on goes before everything still waiting. Main thread only. */
    void newest() {
        batch++;
    }

    /**
     * Show the picture at url in view, at most width pixels wide. headers go with the request, such as the API token
     * for the recipe server's own media. A null url clears the view.
     */
    void load(ImageView view, String url, Map<String, String> headers, int width) {
        view.setTag(url);
        if (url == null || url.isEmpty()) {
            view.setImageDrawable(null);
            return;
        }
        String key = url + "@" + width;
        Bitmap cached = cache.get(key);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        view.setImageDrawable(null);
        Job job = jobs.get(key);
        if (job != null) {
            job.views.add(view);
            job.askedAgain = true;
            // Not started yet, so it goes with what is being asked for now.
            if (job.batch != batch && pool.remove(job.fetch)) start(key, job);
            return;
        }
        job = new Job(url, headers, width);
        job.views.add(view);
        jobs.put(key, job);
        start(key, job);
    }

    private void start(String key, Job job) {
        job.batch = batch;
        job.fetch = new Fetch(batch, asked++, () -> {
            Bitmap bitmap = fetch(job.url, job.headers, job.width);
            if (closed) return;
            if (bitmap != null) cache.put(key, bitmap);
            main.post(() -> Parts.guard(() -> finished(key, job, bitmap)));
        });
        try {
            pool.execute(job.fetch);
        } catch (RejectedExecutionException shutDown) {
            // The plugin is stopping.
            jobs.remove(key);
        }
    }

    private void finished(String key, Job job, Bitmap bitmap) {
        if (jobs.get(key) != job) return;
        if (bitmap == null && job.askedAgain) {
            // This try may be from before the server came back. Once more, and not for ever.
            job.askedAgain = false;
            start(key, job);
            return;
        }
        jobs.remove(key);
        if (bitmap == null) return;
        for (ImageView view : job.views) if (job.url.equals(view.getTag())) view.setImageBitmap(bitmap);
    }

    /** Stop fetching and drop the cache. */
    void close() {
        closed = true;
        pool.shutdownNow();
        http.cancelAll();
        cache.evictAll();
        jobs.clear();
    }

    private Bitmap fetch(String url, Map<String, String> headers, int width) {
        try {
            Http.Response response = http.request("GET", url, headers, null, MAX_BYTES);
            if (!response.ok() || response.body.length == 0) return null;
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(response.body, 0, response.body.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample(bounds.outWidth, bounds.outHeight, Math.max(1, width));
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(response.body, 0, response.body.length, options);
        } catch (Exception | OutOfMemoryError unavailable) {
            // A missing picture is not worth failing over. The row shows without it.
            return null;
        }
    }

    /** The largest power of two that keeps the picture at least width wide, or more when it would still be too large. */
    static int sample(int pictureWidth, int pictureHeight, int width) {
        int sample = 1;
        while (pictureWidth / (sample * 2) >= width) sample *= 2;
        while ((long) (pictureWidth / sample) * (pictureHeight / sample) * 4 > MAX_DECODED_BYTES) sample *= 2;
        return sample;
    }
}
