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
    /** Enough of the recipe's name to show while the recipe itself is not back yet. */
    private static final int NAME_LENGTH = 40;
    /** A timer's recipe when it is the one being cooked and its id is long. */
    private static final String SAME_RECIPE = "=";
    /** Tandoor's ids, which are numbers, stay in each timer as before. Longer ones, as Norish's 36 characters, go once each. */
    private static final int SHORT_ID = 12;

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
    final String name;
    final double servings;
    final int page;
    final long started;
    final List<SavedTimer> timers;

    Snapshot(String device, long savedAt, String recipe, String name, double servings, int page, long started, List<SavedTimer> timers) {
        this.device = device;
        this.savedAt = savedAt;
        this.recipe = recipe;
        this.name = name;
        this.servings = servings;
        this.page = page;
        this.started = started;
        this.timers = Collections.unmodifiableList(new ArrayList<>(timers));
    }

    /** As text of at most MAX_LENGTH characters. The timers are soonest first, so the last are the first to go. */
    String encode() {
        for (int length : LABEL_LENGTHS) {
            String text = write(length, timers.size(), true);
            if (text.length() <= MAX_LENGTH) return text;
        }
        // A timer's recipe only keeps its step's timer from starting twice, which costs less than the timer.
        int shortest = LABEL_LENGTHS[LABEL_LENGTHS.length - 1];
        for (int count = timers.size(); count >= 0; count--) {
            String text = write(shortest, count, false);
            if (text.length() <= MAX_LENGTH) return text;
        }
        return "";
    }

    private static boolean longId(String id) {
        return id.length() > SHORT_ID || !id.matches("[0-9]*");
    }

    private String write(int labelLength, int timerCount, boolean otherRecipes) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("v", VERSION);
        values.put("d", device);
        values.put("t", savedAt);
        if (!recipe.isEmpty()) {
            values.put("r", recipe);
            values.put("n", name.length() > NAME_LENGTH ? name.substring(0, NAME_LENGTH) : name);
            values.put("s", servings);
            values.put("p", page);
            values.put("b", started);
        }
        List<Object> saved = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (SavedTimer timer : timers.subList(0, timerCount)) {
            String label = timer.label.length() > labelLength ? timer.label.substring(0, labelLength) : timer.label;
            Object named = timer.recipe;
            if (longId(timer.recipe)) {
                if (timer.recipe.equals(recipe)) {
                    named = SAME_RECIPE;
                } else if (otherRecipes) {
                    if (!ids.contains(timer.recipe)) ids.add(timer.recipe);
                    named = ids.indexOf(timer.recipe);
                } else {
                    named = "";
                }
            }
            saved.add(Arrays.<Object>asList(label, timer.total, timer.paused ? 1 : 0, timer.value, named, timer.step));
        }
        if (!ids.isEmpty()) values.put("i", ids);
        values.put("m", saved);
        return Json.write(values);
    }

    /** The snapshot in this text, or null when there is none it can read. */
    static Snapshot decode(String text) {
        if (text == null || text.isEmpty()) return null;
        try {
            Map<String, Object> values = Json.object(Json.parse(text));
            if (Json.integer(values.get("v"), 0) != VERSION) return null;
            String recipe = Json.text(values.get("r"));
            List<Object> ids = Json.list(values.get("i"));
            List<SavedTimer> timers = new ArrayList<>();
            for (Object item : Json.list(values.get("m"))) {
                List<Object> timer = Json.list(item);
                if (timer.size() < 6) continue;
                String named;
                if (timer.get(4) instanceof Number) {
                    long index = Json.integer(timer.get(4), -1);
                    named = index >= 0 && index < ids.size() ? Json.text(ids.get((int) index)) : "";
                } else {
                    named = Json.text(timer.get(4));
                    if (named.equals(SAME_RECIPE)) named = recipe;
                }
                timers.add(new SavedTimer(Json.text(timer.get(0)), (int) Json.integer(timer.get(1), 0), Json.integer(timer.get(2), 0) == 1,
                    Json.integer(timer.get(3), 0), named, (int) Json.integer(timer.get(5), -1)));
            }
            return new Snapshot(Json.text(values.get("d")), Json.integer(values.get("t"), 0), recipe, Json.text(values.get("n")), Json.number(values.get("s"), 0),
                (int) Json.integer(values.get("p"), 0), Json.integer(values.get("b"), 0), timers);
        } catch (IllegalArgumentException unreadable) {
            return null;
        }
    }
}
