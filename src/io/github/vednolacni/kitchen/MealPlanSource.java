// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.kitchen;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * Reads the meal plan with Home Assistant's calendar.get_events, one call per calendar so a calendar too large for
 * KS's 32 KB answer only costs itself. Answers arrive on the plugin's worker and are handled under the plugin's lock.
 */
final class MealPlanSource {
    interface Listener {
        void read(MealPlan plan);
    }

    private final Object lock;
    private final Clock clock;
    private final Listener listener;
    private PluginHost host;
    private Settings settings;
    /** Answers of an older reading carry an older generation and are dropped. */
    private int generation;
    private int pending;
    /** A reading was asked for while one ran. */
    private boolean again;
    private LocalDate from;
    private int days;
    private List<Meal> meals;
    private List<String> failures;

    MealPlanSource(Object lock, Clock clock, Listener listener) {
        this.lock = lock;
        this.clock = clock;
        this.listener = listener;
    }

    void start(PluginHost host) {
        this.host = host;
        reset();
    }

    void stop() {
        reset();
        host = null;
    }

    /** Forget a reading in progress, such as after the calendars changed. */
    void reset() {
        generation++;
        pending = 0;
        again = false;
    }

    boolean reading() {
        return pending > 0;
    }

    /** Read the plan for these settings. Asked during a reading, it reads again once that one ends. */
    void read(Settings settings) {
        this.settings = settings;
        if (host == null || settings.calendars.isEmpty()) return;
        if (pending > 0) {
            again = true;
            return;
        }
        final int run = ++generation;
        final LocalDate first = LocalDate.now(clock);
        final LocalDate until = first.plusDays(settings.days);
        from = first;
        days = settings.days;
        meals = new ArrayList<>();
        failures = new ArrayList<>();
        pending = settings.calendars.size();
        for (int i = 0; i < settings.calendars.size(); i++) {
            final int index = i;
            final String entity = settings.calendars.get(i).entity;
            try {
                host.haCall(message(entity, first, until, clock.getZone()), (ok, data, error) -> {
                    synchronized (lock) {
                        answered(run, index, entity, ok ? data : null, ok ? null : String.valueOf(error), until);
                    }
                });
            } catch (RuntimeException refused) {
                // Too many requests at once, or the session just ended.
                answered(run, index, entity, null, String.valueOf(refused.getMessage()), until);
            }
        }
    }

    private void answered(int run, int index, String entity, Object data, String error, LocalDate until) {
        if (run != generation || host == null) return;
        if (error != null) failures.add(entity + ": " + error);
        else meals.addAll(MealPlan.parse(data, entity, index, clock.getZone(), from, until));
        if (--pending > 0) return;
        listener.read(new MealPlan(from, days, meals, failures, clock.millis()));
        if (again) {
            again = false;
            read(settings);
        }
    }

    /** The WebSocket message for one calendar, without its id. KS numbers it. */
    static Map<String, Object> message(String entity, LocalDate from, LocalDate until, ZoneId zone) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("start_date_time", from.atStartOfDay(zone).toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        data.put("end_date_time", until.atStartOfDay(zone).toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "call_service");
        message.put("domain", "calendar");
        message.put("service", "get_events");
        message.put("service_data", data);
        message.put("target", Collections.singletonMap("entity_id", entity));
        message.put("return_response", true);
        return message;
    }
}
