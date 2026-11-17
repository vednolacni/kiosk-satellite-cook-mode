// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** The end of a recipe: a kind word, and the choice to record it in the recipe app. */
final class FinishScreen implements Screen {
    /** The picture takes this share of the screen's height, so the buttons fit under it on a small tablet. */
    private static final int PICTURE_SHARE_PERCENT = 22;
    private static final int PICTURE_MOST_DP = 180;
    /** Below this height the picture keeps to a small size, so the way out stays on the screen with the kind word. */
    private static final int SHORT_SCREEN_DP = 560;
    private static final int SHORT_PICTURE_DP = 72;
    /** The two ways out sit this far apart beside each other, or this far apart one above the other. */
    private static final int BESIDE_GAP_DP = 12;
    private static final int BELOW_GAP_DP = 8;

    private final Parts parts;
    private final Ui.Actions actions;
    private final Images images;
    private final View root;
    private final ImageView picture;
    private final TextView name;
    private final TextView log;
    private final TextView logged;
    private String shownImage;

    FinishScreen(Parts parts, Ui.Actions actions, Images images, Strings strings, int heightPx) {
        this.parts = parts;
        this.actions = actions;
        this.images = images;

        LinearLayout page = parts.column();
        page.setPadding(parts.px(16), parts.px(10), parts.px(16), parts.px(16));
        root = page;

        LinearLayout header = parts.row();
        header.addView(parts.iconButton("mdi:arrow-left", strings.back, "onSurface", 48, actions::previous));
        header.addView(new View(parts.context()), new LinearLayout.LayoutParams(0, 1, 1));
        header.addView(parts.iconButton("mdi:close", strings.close, "onSurface", 48, actions::close));
        page.addView(header, parts.wide());

        LinearLayout center = parts.column();
        center.setGravity(Gravity.CENTER);
        picture = parts.picture(parts.theme().radiusCard());
        int side = Math.min(parts.px(PICTURE_MOST_DP), heightPx * PICTURE_SHARE_PERCENT / 100);
        if (heightPx < parts.px(SHORT_SCREEN_DP)) side = Math.min(side, parts.px(SHORT_PICTURE_DP));
        center.addView(picture, new LinearLayout.LayoutParams(side, side));
        TextView enjoy = parts.text(strings.enjoy, 34, 700, "onSurface", 2);
        enjoy.setGravity(Gravity.CENTER);
        center.addView(enjoy, parts.wide(14));
        name = parts.text("", 18, 400, "onSurfaceVariant", 2);
        name.setGravity(Gravity.CENTER);
        center.addView(name, parts.wide(4));
        log = parts.pill(strings.logCooked, "mdi:check", true, actions::logCooked);
        log.setMinHeight(parts.px(56));
        center.addView(log, centered(16));
        // Green as text is 4.1:1 on KS's light page, so the words are plain and the green is in a check mark beside them.
        logged = parts.text(strings.logged, 16, 600, "onSurface", 2);
        logged.setGravity(Gravity.CENTER);
        Drawable done = parts.icon("mdi:check-circle", "success");
        if (done != null) done.setBounds(0, 0, parts.px(22), parts.px(22));
        logged.setCompoundDrawablesRelative(done, null, null, null);
        logged.setCompoundDrawablePadding(parts.px(8));
        // As wide as its words, so the mark sits beside them and not at the page's edge.
        center.addView(logged, centered(16));
        // Side by side, so the two are not a mistap apart and the second one is not below the fold of a small tablet.
        GradientDrawable gap = new GradientDrawable();
        gap.setColor(Color.TRANSPARENT);
        gap.setSize(parts.px(BESIDE_GAP_DP), parts.px(BELOW_GAP_DP));
        Choices ways = new Choices(parts.context());
        ways.setDividerDrawable(gap);
        ways.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
        ways.addView(parts.pill(strings.backToPlan, false, actions::backToPlan), parts.wrap());
        ways.addView(parts.pill(strings.endCooking, false, actions::endCooking), parts.wrap());
        center.addView(ways, centered(8));

        ScrollView scroll = new ScrollView(parts.context());
        scroll.setFillViewport(true);
        scroll.addView(center, new ScrollView.LayoutParams(Parts.MATCH, Parts.WRAP));
        page.addView(scroll, new LinearLayout.LayoutParams(Parts.MATCH, 0, 1));
    }

    @Override public View view() {
        return root;
    }

    @Override public void show(Model model) {
        name.setText(model.recipeName);
        if (!model.recipeImage.equals(shownImage)) {
            shownImage = model.recipeImage;
            images.newest();
            images.load(picture, model.recipeImage, actions::imageHeaders, parts.px(240));
        }
        log.setVisibility(model.canLogCooked ? View.VISIBLE : View.GONE);
        logged.setVisibility(model.logged ? View.VISIBLE : View.GONE);
    }

    /**
     * Its buttons side by side when they fit, and one above the other when a narrow screen or a long language leaves too
     * little width. The gap between them is the divider, whose width counts beside and whose height counts below.
     */
    private static final class Choices extends LinearLayout {
        Choices(Context context) {
            super(context);
            setGravity(Gravity.CENTER_HORIZONTAL);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int any = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
            int wanted = 0;
            int shown = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (child.getVisibility() == GONE) continue;
                child.measure(any, any);
                wanted += child.getMeasuredWidth();
                shown++;
            }
            if (shown > 1 && getDividerDrawable() != null) wanted += (shown - 1) * getDividerDrawable().getIntrinsicWidth();
            boolean fits = MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED || wanted <= MeasureSpec.getSize(widthSpec);
            setOrientation(fits ? HORIZONTAL : VERTICAL);
            super.onMeasure(widthSpec, heightSpec);
        }
    }

    private LinearLayout.LayoutParams centered(int top) {
        LinearLayout.LayoutParams at = parts.wrap();
        at.topMargin = parts.px(top);
        at.gravity = Gravity.CENTER_HORIZONTAL;
        return at;
    }
}
