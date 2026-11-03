// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import android.content.Context;
import android.graphics.Color;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
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
    /** A wet finger can land twice. What comes up under it after the first touch must not take the second. */
    private static final long PAGE_GUARD_MS = 350L;

    private final Ui.Actions actions;
    private final Images images;
    private final Parts parts;
    private FrameLayout holder;
    private View banner;
    private TextView bannerText;
    private TextView bannerButton;
    private View question;
    private TextView questionText;
    private TextView questionYes;
    private TimerViews timerViews;
    private Screen screen;
    /** Kept between visits, so coming back is instant and finds the lists where they were. */
    private HomeScreen home;
    private Model.Page shownPage;
    private Strings shownStrings;
    private long shownVersion = -1;
    private int width;
    private int height;
    private boolean ingredientsOpen;
    private Model model;
    private List<Timers.Timer> timers = Collections.emptyList();
    private long pageAt;
    private boolean pageMoved;
    private boolean ignoring;

    CookView(Context context, KsTheme theme, Ui.Actions actions, Images images) {
        super(context);
        this.actions = actions;
        this.images = images;
        parts = new Parts(context, theme);
        DisplayMetrics display = context.getResources().getDisplayMetrics();
        width = display.widthPixels;
        height = display.heightPixels;
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
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            Parts.guard(actions::touched);
            // Against now, not the event's own time, which KS's window need not count the same way.
            ignoring = SystemClock.uptimeMillis() - pageAt < PAGE_GUARD_MS;
        }
        // The whole gesture goes: a page must not see the rest of a touch it never saw begin.
        return ignoring || super.dispatchTouchEvent(event);
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        // On a slow tablet building and laying out a page takes longer than the guard itself.
        if (pageMoved) {
            pageMoved = false;
            pageAt = SystemClock.uptimeMillis();
        }
    }

    @Override protected void onSizeChanged(int newWidth, int newHeight, int oldWidth, int oldHeight) {
        super.onSizeChanged(newWidth, newHeight, oldWidth, oldHeight);
        // Turning the tablet changes the width. A bar coming or going takes a little height, which is no reason for a new page.
        boolean turned = Math.abs(newWidth - width) >= parts.px(SAME_WIDTH_DP);
        width = newWidth;
        if (turned) height = newHeight;
        if (turned && model != null) post(() -> Parts.guard(this::rebuild));
    }

    private void rebuild() {
        leaveScreen();
        home = null;
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
        question = questionCard(strings);
        addView(question, new LayoutParams(Parts.MATCH, Parts.MATCH));
        addView(timerViews.sheet(), new LayoutParams(Parts.MATCH, Parts.MATCH));
        addView(timerViews.alarm(), new LayoutParams(Parts.MATCH, Parts.MATCH));
        timerViews.show(timers);
        showModel();
    }

    /** A question with something to lose behind it. A touch beside the card means no. */
    private View questionCard(Strings strings) {
        FrameLayout scrim = new FrameLayout(getContext());
        scrim.setBackgroundColor(Color.argb(140, 0, 0, 0));
        scrim.setVisibility(GONE);
        Parts.onTap(scrim, () -> actions.confirm(false));
        LinearLayout card = parts.column();
        card.setBackground(parts.theme().card());
        card.setPadding(parts.px(28), parts.px(24), parts.px(28), parts.px(20));
        card.setClickable(true);
        questionText = parts.text("", 20, 500, "onSurface", 0);
        card.addView(questionText, parts.wide());
        LinearLayout buttons = parts.row();
        buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        buttons.addView(parts.pill(strings.cancel, false, () -> actions.confirm(false)), parts.wrap());
        questionYes = parts.pill("", true, () -> actions.confirm(true));
        questionYes.setMinHeight(parts.px(56));
        buttons.addView(questionYes, parts.after(8));
        card.addView(buttons, parts.wide(20));
        FrameLayout.LayoutParams cardAt = new FrameLayout.LayoutParams(Math.min(parts.px(480), width - parts.px(32)), Parts.WRAP, Gravity.CENTER);
        scrim.addView(card, cardAt);
        return scrim;
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
        question.setVisibility(current.confirm != null ? View.VISIBLE : View.GONE);
        if (current.confirm != null) {
            questionText.setText(current.confirm);
            questionYes.setText(current.confirmLabel);
        }
        if (screen == null || current.page != shownPage) {
            leaveScreen();
            screen = screen(current);
            shownPage = current.page;
            shownVersion = -1;
            holder.removeAllViews();
            holder.addView(screen.view(), new LayoutParams(Parts.MATCH, Parts.MATCH));
            pageAt = SystemClock.uptimeMillis();
            pageMoved = true;
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
                return new FinishScreen(parts, actions, images, current.strings, height);
            default:
                if (home == null) home = new HomeScreen(parts, actions, images, current.strings, wide, width, height);
                return home;
        }
    }

    /** The page is going away. What belongs to the cooking outlives it. */
    private void leaveScreen() {
        if (screen instanceof StepScreen) ingredientsOpen = ((StepScreen) screen).panelOpen();
        if (screen instanceof HomeScreen) ((HomeScreen) screen).leave();
        screen = null;
    }
}
