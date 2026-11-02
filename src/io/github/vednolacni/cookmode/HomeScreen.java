// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/**
 * The first page: the meal, the plan and every recipe. Today's meals come first, one tap from cooking, and the recipes
 * are there for anyone who does not plan at all. The two lists update apart from the search field, which keeps its
 * text and its keyboard through every answer.
 */
final class HomeScreen implements Screen {
    private static final long SEARCH_AFTER_MS = 500L;
    private static final int THUMBNAIL_WIDTH_DP = 112;
    private static final int CELL_WIDTH_DP = 240;
    /** How narrow a grid cell may get before the grid drops a column. */
    private static final int CELL_MIN_DP = 170;

    private final Parts parts;
    private final Ui.Actions actions;
    private final Images images;
    private final int columns;
    private final View root;
    private final TextView date;
    private final TextView message;
    private final View body;
    private final EditText search;
    private final ImageView clear;
    private final TextView orderAll;
    private final TextView orderRecent;
    private final LinearLayout plan;
    private final LinearLayout grid;
    private final Runnable searchLater = () -> Parts.guard(this::searchNow);

    private boolean setting;
    private String query = "";
    /** What the lists show, to leave them alone when an answer changed nothing. */
    private final List<RecipeSource.Planned> shownMeals = new ArrayList<>();
    private List<RecipeSource.Summary> shownRecipes;
    private String shownPlanState;
    private String shownGridState;

    HomeScreen(Parts parts, Ui.Actions actions, Images images, Strings strings, boolean wide, int widthPx) {
        this.parts = parts;
        this.actions = actions;
        this.images = images;
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
        clear = parts.iconButton("mdi:close-circle-outline", strings.close, "onSurfaceVariant", 44, () -> {
            setQuery("");
            searchNow();
            hideKeyboard();
        });
        clear.setBackground(null);
        LinearLayout searchRow = parts.row();
        searchRow.addView(search, parts.fill(1));
        searchRow.addView(clear);

        LinearLayout orders = parts.row();
        orderAll = orderChip(strings.all, RecipeSource.Order.NAME);
        orderRecent = orderChip(strings.recent, RecipeSource.Order.RECENT);
        orders.addView(orderAll, parts.wrap());
        orders.addView(orderRecent, parts.after(8));

        plan = parts.column();
        grid = parts.column();

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
            right.addView(scroller(grid), new LinearLayout.LayoutParams(Parts.MATCH, 0, 1));
            panes.addView(right, new LinearLayout.LayoutParams(0, Parts.MATCH, 7));
            body = panes;
        } else {
            LinearLayout column = parts.column();
            column.addView(searchRow, parts.wide(4));
            LinearLayout both = parts.column();
            both.addView(plan, parts.wide());
            both.addView(parts.text(strings.recipes, 16, 700, "onSurface", 1), parts.wide(20));
            both.addView(orders, parts.wide(8));
            both.addView(grid, parts.wide());
            column.addView(scroller(both), new LinearLayout.LayoutParams(Parts.MATCH, 0, 1));
            body = column;
        }
        page.addView(body, new LinearLayout.LayoutParams(Parts.MATCH, 0, 1));
    }

    @Override public View view() {
        return root;
    }

    @Override public void show(Model model) {
        date.setText(model.date);
        message.setVisibility(model.configured ? View.GONE : View.VISIBLE);
        message.setText(model.strings.notConfigured);
        body.setVisibility(model.configured ? View.VISIBLE : View.GONE);
        if (!model.configured) return;
        query = model.query;
        // What the cook is typing wins over the answer to what they typed before.
        if (!search.hasFocus() && !model.query.equals(search.getText().toString().trim())) setQuery(model.query);
        clear.setVisibility(search.getText().length() > 0 ? View.VISIBLE : View.GONE);
        order(orderAll, model.order == RecipeSource.Order.NAME);
        order(orderRecent, model.order == RecipeSource.Order.RECENT);
        showPlan(model);
        showGrid(model);
    }

    /** Stop what is waiting. The screen is going away. */
    void leave() {
        search.removeCallbacks(searchLater);
    }

    // The search field.

    private EditText searchField(Strings strings) {
        EditText field = new EditText(parts.context());
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        field.setFilters(new InputFilter[] {new InputFilter.LengthFilter(200)});
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        field.setHint(strings.searchHint);
        parts.theme().styleText(field, 16, 400, "onSurface");
        field.setHintTextColor(parts.theme().color("onSurfaceVariant"));
        field.setBackgroundTintList(null);
        field.setBackground(parts.round(parts.theme().color("surfaceContainerHigh"), parts.theme().radiusControl()));
        field.setPadding(parts.px(14), parts.px(11), parts.px(14), parts.px(11));
        field.setMinHeight(parts.px(48));
        Drawable glass = parts.icon("mdi:magnify", "onSurfaceVariant");
        if (glass != null) {
            glass.setBounds(0, 0, parts.px(22), parts.px(22));
            field.setCompoundDrawablesRelative(glass, null, null, null);
            field.setCompoundDrawablePadding(parts.px(10));
        }
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable text) {
                if (setting) return;
                clear.setVisibility(text.length() > 0 ? View.VISIBLE : View.GONE);
                field.removeCallbacks(searchLater);
                field.postDelayed(searchLater, SEARCH_AFTER_MS);
            }
        });
        field.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            Parts.guard(() -> {
                searchNow();
                hideKeyboard();
            });
            return true;
        });
        return field;
    }

    private void setQuery(String text) {
        setting = true;
        search.setText(text);
        setting = false;
        search.setSelection(search.getText().length());
        clear.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void searchNow() {
        search.removeCallbacks(searchLater);
        String text = search.getText().toString().trim();
        if (!text.equals(query)) actions.search(text);
    }

    private void hideKeyboard() {
        search.clearFocus();
        InputMethodManager keyboard = (InputMethodManager) parts.context().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(search.getWindowToken(), 0);
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
        String state = model.planLoading + "|" + model.resumeAt + "|" + (model.recipe == null ? "" : model.recipe.id);
        if (meals.equals(shownMeals) && state.equals(shownPlanState)) return;
        shownMeals.clear();
        shownMeals.addAll(meals);
        shownPlanState = state;
        plan.removeAllViews();
        if (model.resumeAt > 0 && model.recipe != null) plan.addView(resumeCard(model), parts.wide(4));
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
        images.load(picture, model.recipe.image, actions.imageHeaders(model.recipe.image), parts.px(THUMBNAIL_WIDTH_DP));
        card.addView(picture, new LinearLayout.LayoutParams(parts.px(72), parts.px(72)));
        LinearLayout words = parts.column();
        words.addView(parts.text(strings.cooking, 13, 600, "onSurfaceVariant", 1), parts.wide());
        words.addView(parts.text(model.recipe.name, 18, 700, "onSurface", 2), parts.wide());
        words.addView(parts.text(String.format(strings.continueAt, model.resumeAt), 14, 500, "onSurface", 1), parts.wide(2));
        LinearLayout.LayoutParams wordsAt = parts.fill(1);
        wordsAt.setMarginStart(parts.px(14));
        card.addView(words, wordsAt);
        card.addView(parts.iconButton("mdi:stop", strings.endCooking, "onSurface", 48, actions::backToPlan));
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
        String state = model.recipesLoading + "|" + model.recipesMore;
        if (model.recipes.equals(shownRecipes) && state.equals(shownGridState)) return;
        shownRecipes = model.recipes;
        shownGridState = state;
        grid.removeAllViews();
        for (int at = 0; at < model.recipes.size(); at += columns) {
            LinearLayout row = parts.row();
            row.setGravity(Gravity.TOP);
            row.setBaselineAligned(false);
            for (int column = 0; column < columns; column++) {
                LinearLayout.LayoutParams cellAt = parts.fill(1);
                if (column > 0) cellAt.setMarginStart(parts.px(12));
                int index = at + column;
                if (index < model.recipes.size()) row.addView(cell(model.recipes.get(index)), cellAt);
                else row.addView(new View(parts.context()), cellAt);
            }
            grid.addView(row, parts.wide(12));
        }
        if (model.recipesLoading) {
            TextView loading = parts.text(model.strings.loading, 15, 400, "onSurfaceVariant", 1);
            loading.setGravity(Gravity.CENTER);
            loading.setPadding(0, parts.px(16), 0, parts.px(16));
            grid.addView(loading, parts.wide());
        } else if (model.recipes.isEmpty()) {
            TextView none = parts.text(model.strings.noRecipes, 16, 400, "onSurfaceVariant", 0);
            none.setGravity(Gravity.CENTER);
            none.setPadding(0, parts.px(32), 0, parts.px(32));
            grid.addView(none, parts.wide());
        } else if (model.recipesMore) {
            LinearLayout.LayoutParams at = parts.wide(16);
            at.gravity = Gravity.CENTER_HORIZONTAL;
            grid.addView(parts.pill(model.strings.loadMore, false, actions::loadMore), at);
        }
        grid.addView(new View(parts.context()), new LinearLayout.LayoutParams(1, parts.px(20)));
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
