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
        public void executeCommand(String command, Map<String, Object> arguments, CommandCallback callback) {
            assert arguments.size() <= 2 && callback != null;
            commands.add(command);
            commandArguments.add(arguments);
            if ("getDeviceInfo".equals(command)) callback.onResult(true, Collections.singletonMap("name", deviceName), null);
        }
        public void updateSettings(Map<String, Object> values) { saved.putAll(values); }
        public void subscribe(String event) { subscriptions.add(event); }
        public void haCall(Map<String, Object> message, CommandCallback callback) { assert !message.containsKey("id"); haCalls.add(message); haAnswers.add(callback); }
        public void showOverlay(String key, OverlaySpec spec, OverlayFactory factory) { assert key.matches("[a-z][a-z0-9_]{0,39}") && factory != null; overlays.put(key, spec); }
        public void hideOverlay(String key) { overlays.remove(key); }
        public void status(String message, boolean error) { assert message.length() <= 1000; status = message; statusError = error; }
        public void publishSelect(String key, String name, String[] options, String state) { assert Arrays.asList(options).contains(state); entities.put(key, state); }
        public void publishBinarySensor(String key, String name, String deviceClass, Boolean state) { entities.put(key, state); }
        public void publishSensor(String key, String name, Map<String, Object> metadata, Double state) { entities.put(key, state); }
        public void publishTextSensor(String key, String name, String state) { assert state.length() <= 512; entities.put(key, state); }
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
        int silences;
        boolean shutdown;
        boolean screenOn;
        Settings.Tone tone;
        final List<String> spoken = new ArrayList<>();
        final Map<Runnable, Long> tasks = new LinkedHashMap<>();
        public OverlayFactory main(Ui.Actions actions) { this.actions = actions; return (context, theme) -> null; }
        public OverlayFactory dock(Ui.Actions actions) { this.actions = actions; return (context, theme) -> null; }
        public void render(Model model) { this.model = model; renders++; }
        public void renderDock(String cooking, Strings strings) { dock = cooking; }
        public void sound(Settings.Tone tone, boolean mediaStream, int minutes) { this.tone = tone; }
        public void alarm(boolean on) { alarm = on; }
        public void testAlarm(Ui.Report report) { report.result(true, "Ringing."); }
        public void speak(String text, Locale locale) { spoken.add(text); }
        public void silence() { silences++; }
        public void keepScreenOn(boolean on) { screenOn = on; }
        public void shutdown() { shutdown = true; }
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
        final List<String> calls = new ArrayList<>();
        public String probe() throws SourceException { calls.add("probe"); if (fail != null) throw fail; return "Connected to Tandoor 2.7.2, 2 recipes."; }
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
        public void cancel() { calls.add("cancel"); }
    }

    static final TestClock clock = new TestClock();
    static final LocalDate TODAY = LocalDate.of(2026, 11, 1);
    static Host host;
    static FakeUi ui;
    static Net net;
    static Source source;
    static Cook cook;

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
        cook = new Cook(ui, clock, net, settings -> source);
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
        System.out.println("CookTest passed");
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
        assert ui.posted(300_000L) && ui.renders > renders && ui.model.pageVersion == version : "A timer must not rebuild the page under a finger";
        // One press that landed twice is one timer: the step's timer starts once, and the view can show how it is doing.
        assert !cook.stepTimer("5 min", 300);
        List<Timers.Timer> list = cook.timers();
        assert list.size() == 1 && "Step 1 · 5 min".equals(list.get(0).label) && "7".equals(list.get(0).recipe) && list.get(0).step == 0;
        assert Double.valueOf(1).equals(host.entities.get("timers"));
        assert "Step 1 · 5 min 11:05".equals(nextTimer(11, 5)) : host.entities.get("next_timer");

        // Pause drops the due time, resume brings it back.
        int id = list.get(0).id;
        cook.pauseTimer(id);
        assert !ui.posted(300_000L);
        clock.minutes(1);
        cook.resumeTimer(id);
        assert ui.posted(300_000L);
        cook.addToTimer(id, 60);
        assert ui.posted(360_000L);
        clock.minutes(6);

        // It rings: sound, the screen, the trigger and the entity. The screensaver goes if it is up.
        int stops = host.count("stopScreensaver");
        ui.run(360_000L);
        assert ui.alarm && host.count("stopScreensaver") == stops + 1 && host.triggers.contains("timerDone") && Boolean.TRUE.equals(host.entities.get("timer_ringing"));
        assert cook.timers().get(0).state == Timers.State.RINGING;
        cook.stopAlarm();
        assert !ui.alarm && cook.timers().isEmpty() && Boolean.FALSE.equals(host.entities.get("timer_ringing"));

        // KS's stop word only silences a ringing timer the first time, so a false alarm cannot wipe what ended. The second takes it away.
        cook.startTimer("1 min", 60);
        clock.minutes(1);
        ui.run(60_000L);
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
        ui.run(120_000L);
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
        host.haAnswers.get(0).onResult(false, null, "service_not_found: Action esphome.ks_kitchen_announce not found");
        assert "kitchen_announce".equals(host.haCalls.get(1).get("service"));
        host.haAnswers.get(1).onResult(false, null, "service_not_found: Action esphome.kitchen_announce not found");
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
        host.haAnswers.get(4).onResult(false, null, "service_not_found: Action not found");
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
        ui.run(ui.tasks.values().stream().filter(delay -> delay >= 300_000L && delay < 400_000L).findFirst().get());
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
        ui.run(ui.tasks.values().stream().filter(delay -> delay >= 300_000L && delay < 400_000L).findFirst().get());
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
        assert host.triggers.contains("cookingFinished");
        net.runAll();

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
        ui.run(60_000L);
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
        laterCook.start(later, values);
        assert laterCook.timers().size() == 1 && later.status.contains("2 timers ended") : later.status;
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
