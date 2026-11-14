// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
    /** Makes the source for the settings' app. */
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
    /** A gesture or a clap can fire twice. The same command counts at most this often. */
    static final long COMMAND_GAP_MS = 700L;
    /** How long after the last change the state is saved for a restart. A held plus must not write with every step. */
    static final long SAVE_AFTER_MS = 1_000L;
    /** How long a timer's end goes unchecked. A tablet with no battery can start with its clock behind, and learn the time later. */
    static final long DUE_CHECK_MS = 50_000L;
    /** A timer that ended this long ago while the app was down still rings. An older one is only mentioned. */
    static final long LATE_RING_MS = 30 * 60_000L;
    /** A recipe left this long ago, by a restart, is not picked up again. */
    static final long SESSION_KEPT_MS = 12 * 60 * 60_000L;
    /** How often to ask KS again which kiosk this is, and how many times. Until it says, nothing saved is touched. */
    static final long DEVICE_AGAIN_MS = 10_000L;
    static final int DEVICE_TRIES = 6;
    /** How often a session from before a restart asks for its recipe again. The server may be starting up too. */
    static final long RESUME_AGAIN_MS = 60_000L;
    /** The same after the server answered with an error, which Norish counts against its key. */
    static final long RESUME_SLOWLY_MS = 15 * 60_000L;
    /** A kiosk before 2026.11.1 refuses to read while it is reading. How often to try the newest text, and how many times. */
    static final long SPEECH_AGAIN_MS = 1_500L;
    static final int SPEECH_TRIES = 80;
    /** Home Assistant turns a longer state into unknown. */
    static final int STATE_MOST = 255;
    /** The dock keeps one size: a wrapped overlay that resizes shows stale frames on slow tablets. */
    static final int DOCK_WIDTH = 320;
    static final int DOCK_HEIGHT = 68;
    private static final Map<String, Object> NONE = Collections.emptyMap();

    /** What raised the banner, which is what may clear it. */
    private enum Trouble { PLAN, LIST, RECIPE, LOG, TIMERS }

    /** A session set aside while the recipe replacing it loads. If that recipe does not come, this one comes back. */
    private static final class Stash {
        final Recipe recipe;
        final String id;
        final String image;
        final double servings;
        final List<Pages.Page> pages;
        final int pageIndex;
        final long started;
        final Set<Recipe.Ingredient> checked;
        final boolean logged;
        final boolean logFailed;

        Stash(Recipe recipe, String id, String image, double servings, List<Pages.Page> pages, int pageIndex, long started, Set<Recipe.Ingredient> checked, boolean logged, boolean logFailed) {
            this.recipe = recipe;
            this.id = id;
            this.image = image;
            this.servings = servings;
            this.pages = pages;
            this.pageIndex = pageIndex;
            this.started = started;
            this.checked = checked;
            this.logged = logged;
            this.logFailed = logFailed;
        }
    }

    private final Ui ui;
    private final Clock clock;
    private final Executor net;
    private final Sources sources;
    private final Awake awake;
    private final Timers timers = new Timers();
    private final Runnable dueTask = this::due;
    private final Runnable planTask = this::planTick;
    private final Runnable midnightTask = this::midnight;
    private final Runnable idleTask = this::idleCheck;
    private final Runnable saveTask = this::save;
    private final Runnable deviceTask = this::askDevice;
    private final Runnable resumeTask = this::resumeAgain;
    private final Runnable speechTask = this::speechAgain;

    private PluginHost host;
    private Settings settings;
    private Strings strings = Strings.ENGLISH;
    private RecipeSource source;
    private boolean shown;
    private boolean dockShown;
    private Model.Page page = Model.Page.HOME;
    private long version;
    private String banner;
    private Trouble bannerOwner;

    private List<RecipeSource.Planned> plan = Collections.emptyList();
    private int planGeneration;
    private boolean planLoading;
    private long planReadAt = -1;
    /** When the plan was last asked for, whether it came or not. */
    private long planAskedAt = -1;
    /** Whether the plugin's page now says something is wrong. */
    private boolean statusError;
    /** Why the last read of the plan failed, until one comes in. */
    private RecipeSource.SourceException planProblem;
    /** The earliest day the server may have been on when the plan in hand was read, which says how far that plan reaches. */
    private LocalDate planFrom;
    /** Cook today's meal came while the plan was old: the page it came on, until the plan read again is in. */
    private Model.Page chooseOn;
    /** And the recipe open then. */
    private String chooseWith = "";

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
    /** The last try to record the meal failed, so it is offered again whatever the setting. */
    private boolean logFailed;
    /** A KS page was opened from here. KS does not say when it closes, so the hold on it ends with the idle limit. */
    private boolean linkOpen;

    private long lastTouch;
    private boolean idleExpired;
    private boolean idlePosted;
    private long lastCommand;
    private String lastCommandName = "";
    private boolean alarmOn;
    /** A gesture or the stop word silenced the alarm, and the ringing timer is still on show. */
    private boolean alarmMuted;
    /** A timer has just started ringing. It gets its full time of sound, even if an earlier one's has given up. */
    private boolean restartSound;
    /** What a yes to the question on show would do, or null when nothing is asked. */
    private Runnable confirmed;
    private String confirmText;
    private String confirmLabel;
    /** The kiosk's configured name, from KS. */
    private String deviceName = "";
    /** The ESPHome node that took an announcement, and whether something may still be being read through it. */
    private String assistantNode = "";
    private boolean spoke;
    private boolean noStopAction;
    /** The text that should be read now, and where it was refused because the kiosk still reads the last one. */
    private String speech;
    private List<String> speechNodes = Collections.emptyList();
    private int speechNode;
    private int speechTries;
    /** False until KS has said which kiosk this is. Until then the saved state must stay as it is. */
    private boolean deviceKnown;
    private int deviceTries;
    /** What was saved before this start, until KS has said whether it is this kiosk's. */
    private Snapshot savedAtStart;
    /** The state last saved, without its time, to save only when it changes. */
    private String savedKey = "";
    /** The session to pick up when its recipe has loaded after a restart, or null. */
    private Snapshot resume;
    /** The session a loading recipe is about to replace, or null. */
    private Stash stash;
    /** The same for a session from before a restart, which has no recipe yet to set aside. */
    private Snapshot waitingAside;
    /** How many timers ended too long ago to ring after the restart, which the plugin's page goes on saying. */
    private int missedTimers;
    /** What Test connection is using, so Cancel and stop can reach it. */
    private RecipeSource probe;
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
        publishCatalog();
        configure(values);
        host.subscribe("screensaver.state");
        host.subscribe("screensaver.countdown");
        // The word KS listens for to stop its own timers silences ours too.
        host.subscribe("stopword.detected");
        ui.post(planTask, planEvery());
        ui.post(midnightTask, untilMidnight());
        Object saved = values.get("resumeState");
        boolean anySaved = saved instanceof String && !((String) saved).isEmpty();
        // What is stored must be replaced the first time, whatever it holds.
        savedKey = anySaved ? "?" : "";
        savedAtStart = anySaved ? Snapshot.decode((String) saved) : null;
        askDevice();
    }

    /** Which kiosk is this? A busy KS can fail to say while it starts, so it is asked again a few times. */
    private void askDevice() {
        synchronized (this) {
            if (host == null || deviceKnown) return;
            try {
                host.executeCommand("getDeviceInfo", NONE, (ok, data, error) -> deviceInfo(ok, data));
            } catch (RuntimeException refused) {
                deviceInfo(false, null);
            }
        }
    }

    /** KS says which kiosk this is: for Home Assistant's voice, and whether the saved state is this kiosk's. */
    private synchronized void deviceInfo(boolean ok, Object info) {
        if (host == null || deviceKnown) return;
        try {
            if (!ok) {
                // After the last try saving stays off for this session: what is stored may be all there is.
                if (++deviceTries < DEVICE_TRIES) ui.post(deviceTask, DEVICE_AGAIN_MS);
                return;
            }
            deviceKnown = true;
            deviceName = Json.text(Json.object(info).get("name"));
            Snapshot saved = savedAtStart;
            savedAtStart = null;
            if (saved != null && saved.device.equals(deviceName)) restore(saved);
        } catch (RuntimeException sessionEnded) {
            // KS revokes the host before stop, and this may be its last answer.
        }
    }

    /** What was going on before a restart. Timers that ended meanwhile ring now, if that is not too long ago. */
    private void restore(Snapshot saved) {
        long now = clock.millis();
        int missed = 0;
        for (Snapshot.SavedTimer timer : saved.timers) {
            if (!timer.paused && now - timer.value > LATE_RING_MS) missed++;
            else timers.restore(timer.label, timer.total, timer.paused, timer.value, timer.recipe, timer.step);
        }
        // KS may have answered late. A recipe opened in the meantime is the one that counts now.
        boolean taken = recipe != null || recipeLoading || !recipeId.isEmpty();
        if (!saved.recipe.isEmpty() && now - saved.savedAt < SESSION_KEPT_MS && source != null && !taken) {
            resume = saved;
            recipeId = saved.recipe;
            recipeName = saved.name;
            plannedServings = saved.servings;
            if (!shown) page = Model.Page.RECIPE;
            loadRecipe();
        }
        missedTimers = missed;
        timersChanged();
        if (missed > 0) report();
    }

    /** The recipe of a session from before a restart has loaded: back to where it was. */
    private void pickUp() {
        Snapshot session = resume;
        resume = null;
        ui.cancel(resumeTask);
        if (pages.isEmpty()) {
            // The recipe has lost its steps meanwhile, so that session is over.
            fire("cookingFinished");
            updateDock();
            return;
        }
        cooking = true;
        sessionStart = session.started;
        pageIndex = Math.max(0, Math.min(session.page, pages.size() - 1));
        // Not if the cook has moved on while it loaded: the card on the home page leads back.
        if (page == Model.Page.RECIPE) page = Model.Page.STEP;
        updateAwake();
        updateDock();
        publishState();
    }

    /** A session from before a restart still has no recipe: ask again, unless an answer is on its way. */
    private void retryResume() {
        if (resume != null && !recipeLoading) loadRecipe();
    }

    /** Runs on the main thread while a session from before a restart waits for its recipe. */
    private void resumeAgain() {
        synchronized (this) {
            if (host != null) retryResume();
        }
    }

    private void saveSoon() {
        ui.cancel(saveTask);
        ui.post(saveTask, SAVE_AFTER_MS);
    }

    /** Runs on the main thread a moment after a change: what a restart must not lose. */
    private void save() {
        synchronized (this) {
            if (host == null || !deviceKnown) return;
            try {
                String key = snapshot(0);
                if (key.equals(savedKey)) return;
                savedKey = key;
                host.updateSettings(Collections.<String, Object>singletonMap("resumeState", snapshot(clock.millis())));
            } catch (RuntimeException | LinkageError unavailable) {
                // KS revokes the host just before stop. Only the recovery from a restart is lost.
            }
        }
    }

    private String snapshot(long at) {
        List<Timers.Timer> list = timers.list(clock.millis());
        boolean session = cooking && recipe != null;
        // A session waiting for its recipe, or set aside for another, has not ended.
        Snapshot waiting = resume != null ? resume : waitingAside;
        if (list.isEmpty() && !session && waiting == null && stash == null) return "";
        List<Snapshot.SavedTimer> saved = new ArrayList<>();
        for (Timers.Timer timer : list) saved.add(new Snapshot.SavedTimer(timer.label, timer.total, timer.state == Timers.State.PAUSED, timer.value, timer.recipe, timer.step));
        if (waiting != null) return new Snapshot(deviceName, at, waiting.recipe, waiting.name, waiting.servings, waiting.page, waiting.started, saved).encode();
        if (stash != null) return new Snapshot(deviceName, at, stash.id, stash.recipe.name, stash.servings, stash.pageIndex, stash.started, saved).encode();
        return new Snapshot(deviceName, at, session ? recipeId : "", session ? recipeName : "", servings, pageIndex, sessionStart, saved).encode();
    }

    synchronized void configure(Map<String, Object> values) {
        Settings previous = settings;
        settings = new Settings(values);
        strings = Strings.of(settings.language, settings.app);
        if (host == null) return;
        ui.sound(settings.tone, settings.mediaStream, settings.ringMinutes);
        if (previous != null && !settings.kioskNode.equals(previous.kioskNode)) assistantNode = "";
        updateSound();
        boolean changedServer = !settings.sameServer(previous);
        if (changedServer) {
            RecipeSource old = source;
            if (old != null) old.cancel();
            source = settings.configured() ? sources.create(settings) : null;
            plan = Collections.emptyList();
            planReadAt = -1;
            planAskedAt = -1;
            planFrom = null;
            planProblem = null;
            chooseOn = null;
            recipes = new ArrayList<>();
            recipesPage = 0;
            banner = null;
            planGeneration++;
            listGeneration++;
            planLoading = false;
            recipesLoading = false;
            // What is on its way came through the connection just closed, which is no failure of anything.
            recipeGeneration++;
            recipeLoading = false;
            if (previous != null && settings.otherServer(previous)) {
                // The recipe in hand means nothing on another server.
                endSession();
                page = Model.Page.HOME;
            } else if (recipe == null && !recipeId.isEmpty()) {
                loadRecipe();
            }
            if (source != null) {
                loadPlan();
                if (shown) loadRecipes(1);
            }
        } else if (previous != null && previous.planDays != settings.planDays) {
            loadPlan();
        }
        if (dockShown && previous != null && (!settings.dockCorner.equals(previous.dockCorner) || settings.dockOnTop != previous.dockOnTop)) showDock(OverlaySpec.NONE);
        if (recipe != null && previous != null && previous.splitSteps != settings.splitSteps) {
            pages = Pages.of(recipe, settings.splitSteps);
            pageIndex = Math.min(pageIndex, Math.max(0, pages.size() - 1));
        }
        report();
        updateAwake();
        updateDock();
        changed();
    }

    /** KS revokes the host and removes the overlays and entities before stop, so nothing is hidden here. */
    synchronized void stop() {
        ui.cancel(dueTask);
        ui.cancel(planTask);
        ui.cancel(midnightTask);
        ui.cancel(idleTask);
        ui.cancel(saveTask);
        ui.cancel(deviceTask);
        ui.cancel(resumeTask);
        ui.cancel(speechTask);
        awake.stop();
        if (source != null) source.cancel();
        if (probe != null) probe.cancel();
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
        switch (command) {
            case "openCookMode": open(); break;
            case "closeCookMode": close(); break;
            case "toggleCookMode": if (firstTime(command)) toggleView(); break;
            case "cookToday": if (firstTime(command)) cookToday(); break;
            case "nextStep": if (handsFree(command)) stepCommand(true); break;
            case "previousStep": if (handsFree(command)) stepCommand(false); break;
            case "readStep": if (handsFree(command)) read(); break;
            case "startStepTimer": if (handsFree(command)) startStepTimer(); break;
            case "addMinute": if (firstTime(command)) addMinute(); break;
            case "stopAlarm": if (firstTime(command)) silenceAlarm(); break;
            case "endCooking": endCooking(); break;
            default: throw new IllegalArgumentException("Unknown command: " + command);
        }
        // Only now: holding again dismisses a screensaver, and the command must see that one was up. Close wakes nothing.
        if (idleExpired) {
            idleExpired = false;
            updateAwake();
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
            if (timers.ringing()) silenceAlarm();
            quiet();
        } else if ("settings.button".equals(event)) {
            if ("testConnection".equals(payload.get("id"))) testConnection(String.valueOf(payload.get("run")));
            else if ("testAlarm".equals(payload.get("id"))) testAlarm(String.valueOf(payload.get("run")));
        } else if ("settings.cancel".equals(event)) {
            if (probe != null) probe.cancel();
        }
    }

    // Opening and closing.

    private void open() {
        if (shown) {
            // It may be up under a screensaver, where nobody can see it.
            awake.reveal();
        } else {
            lastTouch = clock.millis();
            idleExpired = false;
            guardCommand("stopScreensaver");
            render();
            // First, because KS can refuse it, and then the card stays and the next try can still open.
            host.showOverlay(MAIN, OverlaySpec.fullScreen().enter(OverlaySpec.FADE).exit(OverlaySpec.FADE).transitionDuration(150), ui.main(this));
            shown = true;
            hideDockNow();
            // A read already on its way is waited for, not started over.
            if (!planLoading && (planReadAt < 0 || clock.millis() - planReadAt > PLAN_STALE_MS)) loadPlan();
            if (recipesPage == 0 && !recipesLoading) loadRecipes(1);
            retryResume();
            // The panel shows what it is loading as soon as it opens.
            changed();
        }
        updateAwake();
        publishState();
    }

    /** Under a screensaver the view cannot be seen, so toggling shows it rather than closing what nobody sees. */
    private void toggleView() {
        if (shown && !awake.covered()) close();
        else open();
    }

    /** The main view went away, by the close button or by back. The session stays until End cooking, or the meal is ready. */
    private void closed() {
        shown = false;
        chooseOn = null;
        quiet();
        if (page == Model.Page.FINISH) {
            endSession();
            page = Model.Page.HOME;
            clearBanner();
            changed();
        }
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
        boolean want = !shown && (timers.any() || cooking || resume != null);
        ui.renderDock(cooking && recipe != null ? recipe.name + cookingProgress() : resume != null ? recipeName : "", strings);
        if (want && !dockShown) showDock(OverlaySpec.SLIDE);
        else if (!want && dockShown) hideDockNow();
    }

    private void showDock(String enter) {
        try {
            host.showOverlay(DOCK, OverlaySpec.at(settings.dockCorner).size(DOCK_WIDTH, DOCK_HEIGHT).inset(16).closeOnBack(false).onTop(settings.dockOnTop).enter(enter).exit(OverlaySpec.FADE), ui.dock(this));
            dockShown = true;
        } catch (RuntimeException refused) {
            // KS limits how often overlays show. The next change tries again.
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
        planAskedAt = clock.millis();
        final LocalDate serverDay = serverDay();
        final LocalDate first = LocalDate.now(clock);
        final LocalDate last = first.plusDays(settings.planDays - 1);
        background(() -> {
            List<RecipeSource.Planned> read = null;
            RecipeSource.SourceException problem = null;
            try {
                read = current.mealPlan(first, last);
            } catch (RecipeSource.SourceException failed) {
                problem = failed;
            } catch (RuntimeException | Error bug) {
                problem = unexpected(bug);
            }
            synchronized (this) {
                if (host == null || generation != planGeneration) return;
                planLoading = false;
                if (read != null) {
                    plan = read;
                    planReadAt = clock.millis();
                    planFrom = serverDay;
                    planProblem = null;
                    cleared(Trouble.PLAN);
                    report();
                } else {
                    planProblem = problem;
                    failed(problem, Trouble.PLAN);
                }
                publishState();
                if (chooseOn != null) {
                    // Cook today's meal waited for this plan, or for the one in memory when none came, unless the cook has
                    // gone elsewhere meanwhile.
                    boolean still = shown && page == chooseOn && recipeId.equals(chooseWith);
                    chooseOn = null;
                    if (still) chooseToday();
                }
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
            } catch (RuntimeException | Error bug) {
                problem = unexpected(bug);
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
                    cleared(Trouble.LIST);
                } else {
                    failed(problem, Trouble.LIST);
                }
                if (page == Model.Page.HOME) changed();
            }
        });
        if (page == Model.Page.HOME) version++;
    }

    /**
     * A call failed: the panel says it in its language, the plugin's page says exactly why. The plan and the list are read
     * in the background too, and a blip in those has no place over a recipe that is already here.
     */
    private void failed(RecipeSource.SourceException problem, Trouble from) {
        if (problem == null) return;
        status(problem.getMessage(), true);
        if ((from == Trouble.PLAN || from == Trouble.LIST) && page != Model.Page.HOME) return;
        bannerOwner = from;
        switch (problem.kind) {
            case AUTH: banner = problem.passing ? strings.problemAuthPassing : strings.problemAuth; break;
            case HOST: banner = strings.problemHost; break;
            case NETWORK: banner = strings.problemNetwork; break;
            case NOT_FOUND: banner = strings.recipeMissing; break;
            case PRIVATE: banner = strings.problemPrivate; break;
            case READ_ONLY: banner = strings.problemReadOnly; break;
            case NOT_API: banner = strings.problemNotApi; break;
            case LOCAL_NAME: banner = strings.problemLocalName; break;
            case TLS: banner = strings.problemTls; break;
            case LIMIT: banner = strings.problemLimit; break;
            default: banner = strings.problemOther;
        }
    }

    /** What raised the banner works again. */
    private void cleared(Trouble from) {
        if (bannerOwner == from) clearBanner();
    }

    private void clearBanner() {
        banner = null;
        bannerOwner = null;
    }

    private static RecipeSource.SourceException unexpected(Throwable bug) {
        return new RecipeSource.SourceException(RecipeSource.SourceException.Kind.PARSE, "Cook Mode could not read the answer: " + bug, bug);
    }

    private void status(String message, boolean error) {
        statusError = error;
        if (host != null) host.status(message.length() > 1000 ? message.substring(0, 1000) : message, error);
    }

    /** The plugin page's status line. */
    private void report() {
        String problem = settings.problem();
        String missed = missedTimers == 0 ? "" : " " + missedTimers + (missedTimers == 1 ? " timer" : " timers") + " ended long ago, while the kiosk was restarting.";
        if (problem != null) {
            status(problem, true);
        } else if (planProblem != null) {
            // No page says Connected while the plan cannot be read.
            status(planProblem.getMessage() + missed, true);
        } else if (planReadAt < 0) {
            status("Reading the meal plan." + missed, false);
        } else {
            int count = 0;
            LocalDate today = LocalDate.now(clock);
            for (RecipeSource.Planned meal : plan) if (meal.date.equals(today)) count++;
            LocalTime read = Instant.ofEpochMilli(planReadAt).atZone(clock.getZone()).toLocalTime().withSecond(0).withNano(0);
            status("Connected. " + count + (count == 1 ? " meal" : " meals") + " planned today. Read at " + read + "." + missed, false);
        }
    }

    private void testAlarm(String run) {
        ui.testAlarm((ok, message) -> {
            try {
                host().setupResult(run, ok ? "success" : "error", message);
            } catch (RuntimeException sessionEnded) {
                // The plugin stopped while the sound played.
            }
        });
    }

    private void testConnection(String run) {
        final Settings current = settings;
        background(() -> {
            RecipeSource trying;
            synchronized (this) {
                // A source already reading this server knows what it has learned of the key, which a new one would not.
                RecipeSource twin = source != null && current.sameServer(settings) ? source.twin() : null;
                trying = twin != null ? twin : sources.create(current);
                probe = trying;
            }
            try {
                if (!host().setupResult(run, "running", "Connecting to " + current.serverUrl)) return;
                host().setupResult(run, "success", trying.probe());
            } catch (RecipeSource.SourceException failed) {
                host().setupResult(run, "error", failed.getMessage());
            } catch (RuntimeException bug) {
                host().setupResult(run, "error", "Cook Mode could not test this: " + bug.getClass().getSimpleName());
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
                } catch (RuntimeException | Error failed) {
                    // Anything leaving this thread would end the app. Usually KS has just revoked the host before stop.
                    reportFailure(failed);
                }
            });
        } catch (RejectedExecutionException stopping) {
            // The plugin is stopping.
        }
    }

    private synchronized void reportFailure(Throwable failed) {
        try {
            if (host != null) status("Cook Mode: " + failed, true);
        } catch (RuntimeException sessionEnded) {
            // Nothing is left to tell.
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
        clearBanner();
        quiet();
        updateAwake();
        changed();
    }

    @Override public synchronized void search(String text) {
        if (host == null) return;
        query = text == null ? "" : text.trim();
        clearBanner();
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
        clearBanner();
        if (page == Model.Page.RECIPE && recipe == null && !recipeId.isEmpty()) loadRecipe();
        else {
            loadPlan();
            loadRecipes(1);
            retryResume();
        }
        if (page == Model.Page.FINISH && logFailed) logCooked();
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
        if (host == null) return;
        if (resume != null) {
            // It comes back at its step as soon as its recipe is here.
            page = Model.Page.RECIPE;
            retryResume();
        } else if (recipe != null) {
            page = cooking ? Model.Page.STEP : Model.Page.RECIPE;
        } else {
            return;
        }
        updateAwake();
        changed();
    }

    private void openById(String id, String name, String image, double planned) {
        // Cooking another recipe forgets this one's place, so it must be meant.
        boolean other = !id.equals(recipeId);
        if (other && cooking && recipe != null && pageIndex > 0) {
            ask(String.format(strings.replaceRecipe, recipe.name, pageIndex + 1), strings.replace, () -> openNow(id, name, image, planned));
        } else if (other && resume != null && resume.page > 0) {
            ask(String.format(strings.replaceRecipe, recipeName, resume.page + 1), strings.replace, () -> openNow(id, name, image, planned));
        } else {
            openNow(id, name, image, planned);
        }
    }

    private void openNow(String id, String name, String image, double planned) {
        clearBanner();
        page = Model.Page.RECIPE;
        // The recipe being cooked comes back as it was left.
        if (id.equals(recipeId) && recipe != null) {
            if (cooking) page = Model.Page.STEP;
            updateAwake();
            changed();
            return;
        }
        // So does one from before a restart, as soon as its recipe is here.
        if (id.equals(recipeId) && resume != null) {
            retryResume();
            updateAwake();
            changed();
            return;
        }
        // The one set aside, before what was to replace it has even come.
        if (stash != null && id.equals(stash.id)) {
            forget();
            comeBack();
            changed();
            return;
        }
        if (waitingAside != null && id.equals(waitingAside.recipe)) {
            forget();
            waitBack();
            retryResume();
            updateAwake();
            changed();
            return;
        }
        // Set aside, not ended: this recipe may not come, and the recipe in hand cannot be read again while the server is away.
        if (cooking && recipe != null) stash = new Stash(recipe, recipeId, recipeImage, servings, pages, pageIndex, sessionStart, copyChecked(), logged, logFailed);
        if (resume != null) waitingAside = resume;
        forget();
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
            } catch (RuntimeException | Error bug) {
                problem = unexpected(bug);
            }
            synchronized (this) {
                if (host == null || generation != recipeGeneration) return;
                recipeLoading = false;
                if (read != null) {
                    boolean replaced = stash != null || waitingAside != null;
                    stash = null;
                    waitingAside = null;
                    recipe = read;
                    recipeName = read.name;
                    if (!read.image.isEmpty()) recipeImage = read.image;
                    servings = plannedServings > 0 ? plannedServings : read.servings;
                    pages = Pages.of(read, settings.splitSteps);
                    pageIndex = 0;
                    checked.clear();
                    cleared(Trouble.RECIPE);
                    if (replaced) fire("cookingFinished");
                    if (resume != null) pickUp();
                } else {
                    recipeFailed(problem);
                }
                changed();
            }
            // The app's recently viewed list, where it keeps one. A read-only token cannot, which costs nothing here.
            if (read != null && tell) {
                try {
                    current.viewed(id);
                } catch (RecipeSource.SourceException ignored) {
                    // Only the app's own list misses it.
                }
            }
        });
    }

    /** The recipe did not come. A session from before a restart tries again later, and one set aside comes back. */
    private void recipeFailed(RecipeSource.SourceException problem) {
        if (resume != null && problem != null && problem.kind == RecipeSource.SourceException.Kind.NOT_FOUND) {
            // Gone from the server, so there is nothing to come back to.
            endSession();
            if (page == Model.Page.RECIPE) page = Model.Page.HOME;
            updateAwake();
            updateDock();
        } else if (resume != null) {
            ui.cancel(resumeTask);
            // A server that is not up or not ready yet costs nothing to ask again soon. One that answers with an error may well count it.
            boolean answered = problem != null && (problem.kind == RecipeSource.SourceException.Kind.SERVER || problem.kind == RecipeSource.SourceException.Kind.PARSE)
                && !problem.passing && source != null && source.countsRequests();
            ui.post(resumeTask, answered ? RESUME_SLOWLY_MS : RESUME_AGAIN_MS);
        } else if (stash != null) {
            comeBack();
        } else if (waitingAside != null) {
            waitBack();
        }
        failed(problem, Trouble.RECIPE);
    }

    /** The session from before a restart goes back to waiting for its own recipe. */
    private void waitBack() {
        resume = waitingAside;
        waitingAside = null;
        recipeId = resume.recipe;
        recipeName = resume.name;
        recipeImage = "";
        plannedServings = resume.servings;
        ui.cancel(resumeTask);
        ui.post(resumeTask, RESUME_AGAIN_MS);
        updateDock();
    }

    private void comeBack() {
        Stash left = stash;
        stash = null;
        recipe = left.recipe;
        recipeId = left.id;
        recipeName = left.recipe.name;
        recipeImage = left.image;
        servings = left.servings;
        pages = left.pages;
        pageIndex = left.pageIndex;
        sessionStart = left.started;
        checked.clear();
        checked.addAll(left.checked);
        logged = left.logged;
        logFailed = left.logFailed;
        cooking = true;
        if (page == Model.Page.RECIPE) page = Model.Page.STEP;
        updateAwake();
        updateDock();
    }

    /** Forget the recipe being cooked. Timers keep running: they name their own recipe. */
    private void endSession() {
        boolean was = cooking || resume != null || stash != null || waitingAside != null;
        forget();
        stash = null;
        waitingAside = null;
        if (was) fire("cookingFinished");
    }

    private void forget() {
        quiet();
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
        logFailed = false;
        linkOpen = false;
        resume = null;
        ui.cancel(resumeTask);
        confirmed = null;
        confirmText = null;
        confirmLabel = null;
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
        quiet();
        updateAwake();
        changed();
        autoRead();
    }

    @Override public synchronized void openInApp() {
        if (host == null || recipe == null || recipe.webUrl.isEmpty()) return;
        final String address = recipe.webUrl;
        // Plugin overlays cover KS pages, so the view goes first.
        close();
        linkOpen = true;
        updateAwake();
        updateDock();
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
                quiet();
                changed();
                autoRead();
            } else {
                page = Model.Page.FINISH;
                quiet();
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
                quiet();
                changed();
                autoRead();
            } else {
                page = Model.Page.RECIPE;
                quiet();
                updateAwake();
                changed();
            }
        } else if (page == Model.Page.RECIPE) {
            home();
        }
    }

    /** False for the echo of a command that just ran: a gesture or a clap can fire twice. */
    private boolean firstTime(String command) {
        long now = clock.millis();
        if (command.equals(lastCommandName) && now - lastCommand < COMMAND_GAP_MS) return false;
        lastCommandName = command;
        lastCommand = now;
        return true;
    }

    /** A command given without touching the screen. A ringing timer takes it first, instead of what it asks for. */
    private boolean handsFree(String command) {
        if (!firstTime(command)) return false;
        if (!timers.ringing()) return true;
        silenceAlarm();
        return false;
    }

    /** nextStep and previousStep. With the view closed or under a screensaver the first one only brings it back, so nothing moves unseen. */
    private void stepCommand(boolean forward) {
        if (!shown || awake.covered()) {
            open();
            return;
        }
        if (page == Model.Page.HOME) {
            if (recipe != null || resume != null) resumeCooking();
            else if (forward) cookToday();
            return;
        }
        if (forward) next();
        else previous();
    }

    /**
     * Today's next meal: the first planned from two hours ago on, or the day's last. From a plan read just now, since the
     * household may have changed tonight's dinner since the last read, which can be an hour ago.
     */
    private void cookToday() {
        boolean old = planReadAt < 0 || clock.millis() - planReadAt > PLAN_STALE_MS;
        open();
        if (old && source != null) {
            // Opening the panel reads the plan when it was closed. When it was up already, this does.
            if (!planLoading) loadPlan();
            chooseOn = page;
            chooseWith = recipeId;
            return;
        }
        chooseToday();
    }

    private void chooseToday() {
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

    /**
     * Home Assistant's voice, which is how a kiosk gets a Slovenian one, through the kiosk's own announce action. A plain
     * assist_satellite.announce would lay KS's announcement card over the step being read.
     */
    private void announce(String text) {
        List<String> nodes = announceNodes();
        if (nodes.isEmpty()) {
            status("Set the kiosk's ESPHome node under Read aloud, such as ks_kitchen.", true);
            return;
        }
        spoke = true;
        speech = text;
        speechTries = 0;
        ui.cancel(speechTask);
        announce(text, nodes, 0);
    }

    private void announce(String text, List<String> nodes, int index) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("message", text);
        data.put("url", "");
        data.put("volume", 0);
        data.put("repeat", 0);
        data.put("repeat_pause", 0);
        data.put("chime", false);
        data.put("chime_file", "");
        data.put("tts_engine", "");
        data.put("tts_language", "");
        data.put("tts_voice", "");
        data.put("audio_only", true);
        try {
            host.haCall(espHomeCall(nodes.get(index) + "_announce", data), (ok, result, error) -> announced(ok, error, text, nodes, index));
        } catch (RuntimeException refused) {
            // KS limits calls to Home Assistant. The step is still on the screen.
        }
    }

    private synchronized void announced(boolean ok, String error, String text, List<String> nodes, int index) {
        if (host == null) return;
        try {
            String answer = error == null ? "" : error;
            if (ok) assistantNode = nodes.get(index);
            // Not if the cook has moved on while that was asked: only the next text is wanted now.
            else if (noSuchAction(answer) && index + 1 < nodes.size()) {
                if (text == speech) announce(text, nodes, index + 1);
            } else if (answer.contains("in a call")) readLater(text, nodes, index);
            // The answer can come later than the limit while it still speaks, so it must not be read twice.
            else if (!answer.startsWith("timeout")) status("Home Assistant could not read it aloud through " + nodes.get(index) + ": " + answer, true);
        } catch (RuntimeException sessionEnded) {
            // KS revokes the host before stop.
        }
    }

    /** Home Assistant's answer when an action does not exist, as code: message. */
    private static boolean noSuchAction(String answer) {
        return answer.startsWith("not_found") || answer.startsWith("service_not_found");
    }

    /** A kiosk before 2026.11.1 refuses while it still reads the last text. The newest text waits for it to finish. */
    private void readLater(String text, List<String> nodes, int index) {
        if (text != speech) return;
        if (++speechTries > SPEECH_TRIES) {
            status("The kiosk was still reading, so the newest text was not read.", true);
            return;
        }
        speechNodes = nodes;
        speechNode = index;
        ui.cancel(speechTask);
        ui.post(speechTask, SPEECH_AGAIN_MS);
    }

    /** Runs on the main thread: the kiosk may have finished reading by now. */
    private void speechAgain() {
        synchronized (this) {
            if (host != null && speech != null) announce(speech, speechNodes, speechNode);
        }
    }

    /** The node that worked, the one typed, or what KS calls a node for a device of this name. */
    private List<String> announceNodes() {
        if (!assistantNode.isEmpty()) return Collections.singletonList(assistantNode);
        if (!settings.kioskNode.isEmpty()) return Collections.singletonList(settings.kioskNode);
        // KS leaves out letters such as š from a new node, and one that announced itself under another name keeps its node.
        Set<String> nodes = new LinkedHashSet<>();
        for (String slug : new String[] {Settings.ksSlug(deviceName), Settings.slug(deviceName)}) {
            if (slug.isEmpty()) continue;
            nodes.add("ks_" + slug);
            nodes.add(slug);
        }
        return new ArrayList<>(nodes);
    }

    private static Map<String, Object> espHomeCall(String service, Map<String, Object> data) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "call_service");
        message.put("domain", "esphome");
        message.put("service", service);
        message.put("service_data", data);
        return message;
    }

    /** Stop anything being read, by the tablet or by Home Assistant. */
    private void quiet() {
        ui.silence();
        speech = null;
        ui.cancel(speechTask);
        if (!spoke || noStopAction || assistantNode.isEmpty() || host == null) return;
        spoke = false;
        try {
            host.haCall(espHomeCall(assistantNode + "_announce_stop", Collections.<String, Object>emptyMap()), (ok, result, error) -> {
                // Kiosks before 2026.11.1 have no such action. Then the reading goes on to its end, and the next one waits for it.
                if (!ok && error != null && noSuchAction(error)) stopActionMissing();
            });
        } catch (RuntimeException refused) {
            // KS limits calls to Home Assistant. The reading goes on to its end.
        }
    }

    private synchronized void stopActionMissing() {
        noStopAction = true;
    }

    // Timers.

    @Override public synchronized void startTimer(String label, int seconds) {
        if (host == null) return;
        int id = timers.start(timerName(label), seconds, recipeId, page == Model.Page.STEP ? pageIndex : -1, clock.millis());
        if (id == 0) {
            banner = strings.tooManyTimers;
            bannerOwner = Trouble.TIMERS;
        }
        timersChanged();
    }

    @Override public synchronized boolean stepTimer(String label, int seconds) {
        if (host == null) return true;
        int step = page == Model.Page.STEP ? pageIndex : -1;
        for (Timers.Timer timer : timers.list(clock.millis())) {
            if (timer.total == seconds && timer.step == step && timer.recipe.equals(recipeId)) return false;
        }
        startTimer(label, seconds);
        return true;
    }

    private String timerName(String label) {
        return recipe != null && page == Model.Page.STEP && !pages.isEmpty() ? strings.stepWord + " " + (pageIndex + 1) + " · " + label : label;
    }

    /** The first of the step's timers that does not run yet. */
    private void startStepTimer() {
        if (recipe == null || page != Model.Page.STEP || pages.isEmpty()) return;
        Pages.Page current = pages.get(pageIndex);
        // The step's own time belongs to its first page. A later page has only the words on it.
        List<StepText.Run> runs = current.part == 0 ? StepText.timers(recipe.steps.get(current.step), currentText(), "min") : StepText.runs(currentText());
        for (StepText.Run run : runs) {
            if (run.timer() && stepTimer(run.text, run.seconds)) return;
        }
    }

    /**
     * A gesture, a clap, a key or the stop word, any of which can fire by accident: the first only silences, so it cannot
     * remove the only trace of what ended. A second one, or a touch, takes it away.
     */
    private void silenceAlarm() {
        if (!timers.ringing()) return;
        if (alarmMuted) {
            stopAlarm();
            return;
        }
        alarmMuted = true;
        updateSound();
    }

    /** One more minute: for the timers that ring, or else on the one that rings next. */
    private void addMinute() {
        followClock();
        long now = clock.millis();
        boolean ringing = timers.ringing();
        for (Timers.Timer timer : timers.list(now)) {
            if (ringing && timer.state == Timers.State.RINGING) {
                timers.add(timer.id, 60, now);
            } else if (!ringing && timer.state == Timers.State.RUNNING) {
                timers.add(timer.id, 60, now);
                break;
            }
        }
        timersChanged();
    }

    @Override public synchronized void pauseTimer(int id) {
        if (host == null) return;
        followClock();
        if (timers.pause(id, clock.millis())) timersChanged();
    }

    @Override public synchronized void resumeTimer(int id) {
        if (host != null && timers.resume(id, clock.millis())) timersChanged();
    }

    @Override public synchronized void addToTimer(int id, int seconds) {
        if (host == null) return;
        followClock();
        if (timers.add(id, seconds, clock.millis())) timersChanged();
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
        if (host != null) followClock();
        return timers.list(clock.millis());
    }

    /** Setting the tablet's clock moves no timer, and shows that one from before a restart ended long ago. */
    private void followClock() {
        int late = timers.steady(clock.millis(), ui.elapsed(), LATE_RING_MS);
        // A moved end must be saved now: a restart before the next save would read the end from before the clock was set.
        boolean moved = timers.takeMoved();
        if (late == 0 && !moved) return;
        if (late > 0) {
            missedTimers += late;
            report();
        }
        timersChanged();
    }

    /** A timer started, moved, stopped or rang. */
    private void timersChanged() {
        postDue();
        if (bannerOwner == Trouble.TIMERS && timers.list(clock.millis()).size() < Timers.MAX) clearBanner();
        updateSound();
        updateAwake();
        updateDock();
        render();
        publishState();
        saveSoon();
    }

    private void updateSound() {
        boolean ringing = timers.ringing();
        if (!ringing) alarmMuted = false;
        boolean sound = ringing && !alarmMuted && settings.tone != Settings.Tone.SILENT;
        if (sound != alarmOn || (sound && restartSound)) {
            alarmOn = sound;
            ui.alarm(sound);
        }
        restartSound = false;
    }

    private void postDue() {
        followClock();
        ui.cancel(dueTask);
        long next = timers.nextDue();
        if (next >= 0) ui.post(dueTask, Math.min(DUE_CHECK_MS, Math.max(1, next - clock.millis())));
    }

    /** Runs on the main thread when the next timer is due, and in any case every so often. */
    private void due() {
        synchronized (this) {
            if (host == null) return;
            try {
                followClock();
                List<Timers.Timer> rang = timers.due(clock.millis());
                if (rang.isEmpty()) {
                    postDue();
                    return;
                }
                alarmMuted = false;
                restartSound = true;
                // A new reason to look, so the wait before the screen may sleep starts over.
                lastTouch = clock.millis();
                idleExpired = false;
                // The screen must show it. Holding also wakes a sleeping screen.
                guardCommand("stopScreensaver");
                fire("timerDone");
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
        // The cook is doing something else now, so Cook today's meal does not open over it once the plan is in.
        chooseOn = null;
        if (idleExpired) {
            idleExpired = false;
            updateAwake();
        }
        // A first page in use shows a plan read half-hourly, though an app such as Norish 0.25 is read less often while nobody looks.
        if (shown && page == Model.Page.HOME && !planLoading && planAskedAt >= 0 && clock.millis() - planAskedAt > PLAN_REFRESH_MS) loadPlan();
    }

    /** Held while a recipe is up, a timer rings or a KS page opened from here may be up, until the idle limit. */
    private void updateAwake() {
        if (settings == null) return;
        boolean onRecipe = shown && page != Model.Page.HOME;
        boolean want = !idleExpired && ((settings.keepAwake && (onRecipe || linkOpen)) || timers.ringing());
        awake.hold(want);
        ui.keepScreenOn(want);
        if (want && !idlePosted) {
            idlePosted = true;
            ui.post(idleTask, Math.max(1, lastTouch + settings.awakeMinutes * 60_000L - clock.millis()));
        } else if (!want && idlePosted) {
            idlePosted = false;
            ui.cancel(idleTask);
        }
    }

    /** Runs on the main thread: a forgotten recipe must not keep the screen on all night. */
    private void idleCheck() {
        synchronized (this) {
            idlePosted = false;
            if (host == null || settings == null) return;
            try {
                long limit = settings.awakeMinutes * 60_000L;
                long left = lastTouch + limit - clock.millis();
                if (left > 0) {
                    idlePosted = true;
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

    /** How often the plan is read while nobody asks: half-hourly, unless the app needs pauses, as Norish 0.25 does. The save rides along. */
    private long planEvery() {
        RecipeSource current = source;
        return current == null ? PLAN_REFRESH_MS : current.planRefresh(PLAN_REFRESH_MS);
    }

    /** Runs on the main thread: keeps the meal plan and Home Assistant's view of it current. */
    private void planTick() {
        synchronized (this) {
            if (host == null) return;
            try {
                if (!planLoading) loadPlan();
                retryResume();
                // What is saved says when this kiosk was last up, so a long wait on one step does not look like a kiosk that was off.
                if ((cooking && recipe != null) || resume != null || stash != null || waitingAside != null) {
                    savedKey = "?";
                    saveSoon();
                }
                ui.post(planTask, planEvery());
            } catch (RuntimeException sessionEnded) {
                // As above.
            }
        }
    }

    /**
     * Runs on the main thread just after midnight. Today's meals are then the ones the plan in hand has for the new day,
     * also for Home Assistant and the plugin's page, with no read, so the day turns on time however seldom the plan is read.
     */
    private void midnight() {
        synchronized (this) {
            if (host == null) return;
            try {
                // A trouble on the plugin's page stays until it is over.
                if (!statusError) report();
                publishState();
                if (shown) changed();
                ui.post(midnightTask, untilMidnight());
            } catch (RuntimeException sessionEnded) {
                // As in planTick.
            }
        }
    }

    /**
     * The earliest day the recipe app's server may be on now: the tablet's, or UTC's when that is earlier. A server runs on
     * UTC by default, or on the tablet's zone when it is set so.
     */
    private LocalDate serverDay() {
        LocalDate today = LocalDate.now(clock);
        LocalDate utc = clock.instant().atZone(ZoneOffset.UTC).toLocalDate();
        return utc.isBefore(today) ? utc : today;
    }

    /** From now until a few seconds after the next midnight on the tablet's clock. */
    private long untilMidnight() {
        ZonedDateTime now = ZonedDateTime.now(clock);
        ZonedDateTime next = now.toLocalDate().plusDays(1).atStartOfDay(now.getZone()).plusSeconds(5);
        return Math.max(1_000L, Duration.between(now, next).toMillis());
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
        final Recipe meal = recipe;
        final String id = recipe.id;
        final double amount = servings;
        logging = true;
        logFailed = false;
        background(() -> {
            RecipeSource.SourceException problem = null;
            try {
                current.cooked(id, amount);
            } catch (RecipeSource.SourceException failed) {
                problem = failed;
            } catch (RuntimeException | Error bug) {
                problem = unexpected(bug);
            }
            synchronized (this) {
                if (host == null) return;
                // The meal may have gone by now, and this answer says nothing about the next one.
                if (recipe != meal) {
                    if (problem != null) status(problem.getMessage(), true);
                    return;
                }
                logging = false;
                if (problem == null) {
                    logged = true;
                    cleared(Trouble.LOG);
                } else {
                    logFailed = true;
                    failed(problem, Trouble.LOG);
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
        clearBanner();
        if (wasLink) guardCommand("hideOverlayPage");
        if (shown) close();
        else updateDock();
        updateAwake();
        changed();
    }

    @Override public synchronized void backToPlan() {
        if (host == null) return;
        endSession();
        page = Model.Page.HOME;
        clearBanner();
        updateDock();
        updateAwake();
        changed();
    }

    @Override public synchronized void askEnd() {
        if (host == null) return;
        if (recipe != null && (pageIndex > 0 || timers.any())) ask(String.format(strings.endAsk, recipe.name, pageIndex + 1), strings.endCooking, this::backToPlan);
        else if (resume != null) ask(String.format(strings.endAsk, recipeName, resume.page + 1), strings.endCooking, this::backToPlan);
        else backToPlan();
    }

    /** Put a question in the view. A yes runs the action. */
    private void ask(String question, String yes, Runnable action) {
        confirmText = question;
        confirmLabel = yes;
        confirmed = action;
        changed();
    }

    @Override public synchronized void confirm(boolean yes) {
        if (host == null) return;
        Runnable action = confirmed;
        confirmed = null;
        confirmText = null;
        confirmLabel = null;
        if (yes && action != null) action.run();
        changed();
    }

    // Commands and triggers.

    /** A command nothing waits for. KS refuses one when too many are pending, which must not stop what comes after it. */
    private void guardCommand(String command) {
        try {
            host.executeCommand(command, NONE, (ok, data, error) -> {});
        } catch (RuntimeException refused) {
            // The sound and the page do not depend on it.
        }
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
        saveSoon();
        publishState();
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
        String fixedNote = "";
        if (recipe != null && !pages.isEmpty()) {
            Pages.Page current = pages.get(Math.min(pageIndex, pages.size() - 1));
            Recipe.Step step = recipe.steps.get(current.step);
            pageNumber = pageIndex + 1;
            stepTitle = step.title;
            if (Math.abs(factor - 1) > 0.001 && Instructions.hasFixedNumbers(step)) fixedNote = String.format(strings.fixedNumbers, strings.servingsFor(recipe.servings, recipe.servingsText));
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
        boolean canLog = (settings.markCooked == Settings.MarkCooked.ASK || logFailed) && recipe != null && !logged;
        // Norish tells only this week and this month, so the days after them are not known to be free. The plan in hand
        // reaches as far as the server's day allowed when it was read.
        boolean cut = source != null && source.planReach(planFrom != null ? planFrom : serverDay()).isBefore(today.plusDays(settings.planDays - 1));
        int resumeAt = cooking ? pageIndex + 1 : resume != null ? Math.max(0, resume.page) + 1 : 0;
        // The plan and the list are for the first page. Trouble with them has no place over a recipe that is here.
        boolean homeOnly = bannerOwner == Trouble.PLAN || bannerOwner == Trouble.LIST;
        return new Model(page, version, strings, settings.textSize, settings.textMost, homeOnly && page != Model.Page.HOME ? null : banner, confirmText, confirmLabel, strings.date(today), settings.configured(), days, planLoading,
            query, order, Collections.unmodifiableList(new ArrayList<>(recipes)), recipesLoading, recipesMore,
            recipe, recipeName, recipeImage, recipeLoading, servings, factor, copyChecked(),
            recipe == null ? Collections.<Recipe.Ingredient>emptyList() : recipe.ingredients(), resumeAt, pageNumber, pages.size(), stepTitle, runs, ownTimer,
            stepIngredients, peek(pageIndex - 1), peek(pageIndex + 1), readyAround, settings.voice != Settings.Voice.OFF, settings.swipeSteps, fixedNote, canLog, logged,
            cut ? strings.planCut : "");
    }

    private Set<Recipe.Ingredient> copyChecked() {
        Set<Recipe.Ingredient> copy = Collections.newSetFromMap(new IdentityHashMap<Recipe.Ingredient, Boolean>());
        copy.addAll(checked);
        return copy;
    }

    // Entities.

    private void publishCatalog() {
        published.clear();
        // What these calls publish need not go out again.
        published.put("view", CLOSED);
        published.put("cooking", "false");
        published.put("timer_ringing", "false");
        published.put("timers", "0");
        for (String empty : new String[] {"recipe", "step", "next_timer", "next_timer_ends", "today_meals"}) published.put(empty, "");
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
        host.publishTextSensor("next_timer_ends", "Next timer ends", "");
        host.publishTextSensor("today_meals", "Today's meals", "");
    }

    /** Entities change only when their value does. Timers tick in the view, never here. */
    private void publishState() {
        if (host == null) return;
        if (differs("view", shown ? OPEN : CLOSED)) host.publishSelect(VIEW, "Cook Mode view", new String[] {CLOSED, OPEN}, shown ? OPEN : CLOSED);
        // A recipe on its way to replace the one being cooked may not come, so that one still counts until it does.
        if (stash == null && waitingAside == null) publishSession();
        boolean ringing = timers.ringing();
        if (differs("timer_ringing", String.valueOf(ringing))) host.publishBinarySensor("timer_ringing", "Timer ringing", "sound", ringing);
        List<Timers.Timer> list = timers.list(clock.millis());
        if (differs("timers", String.valueOf(list.size()))) {
            Map<String, Object> count = new LinkedHashMap<>();
            count.put("stateClass", "measurement");
            count.put("accuracyDecimals", 0);
            host.publishSensor("timers", "Timers", count, (double) list.size());
        }
        String next = "";
        String ends = "";
        for (Timers.Timer timer : list) {
            if (timer.state != Timers.State.RUNNING) continue;
            ZonedDateTime at = java.time.Instant.ofEpochMilli(timer.value).atZone(clock.getZone()).withNano(0);
            next = timer.label + " " + strings.time(at.toLocalTime());
            ends = at.toOffsetDateTime().toString();
            break;
        }
        if (differs("next_timer", next)) host.publishTextSensor("next_timer", "Next timer", clip(next));
        if (differs("next_timer_ends", ends)) host.publishTextSensor("next_timer_ends", "Next timer ends", ends);
        StringBuilder meals = new StringBuilder();
        LocalDate today = LocalDate.now(clock);
        for (RecipeSource.Planned meal : plan) {
            if (!meal.date.equals(today)) continue;
            if (meals.length() > 0) meals.append("; ");
            if (!meal.slot.isEmpty()) meals.append(strings.slot(meal.slot)).append(": ");
            meals.append(meal.title);
        }
        if (differs("today_meals", meals.toString())) host.publishTextSensor("today_meals", "Today's meals", clip(meals.toString()));
    }

    private void publishSession() {
        boolean on = cooking || resume != null;
        if (differs("cooking", String.valueOf(on))) host.publishBinarySensor("cooking", "Cooking", "running", on);
        String name = cooking && recipe != null ? recipe.name : resume != null ? recipeName : "";
        if (differs("recipe", name)) host.publishTextSensor("recipe", "Recipe", clip(name));
        String step = cooking && !pages.isEmpty() ? (pageIndex + 1) + "/" + pages.size() + ": " + PlainText.forSpeech(currentText()) : "";
        if (differs("step", step)) host.publishTextSensor("step", "Step", clip(step));
    }

    private boolean differs(String key, String value) {
        if (value.equals(published.get(key))) return false;
        published.put(key, value);
        return true;
    }

    private static String clip(String text) {
        return text.length() > STATE_MOST ? text.substring(0, STATE_MOST) : text;
    }

    boolean cooking() {
        return cooking;
    }
}
