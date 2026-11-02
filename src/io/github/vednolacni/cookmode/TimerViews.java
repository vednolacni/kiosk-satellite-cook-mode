// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/**
 * What the main view shows of the timers: a strip of running ones above the page, a sheet to start and change them, and
 * the card of a ringing one over everything. Each second only the numbers change, so nothing moves under a finger.
 */
final class TimerViews {
    private static final int[] PRESET_MINUTES = {1, 2, 3, 5, 8, 10, 15, 20, 30, 45, 60, 90};
    private static final int SHEET_WIDTH_DP = 560;

    private static final class Shown {
        final Timers.Timer timer;
        final TextView time;

        Shown(Timers.Timer timer, TextView time) {
            this.timer = timer;
            this.time = time;
        }
    }

    private final Parts parts;
    private final Ui.Actions actions;
    private Strings strings;

    private final HorizontalScrollView strip;
    private final LinearLayout stripRow;
    private final List<Shown> stripShown = new ArrayList<>();
    private String stripKey = "";

    private final FrameLayout sheet;
    private final LinearLayout sheetRows;
    private final TextView sheetEmpty;
    private final List<Shown> sheetShown = new ArrayList<>();
    private String sheetKey = "";

    private final FrameLayout alarm;
    private final TextView alarmLabels;
    private List<Timers.Timer> latest = new ArrayList<>();

    TimerViews(Parts parts, Ui.Actions actions, Strings strings) {
        this.parts = parts;
        this.actions = actions;
        this.strings = strings;

        strip = new HorizontalScrollView(parts.context());
        strip.setHorizontalScrollBarEnabled(false);
        stripRow = parts.row();
        stripRow.setPadding(parts.px(16), parts.px(8), parts.px(16), parts.px(4));
        strip.addView(stripRow, new FrameLayout.LayoutParams(Parts.WRAP, Parts.WRAP));
        strip.setVisibility(View.GONE);

        // The sheet.
        sheet = new FrameLayout(parts.context());
        sheet.setBackgroundColor(Color.argb(140, 0, 0, 0));
        sheet.setVisibility(View.GONE);
        Parts.onTap(sheet, this::closeSheet);
        LinearLayout card = parts.column();
        card.setBackground(parts.theme().card());
        card.setPadding(parts.px(20), parts.px(14), parts.px(20), parts.px(20));
        card.setClickable(true);
        LinearLayout header = parts.row();
        header.addView(parts.text(strings.timers, 22, 700, "onSurface", 1), parts.fill(1));
        header.addView(parts.iconButton("mdi:close", strings.close, "onSurface", 48, this::closeSheet));
        card.addView(header, parts.wide());
        sheetRows = parts.column();
        card.addView(sheetRows, parts.wide());
        sheetEmpty = parts.text(strings.newTimer, 16, 700, "onSurface", 1);
        card.addView(sheetEmpty, parts.wide(18));
        FlowLayout presets = new FlowLayout(parts.context(), parts.px(8));
        for (int minutes : PRESET_MINUTES) presets.addView(preset(minutes));
        card.addView(presets, parts.wide(10));
        ScrollView scroll = new ScrollView(parts.context());
        scroll.addView(card, new ScrollView.LayoutParams(Parts.MATCH, Parts.WRAP));
        int widest = Math.min(parts.px(SHEET_WIDTH_DP), parts.context().getResources().getDisplayMetrics().widthPixels - parts.px(32));
        FrameLayout.LayoutParams scrollAt = new FrameLayout.LayoutParams(widest, Parts.WRAP, Gravity.CENTER);
        int margin = parts.px(16);
        scrollAt.setMargins(margin, margin, margin, margin);
        sheet.addView(scroll, scrollAt);

        // The alarm. A touch anywhere on it stops the sound, whatever a cook has on their hands.
        alarm = new FrameLayout(parts.context());
        alarm.setBackgroundColor(Color.argb(190, 0, 0, 0));
        alarm.setVisibility(View.GONE);
        Parts.onTap(alarm, actions::stopAlarm);
        LinearLayout ringing = parts.column();
        ringing.setBackground(parts.theme().card());
        ringing.setGravity(Gravity.CENTER_HORIZONTAL);
        ringing.setPadding(parts.px(32), parts.px(28), parts.px(32), parts.px(28));
        ImageView bell = new ImageView(parts.context());
        bell.setImageDrawable(parts.icon("mdi:bell-ring", "error"));
        ringing.addView(bell, new LinearLayout.LayoutParams(parts.px(56), parts.px(56)));
        TextView title = parts.text(strings.timeIsUp, 34, 700, "onSurface", 1);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleAt = parts.wide(10);
        ringing.addView(title, titleAt);
        alarmLabels = parts.text("", 20, 400, "onSurfaceVariant", 4);
        alarmLabels.setGravity(Gravity.CENTER);
        ringing.addView(alarmLabels, parts.wide(6));
        LinearLayout buttons = parts.row();
        TextView more = parts.pill("+1 min", false, this::oneMoreMinute);
        more.setMinHeight(parts.px(72));
        buttons.addView(more, parts.wrap());
        TextView stop = parts.pill(strings.stop, "mdi:bell-off", true, actions::stopAlarm);
        parts.theme().styleText(stop, 22, 700, "onPrimary");
        stop.setMinHeight(parts.px(72));
        stop.setMinWidth(parts.px(180));
        buttons.addView(stop, parts.after(12));
        LinearLayout.LayoutParams buttonsAt = parts.wrap();
        buttonsAt.topMargin = parts.px(22);
        ringing.addView(buttons, buttonsAt);
        FrameLayout.LayoutParams ringingAt = new FrameLayout.LayoutParams(Parts.WRAP, Parts.WRAP, Gravity.CENTER);
        ringingAt.setMargins(margin, margin, margin, margin);
        alarm.addView(ringing, ringingAt);
    }

    View strip() {
        return strip;
    }

    View sheet() {
        return sheet;
    }

    View alarm() {
        return alarm;
    }

    void openSheet() {
        sheet.setVisibility(View.VISIBLE);
    }

    private void closeSheet() {
        sheet.setVisibility(View.GONE);
    }

    /** Show the timers as they stand now. Runs each second while any exists. */
    void show(List<Timers.Timer> timers) {
        latest = timers;
        showStrip(timers);
        showSheet(timers);
        showAlarm(timers);
    }

    // The strip.

    private void showStrip(List<Timers.Timer> timers) {
        strip.setVisibility(timers.isEmpty() ? View.GONE : View.VISIBLE);
        String key = key(timers);
        if (!key.equals(stripKey)) {
            stripKey = key;
            stripRow.removeAllViews();
            stripShown.clear();
            for (int i = 0; i < timers.size(); i++) {
                Timers.Timer timer = timers.get(i);
                LinearLayout pill = parts.row();
                boolean rings = timer.state == Timers.State.RINGING;
                pill.setBackground(parts.ripple(parts.theme().color(rings ? "surfaceContainerHighest" : "surfaceContainerHigh"), 100));
                pill.setPadding(parts.px(14), parts.px(6), parts.px(14), parts.px(6));
                pill.setMinimumHeight(parts.px(44));
                TextView label = parts.text(timer.label, 14, 500, "onSurfaceVariant", 1);
                label.setMaxWidth(parts.px(200));
                pill.addView(label, parts.wrap());
                TextView time = parts.text("", 18, 700, rings ? "error" : timer.state == Timers.State.PAUSED ? "onSurfaceVariant" : "onSurface", 1);
                time.setFontFeatureSettings("tnum");
                pill.addView(time, parts.after(10));
                Parts.onTap(pill, this::openSheet);
                stripRow.addView(pill, i == 0 ? parts.wrap() : parts.after(8));
                stripShown.add(new Shown(timer, time));
            }
        }
        tick(stripShown, timers);
    }

    // The sheet.

    private void showSheet(List<Timers.Timer> timers) {
        String key = key(timers);
        if (!key.equals(sheetKey)) {
            sheetKey = key;
            sheetRows.removeAllViews();
            sheetShown.clear();
            for (Timers.Timer timer : timers) sheetRows.addView(sheetRow(timer), parts.wide(10));
        }
        tick(sheetShown, timers);
    }

    private View sheetRow(Timers.Timer timer) {
        LinearLayout row = parts.row();
        LinearLayout words = parts.column();
        words.addView(parts.text(timer.label, 14, 500, "onSurfaceVariant", 2), parts.wide());
        TextView time = parts.text("", 30, 700, "onSurface", 1);
        time.setFontFeatureSettings("tnum");
        words.addView(time, parts.wide());
        row.addView(words, parts.fill(1));
        if (timer.state != Timers.State.RINGING) {
            row.addView(change("−1", -60, timer.id), parts.wrap());
            row.addView(change("+1", 60, timer.id), parts.after(6));
            boolean paused = timer.state == Timers.State.PAUSED;
            row.addView(parts.iconButton(paused ? "mdi:play" : "mdi:pause", paused ? strings.resume : strings.pause, "onSurface", 48, () -> {
                if (paused) actions.resumeTimer(timer.id);
                else actions.pauseTimer(timer.id);
            }), gapped(48));
        }
        row.addView(parts.iconButton("mdi:close", strings.cancel, "error", 48, () -> actions.cancelTimer(timer.id)), gapped(48));
        sheetShown.add(new Shown(timer, time));
        return row;
    }

    private TextView change(String label, int seconds, int id) {
        TextView button = parts.text(label, 16, 700, "onSurface", 1);
        button.setGravity(Gravity.CENTER);
        button.setBackground(parts.ripple(parts.theme().color("surfaceContainerHighest"), 100));
        button.setMinWidth(parts.px(48));
        button.setMinHeight(parts.px(48));
        parts.repeating(button, () -> actions.addToTimer(id, seconds));
        return button;
    }

    private TextView preset(int minutes) {
        TextView chip = parts.text(strings.duration(minutes), 16, 600, "onSurface", 1);
        chip.setGravity(Gravity.CENTER);
        chip.setBackground(parts.ripple(parts.theme().color("surfaceContainerHighest"), 100));
        chip.setPadding(parts.px(16), parts.px(10), parts.px(16), parts.px(10));
        chip.setMinHeight(parts.px(48));
        chip.setMinWidth(parts.px(84));
        Parts.onTap(chip, () -> {
            actions.startTimer(strings.duration(minutes), minutes * 60);
            closeSheet();
        });
        return chip;
    }

    // The alarm.

    private void showAlarm(List<Timers.Timer> timers) {
        StringBuilder labels = new StringBuilder();
        for (Timers.Timer timer : timers) {
            if (timer.state != Timers.State.RINGING) continue;
            if (labels.length() > 0) labels.append('\n');
            labels.append(timer.label);
        }
        alarm.setVisibility(labels.length() > 0 ? View.VISIBLE : View.GONE);
        alarmLabels.setText(labels);
    }

    private void oneMoreMinute() {
        for (Timers.Timer timer : latest) if (timer.state == Timers.State.RINGING) actions.addToTimer(timer.id, 60);
    }

    // Shared.

    /** The timers in order with their states. The numbers change without it. */
    private static String key(List<Timers.Timer> timers) {
        StringBuilder key = new StringBuilder();
        for (Timers.Timer timer : timers) key.append(timer.id).append(timer.state.ordinal()).append(',');
        return key.toString();
    }

    private static void tick(List<Shown> shown, List<Timers.Timer> timers) {
        for (int i = 0; i < shown.size() && i < timers.size(); i++) {
            shown.get(i).time.setText(timers.get(i).state == Timers.State.RINGING ? "0:00" : Durations.clock(timers.get(i).left));
        }
    }

    private LinearLayout.LayoutParams gapped(int size) {
        LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(parts.px(size), parts.px(size));
        at.setMarginStart(parts.px(6));
        return at;
    }
}
