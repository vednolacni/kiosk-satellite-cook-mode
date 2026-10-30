// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.kitchen;

import java.time.Clock;
import java.util.Map;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * Kitchen View: the week's meals from Home Assistant calendars in a full screen panel, recipes opened from any recipe
 * app and a cook mode that keeps the screensaver away while one is open. The work happens in {@link Kitchen}.
 */
public final class KitchenViewPlugin implements KioskPlugin {
    private final Kitchen kitchen = new Kitchen(new AndroidUi(), Clock.systemDefaultZone());

    @Override public void start(PluginHost host, Map<String, Object> settings) {
        kitchen.start(host, settings);
    }

    @Override public void configure(Map<String, Object> settings) {
        kitchen.configure(settings);
    }

    /** SDK 1 passes no arguments. */
    @Override public void execute(String command, Map<String, Object> arguments) {
        kitchen.execute(command);
    }

    @Override public void onEvent(String event, Map<String, Object> payload) {
        kitchen.onEvent(event, payload);
    }

    @Override public void stop() {
        kitchen.stop();
    }
}
