// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;
import me.jxl.kiosk.plugins.KsTheme;

/**
 * The small card that stays while the main view is closed: what is cooking and how long the timers have left, over the
 * dashboard and the screensaver. It keeps one size, so it never has to be measured again. A tap brings the main view back.
 * It always keeps Kiosk Satellite's colors, also under Warm kitchen, because it sits on KS's own dashboard.
 */
final class DockView extends LinearLayout {
    /** The time is read from across the kitchen, so it comes first and large. */
    private static final float TIME_SP = 28;
    /** With no timer the top line holds a word, which is long beside a clock. */
    private static final float WORD_SP = 20;

    private final Ui.Actions actions;
    private final Parts parts;
    private ImageView icon;
    private TextView time;
    private TextView name;
    private TextView stop;
    /** Whether the card shows a ring now, or null after a build, so the next showAll sets every color again. */
    private Boolean ringShown;
    private String cooking = "";
    private Strings strings = Strings.ENGLISH;
    private List<Timers.Timer> timers = java.util.Collections.emptyList();

    DockView(Context context, KsTheme theme, Ui.Actions actions) {
        super(context);
        this.actions = actions;
        parts = new Parts(context, theme);
        setOrientation(HORIZONTAL);
        setGravity(android.view.Gravity.CENTER_VERTICAL);
        Parts.onTap(this, actions::openFromDock);
        build();
        // The icon and colors come with the first showAll, so the card is never drawn without them.
        showAll();
    }

    void theme(KsTheme theme) {
        parts.theme(theme);
        build();
        showAll();
    }

    void show(String text, Strings words, List<Timers.Timer> list) {
        boolean rebuilt = words != strings;
        cooking = text;
        strings = words;
        timers = list;
        if (rebuilt) build();
        showAll();
    }

    private void build() {
        removeAllViews();
        ringShown = null;
        setPadding(parts.px(14), 0, parts.px(10), 0);
        icon = new ImageView(getContext());
        addView(icon, new LayoutParams(parts.px(28), parts.px(28)));
        LinearLayout words = parts.column();
        time = parts.text("", TIME_SP, 700, "onSurface", 1);
        time.setFontFeatureSettings("tnum");
        name = parts.text("", 14, 500, "onSurfaceVariant", 1);
        // The dock is 68 dp high with no room to spare at a large font scale. Without the font's extra padding the two lines
        // still fit at 1.3 times the size, and digits never reach that padding anyway.
        time.setIncludeFontPadding(false);
        name.setIncludeFontPadding(false);
        words.addView(time, parts.wide());
        words.addView(name, parts.wide());
        LayoutParams wordsAt = parts.fill(1);
        wordsAt.setMarginStart(parts.px(12));
        wordsAt.setMarginEnd(parts.px(8));
        addView(words, wordsAt);
        // Stop shows only on the red ringing card, where KS's filled teal pill would be faint, so it is a surface pill instead.
        stop = parts.pill(strings.stop, false, actions::stopAlarm);
        stop.setBackground(parts.ripple(parts.theme().color("surface"), 100));
        parts.theme().styleText(stop, 15, 600, "onSurface");
        stop.setMinWidth(parts.px(72));
        stop.setMinimumWidth(parts.px(72));
        // A finger reaching up from the counter needs the larger target.
        stop.setMinHeight(parts.px(48));
        stop.setMinimumHeight(parts.px(48));
        addView(stop, parts.wrap());
    }

    private void showAll() {
        boolean ringing = false;
        Timers.Timer soonest = null;
        int waiting = 0;
        for (Timers.Timer timer : timers) {
            if (timer.state == Timers.State.RINGING) {
                ringing = true;
                continue;
            }
            // A paused timer still counts, so it is not forgotten, but a running one is shown before it: it is the one that will ring.
            waiting++;
            if (soonest == null || sooner(timer, soonest)) soonest = timer;
        }
        ring(ringing);
        if (ringing) {
            time.setText(Durations.clock(0));
            time.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, TIME_SP);
            name.setText(strings.timeIsUp);
        } else if (soonest != null) {
            // One clock at this size is all that fits beside the icon, so the others are only counted.
            String clock = Durations.clock(soonest.left);
            time.setText(waiting > 1 ? clock + "  +" + (waiting - 1) : clock);
            time.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, TIME_SP);
            // A clock under the name of another recipe than its own would be read as that recipe's, so it names its own.
            // With nothing cooking there is no such recipe to mistake it for, and the timer's own name says which one counts down.
            name.setText(cooking.isEmpty() ? soonest.shown() : !soonest.from.isEmpty() ? soonest.from : cooking);
        } else {
            time.setText(strings.cooking);
            time.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, WORD_SP);
            name.setText(cooking);
        }
        name.setVisibility(name.getText().length() > 0 ? View.VISIBLE : View.GONE);
        stop.setVisibility(ringing ? View.VISIBLE : View.GONE);
    }

    private static boolean sooner(Timers.Timer timer, Timers.Timer than) {
        boolean runs = timer.state == Timers.State.RUNNING;
        boolean thanRuns = than.state == Timers.State.RUNNING;
        if (runs != thanRuns) return runs;
        return timer.left < than.left;
    }

    /**
     * The card turns red while a timer rings, the only sign of it with the main view closed. It is set here and not in
     * build, so that it clears when the ring ends and comes back after a theme change. Only a change is drawn, not each tick.
     */
    private void ring(boolean ringing) {
        if (ringShown != null && ringShown == ringing) return;
        ringShown = ringing;
        KsTheme theme = parts.theme();
        if (ringing) {
            GradientDrawable red = new GradientDrawable();
            red.setColor(theme.color("error"));
            red.setCornerRadius(parts.px(theme.radiusCard()));
            setBackground(red);
        } else {
            setBackground(theme.card());
        }
        icon.setImageDrawable(parts.icon("mdi:chef-hat", ringing ? "onError" : "primary"));
        time.setTextColor(theme.color(ringing ? "onError" : "onSurface"));
        name.setTextColor(theme.color(ringing ? "onError" : "onSurfaceVariant"));
    }
}
