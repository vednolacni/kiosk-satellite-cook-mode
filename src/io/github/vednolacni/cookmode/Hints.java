// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
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

    /** A temperature in the words, such as 180 °C, 350F or 200 stopinj. Shorter numbers are no oven's. */
    static final Pattern TEMPERATURE = Pattern.compile("(?<![\\d.,])(\\d{3})\\s*(°\\s*[CF]?|[CF](?![\\p{L}])|stopinj\\p{L}*|degrees|grad\\p{L}*)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern OVERNIGHT = Pattern.compile("čez noč|preko noči|overnight|über nacht|ueber nacht", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** Shorter waits are part of cooking. Longer ones are worth planning around. */
    private static final int LONG_SECONDS = 60 * 60;
    private static final int MOST_OF_EACH = 2;

    private Hints() {}

    static List<Hint> of(Recipe recipe, Strings strings) {
        List<Integer> temperatures = new ArrayList<>();
        List<Integer> rests = new ArrayList<>();
        boolean overnight = false;
        boolean fahrenheit = false;
        for (Recipe.Step step : recipe.steps) {
            String text = Instructions.render(step, 1, false);
            Matcher temperature = TEMPERATURE.matcher(text);
            while (temperature.find()) {
                int degrees = Integer.parseInt(temperature.group(1));
                if (degrees > 550) continue;
                if (!temperatures.contains(degrees)) temperatures.add(degrees);
                fahrenheit |= temperature.group(2).toUpperCase(Locale.ROOT).endsWith("F");
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
        for (int degrees : temperatures.subList(0, Math.min(MOST_OF_EACH, temperatures.size()))) hints.add(new Hint("mdi:thermometer", degrees + (fahrenheit ? " °F" : " °C")));
        return hints;
    }
}
