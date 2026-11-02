// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import java.util.Map;

/** The validated settings KS passes to start and configure, read once into plain fields. */
final class Settings {
    enum Voice { OFF, TABLET, HOME_ASSISTANT }
    enum MarkCooked { ASK, ALWAYS, NEVER }

    final String serverUrl;
    final String apiToken;
    final int planDays;
    /** The step text's size in sp. */
    final int textSize;
    final String language;
    final Voice voice;
    /** The assist_satellite entity that speaks when voice is Home Assistant. */
    final String satellite;
    final boolean autoRead;
    final MarkCooked markCooked;
    final boolean keepAwake;
    final int awakeMinutes;
    final boolean timerSound;
    final boolean closeOnScreensaver;
    final boolean splitSteps;
    /** One of the OverlaySpec corner anchors. */
    final String dockCorner;

    Settings(Map<String, Object> values) {
        serverUrl = TandoorSource.normalize(text(values.get("serverUrl")));
        apiToken = text(values.get("apiToken"));
        planDays = clamp(number(values.get("planDays"), 7), 1, 14);
        switch (text(values.get("textSize"))) {
            case "Normal": textSize = 24; break;
            case "Extra large": textSize = 38; break;
            default: textSize = 30;
        }
        language = text(values.get("language"));
        switch (text(values.get("readAloud"))) {
            case "Tablet voice": voice = Voice.TABLET; break;
            case "Home Assistant": voice = Voice.HOME_ASSISTANT; break;
            default: voice = Voice.OFF;
        }
        satellite = text(values.get("satellite"));
        autoRead = Boolean.TRUE.equals(values.get("autoRead"));
        switch (text(values.get("markCooked"))) {
            case "Always": markCooked = MarkCooked.ALWAYS; break;
            case "Never": markCooked = MarkCooked.NEVER; break;
            default: markCooked = MarkCooked.ASK;
        }
        keepAwake = !Boolean.FALSE.equals(values.get("keepAwake"));
        awakeMinutes = clamp(number(values.get("awakeMinutes"), 90), 15, 240);
        timerSound = !Boolean.FALSE.equals(values.get("timerSound"));
        closeOnScreensaver = !Boolean.FALSE.equals(values.get("closeOnScreensaver"));
        splitSteps = !Boolean.FALSE.equals(values.get("splitSteps"));
        switch (text(values.get("dockCorner"))) {
            case "Top left": dockCorner = "top-left"; break;
            case "Bottom left": dockCorner = "bottom-left"; break;
            case "Bottom right": dockCorner = "bottom-right"; break;
            default: dockCorner = "top-right";
        }
    }

    /** Whether the server can be asked anything yet. */
    boolean configured() {
        return !serverUrl.isEmpty() && !apiToken.isEmpty();
    }

    /** What keeps the plugin from working, for its status, or null when nothing does. */
    String problem() {
        if (serverUrl.isEmpty()) return "Set the Tandoor address under Recipe server.";
        if (!serverUrl.startsWith("http://") && !serverUrl.startsWith("https://")) return "The Tandoor address must start with http:// or https://.";
        if (apiToken.isEmpty()) return "Set the API token under Recipe server. Create it in Tandoor under Settings > API with the scope \"read write\".";
        if (voice == Voice.HOME_ASSISTANT && !satellite.startsWith("assist_satellite.")) return "Pick the kiosk's assist_satellite entity under Read aloud.";
        return null;
    }

    /** Whether the same server and token, so what was read still holds. */
    boolean sameServer(Settings other) {
        return other != null && serverUrl.equals(other.serverUrl) && apiToken.equals(other.apiToken);
    }

    private static String text(Object value) {
        return value instanceof String ? ((String) value).trim() : "";
    }

    private static int number(Object value, int fallback) {
        return value instanceof Number ? (int) Math.round(((Number) value).doubleValue()) : fallback;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
