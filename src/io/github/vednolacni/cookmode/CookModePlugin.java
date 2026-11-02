// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.PluginHost;

/** The entry class KS loads: it hands every callback to the controller. A new instance serves each session. */
public final class CookModePlugin implements KioskPlugin {
    /** Two, so a slow search does not keep a recipe from opening. */
    private static final int NETWORK_THREADS = 2;

    private ExecutorService net;
    private Cook cook;

    @Override public void start(PluginHost host, Map<String, Object> settings) {
        net = Executors.newFixedThreadPool(NETWORK_THREADS, task -> {
            Thread thread = new Thread(task, "cook-mode-net");
            thread.setDaemon(true);
            return thread;
        });
        cook = new Cook(new AndroidUi(), Clock.systemDefaultZone(), net, values -> new TandoorSource(values.serverUrl, values.apiToken, new Http()));
        cook.start(host, settings);
    }

    @Override public void configure(Map<String, Object> settings) {
        cook.configure(settings);
    }

    @Override public void execute(String command, Map<String, Object> arguments) {
        cook.execute(command);
    }

    @Override public void onEvent(String event, Map<String, Object> payload) {
        cook.onEvent(event, payload);
    }

    @Override public void stop() {
        if (cook != null) cook.stop();
        if (net != null) net.shutdownNow();
    }
}
