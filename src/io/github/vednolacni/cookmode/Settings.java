// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;

/** The validated settings KS passes to start and configure, read once into plain fields. */
final class Settings {
    /** The recipe app, with its name as the views say it. */
    enum App {
        TANDOOR("Tandoor"), NORISH("Norish");

        final String title;

        App(String title) {
            this.title = title;
        }
    }
    enum Voice { OFF, TABLET, HOME_ASSISTANT }
    enum MarkCooked { ASK, ALWAYS, NEVER }
    enum Tone { ALARM, BEEPS, SILENT }

    private static final String KS_ACCENTS = "àáâãäåèéêëìíîïòóôõöùúûüñçýÿ";
    private static final String KS_PLAIN = "aaaaaaeeeeiiiiooooouuuuncyy";

    final App app;
    final String serverUrl;
    final String apiToken;
    final int planDays;
    /** The step text's smallest size in sp, for its longest steps. */
    final int textSize;
    /** The largest, for its shortest steps. */
    final int textMost;
    final String language;
    final Voice voice;
    /** The kiosk's ESPHome node when voice is Home Assistant, such as ks_kitchen, or empty to work it out from the device's name. */
    final String kioskNode;
    final boolean autoRead;
    final MarkCooked markCooked;
    final boolean keepAwake;
    final int awakeMinutes;
    final Tone tone;
    final boolean mediaStream;
    final int ringMinutes;
    final boolean closeOnScreensaver;
    final boolean splitSteps;
    final boolean swipeSteps;
    final boolean dockOnTop;
    /** One of the OverlaySpec corner anchors. */
    final String dockCorner;

    Settings(Map<String, Object> values) {
        app = "Norish".equals(text(values.get("recipeApp"))) ? App.NORISH : App.TANDOOR;
        serverUrl = app == App.NORISH ? NorishSource.normalize(text(values.get("serverUrl"))) : TandoorSource.normalize(text(values.get("serverUrl")));
        // A line break pasted along with it would fail in the request's header, with the token in the error's words.
        String token = text(values.get("apiToken")).replaceAll("\\p{Cntrl}", "");
        apiToken = token.regionMatches(true, 0, "Bearer ", 0, 7) ? token.substring(7).trim() : token;
        planDays = clamp(number(values.get("planDays"), 7), 1, 14);
        switch (text(values.get("textSize"))) {
            case "Normal":
                textSize = 24;
                textMost = 34;
                break;
            case "Extra large":
                textSize = 34;
                textMost = 56;
                break;
            default:
                textSize = 28;
                textMost = 44;
        }
        language = text(values.get("language"));
        switch (text(values.get("readAloud"))) {
            case "Tablet voice": voice = Voice.TABLET; break;
            case "Home Assistant": voice = Voice.HOME_ASSISTANT; break;
            default: voice = Voice.OFF;
        }
        kioskNode = node(text(values.get("kioskNode")));
        autoRead = Boolean.TRUE.equals(values.get("autoRead"));
        // Norish keeps no cook log, so nothing asks to record one.
        String mark = app == App.NORISH ? "Never" : text(values.get("markCooked"));
        switch (mark) {
            case "Always": markCooked = MarkCooked.ALWAYS; break;
            case "Never": markCooked = MarkCooked.NEVER; break;
            default: markCooked = MarkCooked.ASK;
        }
        keepAwake = !Boolean.FALSE.equals(values.get("keepAwake"));
        awakeMinutes = clamp(number(values.get("awakeMinutes"), 90), 15, 240);
        switch (text(values.get("alarmTone"))) {
            case "Beeps": tone = Tone.BEEPS; break;
            case "Silent": tone = Tone.SILENT; break;
            default: tone = Tone.ALARM;
        }
        mediaStream = "Media".equals(values.get("alarmStream"));
        ringMinutes = clamp(number(values.get("ringMinutes"), 3), 1, 10);
        closeOnScreensaver = !Boolean.FALSE.equals(values.get("closeOnScreensaver"));
        splitSteps = !Boolean.FALSE.equals(values.get("splitSteps"));
        swipeSteps = !Boolean.FALSE.equals(values.get("swipeSteps"));
        dockOnTop = !Boolean.FALSE.equals(values.get("dockOnTop"));
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
        if (serverUrl.isEmpty()) return "Set the " + app.title + " address under Recipe server.";
        if (!serverUrl.startsWith("http://") && !serverUrl.startsWith("https://")) return "The " + app.title + " address must start with http:// or https://.";
        if (apiToken.isEmpty() && app == App.NORISH) return "Set the API key under Recipe server. Create it in Norish under Settings > User > API keys.";
        if (apiToken.isEmpty()) return "Set the API token under Recipe server. Create it in Tandoor under Settings > API with the scope \"read write\".";
        return null;
    }

    /** Whether the same app, server and token, so what was read still holds. */
    boolean sameServer(Settings other) {
        return other != null && app == other.app && serverUrl.equals(other.serverUrl) && apiToken.equals(other.apiToken);
    }

    /** Whether another app or address, where the recipe in hand means nothing. */
    boolean otherServer(Settings other) {
        return other == null || app != other.app || !serverUrl.equals(other.serverUrl);
    }

    /** A node as it may be typed: ks-kitchen, ks_kitchen or the whole action esphome.ks_kitchen_announce. */
    static String node(String typed) {
        String node = typed.toLowerCase(Locale.ROOT).replace('-', '_');
        if (node.startsWith("esphome.")) node = node.substring("esphome.".length());
        return node.endsWith("_announce") ? node.substring(0, node.length() - "_announce".length()) : node;
    }

    /** A device's name the way ESPHome names its node: Dedo&babi becomes dedo_babi. */
    static String slug(String name) {
        String plain = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        return plain.replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    /** What KS itself makes of a device's name for a new node. It knows these accents only, so Otroška soba becomes otro_ka_soba. */
    static String ksSlug(String name) {
        StringBuilder plain = new StringBuilder();
        for (char letter : name.toLowerCase(Locale.ROOT).toCharArray()) {
            int accented = KS_ACCENTS.indexOf(letter);
            if (letter == 'ß') plain.append("ss");
            else plain.append(accented >= 0 ? KS_PLAIN.charAt(accented) : letter);
        }
        return plain.toString().replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
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
