// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What must outlive a restart of the app or the plugin: the recipe being cooked and the timers, in one short setting.
 * It holds no token. It names its device, because fleet sync can copy settings to another kiosk.
 */
final class Snapshot {
    /** The most a string setting holds. */
    static final int MAX_LENGTH = 512;
    private static final int VERSION = 1;
    /** Labels shorten before any timer is given up. */
    private static final int[] LABEL_LENGTHS = {60, 30, 16};

    static final class SavedTimer {
        final String label;
        final int total;
        final boolean paused;
        /** When it rings in epoch milliseconds, or while paused, the milliseconds left. */
        final long value;
        final String recipe;
        final int step;

        SavedTimer(String label, int total, boolean paused, long value, String recipe, int step) {
            this.label = label;
            this.total = total;
            this.paused = paused;
            this.value = value;
            this.recipe = recipe;
            this.step = step;
        }
    }

    final String device;
    final long savedAt;
    /** The recipe being cooked, or an empty string. */
    final String recipe;
    final double servings;
    final int page;
    final long started;
    final List<SavedTimer> timers;

    Snapshot(String device, long savedAt, String recipe, double servings, int page, long started, List<SavedTimer> timers) {
        this.device = device;
        this.savedAt = savedAt;
        this.recipe = recipe;
        this.servings = servings;
        this.page = page;
        this.started = started;
        this.timers = Collections.unmodifiableList(new ArrayList<>(timers));
    }

    /** As text of at most MAX_LENGTH characters. The timers are soonest first, so the last are the first to go. */
    String encode() {
        for (int length : LABEL_LENGTHS) {
            String text = write(length, timers.size());
            if (text.length() <= MAX_LENGTH) return text;
        }
        for (int count = timers.size() - 1; count >= 0; count--) {
            String text = write(LABEL_LENGTHS[LABEL_LENGTHS.length - 1], count);
            if (text.length() <= MAX_LENGTH) return text;
        }
        return "";
    }

    private String write(int labelLength, int timerCount) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("v", VERSION);
        values.put("d", device);
        values.put("t", savedAt);
        if (!recipe.isEmpty()) {
            values.put("r", recipe);
            values.put("s", servings);
            values.put("p", page);
            values.put("b", started);
        }
        List<Object> saved = new ArrayList<>();
        for (SavedTimer timer : timers.subList(0, timerCount)) {
            String label = timer.label.length() > labelLength ? timer.label.substring(0, labelLength) : timer.label;
            saved.add(Arrays.<Object>asList(label, timer.total, timer.paused ? 1 : 0, timer.value, timer.recipe, timer.step));
        }
        values.put("m", saved);
        return Json.write(values);
    }

    /** The snapshot in this text, or null when there is none it can read. */
    static Snapshot decode(String text) {
        if (text == null || text.isEmpty()) return null;
        try {
            Map<String, Object> values = Json.object(Json.parse(text));
            if (Json.integer(values.get("v"), 0) != VERSION) return null;
            List<SavedTimer> timers = new ArrayList<>();
            for (Object item : Json.list(values.get("m"))) {
                List<Object> timer = Json.list(item);
                if (timer.size() < 6) continue;
                timers.add(new SavedTimer(Json.text(timer.get(0)), (int) Json.integer(timer.get(1), 0), Json.integer(timer.get(2), 0) == 1,
                    Json.integer(timer.get(3), 0), Json.text(timer.get(4)), (int) Json.integer(timer.get(5), -1)));
            }
            return new Snapshot(Json.text(values.get("d")), Json.integer(values.get("t"), 0), Json.text(values.get("r")), Json.number(values.get("s"), 0),
                (int) Json.integer(values.get("p"), 0), Json.integer(values.get("b"), 0), timers);
        } catch (IllegalArgumentException unreadable) {
            return null;
        }
    }
}
