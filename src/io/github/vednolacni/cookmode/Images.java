// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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
 * view needs, which matters on a 2 GB tablet, and kept in a small memory cache. Their bytes are kept by address as well,
 * so a picture is fetched once for every size it is shown at and for every time the panel opens, which matters to Norish,
 * which counts each picture against its key. For the same reason a picture that does not exist, or that Android cannot
 * read, is left be for a while. What a new page asks for goes first, so a recipe just opened does not wait behind a whole
 * list, and a page's own pictures come from its top down. The same picture is only fetched once at a time. A view gets its
 * picture only if it still shows the same address, so fast scrolling never puts the wrong picture in a row.
 */
final class Images {
    /** Recipe pictures are a few hundred KB. Anything larger is not a picture we want to decode. */
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    /**
     * What one picture may take once decoded, at four bytes a pixel since a picture with alpha ignores the smaller format.
     * Android refuses to draw a bitmap over 100 MB with an exception in the draw pass, which would end the app.
     */
    private static final long MAX_DECODED_BYTES = 16 * 1024 * 1024;
    /** How long a picture that does not exist, or that Android cannot read, is not asked for again. */
    static final long MISSING_MS = 15 * 60_000L;

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
    /** The pictures as they came, by address alone. About a twenty-fourth of the app's memory, in KB. */
    private final LruCache<String, byte[]> bytes = new LruCache<String, byte[]>((int) Math.min(Integer.MAX_VALUE, Runtime.getRuntime().maxMemory() / 1024 / 24)) {
        @Override protected int sizeOf(String key, byte[] picture) {
            return Math.max(1, picture.length / 1024);
        }
    };
    /** Addresses with no picture to show, and until when on the steady clock to leave them be. Main thread only. */
    private final Map<String, Long> missing = new HashMap<>();
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
        /** The server said there is no such picture, or sent one Android cannot read. Set before the answer is posted. */
        boolean lasting;

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
        Long until = missing.get(url);
        if (until != null) {
            if (SystemClock.elapsedRealtime() < until) return;
            missing.remove(url);
        }
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
            Bitmap bitmap = fetch(job);
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
        if (bitmap == null && job.lasting) {
            // Asking again soon would bring the same answer, and Norish would count it.
            missing.put(job.url, SystemClock.elapsedRealtime() + MISSING_MS);
            jobs.remove(key);
            return;
        }
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
        bytes.evictAll();
        missing.clear();
        jobs.clear();
    }

    /** The picture at the job's size, from the bytes kept or else from the server. */
    private Bitmap fetch(Job job) {
        try {
            byte[] picture = bytes.get(job.url);
            if (picture == null) {
                Http.Response response = http.request("GET", job.url, job.headers, null, MAX_BYTES);
                if (!response.ok() || response.body.length == 0) {
                    // The server's own word that there is no such picture lasts. A server that is down or busy does not.
                    job.lasting = response.status >= 400 && response.status < 500 && response.status != 408 && response.status != 429;
                    return null;
                }
                picture = response.body;
            }
            Bitmap bitmap = decode(picture, job.width);
            if (bitmap == null) {
                job.lasting = true;
                bytes.remove(job.url);
                return null;
            }
            bytes.put(job.url, picture);
            return bitmap;
        } catch (Exception | OutOfMemoryError unavailable) {
            // A missing picture is not worth failing over. The row shows without it.
            return null;
        }
    }

    /** The picture no wider than it needs to be, or null when these bytes are no picture Android can read. */
    private static Bitmap decode(byte[] picture, int width) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(picture, 0, picture.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample(bounds.outWidth, bounds.outHeight, Math.max(1, width));
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(picture, 0, picture.length, options);
    }

    /** The largest power of two that keeps the picture at least width wide, or more when it would still be too large. */
    static int sample(int pictureWidth, int pictureHeight, int width) {
        int sample = 1;
        while (pictureWidth / (sample * 2) >= width) sample *= 2;
        while ((long) (pictureWidth / sample) * (pictureHeight / sample) * 4 > MAX_DECODED_BYTES) sample *= 2;
        return sample;
    }
}
