// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.kitchen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** The validated settings KS passes to start and configure, read once into plain fields. */
final class Settings {
    /** The color a calendar row gets when it has none. */
    static final int GREEN = 0xFF43A047;

    /** One row of the Meal plan calendars list. */
    static final class Calendar {
        final String entity;
        final String label;
        final int color;

        Calendar(String entity, String label, int color) {
            this.entity = entity;
            this.label = label;
            this.color = color;
        }
    }

    final List<Calendar> calendars;
    final int days;
    final boolean splitTitles;
    final String appUrl;
    final String searchUrl;
    final boolean cookMode;
    final int cookMinutes;
    final String title;
    final String language;
    final boolean closeOnScreensaver;

    Settings(Map<String, Object> values) {
        List<Calendar> list = new ArrayList<>();
        Object rows = values.get("calendars");
        if (rows instanceof List) {
            for (Object row : (List<?>) rows) {
                if (!(row instanceof Map)) continue;
                Map<?, ?> fields = (Map<?, ?>) row;
                String entity = text(fields.get("calendar"));
                if (!entity.isEmpty()) list.add(new Calendar(entity, text(fields.get("label")), color(fields.get("color"))));
            }
        }
        calendars = Collections.unmodifiableList(list);
        // KS fills in every declared setting. The fallbacks keep tests and older saves readable.
        days = clamp(number(values.get("days"), 7), 1, 14);
        splitTitles = !Boolean.FALSE.equals(values.get("splitTitles"));
        appUrl = text(values.get("appUrl"));
        searchUrl = text(values.get("searchUrl"));
        cookMode = !Boolean.FALSE.equals(values.get("cookMode"));
        cookMinutes = clamp(number(values.get("cookMinutes"), 180), 15, 480);
        title = text(values.get("title"));
        language = values.get("language") instanceof String ? (String) values.get("language") : "Auto";
        closeOnScreensaver = !Boolean.FALSE.equals(values.get("closeOnScreensaver"));
    }

    /** The calendar entities in list order, which decide whether a new configuration needs a new reading. */
    List<String> calendarIds() {
        List<String> ids = new ArrayList<>();
        for (Calendar calendar : calendars) ids.add(calendar.entity);
        return ids;
    }

    /** What keeps the plugin from working, for its status, or null when nothing does. */
    String problem() {
        if (calendars.isEmpty()) return "Add a meal plan calendar under Meal plan calendars.";
        for (Calendar calendar : calendars) {
            if (!calendar.entity.startsWith("calendar.")) return calendar.entity + " is not a calendar entity.";
        }
        if (!appUrl.isEmpty() && !RecipeLinks.valid(appUrl)) return "The recipe app address must be an http:// or https:// address.";
        if (!searchUrl.isEmpty() && !RecipeLinks.validTemplate(searchUrl)) return "The recipe search address must be an http:// or https:// address with {title} in it.";
        return null;
    }

    private static String text(Object value) {
        return value instanceof String ? ((String) value).trim() : "";
    }

    private static int number(Object value, int fallback) {
        return value instanceof Number ? (int) Math.round(((Number) value).doubleValue()) : fallback;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** A #RRGGBB setting as an opaque ARGB color. */
    private static int color(Object value) {
        if (value instanceof String && ((String) value).matches("#[0-9A-Fa-f]{6}")) {
            return 0xFF000000 | Integer.parseInt(((String) value).substring(1), 16);
        }
        return GREEN;
    }
}
