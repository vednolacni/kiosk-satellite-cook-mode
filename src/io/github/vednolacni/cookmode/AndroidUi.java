// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import me.jxl.kiosk.plugins.KsTheme;
import me.jxl.kiosk.plugins.OverlayFactory;

/**
 * The plugin's Android side: its overlays, sound, voice and the main thread. The controller calls in from any thread,
 * and everything here happens in a posted task on the main thread. Exceptions in posted tasks would end the app, so
 * each one is guarded.
 */
class AndroidUi implements Ui {
    private static final long TICK_MS = 1_000L;
    private static final long TEST_MS = 5_000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Images images = new Images();
    private final AlarmSound sound = new AlarmSound();
    private final AtomicBoolean queued = new AtomicBoolean();
    private final Runnable apply = () -> {
        queued.set(false);
        Parts.guard(this::apply);
    };
    private final Runnable tick = this::tick;
    private final Runnable endTest = () -> Parts.guard(this::endTest);

    // Written on any thread, read on the main thread.
    private volatile Model model;
    private volatile String dockText = "";
    private volatile Strings dockStrings = Strings.ENGLISH;
    private volatile boolean keepOn;
    private volatile Settings.Tone tone = Settings.Tone.ALARM;
    private volatile boolean mediaStream;
    private volatile int ringMinutes = 3;

    // Main thread only.
    private Context context;
    private Actions actions;
    private CookView view;
    private DockView dock;
    private TabletVoice voice;
    private Locale voiceLocale;
    private boolean ticking;
    /** The sound is the settings page's test, which ends by itself. */
    private boolean demo;
    private boolean closed;

    @Override public OverlayFactory main(Actions actions) {
        return new OverlayFactory() {
            @Override public View create(Context from, KsTheme theme) {
                context = from.getApplicationContext();
                AndroidUi.this.actions = actions;
                view = new CookView(from, theme, actions, images);
                apply();
                return view;
            }

            @Override public void onThemeChanged(View shown, KsTheme theme) {
                ((CookView) shown).theme(theme);
            }

            @Override public void onDestroy(View shown) {
                ((CookView) shown).leave();
                if (view == shown) view = null;
            }
        };
    }

    @Override public OverlayFactory dock(Actions actions) {
        return new OverlayFactory() {
            @Override public View create(Context from, KsTheme theme) {
                context = from.getApplicationContext();
                AndroidUi.this.actions = actions;
                dock = new DockView(from, theme, actions);
                apply();
                return dock;
            }

            @Override public void onThemeChanged(View shown, KsTheme theme) {
                ((DockView) shown).theme(theme);
            }

            @Override public void onDestroy(View shown) {
                if (dock == shown) dock = null;
            }
        };
    }

    @Override public long elapsed() {
        return android.os.SystemClock.elapsedRealtime();
    }

    @Override public void render(Model next) {
        model = next;
        queue();
    }

    @Override public void renderDock(String cooking, Strings strings) {
        dockText = cooking;
        dockStrings = strings;
        queue();
    }

    @Override public void keepScreenOn(boolean on) {
        keepOn = on;
        queue();
    }

    /** Several changes in a row show once. */
    private void queue() {
        if (queued.compareAndSet(false, true)) main.post(apply);
    }

    private void apply() {
        if (closed) return;
        Model current = model;
        if (view != null) {
            view.setKeepScreenOn(keepOn);
            if (current != null) view.show(current);
        }
        if (dock != null) dock.setKeepScreenOn(keepOn);
        showTimers();
    }

    private void tick() {
        ticking = false;
        if (!closed) Parts.guard(this::showTimers);
    }

    /** The numbers in the views, again each second for as long as a timer exists and a view is up to show it. */
    private void showTimers() {
        if (actions == null || (view == null && dock == null)) return;
        List<Timers.Timer> timers = actions.timers();
        if (view != null) view.timers(timers);
        if (dock != null) dock.show(dockText, dockStrings, timers);
        if (!timers.isEmpty() && !ticking) {
            ticking = true;
            main.postDelayed(tick, TICK_MS);
        }
    }

    @Override public void sound(Settings.Tone tone, boolean mediaStream, int minutes) {
        this.tone = tone;
        this.mediaStream = mediaStream;
        ringMinutes = minutes;
    }

    @Override public void alarm(boolean on) {
        main.post(() -> Parts.guard(() -> {
            if (closed) return;
            if (demo) {
                demo = false;
                main.removeCallbacks(endTest);
                sound.stop();
            }
            if (!on) {
                sound.stop();
                return;
            }
            sound.start(context, tone == Settings.Tone.BEEPS, mediaStream, ringMinutes);
            if (context != null && AlarmSound.volume(context, mediaStream) == 0 && actions != null) {
                actions.problem("A timer rang, but the tablet's " + (mediaStream ? "media" : "alarm") + " volume is at zero.");
            }
        }));
    }

    @Override public void testAlarm(Report report) {
        main.post(() -> Parts.guard(() -> {
            if (closed) return;
            // A timer ringing right now keeps its sound, which shows just as well how loud it is.
            if (demo || !sound.playing()) {
                sound.stop();
                demo = true;
                main.removeCallbacks(endTest);
                sound.start(context, tone != Settings.Tone.ALARM, mediaStream, ringMinutes);
                main.postDelayed(endTest, TEST_MS);
            }
            String stream = mediaStream ? "media" : "alarm";
            if (context == null) {
                report.result(true, "Beeping for a few seconds. Open Cook Mode once to try the alarm tone and read the volume.");
                return;
            }
            float volume = AlarmSound.volume(context, mediaStream);
            if (volume == 0) report.result(false, "The tablet's " + stream + " volume is at zero, so timers will be silent. Turn it up, or choose the other sound stream.");
            else report.result(true, "Ringing for a few seconds at " + Math.round(volume * 100) + "% of the tablet's " + stream + " volume.");
        }));
    }

    private void endTest() {
        if (!demo) return;
        demo = false;
        sound.stop();
    }

    @Override public void speak(String text, Locale locale) {
        main.post(() -> Parts.guard(() -> {
            if (closed || context == null) return;
            if (voice == null || !locale.equals(voiceLocale)) {
                if (voice != null) voice.close();
                voiceLocale = locale;
                Actions report = actions;
                voice = new TabletVoice(context, locale, message -> {
                    if (report != null) Parts.guard(() -> report.problem(message));
                });
            }
            voice.speak(text);
        }));
    }

    @Override public void silence() {
        main.post(() -> Parts.guard(() -> {
            if (voice != null) voice.silence();
        }));
    }

    @Override public void shutdown() {
        main.post(() -> Parts.guard(() -> {
            closed = true;
            main.removeCallbacks(tick);
            main.removeCallbacks(endTest);
            sound.stop();
            if (voice != null) voice.close();
            voice = null;
            images.close();
            view = null;
            dock = null;
        }));
    }

    @Override public void post(Runnable task, long delayMs) {
        main.postDelayed(task, delayMs);
    }

    @Override public void cancel(Runnable task) {
        main.removeCallbacks(task);
    }
}
