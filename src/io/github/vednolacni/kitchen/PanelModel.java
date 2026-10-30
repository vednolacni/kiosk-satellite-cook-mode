// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.kitchen;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** What the panel shows, worked out on the plugin's side so the view only lays it out. */
final class PanelModel {
    /** One meal as the panel shows it. */
    static final class Row {
        final Meal meal;
        /** The meal type, or the calendar's label when the title has none. */
        final String overline;
        final String dish;
        /** The time, and the calendar's label when the overline does not say it already. */
        final String detail;
        final int color;
        /** Whether the meal opens its own recipe page rather than the app's home page. */
        final boolean recipe;

        Row(Meal meal, Settings settings, Strings strings) {
            Settings.Calendar calendar = meal.calendar < settings.calendars.size() ? settings.calendars.get(meal.calendar) : null;
            String label = calendar == null ? "" : calendar.label;
            String type = meal.type(settings.splitTitles);
            this.meal = meal;
            overline = type.isEmpty() ? label : type;
            dish = meal.dish(settings.splitTitles);
            List<String> parts = new ArrayList<>();
            if (meal.time != null) parts.add(strings.time(meal.time));
            if (!type.isEmpty() && !label.isEmpty()) parts.add(label);
            detail = String.join(" · ", parts);
            color = calendar == null ? Settings.GREEN : calendar.color;
            recipe = RecipeLinks.recipe(meal, settings) != null;
        }
    }

    /** One of the next days that has meals. */
    static final class Day {
        final String label;
        final List<Row> rows;

        Day(String label, List<Row> rows) {
            this.label = label;
            this.rows = Collections.unmodifiableList(rows);
        }
    }

    final Strings strings;
    final String title;
    final String date;
    final List<Row> today;
    final List<Day> upcoming;
    /** A note above the meals, such as a missing setting or a calendar that failed, or null. */
    final String message;
    final boolean error;
    final boolean canOpenApp;

    private PanelModel(Strings strings, String title, String date, List<Row> today, List<Day> upcoming, String message, boolean error, boolean canOpenApp) {
        this.strings = strings;
        this.title = title;
        this.date = date;
        this.today = Collections.unmodifiableList(today);
        this.upcoming = Collections.unmodifiableList(upcoming);
        this.message = message;
        this.error = error;
        this.canOpenApp = canOpenApp;
    }

    /** The panel for this day. notice is a message of the plugin's own, such as a missing app address. */
    static PanelModel build(Settings settings, Strings strings, MealPlan plan, boolean loading, String notice, LocalDate now) {
        String message = null;
        boolean error = true;
        if (settings.calendars.isEmpty()) message = strings.noCalendars;
        else if (notice != null) message = notice;
        else if (plan != null && !plan.failures.isEmpty()) message = String.format(strings.failed, String.join("; ", plan.failures));
        else if (loading && plan == null) {
            message = strings.loading;
            error = false;
        }
        List<Row> today = new ArrayList<>();
        List<Day> upcoming = new ArrayList<>();
        if (plan != null) {
            for (Meal meal : plan.on(now)) today.add(new Row(meal, settings, strings));
            for (int offset = 1; offset < settings.days; offset++) {
                LocalDate date = now.plusDays(offset);
                List<Row> rows = new ArrayList<>();
                for (Meal meal : plan.on(date)) rows.add(new Row(meal, settings, strings));
                if (!rows.isEmpty()) upcoming.add(new Day(strings.day(date, now), rows));
            }
        }
        String title = settings.title.isEmpty() ? strings.title : settings.title;
        return new PanelModel(strings, title, strings.date(now), today, upcoming, message, error && message != null, RecipeLinks.home(settings) != null);
    }
}
