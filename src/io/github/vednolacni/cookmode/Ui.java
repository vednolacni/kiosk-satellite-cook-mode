// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import me.jxl.kiosk.plugins.OverlayFactory;

/** The Android side of the plugin: its views, sounds and the main thread. Tests stand in their own. */
interface Ui extends Awake.Scheduler {
    /** What the views can ask for. Called on the main thread. */
    interface Actions {
        void close();
        void home();
        void search(String query);
        void showOrder(RecipeSource.Order order);
        void loadMore();
        void refresh();
        void openPlanned(RecipeSource.Planned meal);
        void openRecipe(RecipeSource.Summary recipe);
        /** Back to the recipe being cooked, at its page. */
        void resumeCooking();
        void changeServings(int direction);
        void toggle(Recipe.Ingredient ingredient);
        void startCooking();
        void openInApp();
        void next();
        void previous();
        void read();
        /** A new timer, however many of its kind already run. */
        void startTimer(String label, int seconds);
        /** The timer a step's words name. False when it already runs, so one press does not make two. */
        boolean stepTimer(String label, int seconds);
        void pauseTimer(int id);
        void resumeTimer(int id);
        void addToTimer(int id, int seconds);
        void cancelTimer(int id);
        void stopAlarm();
        void logCooked();
        void endCooking();
        /** Forget the recipe being cooked but stay in the view, on the home page. */
        void backToPlan();
        /** The same, after asking if there is anything to lose. */
        void askEnd();
        /** The answer to the question the view shows. */
        void confirm(boolean yes);
        /** Someone touched a view, which restarts the wait before the screen may sleep. */
        void touched();
        /** The dock was tapped: back to the main view. */
        void openFromDock();
        /** Every timer as it stands now, for the views' once-a-second tick. */
        List<Timers.Timer> timers();
        /** Something on the tablet does not work, such as its voice. Goes to the plugin page. */
        void problem(String message);
        /** Headers a picture at this address needs. Never blocks. */
        Map<String, String> imageHeaders(String url);
    }

    /** A clock nobody can set, in milliseconds, or below zero when there is none. */
    long elapsed();

    /** How a test of the sound went, for the plugin page. */
    interface Report {
        void result(boolean ok, String message);
    }

    /** The long-lived main view. KS builds it later on the main thread, with the latest model. */
    OverlayFactory main(Actions actions);

    /** The small pill of timers and cooking state shown while the main view is closed. */
    OverlayFactory dock(Actions actions);

    /** Show this model in the main view: at once while it is up, else when KS builds it. Safe on any thread. */
    void render(Model model);

    /** What the dock shows besides the timers, such as the recipe and page being cooked, or empty. Safe on any thread. */
    void renderDock(String cooking, Strings strings);

    /** How the alarm sounds from now on. Safe on any thread. */
    void sound(Settings.Tone tone, boolean mediaStream, int minutes);

    /** Start or stop the alarm sound. Safe on any thread. */
    void alarm(boolean on);

    /** Sound the alarm for a few seconds and tell how loud it can be. Safe on any thread. */
    void testAlarm(Report report);

    /** Keep the tablet's display on while a view is up. Safe on any thread. */
    void keepScreenOn(boolean on);

    /** Say text with the tablet's voice in this language. Safe on any thread. */
    void speak(String text, Locale locale);

    /** Stop anything being said. Safe on any thread. */
    void silence();

    /** Release sounds, voices and picture threads. The session is over. */
    void shutdown();
}
