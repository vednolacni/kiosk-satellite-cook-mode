// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the durations a step's text mentions, such as "bake for 25 minutes", "kuhaj 10–15 min" or "1 Std. 30 Min.",
 * so each one can become a timer. Knows English, Slovenian and German units.
 */
final class Durations {
    /** One duration in the text. A range such as 10–15 min times its lower end, the first moment to check. */
    static final class Found {
        final int start;
        final int end;
        final int seconds;
        /** The words as written, such as "10–15 min". */
        final String text;

        Found(int start, int end, int seconds, String text) {
            this.start = start;
            this.end = end;
            this.seconds = seconds;
            this.text = text;
        }
    }

    private static final String NUMBER = "(\\d+(?:[.,]\\d+)?|½|¼|¾)";
    // Slovenian needs its cases too: 1 uro, 1 minuto, 1 sekundo.
    private static final String HOURS = "h|hr|hrs|hour|hours|ura|uro|uri|ure|ur|std|stunde|stunden";
    private static final String MINUTES = "m|min|mins|minute|minutes|minuta|minuto|minuti|minut|minuten";
    private static final String SECONDS = "s|sec|secs|second|seconds|sek|sekunda|sekundo|sekundi|sekunde|sekund|sekunden";
    /** A number or a range, a unit, then optionally a second part such as "1 h 30 min". */
    private static final Pattern DURATION = Pattern.compile(
        "(?<![\\p{L}\\d.,])" + NUMBER + "(?:\\s*(?:-|–|—|to|do|bis)\\s*" + NUMBER + ")?\\s*(" + HOURS + "|" + MINUTES + "|" + SECONDS + ")\\.?(?![\\p{L}])"
            + "(?:\\s*(?:and|in|und)?\\s*(\\d+)\\s*(" + MINUTES + "|" + SECONDS + ")\\.?(?![\\p{L}]))?",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** Timers longer than a day are a recipe's resting or proofing note, not a timer. */
    private static final int MAX_SECONDS = 24 * 60 * 60;

    private Durations() {}

    static List<Found> find(String text) {
        List<Found> found = new ArrayList<>();
        if (text == null || text.isEmpty()) return found;
        Matcher matcher = DURATION.matcher(text);
        while (matcher.find()) {
            double first = value(matcher.group(1));
            int seconds = (int) Math.round(first * unit(matcher.group(3)));
            if (matcher.group(4) != null) seconds += (int) Math.round(value(matcher.group(4)) * unit(matcher.group(5)));
            if (seconds <= 0 || seconds > MAX_SECONDS) continue;
            found.add(new Found(matcher.start(), matcher.end(), seconds, matcher.group().trim()));
        }
        return found;
    }

    /** A duration as a timer shows it: 45 s, 12:00, 1:05:00. */
    static String clock(int seconds) {
        int hours = seconds / 3600;
        int minutes = seconds / 60 % 60;
        int rest = seconds % 60;
        if (hours > 0) return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, rest);
        return String.format(Locale.ROOT, "%d:%02d", minutes, rest);
    }

    private static double value(String number) {
        switch (number) {
            case "½": return 0.5;
            case "¼": return 0.25;
            case "¾": return 0.75;
            default: return Double.parseDouble(number.replace(',', '.'));
        }
    }

    private static int unit(String unit) {
        String lower = unit.toLowerCase(Locale.ROOT);
        if (lower.matches(HOURS)) return 3600;
        if (lower.matches(MINUTES)) return 60;
        return 1;
    }
}
