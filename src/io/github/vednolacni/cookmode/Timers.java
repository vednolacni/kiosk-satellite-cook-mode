// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Kitchen timers: several at once, each running, paused or ringing. Times come from the clock passed in, so tests
 * and the plugin share the same arithmetic. Not thread safe: the plugin uses it under its lock.
 */
final class Timers {
    enum State { RUNNING, PAUSED, RINGING }

    /** A snapshot of one timer for the views. */
    static final class Timer {
        final int id;
        final String label;
        final int total;
        final State state;
        /** Whole seconds left, rounded up, so a timer shows 0:01 until it rings. Zero while ringing. */
        final int left;
        /** The recipe and page that started it, so a tap can return there. Empty and -1 when none. */
        final String recipe;
        final int step;
        /** When it rings in epoch milliseconds, or when it rang, or while paused, the milliseconds left. */
        final long value;

        Timer(int id, String label, int total, State state, int left, String recipe, int step, long value) {
            this.id = id;
            this.label = label;
            this.total = total;
            this.state = state;
            this.left = left;
            this.recipe = recipe;
            this.step = step;
            this.value = value;
        }
    }

    /** At most this many timers run at once. A kitchen rarely needs more, and the bar stays readable. */
    static final int MAX = 6;

    private static final class Entry {
        final int id;
        final String label;
        final int total;
        final String recipe;
        final int step;
        State state = State.RUNNING;
        /** While running, when it rings in epoch milliseconds. */
        long endsAt;
        /** While paused, the milliseconds that were left. */
        long remaining;
        /** While ringing, when it started to. */
        long rangAt;

        Entry(int id, String label, int total, String recipe, int step) {
            this.id = id;
            this.label = label;
            this.total = total;
            this.recipe = recipe;
            this.step = step;
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private int nextId = 1;

    /** Start a timer and return its id, or 0 when MAX timers already exist. */
    int start(String label, int seconds, String recipe, int step, long now) {
        if (seconds <= 0 || entries.size() >= MAX) return 0;
        Entry entry = new Entry(nextId++, label, seconds, recipe, step);
        entry.endsAt = now + seconds * 1000L;
        entries.add(entry);
        return entry.id;
    }

    /** Bring back a timer from before a restart: running until value, or paused with value milliseconds left. */
    int restore(String label, int total, boolean paused, long value, String recipe, int step) {
        if (entries.size() >= MAX) return 0;
        Entry entry = new Entry(nextId++, label, total, recipe, step);
        if (paused) {
            entry.state = State.PAUSED;
            entry.remaining = value;
        } else {
            entry.endsAt = value;
        }
        entries.add(entry);
        return entry.id;
    }

    boolean pause(int id, long now) {
        Entry entry = find(id);
        if (entry == null || entry.state != State.RUNNING) return false;
        entry.remaining = Math.max(0, entry.endsAt - now);
        entry.state = State.PAUSED;
        return true;
    }

    boolean resume(int id, long now) {
        Entry entry = find(id);
        if (entry == null || entry.state != State.PAUSED) return false;
        entry.endsAt = now + entry.remaining;
        entry.state = State.RUNNING;
        return true;
    }

    /** Add or take away seconds. A timer taken below zero rings at once, and a ringing one starts again for that long. */
    boolean add(int id, int seconds, long now) {
        Entry entry = find(id);
        if (entry == null) return false;
        if (entry.state == State.RINGING) {
            if (seconds <= 0) return false;
            entry.state = State.RUNNING;
            entry.endsAt = now + seconds * 1000L;
            return true;
        }
        if (entry.state == State.PAUSED) entry.remaining = Math.max(0, entry.remaining + seconds * 1000L);
        else entry.endsAt = Math.max(now, entry.endsAt + seconds * 1000L);
        return true;
    }

    /** Cancel a timer, or silence it while it rings. */
    boolean cancel(int id) {
        Entry entry = find(id);
        return entry != null && entries.remove(entry);
    }

    /** Silence and remove every ringing timer. Returns how many there were. */
    int dismissRinging() {
        int count = 0;
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entries.get(i).state == State.RINGING) {
                entries.remove(i);
                count++;
            }
        }
        return count;
    }

    /** Move running timers whose time is up to ringing. Returns the ones that started ringing now. */
    List<Timer> due(long now) {
        List<Timer> rang = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.state == State.RUNNING && entry.endsAt <= now) {
                entry.state = State.RINGING;
                entry.rangAt = now;
                rang.add(snapshot(entry, now));
            }
        }
        return rang;
    }

    /** When the next running timer rings, or -1 when none runs. */
    long nextDue() {
        long next = -1;
        for (Entry entry : entries) {
            if (entry.state == State.RUNNING && (next < 0 || entry.endsAt < next)) next = entry.endsAt;
        }
        return next;
    }

    boolean ringing() {
        for (Entry entry : entries) if (entry.state == State.RINGING) return true;
        return false;
    }

    /** Whether any timer exists: the screen stays on and the timer bar shows while one does. */
    boolean any() {
        return !entries.isEmpty();
    }

    /** Ringing first, then by time left, so the most urgent is first. */
    List<Timer> list(long now) {
        List<Timer> list = new ArrayList<>();
        for (Entry entry : entries) list.add(snapshot(entry, now));
        Collections.sort(list, (a, b) -> {
            if (a.state == State.RINGING || b.state == State.RINGING) {
                if (a.state != b.state) return a.state == State.RINGING ? -1 : 1;
            }
            if (a.left != b.left) return Integer.compare(a.left, b.left);
            return Integer.compare(a.id, b.id);
        });
        return list;
    }

    void clear() {
        entries.clear();
    }

    private Entry find(int id) {
        for (Entry entry : entries) if (entry.id == id) return entry;
        return null;
    }

    private static Timer snapshot(Entry entry, long now) {
        long millis;
        long value;
        if (entry.state == State.RUNNING) {
            millis = Math.max(0, entry.endsAt - now);
            value = entry.endsAt;
        } else if (entry.state == State.PAUSED) {
            millis = entry.remaining;
            value = entry.remaining;
        } else {
            millis = 0;
            value = entry.rangAt;
        }
        int left = (int) ((millis + 999) / 1000);
        return new Timer(entry.id, entry.label, entry.total, entry.state, left, entry.recipe, entry.step, value);
    }
}
