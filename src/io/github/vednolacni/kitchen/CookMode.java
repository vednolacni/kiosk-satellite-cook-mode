// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.kitchen;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * Opens recipe pages full screen and keeps the screensaver away while one is open.
 *
 * <p>KS holds its screensaver for a link page opened with hold and lets go when the page closes, however it closes.
 * A KS release that refuses hold from plugins gets a cooking session instead: the plugin reports activity just before
 * each screensaver deadline until End cooking or until the longest session runs out, since KS does not tell plugins
 * when its page closes. Used under the plugin's lock.
 */
final class CookMode {
    /** The pill that ends a cooking session. */
    interface Pill {
        void show();
        void hide();
    }

    /** The beat while no screensaver deadline is known. */
    static final long BEAT_MS = 30_000L;
    /** How long before the screensaver deadline a beat reports activity. */
    static final long EARLY_MS = 5_000L;
    private static final Map<String, Object> NONE = Collections.emptyMap();

    private final Object lock;
    private final Clock clock;
    private final Ui ui;
    private final Pill pill;
    private final Runnable beat = this::beat;
    private PluginHost host;
    /** Whether KS takes hold from plugins: null until a page tells. */
    private Boolean hold;
    private boolean session;
    private long deadline;

    CookMode(Object lock, Clock clock, Ui ui, Pill pill) {
        this.lock = lock;
        this.clock = clock;
        this.ui = ui;
        this.pill = pill;
    }

    void start(PluginHost host) {
        this.host = host;
        hold = null;
        session = false;
    }

    /** KS removes the pill with the session's overlays, so only the beat stops here. */
    void stop() {
        ui.cancel(beat);
        session = false;
        host = null;
    }

    boolean cooking() {
        return session;
    }

    /** Open a page over the dashboard, in cook mode when keep is set. */
    void open(String url, boolean keep, int minutes) {
        if (!keep) {
            show(url, null);
            return;
        }
        if (Boolean.FALSE.equals(hold)) {
            show(url, () -> begin(minutes));
            return;
        }
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("url", url);
        arguments.put("hold", true);
        PluginHost current = host;
        host.executeCommand("showLinkPage", arguments, (ok, data, error) -> {
            synchronized (lock) {
                if (host != current) return;
                if (ok) {
                    hold = true;
                    return;
                }
                // A release that refuses hold from plugins still opens the page without it, and only that proves it.
                show(url, () -> {
                    hold = false;
                    begin(minutes);
                });
            }
        });
    }

    /** End cooking: close the page, which also lets go of a hold, and stop a running session. */
    void end(boolean closePage) {
        if (session) {
            session = false;
            ui.cancel(beat);
            pill.hide();
        }
        if (closePage && host != null) host.executeCommand("hideOverlayPage", NONE, (ok, data, error) -> {});
    }

    /** KS armed or moved the screensaver deadline. A running session beats just before it. */
    void countdown(Object due) {
        if (!session) return;
        long at = epoch(due);
        later(at < 0 ? BEAT_MS : Math.max(1_000L, at - clock.millis() - EARLY_MS));
    }

    private void show(String url, Runnable then) {
        PluginHost current = host;
        host.executeCommand("showLinkPage", Collections.<String, Object>singletonMap("url", url), (ok, data, error) -> {
            synchronized (lock) {
                if (host != current) return;
                if (!ok) host.status("Could not open " + url + ": " + error, true);
                else if (then != null) then.run();
            }
        });
    }

    private void begin(int minutes) {
        deadline = clock.millis() + minutes * 60_000L;
        if (!session) {
            session = true;
            pill.show();
        }
        postpone();
        later(BEAT_MS);
    }

    /** Runs on the main thread. */
    private void beat() {
        synchronized (lock) {
            if (host == null || !session) return;
            try {
                if (clock.millis() >= deadline) {
                    end(true);
                } else {
                    postpone();
                    later(BEAT_MS);
                }
            } catch (RuntimeException sessionEnded) {
                // KS revokes the host just before stop, and an exception here would end the app.
            }
        }
    }

    private void postpone() {
        host.executeCommand("postponeScreensaver", NONE, (ok, data, error) -> {});
    }

    private void later(long delayMs) {
        ui.cancel(beat);
        ui.post(beat, delayMs);
    }

    /** An ISO 8601 time in epoch milliseconds, or -1. */
    private static long epoch(Object value) {
        if (!(value instanceof String)) return -1;
        try {
            return OffsetDateTime.parse((String) value).toInstant().toEpochMilli();
        } catch (DateTimeParseException unreadable) {
            return -1;
        }
    }
}
