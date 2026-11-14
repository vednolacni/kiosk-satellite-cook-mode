// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.LruCache;
import android.widget.ImageView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Recipe pictures for the views: fetched on two low-priority threads of the plugin's own, decoded no larger than their
 * view needs, which matters on a 2 GB tablet, and kept in a small memory cache. Their bytes are kept by address as well,
 * in memory and in a folder of Kiosk Satellite's cache, so a picture is fetched once for every size it is shown at, every
 * opening of the panel and every start of the plugin, which matters to Norish, which counts each picture against its key.
 * For the same reason a picture the server says does not exist, or that Android cannot read, is left be for a while. What
 * a new page asks for goes first, so a recipe just opened does not wait behind a whole list, and a page's own pictures
 * come from its top down. The same picture is only fetched once at a time, at however many sizes it is asked for. A view
 * gets its picture only if it still shows the same address, so fast scrolling never puts the wrong picture in a row.
 */
final class Images {
    /** Recipe pictures are a few hundred KB. Anything larger is not a picture we want to decode. */
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    /**
     * What one picture may take once decoded, at four bytes a pixel since a picture with alpha ignores the smaller format.
     * Android refuses to draw a bitmap over 100 MB with an exception in the draw pass, which would end the app.
     */
    private static final long MAX_DECODED_BYTES = 16 * 1024 * 1024;
    /** How long a picture the server says does not exist is not asked for again. */
    static final long MISSING_MS = 15 * 60_000L;
    /**
     * How long a picture Android cannot read, or one too large to take, is not asked for again: for as long as the plugin
     * runs, near enough. It will be the same every time, since an address never changes what it shows.
     */
    static final long UNREADABLE_MS = 7 * 24 * 60 * 60_000L;
    /** What the pictures kept on the tablet may take. Android may also empty the cache they are in. */
    static final long KEPT_MOST = 48L * 1024 * 1024;
    /**
     * A picture asked for this recently is not removed to make room, though the folder then grows past KEPT_MOST. The panel
     * asks for every picture of a long list each time it opens, and removing the ones it comes to last would fetch them all
     * again at every opening.
     */
    static final long WANTED_MS = 60 * 60_000L;
    /** How many addresses are remembered as asked for before the ones asked for longer ago than WANTED_MS go. */
    private static final int WANTED_MOST = 512;

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
    /** What the kept pictures may take: KEPT_MOST, or less in a test. */
    private final long keptMost;
    /** Where pictures are kept on the tablet, or null until the first view brings a place for them. */
    private volatile File keptIn;
    /** When each address was last asked for, on the steady clock. Written on the main thread, read on the picture threads. */
    private final Map<String, Long> wanted = new ConcurrentHashMap<>();
    /** What the kept pictures take, or -1 before they were counted. Guarded by itself. */
    private final long[] kept = {-1};
    /** Addresses with no picture to show, and until when on the steady clock to leave them be. Main thread only. */
    private final Map<String, Long> missing = new HashMap<>();
    /** Each picture being fetched. Main thread only. */
    private final Map<String, Job> jobs = new HashMap<>();
    private long batch;
    private long asked;
    private volatile boolean closed;

    Images() {
        this(KEPT_MOST);
    }

    Images(long keptMost) {
        this.keptMost = keptMost;
    }

    /** One picture on its way, and who waits for it. */
    private static final class Job {
        final String url;
        /** Asked at each try, so a wait the server has asked for since the picture was wanted is kept. */
        final Function<String, Map<String, String>> headers;
        final int width;
        final List<ImageView> views = new ArrayList<>();
        /** Views that want the same picture at other sizes. Its bytes, once here, make theirs with no request. */
        final List<Parked> parked = new ArrayList<>();
        Fetch fetch;
        long batch;
        /** Asked for while on its way, perhaps because the server has just come back. */
        boolean askedAgain;
        /**
         * How long to leave the address be after a try with no picture: MISSING_MS when the server says there is none,
         * UNREADABLE_MS for one Android cannot read or take, and 0 to ask again when wanted. Set before the answer is posted.
         */
        long leave;

        Job(String url, Function<String, Map<String, String>> headers, int width) {
            this.url = url;
            this.headers = headers;
            this.width = width;
        }
    }

    /** A view that waits for a picture on its way at another size. */
    private static final class Parked {
        final ImageView view;
        final Function<String, Map<String, String>> headers;
        final int width;

        Parked(ImageView view, Function<String, Map<String, String>> headers, int width) {
            this.view = view;
            this.headers = headers;
            this.width = width;
        }
    }

    /** Keep pictures in this folder from here on, so they outlast the panel and the plugin. Any thread. */
    void keepIn(File folder) {
        keptIn = folder;
    }

    /** What is asked for from here on goes before everything still waiting. Main thread only. */
    void newest() {
        batch++;
    }

    /**
     * Show the picture at url in view, at most width pixels wide. headers says what goes with a request for an address,
     * such as the API token for the recipe server's own media. It is asked on a picture thread just before each request.
     * A null url clears the view.
     */
    void load(ImageView view, String url, Function<String, Map<String, String>> headers, int width) {
        view.setTag(url);
        if (url == null || url.isEmpty()) {
            view.setImageDrawable(null);
            return;
        }
        long now = SystemClock.elapsedRealtime();
        wanted.put(url, now);
        if (wanted.size() > WANTED_MOST) wanted.values().removeIf(at -> now - at > WANTED_MS);
        String key = url + "@" + width;
        Bitmap cached = cache.get(key);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        view.setImageDrawable(null);
        Long until = missing.get(url);
        if (until != null) {
            if (now < until) return;
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
        // On its way at another size: its bytes do for this one too, and fetching them twice would cost Norish twice.
        String other = null;
        for (Map.Entry<String, Job> going : jobs.entrySet()) {
            if (going.getValue().url.equals(url)) {
                other = going.getKey();
                break;
            }
        }
        if (other != null) {
            Job sibling = jobs.get(other);
            for (int i = sibling.parked.size() - 1; i >= 0; i--) if (sibling.parked.get(i).view == view) sibling.parked.remove(i);
            sibling.parked.add(new Parked(view, headers, width));
            if (sibling.batch != batch && pool.remove(sibling.fetch)) start(other, sibling);
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
        if (bitmap == null && job.leave > 0) {
            long now = SystemClock.elapsedRealtime();
            // Answers that have run out go first, so this holds no more than what failed in the last while.
            missing.values().removeIf(until -> until <= now);
            // Asking again soon would bring the same answer, and Norish would count it.
            missing.put(job.url, now + job.leave);
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
        // The other sizes, from the bytes now in memory or on the tablet.
        for (Parked waiting : job.parked) if (job.url.equals(waiting.view.getTag())) load(waiting.view, job.url, waiting.headers, waiting.width);
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
        wanted.clear();
    }

    /** The picture at the job's size, from the bytes kept in memory or on the tablet, or else from the server. */
    private Bitmap fetch(Job job) {
        try {
            byte[] picture = bytes.get(job.url);
            if (picture == null) picture = kept(job.url);
            if (picture != null) {
                Bitmap bitmap = decode(picture, job.width);
                if (bitmap != null) {
                    bytes.put(job.url, picture);
                    return bitmap;
                }
                // Kept bytes that no longer read, as a power cut can leave them. The server still has the picture.
                bytes.remove(job.url);
                forget(job.url);
            }
            Map<String, String> headers = job.headers.apply(job.url);
            // Not now, such as while Norish has asked to wait, when the request would only bring its sign-in page.
            if (headers == null) return null;
            Http.Response response = http.request("GET", job.url, headers, null, MAX_BYTES);
            if (!response.ok() || response.body.length == 0) {
                job.leave = missing(response) ? MISSING_MS : 0;
                return null;
            }
            Bitmap bitmap = decode(response.body, job.width);
            if (bitmap == null) {
                // A picture Android cannot read stays so, such as AVIF before Android 12. A page instead, such as a sign-in
                // page, says nothing of the picture.
                job.leave = response.header("Content-Type").toLowerCase(Locale.ROOT).startsWith("image/") ? UNREADABLE_MS : 0;
                return null;
            }
            bytes.put(job.url, response.body);
            keep(job.url, response.body);
            return bitmap;
        } catch (Http.TooLarge large) {
            job.leave = UNREADABLE_MS;
            return null;
        } catch (Exception | OutOfMemoryError unavailable) {
            // A missing picture is not worth failing over. The row shows without it.
            return null;
        }
    }

    /**
     * The server's own word that there is no such picture: a 404 or 410 in JSON, as Norish says it. A proxy's page while the
     * server starts says nothing of the picture. Nor does Norish's 404 with no-store, its answer to a key over its limit
     * or one it does not know, which may well be served once the limit lifts.
     */
    private static boolean missing(Http.Response response) {
        return (response.status == 404 || response.status == 410) && response.text().trim().startsWith("{")
            && !response.header("Cache-Control").toLowerCase(Locale.ROOT).contains("no-store");
    }

    /** A picture kept on the tablet, or null. */
    private byte[] kept(String url) {
        File folder = keptIn;
        if (folder == null) return null;
        try {
            File file = new File(folder, name(url));
            if (!file.isFile()) return null;
            byte[] picture = Files.readAllBytes(file.toPath());
            if (picture.length == 0) {
                // A power cut soon after it was named can leave it empty. The server still has the picture.
                file.delete();
                return null;
            }
            // Used now, so it goes last when room is made.
            file.setLastModified(System.currentTimeMillis());
            return picture;
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /** Keep a picture on the tablet, making room by the ones used longest ago. */
    private void keep(String url, byte[] picture) {
        File folder = keptIn;
        // A signed link, as Tandoor gives for pictures on S3, is signed anew in every answer, so none would find it here.
        if (folder == null || url.indexOf('?') >= 0) return;
        try {
            if (!folder.isDirectory() && !folder.mkdirs()) return;
            File file = new File(folder, name(url));
            if (file.length() > 0) return;
            File part = File.createTempFile("part", ".tmp", folder);
            boolean named = false;
            try {
                try (FileOutputStream out = new FileOutputStream(part)) {
                    out.write(picture);
                }
                // Whole or not at all, since the other picture thread may read it at once.
                named = part.renameTo(file);
            } finally {
                if (!named) part.delete();
            }
            if (named) trim(folder, picture.length);
        } catch (IOException | RuntimeException unwritable) {
            // It shows all the same, and comes from the server next time.
        }
    }

    private void forget(String url) {
        File folder = keptIn;
        if (folder != null) new File(folder, name(url)).delete();
    }

    /** Keep the folder within its size, the pictures used longest ago going first and none the panel asks for now. */
    private void trim(File folder, long added) {
        synchronized (kept) {
            if (kept[0] >= 0 && kept[0] + added <= keptMost) {
                kept[0] += added;
                return;
            }
            File[] all = folder.listFiles();
            if (all == null) return;
            List<File> files = new ArrayList<>();
            long wall = System.currentTimeMillis();
            for (File file : all) {
                if (file.getName().endsWith(".pic")) files.add(file);
                // A part file this old was left by a write that never ended. A newer one is still being written.
                else if (wall - file.lastModified() > WANTED_MS) file.delete();
            }
            // Times read once, since the other thread may touch a file while they are sorted.
            final long[] used = new long[files.size()];
            Integer[] order = new Integer[files.size()];
            long total = 0;
            for (int i = 0; i < files.size(); i++) {
                used[i] = files.get(i).lastModified();
                order[i] = i;
                total += files.get(i).length();
            }
            // Counted for the first time since the plugin started, the folder may well be within its size.
            if (total <= keptMost) {
                kept[0] = total;
                return;
            }
            long now = SystemClock.elapsedRealtime();
            Set<String> asked = new HashSet<>();
            for (Map.Entry<String, Long> picture : wanted.entrySet()) if (now - picture.getValue() <= WANTED_MS) asked.add(name(picture.getKey()));
            Arrays.sort(order, (a, b) -> Long.compare(used[a], used[b]));
            for (Integer i : order) {
                if (total <= keptMost * 3 / 4) break;
                File file = files.get(i);
                if (asked.contains(file.getName())) continue;
                long length = file.length();
                if (file.delete()) total -= length;
            }
            kept[0] = total;
        }
    }

    /** A file's name for an address: a hash of it, so any address makes a safe one. */
    static String name(String url) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder name = new StringBuilder();
            for (int i = 0; i < 20; i++) name.append(Character.forDigit((hash[i] >> 4) & 15, 16)).append(Character.forDigit(hash[i] & 15, 16));
            return name.append(".pic").toString();
        } catch (NoSuchAlgorithmException impossible) {
            return Integer.toHexString(url.hashCode()) + ".pic";
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
