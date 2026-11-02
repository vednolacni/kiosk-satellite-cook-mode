// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.Map;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * Keeps the kiosk awake while someone cooks. Plugin views sit under the screensaver, so the plugin reports activity
 * just before each screensaver deadline, the way a touch would, for as long as it is held. KS announces each new
 * deadline with screensaver.countdown, and a beat every 30 seconds covers a deadline it has not heard of.
 *
 * <p>The beats keep the idle screensaver away, so one that starts during a hold was asked for, by a schedule, a
 * gesture or Now Playing. It is not fought: postponing would dismiss it, so the beats wait until it goes.
 *
 * <p>Used under the plugin's lock. The beats run on the main thread.
 */
final class Awake {
    /** The beat while no deadline is known. */
    static final long BEAT_MS = 30_000L;
    /** How long before the deadline a beat reports activity. */
    static final long EARLY_MS = 5_000L;
    private static final Map<String, Object> NONE = Collections.emptyMap();

    /** Where beats are scheduled: the main thread on a kiosk, a list in tests. */
    interface Scheduler {
        void post(Runnable task, long delayMs);
        void cancel(Runnable task);
    }

    private final Object lock;
    private final Clock clock;
    private final Scheduler scheduler;
    private final Runnable beat = this::beat;
    private PluginHost host;
    private boolean held;
    /** A screensaver is up. */
    private boolean covered;

    Awake(Object lock, Clock clock, Scheduler scheduler) {
        this.lock = lock;
        this.clock = clock;
        this.scheduler = scheduler;
    }

    void start(PluginHost host) {
        this.host = host;
        held = false;
        covered = false;
    }

    void stop() {
        scheduler.cancel(beat);
        held = false;
        host = null;
    }

    boolean held() {
        return held;
    }

    /** Hold or release. Holding dismisses a screensaver that is up and wakes the screen. */
    void hold(boolean hold) {
        if (hold == held || host == null) return;
        held = hold;
        if (hold) {
            host.executeCommand("stopScreensaver", NONE, (ok, data, error) -> {});
            covered = false;
            later(BEAT_MS);
        } else {
            scheduler.cancel(beat);
        }
    }

    /** The screensaver came or went, from the screensaver.state event. */
    void screensaver(boolean active) {
        covered = active;
        if (!held) return;
        if (active) scheduler.cancel(beat);
        else later(BEAT_MS);
    }

    /** KS armed or moved the screensaver deadline, from the screensaver.countdown event's due field. */
    void countdown(Object due) {
        if (!held || covered) return;
        long at = epoch(due);
        later(at < 0 ? BEAT_MS : Math.max(1_000L, at - clock.millis() - EARLY_MS));
    }

    private void beat() {
        synchronized (lock) {
            if (host == null || !held || covered) return;
            try {
                host.executeCommand("postponeScreensaver", NONE, (ok, data, error) -> {});
                later(BEAT_MS);
            } catch (RuntimeException sessionEnded) {
                // KS revokes the host just before stop, and an exception on the main thread would end the app.
            }
        }
    }

    private void later(long delayMs) {
        scheduler.cancel(beat);
        scheduler.post(beat, delayMs);
    }

    /** An ISO 8601 time in epoch milliseconds, or -1. */
    static long epoch(Object value) {
        if (!(value instanceof String)) return -1;
        try {
            return OffsetDateTime.parse((String) value).toInstant().toEpochMilli();
        } catch (DateTimeParseException unreadable) {
            return -1;
        }
    }
}
