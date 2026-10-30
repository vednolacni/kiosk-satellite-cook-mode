// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.kitchen;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collections;
import java.util.Map;
import me.jxl.kiosk.plugins.OverlaySpec;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * The plugin without its Android views: settings, the meal plan, the panel's state, the commands, the Recipes view
 * select and cook mode. Plugin callbacks reach it on the plugin's worker and the panel's taps on the main thread, so
 * every way in holds its lock.
 */
final class Kitchen implements Ui.Actions {
    static final String PANEL = "panel";
    static final String PILL = "cook";
    static final String VIEW = "recipes_view";
    static final String CLOSED = "Closed";
    static final String OPEN = "Open";
    /** How often an open panel reads the plan again. It also moves the panel to the next day after midnight. */
    static final long REFRESH_MS = 15 * 60_000L;
    private static final Map<String, Object> NONE = Collections.emptyMap();

    private final Ui ui;
    private final Clock clock;
    private final MealPlanSource source;
    private final CookMode cook;
    private final Runnable refreshTask = this::tick;
    private PluginHost host;
    private Settings settings;
    private Strings strings = Strings.ENGLISH;
    private MealPlan plan;
    private boolean shown;
    /** A message of the panel's own, such as a missing app address. Cleared when the panel opens. */
    private String notice;

    Kitchen(Ui ui, Clock clock) {
        this.ui = ui;
        this.clock = clock;
        source = new MealPlanSource(this, clock, this::read);
        cook = new CookMode(this, clock, ui, new CookMode.Pill() {
            @Override public void show() { showPill(); }
            @Override public void hide() { host.hideOverlay(PILL); }
        });
    }

    synchronized void start(PluginHost host, Map<String, Object> values) {
        this.host = host;
        source.start(host);
        cook.start(host);
        configure(values);
        publishView();
        host.subscribe("screensaver.state");
        host.subscribe("screensaver.countdown");
        // Read now, so the panel opens on today's meals.
        refresh();
    }

    synchronized void configure(Map<String, Object> values) {
        Settings previous = settings;
        settings = new Settings(values);
        strings = Strings.of(settings.language);
        if (host == null) return;
        report();
        if (previous != null && (!previous.calendarIds().equals(settings.calendarIds()) || previous.days != settings.days)) {
            plan = null;
            source.reset();
            refresh();
        } else {
            render();
        }
    }

    synchronized void execute(String command) {
        if (host == null) return;
        switch (command) {
            case "showRecipes": show(); break;
            case "hideRecipes": hide(); break;
            case "toggleRecipes": if (shown) hide(); else show(); break;
            case "openRecipeApp": page(RecipeLinks.home(settings)); break;
            case "endCooking": cook.end(true); break;
            default: throw new IllegalArgumentException("Unknown command: " + command);
        }
    }

    synchronized void onEvent(String event, Map<String, Object> payload) {
        if (host == null) return;
        if (("select." + VIEW).equals(event)) {
            Object option = payload.get("option");
            if (OPEN.equals(option)) show();
            else if (CLOSED.equals(option)) hide();
            else throw new IllegalArgumentException("Unknown Recipes view option: " + option);
        } else if ("overlay.closed".equals(event)) {
            if (PANEL.equals(payload.get("key"))) closed();
        } else if ("ks.screensaver.state".equals(event)) {
            if (Boolean.TRUE.equals(payload.get("active")) && shown && settings.closeOnScreensaver) hide();
        } else if ("ks.screensaver.countdown".equals(event)) {
            cook.countdown(payload.get("due"));
        }
    }

    /** KS revokes the host and removes the overlays and the select before stop, so nothing is hidden here. */
    synchronized void stop() {
        ui.cancel(refreshTask);
        cook.stop();
        source.stop();
        host = null;
        settings = null;
        plan = null;
        shown = false;
        notice = null;
    }

    @Override public synchronized void open(Meal meal) {
        if (host != null) page(RecipeLinks.target(meal, settings));
    }

    @Override public synchronized void openApp() {
        if (host != null) page(RecipeLinks.home(settings));
    }

    @Override public synchronized void close() {
        if (host != null) hide();
    }

    @Override public synchronized void refresh() {
        if (host == null) return;
        source.read(settings);
        render();
    }

    /** The pill's tap. */
    synchronized void endCooking() {
        if (host != null) cook.end(true);
    }

    boolean cooking() {
        return cook.cooking();
    }

    private void show() {
        if (!shown) {
            shown = true;
            notice = null;
            // Opened from Home Assistant, the panel must not land under the screensaver.
            host.executeCommand("stopScreensaver", NONE, (ok, data, error) -> {});
            render();
            host.showOverlay(PANEL, OverlaySpec.fullScreen().enter(OverlaySpec.FADE).exit(OverlaySpec.FADE).transitionDuration(180), ui.panel(this));
            refresh();
            ui.cancel(refreshTask);
            ui.post(refreshTask, REFRESH_MS);
        }
        publishView();
    }

    private void hide() {
        if (shown) host.hideOverlay(PANEL);
        closed();
    }

    /** The panel went away, by hide or by back. */
    private void closed() {
        shown = false;
        ui.cancel(refreshTask);
        publishView();
    }

    /** Open a page from the panel. KS draws plugin overlays over its pages, so the panel goes first. */
    private void page(String url) {
        if (url == null) {
            notice = strings.noApp;
            render();
            host.status("Set the recipe app address first.", true);
            return;
        }
        hide();
        cook.open(url, settings.cookMode, settings.cookMinutes);
    }

    private void showPill() {
        host.showOverlay(PILL, OverlaySpec.at(OverlaySpec.BOTTOM_RIGHT).inset(24).closeOnBack(false).enter(OverlaySpec.SLIDE).exit(OverlaySpec.FADE), ui.pill(strings.endCooking, this::endCooking));
    }

    private void publishView() {
        host.publishSelect(VIEW, "Recipes view", new String[] {CLOSED, OPEN}, shown ? OPEN : CLOSED);
    }

    private void render() {
        if (settings != null) ui.render(PanelModel.build(settings, strings, plan, source.reading(), notice, LocalDate.now(clock)));
    }

    /** A reading finished. */
    private void read(MealPlan next) {
        plan = next;
        report();
        render();
    }

    /** The plugin page's status line. */
    private void report() {
        String problem = settings.problem();
        if (problem != null) {
            host.status(problem, true);
        } else if (plan == null) {
            host.status("Reading the meal plan.", false);
        } else if (!plan.failures.isEmpty()) {
            String failures = "Could not read " + String.join("; ", plan.failures);
            host.status(failures.length() > 1000 ? failures.substring(0, 1000) : failures, true);
        } else {
            int count = plan.meals.size();
            host.status("Read " + count + (count == 1 ? " meal" : " meals") + " for the next " + plan.days + (plan.days == 1 ? " day" : " days")
                + " at " + LocalTime.now(clock).withSecond(0).withNano(0) + ".", false);
        }
    }

    /** Runs on the main thread while the panel is open. */
    private void tick() {
        synchronized (this) {
            if (host == null || !shown) return;
            try {
                refresh();
                ui.post(refreshTask, REFRESH_MS);
            } catch (RuntimeException sessionEnded) {
                // KS revokes the host just before stop, and an exception here would end the app.
            }
        }
    }
}
