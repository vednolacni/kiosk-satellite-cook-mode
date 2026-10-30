// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.kitchen;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;

public final class MealPlanTest {
    static final ZoneId ZONE = ZoneId.of("Europe/Ljubljana");
    static final LocalDate TODAY = LocalDate.of(2026, 10, 28);

    static Map<String, Object> event(String start, String end, String summary, String description) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("start", start);
        event.put("end", end);
        event.put("summary", summary);
        if (description != null) event.put("description", description);
        return event;
    }

    /** A call_service answer the way Home Assistant sends it for one calendar. */
    static Map<String, Object> answer(String entity, List<?> events) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put(entity, Collections.singletonMap("events", events));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("context", Collections.singletonMap("id", "01J"));
        result.put("response", response);
        return result;
    }

    static Map<String, Object> settings(String calendar, String label) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("calendar", calendar);
        row.put("label", label);
        row.put("color", "#FF0000");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("calendars", Collections.singletonList(row));
        values.put("days", 7.0);
        values.put("splitTitles", true);
        values.put("appUrl", "http://recipes.local:8080");
        values.put("searchUrl", "");
        values.put("cookMode", true);
        values.put("cookMinutes", 180.0);
        values.put("title", "");
        values.put("language", "English");
        values.put("closeOnScreensaver", true);
        return values;
    }

    public static void main(String[] args) {
        // A Tandoor style all-day meal, a timed one, one past the days shown, a week-long event that started
        // yesterday, an event without a start and one without a title.
        List<Object> events = Arrays.asList(
            event("2026-10-28", "2026-10-29", "Večerja: Lazanja", "Pripravi dan prej."),
            event("2026-10-29T12:30:00+01:00", "2026-10-29T13:30:00+01:00", "Kosilo: Golaž", "Recept: https://recipes.local/view/recipe/12."),
            event("2026-11-05", "2026-11-06", "Too late", null),
            event("2026-10-27", "2026-10-31", "Juhe ta teden", null),
            Collections.singletonMap("summary", "No start"),
            event("2026-10-30", "2026-10-31", "  ", null));
        List<Meal> meals = MealPlan.parse(answer("calendar.meals", events), "calendar.meals", 0, ZONE, TODAY, TODAY.plusDays(7));
        assert meals.size() == 3 : meals.size();

        MealPlan plan = new MealPlan(TODAY, 7, meals, Collections.<String>emptyList(), 0);
        List<Meal> today = plan.on(TODAY);
        assert today.size() == 2;
        // All-day meals keep the calendar's own order. The week-long event shows from the first day shown.
        assert "Večerja: Lazanja".equals(today.get(0).summary);
        assert "Juhe ta teden".equals(today.get(1).summary) && today.get(1).time == null;
        Meal lunch = plan.on(TODAY.plusDays(1)).get(0);
        assert LocalTime.of(12, 30).equals(lunch.time);

        // Meal types split from the dish, but times and plain titles do not.
        Meal dinner = today.get(0);
        assert "Večerja".equals(dinner.type(true)) && "Lazanja".equals(dinner.dish(true));
        assert "".equals(dinner.type(false)) && "Večerja: Lazanja".equals(dinner.dish(false));
        Meal timed = new Meal(TODAY, null, "12:30 Lunch", "", "", 0, 0);
        assert "".equals(timed.type(true)) && "12:30 Lunch".equals(timed.dish(true));
        Meal numbered = new Meal(TODAY, null, "2: Leftovers", "", "", 0, 0);
        assert "".equals(numbered.type(true));

        // Timed meals sort after all-day ones, then by time, then by calendar.
        Meal early = new Meal(TODAY, LocalTime.of(8, 0), "Breakfast", "", "", 1, 0);
        Meal late = new Meal(TODAY, LocalTime.of(19, 0), "Dinner", "", "", 0, 0);
        Meal allDay = new Meal(TODAY, null, "Soup", "", "", 1, 0);
        List<Meal> sorted = new MealPlan(TODAY, 1, Arrays.asList(late, early, allDay), Collections.<String>emptyList(), 0).meals;
        assert sorted.get(0) == allDay && sorted.get(1) == early && sorted.get(2) == late;

        // An answer for another calendar or without events reads as no meals.
        assert MealPlan.parse(answer("calendar.other", events), "calendar.meals", 0, ZONE, TODAY, TODAY.plusDays(7)).isEmpty();
        assert MealPlan.parse(null, "calendar.meals", 0, ZONE, TODAY, TODAY.plusDays(7)).isEmpty();

        // Settings read the list rows, and fall back for missing values.
        Settings settings = new Settings(settings("calendar.meals", "Family"));
        assert settings.calendars.size() == 1 && settings.calendars.get(0).color == 0xFFFF0000;
        assert settings.days == 7 && settings.cookMinutes == 180 && settings.problem() == null;
        assert new Settings(Collections.<String, Object>emptyMap()).problem() != null;
        Map<String, Object> wrong = settings("sensor.meals", "");
        assert new Settings(wrong).problem().contains("not a calendar");
        wrong = settings("calendar.meals", "");
        wrong.put("appUrl", "recipes.local");
        assert new Settings(wrong).problem().contains("recipe app address");
        wrong.put("appUrl", "");
        wrong.put("searchUrl", "http://recipes.local/search");
        assert new Settings(wrong).problem().contains("{title}");

        // The panel: today's meals, the next days with meals and the labels.
        PanelModel model = PanelModel.build(settings, Strings.ENGLISH, plan, false, null, TODAY);
        assert "Meal plan".equals(model.title) && "Wednesday, October 28".equals(model.date) : model.date;
        assert model.today.size() == 2 && model.message == null && model.canOpenApp;
        PanelModel.Row first = model.today.get(0);
        assert "Večerja".equals(first.overline) && "Lazanja".equals(first.dish) && "Family".equals(first.detail);
        assert first.color == 0xFFFF0000 && !first.recipe;
        PanelModel.Row soup = model.today.get(1);
        assert "Family".equals(soup.overline) && "".equals(soup.detail);
        assert model.upcoming.size() == 1 && "Tomorrow".equals(model.upcoming.get(0).label);
        PanelModel.Row golaz = model.upcoming.get(0).rows.get(0);
        assert "12:30 · Family".equals(golaz.detail) && golaz.recipe;

        // Messages: no calendars, a failed calendar, the first reading and a notice of the plugin's own.
        PanelModel empty = PanelModel.build(new Settings(Collections.<String, Object>emptyMap()), Strings.ENGLISH, null, false, null, TODAY);
        assert empty.error && Strings.ENGLISH.noCalendars.equals(empty.message) && !empty.canOpenApp;
        MealPlan failed = new MealPlan(TODAY, 7, meals, Collections.singletonList("calendar.meals: timeout"), 0);
        assert PanelModel.build(settings, Strings.ENGLISH, failed, false, null, TODAY).message.contains("calendar.meals: timeout");
        PanelModel loading = PanelModel.build(settings, Strings.ENGLISH, null, true, null, TODAY);
        assert !loading.error && Strings.ENGLISH.loading.equals(loading.message);
        assert PanelModel.build(settings, Strings.ENGLISH, plan, false, "Note", TODAY).message.equals("Note");

        // Day labels in both languages.
        Strings sl = Strings.SLOVENIAN;
        assert "Danes".equals(sl.day(TODAY, TODAY)) && "Jutri".equals(sl.day(TODAY.plusDays(1), TODAY));
        assert "Petek, 30. 10.".equals(sl.day(TODAY.plusDays(2), TODAY)) : sl.day(TODAY.plusDays(2), TODAY);
        assert "Friday, Oct 30".equals(Strings.ENGLISH.day(TODAY.plusDays(2), TODAY));
        assert "Sreda, 28. oktober".equals(sl.date(TODAY)) : sl.date(TODAY);
        assert Strings.of("Slovenščina") == sl && Strings.of("English") == Strings.ENGLISH;
        Map<String, Object> slovenian = settings("calendar.meals", "");
        slovenian.put("title", "Kuhinja");
        PanelModel titled = PanelModel.build(new Settings(slovenian), sl, plan, false, null, TODAY);
        assert "Kuhinja".equals(titled.title);
        System.out.println("MealPlanTest passed");
    }
}
