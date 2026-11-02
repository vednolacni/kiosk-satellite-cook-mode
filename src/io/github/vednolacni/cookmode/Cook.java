// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import me.jxl.kiosk.plugins.OverlaySpec;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * The plugin without its Android views: the meal plan, the recipe list, the recipe being cooked, timers, the screen
 * hold, read-aloud, commands and entities. KS callbacks arrive on the plugin's worker, taps and timers on the main
 * thread and answers on the network thread, so every way in holds this object's lock, and nothing holds it across a
 * network call.
 */
final class Cook implements Ui.Actions {
    /** Makes the source for the settings. Only Tandoor today. */
    interface Sources {
        RecipeSource create(Settings settings);
    }

    static final String MAIN = "main";
    static final String DOCK = "dock";
    static final String VIEW = "view";
    static final String CLOSED = "Closed";
    static final String OPEN = "Open";
    /** Keeps today's meals fresh, also for Home Assistant, and moves the plan past midnight. */
    static final long PLAN_REFRESH_MS = 30 * 60_000L;
    /** Opening the panel reads the plan again when what it has is older than this. */
    static final long PLAN_STALE_MS = 5 * 60_000L;
    /** A gesture or a clap can fire twice. Steps change at most this often by command. */
    static final long COMMAND_GAP_MS = 700L;
    /** The dock keeps one size: a wrapped overlay that resizes shows stale frames on slow tablets. */
    static final int DOCK_WIDTH = 320;
    static final int DOCK_HEIGHT = 68;
    private static final Map<String, Object> NONE = Collections.emptyMap();

    private final Ui ui;
    private final Clock clock;
    private final Executor net;
    private final Sources sources;
    private final Awake awake;
    private final Timers timers = new Timers();
    private final Runnable dueTask = this::due;
    private final Runnable planTask = this::planTick;
    private final Runnable idleTask = this::idleCheck;

    private PluginHost host;
    private Settings settings;
    private Strings strings = Strings.ENGLISH;
    private RecipeSource source;
    private boolean shown;
    private boolean dockShown;
    private Model.Page page = Model.Page.HOME;
    private long version;
    private String banner;

    private List<RecipeSource.Planned> plan = Collections.emptyList();
    private int planGeneration;
    private boolean planLoading;
    private long planReadAt = -1;

    private String query = "";
    private RecipeSource.Order order = RecipeSource.Order.NAME;
    private List<RecipeSource.Summary> recipes = new ArrayList<>();
    private int recipesPage;
    private boolean recipesMore;
    private boolean recipesLoading;
    private int listGeneration;

    private Recipe recipe;
    private String recipeName = "";
    private String recipeImage = "";
    private String recipeId = "";
    private boolean recipeLoading;
    private int recipeGeneration;
    private double servings;
    /** The servings to start at when the loading recipe arrives, or 0 for its own. */
    private double plannedServings;
    private final Set<Recipe.Ingredient> checked = Collections.newSetFromMap(new IdentityHashMap<Recipe.Ingredient, Boolean>());
    private List<Pages.Page> pages = Collections.emptyList();
    private int pageIndex;
    private boolean cooking;
    private long sessionStart;
    private boolean logged;
    private boolean logging;
    /** A KS page was opened from here. KS does not say when it closes, so the hold on it ends with the idle limit. */
    private boolean linkOpen;

    private long lastTouch;
    private boolean idleExpired;
    private long lastCommand;
    private boolean alarmOn;
    private final Map<String, String> published = new LinkedHashMap<>();

    Cook(Ui ui, Clock clock, Executor net, Sources sources) {
        this.ui = ui;
        this.clock = clock;
        this.net = net;
        this.sources = sources;
        awake = new Awake(this, clock, ui);
    }

    // Lifecycle.

    synchronized void start(PluginHost host, Map<String, Object> values) {
        this.host = host;
        awake.start(host);
        lastTouch = clock.millis();
        configure(values);
        publishCatalog();
        host.subscribe("screensaver.state");
        host.subscribe("screensaver.countdown");
        // The word KS listens for to stop its own timers silences ours too.
        host.subscribe("stopword.detected");
        ui.post(planTask, PLAN_REFRESH_MS);
    }

    synchronized void configure(Map<String, Object> values) {
        Settings previous = settings;
        settings = new Settings(values);
        strings = Strings.of(settings.language);
        if (host == null) return;
        boolean changedServer = !settings.sameServer(previous);
        if (changedServer) {
            RecipeSource old = source;
            if (old != null) old.cancel();
            source = settings.configured() ? sources.create(settings) : null;
            plan = Collections.emptyList();
            planReadAt = -1;
            recipes = new ArrayList<>();
            recipesPage = 0;
            banner = null;
            planGeneration++;
            listGeneration++;
            planLoading = false;
            recipesLoading = false;
            if (source != null) loadPlan();
        } else if (previous != null && previous.planDays != settings.planDays) {
            loadPlan();
        }
        if (recipe != null && previous != null && previous.splitSteps != settings.splitSteps) {
            pages = Pages.of(recipe, settings.splitSteps);
            pageIndex = Math.min(pageIndex, Math.max(0, pages.size() - 1));
        }
        report();
        updateAwake();
        changed();
    }

    /** KS revokes the host and removes the overlays and entities before stop, so nothing is hidden here. */
    synchronized void stop() {
        ui.cancel(dueTask);
        ui.cancel(planTask);
        ui.cancel(idleTask);
        awake.stop();
        if (source != null) source.cancel();
        ui.shutdown();
        host = null;
        settings = null;
        source = null;
        shown = false;
        dockShown = false;
        timers.clear();
    }

    synchronized void execute(String command) {
        if (host == null) return;
        lastTouch = clock.millis();
        if (idleExpired) {
            idleExpired = false;
            updateAwake();
        }
        switch (command) {
            case "openCookMode": open(); break;
            case "closeCookMode": close(); break;
            case "toggleCookMode": if (shown) close(); else open(); break;
            case "cookToday": cookToday(); break;
            case "nextStep": stepCommand(true); break;
            case "previousStep": stepCommand(false); break;
            case "readStep": read(); break;
            case "startStepTimer": startStepTimer(); break;
            case "stopAlarm": stopAlarm(); break;
            case "endCooking": endCooking(); break;
            default: throw new IllegalArgumentException("Unknown command: " + command);
        }
    }

    synchronized void onEvent(String event, Map<String, Object> payload) {
        if (host == null) return;
        if (("select." + VIEW).equals(event)) {
            Object option = payload.get("option");
            if (OPEN.equals(option)) open();
            else if (CLOSED.equals(option)) close();
            else throw new IllegalArgumentException("Unknown Cook Mode view option: " + option);
            publishState();
        } else if ("overlay.closed".equals(event)) {
            if (MAIN.equals(payload.get("key"))) closed();
        } else if ("ks.screensaver.state".equals(event)) {
            boolean active = Boolean.TRUE.equals(payload.get("active"));
            awake.screensaver(active);
            if (active && shown && page == Model.Page.HOME && settings.closeOnScreensaver) close();
        } else if ("ks.screensaver.countdown".equals(event)) {
            awake.countdown(payload.get("due"));
        } else if ("ks.stopword.detected".equals(event)) {
            if (timers.ringing()) stopAlarm();
            ui.silence();
        } else if ("settings.button".equals(event)) {
            if ("testConnection".equals(payload.get("id"))) testConnection(String.valueOf(payload.get("run")));
        }
    }

    // Opening and closing.

    private void open() {
        if (!shown) {
            shown = true;
            lastTouch = clock.millis();
            idleExpired = false;
            hideDockNow();
            guardCommand("stopScreensaver");
            render();
            host.showOverlay(MAIN, OverlaySpec.fullScreen().enter(OverlaySpec.FADE).exit(OverlaySpec.FADE).transitionDuration(150), ui.main(this));
            if (planReadAt < 0 || clock.millis() - planReadAt > PLAN_STALE_MS) loadPlan();
            if (recipesPage == 0 && !recipesLoading) loadRecipes(1);
            // The panel shows what it is loading as soon as it opens.
            changed();
        }
        updateAwake();
        publishState();
    }

    /** The main view went away, by the close button or by back. The session stays until End cooking. */
    private void closed() {
        shown = false;
        ui.silence();
        updateAwake();
        updateDock();
        publishState();
    }

    private void hideDockNow() {
        if (dockShown) host.hideOverlay(DOCK);
        dockShown = false;
    }

    /** The dock shows while the main view is closed and something is going on. */
    private void updateDock() {
        boolean want = !shown && (timers.any() || cooking);
        ui.renderDock(cooking && recipe != null ? recipe.name + cookingProgress() : "", strings);
        if (want && !dockShown) {
            dockShown = true;
            host.showOverlay(DOCK, OverlaySpec.at(settings.dockCorner).size(DOCK_WIDTH, DOCK_HEIGHT).inset(16).closeOnBack(false).onTop(true).enter(OverlaySpec.SLIDE).exit(OverlaySpec.FADE), ui.dock(this));
        } else if (!want && dockShown) {
            hideDockNow();
        }
    }

    private String cookingProgress() {
        return pages.isEmpty() ? "" : " · " + (pageIndex + 1) + "/" + pages.size();
    }

    // Reading the meal plan and the recipes.

    private void loadPlan() {
        final RecipeSource current = source;
        if (current == null) return;
        final int generation = ++planGeneration;
        planLoading = true;
        final LocalDate first = LocalDate.now(clock);
        final LocalDate last = first.plusDays(settings.planDays - 1);
        background(() -> {
            List<RecipeSource.Planned> read = null;
            RecipeSource.SourceException problem = null;
            try {
                read = current.mealPlan(first, last);
            } catch (RecipeSource.SourceException failed) {
                problem = failed;
            }
            synchronized (this) {
                if (host == null || generation != planGeneration) return;
                planLoading = false;
                if (read != null) {
                    plan = read;
                    planReadAt = clock.millis();
                    if (page == Model.Page.HOME) banner = null;
                    report();
                } else {
                    failed(problem);
                }
                publishState();
                if (page == Model.Page.HOME) changed();
            }
        });
        if (page == Model.Page.HOME) version++;
    }

    private void loadRecipes(int number) {
        final RecipeSource current = source;
        if (current == null) return;
        final int generation = ++listGeneration;
        final String text = query;
        final RecipeSource.Order sort = order;
        recipesLoading = true;
        background(() -> {
            RecipeSource.Page read = null;
            RecipeSource.SourceException problem = null;
            try {
                read = current.recipes(text, sort, number);
            } catch (RecipeSource.SourceException failed) {
                problem = failed;
            }
            synchronized (this) {
                if (host == null || generation != listGeneration) return;
                recipesLoading = false;
                if (read != null) {
                    if (number == 1) recipes = new ArrayList<>();
                    // A list can shift between pages, so a recipe may come twice.
                    for (RecipeSource.Summary item : read.items) {
                        boolean seen = false;
                        for (RecipeSource.Summary have : recipes) seen |= have.id.equals(item.id);
                        if (!seen) recipes.add(item);
                    }
                    recipesPage = number;
                    recipesMore = read.more;
                } else {
                    failed(problem);
                }
                if (page == Model.Page.HOME) changed();
            }
        });
        if (page == Model.Page.HOME) version++;
    }

    /** A call failed: the panel says it in its language, the plugin's page says exactly why. */
    private void failed(RecipeSource.SourceException problem) {
        if (problem == null) return;
        switch (problem.kind) {
            case AUTH: banner = strings.problemAuth; break;
            case HOST: banner = strings.problemHost; break;
            case NETWORK: banner = strings.problemNetwork; break;
            case NOT_FOUND: banner = strings.recipeMissing; break;
            default: banner = strings.problemOther;
        }
        status(problem.getMessage(), true);
    }

    private void status(String message, boolean error) {
        if (host != null) host.status(message.length() > 1000 ? message.substring(0, 1000) : message, error);
    }

    /** The plugin page's status line. */
    private void report() {
        String problem = settings.problem();
        if (problem != null) {
            status(problem, true);
        } else if (planReadAt < 0) {
            status("Reading the meal plan.", false);
        } else {
            int count = 0;
            LocalDate today = LocalDate.now(clock);
            for (RecipeSource.Planned meal : plan) if (meal.date.equals(today)) count++;
            status("Connected. " + count + (count == 1 ? " meal" : " meals") + " planned today. Read at " + LocalTime.now(clock).withSecond(0).withNano(0) + ".", false);
        }
    }

    private void testConnection(String run) {
        final Settings current = settings;
        background(() -> {
            RecipeSource probe = sources.create(current);
            try {
                if (!host().setupResult(run, "running", "Connecting to " + current.serverUrl)) return;
                host().setupResult(run, "success", probe.probe());
            } catch (RecipeSource.SourceException failed) {
                host().setupResult(run, "error", failed.getMessage());
            }
        });
    }

    private synchronized PluginHost host() {
        if (host == null) throw new IllegalStateException("The plugin stopped");
        return host;
    }

    /** Run network work on the plugin's own thread. An exception there would end the app, so none gets out. */
    private void background(Runnable task) {
        try {
            net.execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException sessionEnded) {
                    // KS revokes the host before stop. Nothing is left to tell.
                }
            });
        } catch (RejectedExecutionException stopping) {
            // The plugin is stopping.
        }
    }

    // Main view actions.

    @Override public synchronized void close() {
        if (host == null) return;
        if (shown) host.hideOverlay(MAIN);
        closed();
    }

    @Override public synchronized void home() {
        if (host == null) return;
        page = Model.Page.HOME;
        banner = null;
        ui.silence();
        updateAwake();
        changed();
    }

    @Override public synchronized void search(String text) {
        if (host == null) return;
        query = text == null ? "" : text.trim();
        banner = null;
        loadRecipes(1);
        changed();
    }

    @Override public synchronized void showOrder(RecipeSource.Order next) {
        if (host == null || next == order) return;
        order = next;
        loadRecipes(1);
        changed();
    }

    @Override public synchronized void loadMore() {
        if (host != null && recipesMore && !recipesLoading) {
            loadRecipes(recipesPage + 1);
            changed();
        }
    }

    @Override public synchronized void refresh() {
        if (host == null) return;
        banner = null;
        if (page == Model.Page.RECIPE && recipe == null && !recipeId.isEmpty()) loadRecipe();
        else {
            loadPlan();
            loadRecipes(1);
        }
        changed();
    }

    @Override public synchronized void openPlanned(RecipeSource.Planned meal) {
        if (host == null) return;
        if (meal.recipeId == null) {
            // A note such as "leftovers" has no recipe. Its title is the best guess at one.
            search(meal.title);
            return;
        }
        openById(meal.recipeId, meal.title, meal.image, meal.servings);
    }

    @Override public synchronized void openRecipe(RecipeSource.Summary summary) {
        if (host != null) openById(summary.id, summary.name, summary.image, 0);
    }

    @Override public synchronized void resumeCooking() {
        if (host == null || recipe == null) return;
        page = cooking ? Model.Page.STEP : Model.Page.RECIPE;
        updateAwake();
        changed();
    }

    private void openById(String id, String name, String image, double planned) {
        banner = null;
        page = Model.Page.RECIPE;
        // The recipe being cooked comes back as it was left.
        if (id.equals(recipeId) && recipe != null) {
            if (cooking) page = Model.Page.STEP;
            updateAwake();
            changed();
            return;
        }
        endSession();
        recipeId = id;
        recipeName = name;
        recipeImage = image;
        plannedServings = planned;
        loadRecipe();
        updateAwake();
        changed();
    }

    private void loadRecipe() {
        final RecipeSource current = source;
        if (current == null) return;
        final String id = recipeId;
        final int generation = ++recipeGeneration;
        final boolean tell = settings.markCooked != Settings.MarkCooked.NEVER;
        recipeLoading = true;
        background(() -> {
            Recipe read = null;
            RecipeSource.SourceException problem = null;
            try {
                read = current.recipe(id);
            } catch (RecipeSource.SourceException failed) {
                problem = failed;
            }
            synchronized (this) {
                if (host == null || generation != recipeGeneration) return;
                recipeLoading = false;
                if (read != null) {
                    recipe = read;
                    recipeName = read.name;
                    if (!read.image.isEmpty()) recipeImage = read.image;
                    servings = plannedServings > 0 ? plannedServings : read.servings;
                    pages = Pages.of(read, settings.splitSteps);
                    pageIndex = 0;
                    checked.clear();
                    banner = null;
                } else {
                    failed(problem);
                }
                changed();
            }
            // Tandoor's recently viewed list. A read-only token cannot, which costs nothing here.
            if (read != null && tell) {
                try {
                    current.viewed(id);
                } catch (RecipeSource.SourceException ignored) {
                    // Only the app's own list misses it.
                }
            }
        });
    }

    /** Forget the recipe being cooked. Timers keep running: they name their own recipe. */
    private void endSession() {
        boolean was = cooking;
        cooking = false;
        recipe = null;
        recipeId = "";
        recipeName = "";
        recipeImage = "";
        recipeLoading = false;
        recipeGeneration++;
        pages = Collections.emptyList();
        pageIndex = 0;
        servings = 0;
        checked.clear();
        logged = false;
        logging = false;
        linkOpen = false;
        if (was) fire("cookingFinished");
    }

    @Override public synchronized void changeServings(int direction) {
        if (host == null || recipe == null || recipe.servings <= 0) return;
        double step = servings > 2 || (servings == 2 && direction > 0) ? 1 : 0.5;
        double next = servings + direction * step;
        if (next < 0.5 || next > 99) return;
        servings = next;
        changed();
    }

    @Override public synchronized void toggle(Recipe.Ingredient ingredient) {
        if (host == null) return;
        if (!checked.remove(ingredient)) checked.add(ingredient);
        changed();
    }

    @Override public synchronized void startCooking() {
        if (host == null || recipe == null || pages.isEmpty()) return;
        if (!cooking) {
            cooking = true;
            sessionStart = clock.millis();
            fire("cookingStarted");
        }
        page = Model.Page.STEP;
        lastTouch = clock.millis();
        updateAwake();
        changed();
        publishState();
        autoRead();
    }

    @Override public synchronized void openInApp() {
        if (host == null || recipe == null || recipe.webUrl.isEmpty()) return;
        // Plugin overlays cover KS pages, so the view goes first.
        close();
        linkOpen = true;
        updateAwake();
        updateDock();
        final String address = recipe.webUrl;
        host.executeCommand("showLinkPage", Collections.<String, Object>singletonMap("url", address), (ok, data, error) -> {
            if (!ok) status("Could not open " + address + ": " + error, true);
        });
    }

    @Override public synchronized void next() {
        if (host == null) return;
        if (page == Model.Page.RECIPE) {
            startCooking();
        } else if (page == Model.Page.STEP) {
            if (pageIndex < pages.size() - 1) {
                pageIndex++;
                ui.silence();
                changed();
                publishState();
                autoRead();
            } else {
                page = Model.Page.FINISH;
                ui.silence();
                if (settings.markCooked == Settings.MarkCooked.ALWAYS) logCooked();
                changed();
            }
        }
    }

    @Override public synchronized void previous() {
        if (host == null) return;
        if (page == Model.Page.FINISH) {
            page = Model.Page.STEP;
            changed();
        } else if (page == Model.Page.STEP) {
            if (pageIndex > 0) {
                pageIndex--;
                ui.silence();
                changed();
                publishState();
                autoRead();
            } else {
                page = Model.Page.RECIPE;
                updateAwake();
                changed();
            }
        } else if (page == Model.Page.RECIPE) {
            home();
        }
    }

    /** nextStep and previousStep by gesture. With the view closed the first one only brings it back. */
    private void stepCommand(boolean forward) {
        long now = clock.millis();
        if (now - lastCommand < COMMAND_GAP_MS) return;
        lastCommand = now;
        if (!shown) {
            open();
            return;
        }
        if (page == Model.Page.HOME && recipe != null) {
            resumeCooking();
            return;
        }
        if (forward) next();
        else previous();
    }

    /** Today's next meal: the first planned from two hours ago on, or the day's last. */
    private void cookToday() {
        LocalDate today = LocalDate.now(clock);
        LocalTime time = LocalTime.now(clock);
        int minutes = time.getHour() * 60 + time.getMinute() - 120;
        RecipeSource.Planned chosen = null;
        for (RecipeSource.Planned meal : plan) {
            if (!meal.date.equals(today) || meal.recipeId == null) continue;
            if (chosen == null || chosen.slotOrder < minutes) chosen = meal;
            if (meal.slotOrder >= minutes) {
                chosen = meal;
                break;
            }
        }
        if (!shown) open();
        if (chosen != null) openPlanned(chosen);
        else home();
    }

    // Reading aloud.

    @Override public synchronized void read() {
        if (host == null) return;
        String text;
        if (page == Model.Page.STEP) text = PlainText.forSpeech(currentText());
        else if (page == Model.Page.RECIPE && recipe != null) text = ingredientsForSpeech();
        else return;
        speak(text);
    }

    private void autoRead() {
        if (settings.autoRead && page == Model.Page.STEP) speak(PlainText.forSpeech(currentText()));
    }

    private String ingredientsForSpeech() {
        StringBuilder text = new StringBuilder();
        double factor = recipe.factor(servings);
        for (Recipe.Ingredient ingredient : recipe.ingredients()) {
            if (text.length() > 0) text.append(". ");
            text.append(ingredient.line(factor, strings.decimalComma));
        }
        return text.toString();
    }

    private void speak(String text) {
        if (text.isEmpty()) return;
        switch (settings.voice) {
            case TABLET:
                ui.speak(text, strings.locale);
                break;
            case HOME_ASSISTANT:
                announce(text);
                break;
            default:
                break;
        }
    }

    /** Home Assistant's voice through the kiosk's own satellite, which is how a kiosk gets a Slovenian one. */
    private void announce(String text) {
        if (settings.satellite.isEmpty()) {
            status("Pick the kiosk's assist_satellite entity under Read aloud.", true);
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("message", text);
        data.put("preannounce", false);
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "call_service");
        message.put("domain", "assist_satellite");
        message.put("service", "announce");
        message.put("service_data", data);
        message.put("target", Collections.singletonMap("entity_id", settings.satellite));
        host.haCall(message, (ok, result, error) -> {
            if (!ok) status("Home Assistant could not read it aloud: " + error, true);
        });
    }

    // Timers.

    @Override public synchronized void startTimer(String label, int seconds) {
        if (host == null) return;
        String name = label;
        if (recipe != null && page == Model.Page.STEP && !pages.isEmpty()) name = strings.stepWord + " " + (pageIndex + 1) + " · " + label;
        int id = timers.start(name, seconds, recipeId, page == Model.Page.STEP ? pageIndex : -1, clock.millis());
        if (id == 0) banner = strings.tooManyTimers;
        timersChanged();
    }

    private void startStepTimer() {
        if (recipe == null || page != Model.Page.STEP || pages.isEmpty()) return;
        Pages.Page current = pages.get(pageIndex);
        List<StepText.Run> found = StepText.timers(recipe.steps.get(current.step), currentText(), "min");
        if (!found.isEmpty()) startTimer(found.get(0).text, found.get(0).seconds);
    }

    @Override public synchronized void pauseTimer(int id) {
        if (host != null && timers.pause(id, clock.millis())) timersChanged();
    }

    @Override public synchronized void resumeTimer(int id) {
        if (host != null && timers.resume(id, clock.millis())) timersChanged();
    }

    @Override public synchronized void addToTimer(int id, int seconds) {
        if (host != null && timers.add(id, seconds, clock.millis())) timersChanged();
    }

    @Override public synchronized void cancelTimer(int id) {
        if (host != null && timers.cancel(id)) timersChanged();
    }

    @Override public synchronized void stopAlarm() {
        if (host == null) return;
        timers.dismissRinging();
        timersChanged();
    }

    @Override public synchronized List<Timers.Timer> timers() {
        return timers.list(clock.millis());
    }

    /** A timer started, moved, stopped or rang. */
    private void timersChanged() {
        ui.cancel(dueTask);
        long next = timers.nextDue();
        if (next >= 0) ui.post(dueTask, Math.max(1, next - clock.millis()));
        boolean ringing = timers.ringing();
        if (ringing != alarmOn) {
            alarmOn = ringing;
            ui.alarm(ringing && settings.timerSound);
        }
        updateAwake();
        updateDock();
        render();
        publishState();
    }

    /** Runs on the main thread when the next timer is due. */
    private void due() {
        synchronized (this) {
            if (host == null) return;
            try {
                List<Timers.Timer> rang = timers.due(clock.millis());
                if (!rang.isEmpty()) {
                    // The screen must show it. Holding also wakes a sleeping screen.
                    guardCommand("stopScreensaver");
                    fire("timerDone");
                }
                timersChanged();
            } catch (RuntimeException sessionEnded) {
                // KS revokes the host just before stop, and an exception here would end the app.
            }
        }
    }

    // The screen hold.

    @Override public synchronized void touched() {
        if (host == null) return;
        lastTouch = clock.millis();
        if (idleExpired) {
            idleExpired = false;
            updateAwake();
        }
    }

    /** Held while a recipe is up, a timer rings or a KS page opened from here may be up, until the idle limit. */
    private void updateAwake() {
        if (settings == null) return;
        boolean onRecipe = shown && page != Model.Page.HOME;
        boolean want = settings.keepAwake && !idleExpired && (onRecipe || linkOpen);
        if (timers.ringing()) want = true;
        boolean was = awake.held();
        awake.hold(want);
        ui.keepScreenOn(want);
        if (want && !was) {
            ui.cancel(idleTask);
            ui.post(idleTask, settings.awakeMinutes * 60_000L);
        } else if (!want) {
            ui.cancel(idleTask);
        }
    }

    /** Runs on the main thread: a forgotten recipe must not keep the screen on all night. */
    private void idleCheck() {
        synchronized (this) {
            if (host == null || settings == null) return;
            try {
                long limit = settings.awakeMinutes * 60_000L;
                long left = lastTouch + limit - clock.millis();
                if (left > 0) {
                    ui.post(idleTask, left);
                } else {
                    idleExpired = true;
                    linkOpen = false;
                    updateAwake();
                }
            } catch (RuntimeException sessionEnded) {
                // As above.
            }
        }
    }

    /** Runs on the main thread: keeps the meal plan and Home Assistant's view of it current. */
    private void planTick() {
        synchronized (this) {
            if (host == null) return;
            try {
                loadPlan();
                ui.post(planTask, PLAN_REFRESH_MS);
            } catch (RuntimeException sessionEnded) {
                // As above.
            }
        }
    }

    @Override public synchronized void openFromDock() {
        if (host == null) return;
        if (linkOpen) {
            linkOpen = false;
            guardCommand("hideOverlayPage");
        }
        open();
    }

    @Override public synchronized void problem(String message) {
        status(message, true);
    }

    @Override public Map<String, String> imageHeaders(String url) {
        RecipeSource current;
        synchronized (this) {
            current = source;
        }
        return current == null ? Collections.<String, String>emptyMap() : current.imageHeaders(url);
    }

    // Finishing.

    @Override public synchronized void logCooked() {
        final RecipeSource current = source;
        if (host == null || current == null || recipe == null || logged || logging) return;
        final String id = recipe.id;
        final double amount = servings;
        logging = true;
        background(() -> {
            RecipeSource.SourceException problem = null;
            try {
                current.cooked(id, amount);
            } catch (RecipeSource.SourceException failed) {
                problem = failed;
            }
            synchronized (this) {
                if (host == null) return;
                logging = false;
                if (problem == null) {
                    logged = true;
                } else {
                    failed(problem);
                }
                if (page == Model.Page.FINISH) changed();
            }
        });
    }

    @Override public synchronized void endCooking() {
        if (host == null) return;
        boolean wasLink = linkOpen;
        endSession();
        page = Model.Page.HOME;
        banner = null;
        if (wasLink) guardCommand("hideOverlayPage");
        if (shown) close();
        else updateDock();
        updateAwake();
        publishState();
        changed();
    }

    @Override public synchronized void backToPlan() {
        if (host == null) return;
        endSession();
        page = Model.Page.HOME;
        banner = null;
        ui.silence();
        updateDock();
        updateAwake();
        publishState();
        changed();
    }

    // Commands and triggers.

    private void guardCommand(String command) {
        host.executeCommand(command, NONE, (ok, data, error) -> {});
    }

    private void fire(String trigger) {
        try {
            host.fireTrigger(trigger);
        } catch (IllegalStateException | UnsupportedOperationException | IllegalArgumentException unavailable) {
            // A gesture mapped to it simply does not run.
        }
    }

    // What the view shows.

    private void changed() {
        version++;
        render();
    }

    private void render() {
        if (settings != null) ui.render(model());
    }

    private String currentText() {
        if (recipe == null || pages.isEmpty()) return "";
        return Pages.text(recipe, pages.get(Math.min(pageIndex, pages.size() - 1)), recipe.factor(servings), strings.decimalComma);
    }

    private String peek(int index) {
        if (recipe == null || index < 0 || index >= pages.size()) return "";
        return Pages.text(recipe, pages.get(index), recipe.factor(servings), strings.decimalComma);
    }

    private Model model() {
        LocalDate today = LocalDate.now(clock);
        double factor = recipe == null ? 1 : recipe.factor(servings);
        List<Model.Day> days = new ArrayList<>();
        List<RecipeSource.Planned> day = new ArrayList<>();
        days.add(new Model.Day(strings.today, day));
        LocalDate at = today;
        for (RecipeSource.Planned meal : plan) {
            if (meal.date.isBefore(today)) continue;
            if (!meal.date.equals(at)) {
                at = meal.date;
                day = new ArrayList<>();
                days.add(new Model.Day(strings.day(at, today), day));
            }
            day.add(meal);
        }
        int pageNumber = 0;
        String stepTitle = "";
        List<StepText.Run> runs = Collections.emptyList();
        StepText.Run ownTimer = null;
        List<Recipe.Ingredient> stepIngredients = Collections.emptyList();
        String readyAround = "";
        if (recipe != null && !pages.isEmpty()) {
            Pages.Page current = pages.get(Math.min(pageIndex, pages.size() - 1));
            Recipe.Step step = recipe.steps.get(current.step);
            pageNumber = pageIndex + 1;
            stepTitle = step.title;
            String text = currentText();
            runs = StepText.runs(text);
            if (current.part == 0) {
                stepIngredients = step.ingredients;
                if (step.seconds > 0) {
                    boolean named = false;
                    for (StepText.Run run : runs) named |= run.seconds == step.seconds;
                    if (!named) ownTimer = StepText.timers(step, "", "min").get(0);
                }
            }
            long ready = cooking ? StepText.readyAt(sessionStart, recipe) : -1;
            if (ready > 0) {
                LocalTime time = java.time.Instant.ofEpochMilli(ready).atZone(clock.getZone()).toLocalTime();
                readyAround = String.format(strings.readyAround, strings.time(time));
            }
        }
        boolean canLog = settings.markCooked == Settings.MarkCooked.ASK && recipe != null && !logged;
        return new Model(page, version, strings, settings.textSize, banner, strings.date(today), settings.configured(), days, planLoading,
            query, order, Collections.unmodifiableList(new ArrayList<>(recipes)), recipesLoading, recipesMore,
            recipe, recipeName, recipeImage, recipeLoading, servings, factor, copyChecked(),
            recipe == null ? Collections.<Recipe.Ingredient>emptyList() : recipe.ingredients(), cooking ? pageIndex + 1 : 0, pageNumber, pages.size(), stepTitle, runs, ownTimer,
            stepIngredients, peek(pageIndex - 1), peek(pageIndex + 1), readyAround, settings.voice != Settings.Voice.OFF, canLog, logged);
    }

    private Set<Recipe.Ingredient> copyChecked() {
        Set<Recipe.Ingredient> copy = Collections.newSetFromMap(new IdentityHashMap<Recipe.Ingredient, Boolean>());
        copy.addAll(checked);
        return copy;
    }

    // Entities.

    private void publishCatalog() {
        published.clear();
        host.publishSelect(VIEW, "Cook Mode view", new String[] {CLOSED, OPEN}, CLOSED);
        host.publishBinarySensor("cooking", "Cooking", "running", false);
        host.publishBinarySensor("timer_ringing", "Timer ringing", "sound", false);
        Map<String, Object> count = new LinkedHashMap<>();
        count.put("stateClass", "measurement");
        count.put("accuracyDecimals", 0);
        host.publishSensor("timers", "Timers", count, 0.0);
        host.publishTextSensor("recipe", "Recipe", "");
        host.publishTextSensor("step", "Step", "");
        host.publishTextSensor("next_timer", "Next timer", "");
        host.publishTextSensor("today_meals", "Today's meals", "");
    }

    /** Entities change only when their value does. Timers tick in the view, never here. */
    private void publishState() {
        if (host == null) return;
        if (differs("view", shown ? OPEN : CLOSED)) host.publishSelect(VIEW, "Cook Mode view", new String[] {CLOSED, OPEN}, shown ? OPEN : CLOSED);
        if (differs("cooking", String.valueOf(cooking))) host.publishBinarySensor("cooking", "Cooking", "running", cooking);
        boolean ringing = timers.ringing();
        if (differs("timer_ringing", String.valueOf(ringing))) host.publishBinarySensor("timer_ringing", "Timer ringing", "sound", ringing);
        List<Timers.Timer> list = timers.list(clock.millis());
        if (differs("timers", String.valueOf(list.size()))) {
            Map<String, Object> count = new LinkedHashMap<>();
            count.put("stateClass", "measurement");
            count.put("accuracyDecimals", 0);
            host.publishSensor("timers", "Timers", count, (double) list.size());
        }
        String name = cooking && recipe != null ? recipe.name : "";
        if (differs("recipe", name)) host.publishTextSensor("recipe", "Recipe", clip(name));
        String step = cooking && !pages.isEmpty() ? (pageIndex + 1) + "/" + pages.size() + ": " + PlainText.forSpeech(currentText()) : "";
        if (differs("step", step)) host.publishTextSensor("step", "Step", clip(step));
        String next = "";
        for (Timers.Timer timer : list) {
            if (timer.state != Timers.State.RUNNING) continue;
            LocalTime at = java.time.Instant.ofEpochMilli(clock.millis() + timer.left * 1000L).atZone(clock.getZone()).toLocalTime();
            next = timer.label + " " + strings.time(at);
            break;
        }
        if (differs("next_timer", next)) host.publishTextSensor("next_timer", "Next timer", clip(next));
        StringBuilder meals = new StringBuilder();
        LocalDate today = LocalDate.now(clock);
        for (RecipeSource.Planned meal : plan) {
            if (!meal.date.equals(today)) continue;
            if (meals.length() > 0) meals.append("; ");
            if (!meal.slot.isEmpty()) meals.append(meal.slot).append(": ");
            meals.append(meal.title);
        }
        if (differs("today_meals", meals.toString())) host.publishTextSensor("today_meals", "Today's meals", clip(meals.toString()));
    }

    private boolean differs(String key, String value) {
        if (value.equals(published.get(key))) return false;
        published.put(key, value);
        return true;
    }

    /** Text sensors hold at most 512 characters. */
    private static String clip(String text) {
        return text.length() > 512 ? text.substring(0, 512) : text;
    }

    boolean cooking() {
        return cooking;
    }
}
