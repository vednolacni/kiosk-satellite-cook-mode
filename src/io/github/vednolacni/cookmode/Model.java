// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * What the main view shows, worked out by the controller so the view only lays it out. A view rebuilds a page only
 * when pageVersion changes: rebuilding under a finger swallows its tap, and timers tick separately in place.
 */
final class Model {
    enum Page { HOME, RECIPE, STEP, FINISH }

    /** One day of the meal plan. */
    static final class Day {
        final String label;
        final List<RecipeSource.Planned> meals;

        Day(String label, List<RecipeSource.Planned> meals) {
            this.label = label;
            this.meals = Collections.unmodifiableList(meals);
        }
    }

    final Page page;
    /** Changes whenever the current page's content changes. */
    final long pageVersion;
    final Strings strings;
    /** The step text's smallest and largest sizes in sp. Short steps get the room they leave. */
    final int textSize;
    final int textMost;
    /** A problem to show above the page, in the panel's language, or null. */
    final String banner;
    /** A question that needs an answer before anything else, or null, and the word for yes. */
    final String confirm;
    final String confirmLabel;

    // Home.
    final String date;
    final boolean configured;
    final List<Day> days;
    final boolean planLoading;
    final String query;
    final RecipeSource.Order order;
    final List<RecipeSource.Summary> recipes;
    final boolean recipesLoading;
    final boolean recipesMore;

    // Recipe, step and finish.
    /** The recipe, or null while it loads, with name and image from the list it was opened from. */
    final Recipe recipe;
    final String recipeName;
    final String recipeImage;
    final boolean recipeLoading;
    final double servings;
    final double factor;
    final Set<Recipe.Ingredient> checked;
    final List<Recipe.Ingredient> ingredients;
    /** The step to continue at, counted from 1, or 0 when cooking has not started. */
    final int resumeAt;

    // Step.
    final int pageNumber;
    final int pageCount;
    final String stepTitle;
    final List<StepText.Run> runs;
    /** The step's own time when its text does not name it, or null. */
    final StepText.Run ownTimer;
    final List<Recipe.Ingredient> stepIngredients;
    final String previousPeek;
    final String nextPeek;
    /** Ready around this time, as text, or an empty string. */
    final String readyAround;
    final boolean canRead;
    final boolean swipes;
    /** A note that the numbers in the words do not change with the servings, or an empty string. */
    final String fixedNote;

    // Finish.
    final boolean canLogCooked;
    final boolean logged;

    Model(Page page, long pageVersion, Strings strings, int textSize, int textMost, String banner, String confirm, String confirmLabel, String date, boolean configured, List<Day> days, boolean planLoading,
          String query, RecipeSource.Order order, List<RecipeSource.Summary> recipes, boolean recipesLoading, boolean recipesMore,
          Recipe recipe, String recipeName, String recipeImage, boolean recipeLoading, double servings, double factor, Set<Recipe.Ingredient> checked,
          List<Recipe.Ingredient> ingredients, int resumeAt, int pageNumber, int pageCount, String stepTitle, List<StepText.Run> runs, StepText.Run ownTimer,
          List<Recipe.Ingredient> stepIngredients, String previousPeek, String nextPeek, String readyAround, boolean canRead, boolean swipes, String fixedNote, boolean canLogCooked, boolean logged) {
        this.page = page;
        this.pageVersion = pageVersion;
        this.strings = strings;
        this.textSize = textSize;
        this.textMost = textMost;
        this.banner = banner;
        this.confirm = confirm;
        this.confirmLabel = confirmLabel;
        this.date = date;
        this.configured = configured;
        this.days = days;
        this.planLoading = planLoading;
        this.query = query;
        this.order = order;
        this.recipes = recipes;
        this.recipesLoading = recipesLoading;
        this.recipesMore = recipesMore;
        this.recipe = recipe;
        this.recipeName = recipeName;
        this.recipeImage = recipeImage;
        this.recipeLoading = recipeLoading;
        this.servings = servings;
        this.factor = factor;
        this.checked = checked;
        this.ingredients = ingredients;
        this.resumeAt = resumeAt;
        this.pageNumber = pageNumber;
        this.pageCount = pageCount;
        this.stepTitle = stepTitle;
        this.runs = runs;
        this.ownTimer = ownTimer;
        this.stepIngredients = stepIngredients;
        this.previousPeek = previousPeek;
        this.nextPeek = nextPeek;
        this.readyAround = readyAround;
        this.canRead = canRead;
        this.swipes = swipes;
        this.fixedNote = fixedNote;
        this.canLogCooked = canLogCooked;
        this.logged = logged;
    }
}
