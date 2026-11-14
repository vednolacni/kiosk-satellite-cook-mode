// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/**
 * Cooking itself: one step in large letters, readable from across the kitchen, with the steps around it fading away.
 * The durations the text names are timers to tap, the step's ingredients sit under it, and the bar stays in one place
 * so a thumb finds Next without looking.
 */
final class StepScreen implements Screen {
    /** Below this height the steps around the current one take room the current one needs. */
    private static final int PEEKS_FROM_DP = 520;
    /** A wet finger can land twice. Steps change at most this often by touch, so one press cannot skip a step. */
    private static final long MOVE_GAP_MS = 350L;
    /** How far outside a timer chip a touch still counts as on it. */
    private static final int CHIP_MARGIN_DP = 8;
    private static final float LINE_SPACING = 1.28f;
    private static final int TEXT_STEP_SP = 2;
    private static final int NOTE_GAP_DP = 12;
    private static final int TITLE_GAP_DP = 20;
    private static final int CHIPS_GAP_DP = 8;

    private final Parts parts;
    private final Ui.Actions actions;
    private final View root;
    private final ImageView thumbnail;
    private final TextView name;
    private final TextView subtitle;
    private final TextView previousPeek;
    private final TextView nextPeek;
    private final boolean peeks;
    private final ScrollView scroll;
    private final LinearLayout story;
    private final TextView text;
    private final TextView fixedNote;
    private final Runnable openTimers;
    private final Swipes swipes;
    private final TextView chipsTitle;
    private final FlowLayout chips;
    private final View panel;
    private final IngredientList ingredients;
    private final View meter;
    private final LinearLayout meterTrack;
    private final TextView counter;
    private final TextView ready;
    private final ImageView readButton;
    private final ImageView panelButton;
    private final TextView next;
    private final List<StepChip> stepChips = new ArrayList<>();
    private final Images images;
    private String shownImage;
    private String shownStep;
    private int textLeast;
    private int textMost;
    private int textShown;
    private boolean fitComing;
    /** What the size was last worked out for. The same room and words need no second look. */
    private int fitWidth;
    private int fitRoom;
    private CharSequence fitWords;
    private boolean panelOpen;
    private ChipSpan pressedChip;
    private long lastMove;

    private static final class StepChip {
        final Recipe.Ingredient ingredient;
        final TextView view;

        StepChip(Recipe.Ingredient ingredient, TextView view) {
            this.ingredient = ingredient;
            this.view = view;
        }
    }

    StepScreen(Parts parts, Ui.Actions actions, Images images, Strings strings, boolean wide, int heightPx, boolean panelOpen, Runnable openTimers) {
        this.parts = parts;
        this.actions = actions;
        this.images = images;
        this.panelOpen = panelOpen;
        this.openTimers = openTimers;
        peeks = heightPx >= parts.px(PEEKS_FROM_DP);
        Runnable forward = () -> move(actions::next);
        Runnable backward = () -> move(actions::previous);

        LinearLayout page = parts.column();
        root = page;

        // The recipe, and the way out.
        LinearLayout header = parts.row();
        header.setPadding(parts.px(16), parts.px(10), parts.px(16), parts.px(6));
        thumbnail = parts.picture(parts.theme().radiusControl());
        header.addView(thumbnail, new LinearLayout.LayoutParams(parts.px(48), parts.px(48)));
        LinearLayout titles = parts.column();
        name = parts.text("", 16, 700, "onSurface", 1);
        subtitle = parts.text("", 14, 500, "onSurfaceVariant", 1);
        titles.addView(name, parts.wide());
        titles.addView(subtitle, parts.wide());
        LinearLayout.LayoutParams titlesAt = parts.fill(1);
        titlesAt.setMarginStart(parts.px(12));
        titlesAt.setMarginEnd(parts.px(12));
        header.addView(titles, titlesAt);
        header.addView(parts.iconButton("mdi:home-outline", strings.plan, "onSurface", 48, actions::home));
        header.addView(parts.iconButton("mdi:close", strings.close, "onSurface", 48, actions::close), gapped(48));
        page.addView(header, parts.wide());

        // The step between the ones around it.
        LinearLayout middle = parts.row();
        middle.setGravity(Gravity.TOP);
        middle.setBaselineAligned(false);
        LinearLayout column = parts.column();
        column.setPadding(parts.px(wide ? 56 : 24), 0, parts.px(wide ? 56 : 24), 0);
        previousPeek = peek(backward);
        column.addView(previousPeek, parts.wide(4));
        text = parts.text("", 30, 500, "onSurface", 0);
        text.setLineSpacing(0, LINE_SPACING);
        text.setOnTouchListener((view, event) -> touchedText(event));
        fixedNote = parts.text("", 14, 400, "onSurfaceVariant", 2);
        fixedNote.setVisibility(View.GONE);
        chipsTitle = parts.text(strings.forThisStep, 13, 600, "onSurfaceVariant", 1);
        chips = new FlowLayout(parts.context(), parts.px(8));
        story = parts.column();
        story.setGravity(Gravity.CENTER_VERTICAL);
        story.setPadding(0, parts.px(16), 0, parts.px(16));
        story.addView(text, parts.wide());
        story.addView(fixedNote, parts.wide(NOTE_GAP_DP));
        story.addView(chipsTitle, parts.wide(TITLE_GAP_DP));
        story.addView(chips, parts.wide(CHIPS_GAP_DP));
        scroll = new ScrollView(parts.context());
        scroll.setFillViewport(true);
        scroll.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            // The room changed, when the page first shows, a timer comes or the list opens beside it.
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) fitBeforeDrawing();
        });
        scroll.addView(story, new ScrollView.LayoutParams(Parts.MATCH, Parts.WRAP));
        column.addView(scroll, new LinearLayout.LayoutParams(Parts.MATCH, 0, 1));
        nextPeek = peek(forward);
        column.addView(nextPeek, parts.wide());
        swipes = new Swipes(parts.context(), parts, scroll, forward, backward);
        swipes.addView(column, new FrameLayout.LayoutParams(Parts.MATCH, Parts.MATCH));
        middle.addView(swipes, new LinearLayout.LayoutParams(0, Parts.MATCH, 1));

        // All the ingredients, beside the step on a wide screen and over it on a narrow one.
        ingredients = new IngredientList(parts, actions);
        LinearLayout inner = parts.column();
        inner.setPadding(parts.px(20), parts.px(12), parts.px(16), parts.px(16));
        inner.addView(parts.text(strings.ingredients, 18, 700, "onSurface", 1), parts.wide());
        inner.addView(ingredients.view(), parts.wide(6));
        ScrollView panelScroll = new ScrollView(parts.context());
        panelScroll.setBackground(parts.round(parts.theme().color("surfaceContainerHigh"), parts.theme().radiusRow()));
        panelScroll.addView(inner, new ScrollView.LayoutParams(Parts.MATCH, Parts.WRAP));
        panel = panelScroll;
        LinearLayout.LayoutParams panelAt = new LinearLayout.LayoutParams(wide ? parts.px(360) : Parts.MATCH, Parts.MATCH);
        panelAt.setMargins(wide ? 0 : parts.px(12), parts.px(4), parts.px(12), parts.px(8));
        middle.addView(panel, panelAt);
        page.addView(middle, new LinearLayout.LayoutParams(Parts.MATCH, 0, 1));

        // How far along, in a thin line above the bar.
        meterTrack = parts.row();
        meterTrack.setBackgroundColor(parts.theme().color("surfaceContainerHighest"));
        meter = new View(parts.context());
        meter.setBackgroundColor(parts.theme().color("primary"));
        meterTrack.addView(meter, new LinearLayout.LayoutParams(0, Parts.MATCH, 0));
        page.addView(meterTrack, new LinearLayout.LayoutParams(Parts.MATCH, parts.px(4)));

        counter = parts.text("", 18, 700, "onSurface", 1);
        ready = parts.text("", 14, 400, "onSurfaceVariant", 1);
        LinearLayout progress = parts.column();
        progress.addView(counter, parts.wide());
        progress.addView(ready, parts.wide());

        readButton = parts.iconButton("mdi:volume-high", strings.read, "onSurface", 56, actions::read);
        panelButton = parts.iconButton("mdi:format-list-checks", strings.ingredients, "onSurface", 56, this::togglePanel);
        next = parts.pill(strings.nextStep, "mdi:arrow-right", true, forward);
        parts.iconAfter(next);
        parts.theme().styleText(next, 19, 700, "onPrimary");
        next.setMinHeight(parts.px(72));
        next.setMinWidth(parts.px(wide ? 240 : 170));

        LinearLayout tools = parts.row();
        tools.addView(parts.iconButton("mdi:arrow-left", strings.back, "onSurface", 56, backward));
        tools.addView(parts.iconButton("mdi:timer-outline", strings.timers, "onSurface", 56, openTimers), gapped(56));
        tools.addView(readButton, gapped(56));
        tools.addView(panelButton, gapped(56));

        LinearLayout bar = parts.column();
        bar.setPadding(parts.px(16), parts.px(10), parts.px(16), parts.px(12));
        if (wide) {
            LinearLayout line = parts.row();
            line.addView(tools, parts.wrap());
            LinearLayout.LayoutParams progressAt = parts.fill(1);
            progressAt.setMarginStart(parts.px(24));
            progressAt.setMarginEnd(parts.px(24));
            line.addView(progress, progressAt);
            line.addView(next, parts.wrap());
            bar.addView(line, parts.wide());
        } else {
            bar.addView(progress, parts.wide());
            LinearLayout line = parts.row();
            line.addView(tools, parts.fill(1));
            line.addView(next, parts.wrap());
            bar.addView(line, parts.wide(8));
        }
        page.addView(bar, parts.wide());
        showPanel();
    }

    @Override public View view() {
        return root;
    }

    boolean panelOpen() {
        return panelOpen;
    }

    @Override public void show(Model model) {
        Strings strings = model.strings;
        name.setText(model.recipeName);
        if (!model.recipeImage.equals(shownImage)) {
            shownImage = model.recipeImage;
            images.newest();
            images.load(thumbnail, model.recipeImage, actions::imageHeaders, parts.px(112));
        }
        subtitle.setText(model.stepTitle.isEmpty() ? strings.stepWord + " " + model.pageNumber : strings.stepWord + " " + model.pageNumber + " · " + model.stepTitle);
        counter.setText(model.pageNumber + " / " + model.pageCount);
        ready.setText(model.readyAround);
        ready.setVisibility(model.readyAround.isEmpty() ? View.GONE : View.VISIBLE);
        next.setText(model.pageNumber >= model.pageCount ? strings.finish : strings.nextStep);
        readButton.setVisibility(model.canRead ? View.VISIBLE : View.GONE);
        LinearLayout.LayoutParams filled = (LinearLayout.LayoutParams) meter.getLayoutParams();
        filled.weight = model.pageNumber;
        meter.setLayoutParams(filled);
        meterTrack.setWeightSum(Math.max(1, model.pageCount));
        swipes.on(model.swipes);

        // A step change or new servings means new words. Anything else, such as ticking an ingredient, leaves the page be.
        String step = model.pageNumber + "|" + model.factor + "|" + model.textSize + "|" + model.textMost;
        if (!step.equals(shownStep)) {
            shownStep = step;
            showStep(model);
        }
        for (StepChip chip : stepChips) chip(chip, model.checked.contains(chip.ingredient));
        if (model.recipe != null) ingredients.show(model);
    }

    private void showStep(Model model) {
        textLeast = model.textSize;
        textMost = model.textMost;
        text.setText(spanned(model));
        fixedNote.setText(model.fixedNote);
        fixedNote.setVisibility(model.fixedNote.isEmpty() ? View.GONE : View.VISIBLE);
        // A long step can be read from its start, and a new step does not begin scrolled.
        scroll.scrollTo(0, 0);
        previousPeek.setText(firstLine(model.previousPeek));
        previousPeek.setVisibility(peeks && !model.previousPeek.isEmpty() ? View.VISIBLE : View.GONE);
        nextPeek.setText(firstLine(model.nextPeek));
        nextPeek.setVisibility(peeks && !model.nextPeek.isEmpty() ? View.VISIBLE : View.GONE);

        chips.removeAllViews();
        stepChips.clear();
        if (model.ownTimer != null) chips.addView(timerChip(model.ownTimer));
        for (Recipe.Ingredient ingredient : model.stepIngredients) {
            if (ingredient.header) continue;
            TextView view = parts.text(ingredient.line(model.factor, model.strings.decimalComma), Math.max(15, Math.min(24, model.textSize * 0.6f)), 500, "onSurface", 1);
            view.setPadding(parts.px(14), parts.px(8), parts.px(14), parts.px(8));
            view.setMinHeight(parts.px(40));
            view.setGravity(Gravity.CENTER_VERTICAL);
            view.setBackground(parts.ripple(parts.theme().color("surfaceContainerHigh"), 100));
            Parts.onTap(view, () -> actions.toggle(ingredient));
            chips.addView(view);
            stepChips.add(new StepChip(ingredient, view));
        }
        showStepChips();
        fitText();
    }

    /**
     * The largest size at which the words and what is under them fit without scrolling, so a short step can be read from
     * across the kitchen. A step too long for the smallest size scrolls.
     */
    private void fitText() {
        int width = scroll.getWidth();
        int room = scroll.getHeight() - story.getPaddingTop() - story.getPaddingBottom();
        if (width <= 0 || room <= 0 || textMost == 0) return;
        room -= under(fixedNote, width, NOTE_GAP_DP) + under(chipsTitle, width, TITLE_GAP_DP) + under(chips, width, CHIPS_GAP_DP);
        CharSequence words = text.getText();
        if (width == fitWidth && room == fitRoom && words == fitWords) return;
        fitWidth = width;
        fitRoom = room;
        fitWords = words;
        TextPaint paint = new TextPaint(text.getPaint());
        DisplayMetrics metrics = parts.context().getResources().getDisplayMetrics();
        int size = textLeast;
        for (int sp = textMost; sp > textLeast; sp -= TEXT_STEP_SP) {
            paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, metrics));
            StaticLayout layout = StaticLayout.Builder.obtain(words, 0, words.length(), paint, width).setLineSpacing(0, LINE_SPACING).build();
            if (layout.getHeight() <= room && (!(words instanceof Spanned) || ChipSpan.widest((Spanned) words, paint) <= width)) {
                size = sp;
                break;
            }
        }
        if (size != textShown) {
            textShown = size;
            text.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        }
    }

    /**
     * The room is only known during layout, after which the frame would be drawn at the old size and jump on the next one.
     * So the size is worked out just before drawing, and a frame with a new size is left out for the layout it needs.
     */
    private void fitBeforeDrawing() {
        // In case nothing comes to draw, when the page is not on the screen.
        scroll.post(() -> Parts.guard(this::fitText));
        if (fitComing) return;
        fitComing = true;
        final ViewTreeObserver observer = scroll.getViewTreeObserver();
        observer.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                fitComing = false;
                // The window's own observer took over from this one if the view was not attached yet.
                (observer.isAlive() ? observer : scroll.getViewTreeObserver()).removeOnPreDrawListener(this);
                int before = textShown;
                Parts.guard(StepScreen.this::fitText);
                return textShown == before;
            }
        });
    }

    private int under(View view, int width, int gapDp) {
        if (view.getVisibility() != View.VISIBLE) return 0;
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        return view.getMeasuredHeight() + parts.px(gapDp);
    }

    /** With the whole list open beside the step, its own ingredients would only take the step's room. */
    private void showStepChips() {
        for (StepChip chip : stepChips) chip.view.setVisibility(panelOpen ? View.GONE : View.VISIBLE);
        chipsTitle.setVisibility(stepChips.isEmpty() || panelOpen ? View.GONE : View.VISIBLE);
        boolean any = false;
        for (int i = 0; i < chips.getChildCount(); i++) any |= chips.getChildAt(i).getVisibility() == View.VISIBLE;
        chips.setVisibility(any ? View.VISIBLE : View.GONE);
    }

    /** The step's words with each duration drawn as a timer chip that starts when tapped. */
    private CharSequence spanned(Model model) {
        SpannableStringBuilder words = new SpannableStringBuilder();
        int background = parts.theme().color("primaryContainer");
        int foreground = parts.theme().color("onSurface");
        for (StepText.Run run : model.runs) {
            int start = words.length();
            words.append(run.text);
            if (!run.timer()) continue;
            int end = words.length();
            Runnable tap = () -> startOrShow(run);
            words.setSpan(new ChipSpan(background, foreground, parts.theme().iconPath("mdi:timer-outline"), parts.px(8), tap), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return words;
    }

    /** A tap on a timer chip starts it. Every other touch goes on to the swipes and the scrolling. */
    private boolean touchedText(MotionEvent event) {
        float margin = parts.px(CHIP_MARGIN_DP);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressedChip = ChipSpan.at(text, event.getX(), event.getY(), margin);
                return pressedChip != null;
            case MotionEvent.ACTION_UP:
                ChipSpan chip = pressedChip;
                pressedChip = null;
                if (chip != null && chip == ChipSpan.at(text, event.getX(), event.getY(), margin)) Parts.guard(chip.tap);
                return chip != null;
            case MotionEvent.ACTION_CANCEL:
                pressedChip = null;
                return false;
            default:
                return pressedChip != null;
        }
    }

    private void move(Runnable action) {
        long now = SystemClock.uptimeMillis();
        if (now - lastMove < MOVE_GAP_MS) return;
        lastMove = now;
        action.run();
    }

    private TextView timerChip(StepText.Run timer) {
        TextView chip = parts.text(timer.text, 16, 600, "onSurface", 1);
        chip.setBackground(parts.ripple(parts.theme().color("primaryContainer"), 100));
        chip.setPadding(parts.px(14), parts.px(8), parts.px(14), parts.px(8));
        chip.setMinHeight(parts.px(40));
        chip.setGravity(Gravity.CENTER_VERTICAL);
        Drawable icon = parts.icon("mdi:timer-outline", "onSurface");
        if (icon != null) {
            icon.setBounds(0, 0, parts.px(20), parts.px(20));
            chip.setCompoundDrawablesRelative(icon, null, null, null);
            chip.setCompoundDrawablePadding(parts.px(6));
        }
        Parts.onTap(chip, () -> startOrShow(timer));
        return chip;
    }

    /** A timer the step names starts once. Pressing it again shows how it is doing. */
    private void startOrShow(StepText.Run timer) {
        if (!actions.stepTimer(timer.text, timer.seconds)) openTimers.run();
    }

    private void chip(StepChip chip, boolean checked) {
        chip.view.setTextColor(parts.theme().color(checked ? "onSurfaceVariant" : "onSurface"));
        int flags = chip.view.getPaintFlags();
        chip.view.setPaintFlags(checked ? flags | Paint.STRIKE_THRU_TEXT_FLAG : flags & ~Paint.STRIKE_THRU_TEXT_FLAG);
    }

    private TextView peek(Runnable action) {
        TextView peek = parts.text("", 16, 400, "onSurfaceVariant", 2);
        // Fades into the page, so it reads as what is around the step and not as the step.
        peek.setTextColor(parts.faded("onSurfaceVariant", 150));
        peek.setPadding(0, parts.px(8), 0, parts.px(8));
        peek.setMinHeight(parts.px(44));
        Parts.onTap(peek, action);
        return peek;
    }

    private static String firstLine(String text) {
        int line = text.indexOf('\n');
        return line < 0 ? text : text.substring(0, line);
    }

    private void togglePanel() {
        panelOpen = !panelOpen;
        showPanel();
    }

    private void showPanel() {
        panel.setVisibility(panelOpen ? View.VISIBLE : View.GONE);
        showStepChips();
        // Without the step's own ingredients under them, the words have more room.
        fitText();
        panelButton.setBackground(parts.ripple(parts.theme().color(panelOpen ? "primaryContainer" : "surfaceContainerHigh"), 100));
    }

    private LinearLayout.LayoutParams gapped(int size) {
        LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(parts.px(size), parts.px(size));
        at.setMarginStart(parts.px(8));
        return at;
    }
}
