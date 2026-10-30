// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.kitchen;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One meal plan event, the way Home Assistant's calendar gave it. */
final class Meal {
    /** "Dinner: Lasagna": a short meal type, a colon and a space, then the dish. "12:30 Lunch" is not one. */
    private static final Pattern TYPED = Pattern.compile("([^:]{1,30}?):\\s+(\\S.*)", Pattern.DOTALL);
    private static final Pattern LETTER = Pattern.compile("\\p{L}");

    final LocalDate date;
    /** Null for an all-day event, which is how most recipe apps plan their meals. */
    final LocalTime time;
    final String summary;
    final String description;
    final String location;
    /** Position of its calendar in the Meal plan calendars list. */
    final int calendar;
    /** Position in its calendar's answer, which keeps the app's own order for meals of the same day. */
    final int order;

    Meal(LocalDate date, LocalTime time, String summary, String description, String location, int calendar, int order) {
        this.date = date;
        this.time = time;
        this.summary = summary;
        this.description = description;
        this.location = location;
        this.calendar = calendar;
        this.order = order;
    }

    /** The meal type before the colon, or an empty string. */
    String type(boolean split) {
        return parts(split)[0];
    }

    /** The dish, or the whole title when it has no meal type. */
    String dish(boolean split) {
        return parts(split)[1];
    }

    private String[] parts(boolean split) {
        if (split) {
            Matcher matcher = TYPED.matcher(summary);
            if (matcher.matches()) {
                String type = matcher.group(1).trim();
                if (LETTER.matcher(type).find()) return new String[] {type, matcher.group(2).trim()};
            }
        }
        return new String[] {"", summary};
    }
}
