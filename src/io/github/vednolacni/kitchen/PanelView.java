// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.kitchen;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import me.jxl.kiosk.plugins.KsTheme;

/**
 * The full screen meal plan: a header with the date and the panel's buttons, today's meals as cards and the next
 * days as rows, side by side on a wide screen. Built and changed on the main thread only.
 */
final class PanelView {
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    /** From this width in dp a landscape screen shows the next days beside today. */
    private static final int WIDE_DP = 720;

    final LinearLayout root;
    private final Context context;
    private final Ui.Actions actions;
    private final TextView title;
    private final TextView date;
    private final TextView openApp;
    private final ImageView refresh;
    private final ImageView close;
    private final TextView message;
    private final LinearLayout content;
    private KsTheme theme;
    private PanelModel model;

    PanelView(Context context, KsTheme theme, Ui.Actions actions) {
        this.context = context;
        this.theme = theme;
        this.actions = actions;
        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout heading = new LinearLayout(context);
        heading.setOrientation(LinearLayout.VERTICAL);
        title = new TextView(context);
        date = new TextView(context);
        heading.addView(title);
        heading.addView(date);
        header.addView(heading, new LinearLayout.LayoutParams(0, WRAP, 1f));
        openApp = new TextView(context);
        openApp.setOnClickListener(view -> tap(actions::openApp));
        LinearLayout.LayoutParams openParams = new LinearLayout.LayoutParams(WRAP, WRAP);
        openParams.setMarginEnd(theme.px(8));
        header.addView(openApp, openParams);
        refresh = button(actions::refresh);
        close = button(actions::close);
        header.addView(refresh, new LinearLayout.LayoutParams(theme.px(48), theme.px(48)));
        header.addView(close, new LinearLayout.LayoutParams(theme.px(48), theme.px(48)));
        root.addView(header, new LinearLayout.LayoutParams(MATCH, WRAP));

        message = new TextView(context);
        message.setVisibility(View.GONE);
        root.addView(message, spaced(8));

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        content = new LinearLayout(context);
        scroll.addView(content, new FrameLayout.LayoutParams(MATCH, WRAP));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(MATCH, 0, 1f);
        scrollParams.topMargin = theme.px(8);
        root.addView(scroll, scrollParams);
        restyle(theme);
    }

    /** KS switched between its light and dark themes. */
    void restyle(KsTheme theme) {
        this.theme = theme;
        root.setBackgroundColor(theme.color("surface"));
        root.setPadding(theme.px(28), theme.px(20), theme.px(28), theme.px(12));
        theme.styleText(title, 30, 600, "onSurface");
        theme.styleText(date, 16, 400, "onSurfaceVariant");
        theme.stylePill(openApp, false);
        openApp.setCompoundDrawablesRelative(sized(icon("mdi:silverware-fork-knife", "primary"), 20), null, null, null);
        openApp.setCompoundDrawablePadding(theme.px(8));
        refresh.setImageDrawable(icon("mdi:refresh", "onSurfaceVariant"));
        refresh.setBackground(ripple(Color.TRANSPARENT, 100));
        close.setImageDrawable(icon("mdi:close", "onSurface"));
        close.setBackground(ripple(Color.TRANSPARENT, 100));
        if (model != null) render(model);
    }

    void render(PanelModel model) {
        this.model = model;
        title.setText(model.title);
        date.setText(model.date);
        openApp.setText(model.strings.openApp);
        openApp.setVisibility(model.canOpenApp ? View.VISIBLE : View.GONE);
        refresh.setContentDescription(model.strings.refresh);
        close.setContentDescription(model.strings.close);
        if (model.message == null) {
            message.setVisibility(View.GONE);
        } else {
            message.setText(model.message);
            theme.styleText(message, 16, 500, model.error ? "error" : "onSurfaceVariant");
            message.setVisibility(View.VISIBLE);
        }

        LinearLayout today = column();
        today.addView(text(model.strings.today, 20, 600, "onSurface", 1), spaced(8));
        if (model.today.isEmpty()) today.addView(text(model.strings.nothingToday, 16, 400, "onSurfaceVariant", 3), spaced(8));
        for (PanelModel.Row row : model.today) today.addView(card(row), spaced(12));

        LinearLayout next = column();
        next.addView(text(model.strings.upcoming, 20, 600, "onSurface", 1), spaced(8));
        if (model.upcoming.isEmpty()) next.addView(text(model.strings.nothingUpcoming, 16, 400, "onSurfaceVariant", 3), spaced(8));
        for (PanelModel.Day day : model.upcoming) {
            next.addView(text(day.label, 15, 600, "primary", 1), spaced(16));
            for (PanelModel.Row row : day.rows) next.addView(line(row), spaced(2));
        }

        content.removeAllViews();
        if (wide()) {
            content.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams first = new LinearLayout.LayoutParams(0, WRAP, 1f);
            first.setMarginEnd(theme.px(32));
            content.addView(today, first);
            content.addView(next, new LinearLayout.LayoutParams(0, WRAP, 1f));
        } else {
            content.setOrientation(LinearLayout.VERTICAL);
            content.addView(today, new LinearLayout.LayoutParams(MATCH, WRAP));
            content.addView(next, spaced(28));
        }
    }

    /** One of today's meals: a colored stripe for its calendar, the meal type, the dish and its time. */
    private View card(PanelModel.Row row) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(ripple(theme.color("surfaceContainerHigh"), theme.radiusRow()));
        card.setPadding(theme.px(16), theme.px(16), theme.px(12), theme.px(16));
        card.setMinimumHeight(theme.px(88));
        View stripe = new View(context);
        GradientDrawable bar = new GradientDrawable();
        bar.setColor(row.color);
        bar.setCornerRadius(theme.px(3));
        stripe.setBackground(bar);
        card.addView(stripe, new LinearLayout.LayoutParams(theme.px(6), MATCH));
        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        if (!row.overline.isEmpty()) texts.addView(text(row.overline, 14, 500, "onSurfaceVariant", 1));
        texts.addView(text(row.dish, 24, 600, "onSurface", 3));
        if (!row.detail.isEmpty()) texts.addView(text(row.detail, 14, 400, "onSurfaceVariant", 1));
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, WRAP, 1f);
        textParams.setMarginStart(theme.px(16));
        card.addView(texts, textParams);
        ImageView opens = new ImageView(context);
        opens.setImageDrawable(icon(row.recipe ? "mdi:book-open-page-variant" : "mdi:chevron-right", "onSurfaceVariant"));
        LinearLayout.LayoutParams opensParams = new LinearLayout.LayoutParams(WRAP, WRAP);
        opensParams.setMarginStart(theme.px(8));
        card.addView(opens, opensParams);
        card.setOnClickListener(view -> tap(() -> actions.open(row.meal)));
        return card;
    }

    /** A meal of the next days: a dot in its calendar's color, the dish, then its type and time. */
    private View line(PanelModel.Row row) {
        LinearLayout line = new LinearLayout(context);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setBackground(ripple(Color.TRANSPARENT, theme.radiusRow()));
        line.setPadding(theme.px(12), theme.px(10), theme.px(12), theme.px(10));
        line.setMinimumHeight(theme.px(48));
        View dot = new View(context);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(row.color);
        dot.setBackground(circle);
        line.addView(dot, new LinearLayout.LayoutParams(theme.px(10), theme.px(10)));
        LinearLayout.LayoutParams dishParams = new LinearLayout.LayoutParams(0, WRAP, 1f);
        dishParams.setMarginStart(theme.px(14));
        line.addView(text(row.dish, 18, 500, "onSurface", 2), dishParams);
        String side = row.overline.isEmpty() ? row.detail : row.detail.isEmpty() ? row.overline : row.overline + " · " + row.detail;
        if (!side.isEmpty()) {
            LinearLayout.LayoutParams sideParams = new LinearLayout.LayoutParams(WRAP, WRAP);
            sideParams.setMarginStart(theme.px(12));
            line.addView(text(side, 14, 400, "onSurfaceVariant", 1), sideParams);
        }
        line.setOnClickListener(view -> tap(() -> actions.open(row.meal)));
        return line;
    }

    private ImageView button(Runnable action) {
        ImageView button = new ImageView(context);
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setOnClickListener(view -> tap(action));
        return button;
    }

    private LinearLayout column() {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    private TextView text(String value, float sp, int weight, String role, int lines) {
        TextView view = new TextView(context);
        view.setText(value);
        theme.styleText(view, sp, weight, role);
        view.setMaxLines(lines);
        view.setEllipsize(TextUtils.TruncateAt.END);
        return view;
    }

    private LinearLayout.LayoutParams spaced(int topDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(MATCH, WRAP);
        params.topMargin = theme.px(topDp);
        return params;
    }

    /** A background that ripples on touch, filled with color unless it is transparent. */
    private Drawable ripple(int color, float radiusDp) {
        int on = theme.color("onSurface");
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(theme.px(radiusDp));
        GradientDrawable fill = null;
        if (color != Color.TRANSPARENT) {
            fill = new GradientDrawable();
            fill.setColor(color);
            fill.setCornerRadius(theme.px(radiusDp));
        }
        return new RippleDrawable(ColorStateList.valueOf(Color.argb(31, Color.red(on), Color.green(on), Color.blue(on))), fill, mask);
    }

    private Drawable icon(String name, String role) {
        return AndroidUi.icon(theme, name, role);
    }

    private Drawable sized(Drawable drawable, int dp) {
        if (drawable != null) drawable.setBounds(0, 0, theme.px(dp), theme.px(dp));
        return drawable;
    }

    private boolean wide() {
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        return metrics.widthPixels > metrics.heightPixels && metrics.widthPixels / metrics.density >= WIDE_DP;
    }

    /**
     * Click listeners run on the main thread, outside the callbacks KS guards. The session can end between a tap and
     * the host call, and an exception thrown here would end the app, so it is dropped.
     */
    private static void tap(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException sessionEnded) {
            // Nothing to do: the plugin is gone.
        }
    }
}
