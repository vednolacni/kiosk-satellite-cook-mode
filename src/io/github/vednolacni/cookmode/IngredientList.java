// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/**
 * The ingredients with a circle to tick each one off, on the recipe page and beside the steps. Changing the servings
 * or ticking a line changes the row in place, so a held plus button keeps working.
 */
final class IngredientList {
    private static final class Row {
        final Recipe.Ingredient ingredient;
        final ImageView circle;
        final TextView amount;
        final TextView rest;

        Row(Recipe.Ingredient ingredient, ImageView circle, TextView amount, TextView rest) {
            this.ingredient = ingredient;
            this.circle = circle;
            this.amount = amount;
            this.rest = rest;
        }
    }

    private final Parts parts;
    private final Ui.Actions actions;
    private final LinearLayout view;
    private final List<Row> rows = new ArrayList<>();
    private Recipe shown;

    IngredientList(Parts parts, Ui.Actions actions) {
        this.parts = parts;
        this.actions = actions;
        view = parts.column();
    }

    View view() {
        return view;
    }

    void show(Model model) {
        if (model.recipe != shown) {
            shown = model.recipe;
            build(model.ingredients);
        }
        for (Row row : rows) update(row, model);
    }

    private void build(List<Recipe.Ingredient> ingredients) {
        view.removeAllViews();
        rows.clear();
        boolean first = true;
        for (Recipe.Ingredient ingredient : ingredients) {
            if (ingredient.header) {
                view.addView(parts.text(ingredient.food, 14, 700, "primary", 2), parts.wide(first ? 4 : 18));
            } else {
                view.addView(row(ingredient), parts.wide(2));
            }
            first = false;
        }
    }

    private View row(Recipe.Ingredient ingredient) {
        LinearLayout line = parts.row();
        line.setBackground(parts.ripple(android.graphics.Color.TRANSPARENT, parts.theme().radiusControl()));
        line.setMinimumHeight(parts.px(48));
        line.setPadding(parts.px(4), parts.px(6), parts.px(8), parts.px(6));
        ImageView circle = new ImageView(parts.context());
        circle.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int inner = parts.px(4);
        circle.setPadding(inner, inner, inner, inner);
        line.addView(circle, new LinearLayout.LayoutParams(parts.px(26), parts.px(26)));
        TextView amount = parts.text("", 17, 700, "onSurface", 1);
        amount.setMinWidth(parts.px(68));
        LinearLayout.LayoutParams amountAt = parts.after(10);
        line.addView(amount, amountAt);
        TextView rest = parts.text("", 17, 400, "onSurface", 0);
        LinearLayout.LayoutParams restAt = parts.fill(1);
        restAt.setMarginStart(parts.px(8));
        line.addView(rest, restAt);
        Row row = new Row(ingredient, circle, amount, rest);
        rows.add(row);
        Parts.onTap(line, () -> actions.toggle(ingredient));
        return line;
    }

    private void update(Row row, Model model) {
        boolean checked = model.checked.contains(row.ingredient);
        String amount = row.ingredient.amountText(model.factor, model.strings.decimalComma);
        row.amount.setText(amount);
        row.amount.setVisibility(amount.isEmpty() ? View.GONE : View.VISIBLE);
        row.rest.setText(row.ingredient.rest(model.factor));
        // A color rather than alpha: a change of alpha alone may not reach an overlay's screen.
        int color = parts.theme().color(checked ? "onSurfaceVariant" : "onSurface");
        row.amount.setTextColor(color);
        row.rest.setTextColor(color);
        int flags = row.rest.getPaintFlags();
        int struck = checked ? flags | Paint.STRIKE_THRU_TEXT_FLAG : flags & ~Paint.STRIKE_THRU_TEXT_FLAG;
        row.rest.setPaintFlags(struck);
        row.amount.setPaintFlags(struck);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        if (checked) {
            ring.setColor(parts.theme().color("primary"));
        } else {
            ring.setColor(android.graphics.Color.TRANSPARENT);
            ring.setStroke(Math.max(2, parts.px(2)), parts.theme().color("outline"));
        }
        row.circle.setBackground(ring);
        row.circle.setImageDrawable(checked ? parts.icon("mdi:check", "onPrimary") : null);
    }
}
