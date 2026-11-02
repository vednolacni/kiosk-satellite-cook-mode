// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** The end of a recipe: a kind word, and the choice to record it in the recipe app. */
final class FinishScreen implements Screen {
    private final Parts parts;
    private final Ui.Actions actions;
    private final Images images;
    private final View root;
    private final ImageView picture;
    private final TextView name;
    private final TextView log;
    private final TextView logged;
    private String shownImage;

    FinishScreen(Parts parts, Ui.Actions actions, Images images, Strings strings) {
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
        LinearLayout.LayoutParams pictureAt = new LinearLayout.LayoutParams(parts.px(180), parts.px(180));
        center.addView(picture, pictureAt);
        TextView enjoy = parts.text(strings.enjoy, 34, 700, "onSurface", 2);
        enjoy.setGravity(Gravity.CENTER);
        center.addView(enjoy, parts.wide(20));
        name = parts.text("", 18, 400, "onSurfaceVariant", 2);
        name.setGravity(Gravity.CENTER);
        center.addView(name, parts.wide(6));
        log = parts.pill(strings.logCooked, "mdi:check", true, actions::logCooked);
        log.setMinHeight(parts.px(56));
        center.addView(log, centered(24));
        logged = parts.text(strings.logged, 16, 600, "success", 2);
        logged.setGravity(Gravity.CENTER);
        center.addView(logged, parts.wide(24));
        center.addView(parts.pill(strings.backToPlan, false, actions::backToPlan), centered(16));
        center.addView(parts.pill(strings.endCooking, false, actions::endCooking), centered(4));

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
            images.load(picture, model.recipeImage, actions.imageHeaders(model.recipeImage), parts.px(240));
        }
        log.setVisibility(model.canLogCooked ? View.VISIBLE : View.GONE);
        logged.setVisibility(model.logged ? View.VISIBLE : View.GONE);
    }

    private LinearLayout.LayoutParams centered(int top) {
        LinearLayout.LayoutParams at = parts.wrap();
        at.topMargin = parts.px(top);
        at.gravity = Gravity.CENTER_HORIZONTAL;
        return at;
    }
}
