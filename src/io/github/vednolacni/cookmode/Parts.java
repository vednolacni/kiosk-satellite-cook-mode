// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import me.jxl.kiosk.plugins.KsTheme;

/** The small pieces the views are built from, in the KS theme. Main thread only. */
final class Parts {
    static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    /** How long a plus or minus must be held before it repeats. Fast taps would trip the Kiosk Mode exit gesture. */
    private static final long REPEAT_AFTER_MS = 450L;
    private static final long REPEAT_EVERY_MS = 180L;

    private final Context context;
    private KsTheme theme;

    Parts(Context context, KsTheme theme) {
        this.context = context;
        this.theme = theme;
    }

    void theme(KsTheme theme) {
        this.theme = theme;
    }

    KsTheme theme() {
        return theme;
    }

    Context context() {
        return context;
    }

    int px(float dp) {
        return theme.px(dp);
    }

    /**
     * Click listeners and posted work run on the main thread, outside the callbacks KS guards. The session can end
     * between a tap and the host call, and an exception thrown there would end the app, so it is dropped.
     */
    static void guard(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException sessionEnded) {
            // The plugin is stopping.
        }
    }

    static void onTap(View view, Runnable action) {
        view.setOnClickListener(tapped -> guard(action));
    }

    TextView text(String value, float sp, int weight, String role, int lines) {
        TextView view = new TextView(context);
        view.setText(value);
        theme.styleText(view, sp, weight, role);
        if (lines > 0) {
            view.setMaxLines(lines);
            view.setEllipsize(TextUtils.TruncateAt.END);
        }
        return view;
    }

    LinearLayout row() {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    LinearLayout column() {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    /** A pill button, filled for the main thing on a page. */
    TextView pill(String label, boolean filled, Runnable action) {
        TextView pill = new TextView(context);
        pill.setText(label);
        theme.stylePill(pill, filled);
        onTap(pill, action);
        return pill;
    }

    /** The same with an icon before the label. */
    TextView pill(String label, String icon, boolean filled, Runnable action) {
        TextView pill = pill(label, filled, action);
        Drawable glyph = icon(icon, filled ? "onPrimary" : "primary");
        if (glyph != null) glyph.setBounds(0, 0, px(20), px(20));
        pill.setCompoundDrawablesRelative(glyph, null, null, null);
        pill.setCompoundDrawablePadding(px(8));
        return pill;
    }

    /** Moves a pill's icon after its label, for a button that leads onward. */
    void iconAfter(TextView pill) {
        pill.setCompoundDrawablesRelative(null, null, pill.getCompoundDrawablesRelative()[0], null);
    }

    /** A round icon button with a touch target of size dp. */
    ImageView iconButton(String icon, String description, String role, int size, Runnable action) {
        ImageView button = new ImageView(context);
        button.setImageDrawable(icon(icon, role));
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setContentDescription(description);
        button.setBackground(ripple(theme.color("surfaceContainerHigh"), 100));
        button.setLayoutParams(new LinearLayout.LayoutParams(px(size), px(size)));
        onTap(button, action);
        return button;
    }

    /** Runs action on each press and again while held, so nobody has to tap fast. */
    void repeating(View view, Runnable action) {
        final Handler handler = new Handler(Looper.getMainLooper());
        final Runnable[] again = new Runnable[1];
        again[0] = () -> {
            // A view taken away under the finger may never hear that the finger left.
            if (!view.isPressed() || !view.isAttachedToWindow()) return;
            guard(action);
            handler.postDelayed(again[0], REPEAT_EVERY_MS);
        };
        view.setOnTouchListener((touched, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    guard(action);
                    touched.setPressed(true);
                    handler.postDelayed(again[0], REPEAT_AFTER_MS);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    touched.setPressed(false);
                    handler.removeCallbacks(again[0]);
                    return true;
                default:
                    return true;
            }
        });
    }

    /** A background that ripples on touch, filled with color unless it is transparent. */
    Drawable ripple(int color, float radiusDp) {
        int on = theme.color("onSurface");
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(px(radiusDp));
        GradientDrawable fill = null;
        if (color != Color.TRANSPARENT) {
            fill = new GradientDrawable();
            fill.setColor(color);
            fill.setCornerRadius(px(radiusDp));
        }
        return new RippleDrawable(ColorStateList.valueOf(Color.argb(31, Color.red(on), Color.green(on), Color.blue(on))), fill, mask);
    }

    GradientDrawable round(int color, float radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(px(radiusDp));
        return shape;
    }

    /** A view whose picture is clipped to rounded corners, with a quiet icon until the picture comes, or if none does. */
    ImageView picture(float radiusDp) {
        ImageView picture = new ImageView(context);
        picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Drawable fork = icon("mdi:silverware-fork-knife", "onSurfaceVariant");
        Drawable shape = round(theme.color("surfaceContainerHighest"), radiusDp);
        if (fork == null) {
            picture.setBackground(shape);
        } else {
            LayerDrawable layers = new LayerDrawable(new Drawable[] {shape, fork});
            layers.setLayerGravity(1, Gravity.CENTER);
            layers.setLayerSize(1, px(28), px(28));
            picture.setBackground(layers);
        }
        picture.setClipToOutline(true);
        return picture;
    }

    /** A role's color at this alpha, from 0 to 255. */
    int faded(String role, int alpha) {
        int color = theme.color(role);
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    Drawable icon(String name, String role) {
        try {
            return theme.icon(name, role);
        } catch (LinkageError olderHost) {
            return null;
        }
    }

    LinearLayout.LayoutParams wide() {
        return new LinearLayout.LayoutParams(MATCH, WRAP);
    }

    LinearLayout.LayoutParams wide(int topDp) {
        LinearLayout.LayoutParams params = wide();
        params.topMargin = px(topDp);
        return params;
    }

    LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(WRAP, WRAP);
    }

    LinearLayout.LayoutParams after(int startDp) {
        LinearLayout.LayoutParams params = wrap();
        params.setMarginStart(px(startDp));
        return params;
    }

    LinearLayout.LayoutParams fill(float weight) {
        return new LinearLayout.LayoutParams(0, WRAP, weight);
    }
}
