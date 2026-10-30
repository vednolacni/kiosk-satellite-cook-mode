// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.kitchen;

import java.time.*;
import java.util.*;
import me.jxl.kiosk.plugins.OverlayFactory;
import me.jxl.kiosk.plugins.OverlaySpec;
import me.jxl.kiosk.plugins.PluginHost;

public final class KitchenTest {
    /** A clock the test moves by hand. */
    static final class TestClock extends Clock {
        Instant now = ZonedDateTime.of(2026, 10, 28, 17, 0, 0, 0, MealPlanTest.ZONE).toInstant();
        @Override public ZoneId getZone() { return MealPlanTest.ZONE; }
        @Override public Clock withZone(ZoneId zone) { throw new UnsupportedOperationException(); }
        @Override public Instant instant() { return now; }
        void advance(Duration by) { now = now.plus(by); }
    }

    static final class Call {
        final String name;
        final Map<String, Object> arguments;
        final PluginHost.CommandCallback callback;
        Call(String name, Map<String, Object> arguments, PluginHost.CommandCallback callback) { this.name = name; this.arguments = arguments; this.callback = callback; }
    }

    static final class Host implements PluginHost {
        final List<Call> commands = new ArrayList<>();
        final List<Call> haCalls = new ArrayList<>();
        final Set<String> subscriptions = new HashSet<>();
        final Map<String, OverlaySpec> overlays = new LinkedHashMap<>();
        String select;
        String status;
        boolean statusError;
        public void executeCommand(String command, Map<String, Object> arguments, CommandCallback callback) {
            // KS takes at most two arguments, each a string or a boolean.
            assert arguments.size() <= 2 && callback != null;
            for (Object value : arguments.values()) assert value instanceof String || value instanceof Boolean;
            commands.add(new Call(command, arguments, callback));
        }
        public void subscribe(String event) { subscriptions.add(event); }
        public void unsubscribe(String event) { subscriptions.remove(event); }
        public void haCall(Map<String, Object> message, CommandCallback callback) {
            assert !message.containsKey("id");
            haCalls.add(new Call((String) message.get("type"), message, callback));
        }
        public void showOverlay(String key, OverlaySpec spec, OverlayFactory factory) {
            assert key.matches("[a-z][a-z0-9_]{0,39}") && factory != null;
            overlays.put(key, spec);
        }
        public void hideOverlay(String key) { overlays.remove(key); }
        public void publishSelect(String key, String name, String[] options, String state) {
            assert Kitchen.VIEW.equals(key) && Arrays.asList(options).contains(state);
            select = state;
        }
        public void status(String message, boolean error) { assert message.length() <= 1000; status = message; statusError = error; }
        public void showWindow(String title, String message, String buttonLabel) {}
        public void hideWindow() {}
        public void log(String message) {}

        Call last() { return commands.get(commands.size() - 1); }
        Call last(String name) {
            for (int i = commands.size() - 1; i >= 0; i--) if (commands.get(i).name.equals(name)) return commands.get(i);
            return null;
        }
        int count(String name) {
            int count = 0;
            for (Call call : commands) if (call.name.equals(name)) count++;
            return count;
        }
    }

    static final class FakeUi implements Ui {
        PanelModel model;
        Ui.Actions actions;
        Runnable end;
        final Map<Runnable, Long> tasks = new LinkedHashMap<>();
        public OverlayFactory panel(Ui.Actions actions) { this.actions = actions; return (context, theme) -> null; }
        public void render(PanelModel model) { this.model = model; }
        public OverlayFactory pill(String label, Runnable end) { assert !label.isEmpty(); this.end = end; return (context, theme) -> null; }
        public void post(Runnable task, long delayMs) { assert delayMs > 0; tasks.put(task, delayMs); }
        public void cancel(Runnable task) { tasks.remove(task); }
        /** Run the one posted task, the way the main thread would. */
        void runOnly() {
            assert tasks.size() == 1 : tasks;
            Runnable task = tasks.keySet().iterator().next();
            tasks.remove(task);
            task.run();
        }
    }

    static List<Object> events() {
        return Arrays.<Object>asList(
            MealPlanTest.event("2026-10-28", "2026-10-29", "Dinner: Lasagna", null),
            MealPlanTest.event("2026-10-29T12:30:00+01:00", "2026-10-29T13:30:00+01:00", "Lunch: Goulash", "https://recipes.local/view/recipe/12"));
    }

    static Map<String, Object> key(String name, Object value) {
        return Collections.singletonMap(name, value);
    }

    public static void main(String[] args) {
        TestClock clock = new TestClock();
        FakeUi ui = new FakeUi();
        Host host = new Host();
        Kitchen kitchen = new Kitchen(ui, clock);
        Map<String, Object> values = MealPlanTest.settings("calendar.meals", "Family");
        kitchen.start(host, values);

        // Start: the select says Closed, the screensaver is followed and the plan is read for today and six more days.
        assert "Closed".equals(host.select);
        assert host.subscriptions.contains("screensaver.state") && host.subscriptions.contains("screensaver.countdown");
        assert host.haCalls.size() == 1;
        Map<String, Object> message = host.haCalls.get(0).arguments;
        assert "call_service".equals(message.get("type")) && "calendar".equals(message.get("domain")) && "get_events".equals(message.get("service"));
        assert Boolean.TRUE.equals(message.get("return_response"));
        assert "calendar.meals".equals(((Map<?, ?>) message.get("target")).get("entity_id"));
        Map<?, ?> data = (Map<?, ?>) message.get("service_data");
        assert "2026-10-28T00:00:00+01:00".equals(data.get("start_date_time")) : data;
        assert "2026-11-04T00:00:00+01:00".equals(data.get("end_date_time")) : data;
        assert Strings.ENGLISH.loading.equals(ui.model.message);

        // A refresh during a reading waits for it, then reads again.
        kitchen.refresh();
        assert host.haCalls.size() == 1;
        host.haCalls.get(0).callback.onResult(true, MealPlanTest.answer("calendar.meals", events()), null);
        assert host.haCalls.size() == 2;
        host.haCalls.get(1).callback.onResult(true, MealPlanTest.answer("calendar.meals", events()), null);
        assert ui.model.today.size() == 1 && ui.model.message == null;
        assert host.status.startsWith("Read 2 meals for the next 7 days at 17:00") && !host.statusError : host.status;

        // Show recipes: the screensaver goes, the panel covers the kiosk, the select follows and the plan is read again.
        kitchen.execute("showRecipes");
        assert host.count("stopScreensaver") == 1;
        OverlaySpec spec = host.overlays.get(Kitchen.PANEL);
        assert spec.width() == OverlaySpec.FILL && spec.height() == OverlaySpec.FILL && spec.closeOnBack();
        assert "Open".equals(host.select) && host.haCalls.size() == 3;
        assert ui.tasks.containsValue(Kitchen.REFRESH_MS);
        host.haCalls.get(2).callback.onResult(true, MealPlanTest.answer("calendar.meals", events()), null);

        // A meal with a link opens it with hold, and the panel goes first because plugin overlays cover KS pages.
        Meal goulash = ui.model.upcoming.get(0).rows.get(0).meal;
        ui.actions.open(goulash);
        assert !host.overlays.containsKey(Kitchen.PANEL) && "Closed".equals(host.select);
        assert !ui.tasks.containsValue(Kitchen.REFRESH_MS);
        Call page = host.last("showLinkPage");
        assert "https://recipes.local/view/recipe/12".equals(page.arguments.get("url")) && Boolean.TRUE.equals(page.arguments.get("hold"));
        page.callback.onResult(true, null, null);
        assert !kitchen.cooking() && !host.overlays.containsKey(Kitchen.PILL);
        // The app's home page asks for hold too, and End cooking closes the page, which lets go of the hold.
        ui.actions.openApp();
        assert "http://recipes.local:8080".equals(host.last("showLinkPage").arguments.get("url"));
        assert Boolean.TRUE.equals(host.last("showLinkPage").arguments.get("hold"));
        kitchen.execute("endCooking");
        assert "hideOverlayPage".equals(host.last().name);

        // Back closes the panel. So does the screensaver. The select opens it.
        kitchen.execute("toggleRecipes");
        assert "Open".equals(host.select);
        kitchen.onEvent("overlay.closed", key("key", Kitchen.PANEL));
        assert "Closed".equals(host.select);
        kitchen.onEvent("select." + Kitchen.VIEW, key("option", "Open"));
        assert "Open".equals(host.select) && host.overlays.containsKey(Kitchen.PANEL);
        kitchen.onEvent("ks.screensaver.state", key("active", true));
        assert "Closed".equals(host.select) && !host.overlays.containsKey(Kitchen.PANEL);
        boolean refused = false;
        try {
            kitchen.onEvent("select." + Kitchen.VIEW, key("option", "Half"));
        } catch (IllegalArgumentException expected) {
            refused = true;
        }
        assert refused;

        // Without an app address, a meal without a link says so in the panel and in the plugin's status.
        values.put("appUrl", "");
        kitchen.configure(values);
        kitchen.execute("showRecipes");
        int pages = host.count("showLinkPage");
        ui.actions.open(ui.model.today.get(0).meal);
        assert host.count("showLinkPage") == pages && Strings.ENGLISH.noApp.equals(ui.model.message) && host.statusError;
        assert host.overlays.containsKey(Kitchen.PANEL);
        kitchen.stop();

        fallback();
        System.out.println("KitchenTest passed");
    }

    /** A Kiosk Satellite release that refuses hold from plugins: a cooking session with its pill and beats. */
    static void fallback() {
        TestClock clock = new TestClock();
        FakeUi ui = new FakeUi();
        Host host = new Host();
        Kitchen kitchen = new Kitchen(ui, clock);
        kitchen.start(host, MealPlanTest.settings("calendar.meals", ""));
        kitchen.execute("openRecipeApp");
        Call hold = host.last("showLinkPage");
        assert Boolean.TRUE.equals(hold.arguments.get("hold"));
        hold.callback.onResult(false, null, "Invalid arguments");
        Call plain = host.last("showLinkPage");
        assert plain != hold && !plain.arguments.containsKey("hold") && "http://recipes.local:8080".equals(plain.arguments.get("url"));
        assert !kitchen.cooking();
        plain.callback.onResult(true, null, null);
        assert kitchen.cooking() && host.overlays.containsKey(Kitchen.PILL) && !host.overlays.get(Kitchen.PILL).closeOnBack();
        assert "postponeScreensaver".equals(host.last().name);
        assert ui.tasks.size() == 1 && ui.tasks.containsValue(CookMode.BEAT_MS);

        // KS armed the screensaver 20 seconds out, so the beat moves to 5 seconds before it.
        kitchen.onEvent("ks.screensaver.countdown", key("due", clock.instant().plusSeconds(20).toString()));
        assert ui.tasks.size() == 1 && ui.tasks.containsValue(15_000L) : ui.tasks;
        clock.advance(Duration.ofSeconds(15));
        int postpones = host.count("postponeScreensaver");
        ui.runOnly();
        assert host.count("postponeScreensaver") == postpones + 1 && ui.tasks.containsValue(CookMode.BEAT_MS);
        // A countdown without a deadline falls back to the plain beat.
        kitchen.onEvent("ks.screensaver.countdown", key("due", null));
        assert ui.tasks.size() == 1 && ui.tasks.containsValue(CookMode.BEAT_MS);

        // The next page goes straight to a session, without asking for hold again.
        int count = host.count("showLinkPage");
        kitchen.execute("openRecipeApp");
        assert host.count("showLinkPage") == count + 1 && !host.last("showLinkPage").arguments.containsKey("hold");
        host.last("showLinkPage").callback.onResult(true, null, null);
        assert kitchen.cooking();

        // The session ends after the longest cooking time: the pill goes and the page closes.
        clock.advance(Duration.ofMinutes(181));
        ui.runOnly();
        assert !kitchen.cooking() && !host.overlays.containsKey(Kitchen.PILL) && "hideOverlayPage".equals(host.last().name);
        assert ui.tasks.isEmpty();

        // The pill's tap ends a session too.
        kitchen.execute("openRecipeApp");
        host.last("showLinkPage").callback.onResult(true, null, null);
        assert kitchen.cooking();
        ui.end.run();
        assert !kitchen.cooking() && !host.overlays.containsKey(Kitchen.PILL) && ui.tasks.isEmpty();

        // With cook mode off, pages open plainly and no session starts.
        Map<String, Object> values = MealPlanTest.settings("calendar.meals", "");
        values.put("cookMode", false);
        kitchen.configure(values);
        kitchen.execute("openRecipeApp");
        assert !host.last("showLinkPage").arguments.containsKey("hold");
        host.last("showLinkPage").callback.onResult(true, null, null);
        assert !kitchen.cooking();

        // A page that fails to open says why.
        kitchen.execute("openRecipeApp");
        host.last("showLinkPage").callback.onResult(false, null, "Lockdown Mode is on");
        assert host.statusError && host.status.contains("Lockdown Mode is on");

        // After stop, late answers and commands change nothing.
        kitchen.configure(MealPlanTest.settings("calendar.meals", ""));
        kitchen.execute("openRecipeApp");
        Call late = host.last("showLinkPage");
        kitchen.stop();
        late.callback.onResult(false, null, "Invalid arguments");
        assert !kitchen.cooking() && !host.overlays.containsKey(Kitchen.PILL);
        int commands = host.commands.size();
        kitchen.execute("showRecipes");
        assert host.commands.size() == commands && !host.overlays.containsKey(Kitchen.PANEL);
    }
}
