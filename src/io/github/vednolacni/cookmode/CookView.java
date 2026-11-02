// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import android.content.Context;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Collections;
import java.util.List;
import me.jxl.kiosk.plugins.KsTheme;

/**
 * The one long-lived view of the main overlay. Creating an overlay takes seconds on a slow tablet, so the pages swap
 * inside it. A page is built when it comes and then changes in place, and a banner, the running timers and the alarm
 * sit around whichever page is up. Main thread only.
 */
final class CookView extends FrameLayout {
    /** From this width the pages lay out in two columns. */
    private static final int WIDE_FROM_DP = 720;
    /** The display's size is a guess at the overlay's until it is laid out. Closer than this needs no second build. */
    private static final int SAME_WIDTH_DP = 48;

    private final Ui.Actions actions;
    private final Images images;
    private final Parts parts;
    private FrameLayout holder;
    private View banner;
    private TextView bannerText;
    private TextView bannerButton;
    private TimerViews timerViews;
    private Screen screen;
    private Model.Page shownPage;
    private Strings shownStrings;
    private long shownVersion = -1;
    private int width;
    private int height;
    private boolean ingredientsOpen;
    private Model model;
    private List<Timers.Timer> timers = Collections.emptyList();

    CookView(Context context, KsTheme theme, Ui.Actions actions, Images images) {
        super(context);
        this.actions = actions;
        this.images = images;
        parts = new Parts(context, theme);
        DisplayMetrics display = context.getResources().getDisplayMetrics();
        width = display.widthPixels;
        height = display.heightPixels;
        // The search field must not take the first focus, or opening the view would raise the keyboard.
        setFocusableInTouchMode(true);
    }

    void show(Model next) {
        model = next;
        if (holder == null || next.strings != shownStrings) rebuild();
        else showModel();
    }

    void timers(List<Timers.Timer> list) {
        timers = list;
        if (timerViews != null) timerViews.show(list);
    }

    void theme(KsTheme theme) {
        parts.theme(theme);
        if (model != null) rebuild();
    }

    /** The view is going away. */
    void leave() {
        if (screen instanceof HomeScreen) ((HomeScreen) screen).leave();
        hideKeyboard();
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) Parts.guard(actions::touched);
        return super.dispatchTouchEvent(event);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        requestFocus();
    }

    @Override protected void onSizeChanged(int newWidth, int newHeight, int oldWidth, int oldHeight) {
        super.onSizeChanged(newWidth, newHeight, oldWidth, oldHeight);
        // Height alone changes when the keyboard opens, and a new page would close it again.
        boolean turned = Math.abs(newWidth - width) >= parts.px(SAME_WIDTH_DP);
        width = newWidth;
        if (turned) height = newHeight;
        if (turned && model != null) post(() -> Parts.guard(this::rebuild));
    }

    private void rebuild() {
        leaveScreen();
        removeAllViews();
        setBackgroundColor(parts.theme().color("surface"));
        Strings strings = model.strings;
        shownStrings = strings;

        LinearLayout column = parts.column();
        LinearLayout bar = parts.row();
        bar.setBackgroundColor(parts.theme().color("surfaceContainerHighest"));
        bar.setPadding(parts.px(20), parts.px(8), parts.px(12), parts.px(8));
        bannerText = parts.text("", 15, 500, "error", 3);
        bar.addView(bannerText, parts.fill(1));
        bannerButton = parts.pill(strings.retry, false, actions::refresh);
        bar.addView(bannerButton, parts.after(12));
        banner = bar;
        column.addView(banner, parts.wide());

        timerViews = new TimerViews(parts, actions, strings);
        column.addView(timerViews.strip(), parts.wide());
        holder = new FrameLayout(getContext());
        column.addView(holder, new LinearLayout.LayoutParams(Parts.MATCH, 0, 1));
        addView(column, new LayoutParams(Parts.MATCH, Parts.MATCH));
        addView(timerViews.sheet(), new LayoutParams(Parts.MATCH, Parts.MATCH));
        addView(timerViews.alarm(), new LayoutParams(Parts.MATCH, Parts.MATCH));
        timerViews.show(timers);
        showModel();
    }

    private void showModel() {
        Model current = model;
        boolean showsBanner = current.banner != null;
        banner.setVisibility(showsBanner ? View.VISIBLE : View.GONE);
        if (showsBanner) {
            bannerText.setText(current.banner);
            // There is nothing to try again when all the timers are taken.
            bannerButton.setText(current.banner.equals(current.strings.tooManyTimers) ? current.strings.close : current.strings.retry);
        }
        if (screen == null || current.page != shownPage) {
            leaveScreen();
            screen = screen(current);
            shownPage = current.page;
            shownVersion = -1;
            holder.removeAllViews();
            holder.addView(screen.view(), new LayoutParams(Parts.MATCH, Parts.MATCH));
        }
        if (current.pageVersion != shownVersion) {
            shownVersion = current.pageVersion;
            screen.show(current);
        }
    }

    private Screen screen(Model current) {
        boolean wide = width >= parts.px(WIDE_FROM_DP);
        switch (current.page) {
            case RECIPE:
                return new RecipeScreen(parts, actions, images, current.strings, wide, height);
            case STEP:
                return new StepScreen(parts, actions, images, current.strings, wide, height, ingredientsOpen, timerViews::openSheet);
            case FINISH:
                return new FinishScreen(parts, actions, images, current.strings);
            default:
                return new HomeScreen(parts, actions, images, current.strings, wide, width);
        }
    }

    /** The page is going away. What belongs to the cooking outlives it. */
    private void leaveScreen() {
        if (screen instanceof StepScreen) ingredientsOpen = ((StepScreen) screen).panelOpen();
        if (screen instanceof HomeScreen) {
            ((HomeScreen) screen).leave();
            hideKeyboard();
        }
        screen = null;
    }

    private void hideKeyboard() {
        InputMethodManager keyboard = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null && getWindowToken() != null) keyboard.hideSoftInputFromWindow(getWindowToken(), 0);
        requestFocus();
    }
}
