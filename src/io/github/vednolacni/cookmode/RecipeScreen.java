// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** A recipe before cooking: its picture, times, servings and ingredients, and the big button that starts. */
final class RecipeScreen implements Screen {
    private static final int PICTURE_WIDTH_DP = 640;
    /** The picture takes this share of the screen's height, so what matters stays in view on a small tablet. */
    private static final int PICTURE_SHARE_PERCENT = 30;
    private static final int PICTURE_LEAST_DP = 110;
    private static final int PICTURE_MOST_DP = 240;

    private final Parts parts;
    private final Ui.Actions actions;
    private final Images images;
    private final View root;
    private final ImageView picture;
    private final TextView name;
    private final TextView description;
    private final TextView times;
    private final LinearLayout servings;
    private final TextView servingsText;
    private final TextView status;
    private final TextView ingredientsTitle;
    private final IngredientList ingredients;
    private final TextView start;
    private final View read;
    private final TextView openInApp;
    private String shownImage;

    RecipeScreen(Parts parts, Ui.Actions actions, Images images, Strings strings, boolean wide, int heightPx) {
        this.parts = parts;
        this.actions = actions;
        this.images = images;

        LinearLayout page = parts.column();
        page.setPadding(parts.px(20), parts.px(12), parts.px(20), parts.px(16));
        root = page;

        LinearLayout header = parts.row();
        header.addView(parts.iconButton("mdi:arrow-left", strings.back, "onSurface", 48, actions::previous));
        View gap = new View(parts.context());
        header.addView(gap, new LinearLayout.LayoutParams(0, 1, 1));
        openInApp = parts.pill(strings.openInApp, "mdi:open-in-new", false, actions::openInApp);
        header.addView(openInApp, parts.wrap());
        read = parts.iconButton("mdi:volume-high", strings.read, "onSurface", 48, actions::read);
        header.addView(read, gapped(48));
        header.addView(parts.iconButton("mdi:close", strings.close, "onSurface", 48, actions::close), gapped(48));
        page.addView(header, parts.wide());

        picture = parts.picture(parts.theme().radiusRow());
        name = parts.text("", 26, 700, "onSurface", 3);
        description = parts.text("", 15, 400, "onSurfaceVariant", 3);
        times = parts.text("", 15, 500, "onSurfaceVariant", 2);

        servings = parts.row();
        View minus = parts.iconButton("mdi:minus", "-", "onSurface", 52, () -> {});
        parts.repeating(minus, () -> actions.changeServings(-1));
        servingsText = parts.text("", 18, 600, "onSurface", 1);
        servingsText.setGravity(Gravity.CENTER);
        View plus = parts.iconButton("mdi:plus", "+", "onSurface", 52, () -> {});
        parts.repeating(plus, () -> actions.changeServings(1));
        servings.addView(minus);
        servings.addView(servingsText, parts.fill(1));
        servings.addView(plus);

        status = parts.text("", 16, 400, "onSurfaceVariant", 0);
        ingredientsTitle = parts.text(strings.ingredients, 18, 700, "onSurface", 1);
        ingredients = new IngredientList(parts, actions);
        start = parts.pill(strings.startCooking, true, actions::startCooking);
        parts.theme().styleText(start, 19, 700, "onPrimary");
        start.setMinHeight(parts.px(64));

        LinearLayout summary = parts.column();
        LinearLayout list = parts.column();
        int pictureHeight = Math.max(parts.px(PICTURE_LEAST_DP), Math.min(parts.px(PICTURE_MOST_DP), heightPx * PICTURE_SHARE_PERCENT / 100));
        summary.addView(picture, new LinearLayout.LayoutParams(Parts.MATCH, pictureHeight));
        summary.addView(name, parts.wide(14));
        summary.addView(description, parts.wide(6));
        summary.addView(times, parts.wide(8));
        list.addView(ingredientsTitle, parts.wide(wide ? 4 : 16));
        list.addView(status, parts.wide(8));
        list.addView(ingredients.view(), parts.wide(6));

        if (wide) {
            LinearLayout both = parts.row();
            both.setGravity(Gravity.TOP);
            both.setBaselineAligned(false);
            LinearLayout left = parts.column();
            left.addView(scroller(summary), new LinearLayout.LayoutParams(Parts.MATCH, 0, 1));
            left.addView(servings, parts.wide(8));
            left.addView(start, parts.wide(8));
            LinearLayout.LayoutParams leftAt = new LinearLayout.LayoutParams(0, Parts.MATCH, 5);
            leftAt.setMarginEnd(parts.px(28));
            both.addView(left, leftAt);
            both.addView(scroller(list), new LinearLayout.LayoutParams(0, Parts.MATCH, 6));
            page.addView(both, new LinearLayout.LayoutParams(Parts.MATCH, 0, 1));
        } else {
            LinearLayout both = parts.column();
            both.addView(summary, parts.wide());
            both.addView(list, parts.wide());
            page.addView(scroller(both), new LinearLayout.LayoutParams(Parts.MATCH, 0, 1));
            page.addView(servings, parts.wide(8));
            page.addView(start, parts.wide(8));
        }
    }

    @Override public View view() {
        return root;
    }

    @Override public void show(Model model) {
        Strings strings = model.strings;
        Recipe recipe = model.recipe;
        name.setText(model.recipeName);
        if (!model.recipeImage.equals(shownImage)) {
            shownImage = model.recipeImage;
            images.load(picture, model.recipeImage, actions.imageHeaders(model.recipeImage), parts.px(PICTURE_WIDTH_DP));
        }
        boolean ready = recipe != null;
        read.setVisibility(ready && model.canRead ? View.VISIBLE : View.GONE);
        openInApp.setVisibility(ready && !recipe.webUrl.isEmpty() ? View.VISIBLE : View.GONE);
        boolean steps = ready && !recipe.steps.isEmpty();
        start.setVisibility(steps ? View.VISIBLE : View.GONE);
        start.setText(model.resumeAt > 0 ? String.format(strings.continueAt, model.resumeAt) : strings.startCooking);
        ingredientsTitle.setVisibility(ready && !model.ingredients.isEmpty() ? View.VISIBLE : View.GONE);
        if (!ready) {
            status.setText(model.recipeLoading ? strings.loading : "");
            status.setVisibility(model.recipeLoading ? View.VISIBLE : View.GONE);
            description.setVisibility(View.GONE);
            times.setVisibility(View.GONE);
            servings.setVisibility(View.GONE);
            ingredients.view().setVisibility(View.GONE);
            return;
        }
        status.setText(strings.noSteps);
        status.setVisibility(steps ? View.GONE : View.VISIBLE);
        description.setText(PlainText.fromMarkdown(recipe.description));
        description.setVisibility(recipe.description.isEmpty() ? View.GONE : View.VISIBLE);
        String time = times(recipe, strings);
        times.setText(time);
        times.setVisibility(time.isEmpty() ? View.GONE : View.VISIBLE);
        servings.setVisibility(recipe.servings > 0 ? View.VISIBLE : View.GONE);
        servingsText.setText(strings.servings(model.servings, recipe.servingsText));
        ingredients.view().setVisibility(View.VISIBLE);
        ingredients.show(model);
    }

    private static String times(Recipe recipe, Strings strings) {
        StringBuilder text = new StringBuilder();
        if (recipe.workingMinutes > 0) text.append(strings.prep).append(' ').append(strings.duration(recipe.workingMinutes));
        if (recipe.waitingMinutes > 0) {
            if (text.length() > 0) text.append(" · ");
            text.append(strings.waiting).append(' ').append(strings.duration(recipe.waitingMinutes));
        }
        return text.toString();
    }

    private LinearLayout.LayoutParams gapped(int size) {
        LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(parts.px(size), parts.px(size));
        at.setMarginStart(parts.px(8));
        return at;
    }

    private ScrollView scroller(View content) {
        ScrollView scroll = new ScrollView(parts.context());
        scroll.addView(content, new ScrollView.LayoutParams(Parts.MATCH, Parts.WRAP));
        return scroll;
    }
}
