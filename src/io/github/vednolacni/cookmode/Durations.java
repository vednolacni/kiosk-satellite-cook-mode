// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the durations a step's text mentions, such as "bake for 25 minutes", "kuhaj 10–15 min", "pol ure", "1½ ure" or
 * "1 Std. 30 Min.", so each one can become a timer. Knows English, Slovenian and German. A wrong length burns food, so
 * what is unclear, such as a clock time, is left as plain text.
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

    // Plain ASCII digits. On Android \d also matches digits that parseDouble cannot read.
    private static final String WHOLE = "[0-9]+";
    private static final String GLYPHS = "½¼¾⅓⅔";
    private static final String NUMBER = "(?:" + WHOLE + "[ \\t]*)?(?:[" + GLYPHS + "]|" + WHOLE + "[/⁄]" + WHOLE + ")|" + WHOLE + "(?:[.,]" + WHOLE + ")?";
    private static final Pattern NUMBER_PARTS = Pattern.compile("(?:(" + WHOLE + ")[ \\t]*)?(?:([" + GLYPHS + "])|(" + WHOLE + ")[/⁄](" + WHOLE + "))");

    private static final String[] HOUR_WORDS = {"hr", "hrs", "hour", "hours", "ura", "uro", "uri", "ure", "ur", "urah", "urami", "std", "stunde", "stunden"};
    private static final String[] MINUTE_WORDS = {"min", "mins", "minute", "minutes", "minuta", "minuto", "minuti", "minut", "minutah", "minutami", "minuten"};
    private static final String[] SECOND_WORDS = {"sec", "secs", "second", "seconds", "sek", "sekunda", "sekundo", "sekundi", "sekunde", "sekund", "sekundah", "sekundami", "sekunden"};
    /** Short forms, which may carry a full stop inside a longer duration such as "1 Std. 30 Min.". */
    private static final Set<String> ABBREVIATIONS = new HashSet<>(Arrays.asList("h", "hr", "hrs", "std", "min", "mins", "m", "sec", "secs", "sek", "s"));
    // A single letter counts only in lower case: S at the start of a sentence or an egg's size M is no unit.
    private static final String HOURS = "(?-i:h)|" + String.join("|", HOUR_WORDS);
    private static final String MINUTES = "(?-i:m)|" + String.join("|", MINUTE_WORDS);
    private static final String SECONDS = "(?-i:s)|" + String.join("|", SECOND_WORDS);
    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    /** A number or a range and its unit. Not after a slash or a colon, which would make it the end of 1/2 or 2:15. */
    private static final Pattern DURATION = Pattern.compile(
        "(?<![\\p{L}\\p{N}.,/⁄:])(" + NUMBER + ")(?:[ \\t]*(?:-|–|—|to|do|bis)[ \\t]*(?:" + NUMBER + "))?[ \\t]*(" + HOURS + "|" + MINUTES + "|" + SECONDS + ")(?!\\p{L})", FLAGS);
    /** A shorter part on the same line, as in "1 uro in 30 minut". A full stop before it is checked apart. */
    private static final Pattern SECOND_PART = Pattern.compile("(\\.)?[ \\t]*(?:(?:and|in|und)[ \\t]+)?(" + WHOLE + ")[ \\t]*(" + MINUTES + "|" + SECONDS + ")(?!\\p{L})", FLAGS);
    private static final Pattern AND_A_HALF = Pattern.compile("[ \\t]+(?:in[ \\t]+pol|and[ \\t]+a[ \\t]+half)(?!\\p{L})", FLAGS);
    /** The minutes of 1h30, which name no unit of their own. */
    private static final Pattern GLUED_MINUTES = Pattern.compile("([0-9]{1,2})(?![\\p{L}0-9])");
    /** "ob 18 h" and "at 7 h" say when, not how long. */
    private static final Pattern CLOCK_BEFORE = Pattern.compile("(?:^|[^\\p{L}])(?:ob|at|um)[ \\t]+$", FLAGS);
    /** "18.30 h" is half past six in the evening. */
    private static final Pattern CLOCK_TIME = Pattern.compile("(?:[4-9]|[1-2][0-9])\\.[0-9]{2}");
    /** In Slovenian "s" is also the word for "with": "razdeli na 4 s pomočjo žlice", "stepi 2 s sladkorjem". */
    private static final Pattern WITH_SOMETHING = Pattern.compile("[ \\t]+\\p{L}*(?:om|em|jo|ami|mi)(?!\\p{L})", FLAGS);

    private static final Map<String, Integer> PHRASES = new LinkedHashMap<>();
    static {
        // Longest first, so "an hour and a half" is not read as "an hour".
        PHRASES.put("three quarters of an hour", 2700);
        PHRASES.put("a quarter of an hour", 900);
        PHRASES.put("quarter of an hour", 900);
        PHRASES.put("an hour and a half", 5400);
        PHRASES.put("half an hour", 1800);
        PHRASES.put("half hour", 1800);
        PHRASES.put("one minute", 60);
        PHRASES.put("one hour", 3600);
        PHRASES.put("a minute", 60);
        PHRASES.put("an hour", 3600);
        PHRASES.put("tri četrt ure", 2700);
        PHRASES.put("tričetrt ure", 2700);
        PHRASES.put("poldrugo uro", 5400);
        PHRASES.put("uro in pol", 5400);
        PHRASES.put("četrt ure", 900);
        PHRASES.put("eno uro in pol", 5400);
        PHRASES.put("eno minuto", 60);
        PHRASES.put("pol minute", 30);
        PHRASES.put("pol ure", 1800);
        PHRASES.put("eno uro", 3600);
        PHRASES.put("dreiviertelstunde", 2700);
        PHRASES.put("eineinhalb stunden", 5400);
        PHRASES.put("anderthalb stunden", 5400);
        PHRASES.put("viertelstunde", 900);
        PHRASES.put("halbe stunde", 1800);
        PHRASES.put("eine stunde", 3600);
        PHRASES.put("eine minute", 60);
    }
    private static final Pattern PHRASE = phrasePattern();

    /** Timers longer than a day are a recipe's resting or proofing note, not a timer. */
    private static final int MAX_SECONDS = 24 * 60 * 60;

    private Durations() {}

    private static Pattern phrasePattern() {
        StringBuilder names = new StringBuilder();
        for (String phrase : PHRASES.keySet()) {
            if (names.length() > 0) names.append('|');
            names.append(phrase.replace(" ", "[ \\t]+"));
        }
        return Pattern.compile("(?<!\\p{L})(?:" + names + ")(?!\\p{L})", FLAGS);
    }

    static List<Found> find(String text) {
        List<Found> found = new ArrayList<>();
        if (text == null || text.isEmpty()) return found;
        Matcher matcher = DURATION.matcher(text);
        while (matcher.find()) {
            Found one = numbered(text, matcher);
            if (one != null) found.add(one);
        }
        Matcher phrase = PHRASE.matcher(text);
        while (phrase.find()) {
            Integer seconds = PHRASES.get(phrase.group().toLowerCase(Locale.ROOT).replaceAll("[ \\t]+", " "));
            if (seconds != null) found.add(new Found(phrase.start(), phrase.end(), seconds, phrase.group()));
        }
        Collections.sort(found, (a, b) -> Integer.compare(a.start, b.start));
        List<Found> apart = new ArrayList<>();
        int end = 0;
        for (Found one : found) {
            if (one.start < end) continue;
            apart.add(one);
            end = one.end;
        }
        return apart;
    }

    private static Found numbered(String text, Matcher matcher) {
        String number = matcher.group(1);
        String unit = matcher.group(2);
        int first = unit(unit);
        int end = matcher.end();
        if (first == 3600 && CLOCK_TIME.matcher(number).matches()) return null;
        if (first == 3600 && CLOCK_BEFORE.matcher(text.substring(Math.max(0, matcher.start() - 8), matcher.start())).find()) return null;
        if (end < text.length() && Character.isDigit(text.charAt(end)) && !unit.equals("h")) return null;
        if (unit.equals("s") && lookingAt(WITH_SOMETHING, text, end) != null) return null;
        double value = value(number);
        if (Double.isNaN(value)) return null;
        double seconds = value * first;
        // Whatever else stands between the number and its unit makes it a range, which times its lower end alone.
        boolean range = !matcher.group().substring(number.length(), matcher.group().length() - unit.length()).trim().isEmpty();
        if (!range) {
            Matcher glued = unit.equals("h") ? lookingAt(GLUED_MINUTES, text, end) : null;
            Matcher half = first == 3600 ? lookingAt(AND_A_HALF, text, end) : null;
            Matcher second = lookingAt(SECOND_PART, text, end);
            if (glued != null) {
                seconds += Integer.parseInt(glued.group(1)) * 60;
                end = glued.end();
            } else if (half != null) {
                seconds += first / 2.0;
                end = half.end();
            } else if (second != null && unit(second.group(3)) < first && (second.group(1) == null || ABBREVIATIONS.contains(unit.toLowerCase(Locale.ROOT)))) {
                // Only a smaller unit belongs to the first, and past a full stop only after a short form:
                // "Peci 30 minut. 10 minut pred koncem dodaj sir." is two timers.
                seconds += Integer.parseInt(second.group(2)) * unit(second.group(3));
                end = second.end();
            }
        }
        int total = (int) Math.round(seconds);
        if (total <= 0 || total > MAX_SECONDS) return null;
        return new Found(matcher.start(), end, total, text.substring(matcher.start(), end).trim());
    }

    private static Matcher lookingAt(Pattern pattern, String text, int from) {
        Matcher matcher = pattern.matcher(text);
        matcher.region(from, text.length());
        // The lookbehind and start checks of the patterns must see what comes before the region.
        matcher.useTransparentBounds(true);
        matcher.useAnchoringBounds(false);
        return matcher.lookingAt() ? matcher : null;
    }

    /** A duration as a timer shows it: 45 s, 12:00, 1:05:00. */
    static String clock(int seconds) {
        int hours = seconds / 3600;
        int minutes = seconds / 60 % 60;
        int rest = seconds % 60;
        if (hours > 0) return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, rest);
        return String.format(Locale.ROOT, "%d:%02d", minutes, rest);
    }

    /** The number's value, or NaN when it has none, as with 1/0. */
    private static double value(String number) {
        Matcher parts = NUMBER_PARTS.matcher(number);
        try {
            if (!parts.matches()) return Double.parseDouble(number.replace(',', '.'));
            double value = parts.group(1) == null ? 0 : Double.parseDouble(parts.group(1));
            if (parts.group(2) != null) return value + glyph(parts.group(2));
            double below = Double.parseDouble(parts.group(4));
            return below == 0 ? Double.NaN : value + Double.parseDouble(parts.group(3)) / below;
        } catch (NumberFormatException unreadable) {
            return Double.NaN;
        }
    }

    private static double glyph(String glyph) {
        switch (glyph) {
            case "½": return 0.5;
            case "¼": return 0.25;
            case "¾": return 0.75;
            case "⅓": return 1.0 / 3;
            default: return 2.0 / 3;
        }
    }

    private static int unit(String unit) {
        String lower = unit.toLowerCase(Locale.ROOT);
        if (lower.equals("h") || Arrays.asList(HOUR_WORDS).contains(lower)) return 3600;
        if (lower.equals("m") || Arrays.asList(MINUTE_WORDS).contains(lower)) return 60;
        return 1;
    }
}
