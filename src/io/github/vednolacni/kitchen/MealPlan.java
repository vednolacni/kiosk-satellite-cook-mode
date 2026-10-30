// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.kitchen;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** One reading of the meal plan: every calendar's meals in the days shown, in order, and the calendars that failed. */
final class MealPlan {
    /** By day, all-day meals first, then by time, by calendar and in each calendar's own order. */
    private static final Comparator<Meal> ORDER = new Comparator<Meal>() {
        @Override public int compare(Meal a, Meal b) {
            int byDate = a.date.compareTo(b.date);
            if (byDate != 0) return byDate;
            if (a.time == null || b.time == null) {
                if (a.time != b.time) return a.time == null ? -1 : 1;
            } else {
                int byTime = a.time.compareTo(b.time);
                if (byTime != 0) return byTime;
            }
            if (a.calendar != b.calendar) return Integer.compare(a.calendar, b.calendar);
            return Integer.compare(a.order, b.order);
        }
    };

    final LocalDate from;
    final int days;
    final List<Meal> meals;
    /** One "calendar.x: reason" per calendar that could not be read. */
    final List<String> failures;
    /** When the reading finished, in epoch milliseconds. */
    final long readAt;

    MealPlan(LocalDate from, int days, List<Meal> meals, List<String> failures, long readAt) {
        List<Meal> sorted = new ArrayList<>(meals);
        Collections.sort(sorted, ORDER);
        this.from = from;
        this.days = days;
        this.meals = Collections.unmodifiableList(sorted);
        this.failures = Collections.unmodifiableList(new ArrayList<>(failures));
        this.readAt = readAt;
    }

    List<Meal> on(LocalDate day) {
        List<Meal> found = new ArrayList<>();
        for (Meal meal : meals) if (meal.date.equals(day)) found.add(meal);
        return found;
    }

    /**
     * The meals in one calendar.get_events answer, {context, response: {calendar.x: {events: [...]}}}, that fall on
     * a day from from up to until. Events without a title or a readable start are left out.
     */
    static List<Meal> parse(Object result, String entity, int calendar, ZoneId zone, LocalDate from, LocalDate until) {
        List<Meal> meals = new ArrayList<>();
        Object events = map(map(map(result).get("response")).get(entity)).get("events");
        if (!(events instanceof List)) return meals;
        int order = 0;
        for (Object item : (List<?>) events) {
            Map<?, ?> event = map(item);
            String start = text(event.get("start"));
            LocalDate date;
            LocalTime time = null;
            try {
                if (start.length() == 10) {
                    date = LocalDate.parse(start);
                    String end = text(event.get("end"));
                    // An all-day event that began before the first day shown and still runs belongs to that day.
                    if (date.isBefore(from) && end.length() == 10 && LocalDate.parse(end).isAfter(from)) date = from;
                } else {
                    LocalDateTime at = local(start, zone);
                    date = at.toLocalDate();
                    time = at.toLocalTime();
                }
            } catch (DateTimeParseException unreadable) {
                continue;
            }
            if (date.isBefore(from) || !date.isBefore(until)) continue;
            String summary = text(event.get("summary"));
            if (summary.isEmpty()) continue;
            meals.add(new Meal(date, time, summary, text(event.get("description")), text(event.get("location")), calendar, order++));
        }
        return meals;
    }

    /** Home Assistant sends timed events with their offset. Others are taken as local time. */
    private static LocalDateTime local(String value, ZoneId zone) {
        try {
            return OffsetDateTime.parse(value).atZoneSameInstant(zone).toLocalDateTime();
        } catch (DateTimeParseException noOffset) {
            return LocalDateTime.parse(value);
        }
    }

    private static Map<?, ?> map(Object value) {
        return value instanceof Map ? (Map<?, ?>) value : Collections.emptyMap();
    }

    private static String text(Object value) {
        return value instanceof String ? ((String) value).trim() : "";
    }
}
