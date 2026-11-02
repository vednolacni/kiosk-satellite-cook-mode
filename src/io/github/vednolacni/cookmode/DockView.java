// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import android.content.Context;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;
import me.jxl.kiosk.plugins.KsTheme;

/**
 * The small card that stays while the main view is closed: what is cooking and how long the timers have left, over the
 * dashboard and the screensaver. It keeps one size, so it never has to be measured again. A tap brings the main view back.
 */
final class DockView extends LinearLayout {
    /** How many timers fit on its second line. */
    private static final int TIMERS_SHOWN = 3;

    private final Ui.Actions actions;
    private final Parts parts;
    private TextView top;
    private TextView bottom;
    private TextView stop;
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
        setBackground(parts.theme().card());
        setPadding(parts.px(14), 0, parts.px(10), 0);
        ImageView icon = new ImageView(getContext());
        icon.setImageDrawable(parts.icon("mdi:chef-hat", "primary"));
        addView(icon, new LayoutParams(parts.px(28), parts.px(28)));
        LinearLayout words = parts.column();
        top = parts.text("", 14, 600, "onSurface", 1);
        bottom = parts.text("", 15, 700, "onSurfaceVariant", 1);
        bottom.setFontFeatureSettings("tnum");
        words.addView(top, parts.wide());
        words.addView(bottom, parts.wide());
        LayoutParams wordsAt = parts.fill(1);
        wordsAt.setMarginStart(parts.px(12));
        wordsAt.setMarginEnd(parts.px(8));
        addView(words, wordsAt);
        stop = parts.pill(strings.stop, true, actions::stopAlarm);
        stop.setMinWidth(parts.px(72));
        stop.setMinimumWidth(parts.px(72));
        addView(stop, parts.wrap());
    }

    private void showAll() {
        boolean ringing = false;
        StringBuilder times = new StringBuilder();
        String firstLabel = "";
        int count = 0;
        for (Timers.Timer timer : timers) {
            if (timer.state == Timers.State.RINGING) {
                ringing = true;
                if (firstLabel.isEmpty()) firstLabel = timer.label;
                continue;
            }
            if (firstLabel.isEmpty()) firstLabel = timer.label;
            if (count++ == TIMERS_SHOWN) continue;
            if (times.length() > 0) times.append("  ·  ");
            times.append(Durations.clock(timer.left));
        }
        top.setText(cooking.isEmpty() ? firstLabel : cooking);
        bottom.setText(ringing ? strings.timeIsUp : times.length() > 0 ? times : strings.cooking);
        bottom.setTextColor(parts.theme().color(ringing ? "error" : "onSurfaceVariant"));
        stop.setVisibility(ringing ? View.VISIBLE : View.GONE);
    }
}
