// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a cook wants to know before starting, found in the steps' words: the oven's temperature, a long rest and anything
 * that takes overnight. Finding out about a night's soaking at step 3 is too late.
 */
final class Hints {
    /** One thing to know, with the icon that goes with it. */
    static final class Hint {
        final String icon;
        final String text;

        Hint(String icon, String text) {
            this.icon = icon;
            this.text = text;
        }
    }

    private static final String SCALE = "(?:celsius|celzij\\p{L}*|fahrenheit|[CF](?!\\p{L}))";
    /** A temperature in the words, or a range of two: 180 °C, 350 degrees F, 180/160 °C, 200 stopinj, 180 st. A lone letter counts only in capitals. */
    static final Pattern TEMPERATURE = Pattern.compile(
        "(?<![\\p{L}\\p{N}.,])([0-9]{1,3})(?:[ \\t]*[-–/][ \\t]*([0-9]{2,3}))?[ \\t]*"
            + "(°[ \\t]*" + SCALE + "?|(?:stopinj\\p{L}*|degrees?|grad\\p{L}*)(?:[ \\t]+" + SCALE + ")?|(?-i:[CF])(?!\\p{L})|st\\.|stop\\.)",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern OVERNIGHT = Pattern.compile("čez noč|preko noči|overnight|über nacht|ueber nacht", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** Below this it is warm milk, not an oven, and above the other no oven goes. */
    private static final int OVEN_LEAST = 100;
    private static final int OVEN_MOST = 550;
    /** With no scale named, this is too hot for Celsius. */
    private static final int SURELY_FAHRENHEIT = 300;
    /** Shorter waits are part of cooking. Longer ones are worth planning around. */
    private static final int LONG_SECONDS = 60 * 60;
    private static final int MOST_OF_EACH = 2;

    private Hints() {}

    static List<Hint> of(Recipe recipe, Strings strings) {
        // Each oven temperature with its named scale, C or F, or an empty string.
        Map<Integer, String> temperatures = new LinkedHashMap<>();
        List<Integer> rests = new ArrayList<>();
        boolean overnight = false;
        for (Recipe.Step step : recipe.steps) {
            String text = Instructions.render(step, 1, false);
            Matcher temperature = TEMPERATURE.matcher(text);
            while (temperature.find()) {
                String scale = scale(temperature.group(3));
                oven(temperatures, temperature.group(1), scale);
                if (temperature.group(2) != null) oven(temperatures, temperature.group(2), scale);
            }
            overnight |= OVERNIGHT.matcher(text).find();
            if (step.seconds >= LONG_SECONDS && !rests.contains(step.seconds)) rests.add(step.seconds);
            for (Durations.Found found : Durations.find(text)) {
                if (found.seconds >= LONG_SECONDS && !rests.contains(found.seconds)) rests.add(found.seconds);
            }
        }
        Collections.sort(rests, Collections.reverseOrder());
        List<Hint> hints = new ArrayList<>();
        if (overnight) hints.add(new Hint("mdi:weather-night", strings.overnight));
        for (int rest : rests.subList(0, Math.min(MOST_OF_EACH, rests.size()))) hints.add(new Hint("mdi:timer-sand", strings.duration(rest / 60)));
        // A number with no scale takes the recipe's, when it uses only one.
        boolean celsius = temperatures.containsValue("C");
        boolean fahrenheit = temperatures.containsValue("F");
        int shown = 0;
        for (Map.Entry<Integer, String> oven : temperatures.entrySet()) {
            if (shown++ == MOST_OF_EACH) break;
            String scale = oven.getValue();
            if (scale.isEmpty()) scale = celsius != fahrenheit ? (fahrenheit ? "F" : "C") : oven.getKey() > SURELY_FAHRENHEIT ? "F" : "C";
            hints.add(new Hint("mdi:thermometer", oven.getKey() + " °" + scale));
        }
        return hints;
    }

    private static void oven(Map<Integer, String> temperatures, String number, String scale) {
        int degrees = Integer.parseInt(number);
        if (degrees < OVEN_LEAST || degrees > OVEN_MOST) return;
        String known = temperatures.get(degrees);
        if (known == null || known.isEmpty()) temperatures.put(degrees, scale);
    }

    private static String scale(String unit) {
        // None of the words for a degree holds either letter, so any here names the scale.
        String upper = unit.toUpperCase(Locale.ROOT);
        if (upper.contains("F")) return "F";
        return upper.contains("C") ? "C" : "";
    }
}
