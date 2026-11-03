// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The first page: the meal, the plan and every recipe. Today's meals come first, one tap from cooking, and the recipes
 * are there for anyone who does not plan at all. The two lists update apart from the search field, which keeps what is
 * typed in it through every answer.
 */
final class HomeScreen implements Screen {
    private static final long SEARCH_AFTER_MS = 500L;
    private static final int THUMBNAIL_WIDTH_DP = 112;
    private static final int CELL_WIDTH_DP = 240;
    /** How narrow a grid cell may get before the grid drops a column. */
    private static final int CELL_MIN_DP = 170;
    /** Below this height the keys shrink, so a row of results stays in view above them. */
    private static final int SHORT_SCREEN_DP = 560;
    private static final int QUERY_MOST = 60;

    private final Parts parts;
    private final Ui.Actions actions;
    private final Images images;
    private final int columns;
    private final boolean wide;
    private final View root;
    private final TextView date;
    private final TextView message;
    private final View body;
    private final TextView search;
    private final ImageView clear;
    private final Keypad keypad;
    private final View orders;
    private final TextView orderAll;
    private final TextView orderRecent;
    private final LinearLayout plan;
    private final View recipesTitle;
    /** The rows of recipes. What is under them changes apart, so a word there does not rebuild them. */
    private final LinearLayout rows;
    private final LinearLayout footer;
    private final ScrollView gridScroll;
    private final Runnable searchLater = () -> Parts.guard(this::searchNow);

    private String typed = "";
    private String query = "";
    /** What the lists show, to leave them alone when an answer changed nothing. */
    private final List<RecipeSource.Planned> shownMeals = new ArrayList<>();
    private List<RecipeSource.Summary> shownRecipes;
    private String shownPlanState;
    private String shownFooter;
    private String answered;

    HomeScreen(Parts parts, Ui.Actions actions, Images images, Strings strings, boolean wide, int widthPx, int heightPx) {
        this.parts = parts;
        this.actions = actions;
        this.images = images;
        this.wide = wide;
        // The recipes get the wider column next to the plan, or all of a narrow screen.
        int gridWidth = wide ? widthPx * 7 / 12 : widthPx;
        columns = Math.max(2, Math.min(5, (gridWidth - parts.px(32)) / parts.px(CELL_MIN_DP)));

        LinearLayout page = parts.column();
        page.setPadding(parts.px(20), parts.px(12), parts.px(20), 0);
        root = page;

        LinearLayout header = parts.row();
        date = parts.text("", 22, 700, "onSurface", 1);
        header.addView(date, parts.fill(1));
        header.addView(parts.iconButton("mdi:refresh", strings.retry, "onSurfaceVariant", 48, actions::refresh));
        View close = parts.iconButton("mdi:close", strings.close, "onSurface", 48, actions::close);
        LinearLayout.LayoutParams closeAt = new LinearLayout.LayoutParams(parts.px(48), parts.px(48));
        closeAt.setMarginStart(parts.px(8));
        header.addView(close, closeAt);
        page.addView(header, parts.wide());

        message = parts.text("", 20, 400, "onSurfaceVariant", 0);
        message.setGravity(Gravity.CENTER);
        message.setPadding(parts.px(32), parts.px(48), parts.px(32), parts.px(48));
        message.setVisibility(View.GONE);
        page.addView(message, parts.wide());

        search = searchField(strings);
        clear = parts.iconButton("mdi:close-circle-outline", strings.cancel, "onSurfaceVariant", 44, () -> {
            type("");
            searchNow();
        });
        clear.setBackground(null);
        clear.setVisibility(View.GONE);
        LinearLayout searchRow = parts.row();
        searchRow.addView(search, parts.fill(1));
        searchRow.addView(clear);

        LinearLayout orderRow = parts.row();
        orderAll = orderChip(strings.all, RecipeSource.Order.NAME);
        orderRecent = orderChip(strings.recent, RecipeSource.Order.RECENT);
        orderRow.addView(orderAll, parts.wrap());
        orderRow.addView(orderRecent, parts.after(8));
        orders = orderRow;

        plan = parts.column();
        rows = parts.column();
        footer = parts.column();
        LinearLayout grid = parts.column();
        grid.addView(rows, parts.wide());
        grid.addView(footer, parts.wide());
        grid.addView(new View(parts.context()), new LinearLayout.LayoutParams(1, parts.px(20)));
        recipesTitle = parts.text(strings.recipes, 16, 700, "onSurface", 1);

        if (wide) {
            LinearLayout panes = parts.row();
            panes.setGravity(Gravity.TOP);
            panes.setBaselineAligned(false);
            LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, Parts.MATCH, 5);
            left.setMarginEnd(parts.px(24));
            panes.addView(scroller(plan), left);
            LinearLayout right = parts.column();
            right.addView(searchRow, parts.wide(4));
            right.addView(orders, parts.wide(10));
            gridScroll = scroller(grid);
            right.addView(gridScroll, new LinearLayout.LayoutParams(Parts.MATCH, 0, 1));
            panes.addView(right, new LinearLayout.LayoutParams(0, Parts.MATCH, 7));
            body = panes;
        } else {
            LinearLayout column = parts.column();
            column.addView(searchRow, parts.wide(4));
            LinearLayout both = parts.column();
            both.addView(plan, parts.wide());
            both.addView(recipesTitle, parts.wide(20));
            both.addView(orders, parts.wide(8));
            both.addView(grid, parts.wide());
            gridScroll = scroller(both);
            column.addView(gridScroll, new LinearLayout.LayoutParams(Parts.MATCH, 0, 1));
            body = column;
        }
        page.addView(body, new LinearLayout.LayoutParams(Parts.MATCH, 0, 1));

        boolean shortScreen = heightPx < parts.px(SHORT_SCREEN_DP);
        keypad = new Keypad(parts, strings, shortScreen ? 42 : 52, new Keypad.Keys() {
            @Override public void typed(String letter) {
                if (typed.length() < QUERY_MOST) searchSoon(typed + letter.toLowerCase(Locale.ROOT));
            }

            @Override public void erase() {
                if (!typed.isEmpty()) searchSoon(typed.substring(0, typed.length() - 1));
            }

            @Override public void close() {
                searchNow();
                showKeypad(false);
            }

            @Override public void search() {
                searchNow();
                showKeypad(false);
            }
        });
        page.addView(keypad.view(), parts.wide());
    }

    @Override public View view() {
        return root;
    }

    @Override public void show(Model model) {
        date.setText(model.date);
        message.setVisibility(model.configured ? View.GONE : View.VISIBLE);
        message.setText(model.strings.notConfigured);
        body.setVisibility(model.configured ? View.VISIBLE : View.GONE);
        if (!model.configured) {
            keypad.show(false);
            return;
        }
        query = model.query;
        // What the cook is typing wins over the answer to what they typed before.
        if (!keypad.open() && !model.query.equals(typed.trim())) type(model.query);
        order(orderAll, model.order == RecipeSource.Order.NAME);
        order(orderRecent, model.order == RecipeSource.Order.RECENT);
        showPlan(model);
        showGrid(model);
    }

    /** Stop what is waiting. The page is going away, and may come back. */
    void leave() {
        search.removeCallbacks(searchLater);
        keypad.show(false);
        showSearching();
    }

    // The search field.

    private TextView searchField(Strings strings) {
        TextView field = parts.text("", 16, 400, "onSurface", 1);
        field.setHint(strings.searchHint);
        field.setHintTextColor(parts.theme().color("onSurfaceVariant"));
        field.setBackground(parts.ripple(parts.theme().color("surfaceContainerHigh"), parts.theme().radiusControl()));
        field.setPadding(parts.px(14), parts.px(11), parts.px(14), parts.px(11));
        field.setMinHeight(parts.px(48));
        field.setGravity(Gravity.CENTER_VERTICAL);
        Drawable glass = parts.icon("mdi:magnify", "onSurfaceVariant");
        if (glass != null) {
            glass.setBounds(0, 0, parts.px(22), parts.px(22));
            field.setCompoundDrawablesRelative(glass, null, null, null);
            field.setCompoundDrawablePadding(parts.px(10));
        }
        Parts.onTap(field, () -> showKeypad(true));
        return field;
    }

    private void type(String text) {
        typed = text;
        search.setText(text);
        clear.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
        showSearching();
    }

    private void searchSoon(String text) {
        type(text);
        search.removeCallbacks(searchLater);
        search.postDelayed(searchLater, SEARCH_AFTER_MS);
    }

    private void searchNow() {
        search.removeCallbacks(searchLater);
        String text = typed.trim();
        if (!text.equals(query)) actions.search(text);
    }

    private void showKeypad(boolean open) {
        keypad.show(open);
        showSearching();
    }

    /** While searching, what is found goes first. On a wide screen the plan can stay beside it. */
    private void showSearching() {
        boolean searching = keypad.open() || !typed.isEmpty();
        orders.setVisibility(keypad.open() ? View.GONE : View.VISIBLE);
        if (wide) return;
        plan.setVisibility(searching ? View.GONE : View.VISIBLE);
        recipesTitle.setVisibility(searching ? View.GONE : View.VISIBLE);
    }

    private TextView orderChip(String label, RecipeSource.Order order) {
        TextView chip = parts.text(label, 14, 600, "onSurfaceVariant", 1);
        chip.setGravity(Gravity.CENTER);
        chip.setMinHeight(parts.px(40));
        chip.setPadding(parts.px(16), parts.px(8), parts.px(16), parts.px(8));
        Parts.onTap(chip, () -> actions.showOrder(order));
        return chip;
    }

    private void order(TextView chip, boolean selected) {
        chip.setBackground(parts.ripple(parts.theme().color(selected ? "primaryContainer" : "surfaceContainerHigh"), 100));
        chip.setTextColor(parts.theme().color(selected ? "onSurface" : "onSurfaceVariant"));
    }

    // The meal plan.

    private void showPlan(Model model) {
        List<RecipeSource.Planned> meals = new ArrayList<>();
        for (Model.Day day : model.days) meals.addAll(day.meals);
        String state = model.planLoading + "|" + model.resumeAt + "|" + model.recipeName + "|" + model.recipeImage;
        if (meals.equals(shownMeals) && state.equals(shownPlanState)) return;
        shownMeals.clear();
        shownMeals.addAll(meals);
        shownPlanState = state;
        plan.removeAllViews();
        if (model.resumeAt > 0) plan.addView(resumeCard(model), parts.wide(4));
        boolean first = true;
        for (Model.Day day : model.days) {
            plan.addView(parts.text(day.label, first ? 18 : 15, 700, first ? "primary" : "onSurfaceVariant", 1), parts.wide(first ? 12 : 22));
            for (RecipeSource.Planned meal : day.meals) plan.addView(mealRow(meal, first), parts.wide(first ? 10 : 6));
            if (first && day.meals.isEmpty()) {
                String empty = model.planLoading ? model.strings.loading : model.strings.nothingToday;
                plan.addView(parts.text(empty, 16, 400, "onSurfaceVariant", 0), parts.wide(8));
            }
            first = false;
        }
        View end = new View(parts.context());
        plan.addView(end, new LinearLayout.LayoutParams(1, parts.px(20)));
    }

    /** The recipe being cooked: one tap back to the step it was left at. */
    private View resumeCard(Model model) {
        Strings strings = model.strings;
        LinearLayout card = parts.row();
        card.setBackground(parts.ripple(parts.theme().color("primaryContainer"), parts.theme().radiusRow()));
        card.setPadding(parts.px(12), parts.px(12), parts.px(12), parts.px(12));
        ImageView picture = parts.picture(parts.theme().radiusControl());
        images.load(picture, model.recipeImage, actions.imageHeaders(model.recipeImage), parts.px(THUMBNAIL_WIDTH_DP));
        card.addView(picture, new LinearLayout.LayoutParams(parts.px(72), parts.px(72)));
        LinearLayout words = parts.column();
        words.addView(parts.text(strings.cooking, 13, 600, "onSurfaceVariant", 1), parts.wide());
        words.addView(parts.text(model.recipeName, 18, 700, "onSurface", 2), parts.wide());
        words.addView(parts.text(String.format(strings.continueAt, model.resumeAt), 14, 500, "onSurface", 1), parts.wide(2));
        LinearLayout.LayoutParams wordsAt = parts.fill(1);
        wordsAt.setMarginStart(parts.px(14));
        card.addView(words, wordsAt);
        card.addView(parts.iconButton("mdi:stop", strings.endCooking, "onSurface", 48, actions::askEnd));
        Parts.onTap(card, actions::resumeCooking);
        return card;
    }

    private View mealRow(RecipeSource.Planned meal, boolean today) {
        LinearLayout row = parts.row();
        row.setBackground(parts.ripple(parts.theme().color("surfaceContainerHigh"), parts.theme().radiusRow()));
        row.setPadding(parts.px(10), parts.px(10), parts.px(14), parts.px(10));
        row.setMinimumHeight(parts.px(56));
        boolean recipe = meal.recipeId != null;
        if (recipe) {
            ImageView picture = parts.picture(parts.theme().radiusControl());
            images.load(picture, meal.image, actions.imageHeaders(meal.image), parts.px(THUMBNAIL_WIDTH_DP));
            int size = parts.px(today ? 84 : 56);
            LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(size, size);
            at.setMarginEnd(parts.px(14));
            row.addView(picture, at);
        }
        LinearLayout words = parts.column();
        if (!meal.slot.isEmpty()) {
            TextView slot = parts.text(meal.slot, 13, 600, "onSurfaceVariant", 1);
            if (meal.color != 0) slot.setTextColor(meal.color);
            words.addView(slot, parts.wide());
        }
        words.addView(parts.text(meal.title, today ? 19 : 16, 600, "onSurface", 2), parts.wide());
        String details = detail(meal);
        if (!details.isEmpty()) words.addView(parts.text(details, 13, 400, "onSurfaceVariant", 1), parts.wide());
        row.addView(words, parts.fill(1));
        Parts.onTap(row, () -> actions.openPlanned(meal));
        return row;
    }

    private static String detail(RecipeSource.Planned meal) {
        StringBuilder text = new StringBuilder();
        if (meal.minutes > 0) text.append(meal.minutes).append(" min");
        if (!meal.note.isEmpty() && !meal.note.equals(meal.title)) {
            if (text.length() > 0) text.append(" · ");
            text.append(meal.note);
        }
        return text.toString();
    }

    // Every recipe.

    private void showGrid(Model model) {
        if (!model.recipes.equals(shownRecipes)) showRows(model.recipes);
        showFooter(model);
        // The answer to a new question starts at its top. Loading more of the same stays where it is.
        String question = model.query + "|" + model.order;
        if (!model.recipesLoading && !question.equals(answered)) {
            // On a narrow screen the plan is above the list, and the cook is not looking for that.
            if (answered != null) gridScroll.scrollTo(0, Math.min(gridScroll.getScrollY(), wide ? 0 : orders.getTop()));
            answered = question;
        }
    }

    /** More of the same list only adds rows, so what is on the screen is not built again under a finger. */
    private void showRows(List<RecipeSource.Summary> recipes) {
        boolean more = shownRecipes != null && recipes.size() >= shownRecipes.size() && recipes.subList(0, shownRecipes.size()).equals(shownRecipes);
        int keep = more ? shownRecipes.size() / columns : 0;
        while (rows.getChildCount() > keep) rows.removeViewAt(rows.getChildCount() - 1);
        shownRecipes = recipes;
        for (int at = keep * columns; at < recipes.size(); at += columns) {
            LinearLayout row = parts.row();
            row.setGravity(Gravity.TOP);
            row.setBaselineAligned(false);
            for (int column = 0; column < columns; column++) {
                LinearLayout.LayoutParams cellAt = parts.fill(1);
                if (column > 0) cellAt.setMarginStart(parts.px(12));
                int index = at + column;
                if (index < recipes.size()) row.addView(cell(recipes.get(index)), cellAt);
                else row.addView(new View(parts.context()), cellAt);
            }
            rows.addView(row, parts.wide(12));
        }
    }

    private void showFooter(Model model) {
        // With a problem in the banner, an empty list says nothing about the recipes.
        String state = model.recipesLoading ? "loading" : model.recipes.isEmpty() && model.banner == null ? "none" : model.recipesMore ? "more" : "";
        if (state.equals(shownFooter)) return;
        shownFooter = state;
        footer.removeAllViews();
        if (state.equals("loading")) {
            TextView loading = parts.text(model.strings.loading, 15, 400, "onSurfaceVariant", 1);
            loading.setGravity(Gravity.CENTER);
            loading.setPadding(0, parts.px(16), 0, parts.px(16));
            footer.addView(loading, parts.wide());
        } else if (state.equals("none")) {
            TextView none = parts.text(model.strings.noRecipes, 16, 400, "onSurfaceVariant", 0);
            none.setGravity(Gravity.CENTER);
            none.setPadding(0, parts.px(32), 0, parts.px(32));
            footer.addView(none, parts.wide());
        } else if (state.equals("more")) {
            LinearLayout.LayoutParams at = parts.wide(16);
            at.gravity = Gravity.CENTER_HORIZONTAL;
            footer.addView(parts.pill(model.strings.loadMore, false, actions::loadMore), at);
        }
    }

    private View cell(RecipeSource.Summary recipe) {
        LinearLayout cell = parts.column();
        cell.setBackground(parts.ripple(parts.theme().color("surfaceContainerHigh"), parts.theme().radiusRow()));
        cell.setPadding(parts.px(8), parts.px(8), parts.px(8), parts.px(10));
        ImageView picture = parts.picture(parts.theme().radiusControl());
        images.load(picture, recipe.image, actions.imageHeaders(recipe.image), parts.px(CELL_WIDTH_DP));
        cell.addView(picture, new LinearLayout.LayoutParams(Parts.MATCH, parts.px(112)));
        cell.addView(parts.text(recipe.name, 15, 600, "onSurface", 2), parts.wide(8));
        if (recipe.minutes > 0) cell.addView(parts.text(recipe.minutes + " min", 13, 400, "onSurfaceVariant", 1), parts.wide(2));
        Parts.onTap(cell, () -> actions.openRecipe(recipe));
        return cell;
    }

    private ScrollView scroller(View content) {
        ScrollView scroll = new ScrollView(parts.context());
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.addView(content, new ScrollView.LayoutParams(Parts.MATCH, Parts.WRAP));
        return scroll;
    }
}
