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
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.CheckBox;
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
    /** How far past its edge a finger on a plus or minus may slide and still mean it. */
    private static final int SLIDE_DP = 16;
    private static final View.AccessibilityDelegate TICKED = new Ticked(true);
    private static final View.AccessibilityDelegate UNTICKED = new Ticked(false);

    private final Context base;
    private Context context;
    private KsTheme theme;

    Parts(Context context, KsTheme theme) {
        base = context;
        theme(theme);
    }

    /** New views take their scroll bars and overscroll glow from their context, whose own theme KS never changes. */
    void theme(KsTheme theme) {
        this.theme = theme;
        context = new ContextThemeWrapper(base, theme.isDark() ? android.R.style.Theme_DeviceDefault_NoActionBar : android.R.style.Theme_DeviceDefault_Light_NoActionBar);
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

    /**
     * A pill button, filled for the main thing on a page. KS's white on its teal is 4.37:1, under the 4.5:1 that small
     * text needs, so a filled label is large and bold, where 3:1 is enough. Its teal as quiet text is under 4:1 on KS's
     * grounds, so a quiet pill is tonal instead: its own fill under a text color made for it, 7.5:1 or more in every look.
     */
    TextView pill(String label, boolean filled, Runnable action) {
        TextView pill = new TextView(context);
        pill.setText(label);
        theme.stylePill(pill, filled);
        if (filled) {
            theme.styleText(pill, 19, 700, "onPrimary");
            // A long label, such as logging in the recipe app in Slovenian, takes a second line rather than losing its end.
            pill.setMaxLines(2);
        } else {
            pill.setBackground(ripple(theme.color("primaryContainer"), 100));
            pill.setTextColor(theme.color("onPrimaryContainer"));
        }
        // KS's typeface at weight 600 can draw wider than Android measures it, and TextView cuts its text off at the edge it
        // measured, so a long label lost its last letter. A shadow that shows nothing moves that edge into the padding.
        pill.setShadowLayer(pill.getTextSize() / 2, 0, 0, Color.TRANSPARENT);
        onTap(pill, action);
        return pill;
    }

    /** The same with an icon before the label. */
    TextView pill(String label, String icon, boolean filled, Runnable action) {
        TextView pill = pill(label, filled, action);
        Drawable glyph = icon(icon, filled ? "onPrimary" : "onPrimaryContainer");
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
        // An icon alone can leave a first-time cook guessing, and a long press is free on these buttons, so it names them.
        button.setTooltipText(description);
        button.setBackground(ripple(theme.color("surfaceContainerHigh"), 100));
        button.setLayoutParams(new LinearLayout.LayoutParams(px(size), px(size)));
        onTap(button, action);
        return button;
    }

    /**
     * Runs action when a press lets go, or from a moment on while it is held, so nobody has to tap fast. Not at the touch
     * itself: a finger starting a drag on a plus or minus in a list only means to move the list.
     */
    void repeating(View view, Runnable action) {
        // TalkBack, Switch Access and a keyboard send a click, not a touch, so a click takes one step. A finger never
        // reaches this listener: the touch listener below takes every event, ACTION_UP included, and it must keep
        // returning true, or a tap would step twice.
        view.setOnClickListener(clicked -> guard(action));
        view.setClickable(true);
        final Handler handler = new Handler(Looper.getMainLooper());
        final boolean[] ran = new boolean[1];
        final Runnable[] again = new Runnable[1];
        again[0] = () -> {
            // A view taken away under the finger may never hear that the finger left.
            if (!view.isPressed() || !view.isAttachedToWindow()) return;
            ran[0] = true;
            // Held on purpose, so the list around it no longer takes this for a scroll.
            if (view.getParent() != null) view.getParent().requestDisallowInterceptTouchEvent(true);
            guard(action);
            handler.postDelayed(again[0], REPEAT_EVERY_MS);
        };
        view.setOnTouchListener((touched, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    ran[0] = false;
                    touched.setPressed(true);
                    handler.postDelayed(again[0], REPEAT_AFTER_MS);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (touched.isPressed() && !near(touched, event)) {
                        // A finger that slid off has changed its mind.
                        touched.setPressed(false);
                        handler.removeCallbacks(again[0]);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    boolean tap = touched.isPressed() && !ran[0];
                    touched.setPressed(false);
                    handler.removeCallbacks(again[0]);
                    if (tap) guard(action);
                    // True, so the click listener above does not run as well.
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    touched.setPressed(false);
                    handler.removeCallbacks(again[0]);
                    return true;
                default:
                    return true;
            }
        });
    }

    /**
     * Tells TalkBack that a line to tick off is ticked or not. Sighted cooks see the filled circle and the line through
     * the words, which a screen reader cannot read.
     */
    static void ticked(View view, boolean ticked) {
        view.setAccessibilityDelegate(ticked ? TICKED : UNTICKED);
    }

    /** A check box's state for a view that draws its own. It holds no view, so one of each serves them all. */
    private static final class Ticked extends View.AccessibilityDelegate {
        private final boolean ticked;

        Ticked(boolean ticked) {
            this.ticked = ticked;
        }

        @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            info.setClassName(CheckBox.class.getName());
            info.setCheckable(true);
            info.setChecked(ticked);
        }
    }

    private boolean near(View view, MotionEvent event) {
        int slide = px(SLIDE_DP);
        return event.getX() >= -slide && event.getX() <= view.getWidth() + slide && event.getY() >= -slide && event.getY() <= view.getHeight() + slide;
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
