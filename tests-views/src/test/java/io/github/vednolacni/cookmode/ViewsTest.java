// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.text.Spanned;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.jxl.kiosk.plugins.KsTheme;
import me.jxl.kiosk.plugins.OverlayFactory;
import me.jxl.kiosk.plugins.OverlaySpec;
import me.jxl.kiosk.plugins.PluginHost;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

/**
 * The real views under Robolectric: built, laid out, drawn, and touched the way a finger touches them, through the
 * whole of cooking a recipe. An exception in any listener or posted task fails the test, as it would end the app on a
 * tablet. Screenshots of each page land in build/screenshots.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(manifest = Config.NONE, sdk = 34)
public final class ViewsTest {
    private static final ZoneId ZONE = ZoneId.of("Europe/Ljubljana");
    private static final Map<Integer, byte[]> PICTURES = new HashMap<>();
    private static final Map<Integer, Typeface> FONTS = new HashMap<>();

    private final class TestClock extends Clock {
        Instant now = ZonedDateTime.of(2026, 11, 2, 11, 0, 0, 0, ZONE).toInstant();
        @Override public ZoneId getZone() { return ZONE; }
        @Override public Clock withZone(ZoneId zone) { throw new UnsupportedOperationException(); }
        @Override public Instant instant() { return now; }
    }

    private static final class Net implements Executor {
        final List<Runnable> tasks = new ArrayList<>();
        @Override public void execute(Runnable task) { tasks.add(task); }
        void runAll() {
            while (!tasks.isEmpty()) tasks.remove(0).run();
        }
    }

    private static final class Shown {
        final OverlaySpec spec;
        final OverlayFactory factory;
        View view;

        Shown(OverlaySpec spec, OverlayFactory factory) {
            this.spec = spec;
            this.factory = factory;
        }
    }

    /** What KS does: builds an overlay's view a little later on the main thread, and takes it away again. */
    private final class Host implements PluginHost {
        final List<String> commands = new ArrayList<>();
        final List<String> triggers = new ArrayList<>();
        final Map<String, Object> entities = new HashMap<>();
        final Map<String, Shown> overlays = new LinkedHashMap<>();
        final Map<String, Object> saved = new HashMap<>();
        String status = "";
        boolean statusError;

        @Override public void executeCommand(String command, Map<String, Object> arguments, CommandCallback callback) {
            commands.add(command);
            callback.onResult(true, "getDeviceInfo".equals(command) ? Collections.singletonMap("name", "Letna Kuhinja") : null, null);
        }
        @Override public void updateSettings(Map<String, Object> values) { saved.putAll(values); }
        @Override public void subscribe(String event) {}
        @Override public void haCall(Map<String, Object> message, CommandCallback callback) { callback.onResult(true, null, null); }
        @Override public void showOverlay(String key, OverlaySpec spec, OverlayFactory factory) {
            hideOverlay(key);
            Shown shown = new Shown(spec, factory);
            overlays.put(key, shown);
            new Handler(Looper.getMainLooper()).post(() -> {
                if (overlays.get(key) != shown) return;
                shown.view = factory.create(activity, theme);
                content.addView(shown.view, params(spec));
            });
        }
        @Override public void hideOverlay(String key) {
            Shown shown = overlays.remove(key);
            if (shown != null && shown.view != null) {
                content.removeView(shown.view);
                shown.factory.onDestroy(shown.view);
            }
        }
        @Override public void status(String message, boolean error) { status = message; statusError = error; }
        @Override public void publishSelect(String key, String name, String[] options, String state) { entities.put(key, state); }
        @Override public void publishBinarySensor(String key, String name, String deviceClass, Boolean state) { entities.put(key, state); }
        @Override public void publishSensor(String key, String name, Map<String, Object> metadata, Double state) { entities.put(key, state); }
        @Override public void publishTextSensor(String key, String name, String state) { entities.put(key, state); }
        @Override public void fireTrigger(String id) { triggers.add(id); }
        @Override public boolean setupResult(String run, String state, String message) { return true; }
        @Override public void showWindow(String title, String message, String buttonLabel) {}
        @Override public void hideWindow() {}
        @Override public void log(String message) {}
    }

    private Activity activity;
    private FrameLayout content;
    private KsTheme theme;
    private Host host;
    private TestClock clock;
    private Net net;
    private Kitchen kitchen;
    private Cook cook;
    private Strings s;
    private HttpServer server;
    private String prefix = "";

    @Before public void setUp() throws IOException {
        activity = Robolectric.buildActivity(Activity.class).setup().visible().get();
        content = new FrameLayout(activity);
        content.setBackgroundColor(0xFF3A5A40);
        activity.setContentView(content);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String name = exchange.getRequestURI().getPath().replaceAll("\\D", "");
            byte[] body = picture(name.isEmpty() ? 0 : Integer.parseInt(name));
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @After public void tearDown() {
        if (cook != null) cook.stop();
        idle();
        server.stop(0);
    }

    // The whole of cooking, on the screens the kiosks have.

    @Test @Config(qualifiers = "w960dp-h600dp-land-tvdpi")
    public void cookingOnALandscapeTablet() {
        walk("land-dark", true, "Slovenščina");
    }

    @Test @Config(qualifiers = "w600dp-h960dp-port-tvdpi")
    public void cookingOnAPortraitTablet() {
        walk("port-light", false, "Slovenščina");
    }

    @Test @Config(qualifiers = "w800dp-h480dp-land-mdpi")
    public void cookingOnASmallTablet() {
        walk("small-dark", true, "English");
    }

    @Test @Config(sdk = 27, qualifiers = "w960dp-h600dp-land-tvdpi")
    public void cookingOnAndroid8() {
        walk("android8", true, "English");
    }

    private View mainView() {
        return host.overlays.get(Cook.MAIN).view;
    }

    /** A text's size in sp. */
    private int sizeOf(TextView view) {
        return Math.round(view.getTextSize() / view.getResources().getDisplayMetrics().scaledDensity);
    }

    /** The step counter on show, for a clearer failure. */
    private String counter() {
        TextView shown = findText(content, text -> text.matches("\\d+ / \\d+"));
        return shown == null ? "nothing" : shown.getText().toString();
    }

    private void walk(String shots, boolean dark, String language) {
        begin(shots, dark, settings(language));
        open();
        assertNotNull(host.overlays.get(Cook.MAIN).view);
        assertNotNull(find(s.today));
        assertNotNull(find("Ostanki"));
        assertNotNull(find(s.loadMore));
        shot("1-home");

        // The list: the next page, the other order, and a search that keeps its field and focus.
        tap(find(s.loadMore));
        assertNotNull(find("Kremšnita"));
        assertNull(find(s.loadMore));
        tap(find(s.recent));
        assertNotNull(find("Kremšnita"));
        tap(find(s.all));
        // Searching with the plugin's own keys, whatever keyboard the kiosk has or lacks.
        TextView search = searchField();
        assertNull(find(s.space));
        tap(search);
        assertNotNull(find(s.space));
        assertOnScreen(find(s.search));
        shot("1-keypad");
        tap(find("J"));
        tap(find("U"));
        tap(find("H"));
        assertEquals("juh", search.getText().toString());
        later(600);
        assertNotNull(find("Bučna juha"));
        assertNull(find("Ričet"));
        assertSame(search, searchField());
        assertNotNull("the keys stay while the answer comes", find(s.space));
        shot("1-search");
        View erase = described(s.back);
        tap(erase);
        assertEquals("ju", search.getText().toString());
        press(erase);
        later(1200);
        release(erase);
        assertEquals("a held key goes on", "", search.getText().toString());
        later(600);
        assertNotNull(find("Ričet"));
        tap(find(s.close));
        assertNull(find(s.space));
        assertNotNull(find(s.all));

        // A note in the plan has no recipe: it looks for one by name.
        tap(find("Ostanki"));
        assertEquals("Ostanki", searchField().getText().toString());
        assertNotNull(find(s.noRecipes));
        tap(described(s.cancel));
        assertEquals("", searchField().getText().toString());
        settle();

        // A recipe with no steps says so and offers its app.
        tap(find("Zelenjavna juha"));
        assertNotNull(find(s.noSteps));
        assertNull(find(s.startCooking));
        assertNotNull(find(s.openInApp));
        tap(described(s.back));
        assertNotNull(find(s.today));

        // Today's meal, from the plan: the recipe with its photo.
        tap(find("Goveji golaž"));
        assertNotNull(find(s.startCooking));
        assertNotNull(find("800 g"));
        assertNotNull(find("goveje meso, za golaž"));
        assertOnScreen(described("+"));
        assertOnScreen(find(s.startCooking));
        assertTrue(kitchen.viewed.contains("1"));

        // What is worth knowing before starting.
        assertNotNull(find(s.overnight));
        assertNotNull(find("1 h 30 min"));
        assertNotNull(find("180 °C"));
        shot("2-recipe");

        // Servings rescale in place, so a held plus keeps working.
        View plus = described("+");
        tap(plus);
        assertSame(plus, described("+"));
        assertNotNull(find("1000 g"));
        assertNotNull(find(s.servings(5, "")));
        press(plus);
        later(1200);
        release(plus);
        assertNull("a held plus should have gone on", find(s.servings(5, "")));
        for (int i = 0; i < 40 && find(s.servings(4, "")) == null; i++) tap(described("-"));
        assertNotNull(find(s.servings(4, "")));
        assertNotNull(find("800 g"));

        // Ticking off an ingredient strikes it through in place.
        TextView fat = find("mast");
        assertNotNull(fat);
        tap(fat);
        assertSame(fat, find("mast"));
        assertTrue((fat.getPaintFlags() & Paint.STRIKE_THRU_TEXT_FLAG) != 0);
        shot("2-recipe-ticked");

        // Cooking starts.
        tap(find(s.startCooking));
        assertNotNull(find(s.nextStep));
        assertNotNull(find("1 / 7"));
        assertEquals(Boolean.TRUE, host.entities.get("cooking"));
        assertTrue(host.triggers.contains("cookingStarted"));
        assertTrue(mainView().getKeepScreenOn());
        TextView step = containing("Čebulo drobno");
        assertNotNull(step);
        assertFalse("the template tags must be gone", step.getText().toString().contains("scalable"));
        assertTrue(step.getText().toString().contains("30 g"));
        shot("3-step");

        // The timer in the sentence: its chip starts it, a second tap shows how it is doing, and the space next to it does nothing.
        tapChip(step);
        assertEquals(1, cook.timers().size());
        assertNotNull(find(s.stepWord + " 1 · 10–15 minut"));
        assertNull(find(s.newTimer));
        settle();
        tapChip(step);
        assertEquals(1, cook.timers().size());
        assertNotNull("the second tap opens the timers", find(s.newTimer));
        tapAt(4, 4);
        assertNull(find(s.newTimer));
        advance(3);
        tapBesideText(step);
        assertEquals(1, cook.timers().size());
        assertNull(find(s.newTimer));
        shot("3-step-timer");

        // The step's ingredients are chips to tick, and share their state with the list.
        assertNotNull(find("500 g čebula"));
        assertNotNull(find(s.forThisStep));

        // The big button stays put, and one press that lands twice moves one step.
        View next = find(s.nextStep);
        tap(next);
        assertNotNull(containing("Dodaj meso"));
        assertNotNull(find("2 / 7"));
        assertSame(next, find(s.nextStep));
        later(400);
        // On Android 8 Robolectric's clock runs on through the button's ripple, so two presses are never that close there.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) doubleTap(next);
        else tap(next);
        assertNotNull("one press that landed twice is at " + counter(), find("3 / 7"));
        assertNotNull(containing("Dolij vodo"));

        // Swipes every way. A text that does not fit keeps its vertical drags.
        later(400);
        swipe(0.2f, 0.5f, 0.8f, 0.5f);
        assertNotNull(find("2 / 7"));
        later(400);
        swipe(0.8f, 0.5f, 0.2f, 0.5f);
        assertNotNull(find("3 / 7"));
        later(400);
        swipe(0.2f, 0.5f, 0.8f, 0.5f);
        assertNotNull(find("2 / 7"));
        ScrollView stepScroll = enclosingScroll(containing("Dodaj meso"));
        boolean fits = !stepScroll.canScrollVertically(1) && !stepScroll.canScrollVertically(-1);
        later(400);
        swipe(0.5f, 0.7f, 0.5f, 0.3f);
        assertNotNull("a swipe up moves on when the text fits", find(fits ? "3 / 7" : "2 / 7"));
        if (fits) {
            later(400);
            swipe(0.5f, 0.3f, 0.5f, 0.7f);
            assertNotNull(find("2 / 7"));
        }
        later(400);
        swipe(0.5f, 0.5f, 0.52f, 0.52f);
        assertNotNull("a small movement is no swipe", find("2 / 7"));

        // The step's own time, named in its words, is one chip and not a second one.
        later(400);
        tap(find(s.nextStep));
        assertNotNull(containing("1 uro 30 minut"));
        assertNull(find("90 min"));
        shot("3-step-long-timer");

        // All the ingredients beside the step, and back.
        View list = described(s.ingredients);
        tap(list);
        assertNotNull(find("goveje meso, za golaž"));
        assertNotNull(find("Za konec"));
        assertNull("the list beside the step has the step's own ingredients", find(s.forThisStep));
        shot("4-step-ingredients");
        tap(described(s.ingredients));
        assertNull(find("goveje meso, za golaž"));
        assertNotNull(find(s.forThisStep));

        // The timers sheet: a preset, pause, more time, cancel.
        tap(described(s.timers));
        assertNotNull(find(s.newTimer));
        shot("5-timers");
        tap(find(s.duration(5)));
        assertNull(find(s.newTimer));
        assertEquals(2, cook.timers().size());
        tap(described(s.timers));
        tap(described(s.pause));
        assertEquals(Timers.State.PAUSED, cook.timers().get(0).state);
        shot("5-timers-paused");
        tap(described(s.resume));
        int left = cook.timers().get(0).left;
        tap(find("+1"));
        assertEquals(left + 60, cook.timers().get(0).left);
        for (Timers.Timer timer : cook.timers()) cook.cancelTimer(timer.id);
        settle();
        assertNull(described(s.pause));
        tapAt(4, 4);
        assertNull("a touch beside the sheet closes it", find(s.newTimer));

        // The alarm wakes the screen, a minute more starts it again, and a touch anywhere stops it.
        cook.startTimer("Test", 60);
        settle();
        int wakes = count("stopScreensaver");
        advance(61);
        assertNotNull(find(s.timeIsUp));
        assertTrue(host.triggers.contains("timerDone"));
        assertEquals(wakes + 1, count("stopScreensaver"));
        assertEquals(Boolean.TRUE, host.entities.get("timer_ringing"));
        shot("6-alarm");
        tap(find("+1 min"));
        assertNull(find(s.timeIsUp));
        assertEquals(60, cook.timers().get(0).left);
        advance(61);
        assertNotNull(find(s.timeIsUp));
        tapAt(4, 4);
        assertNull(find(s.timeIsUp));
        assertTrue(cook.timers().isEmpty());

        // The first page while cooking offers the way back, to the same step.
        tap(described(s.plan));
        assertNotNull(find(s.today));
        assertNotNull(find(String.format(s.continueAt, 3)));
        shot("7-home-cooking");

        // Another recipe would forget this one's place, and ending it too, so both ask.
        String switching = String.format(s.replaceRecipe, "Goveji golaž", 3);
        tap(find("Ričet"));
        assertNotNull(find(switching));
        assertNotNull(find(s.replace));
        shot("7-question");
        tap(find(s.cancel));
        assertNull(find(switching));
        tap(described(s.endCooking));
        assertNotNull(find(String.format(s.endAsk, "Goveji golaž", 3)));
        tapAt(4, 4);
        assertNull("a touch beside the question is a no", find(String.format(s.endAsk, "Goveji golaž", 3)));
        assertTrue(cook.cooking());
        tap(find(String.format(s.continueAt, 3)));
        assertNotNull(find("3 / 7"));

        // Closing keeps the place and leaves the small card, also with a timer.
        cook.startTimer("Test", 600);
        settle();
        tap(described(s.close));
        assertNull(host.overlays.get(Cook.MAIN));
        assertNotNull(host.overlays.get(Cook.DOCK));
        settle();
        assertNotNull(host.overlays.get(Cook.DOCK).view);
        assertNotNull(containing("Goveji golaž · 3/7"));
        assertNotNull(find(Durations.clock(600)));
        shot("8-dock");
        advance(5);
        assertNotNull("the card counts down", find(Durations.clock(595)));
        tap(host.overlays.get(Cook.DOCK).view);
        settle();
        assertNotNull(find("3 / 7"));
        assertNull(host.overlays.get(Cook.DOCK));
        for (Timers.Timer timer : cook.timers()) cook.cancelTimer(timer.id);

        // A long step became paragraphs, and the last page ends in a kind word.
        for (int i = 0; i < 8 && find(s.finish) == null; i++) {
            later(400);
            tap(find(s.nextStep));
        }
        assertNotNull(find("7 / 7"));
        assertNotNull("the step's own time, not in its words, is a chip below them", find("5 min"));
        later(400);
        tap(find(s.finish));
        assertNotNull(find(s.enjoy));
        shot("9-finish");
        tap(find(s.logCooked));
        assertEquals(1, kitchen.cooked.size());
        assertEquals("1 4.0", kitchen.cooked.get(0));
        assertNotNull(find(s.logged));
        tap(find(s.backToPlan));
        assertNotNull(find(s.today));
        assertNull(find(String.format(s.continueAt, 7)));
        assertFalse(mainView().getKeepScreenOn());
        assertEquals(Boolean.FALSE, host.entities.get("cooking"));
    }

    // What else can happen to a view.

    @Test @Config(qualifiers = "w960dp-h600dp-land-tvdpi")
    public void withoutASeverTheViewSaysWhatToDo() {
        Map<String, Object> values = settings("English");
        values.put("serverUrl", "");
        begin("empty", true, values);
        open();
        assertNotNull(find(s.notConfigured));
        shot("not-configured");
    }

    @Test @Config(qualifiers = "w960dp-h600dp-land-tvdpi")
    public void aFailureShowsABannerAndRetrying() {
        begin("failure", false, settings("English"));
        net.runAll();
        kitchen.failNext = new RecipeSource.SourceException(RecipeSource.SourceException.Kind.NETWORK, "Could not reach it");
        open();
        assertNotNull(find(s.problemNetwork));
        assertTrue(host.statusError);
        shot("banner");
        tap(find(s.retry));
        assertNull(find(s.problemNetwork));
        assertNotNull(find("Goveji golaž"));
    }

    @Test @Config(qualifiers = "w960dp-h600dp-land-tvdpi")
    public void theSwipesCanBeTurnedOff() {
        Map<String, Object> values = settings("English");
        values.put("swipeSteps", false);
        begin("no-swipes", true, values);
        open();
        tap(find("Goveji golaž"));
        tap(find(s.startCooking));
        later(400);
        swipe(0.8f, 0.5f, 0.2f, 0.5f);
        assertNotNull(find("1 / 7"));
    }

    @Test @Config(qualifiers = "w960dp-h600dp-land-tvdpi")
    public void aRecreatedViewComesBackAtTheSameStepAndAThemeChangeRestyles() {
        begin("recreate", true, settings("English"));
        open();
        tap(find("Palačinke"));
        assertNotNull("the servings planned, in the recipe's own word", find("2 kosov"));
        assertNull("this recipe has no page in its app", find(s.openInApp));
        tap(find(s.startCooking));
        later(400);
        tap(find(s.nextStep));
        assertNotNull(find("2 / 2"));

        Shown shown = host.overlays.get(Cook.MAIN);
        View old = shown.view;
        KsTheme light = theme(false);
        shown.factory.onThemeChanged(old, light);
        settle();
        assertNotNull(find("2 / 2"));
        shot("theme-changed");

        // KS builds the view again after its Activity restarts.
        content.removeView(old);
        shown.view = shown.factory.create(activity, theme);
        content.addView(shown.view, params(shown.spec));
        shown.factory.onDestroy(old);
        settle();
        assertNotSame(old, shown.view);
        assertNotNull(find("2 / 2"));
        later(400);
        tap(find(s.finish));
        assertNotNull(find(s.enjoy));
    }

    @Test @Config(qualifiers = "w960dp-h600dp-land-tvdpi")
    public void turningTheTabletRebuildsAndABarDoesNot() {
        begin("turn", true, settings("English"));
        open();
        TextView search = searchField();
        int width = content.getWidth();
        int height = content.getHeight();

        // A bar coming or going takes height only: the page stays.
        layout(width, height - 100);
        idle();
        assertSame(search, searchField());
        layout(width, height);
        idle();

        // Turning changes the width: a new layout, the same place.
        tap(find("Goveji golaž"));
        tap(find(s.startCooking));
        View before = find(s.nextStep);
        layout(height, width);
        idle();
        layout(height, width);
        View after = find(s.nextStep);
        assertNotNull(after);
        assertNotSame(before, after);
        assertNotNull(find("1 / 7"));
    }

    @Test @Config(qualifiers = "w800dp-h480dp-land-mdpi")
    public void aLongStepScrollsInsteadOfMoving() {
        Map<String, Object> values = settings("English");
        values.put("textSize", "Extra large");
        values.put("splitSteps", false);
        begin("scroll", true, values);
        open();
        tap(find("Goveji golaž"));
        tap(find(s.startCooking));
        for (int i = 0; i < 3; i++) {
            later(400);
            tap(find(s.nextStep));
        }
        assertNotNull(find("4 / 5"));
        TextView words = containing("Medtem pripravi");
        ScrollView scroll = enclosingScroll(words);
        assertNotNull(scroll);
        assertTrue("this step is meant to overflow", scroll.canScrollVertically(1));
        assertEquals("the longest steps get the smallest size", 34, sizeOf(words));
        shot("long-step");
        later(400);
        swipe(0.5f, 0.6f, 0.5f, 0.2f);
        settle();
        assertNotNull("a vertical drag scrolls a long step", find("4 / 5"));
        swipe(0.8f, 0.5f, 0.2f, 0.5f);
        assertNotNull("a sideways swipe still moves on", find("5 / 5"));
        settle();
        assertEquals("a short step fills the screen", 56, sizeOf(containing("Postrezi s polento")));
        shot("short-step");
    }

    @Test @Config(qualifiers = "w960dp-h600dp-land-tvdpi")
    public void numbersThatDoNotScaleArePointedOut() {
        begin("note", true, settings("Slovenščina"));
        open();
        tap(find("Goveji golaž"));
        tap(described("+"));
        tap(find(s.startCooking));
        String note = String.format(s.fixedNumbers, s.servings(4, ""));
        assertNull("only scaled amounts and a time in this step", find(note));
        assertTrue(containing("Čebulo").getText().toString().contains("37,5 g"));
        later(400);
        tap(find(s.nextStep));
        assertNotNull(containing("2 lovorjeva lista"));
        assertNotNull(find(note));
        shot("note");
    }

    @Test @Config(qualifiers = "w960dp-h600dp-land-tvdpi")
    public void aRestartBringsBackTheStepAndTheTimers() {
        begin("restart", true, settings("English"));
        open();
        tap(find("Goveji golaž"));
        tap(find(s.startCooking));
        later(400);
        tap(find(s.nextStep));
        cook.startTimer("Sauce", 600);
        later(Cook.SAVE_AFTER_MS + 100);
        String saved = (String) host.saved.get("resumeState");
        assertNotNull(saved);

        // Everything goes, and a new session starts from the setting.
        host.overlays.clear();
        content.removeAllViews();
        cook.stop();
        idle();
        Map<String, Object> values = settings("English");
        values.put("resumeState", saved);
        Host again = new Host();
        host = again;
        net = new Net();
        cook = new Cook(new AndroidUi(), clock, net, settings -> kitchen);
        cook.start(again, values);
        settle();
        assertNotNull("the card shows what is going on", again.overlays.get(Cook.DOCK));
        assertNotNull(containing("Goveji golaž · 2/7"));
        assertEquals(1, cook.timers().size());
        cook.execute("openCookMode");
        settle();
        assertNotNull(find("2 / 7"));
        assertNotNull(find(s.stepWord + " 2 · Sauce"));
    }

    @Test @Config(qualifiers = "w960dp-h600dp-land-tvdpi")
    public void readingAloudAndTheStopWord() {
        Map<String, Object> values = settings("English");
        values.put("readAloud", "Tablet voice");
        begin("voice", true, values);
        open();
        tap(find("Goveji golaž"));
        tap(described(s.read));
        tap(find(s.startCooking));
        tap(described(s.read));
        cook.startTimer("Test", 60);
        advance(61);
        assertNotNull(find(s.timeIsUp));
        // The first word only silences, so what ended stays on show.
        cook.onEvent("ks.stopword.detected", new HashMap<String, Object>());
        settle();
        assertNotNull(find(s.timeIsUp));
        cook.onEvent("ks.stopword.detected", new HashMap<String, Object>());
        settle();
        assertNull(find(s.timeIsUp));
    }

    @Test @Config(qualifiers = "w960dp-h600dp-land-tvdpi")
    public void stoppingWithEverythingRunningLeavesNothingBehind() {
        begin("stop", true, settings("English"));
        open();
        tap(find("Goveji golaž"));
        tap(find(s.startCooking));
        cook.startTimer("Test", 60);
        settle();
        // KS revokes the host and removes the views first, and what is queued on the main thread still runs.
        host.overlays.clear();
        content.removeAllViews();
        cook.stop();
        cook = null;
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofHours(2));
    }

    @Test @Config(qualifiers = "w960dp-h600dp-land-tvdpi")
    public void theEntryClassRunsWithARealServerThatIsNotThere() throws Exception {
        activity.setTitle("entry");
        theme = theme(true);
        s = Strings.ENGLISH;
        host = new Host();
        Map<String, Object> values = settings("English");
        values.put("serverUrl", "http://127.0.0.1:9/");
        CookModePlugin plugin = new CookModePlugin();
        plugin.start(host, values);
        plugin.execute("openCookMode", new HashMap<String, Object>());
        idle();
        layout(content.getResources().getDisplayMetrics().widthPixels, content.getResources().getDisplayMetrics().heightPixels);
        for (int i = 0; i < 100 && find(s.problemNetwork) == null; i++) {
            Thread.sleep(50);
            idle();
            layout(content.getWidth(), content.getHeight());
        }
        assertNotNull("the network thread reports an unreachable server to the view", find(s.problemNetwork));
        plugin.stop();
        idle();
    }

    // Setting up.

    private void begin(String shots, boolean dark, Map<String, Object> values) {
        prefix = shots;
        theme = theme(dark);
        clock = new TestClock();
        net = new Net();
        host = new Host();
        kitchen = new Kitchen("http://127.0.0.1:" + server.getAddress().getPort() + "/picture");
        cook = new Cook(new AndroidUi(), clock, net, settings -> kitchen);
        cook.start(host, values);
        s = Strings.of((String) values.get("language"));
    }

    private void open() {
        cook.execute("openCookMode");
        settle();
    }

    private static Map<String, Object> settings(String language) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("serverUrl", "http://tandoor.test:8080/");
        values.put("apiToken", "tda_test");
        values.put("planDays", 7.0);
        values.put("textSize", "Large");
        values.put("language", language);
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

    private FrameLayout.LayoutParams params(OverlaySpec spec) {
        if (spec.width() == OverlaySpec.FILL) return new FrameLayout.LayoutParams(-1, -1);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(theme.px(spec.width()), theme.px(spec.height()), Gravity.TOP | Gravity.END);
        int inset = theme.px(spec.inset());
        params.setMargins(inset, inset, inset, inset);
        return params;
    }

    private KsTheme theme(boolean dark) {
        float density = activity.getResources().getDisplayMetrics().density;
        return new KsTheme(dark, density, colors(dark), ViewsTest::font, ViewsTest::icon);
    }

    /** Only the roles the SDK documents: any other throws, as it may on a real kiosk. */
    private static Map<String, Integer> colors(boolean dark) {
        Map<String, Integer> colors = new HashMap<>();
        colors.put("surface", dark ? 0xFF141218 : 0xFFFEF7FF);
        colors.put("onSurface", dark ? 0xFFE6E0E9 : 0xFF1D1B20);
        colors.put("onSurfaceVariant", dark ? 0xFFCAC4D0 : 0xFF49454F);
        colors.put("surfaceContainerHigh", dark ? 0xFF2B2930 : 0xFFECE6F0);
        colors.put("surfaceContainerHighest", dark ? 0xFF36343B : 0xFFE6E0E9);
        colors.put("primary", dark ? 0xFFD0BCFF : 0xFF6750A4);
        colors.put("onPrimary", dark ? 0xFF381E72 : 0xFFFFFFFF);
        colors.put("primaryContainer", dark ? 0xFF4F378B : 0xFFEADDFF);
        colors.put("secondary", dark ? 0xFFCCC2DC : 0xFF625B71);
        colors.put("tertiary", dark ? 0xFFEFB8C8 : 0xFF7D5260);
        colors.put("error", dark ? 0xFFF2B8B5 : 0xFFB3261E);
        colors.put("outline", dark ? 0xFF938F99 : 0xFF79747E);
        colors.put("outlineVariant", dark ? 0xFF49454F : 0xFFCAC4D0);
        colors.put("success", dark ? 0xFF81C784 : 0xFF2E7D32);
        return colors;
    }

    private static Typeface font(int weight) {
        return FONTS.computeIfAbsent(weight, chosen -> {
            File file = new File(System.getProperty("fixtures"), "Rubik.ttf");
            if (!file.exists()) return Typeface.DEFAULT;
            try {
                Typeface typeface = new Typeface.Builder(file).setFontVariationSettings("'wght' " + chosen).build();
                return typeface != null ? typeface : Typeface.DEFAULT;
            } catch (RuntimeException unsupported) {
                return Typeface.DEFAULT;
            }
        });
    }

    /** KS's icon set, from the same Material Design Icons. A name that does not exist fails the test. */
    private static Path icon(String name) {
        assertTrue(name, name.startsWith("mdi:"));
        File file = new File(System.getProperty("fixtures"), "package/svg/" + name.substring(4) + ".svg");
        if (!new File(System.getProperty("fixtures"), "package/svg").exists()) return new Path();
        assertTrue("No such icon: " + name, file.exists());
        try {
            String svg = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            Matcher path = Pattern.compile(" d=\"([^\"]+)\"").matcher(svg);
            assertTrue(path.find());
            return SvgPath.parse(path.group(1));
        } catch (IOException unreadable) {
            throw new AssertionError(unreadable);
        }
    }

    private static byte[] picture(int number) {
        synchronized (PICTURES) {
            byte[] known = PICTURES.get(number);
            if (known != null) return known;
            Bitmap bitmap = Bitmap.createBitmap(640, 400, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(Color.HSVToColor(new float[] {(number * 47) % 360, 0.55f, 0.75f}));
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(Color.HSVToColor(new float[] {(number * 47 + 40) % 360, 0.7f, 0.5f}));
            canvas.drawCircle(320, 200, 150, paint);
            paint.setColor(Color.HSVToColor(new float[] {(number * 47 + 80) % 360, 0.35f, 0.92f}));
            canvas.drawCircle(320, 200, 105, paint);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes);
            PICTURES.put(number, bytes.toByteArray());
            return bytes.toByteArray();
        }
    }

    // Time.

    private void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /** Answers arrive, posted work runs, and the pictures come in. */
    private void settle() {
        for (int round = 0; round < 3; round++) {
            if (net != null) net.runAll();
            idle();
            layout();
            idle();
        }
        for (int wait = 0; wait < 100 && picturesPending(content); wait++) {
            try {
                Thread.sleep(30);
            } catch (InterruptedException interrupted) {
                throw new AssertionError(interrupted);
            }
            idle();
        }
        layout();
    }

    private void later(long millis) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
        settle();
    }

    /** The clock and the main thread's delayed work move together, a second at a time so the views can tick. */
    private void advance(int seconds) {
        for (int i = 0; i < seconds; i++) {
            clock.now = clock.now.plusSeconds(1);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        }
        settle();
    }

    private void layout() {
        int width = content.getWidth() > 0 ? content.getWidth() : activity.getResources().getDisplayMetrics().widthPixels;
        int height = content.getHeight() > 0 ? content.getHeight() : activity.getResources().getDisplayMetrics().heightPixels;
        layout(width, height);
    }

    private void layout(int width, int height) {
        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        content.layout(0, 0, width, height);
    }

    private static boolean picturesPending(View view) {
        if (view instanceof ImageView) {
            Object tag = view.getTag();
            if (tag instanceof String && !((String) tag).isEmpty() && ((ImageView) view).getDrawable() == null) return true;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) if (picturesPending(group.getChildAt(i))) return true;
        }
        return false;
    }

    // Touch.

    private void reveal(View view) {
        ScrollView scroll = enclosingScroll(view);
        if (scroll != null) {
            int y = 0;
            for (View at = view; at != scroll; at = (View) at.getParent()) y += at.getTop();
            scroll.scrollTo(0, Math.max(0, y - scroll.getHeight() / 3));
            layout();
        }
    }

    private static ScrollView enclosingScroll(View view) {
        for (Object at = view; at instanceof View; at = ((View) at).getParent()) if (at instanceof ScrollView) return (ScrollView) at;
        return null;
    }

    private float[] center(View view) {
        float x = view.getWidth() / 2f;
        float y = view.getHeight() / 2f;
        View at = view;
        while (at != content) {
            x += at.getLeft() - ((View) at.getParent()).getScrollX();
            y += at.getTop() - ((View) at.getParent()).getScrollY();
            at = (View) at.getParent();
        }
        return new float[] {x, y};
    }

    private void tap(View view) {
        assertNotNull("nothing to tap", view);
        reveal(view);
        float[] at = center(view);
        tapAt(at[0], at[1]);
    }

    private void tapAt(float x, float y) {
        long down = SystemClock.uptimeMillis();
        send(MotionEvent.ACTION_DOWN, x, y, down, down);
        send(MotionEvent.ACTION_UP, x, y, down, down + 40);
        settle();
    }

    /** Two presses with no time between them, the way a wet finger can land twice. */
    private void doubleTap(View view) {
        float[] at = center(view);
        for (int i = 0; i < 2; i++) {
            long down = SystemClock.uptimeMillis();
            send(MotionEvent.ACTION_DOWN, at[0], at[1], down, down);
            send(MotionEvent.ACTION_UP, at[0], at[1], down, down);
            idle();
        }
        settle();
    }

    private void press(View view) {
        reveal(view);
        float[] at = center(view);
        long down = SystemClock.uptimeMillis();
        send(MotionEvent.ACTION_DOWN, at[0], at[1], down, down);
    }

    private void release(View view) {
        float[] at = center(view);
        long time = SystemClock.uptimeMillis();
        send(MotionEvent.ACTION_UP, at[0], at[1], time - 100, time);
        settle();
    }

    /** A finger across the screen, in fractions of its size. */
    private void swipe(float fromX, float fromY, float toX, float toY) {
        float width = content.getWidth();
        float height = content.getHeight();
        long down = SystemClock.uptimeMillis();
        send(MotionEvent.ACTION_DOWN, fromX * width, fromY * height, down, down);
        for (int i = 1; i <= 10; i++) {
            float part = i / 10f;
            send(MotionEvent.ACTION_MOVE, (fromX + (toX - fromX) * part) * width, (fromY + (toY - fromY) * part) * height, down, down + i * 10);
        }
        send(MotionEvent.ACTION_UP, toX * width, toY * height, down, down + 120);
        settle();
    }

    private void send(int action, float x, float y, long down, long time) {
        MotionEvent event = MotionEvent.obtain(down, time, action, x, y, 0);
        content.dispatchTouchEvent(event);
        event.recycle();
    }

    private float[] chipCenter(TextView view) {
        Spanned text = (Spanned) view.getText();
        ChipSpan[] chips = text.getSpans(0, text.length(), ChipSpan.class);
        assertTrue("the step has no timer chip", chips.length > 0);
        Layout layout = view.getLayout();
        int start = text.getSpanStart(chips[0]);
        int line = layout.getLineForOffset(start);
        float x = layout.getPrimaryHorizontal(start) + theme.px(12);
        float y = layout.getLineBaseline(line) - theme.px(6);
        float[] at = center(view);
        return new float[] {at[0] - view.getWidth() / 2f + view.getTotalPaddingLeft() + x, at[1] - view.getHeight() / 2f + view.getTotalPaddingTop() + y};
    }

    private void tapChip(TextView view) {
        float[] at = chipCenter(view);
        tapAt(at[0], at[1]);
    }

    /** Far right of the last line, where a span would answer but a chip must not. */
    private void tapBesideText(TextView view) {
        Layout layout = view.getLayout();
        int line = layout.getLineCount() - 1;
        float[] at = center(view);
        float x = at[0] + view.getWidth() / 2f - 4;
        float y = at[1] - view.getHeight() / 2f + view.getTotalPaddingTop() + layout.getLineBaseline(line) - theme.px(6);
        tapAt(x, y);
    }

    // Finding things on screen. Only what a person could see counts.

    private TextView find(String exact) {
        return findText(content, text -> text.equals(exact));
    }

    private TextView containing(String part) {
        return findText(content, text -> text.contains(part));
    }

    private interface Words {
        boolean match(String text);
    }

    private static TextView findText(View view, Words words) {
        if (view.getVisibility() != View.VISIBLE) return null;
        if (view instanceof TextView && words.match(((TextView) view).getText().toString())) return (TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findText(group.getChildAt(i), words);
                if (found != null) return found;
            }
        }
        return null;
    }

    private View described(String description) {
        return findDescribed(content, description);
    }

    private static View findDescribed(View view, String description) {
        if (view.getVisibility() != View.VISIBLE) return null;
        if (description.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findDescribed(group.getChildAt(i), description);
                if (found != null) return found;
            }
        }
        return null;
    }

    private TextView searchField() {
        TextView field = findHint(content, s.searchHint);
        if (field == null) fail("no search field");
        return field;
    }

    private static TextView findHint(View view, String hint) {
        if (view.getVisibility() != View.VISIBLE) return null;
        if (view instanceof TextView && hint.contentEquals(((TextView) view).getHint() == null ? "" : ((TextView) view).getHint())) return (TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findHint(group.getChildAt(i), hint);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** In full view without scrolling: inside the screen and in no scrolling list. */
    private void assertOnScreen(View view) {
        assertNotNull(view);
        assertNull("this must not need scrolling", enclosingScroll(view));
        float[] at = center(view);
        assertTrue(at[1] - view.getHeight() / 2f >= 0 && at[1] + view.getHeight() / 2f <= content.getHeight());
        assertTrue(at[0] - view.getWidth() / 2f >= 0 && at[0] + view.getWidth() / 2f <= content.getWidth());
    }

    private int count(String command) {
        int count = 0;
        for (String name : host.commands) if (name.equals(command)) count++;
        return count;
    }

    // Pictures of it.

    private void shot(String name) {
        settle();
        File folder = new File(System.getProperty("screenshots"));
        folder.mkdirs();
        Bitmap bitmap = Bitmap.createBitmap(content.getWidth(), content.getHeight(), Bitmap.Config.ARGB_8888);
        content.draw(new Canvas(bitmap));
        try (FileOutputStream out = new FileOutputStream(new File(folder, prefix + "-" + name + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (IOException failed) {
            throw new AssertionError(failed);
        }
    }
}
