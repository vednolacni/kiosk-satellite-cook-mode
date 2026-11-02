// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import me.jxl.kiosk.plugins.PluginHost;

/** Timers and Awake: the clock arithmetic of kitchen timers and the screensaver beats. */
public final class TimersTest {
    /** A clock the test moves by hand. */
    static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-10-30T16:00:00Z");
        @Override public ZoneId getZone() { return ZoneId.of("Europe/Ljubljana"); }
        @Override public Clock withZone(ZoneId zone) { throw new UnsupportedOperationException(); }
        @Override public Instant instant() { return now; }
        void seconds(long seconds) { now = now.plusSeconds(seconds); }
    }

    /** Posted main thread tasks, run by hand. */
    static final class Main implements Awake.Scheduler {
        final Map<Runnable, Long> tasks = new LinkedHashMap<>();
        public void post(Runnable task, long delayMs) { assert delayMs > 0; tasks.put(task, delayMs); }
        public void cancel(Runnable task) { tasks.remove(task); }
        long only() { assert tasks.size() == 1 : tasks; return tasks.values().iterator().next(); }
        void runOnly() { Runnable task = tasks.keySet().iterator().next(); tasks.remove(task); task.run(); }
    }

    static final class Host implements PluginHost {
        final List<String> commands = new ArrayList<>();
        public void executeCommand(String command, Map<String, Object> arguments, CommandCallback callback) { assert arguments.isEmpty() && callback != null; commands.add(command); }
        public void showWindow(String title, String message, String buttonLabel) {}
        public void hideWindow() {}
        public void log(String message) {}
    }

    public static void main(String[] args) {
        timers();
        awake();
        System.out.println("TimersTest passed");
    }

    static void timers() {
        TestClock clock = new TestClock();
        Timers timers = new Timers();
        long now = clock.millis();
        int pasta = timers.start("Pasta", 600, "12", 2, now);
        int sauce = timers.start("Sauce", 90, "12", 3, now);
        assert pasta > 0 && sauce > pasta && timers.any() && !timers.ringing();
        assert timers.start("Nothing", 0, "", -1, now) == 0;
        assert timers.nextDue() == now + 90_000;
        List<Timers.Timer> list = timers.list(now);
        assert list.get(0).id == sauce && list.get(0).left == 90 && list.get(1).left == 600 && "12".equals(list.get(1).recipe) && list.get(1).step == 2;
        // Left rounds up: half a second before the end still shows one second.
        assert timers.list(now + 89_500).get(0).left == 1;

        // Pause holds the time left, resume carries on from it.
        assert timers.pause(pasta, now + 60_000) && !timers.pause(pasta, now + 60_000);
        assert timers.list(now + 500_000).get(1).left == 540 && timers.list(now + 500_000).get(1).state == Timers.State.PAUSED;
        assert timers.resume(pasta, now + 100_000) && timers.nextDue() == now + 90_000;

        // The sauce rings first, once, and stays first in the list.
        assert timers.due(now + 89_000).isEmpty();
        List<Timers.Timer> rang = timers.due(now + 90_000);
        assert rang.size() == 1 && rang.get(0).id == sauce && rang.get(0).state == Timers.State.RINGING;
        assert timers.due(now + 91_000).isEmpty() && timers.ringing();
        assert timers.list(now + 91_000).get(0).id == sauce && timers.list(now + 91_000).get(0).left == 0;
        assert !timers.add(sauce, -60, now + 91_000) && timers.ringing();

        // Adding time moves the end, taking more than is left rings at once.
        assert timers.add(pasta, 60, now + 100_000) && timers.nextDue() == now + 100_000 + 540_000 + 60_000;
        assert timers.add(pasta, -10_000, now + 100_000) && timers.nextDue() == now + 100_000;
        assert timers.due(now + 100_000).size() == 1;
        assert timers.dismissRinging() == 2 && !timers.any() && timers.nextDue() == -1;

        // One more minute: a ringing timer starts again and stops ringing.
        int again = timers.start("Again", 30, "", -1, now);
        timers.due(now + 30_000);
        assert timers.ringing() && timers.add(again, 60, now + 40_000);
        assert !timers.ringing() && timers.nextDue() == now + 100_000 && timers.list(now + 40_000).get(0).left == 60;
        assert timers.cancel(again) && !timers.any();

        // At most MAX timers, and cancel removes one.
        for (int i = 0; i < Timers.MAX; i++) assert timers.start("T" + i, 60, "", -1, now) > 0;
        assert timers.start("One too many", 60, "", -1, now) == 0;
        int first = timers.list(now).get(0).id;
        assert timers.cancel(first) && !timers.cancel(first) && timers.list(now).size() == Timers.MAX - 1;
        timers.clear();
        assert !timers.any();
    }

    static void awake() {
        TestClock clock = new TestClock();
        Main main = new Main();
        Host host = new Host();
        Object lock = new Object();
        Awake awake = new Awake(lock, clock, main);
        awake.start(host);
        awake.countdown(clock.instant().plusSeconds(60).toString());
        assert main.tasks.isEmpty();

        // Holding wakes the screen and beats every 30 seconds until KS names a deadline.
        awake.hold(true);
        assert host.commands.equals(Collections.singletonList("stopScreensaver")) && main.only() == Awake.BEAT_MS;
        awake.hold(true);
        assert host.commands.size() == 1;
        // A deadline 20 seconds out moves the beat to 5 seconds before it.
        awake.countdown(clock.instant().plusSeconds(20).toString());
        assert main.only() == 15_000L;
        awake.countdown(clock.instant().plusSeconds(3).toString());
        assert main.only() == 1_000L;
        awake.countdown(null);
        assert main.only() == Awake.BEAT_MS;
        main.runOnly();
        assert host.commands.get(1).equals("postponeScreensaver") && main.only() == Awake.BEAT_MS;

        // Release stops the beats. A late beat does nothing.
        awake.hold(false);
        assert main.tasks.isEmpty() && !awake.held();
        awake.hold(true);
        Runnable beat = main.tasks.keySet().iterator().next();
        awake.stop();
        beat.run();
        assert main.tasks.isEmpty() && host.commands.size() == 3 && host.commands.get(2).equals("stopScreensaver");
        assert Awake.epoch("2026-10-30T16:00:20Z") == clock.millis() + 20_000 && Awake.epoch("soon") == -1 && Awake.epoch(5) == -1;
    }
}
