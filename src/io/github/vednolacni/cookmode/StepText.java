// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A step's text cut into plain runs and timer runs. A duration in the text, such as "4 to 5 minutes", is a phrase a
 * cook reads anyway, so the cooking view draws it as a tappable chip in place instead of a separate button.
 */
final class StepText {
    /** One run of text. seconds is zero for plain text and the timer's length for a duration. */
    static final class Run {
        final String text;
        final int seconds;

        Run(String text, int seconds) {
            this.text = text;
            this.seconds = seconds;
        }

        boolean timer() {
            return seconds > 0;
        }
    }

    private StepText() {}

    /** The runs always add up to the text. */
    static List<Run> runs(String text) {
        if (text == null || text.isEmpty()) return Collections.emptyList();
        List<Run> runs = new ArrayList<>();
        int at = 0;
        for (Durations.Found found : Durations.find(text)) {
            if (found.start > at) runs.add(new Run(text.substring(at, found.start), 0));
            runs.add(new Run(text.substring(found.start, found.end), found.seconds));
            at = found.end;
        }
        if (at < text.length()) runs.add(new Run(text.substring(at), 0));
        return runs;
    }

    /** Every distinct timer of a step: its own time first, then the durations its text names. */
    static List<Run> timers(Recipe.Step step, String plain, String minutesLabel) {
        List<Run> timers = new ArrayList<>();
        if (step.seconds > 0) {
            String label = step.seconds % 60 == 0 ? step.seconds / 60 + " " + minutesLabel : Durations.clock(step.seconds);
            timers.add(new Run(label, step.seconds));
        }
        for (Run run : runs(plain)) {
            if (!run.timer()) continue;
            boolean seen = false;
            for (Run timer : timers) seen |= timer.seconds == run.seconds;
            if (!seen) timers.add(run);
        }
        return timers;
    }

    /** When the food should be ready if cooking started at start, or -1 when the recipe has no times. */
    static long readyAt(long start, Recipe recipe) {
        int minutes = recipe.totalMinutes();
        return minutes > 0 ? start + minutes * 60_000L : -1;
    }
}
