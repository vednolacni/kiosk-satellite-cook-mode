// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.kitchen;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** The panel's words and dates in English or Slovenian. */
final class Strings {
    static final Strings ENGLISH = new Strings(Locale.ENGLISH, "EEEE, MMMM d", "EEEE, MMM d", new String[] {
        "Meal plan", "Today", "Tomorrow", "Next days",
        "Nothing planned for today.", "Nothing planned for the next days.",
        "Open recipes", "Close", "Refresh", "Loading the meal plan…",
        "Add a meal plan calendar in the plugin settings.",
        "Set the recipe app address in the plugin settings.",
        "Could not read %s.", "End cooking",
    });
    static final Strings SLOVENIAN = new Strings(new Locale("sl"), "EEEE, d. MMMM", "EEEE, d. M.", new String[] {
        "Jedilnik", "Danes", "Jutri", "Naslednji dnevi",
        "Za danes ni načrtovanih obrokov.", "V naslednjih dneh ni načrtovanih obrokov.",
        "Odpri recepte", "Zapri", "Osveži", "Nalagam jedilnik …",
        "V nastavitvah vtičnika dodaj koledar z jedilnikom.",
        "V nastavitvah vtičnika vpiši naslov aplikacije z recepti.",
        "Branje ni uspelo: %s.", "Končaj kuhanje",
    });
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    final Locale locale;
    final String title;
    final String today;
    final String tomorrow;
    final String upcoming;
    final String nothingToday;
    final String nothingUpcoming;
    final String openApp;
    final String close;
    final String refresh;
    final String loading;
    final String noCalendars;
    final String noApp;
    /** Takes the calendars that failed, with their reasons. */
    final String failed;
    final String endCooking;
    private final DateTimeFormatter longDate;
    private final DateTimeFormatter shortDate;

    private Strings(Locale locale, String longDate, String shortDate, String[] words) {
        this.locale = locale;
        this.longDate = DateTimeFormatter.ofPattern(longDate, locale);
        this.shortDate = DateTimeFormatter.ofPattern(shortDate, locale);
        title = words[0];
        today = words[1];
        tomorrow = words[2];
        upcoming = words[3];
        nothingToday = words[4];
        nothingUpcoming = words[5];
        openApp = words[6];
        close = words[7];
        refresh = words[8];
        loading = words[9];
        noCalendars = words[10];
        noApp = words[11];
        failed = words[12];
        endCooking = words[13];
    }

    /** The Language setting's choice. Auto follows the device. */
    static Strings of(String language) {
        if ("Slovenščina".equals(language)) return SLOVENIAN;
        if ("English".equals(language)) return ENGLISH;
        return "sl".equals(Locale.getDefault().getLanguage()) ? SLOVENIAN : ENGLISH;
    }

    /** Today, Tomorrow or the weekday with its date. */
    String day(LocalDate date, LocalDate now) {
        if (date.equals(now)) return today;
        if (date.equals(now.plusDays(1))) return tomorrow;
        return capitalized(shortDate.format(date));
    }

    /** The header's date, such as Wednesday, October 28. */
    String date(LocalDate date) {
        return capitalized(longDate.format(date));
    }

    String time(LocalTime time) {
        return TIME.format(time);
    }

    private String capitalized(String text) {
        return text.isEmpty() ? text : text.substring(0, 1).toUpperCase(locale) + text.substring(1);
    }
}
