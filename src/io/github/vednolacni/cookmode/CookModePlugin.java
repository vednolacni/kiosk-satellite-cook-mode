// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import android.os.SystemClock;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * The entry class KS loads: it hands every callback to the controller. A new instance serves each session. KS disables a
 * plugin whose callback throws, which is too much to pay for one command that went wrong in the middle of cooking, so a
 * failure after the start goes to the plugin's status instead.
 */
public final class CookModePlugin implements KioskPlugin {
    /** Two, so a slow search does not keep a recipe from opening. */
    private static final int NETWORK_THREADS = 2;

    private PluginHost host;
    private ExecutorService net;
    private Cook cook;

    @Override public void start(PluginHost host, Map<String, Object> settings) {
        this.host = host;
        net = Executors.newFixedThreadPool(NETWORK_THREADS, task -> {
            Thread thread = new Thread(task, "cook-mode-net");
            thread.setDaemon(true);
            return thread;
        });
        cook = new Cook(new AndroidUi(), Clock.systemDefaultZone(), net, values -> RecipeSource.of(values, SystemClock::elapsedRealtime));
        cook.start(host, settings);
    }

    @Override public void configure(Map<String, Object> settings) {
        guarded(() -> cook.configure(settings));
    }

    @Override public void execute(String command, Map<String, Object> arguments) {
        guarded(() -> cook.execute(command));
    }

    @Override public void onEvent(String event, Map<String, Object> payload) {
        guarded(() -> cook.onEvent(event, payload));
    }

    @Override public void stop() {
        if (cook != null) cook.stop();
        if (net != null) net.shutdownNow();
    }

    private void guarded(Runnable callback) {
        try {
            callback.run();
        } catch (RuntimeException | LinkageError failed) {
            try {
                host.status("Cook Mode: " + failed, true);
            } catch (RuntimeException sessionEnded) {
                // Nothing is left to tell.
            }
        }
    }
}
