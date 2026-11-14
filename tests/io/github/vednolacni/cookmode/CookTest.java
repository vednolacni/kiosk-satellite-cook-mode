// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import java.time.*;
import java.util.*;
import java.util.concurrent.Executor;
import me.jxl.kiosk.plugins.OverlayFactory;
import me.jxl.kiosk.plugins.OverlaySpec;
import me.jxl.kiosk.plugins.PluginHost;

/** The controller end to end against a fake host, view and recipe source: everything but the Android views. */
public final class CookTest {
    static final ZoneId ZONE = ZoneId.of("Europe/Ljubljana");

    static final class TestClock extends Clock {
        Instant now = ZonedDateTime.of(2026, 11, 1, 11, 0, 0, 0, ZONE).toInstant();
        @Override public ZoneId getZone() { return ZONE; }
        @Override public Clock withZone(ZoneId zone) { throw new UnsupportedOperationException(); }
        @Override public Instant instant() { return now; }
        void seconds(long seconds) { now = now.plusSeconds(seconds); }
        void minutes(long minutes) { seconds(minutes * 60); }
    }

    static final class Host implements PluginHost {
        final List<String> commands = new ArrayList<>();
        final List<Map<String, Object>> commandArguments = new ArrayList<>();
        final Set<String> subscriptions = new HashSet<>();
        final Map<String, OverlaySpec> overlays = new LinkedHashMap<>();
        final Map<String, Object> entities = new HashMap<>();
        final List<String> triggers = new ArrayList<>();
        final List<Map<String, Object>> haCalls = new ArrayList<>();
        final List<String> setup = new ArrayList<>();
        final List<CommandCallback> haAnswers = new ArrayList<>();
        final Map<String, Object> saved = new HashMap<>();
        String deviceName = "Kitchen";
        String status = "";
        boolean statusError;
        /** How many times KS cannot say which kiosk this is. */
        int deviceFailures;
        /** KS refuses commands and calls, as it does with too many pending. */
        boolean refuse;
        boolean refuseOverlays;
        public void executeCommand(String command, Map<String, Object> arguments, CommandCallback callback) {
            assert arguments.size() <= 2 && callback != null;
            if (refuse) throw new IllegalStateException("At most 8 KS commands may be pending");
            commands.add(command);
            commandArguments.add(arguments);
            if ("getDeviceInfo".equals(command)) {
                if (deviceFailures > 0) {
                    deviceFailures--;
                    callback.onResult(false, null, "KS could not complete this command");
                } else {
                    callback.onResult(true, Collections.singletonMap("name", deviceName), null);
                }
            }
        }
        public void updateSettings(Map<String, Object> values) { saved.putAll(values); }
        public void subscribe(String event) { subscriptions.add(event); }
        public void haCall(Map<String, Object> message, CommandCallback callback) {
            assert !message.containsKey("id");
            if (refuse) throw new IllegalStateException("At most 8 KS commands may be pending");
            haCalls.add(message);
            haAnswers.add(callback);
        }
        public void showOverlay(String key, OverlaySpec spec, OverlayFactory factory) {
            assert key.matches("[a-z][a-z0-9_]{0,39}") && factory != null;
            if (refuseOverlays) throw new IllegalStateException("Too many overlays in a second");
            overlays.put(key, spec);
        }
        public void hideOverlay(String key) { overlays.remove(key); }
        public void status(String message, boolean error) { assert message.length() <= 1000; status = message; statusError = error; }
        public void publishSelect(String key, String name, String[] options, String state) { assert Arrays.asList(options).contains(state); entities.put(key, state); }
        public void publishBinarySensor(String key, String name, String deviceClass, Boolean state) { entities.put(key, state); }
        public void publishSensor(String key, String name, Map<String, Object> metadata, Double state) { entities.put(key, state); }
        public void publishTextSensor(String key, String name, String state) { assert state.length() <= Cook.STATE_MOST : "Home Assistant would show unknown"; entities.put(key, state); }
        public void fireTrigger(String id) { triggers.add(id); }
        public boolean setupResult(String run, String state, String message) { setup.add(run + " " + state + " " + message); return true; }
        public void showWindow(String title, String message, String buttonLabel) {}
        public void hideWindow() {}
        public void log(String message) {}
        int count(String command) {
            int count = 0;
            for (String name : commands) if (name.equals(command)) count++;
            return count;
        }
    }

    static final class FakeUi implements Ui {
        Model model;
        Ui.Actions actions;
        String dock = "";
        int renders;
        boolean alarm;
        int alarmStarts;
        int silences;
        boolean shutdown;
        boolean screenOn;
        long elapsed = -1;
        Settings.Tone tone;
        final List<String> spoken = new ArrayList<>();
        final Map<Runnable, Long> tasks = new LinkedHashMap<>();
        public OverlayFactory main(Ui.Actions actions) { this.actions = actions; return (context, theme) -> null; }
        public OverlayFactory dock(Ui.Actions actions) { this.actions = actions; return (context, theme) -> null; }
        public void render(Model model) { this.model = model; renders++; }
        public void renderDock(String cooking, Strings strings) { dock = cooking; }
        public void sound(Settings.Tone tone, boolean mediaStream, int minutes) { this.tone = tone; }
        public void alarm(boolean on) { alarm = on; if (on) alarmStarts++; }
        public void testAlarm(Ui.Report report) { report.result(true, "Ringing."); }
        public void speak(String text, Locale locale) { spoken.add(text); }
        public void silence() { silences++; }
        public void keepScreenOn(boolean on) { screenOn = on; }
        public void shutdown() { shutdown = true; }
        public long elapsed() { return elapsed; }
        public void post(Runnable task, long delayMs) { assert delayMs > 0; tasks.put(task, delayMs); }
        public void cancel(Runnable task) { tasks.remove(task); }
        boolean posted(long delay) { return tasks.containsValue(delay); }
        void run(long delay) {
            for (Map.Entry<Runnable, Long> task : tasks.entrySet()) {
                if (task.getValue() == delay) {
                    Runnable found = task.getKey();
                    tasks.remove(found);
                    found.run();
                    return;
                }
            }
            throw new AssertionError("No task posted with delay " + delay + ": " + tasks.values());
        }
    }

    /** Network work waits here until the test lets it run. */
    static final class Net implements Executor {
        final List<Runnable> queue = new ArrayList<>();
        public void execute(Runnable task) { queue.add(task); }
        void runAll() {
            while (!queue.isEmpty()) queue.remove(0).run();
        }
    }

    static final class Source implements RecipeSource {
        List<Planned> plan = new ArrayList<>();
        List<Summary> list = new ArrayList<>();
        Recipe recipe;
        SourceException fail;
        RuntimeException bug;
        final List<String> calls = new ArrayList<>();
        public String probe() throws SourceException { calls.add("probe"); if (bug != null) throw bug; if (fail != null) throw fail; return "Connected to Tandoor 2.7.2, 2 recipes."; }
        public List<Planned> mealPlan(LocalDate first, LocalDate last) throws SourceException { calls.add("plan " + first + " " + last); if (fail != null) throw fail; return plan; }
        public Page recipes(String query, Order order, int page) throws SourceException {
            calls.add("recipes " + query + " " + order + " " + page);
            if (fail != null) throw fail;
            return new Page(list, page < 2, 30);
        }
        public Recipe recipe(String id) throws SourceException { calls.add("recipe " + id); if (fail != null) throw fail; return recipe; }
        public void viewed(String id) { calls.add("viewed " + id); }
        public void cooked(String id, double servings) throws SourceException { calls.add("cooked " + id + " " + servings); if (fail != null) throw fail; }
        public Map<String, String> imageHeaders(String url) { return Collections.emptyMap(); }
        LocalDate reach = LocalDate.MAX;
        LocalDate reachFrom;
        public LocalDate planReach(LocalDate serverDay) { reachFrom = serverDay; return reach; }
        long refresh;
        public long planRefresh(long usual) { return refresh > 0 ? refresh : usual; }
        boolean counts;
        public boolean countsRequests() { return counts; }
        RecipeSource twinSource;
        public RecipeSource twin() { calls.add("twin"); return twinSource; }
        public void cancel() { calls.add("cancel"); }
    }

    static final TestClock clock = new TestClock();
    static final LocalDate TODAY = LocalDate.of(2026, 11, 1);
    static Host host;
    static FakeUi ui;
    static Net net;
    static Source source;
    static Cook cook;
    /** How many sources the plugin has made. */
    static int created;

    static Map<String, Object> settings() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("serverUrl", "http://tandoor.local:8080/");
        values.put("apiToken", "tda_secret");
        values.put("planDays", 7.0);
        values.put("textSize", "Large");
        values.put("language", "English");
        values.put("readAloud", "Off");
        values.put("kioskNode", "");
        values.put("autoRead", false);
        values.put("markCooked", "Ask");
        values.put("keepAwake", true);
        values.put("awakeMinutes", 90.0);
        values.put("alarmTone", "Alarm tone");
        values.put("closeOnScreensaver", true);
        values.put("splitSteps", true);
        values.put("dockCorner", "Top right");
        return values;
    }

    static Recipe recipe() {
        Recipe.Ingredient flour = new Recipe.Ingredient(false, 200, "g", "", "flour", "", "sifted", "");
        Recipe.Ingredient eggs = new Recipe.Ingredient(false, 2, "", "", "egg", "eggs", "", "");
        List<Recipe.Step> steps = Arrays.asList(
            new Recipe.Step("Dough", "<p>Mix <scalable-number v-bind:number=\"200.0\"></scalable-number> g flour for 5 min.</p>", Recipe.Format.TANDOOR_HTML, Arrays.asList(flour, eggs), 0),
            new Recipe.Step("", "Bake.", Recipe.Format.PLAIN, Collections.<Recipe.Ingredient>emptyList(), 25 * 60));
        return new Recipe("7", "Pancakes", "", "http://tandoor.local:8080/media/a.jpg", 4, "", 20, 10, "http://tandoor.local:8080/view/recipe/7", "", steps);
    }

    static RecipeSource.Planned meal(String id, String title, String recipeId, int slotOrder, double servings) {
        return new RecipeSource.Planned(id, TODAY, "Lunch", slotOrder, 0, recipeId, title, "", servings, "", 30);
    }

    static void fresh(Map<String, Object> values) {
        clock.now = ZonedDateTime.of(2026, 11, 1, 11, 0, 0, 0, ZONE).toInstant();
        host = new Host();
        ui = new FakeUi();
        net = new Net();
        source = new Source();
        source.recipe = recipe();
        source.plan = new ArrayList<>(Arrays.asList(meal("1", "Pancakes", "7", 12 * 60, 2), meal("2", "Leftovers", null, 19 * 60, 0)));
        source.list = new ArrayList<>(Arrays.asList(new RecipeSource.Summary("7", "Pancakes", "", 30), new RecipeSource.Summary("8", "Soup", "", 45)));
        cook = new Cook(ui, clock, net, settings -> {
            created++;
            return source;
        });
        cook.start(host, values);
    }

    static Map<String, Object> event(String key, Object value) {
        return Collections.singletonMap(key, value);
    }

    public static void main(String[] args) {
        startAndHome();
        cookingFlow();
        timers();
        screen();
        voice();
        commands();
        problems();
        questions();
        restart();
        secondTimer();
        refused();
        restartWaiting();
        banners();
        covered();
        resting();
        stepTimers();
        finishing();
        switching();
        settingsChange();
        readingBusy();
        clockBehind();
        longRest();
        norish();
        System.out.println("CookTest passed");
    }

    static void norish() {
        // Norish: the panel names it, nothing offers to record a meal, and a key that asked too much waits.
        Map<String, Object> values = settings();
        values.put("recipeApp", "Norish");
        values.put("markCooked", "Always");
        fresh(values);
        net.runAll();
        cook.execute("openCookMode");
        net.runAll();
        assert "Open in Norish".equals(ui.model.strings.openInApp) && "Newest".equals(ui.model.strings.recent);
        source.fail = new RecipeSource.SourceException(RecipeSource.SourceException.Kind.LIMIT, "Norish asks to wait 31 minutes.");
        cook.refresh();
        net.runAll();
        assert ui.model.strings.problemLimit.equals(ui.model.banner) && host.statusError && "Norish asks to wait 31 minutes.".equals(host.status) : ui.model.banner;
        // A key Norish 0.25 refuses may only be in its hour after too many requests, and the panel says that too.
        source.fail = new RecipeSource.SourceException(RecipeSource.SourceException.Kind.AUTH, "Norish refused the API key.", true);
        cook.refresh();
        net.runAll();
        assert ui.model.strings.problemAuthPassing.equals(ui.model.banner) && ui.model.banner.startsWith("Norish refused the API key. If it has worked before, wait up to an hour") : ui.model.banner;
        source.fail = new RecipeSource.SourceException(RecipeSource.SourceException.Kind.AUTH, "Norish refused the API key.");
        cook.refresh();
        net.runAll();
        assert ui.model.strings.problemAuth.equals(ui.model.banner) : ui.model.banner;
        source.fail = null;
        cook.refresh();
        net.runAll();
        assert ui.model.banner == null && !host.statusError;
        cook.openRecipe(new RecipeSource.Summary("7", "Pancakes", "", 30));
        net.runAll();
        cook.startCooking();
        cook.next();
        cook.next();
        net.runAll();
        assert ui.model.page == Model.Page.FINISH && !ui.model.canLogCooked && !ui.model.logged;
        for (String call : source.calls) assert !call.startsWith("cooked") && !call.startsWith("viewed") : call;
        // Tandoor at the same address is another server, where the recipe in hand means nothing.
        Map<String, Object> tandoor = new LinkedHashMap<>(values);
        tandoor.put("recipeApp", "Tandoor");
        cook.configure(tandoor);
        net.runAll();
        assert ui.model.page == Model.Page.HOME && ui.model.recipe == null && !cook.cooking() && "Open in Tandoor".equals(ui.model.strings.openInApp);
        assert host.triggers.contains("cookingFinished");
        cook.close();

        // Cook today's meal goes to the meal that comes next, though Norish's slots have no time of their own.
        String[][] times = {{"7", "30", "1"}, {"12", "30", "2"}, {"13", "59", "2"}, {"18", "30", "3"}, {"23", "30", "3"}};
        for (String[] at : times) {
            fresh(values);
            source.plan = new ArrayList<>(Arrays.asList(
                new RecipeSource.Planned("b", TODAY, "Breakfast", NorishSource.slotOrder("Breakfast"), 0, "1", "Kruh", "", 0, "", 0),
                new RecipeSource.Planned("l", TODAY, "Lunch", NorishSource.slotOrder("Lunch"), 0, "2", "Jota", "", 0, "", 0),
                new RecipeSource.Planned("d", TODAY, "Dinner", NorishSource.slotOrder("Dinner"), 0, "3", "Ričet", "", 0, "", 0)));
            net.runAll();
            clock.now = ZonedDateTime.of(2026, 11, 1, Integer.parseInt(at[0]), Integer.parseInt(at[1]), 0, 0, ZONE).toInstant();
            cook.execute("cookToday");
            net.runAll();
            String asked = "";
            for (String call : source.calls) if (call.startsWith("recipe ")) asked = call;
            assert ("recipe " + at[2]).equals(asked) : at[0] + ":" + at[1] + " asked " + asked;
            cook.close();
        }

        // Cook today's meal from a plan read more than five minutes ago reads it again first: someone may have changed
        // tonight's dinner since. With the panel closed, and with it up on the first page.
        for (boolean up : new boolean[] {false, true}) {
            fresh(values);
            source.plan = new ArrayList<>(Arrays.asList(new RecipeSource.Planned("d", TODAY, "Dinner", NorishSource.slotOrder("Dinner"), 0, "3", "Ričet", "", 0, "", 0)));
            net.runAll();
            if (up) {
                cook.execute("openCookMode");
                net.runAll();
            }
            clock.now = ZonedDateTime.of(2026, 11, 1, 17, 50, 0, 0, ZONE).toInstant();
            source.plan = new ArrayList<>(Arrays.asList(new RecipeSource.Planned("d2", TODAY, "Dinner", NorishSource.slotOrder("Dinner"), 0, "2", "Jota", "", 0, "", 0)));
            int asked = source.calls.size();
            cook.execute("cookToday");
            net.runAll();
            List<String> since = source.calls.subList(asked, source.calls.size());
            assert since.contains("recipe 2") && !since.contains("recipe 3") && since.indexOf("recipe 2") > since.indexOf("plan 2026-11-01 2026-11-07") : up + " " + since;
            assert ui.model.page == Model.Page.RECIPE;
            cook.close();
        }
        // A plan read just now is chosen from at once, with no read of its own.
        fresh(values);
        net.runAll();
        clock.minutes(2);
        int before = source.calls.size();
        cook.execute("cookToday");
        net.runAll();
        List<String> then = source.calls.subList(before, source.calls.size());
        assert then.contains("recipe 7") && then.stream().noneMatch(call -> call.startsWith("plan ")) : then;
        cook.close();

        // An app that needs pauses between plan reads gets them.
        fresh(values);
        net.runAll();
        source.refresh = 65 * 60_000L;
        ui.run(Cook.PLAN_REFRESH_MS);
        net.runAll();
        assert ui.posted(65 * 60_000L) && !ui.posted(Cook.PLAN_REFRESH_MS);
        // But a first page in use is read half-hourly, once for each half hour.
        cook.execute("openCookMode");
        net.runAll();
        int plans = 0;
        for (String call : source.calls) if (call.startsWith("plan ")) plans++;
        clock.minutes(31);
        cook.touched();
        net.runAll();
        clock.minutes(1);
        cook.touched();
        net.runAll();
        int after = 0;
        for (String call : source.calls) if (call.startsWith("plan ")) after++;
        assert after == plans + 1 : plans + " " + after;
        cook.close();

        // Where Norish's plan ends before the days asked for, the first page says so.
        fresh(values);
        net.runAll();
        cook.execute("openCookMode");
        net.runAll();
        assert "".equals(ui.model.planNote);
        source.reach = TODAY.plusDays(2);
        cook.refresh();
        net.runAll();
        assert ui.model.strings.planCut.equals(ui.model.planNote) && "Norish shares only this week and this month of the meal plan.".equals(ui.model.planNote);
        source.reach = TODAY.plusDays(6);
        cook.refresh();
        net.runAll();
        assert "".equals(ui.model.planNote) && TODAY.equals(source.reachFrom) : source.reachFrom;
        // Norish's day is the tablet's or, as it runs by default, UTC's. At 0:30 on the first of December in Ljubljana a UTC
        // Norish is still in November, and from 1:00 on it is not.
        clock.now = ZonedDateTime.of(2026, 12, 1, 0, 30, 0, 0, ZONE).toInstant();
        cook.refresh();
        net.runAll();
        assert LocalDate.of(2026, 11, 30).equals(source.reachFrom) : source.reachFrom;
        clock.now = ZonedDateTime.of(2026, 12, 1, 1, 0, 0, 0, ZONE).toInstant();
        cook.refresh();
        net.runAll();
        assert LocalDate.of(2026, 12, 1).equals(source.reachFrom) : source.reachFrom;
        cook.close();
    }

    static void startAndHome() {
        fresh(settings());
        // Start: entities exist, the screensaver and the stop word are followed, the plan is read for the home page.
        assert "Closed".equals(host.entities.get("view")) && Boolean.FALSE.equals(host.entities.get("cooking"));
        assert host.subscriptions.containsAll(Arrays.asList("screensaver.state", "screensaver.countdown", "stopword.detected"));
        net.runAll();
        assert source.calls.contains("plan 2026-11-01 2026-11-07") : source.calls;
        assert "Lunch: Pancakes; Lunch: Leftovers".equals(host.entities.get("today_meals")) : host.entities.get("today_meals");
        assert host.status.startsWith("Connected. 2 meals planned today. Read at 11:00") && !host.statusError : host.status;

        // Opening: full screen, the screensaver goes, the select follows and the recipe list loads.
        cook.execute("openCookMode");
        OverlaySpec main = host.overlays.get(Cook.MAIN);
        assert main.width() == OverlaySpec.FILL && main.height() == OverlaySpec.FILL && main.closeOnBack() && !main.onTop();
        assert host.count("stopScreensaver") == 1 && "Open".equals(host.entities.get("view"));
        assert ui.model.recipesLoading && ui.model.page == Model.Page.HOME;
        net.runAll();
        assert ui.model.recipes.size() == 2 && ui.model.recipesMore && !ui.model.recipesLoading;
        assert ui.model.days.size() == 1 && ui.model.days.get(0).meals.size() == 2 && "Today".equals(ui.model.days.get(0).label);
        assert "Sunday, November 1".equals(ui.model.date) : ui.model.date;
        // A plan read a moment ago is not read again.
        int plans = planCalls();
        cook.close();
        cook.execute("openCookMode");
        net.runAll();
        assert planCalls() == plans;
        clock.minutes(6);
        cook.close();
        cook.execute("toggleCookMode");
        net.runAll();
        assert planCalls() == plans + 1;

        // Search and order reload the list, and paging appends without doubles.
        cook.search(" soup ");
        net.runAll();
        assert source.calls.contains("recipes soup NAME 1") && "soup".equals(ui.model.query);
        cook.showOrder(RecipeSource.Order.RECENT);
        net.runAll();
        assert source.calls.contains("recipes soup RECENT 1");
        cook.loadMore();
        net.runAll();
        assert source.calls.contains("recipes soup RECENT 2") && ui.model.recipes.size() == 2 && !ui.model.recipesMore;

        // Back closes the view and says so. The select opens and closes it. The screensaver closes the home page.
        cook.onEvent("overlay.closed", event("key", Cook.MAIN));
        assert "Closed".equals(host.entities.get("view"));
        cook.onEvent("select.view", event("option", "Open"));
        assert "Open".equals(host.entities.get("view")) && host.overlays.containsKey(Cook.MAIN);
        cook.onEvent("ks.screensaver.state", event("active", true));
        assert !host.overlays.containsKey(Cook.MAIN) && "Closed".equals(host.entities.get("view"));
        cook.onEvent("ks.screensaver.state", event("active", false));
        boolean refused = false;
        try {
            cook.onEvent("select.view", event("option", "Half"));
        } catch (IllegalArgumentException expected) {
            refused = true;
        }
        assert refused;

        // Test connection reports through the run, off the callback's thread.
        cook.onEvent("settings.button", new HashMap<String, Object>() {{ put("id", "testConnection"); put("run", "r1"); }});
        assert host.setup.isEmpty();
        net.runAll();
        assert host.setup.equals(Arrays.asList("r1 running Connecting to http://tandoor.local:8080", "r1 success Connected to Tandoor 2.7.2, 2 recipes.")) : host.setup;
        // It asks the running source for a twin that knows what it has learned, and makes a new one only when there is none.
        assert source.calls.contains("twin");
        // The twin's own answer is the one reported, and no new source is made.
        Source twin = new Source();
        twin.fail = new RecipeSource.SourceException(RecipeSource.SourceException.Kind.LIMIT, "The plugin waits 42 minutes before it uses this API key again.");
        source.twinSource = twin;
        int made = created;
        cook.onEvent("settings.button", new HashMap<String, Object>() {{ put("id", "testConnection"); put("run", "r2"); }});
        net.runAll();
        assert host.setup.contains("r2 error The plugin waits 42 minutes before it uses this API key again.") && twin.calls.contains("probe") && created == made : host.setup;
        source.twinSource = null;
        cook.stop();
        assert ui.shutdown && source.calls.contains("cancel");
    }

    static int planCalls() {
        int count = 0;
        for (String call : source.calls) if (call.startsWith("plan ")) count++;
        return count;
    }

    static void cookingFlow() {
        fresh(settings());
        net.runAll();
        cook.execute("openCookMode");
        net.runAll();
        // A planned meal opens at the planned servings, its name showing while it loads.
        RecipeSource.Planned planned = ui.model.days.get(0).meals.get(0);
        cook.openPlanned(planned);
        assert ui.model.page == Model.Page.RECIPE && ui.model.recipe == null && "Pancakes".equals(ui.model.recipeName) && ui.model.recipeLoading;
        net.runAll();
        assert ui.model.recipe != null && ui.model.servings == 2 && ui.model.factor == 0.5 && !ui.model.recipeLoading;
        assert source.calls.contains("recipe 7") && source.calls.contains("viewed 7");
        assert ui.model.ingredients.size() == 2 && "100 g flour, sifted".equals(ui.model.ingredients.get(0).line(ui.model.factor, false));
        assert ui.model.resumeAt == 0 && ui.model.pageCount == 2;

        // Servings step by one, by a half below two, and stop at a half.
        cook.changeServings(1);
        assert ui.model.servings == 3 : ui.model.servings;
        cook.changeServings(-1);
        cook.changeServings(-1);
        assert ui.model.servings == 1.5 : ui.model.servings;
        cook.changeServings(-1);
        cook.changeServings(-1);
        assert ui.model.servings == 0.5;
        cook.changeServings(-1);
        assert ui.model.servings == 0.5;
        cook.changeServings(1);
        cook.changeServings(1);
        cook.changeServings(1);
        assert ui.model.servings == 2 : ui.model.servings;

        // Ticks stay until the recipe goes.
        Recipe.Ingredient flour = ui.model.ingredients.get(0);
        cook.toggle(flour);
        assert ui.model.checked.contains(flour);

        // Start cooking: the page, the trigger, the hold, the entities and the projection.
        long version = ui.model.pageVersion;
        cook.startCooking();
        assert ui.model.page == Model.Page.STEP && ui.model.pageVersion > version && cook.cooking();
        assert host.triggers.contains("cookingStarted") && Boolean.TRUE.equals(host.entities.get("cooking")) && "Pancakes".equals(host.entities.get("recipe"));
        assert ui.posted(Awake.BEAT_MS) && ui.posted(90 * 60_000L);
        assert ui.model.pageNumber == 1 && "Dough".equals(ui.model.stepTitle) && ui.model.resumeAt == 1;
        assert ui.model.stepIngredients.size() == 2 && ui.model.checked.contains(flour);
        assert "Ready around 11:30".equals(ui.model.readyAround) : ui.model.readyAround;
        assert ui.model.ownTimer == null && ui.model.runs.size() == 3 && ui.model.runs.get(1).seconds == 300 && "100".equals(ui.model.runs.get(0).text.replaceAll("\\D", ""));
        assert "".equals(ui.model.previousPeek) && "Bake.".equals(ui.model.nextPeek);
        assert ((String) host.entities.get("step")).startsWith("1/2: Mix 100 g flour") : host.entities.get("step");

        // Next: the step's own time shows as a chip because the text does not name it.
        cook.next();
        assert ui.model.pageNumber == 2 && ui.model.ownTimer.seconds == 1500 && ui.model.stepIngredients.isEmpty();
        assert ui.model.previousPeek.startsWith("Mix 100 g flour") && "".equals(ui.model.nextPeek);
        // Leaving for the home page keeps the session, and the same recipe comes back where it was.
        cook.home();
        assert ui.model.page == Model.Page.HOME && ui.model.resumeAt == 2 && ui.model.recipe != null;
        cook.resumeCooking();
        assert ui.model.page == Model.Page.STEP && ui.model.pageNumber == 2;
        cook.home();
        cook.openPlanned(planned);
        assert ui.model.page == Model.Page.STEP && ui.model.pageNumber == 2 && !source.calls.get(source.calls.size() - 1).equals("recipe 7 again");

        // The last Next finishes. Ask offers the log, which goes once and in the servings cooked.
        cook.next();
        assert ui.model.page == Model.Page.FINISH && ui.model.canLogCooked && !ui.model.logged;
        cook.previous();
        assert ui.model.page == Model.Page.STEP;
        cook.next();
        cook.logCooked();
        cook.logCooked();
        net.runAll();
        assert ui.model.logged && !ui.model.canLogCooked && source.calls.contains("cooked 7 2.0");
        int logs = 0;
        for (String call : source.calls) if (call.startsWith("cooked")) logs++;
        assert logs == 1;
        // End cooking closes the view, lets go of the screen and forgets the recipe.
        cook.endCooking();
        assert !cook.cooking() && !host.overlays.containsKey(Cook.MAIN) && !ui.posted(Awake.BEAT_MS);
        assert host.triggers.contains("cookingFinished") && Boolean.FALSE.equals(host.entities.get("cooking")) && "".equals(host.entities.get("recipe"));
        assert ui.model.page == Model.Page.HOME && ui.model.recipe == null && ui.model.resumeAt == 0;

        // Always logs when the last step is done. Never neither logs nor tells the recipe was viewed.
        Map<String, Object> always = settings();
        always.put("markCooked", "Always");
        fresh(always);
        net.runAll();
        cook.execute("openCookMode");
        cook.openRecipe(new RecipeSource.Summary("7", "Pancakes", "", 30));
        net.runAll();
        assert ui.model.servings == 4 : "A recipe from the list opens at its own servings";
        cook.startCooking();
        cook.next();
        cook.next();
        net.runAll();
        assert source.calls.contains("cooked 7 4.0") && !ui.model.canLogCooked;
        Map<String, Object> never = settings();
        never.put("markCooked", "Never");
        fresh(never);
        net.runAll();
        cook.execute("openCookMode");
        cook.openRecipe(new RecipeSource.Summary("7", "Pancakes", "", 30));
        net.runAll();
        assert !source.calls.contains("viewed 7");

        // A long imported step becomes one page per paragraph, and the page count does not move with the servings.
        StringBuilder long1 = new StringBuilder();
        for (int i = 0; i < 3; i++) long1.append("<p>Paragraph ").append(i).append(" with ").append(new String(new char[120]).replace('\0', 'x')).append(" <scalable-number v-bind:number=\"100\"></scalable-number> g.</p>\n");
        Recipe.Step big = new Recipe.Step("", long1.toString(), Recipe.Format.TANDOOR_HTML, Collections.<Recipe.Ingredient>emptyList(), 0);
        Recipe imported = new Recipe("9", "Imported", "", "", 4, "", 0, 0, "", "", Collections.singletonList(big));
        List<Pages.Page> pages = Pages.of(imported, true);
        assert pages.size() == 3 && pages.get(2).part == 2 && pages.get(2).parts == 3;
        assert Pages.of(imported, false).size() == 1;
        assert Pages.text(imported, pages.get(1), 0.5, false).startsWith("Paragraph 1") && Pages.text(imported, pages.get(1), 0.5, false).endsWith("50 g.");
        assert Pages.parts("Short.\n\nSteps stay whole.").size() == 1;
    }

    static void timers() {
        fresh(settings());
        net.runAll();
        cook.execute("openCookMode");
        cook.openRecipe(new RecipeSource.Summary("7", "Pancakes", "", 30));
        net.runAll();
        cook.startCooking();

        // A chip starts a timer named after its step. Its due time is posted, the entities follow.
        int renders = ui.renders;
        long version = ui.model.pageVersion;
        cook.startTimer("5 min", 300);
        assert ui.posted(Cook.DUE_CHECK_MS) && ui.renders > renders && ui.model.pageVersion == version : "A timer must not rebuild the page under a finger";
        // One press that landed twice is one timer: the step's timer starts once, and the view can show how it is doing.
        assert !cook.stepTimer("5 min", 300);
        List<Timers.Timer> list = cook.timers();
        assert list.size() == 1 && "Step 1 · 5 min".equals(list.get(0).label) && "7".equals(list.get(0).recipe) && list.get(0).step == 0;
        assert Double.valueOf(1).equals(host.entities.get("timers"));
        assert "Step 1 · 5 min 11:05".equals(nextTimer(11, 5)) : host.entities.get("next_timer");

        // Pause drops the due time, resume brings it back.
        int id = list.get(0).id;
        cook.pauseTimer(id);
        assert !ui.posted(Cook.DUE_CHECK_MS);
        clock.minutes(1);
        cook.resumeTimer(id);
        assert ui.posted(Cook.DUE_CHECK_MS);
        cook.addToTimer(id, 60);
        // Looking early changes nothing, not even the view.
        clock.minutes(1);
        int looked = ui.renders;
        ui.run(Cook.DUE_CHECK_MS);
        assert !ui.alarm && ui.renders == looked && ui.posted(Cook.DUE_CHECK_MS);
        clock.minutes(5);

        // It rings: sound, the screen, the trigger and the entity. The screensaver goes if it is up.
        int stops = host.count("stopScreensaver");
        ui.run(Cook.DUE_CHECK_MS);
        assert ui.alarm && host.count("stopScreensaver") == stops + 1 && host.triggers.contains("timerDone") && Boolean.TRUE.equals(host.entities.get("timer_ringing"));
        assert cook.timers().get(0).state == Timers.State.RINGING;
        cook.stopAlarm();
        assert !ui.alarm && cook.timers().isEmpty() && Boolean.FALSE.equals(host.entities.get("timer_ringing"));

        // KS's stop word only silences a ringing timer the first time, so a false alarm cannot wipe what ended. The second takes it away.
        cook.startTimer("1 min", 60);
        clock.minutes(1);
        ui.run(Cook.DUE_CHECK_MS);
        assert ui.alarm;
        int silences = ui.silences;
        cook.onEvent("ks.stopword.detected", new HashMap<String, Object>());
        assert !ui.alarm && ui.silences == silences + 1 && cook.timers().get(0).state == Timers.State.RINGING;
        assert Boolean.TRUE.equals(host.entities.get("timer_ringing"));
        cook.onEvent("ks.stopword.detected", new HashMap<String, Object>());
        assert cook.timers().isEmpty() && Boolean.FALSE.equals(host.entities.get("timer_ringing"));

        // With the view closed, the dock shows over everything with the recipe and its timers.
        cook.startTimer("2 min", 120);
        cook.close();
        OverlaySpec dock = host.overlays.get(Cook.DOCK);
        assert dock != null && dock.onTop() && !dock.closeOnBack() && "top-right".equals(dock.anchor());
        assert "Pancakes · 1/2".equals(ui.dock) : ui.dock;
        // A timer ringing with the view closed holds the screen until it stops.
        clock.minutes(2);
        assert !ui.posted(Awake.BEAT_MS);
        ui.run(Cook.DUE_CHECK_MS);
        assert ui.posted(Awake.BEAT_MS);
        cook.stopAlarm();
        assert !ui.posted(Awake.BEAT_MS);
        // The dock stays while cooking goes on. Reopening hides it and keeps the page.
        assert host.overlays.containsKey(Cook.DOCK);
        cook.openFromDock();
        assert !host.overlays.containsKey(Cook.DOCK) && host.overlays.containsKey(Cook.MAIN) && ui.model.page == Model.Page.STEP;

        // At most six timers, and the seventh says so.
        for (int i = 0; i < Timers.MAX; i++) cook.startTimer("t" + i, 1000 + i);
        assert cook.timers().size() == Timers.MAX && ui.model.banner == null;
        cook.startTimer("one too many", 2000);
        assert Strings.ENGLISH.tooManyTimers.equals(ui.model.banner);
        // A timer keeps going after cooking ends, in the dock.
        cook.endCooking();
        assert cook.timers().size() == Timers.MAX && host.overlays.containsKey(Cook.DOCK) && "".equals(ui.dock);
        for (Timers.Timer timer : cook.timers()) cook.cancelTimer(timer.id);
        assert !host.overlays.containsKey(Cook.DOCK);
    }

    static String nextTimer(int hour, int minute) {
        return (String) host.entities.get("next_timer");
    }

    static void screen() {
        fresh(settings());
        net.runAll();
        cook.execute("openCookMode");
        // The home page does not hold the screen. A recipe does.
        assert !ui.posted(Awake.BEAT_MS);
        cook.openRecipe(new RecipeSource.Summary("7", "Pancakes", "", 30));
        assert ui.posted(Awake.BEAT_MS);
        net.runAll();
        cook.startCooking();

        // Each deadline KS names gets a report well before it.
        cook.onEvent("ks.screensaver.countdown", event("due", clock.instant().plusSeconds(60).toString()));
        assert ui.posted(45_000L);
        int postpones = host.count("postponeScreensaver");
        clock.seconds(45);
        ui.run(45_000L);
        assert host.count("postponeScreensaver") == postpones + 1 && ui.posted(Awake.BEAT_MS);

        // A screensaver someone asked for is left alone until it goes, and cooking does not close for it.
        cook.onEvent("ks.screensaver.state", event("active", true));
        assert !ui.posted(Awake.BEAT_MS) && host.overlays.containsKey(Cook.MAIN);
        cook.onEvent("ks.screensaver.countdown", event("due", clock.instant().plusSeconds(60).toString()));
        assert !ui.posted(45_000L);
        cook.onEvent("ks.screensaver.state", event("active", false));
        assert ui.posted(Awake.BEAT_MS);

        // After the idle limit without a touch the hold ends, but the session stays. A touch brings it back.
        clock.minutes(20);
        cook.touched();
        clock.minutes(70);
        ui.run(90 * 60_000L);
        assert ui.posted(20 * 60_000L) : "Twenty more minutes since the touch";
        clock.minutes(20);
        ui.run(20 * 60_000L);
        assert !ui.posted(Awake.BEAT_MS) && cook.cooking() && host.overlays.containsKey(Cook.MAIN);
        cook.touched();
        assert ui.posted(Awake.BEAT_MS);

        // Keep awake off holds nothing.
        Map<String, Object> off = settings();
        off.put("keepAwake", false);
        fresh(off);
        net.runAll();
        cook.execute("openCookMode");
        cook.openRecipe(new RecipeSource.Summary("7", "Pancakes", "", 30));
        net.runAll();
        cook.startCooking();
        assert !ui.posted(Awake.BEAT_MS);

        // A KS page opened from here: the view goes first, the hold stays, and the dock leads back.
        fresh(settings());
        net.runAll();
        cook.execute("openCookMode");
        cook.openRecipe(new RecipeSource.Summary("7", "Pancakes", "", 30));
        net.runAll();
        cook.startCooking();
        cook.openInApp();
        assert !host.overlays.containsKey(Cook.MAIN) && host.commands.contains("showLinkPage") && ui.posted(Awake.BEAT_MS);
        assert "http://tandoor.local:8080/view/recipe/7".equals(host.commandArguments.get(host.commands.indexOf("showLinkPage")).get("url"));
        assert host.overlays.containsKey(Cook.DOCK);
        cook.openFromDock();
        assert host.commands.contains("hideOverlayPage") && host.overlays.containsKey(Cook.MAIN);
    }

    static void voice() {
        // Off: nothing is said and the button is not offered.
        fresh(settings());
        net.runAll();
        cook.execute("openCookMode");
        cook.openRecipe(new RecipeSource.Summary("7", "Pancakes", "", 30));
        net.runAll();
        cook.startCooking();
        assert !ui.model.canRead;
        cook.read();
        assert ui.spoken.isEmpty() && host.haCalls.isEmpty();

        // The tablet's voice reads the step at the chosen servings, and each new step when asked to.
        Map<String, Object> tablet = settings();
        tablet.put("readAloud", "Tablet voice");
        tablet.put("autoRead", true);
        fresh(tablet);
        net.runAll();
        cook.execute("openCookMode");
        cook.openRecipe(new RecipeSource.Summary("7", "Pancakes", "", 30));
        net.runAll();
        cook.read();
        assert ui.spoken.size() == 1 && ui.spoken.get(0).startsWith("200 g flour, sifted. 2 eggs") : ui.spoken;
        cook.startCooking();
        assert ui.spoken.size() == 2 && ui.spoken.get(1).equals("Mix 200 g flour for 5 min.") : ui.spoken;
        cook.execute("nextStep");
        assert ui.spoken.size() == 3 && ui.spoken.get(2).equals("Bake.") : ui.spoken;

        // Home Assistant's voice goes through the kiosk's own announce action, without the card that would cover the step.
        Map<String, Object> assistant = settings();
        assistant.put("readAloud", "Home Assistant");
        fresh(assistant);
        assert host.status.length() > 0;
        net.runAll();
        cook.execute("openCookMode");
        cook.openRecipe(new RecipeSource.Summary("7", "Pancakes", "", 30));
        net.runAll();
        cook.startCooking();
        cook.read();
        Map<String, Object> call = host.haCalls.get(0);
        assert "esphome".equals(call.get("domain")) && "ks_kitchen_announce".equals(call.get("service")) && !call.containsKey("target");
        Map<?, ?> data = (Map<?, ?>) call.get("service_data");
        assert "Mix 200 g flour for 5 min.".equals(data.get("message")) && Boolean.TRUE.equals(data.get("audio_only")) && Boolean.FALSE.equals(data.get("chime"));
        assert data.keySet().equals(new HashSet<>(Arrays.asList("message", "url", "volume", "repeat", "repeat_pause", "chime", "chime_file", "tts_engine", "tts_language", "tts_voice", "audio_only"))) : data.keySet();

        // No such action: the other name a kiosk's node can have, then an honest status.
        host.haAnswers.get(0).onResult(false, null, "not_found: Service esphome.ks_kitchen_announce not found.");
        assert "kitchen_announce".equals(host.haCalls.get(1).get("service"));
        host.haAnswers.get(1).onResult(false, null, "not_found: Service esphome.kitchen_announce not found.");
        assert host.haCalls.size() == 2 && host.statusError && host.status.contains("kitchen");
        // No answer within the limit is no failure: it may still be speaking, and must not be read twice.
        host.status = "";
        cook.read();
        host.haAnswers.get(2).onResult(false, null, "timeout: No answer within 25 seconds");
        assert host.haCalls.size() == 3 && host.status.isEmpty();
        // The node that worked is the one used from then on, and leaving the step stops what is being read.
        host.haAnswers.get(2).onResult(true, null, null);
        cook.read();
        assert "ks_kitchen_announce".equals(host.haCalls.get(3).get("service")) : host.haCalls.get(3);
        cook.next();
        Map<String, Object> stop = host.haCalls.get(4);
        assert "ks_kitchen_announce_stop".equals(stop.get("service")) : stop;
        // Older kiosks have no stop action. That is remembered, and costs nothing.
        host.haAnswers.get(4).onResult(false, null, "not_found: Service esphome.ks_kitchen_announce_stop not found.");
        cook.read();
        cook.previous();
        assert host.haCalls.size() == 6 : host.haCalls.size();

        // A typed node wins, in whichever form it was copied, and the setting can be changed while cooking.
        Map<String, Object> typed = new LinkedHashMap<>(assistant);
        typed.put("kioskNode", "esphome.KS-Letna-Kuhinja_announce");
        cook.configure(typed);
        cook.read();
        assert "ks_letna_kuhinja_announce".equals(host.haCalls.get(host.haCalls.size() - 1).get("service"));
        assert "dedo_babi".equals(Settings.slug("Dedo&babi")) && "otroska_soba".equals(Settings.slug("Otroška soba"));

        // A kiosk named with a letter KS leaves out: KS's own form first, and then the form an older node may have.
        Host accented = new Host();
        accented.deviceName = "Otroška soba";
        FakeUi accentedUi = new FakeUi();
        Net accentedNet = new Net();
        Cook accentedCook = new Cook(accentedUi, clock, accentedNet, settings -> source);
        accentedCook.start(accented, assistant);
        accentedNet.runAll();
        accentedCook.execute("openCookMode");
        accentedNet.runAll();
        accentedCook.openRecipe(new RecipeSource.Summary("7", "Pancakes", "", 30));
        accentedNet.runAll();
        accentedCook.startCooking();
        accentedCook.read();
        List<String> tried = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tried.add((String) accented.haCalls.get(i).get("service"));
            accented.haAnswers.get(i).onResult(false, null, "not_found: Service not found.");
        }
        assert tried.equals(Arrays.asList("ks_otro_ka_soba_announce", "otro_ka_soba_announce", "ks_otroska_soba_announce", "otroska_soba_announce")) : tried;
        assert accented.statusError && accented.status.contains("otroska_soba") : accented.status;
        accentedCook.stop();
    }

    static void commands() {
        fresh(settings());
        net.runAll();
        // Today's next meal: lunch at 12, it is 11, and it opens at the planned servings.
        cook.execute("cookToday");
        net.runAll();
        assert ui.model.page == Model.Page.RECIPE && ui.model.recipe != null && ui.model.servings == 2 && host.overlays.containsKey(Cook.MAIN);

        // On the recipe page Next starts cooking. Steps by command are held off briefly, since a clap can fire twice.
        clock.seconds(2);
        cook.execute("nextStep");
        assert ui.model.page == Model.Page.STEP && ui.model.pageNumber == 1;
        cook.execute("nextStep");
        assert ui.model.pageNumber == 1 : "A second command right after the first does nothing";
        clock.seconds(1);
        cook.execute("nextStep");
        assert ui.model.pageNumber == 2;
        clock.seconds(1);
        cook.execute("previousStep");
        assert ui.model.pageNumber == 1;

        // The step's timer by command: the first duration of the page, and it starts once.
        cook.execute("startStepTimer");
        assert cook.timers().size() == 1 && cook.timers().get(0).total == 300;
        clock.seconds(1);
        cook.execute("startStepTimer");
        assert cook.timers().size() == 1;
        // One more minute goes on the timer that rings next.
        int left = cook.timers().get(0).left;
        cook.execute("addMinute");
        assert cook.timers().get(0).left == left + 60;
        cook.execute("addMinute");
        assert cook.timers().get(0).left == left + 60 : "An echo does not count";

        // A ringing timer takes the first command given without touching, instead of what it asks for. The step stays.
        clock.seconds(400);
        ui.run(Cook.DUE_CHECK_MS);
        assert ui.alarm && cook.timers().get(0).state == Timers.State.RINGING;
        int before = ui.model.pageNumber;
        cook.execute("nextStep");
        assert !ui.alarm && ui.model.pageNumber == before && cook.timers().size() == 1 : "Only silenced";
        clock.seconds(1);
        cook.execute("nextStep");
        assert cook.timers().isEmpty() && ui.model.pageNumber == before : "Then cleared";
        clock.seconds(1);
        cook.execute("nextStep");
        assert ui.model.pageNumber == before + 1 : "Then it moves";
        clock.seconds(1);
        cook.execute("previousStep");
        cook.execute("startStepTimer");
        assert cook.timers().get(0).total == 300;
        // A clap's echo must not silence and clear at once, and one more minute starts a ringing timer again.
        clock.seconds(400);
        ui.run(Cook.DUE_CHECK_MS);
        cook.execute("stopAlarm");
        cook.execute("stopAlarm");
        assert !ui.alarm && cook.timers().size() == 1;
        cook.execute("addMinute");
        assert !ui.alarm && cook.timers().get(0).state == Timers.State.RUNNING && cook.timers().get(0).left == 60;
        cook.execute("closeCookMode");
        assert !host.overlays.containsKey(Cook.MAIN);
        // With the view closed the first step command only brings it back.
        clock.seconds(1);
        cook.execute("nextStep");
        assert host.overlays.containsKey(Cook.MAIN) && ui.model.pageNumber == 1;
        cook.execute("endCooking");
        assert !cook.cooking();
        boolean refused = false;
        try {
            cook.execute("dance");
        } catch (IllegalArgumentException expected) {
            refused = true;
        }
        assert refused;

        // A note on the plan has no recipe: its title becomes the search. Nothing planned falls back to the home page.
        fresh(settings());
        net.runAll();
        cook.execute("openCookMode");
        cook.openPlanned(meal("2", "Leftovers", null, 19 * 60, 0));
        net.runAll();
        assert "Leftovers".equals(ui.model.query) && ui.model.page == Model.Page.HOME && source.calls.contains("recipes Leftovers NAME 1");
        fresh(settings());
        source.plan.clear();
        net.runAll();
        cook.execute("cookToday");
        net.runAll();
        assert ui.model.page == Model.Page.HOME;

        // On the first page Next means today's meal, and Previous nothing. With a recipe open both go back to it.
        fresh(settings());
        net.runAll();
        cook.execute("openCookMode");
        clock.seconds(1);
        cook.execute("previousStep");
        assert ui.model.page == Model.Page.HOME;
        clock.seconds(1);
        cook.execute("nextStep");
        net.runAll();
        assert ui.model.page == Model.Page.RECIPE && ui.model.recipe != null;
        cook.home();
        clock.seconds(1);
        cook.execute("previousStep");
        assert ui.model.page == Model.Page.RECIPE;
    }

    /** Questions before losing a place, and each one's way out. */
    static void questions() {
        fresh(settings());
        net.runAll();
        cook.execute("openCookMode");
        cook.openRecipe(new RecipeSource.Summary("7", "Pancakes", "", 30));
        net.runAll();
        cook.startCooking();

        // On the first step there is nothing to lose. Further on another recipe needs a yes.
        cook.next();
        cook.home();
        assert ui.model.resumeAt == 2;
        cook.openRecipe(new RecipeSource.Summary("8", "Soup", "", 45));
        assert String.format(Strings.ENGLISH.replaceRecipe, "Pancakes", 2).equals(ui.model.confirm) && ui.model.page == Model.Page.HOME && net.queue.isEmpty();
        assert Strings.ENGLISH.replace.equals(ui.model.confirmLabel);
        cook.confirm(false);
        assert ui.model.confirm == null && cook.cooking() && ui.model.resumeAt == 2;
        // The same recipe is just going back to it.
        cook.openRecipe(new RecipeSource.Summary("7", "Pancakes", "", 30));
        assert ui.model.confirm == null && ui.model.page == Model.Page.STEP;
        cook.home();
        cook.openRecipe(new RecipeSource.Summary("8", "Soup", "", 45));
        cook.confirm(true);
        assert ui.model.confirm == null && !cook.cooking() && ui.model.page == Model.Page.RECIPE && !net.queue.isEmpty();
        assert !host.triggers.contains("cookingFinished") : "Not before the new recipe is here";
        net.runAll();
        assert host.triggers.contains("cookingFinished");

        // Ending from the first page asks only when there is something to lose.
        cook.startCooking();
        cook.home();
        cook.askEnd();
        assert ui.model.confirm == null && !cook.cooking();
        cook.openRecipe(new RecipeSource.Summary("7", "Pancakes", "", 30));
        net.runAll();
        cook.startCooking();
        cook.next();
        cook.home();
        cook.askEnd();
        assert String.format(Strings.ENGLISH.endAsk, "Pancakes", 2).equals(ui.model.confirm) && cook.cooking();
        cook.confirm(true);
        assert ui.model.confirm == null && !cook.cooking() && ui.model.page == Model.Page.HOME;

        // New servings with typed numbers in the words get a note, since those stay as written.
        cook.openRecipe(new RecipeSource.Summary("7", "Pancakes", "", 30));
        net.runAll();
        cook.startCooking();
        assert ui.model.fixedNote.isEmpty();
        cook.previous();
        cook.changeServings(1);
        cook.startCooking();
        assert ui.model.fixedNote.isEmpty() : "Mix 200 g flour for 5 min. has only a scaled amount and a time: " + ui.model.fixedNote;
        // Silent sets no sound but keeps everything else.
        Map<String, Object> silent = settings();
        silent.put("alarmTone", "Silent");
        fresh(silent);
        assert ui.tone == Settings.Tone.SILENT;
        cook.execute("openCookMode");
        cook.startTimer("t", 60);
        clock.seconds(60);
        ui.run(Cook.DUE_CHECK_MS);
        assert !ui.alarm && cook.timers().get(0).state == Timers.State.RINGING && Boolean.TRUE.equals(host.entities.get("timer_ringing"));
    }

    /** A restart of the app or the plugin loses neither the place nor the timers. */
    static void restart() {
        fresh(settings());
        net.runAll();
        cook.execute("openCookMode");
        cook.openRecipe(new RecipeSource.Summary("7", "Pancakes", "", 30));
        net.runAll();
        cook.changeServings(1);
        cook.startCooking();
        cook.next();
        cook.startTimer("Sauce", 600);
        cook.startTimer("Rest", 120);
        cook.pauseTimer(cook.timers().stream().filter(timer -> timer.label.contains("Rest")).findFirst().get().id);
        cook.startTimer("Long gone", 60);
        ui.run(Cook.SAVE_AFTER_MS);
        String state = (String) host.saved.get("resumeState");
        assert state != null && state.length() <= Snapshot.MAX_LENGTH && !state.contains("tda_secret") : state;
        Snapshot snapshot = Snapshot.decode(state);
        assert "Kitchen".equals(snapshot.device) && "7".equals(snapshot.recipe) && snapshot.page == 1 && snapshot.servings == 5 && snapshot.timers.size() == 3;
        // Saved only when something changed, however often the view renders.
        host.saved.clear();
        cook.touched();
        cook.toggle(ui.model.ingredients.get(0));
        ui.run(Cook.SAVE_AFTER_MS);
        assert host.saved.isEmpty() : "Ticking an ingredient is not worth a write";

        // Five minutes pass and the kiosk starts again.
        Map<String, Object> values = settings();
        values.put("resumeState", state);
        clock.minutes(5);
        Host again = new Host();
        FakeUi newUi = new FakeUi();
        Net newNet = new Net();
        Cook newCook = new Cook(newUi, clock, newNet, settings -> source);
        newCook.start(again, values);
        assert newCook.timers().size() == 3 && !newCook.cooking();
        assert again.overlays.containsKey(Cook.DOCK) : "The timers show at once, before the recipe has loaded";
        newNet.runAll();
        assert newCook.cooking() && newUi.model.page == Model.Page.STEP && newUi.model.pageNumber == 2 && newUi.model.servings == 5;
        assert "Pancakes · 2/2".equals(newUi.dock) : newUi.dock;
        assert Boolean.TRUE.equals(again.entities.get("cooking"));
        assert again.triggers.isEmpty() : "It did not start again";
        // A paused timer stays paused, a running one keeps its end, and the page comes back on opening.
        Map<String, Timers.State> states = new HashMap<>();
        for (Timers.Timer timer : newCook.timers()) states.put(timer.label, timer.state);
        assert states.get("Step 2 · Rest") == Timers.State.PAUSED && states.get("Step 2 · Sauce") == Timers.State.RUNNING;
        for (Timers.Timer timer : newCook.timers()) if (timer.label.contains("Sauce")) assert timer.left == 300 : timer.left;
        newCook.execute("openCookMode");
        assert newUi.model.page == Model.Page.STEP;

        // What ended during the restart rings now.
        assert newUi.tasks.containsValue(1L) : newUi.tasks.values();
        newUi.run(1L);
        assert newUi.alarm && again.triggers.contains("timerDone");
        for (Timers.Timer timer : newCook.timers()) newCook.cancelTimer(timer.id);
        newCook.stop();

        // Ended too long ago: only mentioned. A session gone cold: not picked up.
        clock.minutes(60);
        Host later = new Host();
        FakeUi laterUi = new FakeUi();
        Cook laterCook = new Cook(laterUi, clock, new Net(), settings -> source);
        Net laterNet = new Net();
        laterCook = new Cook(laterUi, clock, laterNet, settings -> source);
        laterCook.start(later, values);
        assert laterCook.timers().size() == 1 && later.status.contains("2 timers ended") : later.status;
        // And that stays said once the plan has answered.
        laterNet.runAll();
        assert later.status.startsWith("Connected.") && later.status.contains("2 timers ended") : later.status;
        laterCook.stop();
        clock.now = clock.now.plusSeconds(13 * 3600);
        Host cold = new Host();
        Net coldNet = new Net();
        Cook coldCook = new Cook(new FakeUi(), clock, coldNet, settings -> source);
        coldCook.start(cold, values);
        coldNet.runAll();
        assert !coldCook.cooking();
        coldCook.stop();

        // Another kiosk's state, copied by fleet sync, is not ours. The first save replaces it.
        Host other = new Host();
        other.deviceName = "Bedroom";
        FakeUi otherUi = new FakeUi();
        Net otherNet = new Net();
        Cook otherCook = new Cook(otherUi, clock, otherNet, settings -> source);
        otherCook.start(other, values);
        otherNet.runAll();
        assert otherCook.timers().isEmpty() && !otherCook.cooking();
        otherUi.run(Cook.SAVE_AFTER_MS);
        assert "".equals(other.saved.get("resumeState"));
        otherCook.stop();
        assert Snapshot.decode("") == null && Snapshot.decode("not json") == null && Snapshot.decode("{\"v\":99}") == null;
    }

    static Map<String, Object> assistant() {
        Map<String, Object> values = settings();
        values.put("readAloud", "Home Assistant");
        return values;
    }

    /** Open the view and start cooking the usual recipe. */
    static void cooking() {
        net.runAll();
        cook.execute("openCookMode");
        net.runAll();
        cook.openRecipe(new RecipeSource.Summary("7", "Pancakes", "", 30));
        net.runAll();
        cook.startCooking();
    }

    static RecipeSource.SourceException down() {
        return new RecipeSource.SourceException(RecipeSource.SourceException.Kind.NETWORK, "Could not reach Tandoor.");
    }

    /** Each timer gets its own sound, whatever has become of the last one's. */
    static void secondTimer() {
        fresh(settings());
        net.runAll();
        cook.startTimer("Pasta", 60);
        cook.startTimer("Oven", 120);
        clock.seconds(60);
        ui.run(Cook.DUE_CHECK_MS);
        assert ui.alarm && ui.alarmStarts == 1;
        // The first one's sound may have given up by now, which only the sound itself knows, so it is asked to start again.
        clock.seconds(60);
        ui.run(Cook.DUE_CHECK_MS);
        assert ui.alarm && ui.alarmStarts == 2 : ui.alarmStarts;
        // Muted, a new ring sounds again. Nothing else starts the sound a second time.
        cook.execute("stopAlarm");
        assert !ui.alarm;
        cook.startTimer("Sauce", 40);
        assert !ui.alarm && ui.alarmStarts == 2;
        clock.seconds(40);
        ui.run(40_000L);
        assert ui.alarm && ui.alarmStarts == 3;
    }

    /** KS refuses a command with too many pending. Nothing of what a timer must do may depend on it. */
    static void refused() {
        fresh(settings());
        net.runAll();
        cook.startTimer("One", 60);
        cook.startTimer("Two", 120);
        host.refuse = true;
        clock.seconds(60);
        ui.run(Cook.DUE_CHECK_MS);
        assert ui.alarm && host.triggers.contains("timerDone") && Boolean.TRUE.equals(host.entities.get("timer_ringing")) : "The ring must go on";
        assert ui.posted(Cook.DUE_CHECK_MS) : "And the next timer must still ring";
        assert ui.posted(90 * 60_000L) && ui.screenOn : "And the limit on holding the screen must still run";
        ui.run(Awake.BEAT_MS);
        assert ui.posted(Awake.BEAT_MS) : "A beat that was refused does not end the beats";
        host.refuse = false;
        cook.stopAlarm();
        cook.stopAlarm();

        // A view that KS would not show: the card stays, and the next try opens it.
        host.refuseOverlays = true;
        cook.close();
        assert host.overlays.containsKey(Cook.DOCK);
        boolean failed = false;
        try {
            cook.execute("openCookMode");
        } catch (IllegalStateException expected) {
            failed = true;
        }
        assert failed && host.overlays.containsKey(Cook.DOCK) && "Closed".equals(host.entities.get("view"));
        host.refuseOverlays = false;
        cook.execute("openCookMode");
        assert host.overlays.containsKey(Cook.MAIN) && !host.overlays.containsKey(Cook.DOCK);

        // Read aloud and the stop of it, with KS refusing.
        fresh(assistant());
        cooking();
        cook.read();
        host.haAnswers.get(0).onResult(true, null, null);
        host.refuse = true;
        cook.read();
        cook.next();
        assert ui.model.pageNumber == 2 : "The step moves all the same";
        host.refuse = false;

        // Test connection reports what went wrong, in words that cannot hold the token.
        fresh(settings());
        net.runAll();
        source.bug = new IllegalArgumentException("Unexpected char 0x0a in header value: Bearer tda_secret");
        cook.onEvent("settings.button", new HashMap<String, Object>() {{ put("id", "testConnection"); put("run", "r1"); }});
        net.runAll();
        assert host.setup.get(host.setup.size() - 1).startsWith("r1 error") && !host.setup.get(host.setup.size() - 1).contains("tda_secret") : host.setup;
        // And Cancel reaches it.
        int cancels = Collections.frequency(source.calls, "cancel");
        cook.onEvent("settings.cancel", event("run", "r1"));
        assert Collections.frequency(source.calls, "cancel") == cancels + 1;
    }

    /** What a restart has to go through before the session is back. */
    static void restartWaiting() {
        fresh(settings());
        cooking();
        cook.changeServings(1);
        cook.next();
        cook.startTimer("Sauce", 600);
        ui.run(Cook.SAVE_AFTER_MS);
        Map<String, Object> values = settings();
        values.put("resumeState", host.saved.get("resumeState"));
        Snapshot saved = Snapshot.decode((String) host.saved.get("resumeState"));
        assert "Pancakes".equals(saved.name) && saved.page == 1;
        clock.minutes(1);

        // KS cannot say which kiosk this is yet: nothing is restored, and above all nothing saved is touched.
        Host slow = new Host();
        slow.deviceFailures = 2;
        FakeUi slowUi = new FakeUi();
        Net slowNet = new Net();
        Cook slowCook = new Cook(slowUi, clock, slowNet, settings -> source);
        slowCook.start(slow, values);
        slowNet.runAll();
        slowUi.run(Cook.SAVE_AFTER_MS);
        assert slow.saved.isEmpty() && slowCook.timers().isEmpty();
        slowUi.run(Cook.DEVICE_AGAIN_MS);
        assert slowCook.timers().isEmpty();
        slowUi.run(Cook.DEVICE_AGAIN_MS);
        assert slowCook.timers().size() == 1 : "The third try knows";
        slowNet.runAll();
        assert slowCook.cooking();
        slowCook.stop();

        // If it never says, saving stays off rather than writing over what could not be matched.
        Host never = new Host();
        never.deviceFailures = 100;
        FakeUi neverUi = new FakeUi();
        Cook neverCook = new Cook(neverUi, clock, new Net(), settings -> source);
        neverCook.start(never, values);
        for (int i = 1; i < Cook.DEVICE_TRIES; i++) neverUi.run(Cook.DEVICE_AGAIN_MS);
        assert !neverUi.posted(Cook.DEVICE_AGAIN_MS);
        neverUi.run(Cook.SAVE_AFTER_MS);
        assert never.saved.isEmpty();
        neverCook.stop();

        // Saved again while its recipe is still on the way, the session stays in what is saved.
        Host again = new Host();
        FakeUi newUi = new FakeUi();
        Net newNet = new Net();
        Cook newCook = new Cook(newUi, clock, newNet, settings -> source);
        newCook.start(again, values);
        newUi.run(Cook.SAVE_AFTER_MS);
        Snapshot waiting = Snapshot.decode((String) again.saved.get("resumeState"));
        assert "7".equals(waiting.recipe) && waiting.page == 1 && waiting.servings == 5 && waiting.timers.size() == 1 : again.saved;
        // Home Assistant and the card see it already, with its name.
        assert Boolean.TRUE.equals(again.entities.get("cooking")) && "Pancakes".equals(again.entities.get("recipe")) && "Pancakes".equals(newUi.dock);

        // The server is not up yet: asked again every minute, and the place stays.
        source.fail = down();
        newNet.runAll();
        assert !newCook.cooking() && newUi.posted(Cook.RESUME_AGAIN_MS) && newUi.model.resumeAt == 2 && "Pancakes".equals(newUi.model.recipeName);
        newUi.run(Cook.RESUME_AGAIN_MS);
        newNet.runAll();
        assert newUi.posted(Cook.RESUME_AGAIN_MS);
        newUi.run(Cook.SAVE_AFTER_MS);
        assert "7".equals(Snapshot.decode((String) again.saved.get("resumeState")).recipe);
        // A server that answers with an error, which Norish counts against its key, is asked again only now and then.
        source.counts = true;
        source.fail = new RecipeSource.SourceException(RecipeSource.SourceException.Kind.SERVER, "Norish answered HTTP 500: Failed to parse FullRecipeDTO.");
        newUi.run(Cook.RESUME_AGAIN_MS);
        newNet.runAll();
        assert newUi.posted(Cook.RESUME_SLOWLY_MS) && !newUi.posted(Cook.RESUME_AGAIN_MS) && newUi.model.resumeAt == 2;
        // One that answers but is not ready yet, which costs the key nothing, is asked again every minute.
        source.fail = new RecipeSource.SourceException(RecipeSource.SourceException.Kind.SERVER, "Norish is not ready: Database is error. That, and not the key, is why it refused it.", true);
        newUi.run(Cook.RESUME_SLOWLY_MS);
        newNet.runAll();
        assert newUi.posted(Cook.RESUME_AGAIN_MS) && !newUi.posted(Cook.RESUME_SLOWLY_MS);
        // An app that counts nothing, such as Tandoor, is asked again every minute, whatever it answered.
        source.counts = false;
        source.fail = new RecipeSource.SourceException(RecipeSource.SourceException.Kind.SERVER, "Tandoor answered HTTP 500.");
        newUi.run(Cook.RESUME_AGAIN_MS);
        newNet.runAll();
        assert newUi.posted(Cook.RESUME_AGAIN_MS) && !newUi.posted(Cook.RESUME_SLOWLY_MS);
        source.fail = down();

        // Opened meanwhile: a card on the home page, and the same recipe leads back instead of starting over.
        newCook.execute("openCookMode");
        newNet.runAll();
        newCook.home();
        assert newUi.model.page == Model.Page.HOME && newUi.model.resumeAt == 2;
        newCook.openRecipe(new RecipeSource.Summary("7", "Pancakes", "", 30));
        assert newUi.model.resumeAt == 2 && newUi.model.page == Model.Page.RECIPE;
        newNet.runAll();
        // Another recipe needs a yes, and so does ending it.
        newCook.home();
        newCook.openRecipe(new RecipeSource.Summary("8", "Soup", "", 45));
        assert String.format(Strings.ENGLISH.replaceRecipe, "Pancakes", 2).equals(newUi.model.confirm);
        newCook.confirm(false);
        newCook.askEnd();
        assert String.format(Strings.ENGLISH.endAsk, "Pancakes", 2).equals(newUi.model.confirm);
        newCook.confirm(false);
        // The server is back, and the cook is somewhere else: nobody is pulled off the page they are on.
        source.fail = null;
        newCook.refresh();
        newNet.runAll();
        assert newCook.cooking() && newUi.model.page == Model.Page.HOME && newUi.model.resumeAt == 2 && !newUi.posted(Cook.RESUME_AGAIN_MS) : newUi.model.page;
        assert newUi.screenOn == false;
        newCook.resumeCooking();
        assert newUi.model.page == Model.Page.STEP && newUi.model.pageNumber == 2 && newUi.model.servings == 5 && newUi.screenOn;
        newCook.stop();

        // Replacing a session that waits: it comes back if the other recipe cannot be read either, and the place stays saved.
        Map<String, Object> aside = settings();
        aside.put("resumeState", new Snapshot("Kitchen", clock.millis(), "7", "Pancakes", 5, 1, clock.millis(), Collections.<Snapshot.SavedTimer>emptyList()).encode());
        Host asideHost = new Host();
        FakeUi asideUi = new FakeUi();
        Net asideNet = new Net();
        source.fail = down();
        Cook asideCook = new Cook(asideUi, clock, asideNet, settings -> source);
        asideCook.start(asideHost, aside);
        asideCook.execute("openCookMode");
        asideNet.runAll();
        asideCook.home();
        asideCook.openRecipe(new RecipeSource.Summary("8", "Soup", "", 45));
        asideCook.confirm(true);
        assert asideUi.model.page == Model.Page.RECIPE && "Soup".equals(asideUi.model.recipeName) && asideUi.model.resumeAt == 0 && !asideUi.posted(Cook.RESUME_AGAIN_MS);
        assert Boolean.TRUE.equals(asideHost.entities.get("cooking")) && "Pancakes".equals(asideHost.entities.get("recipe")) : "It still counts until the other one comes";
        asideUi.run(Cook.SAVE_AFTER_MS);
        assert "7".equals(Snapshot.decode((String) asideHost.saved.get("resumeState")).recipe);
        asideNet.runAll();
        assert "Pancakes".equals(asideUi.model.recipeName) && asideUi.model.resumeAt == 2 && asideUi.posted(Cook.RESUME_AGAIN_MS) && !asideHost.triggers.contains("cookingFinished");
        assert Strings.ENGLISH.problemNetwork.equals(asideUi.model.banner);
        // Changing one's mind before the other has come: its answer changes nothing.
        asideCook.home();
        asideCook.openRecipe(new RecipeSource.Summary("8", "Soup", "", 45));
        asideCook.confirm(true);
        Runnable lateSoup = asideNet.queue.remove(0);
        asideCook.openRecipe(new RecipeSource.Summary("7", "Pancakes", "", 30));
        assert asideUi.model.resumeAt == 2 && "Pancakes".equals(asideUi.model.recipeName) && asideNet.queue.size() >= 1;
        source.fail = null;
        lateSoup.run();
        assert "Pancakes".equals(asideUi.model.recipeName) && !asideCook.cooking();
        // The server is back: its own recipe comes, at its step.
        asideNet.runAll();
        assert asideCook.cooking() && asideUi.model.page == Model.Page.STEP && asideUi.model.pageNumber == 2 && !asideHost.triggers.contains("cookingFinished");
        asideCook.stop();
        // When the other one does come, the session is over, and that is said.
        Host replacedHost = new Host();
        FakeUi replacedUi = new FakeUi();
        Net replacedNet = new Net();
        source.fail = down();
        Cook replacedCook = new Cook(replacedUi, clock, replacedNet, settings -> source);
        replacedCook.start(replacedHost, aside);
        replacedCook.execute("openCookMode");
        replacedNet.runAll();
        replacedCook.home();
        replacedCook.openRecipe(new RecipeSource.Summary("8", "Soup", "", 45));
        replacedCook.confirm(true);
        source.fail = null;
        replacedNet.runAll();
        assert replacedHost.triggers.contains("cookingFinished") && !replacedCook.cooking() && replacedUi.model.resumeAt == 0 && Boolean.FALSE.equals(replacedHost.entities.get("cooking"));
        replacedCook.stop();

        // Ending one that waits is an end like any other.
        Host endedHost = new Host();
        FakeUi endedUi = new FakeUi();
        Net endedNet = new Net();
        source.fail = down();
        Cook endedCook = new Cook(endedUi, clock, endedNet, settings -> source);
        endedCook.start(endedHost, aside);
        endedNet.runAll();
        assert Boolean.TRUE.equals(endedHost.entities.get("cooking"));
        endedCook.execute("endCooking");
        assert endedHost.triggers.contains("cookingFinished") && Boolean.FALSE.equals(endedHost.entities.get("cooking")) && !endedHost.overlays.containsKey(Cook.DOCK);
        endedCook.stop();
        source.fail = null;

        // The recipe lost its steps during the restart: nothing to go back to, and no card that says otherwise.
        Recipe noSteps = new Recipe("7", "Pancakes", "", "", 4, "", 0, 0, "", "", Collections.<Recipe.Step>emptyList());
        Recipe usual = source.recipe;
        source.recipe = noSteps;
        Host stepsHost = new Host();
        FakeUi stepsUi = new FakeUi();
        Net stepsNet = new Net();
        Cook stepsCook = new Cook(stepsUi, clock, stepsNet, settings -> source);
        stepsCook.start(stepsHost, aside);
        assert stepsHost.overlays.containsKey(Cook.DOCK);
        stepsNet.runAll();
        assert !stepsCook.cooking() && !stepsHost.overlays.containsKey(Cook.DOCK) && stepsHost.triggers.contains("cookingFinished");
        stepsCook.stop();
        source.recipe = usual;

        // A recipe gone from the server: nothing to wait for, and no dock left for it.
        source.fail = new RecipeSource.SourceException(RecipeSource.SourceException.Kind.NOT_FOUND, "Tandoor has nothing at /api/recipe/7/.");
        Map<String, Object> gone = settings();
        gone.put("resumeState", new Snapshot("Kitchen", clock.millis(), "7", "Pancakes", 4, 1, clock.millis(), Collections.<Snapshot.SavedTimer>emptyList()).encode());
        Host goneHost = new Host();
        FakeUi goneUi = new FakeUi();
        Net goneNet = new Net();
        Cook goneCook = new Cook(goneUi, clock, goneNet, settings -> source);
        goneCook.start(goneHost, gone);
        assert goneHost.overlays.containsKey(Cook.DOCK);
        goneNet.runAll();
        assert !goneCook.cooking() && goneUi.model.resumeAt == 0 && !goneHost.overlays.containsKey(Cook.DOCK) && goneUi.model.page == Model.Page.HOME && !goneUi.posted(Cook.RESUME_AGAIN_MS);
        assert Strings.ENGLISH.recipeMissing.equals(goneUi.model.banner) && goneHost.triggers.contains("cookingFinished");
        goneCook.stop();
        source.fail = null;

        // A page that was edited to make no sense, which only a hand can do, does not wedge the panel.
        Map<String, Object> edited = settings();
        edited.put("resumeState", new Snapshot("Kitchen", clock.millis(), "7", "Pancakes", 4, -1, clock.millis(), Collections.<Snapshot.SavedTimer>emptyList()).encode());
        FakeUi editedUi = new FakeUi();
        Net editedNet = new Net();
        Cook editedCook = new Cook(editedUi, clock, editedNet, settings -> source);
        editedCook.start(new Host(), edited);
        editedNet.runAll();
        assert editedCook.cooking() && editedUi.model.pageNumber == 1;
        editedCook.stop();

        // A recipe opened before KS answered is the one that counts.
        Host late = new Host();
        late.deviceFailures = 1;
        FakeUi lateUi = new FakeUi();
        Net lateNet = new Net();
        Cook lateCook = new Cook(lateUi, clock, lateNet, settings -> source);
        lateCook.start(late, values);
        lateCook.execute("openCookMode");
        lateNet.runAll();
        lateCook.openRecipe(new RecipeSource.Summary("8", "Soup", "", 45));
        lateNet.runAll();
        lateCook.startCooking();
        lateUi.run(Cook.DEVICE_AGAIN_MS);
        lateNet.runAll();
        assert lateCook.timers().size() == 1 && lateUi.model.pageNumber == 1 : "Only the timers come back";
        lateCook.stop();
    }

    /** A problem stands over the page only where it matters, and goes when what raised it works again. */
    static void banners() {
        fresh(settings());
        cooking();
        // A blip in the plan's background read is no reason to shout over a step.
        source.fail = down();
        ui.run(Cook.PLAN_REFRESH_MS);
        net.runAll();
        assert ui.model.banner == null && host.statusError : ui.model.banner;
        cook.next();
        assert ui.model.banner == null;
        source.fail = null;
        cook.previous();

        // Raised on the first page, it stays there, whichever way the cook goes on from it.
        cook.home();
        source.fail = down();
        ui.run(Cook.PLAN_REFRESH_MS);
        net.runAll();
        assert Strings.ENGLISH.problemNetwork.equals(ui.model.banner);
        cook.resumeCooking();
        assert ui.model.page == Model.Page.STEP && ui.model.banner == null;
        cook.home();
        cook.search("soup");
        net.runAll();
        cook.resumeCooking();
        assert ui.model.banner == null;
        // A recipe's own trouble does show on its page.
        source.fail = null;
        cook.home();
        net.runAll();

        // On the home page it does say so, and a plan that reads fine says nothing about a search that failed.
        cook.home();
        source.fail = down();
        cook.search("soup");
        net.runAll();
        assert Strings.ENGLISH.problemNetwork.equals(ui.model.banner);
        source.fail = null;
        ui.run(Cook.PLAN_REFRESH_MS);
        net.runAll();
        assert Strings.ENGLISH.problemNetwork.equals(ui.model.banner) : "The search has still not been answered";
        cook.refresh();
        net.runAll();
        assert ui.model.banner == null;

        // All the timers taken: said once, and gone as soon as there is room.
        for (int i = 0; i <= Timers.MAX; i++) cook.startTimer("t" + i, 1000 + i);
        assert Strings.ENGLISH.tooManyTimers.equals(ui.model.banner);
        cook.cancelTimer(cook.timers().get(0).id);
        assert ui.model.banner == null;

        // Retry on the last page tries the record again, also where the setting offers no button.
        Map<String, Object> always = settings();
        always.put("markCooked", "Always");
        fresh(always);
        cooking();
        source.fail = down();
        cook.next();
        cook.next();
        net.runAll();
        assert ui.model.page == Model.Page.FINISH && !ui.model.logged && ui.model.canLogCooked && Strings.ENGLISH.problemNetwork.equals(ui.model.banner);
        source.fail = null;
        cook.refresh();
        net.runAll();
        assert ui.model.logged && !ui.model.canLogCooked && ui.model.banner == null;
    }

    /** With the view up under a screensaver nothing may happen that nobody can see. */
    static void covered() {
        fresh(settings());
        cooking();
        int stops = host.count("stopScreensaver");
        cook.onEvent("ks.screensaver.state", event("active", true));
        clock.seconds(1);
        cook.execute("nextStep");
        assert ui.model.pageNumber == 1 && host.count("stopScreensaver") == stops + 1 : "It only comes back";
        cook.onEvent("ks.screensaver.state", event("active", false));
        clock.seconds(1);
        cook.execute("nextStep");
        assert ui.model.pageNumber == 2;

        // Open wakes it, whatever the hold is doing.
        cook.onEvent("ks.screensaver.state", event("active", true));
        cook.execute("openCookMode");
        assert host.count("stopScreensaver") == stops + 2;
        // Toggle shows what nobody can see, rather than closing it. An echo of it does nothing.
        cook.onEvent("ks.screensaver.state", event("active", true));
        clock.seconds(1);
        cook.execute("toggleCookMode");
        assert host.overlays.containsKey(Cook.MAIN) && host.count("stopScreensaver") == stops + 3;
        cook.onEvent("ks.screensaver.state", event("active", false));
        cook.execute("toggleCookMode");
        assert host.overlays.containsKey(Cook.MAIN) : "The echo of a toggle is no second toggle";
        clock.seconds(1);
        cook.execute("toggleCookMode");
        assert !host.overlays.containsKey(Cook.MAIN);
        clock.seconds(1);
        cook.execute("toggleCookMode");
        assert host.overlays.containsKey(Cook.MAIN);
        // Cook today's meal wakes it too, with the recipe up already.
        cook.onEvent("ks.screensaver.state", event("active", true));
        int now = host.count("stopScreensaver");
        clock.seconds(1);
        cook.execute("cookToday");
        assert host.count("stopScreensaver") == now + 1;

        // The same under a screensaver that came because the hold let go at its limit.
        fresh(settings());
        cooking();
        clock.minutes(90);
        ui.run(90 * 60_000L);
        cook.onEvent("ks.screensaver.state", event("active", true));
        clock.seconds(5);
        stops = host.count("stopScreensaver");
        cook.execute("nextStep");
        assert ui.model.pageNumber == 1 && host.count("stopScreensaver") > stops : "It only comes back";
        cook.onEvent("ks.screensaver.state", event("active", false));
        clock.seconds(1);
        cook.execute("nextStep");
        assert ui.model.pageNumber == 2 && ui.screenOn : "And then it holds again";
        fresh(settings());
        cooking();
        clock.minutes(90);
        ui.run(90 * 60_000L);
        cook.onEvent("ks.screensaver.state", event("active", true));
        clock.seconds(5);
        cook.execute("toggleCookMode");
        assert host.overlays.containsKey(Cook.MAIN) : "Toggle shows what nobody can see";
        // Closing it at night from an automation wakes nothing.
        fresh(settings());
        cooking();
        clock.minutes(90);
        ui.run(90 * 60_000L);
        cook.onEvent("ks.screensaver.state", event("active", true));
        clock.seconds(5);
        stops = host.count("stopScreensaver");
        cook.execute("closeCookMode");
        assert host.count("stopScreensaver") == stops && !host.overlays.containsKey(Cook.MAIN);
    }

    /** Nothing holds the screen for ever, however it came to be held. */
    static void resting() {
        fresh(settings());
        net.runAll();
        cook.startTimer("t", 60);
        clock.seconds(60);
        ui.run(Cook.DUE_CHECK_MS);
        assert ui.posted(Awake.BEAT_MS) && ui.screenOn && ui.posted(90 * 60_000L);

        // Nobody came. The timer stays on show, and the screen may rest.
        clock.minutes(90);
        ui.run(90 * 60_000L);
        assert !ui.posted(Awake.BEAT_MS) && !ui.screenOn && cook.timers().get(0).state == Timers.State.RINGING;
        // A touch holds it again, and the limit is running again too.
        cook.touched();
        assert ui.posted(Awake.BEAT_MS) && ui.screenOn && ui.posted(90 * 60_000L);
        cook.stopAlarm();
        assert !ui.posted(Awake.BEAT_MS) && !ui.posted(90 * 60_000L);

        // The hold from a recipe left past its limit during a ring: the limit still comes after the next touch.
        fresh(settings());
        cooking();
        cook.startTimer("t", 60);
        clock.seconds(60);
        ui.run(Cook.DUE_CHECK_MS);
        clock.minutes(100);
        // Posted for the ring's own start, and nothing touched since.
        ui.run(90 * 60_000L);
        assert !ui.screenOn;
        cook.touched();
        cook.stopAlarm();
        assert ui.screenOn && ui.posted(90 * 60_000L) : "A forgotten recipe must still let go";
        clock.minutes(90);
        ui.run(90 * 60_000L);
        assert !ui.screenOn && !ui.posted(Awake.BEAT_MS);
    }

    /** A timer the words name is the same timer whichever way it was started. */
    static void stepTimers() {
        fresh(settings());
        Recipe.Step tenMinutes = new Recipe.Step("", "Kuhaj 10 minut.", Recipe.Format.PLAIN, Collections.<Recipe.Ingredient>emptyList(), 600);
        StringBuilder words = new StringBuilder();
        for (int i = 0; i < 40; i++) words.append("Mešaj dolgo. ");
        Recipe.Step split = new Recipe.Step("", "<p>" + words + "</p><p>Počakaj 5 minut.</p>", Recipe.Format.TANDOOR_HTML, Collections.<Recipe.Ingredient>emptyList(), 1800);
        source.recipe = new Recipe("7", "Golaž", "", "", 4, "", 0, 0, "", "", Arrays.asList(tenMinutes, split));
        cooking();
        // The chip in the words, then the command. Its own time and the words' are one timer, whichever words they use.
        assert cook.stepTimer("10 minut", 600);
        clock.seconds(1);
        cook.execute("startStepTimer");
        assert cook.timers().size() == 1 : cook.timers().size();
        assert !cook.stepTimer("10 min", 600);
        cook.cancelTimer(cook.timers().get(0).id);
        clock.seconds(1);
        cook.execute("startStepTimer");
        assert cook.timers().size() == 1 && !cook.stepTimer("10 minut", 600);
        cook.cancelTimer(cook.timers().get(0).id);
        // The same length on another step is another timer.
        assert cook.stepTimer("10 minut", 600);
        cook.next();
        assert cook.stepTimer("10 minut", 600) && cook.timers().size() == 2;
        for (Timers.Timer timer : cook.timers()) cook.cancelTimer(timer.id);

        // The later page of a split step has only what it says on it.
        cook.next();
        assert ui.model.pageNumber == 3 && ui.model.ownTimer == null;
        clock.seconds(1);
        cook.execute("startStepTimer");
        assert cook.timers().size() == 1 && cook.timers().get(0).total == 300 : cook.timers().get(0).total;
        // The long step's text does not go to Home Assistant whole.
        cook.previous();
        assert ((String) host.entities.get("step")).length() == Cook.STATE_MOST;
    }

    /** The last page is the end of cooking for anyone who just closes it. */
    static void finishing() {
        fresh(settings());
        cooking();
        cook.next();
        cook.next();
        assert ui.model.page == Model.Page.FINISH && cook.cooking();
        cook.close();
        assert !cook.cooking() && host.triggers.contains("cookingFinished") && Boolean.FALSE.equals(host.entities.get("cooking")) && "".equals(host.entities.get("recipe"));
        assert !host.overlays.containsKey(Cook.DOCK) && ui.model.page == Model.Page.HOME : "No card is left for a meal that is ready";
        cook.execute("openCookMode");
        assert ui.model.page == Model.Page.HOME && ui.model.resumeAt == 0;

        // A timer's card stays, as it does after End cooking.
        fresh(settings());
        cooking();
        cook.startTimer("Coffee", 600);
        cook.next();
        cook.next();
        cook.onEvent("overlay.closed", event("key", Cook.MAIN));
        assert !cook.cooking() && host.overlays.containsKey(Cook.DOCK) && "".equals(ui.dock);

        // An answer about a meal that has gone says nothing about the next.
        fresh(settings());
        cooking();
        cook.next();
        cook.next();
        cook.logCooked();
        Runnable late = net.queue.remove(0);
        cook.backToPlan();
        assert !ui.model.logged;
        source.recipe = recipe();
        cook.openRecipe(new RecipeSource.Summary("8", "Soup", "", 45));
        net.runAll();
        cook.startCooking();
        cook.next();
        cook.next();
        late.run();
        assert !ui.model.logged && ui.model.canLogCooked : "Soup has not been recorded";
        source.fail = down();
        cook.logCooked();
        net.runAll();
        assert Strings.ENGLISH.problemNetwork.equals(ui.model.banner);
        source.fail = null;
        cook.logCooked();
        net.runAll();
        assert ui.model.logged && ui.model.banner == null;
    }

    /** Another recipe replaces the one being cooked only once it is here. */
    static void switching() {
        fresh(settings());
        cooking();
        Recipe.Ingredient flour = ui.model.ingredients.get(0);
        cook.toggle(flour);
        cook.changeServings(1);
        cook.next();
        cook.home();
        source.fail = down();
        cook.openRecipe(new RecipeSource.Summary("8", "Soup", "", 45));
        cook.confirm(true);
        assert ui.model.recipe == null && ui.model.recipeLoading && "Soup".equals(ui.model.recipeName);
        assert Boolean.TRUE.equals(host.entities.get("cooking")) && "Pancakes".equals(host.entities.get("recipe")) : "It may yet come back";
        ui.run(Cook.SAVE_AFTER_MS);
        assert "7".equals(Snapshot.decode((String) host.saved.get("resumeState")).recipe);
        net.runAll();
        assert cook.cooking() && ui.model.page == Model.Page.STEP && ui.model.pageNumber == 2 && "Pancakes".equals(ui.model.recipeName) && ui.model.servings == 5 && ui.model.checked.contains(flour);
        assert !host.triggers.contains("cookingFinished") && Strings.ENGLISH.problemNetwork.equals(ui.model.banner);
        cook.home();
        assert ui.model.resumeAt == 2;

        // Changing one's mind before the other has come: nothing was lost, and its answer changes nothing.
        cook.openRecipe(new RecipeSource.Summary("8", "Soup", "", 45));
        cook.confirm(true);
        Runnable late = net.queue.remove(0);
        cook.home();
        assert ui.model.resumeAt == 0;
        cook.openRecipe(new RecipeSource.Summary("7", "Pancakes", "", 30));
        assert cook.cooking() && ui.model.page == Model.Page.STEP && ui.model.pageNumber == 2 && ui.model.confirm == null;
        source.fail = null;
        late.run();
        assert "Pancakes".equals(ui.model.recipeName) && !host.triggers.contains("cookingFinished");
        cook.home();

        // With the server back it goes through, and only now is the first one finished.
        source.fail = null;
        source.recipe = new Recipe("8", "Soup", "", "", 2, "", 5, 0, "", "", Collections.<Recipe.Step>emptyList());
        cook.openRecipe(new RecipeSource.Summary("8", "Soup", "", 45));
        cook.confirm(true);
        assert !host.triggers.contains("cookingFinished");
        net.runAll();
        assert !cook.cooking() && host.triggers.contains("cookingFinished") && "Soup".equals(ui.model.recipeName) && ui.model.page == Model.Page.RECIPE;
        assert Boolean.FALSE.equals(host.entities.get("cooking")) && "".equals(host.entities.get("recipe")) : "Home Assistant hears at once";
    }

    static void settingsChange() {
        // A new token: the same server, so the recipe stays, and what was on its way is asked for again without a word.
        fresh(settings());
        net.runAll();
        cook.execute("openCookMode");
        net.runAll();
        cook.openRecipe(new RecipeSource.Summary("7", "Pancakes", "", 30));
        Map<String, Object> token = settings();
        token.put("apiToken", "tda_new");
        cook.configure(token);
        assert source.calls.contains("cancel") && ui.model.recipeLoading;
        net.runAll();
        assert ui.model.recipe != null && ui.model.banner == null;
        cook.startCooking();
        cook.configure(settings());
        assert cook.cooking() && ui.model.recipe != null;
        net.runAll();

        // Another server: the recipe means nothing there, and the list reads from the new one at once.
        int lists = 0;
        for (String call : source.calls) if (call.startsWith("recipes")) lists++;
        Map<String, Object> other = settings();
        other.put("serverUrl", "http://other:80");
        cook.configure(other);
        assert !cook.cooking() && ui.model.page == Model.Page.HOME && ui.model.recipe == null && host.triggers.contains("cookingFinished");
        assert ui.dock.isEmpty() && !host.overlays.containsKey(Cook.DOCK) : ui.dock;
        net.runAll();
        int after = 0;
        for (String call : source.calls) if (call.startsWith("recipes")) after++;
        assert after == lists + 1 : after;

        // The card moves as soon as the setting does.
        cook.startTimer("t", 600);
        cook.close();
        assert "top-right".equals(host.overlays.get(Cook.DOCK).anchor()) && host.overlays.get(Cook.DOCK).onTop();
        Map<String, Object> moved = new LinkedHashMap<>(other);
        moved.put("dockCorner", "Bottom left");
        moved.put("dockOnTop", false);
        cook.configure(moved);
        assert "bottom-left".equals(host.overlays.get(Cook.DOCK).anchor()) && !host.overlays.get(Cook.DOCK).onTop();

        // The card follows the session when the server changes with the panel closed, and when its pages do.
        fresh(settings());
        cooking();
        cook.next();
        cook.close();
        assert "Pancakes · 2/2".equals(ui.dock) && host.overlays.containsKey(Cook.DOCK) : ui.dock;
        Map<String, Object> elsewhere = settings();
        elsewhere.put("serverUrl", "http://other:80");
        cook.configure(elsewhere);
        assert !host.overlays.containsKey(Cook.DOCK) && ui.dock.isEmpty();
    }

    /** A tablet with no battery can start with its clock behind, and a timer must still ring once it knows the time. */
    static void clockBehind() {
        fresh(settings());
        net.runAll();
        cook.startTimer("Pasta", 600);
        ui.run(Cook.SAVE_AFTER_MS);
        Map<String, Object> values = settings();
        values.put("resumeState", host.saved.get("resumeState"));
        cook.stop();
        clock.now = clock.now.minusSeconds(2 * 3600);
        Host again = new Host();
        FakeUi againUi = new FakeUi();
        Cook againCook = new Cook(againUi, clock, new Net(), settings -> source);
        againCook.start(again, values);
        assert againCook.timers().get(0).left == 2 * 3600 + 600 : againCook.timers().get(0).left;
        clock.now = clock.now.plusSeconds(2 * 3600 + 601);
        againUi.run(Cook.DUE_CHECK_MS);
        assert againUi.alarm && again.triggers.contains("timerDone") && Boolean.TRUE.equals(again.entities.get("timer_ringing"));
        againCook.stop();

        // The power went at 11:00 and came back at 20:00, with the clock a year behind. It ended at 14:00, so it is only mentioned.
        final long year = 365 * 24 * 3600L;
        fresh(settings());
        net.runAll();
        java.time.Instant cut = clock.now;
        cook.startTimer("Braise", 3 * 3600);
        ui.run(Cook.SAVE_AFTER_MS);
        values = settings();
        values.put("resumeState", host.saved.get("resumeState"));
        cook.stop();
        java.time.Instant back = cut.plusSeconds(9 * 3600);
        clock.now = back.minusSeconds(year);
        again = new Host();
        againUi = new FakeUi();
        againUi.elapsed = 5_000;
        againCook = new Cook(againUi, clock, new Net(), settings -> source);
        againCook.start(again, values);
        assert againCook.timers().size() == 1 && againCook.timers().get(0).state == Timers.State.RUNNING;
        // Still behind, it is saved with the end it has, and a second restart changes nothing about that.
        againUi.run(Cook.SAVE_AFTER_MS);
        values = settings();
        values.put("resumeState", again.saved.get("resumeState"));
        againCook.stop();
        again = new Host();
        againUi = new FakeUi();
        againUi.elapsed = 9_000;
        againCook = new Cook(againUi, clock, new Net(), settings -> source);
        againCook.start(again, values);
        clock.now = back.plusSeconds(60);
        againUi.elapsed += 60_000;
        againUi.run(Cook.DUE_CHECK_MS);
        assert againCook.timers().isEmpty() && !againUi.alarm && !again.triggers.contains("timerDone") : againCook.timers().size();
        assert again.status.contains("1 timer ended long ago") : again.status;
        againCook.stop();

        // It ended five minutes before the clock was right: that still rings.
        fresh(settings());
        net.runAll();
        cut = clock.now;
        cook.startTimer("Braise", 3600);
        ui.run(Cook.SAVE_AFTER_MS);
        values = settings();
        values.put("resumeState", host.saved.get("resumeState"));
        cook.stop();
        clock.now = cut.plusSeconds(3600 + 300).minusSeconds(year);
        again = new Host();
        againUi = new FakeUi();
        againUi.elapsed = 5_000;
        againCook = new Cook(againUi, clock, new Net(), settings -> source);
        againCook.start(again, values);
        clock.now = cut.plusSeconds(3600 + 300);
        againUi.run(Cook.DUE_CHECK_MS);
        assert againUi.alarm && again.triggers.contains("timerDone");
        againCook.stop();

        // It has an hour left when the clock is right: that long, and no longer.
        fresh(settings());
        net.runAll();
        cut = clock.now;
        cook.startTimer("Braise", 2 * 3600);
        ui.run(Cook.SAVE_AFTER_MS);
        values = settings();
        values.put("resumeState", host.saved.get("resumeState"));
        cook.stop();
        clock.now = cut.plusSeconds(3600).minusSeconds(year);
        again = new Host();
        againUi = new FakeUi();
        againUi.elapsed = 5_000;
        againCook = new Cook(againUi, clock, new Net(), settings -> source);
        againCook.start(again, values);
        clock.now = cut.plusSeconds(3600);
        againUi.run(Cook.DUE_CHECK_MS);
        assert !againUi.alarm && againCook.timers().get(0).left == 3600 : againCook.timers().get(0).left;
        againUi.elapsed += 3600_000;
        clock.now = clock.now.plusSeconds(3600);
        againUi.run(Cook.DUE_CHECK_MS);
        assert againUi.alarm;
        againCook.stop();

        // A timer started while the clock is behind counts what it was given when the clock is set.
        fresh(settings());
        net.runAll();
        ui.elapsed = 5_000;
        java.time.Instant right = clock.now;
        clock.now = right.minusSeconds(year);
        cook.startTimer("Pasta", 600);
        clock.now = clock.now.plusSeconds(120);
        ui.elapsed += 120_000;
        clock.now = right.plusSeconds(120);
        assert cook.timers().get(0).left == 480 : cook.timers().get(0).left;
        ui.run(Cook.DUE_CHECK_MS);
        assert !ui.alarm;
        // Whichever way it is touched right after that.
        clock.now = right.minusSeconds(year);
        fresh(settings());
        net.runAll();
        right = clock.now;
        ui.elapsed = 5_000;
        clock.now = right.minusSeconds(year);
        cook.startTimer("Pasta", 600);
        int pasta = cook.timers().get(0).id;
        ui.elapsed += 120_000;
        clock.now = right.plusSeconds(120);
        cook.addToTimer(pasta, 60);
        assert cook.timers().get(0).left == 540 && !ui.alarm : cook.timers().get(0).left;
        ui.elapsed += 1_000;
        clock.now = clock.now.plusSeconds(1);
        // Set once more, and paused at once: what is left stays what was left.
        clock.now = clock.now.plusSeconds(3600);
        cook.pauseTimer(pasta);
        assert cook.timers().get(0).left == 539 && cook.timers().get(0).state == Timers.State.PAUSED : cook.timers().get(0).left;
        cook.resumeTimer(pasta);
        ui.elapsed += 539_000;
        clock.now = clock.now.plusSeconds(539);
        ui.run(Cook.DUE_CHECK_MS);
        assert ui.alarm;

        // Moved by the set, it is saved at once, so a restart right after it finds the right end.
        fresh(settings());
        net.runAll();
        right = clock.now;
        ui.elapsed = 5_000;
        clock.now = right.minusSeconds(year);
        cook.startTimer("Braise", 2 * 3600);
        ui.run(Cook.SAVE_AFTER_MS);
        ui.elapsed += 120_000;
        clock.now = right.plusSeconds(120);
        assert cook.timers().get(0).left == 2 * 3600 - 120;
        ui.run(Cook.SAVE_AFTER_MS);
        values = settings();
        values.put("resumeState", host.saved.get("resumeState"));
        cook.stop();
        again = new Host();
        againUi = new FakeUi();
        againUi.elapsed = 1_000;
        againCook = new Cook(againUi, clock, new Net(), settings -> source);
        againCook.start(again, values);
        assert againCook.timers().size() == 1 && againCook.timers().get(0).left == 2 * 3600 - 120 : againCook.timers();
        againCook.stop();

        // Started on a clock at its floor and restored before the set: it counts what it had, and is not taken for one that ended long ago.
        fresh(settings());
        net.runAll();
        right = clock.now;
        ui.elapsed = 5_000;
        clock.now = right.minusSeconds(year);
        cook.startTimer("Pasta", 600);
        ui.run(Cook.SAVE_AFTER_MS);
        values = settings();
        values.put("resumeState", host.saved.get("resumeState"));
        cook.stop();
        clock.now = clock.now.plusSeconds(60);
        again = new Host();
        againUi = new FakeUi();
        againUi.elapsed = 1_000;
        againCook = new Cook(againUi, clock, new Net(), settings -> source);
        againCook.start(again, values);
        assert againCook.timers().get(0).left == 540;
        againUi.elapsed += 60_000;
        clock.now = right.plusSeconds(120);
        againUi.run(Cook.DUE_CHECK_MS);
        assert againCook.timers().size() == 1 && againCook.timers().get(0).left == 480 && !againUi.alarm : againCook.timers();
        assert !again.status.contains("ended long ago") : again.status;
        againUi.elapsed += 480_000;
        clock.now = clock.now.plusSeconds(480);
        againUi.run(Cook.DUE_CHECK_MS);
        assert againUi.alarm && again.triggers.contains("timerDone");
        againCook.stop();

        // A tablet that was asleep has lost no time on either clock: what ended meanwhile rings when it wakes, however late.
        fresh(settings());
        net.runAll();
        cook.startTimer("Pasta", 600);
        ui.run(Cook.SAVE_AFTER_MS);
        values = settings();
        values.put("resumeState", host.saved.get("resumeState"));
        cook.stop();
        again = new Host();
        againUi = new FakeUi();
        againUi.elapsed = 5_000;
        againCook = new Cook(againUi, clock, new Net(), settings -> source);
        againCook.start(again, values);
        againUi.elapsed += 4 * 3600_000L;
        clock.now = clock.now.plusSeconds(4 * 3600);
        againUi.run(Cook.DUE_CHECK_MS);
        assert againUi.alarm && againCook.timers().size() == 1;
        againCook.stop();
    }

    /** A step that waits for half a day is no kiosk that was off for half a day. */
    static void longRest() {
        fresh(settings());
        cooking();
        cook.next();
        ui.run(Cook.SAVE_AFTER_MS);
        cook.close();
        for (int i = 0; i < 25; i++) {
            clock.minutes(30);
            ui.run(Cook.PLAN_REFRESH_MS);
            net.runAll();
            ui.run(Cook.SAVE_AFTER_MS);
        }
        Map<String, Object> values = settings();
        values.put("resumeState", host.saved.get("resumeState"));
        cook.stop();
        Host again = new Host();
        FakeUi againUi = new FakeUi();
        Net againNet = new Net();
        Cook againCook = new Cook(againUi, clock, againNet, settings -> source);
        againCook.start(again, values);
        againNet.runAll();
        assert againCook.cooking() && "Pancakes · 2/2".equals(againUi.dock) : againUi.dock;
        againCook.stop();
        // Really off for that long, it is another day's meal.
        clock.now = clock.now.plusSeconds(13 * 3600);
        Host cold = new Host();
        Net coldNet = new Net();
        Cook coldCook = new Cook(new FakeUi(), clock, coldNet, settings -> source);
        coldCook.start(cold, values);
        coldNet.runAll();
        assert !coldCook.cooking();
        coldCook.stop();

        // With nothing going on, a tick writes nothing.
        fresh(settings());
        net.runAll();
        ui.run(Cook.SAVE_AFTER_MS);
        host.saved.clear();
        clock.minutes(30);
        ui.run(Cook.PLAN_REFRESH_MS);
        net.runAll();
        ui.run(Cook.SAVE_AFTER_MS);
        assert host.saved.isEmpty() : host.saved;
    }

    /** A kiosk before 2026.11.1 has no way to stop a reading, and refuses the next while it lasts. */
    static void readingBusy() {
        fresh(assistant());
        cooking();
        cook.read();
        host.haAnswers.get(0).onResult(true, null, null);
        cook.next();
        assert "ks_kitchen_announce_stop".equals(host.haCalls.get(1).get("service"));
        host.haAnswers.get(1).onResult(false, null, "not_found: Service esphome.ks_kitchen_announce_stop not found.");

        // The newest text waits for the kiosk, and is not lost.
        cook.read();
        assert host.haCalls.size() == 3;
        host.status = "";
        host.haAnswers.get(2).onResult(false, null, "home_assistant_error: in a call");
        assert ui.posted(Cook.SPEECH_AGAIN_MS) && host.status.isEmpty();
        ui.run(Cook.SPEECH_AGAIN_MS);
        assert host.haCalls.size() == 4 && "ks_kitchen_announce".equals(host.haCalls.get(3).get("service"));
        assert "Bake.".equals(((Map<?, ?>) host.haCalls.get(3).get("service_data")).get("message"));
        host.haAnswers.get(3).onResult(true, null, null);
        assert !ui.posted(Cook.SPEECH_AGAIN_MS);

        // Moving on drops what was waiting, also if its refusal comes only afterwards.
        cook.read();
        cook.previous();
        host.haAnswers.get(4).onResult(false, null, "home_assistant_error: in a call");
        assert !ui.posted(Cook.SPEECH_AGAIN_MS);
        cook.read();
        host.haAnswers.get(5).onResult(false, null, "home_assistant_error: in a call");
        assert ui.posted(Cook.SPEECH_AGAIN_MS);
        cook.home();
        assert !ui.posted(Cook.SPEECH_AGAIN_MS);

        // Going back past the first step leaves its text behind, and so does starting, ending and opening something else.
        cook.resumeCooking();
        assert ui.model.page == Model.Page.STEP && ui.model.pageNumber == 1;
        cook.read();
        host.haAnswers.get(host.haAnswers.size() - 1).onResult(false, null, "home_assistant_error: in a call");
        assert ui.posted(Cook.SPEECH_AGAIN_MS);
        cook.previous();
        assert ui.model.page == Model.Page.RECIPE && !ui.posted(Cook.SPEECH_AGAIN_MS);
        cook.read();
        host.haAnswers.get(host.haAnswers.size() - 1).onResult(false, null, "home_assistant_error: in a call");
        cook.startCooking();
        assert !ui.posted(Cook.SPEECH_AGAIN_MS);
        cook.read();
        host.haAnswers.get(host.haAnswers.size() - 1).onResult(false, null, "home_assistant_error: in a call");
        cook.close();
        assert !ui.posted(Cook.SPEECH_AGAIN_MS);
        cook.execute("openCookMode");
        cook.read();
        host.haAnswers.get(host.haAnswers.size() - 1).onResult(false, null, "home_assistant_error: in a call");
        cook.openRecipe(new RecipeSource.Summary("8", "Soup", "", 45));
        assert ui.model.confirm == null || !ui.model.confirm.isEmpty();
        if (ui.model.confirm != null) cook.confirm(true);
        assert !ui.posted(Cook.SPEECH_AGAIN_MS);
        net.runAll();

        // It does not try for ever, and says so.
        fresh(assistant());
        cooking();
        cook.read();
        int calls = host.haCalls.size();
        for (int i = 0; i <= Cook.SPEECH_TRIES; i++) {
            host.haAnswers.get(host.haAnswers.size() - 1).onResult(false, null, "home_assistant_error: in a call");
            if (i < Cook.SPEECH_TRIES) ui.run(Cook.SPEECH_AGAIN_MS);
        }
        assert host.haCalls.size() == calls + Cook.SPEECH_TRIES && !ui.posted(Cook.SPEECH_AGAIN_MS) : host.haCalls.size() - calls;
        assert host.statusError && host.status.contains("still reading") : host.status;

        // Moving on while the first node is being asked: the next node is not asked for the old text.
        Map<String, Object> noNode = settings();
        noNode.put("readAloud", "Home Assistant");
        fresh(noNode);
        cooking();
        cook.read();
        int asked = host.haCalls.size();
        cook.next();
        host.haAnswers.get(host.haAnswers.size() - 1).onResult(false, null, "not_found: Service esphome.ks_kitchen_announce not found.");
        assert host.haCalls.size() == asked : "Only the first node was asked";

        // A stop action that exists but failed for once is not forgotten.
        fresh(assistant());
        cooking();
        cook.read();
        host.haAnswers.get(0).onResult(true, null, null);
        cook.next();
        host.haAnswers.get(1).onResult(false, null, "timeout: No answer within 25 seconds");
        cook.read();
        host.haAnswers.get(2).onResult(true, null, null);
        cook.previous();
        assert "ks_kitchen_announce_stop".equals(host.haCalls.get(3).get("service"));
    }

    static void problems() {
        // Not configured: no calls, and the status says what to set.
        Map<String, Object> empty = settings();
        empty.put("apiToken", "");
        fresh(empty);
        assert net.queue.isEmpty() && host.statusError && host.status.contains("API token");
        cook.execute("openCookMode");
        assert !ui.model.configured;
        cook.close();

        // A refused token: the panel says it in its language, the plugin's page says exactly why, and Retry reads again.
        fresh(settings());
        source.fail = new RecipeSource.SourceException(RecipeSource.SourceException.Kind.AUTH, "Tandoor refused the API token.");
        net.runAll();
        cook.execute("openCookMode");
        net.runAll();
        assert Strings.ENGLISH.problemAuth.equals(ui.model.banner) && host.statusError && "Tandoor refused the API token.".equals(host.status);
        source.fail = null;
        cook.refresh();
        net.runAll();
        assert ui.model.banner == null && !host.statusError && ui.model.days.get(0).meals.size() == 2;

        // A recipe that fails to load stays on its page with the reason, and Retry asks for it again.
        source.fail = new RecipeSource.SourceException(RecipeSource.SourceException.Kind.NOT_FOUND, "Tandoor has no recipe 7.");
        cook.openRecipe(new RecipeSource.Summary("7", "Pancakes", "", 30));
        net.runAll();
        assert ui.model.page == Model.Page.RECIPE && ui.model.recipe == null && Strings.ENGLISH.recipeMissing.equals(ui.model.banner) && !ui.model.recipeLoading;
        source.fail = null;
        cook.refresh();
        net.runAll();
        assert ui.model.recipe != null && ui.model.banner == null;

        // A failed log says so and may be tried again.
        cook.startCooking();
        cook.next();
        cook.next();
        source.fail = new RecipeSource.SourceException(RecipeSource.SourceException.Kind.AUTH, "Read only token.");
        cook.logCooked();
        net.runAll();
        assert !ui.model.logged && ui.model.canLogCooked && Strings.ENGLISH.problemAuth.equals(ui.model.banner);
        source.fail = null;
        cook.logCooked();
        net.runAll();
        assert ui.model.logged;

        // An answer for an older question is dropped.
        fresh(settings());
        net.runAll();
        cook.execute("openCookMode");
        net.runAll();
        cook.openRecipe(new RecipeSource.Summary("7", "Pancakes", "", 30));
        Runnable late = net.queue.remove(0);
        cook.home();
        cook.openRecipe(new RecipeSource.Summary("8", "Soup", "", 30));
        Recipe soup = new Recipe("8", "Soup", "", "", 2, "", 5, 0, "", "", Collections.<Recipe.Step>emptyList());
        source.recipe = soup;
        net.runAll();
        source.recipe = recipe();
        late.run();
        assert "Soup".equals(ui.model.recipeName) && ui.model.recipe == soup;
        // A recipe without steps cannot start.
        cook.startCooking();
        assert ui.model.page == Model.Page.RECIPE && !cook.cooking();

        // A new server forgets what the old one said, and asks the new one.
        fresh(settings());
        net.runAll();
        Map<String, Object> other = settings();
        other.put("serverUrl", "http://other:80");
        int plans = planCalls();
        cook.configure(other);
        assert source.calls.contains("cancel");
        net.runAll();
        assert planCalls() == plans + 1;

        // After stop, late answers and commands change nothing, and a posted task does nothing.
        fresh(settings());
        net.runAll();
        cook.execute("openCookMode");
        Runnable answer = net.queue.isEmpty() ? null : net.queue.remove(0);
        Model last = ui.model;
        cook.stop();
        if (answer != null) answer.run();
        assert ui.model == last;
        int commands = host.commands.size();
        cook.execute("openCookMode");
        cook.next();
        cook.startTimer("t", 60);
        assert host.commands.size() == commands;
    }
}
