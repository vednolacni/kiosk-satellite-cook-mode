// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A recipe as the cooking view needs it, whichever app it came from. Sources fill it in, and the views never see an
 * app's own JSON. Amounts are stored for the recipe's own servings and scaled when shown. Immutable.
 */
final class Recipe {
    /** How a step's text is written. Tandoor sends HTML whose amounts are tags the client scales. */
    enum Format { PLAIN, MARKDOWN, TANDOOR_HTML }

    /** One ingredient line. A header groups the lines after it, such as "For the sauce". */
    static final class Ingredient {
        final boolean header;
        /** The amount for the recipe's own servings, or NaN when the line has none, as with "salt to taste". */
        final double amount;
        final String unit;
        final String unitPlural;
        final String food;
        final String foodPlural;
        final String note;
        /** The line as the recipe first had it, shown when there is no food name. */
        final String raw;

        Ingredient(boolean header, double amount, String unit, String unitPlural, String food, String foodPlural, String note, String raw) {
            this.header = header;
            this.amount = amount;
            this.unit = unit;
            this.unitPlural = unitPlural;
            this.food = food;
            this.foodPlural = foodPlural;
            this.note = note;
            this.raw = raw;
        }

        static Ingredient header(String title) {
            return new Ingredient(true, Double.NaN, "", "", title, "", "", title);
        }

        boolean hasAmount() {
            return !header && !Double.isNaN(amount) && amount > 0;
        }

        /** The amount for these servings as text, such as 1½, or an empty string. */
        String amountText(double factor, boolean decimalComma) {
            return hasAmount() ? Amounts.format(amount * factor, decimalComma) : "";
        }

        /** The amount and its unit for these servings, such as 200 g, or an empty string. */
        String measure(double factor, boolean decimalComma) {
            String amount = amountText(factor, decimalComma);
            if (amount.isEmpty()) return "";
            String unitName = plural(factor) && !unitPlural.isEmpty() ? unitPlural : unit;
            return unitName.isEmpty() ? amount : amount + " " + unitName;
        }

        /** What the line is about: "flour, sifted". */
        String name(double factor) {
            if (header) return food;
            String foodName = plural(factor) && !foodPlural.isEmpty() ? foodPlural : food;
            if (foodName.isEmpty()) return (note.isEmpty() ? rawName() : note).trim();
            return (note.isEmpty() ? foodName : foodName + ", " + note).trim();
        }

        /** With nothing else to go on, the line as first written, without the amount and unit that are shown scaled beside it. */
        private String rawName() {
            String rest = raw.trim();
            if (!hasAmount()) return rest;
            // All that says the amount again: ca. 200, 2 do 3, 1–2, 1 and 1/2, pol.
            rest = rest.replaceFirst("(?i)^(?:(?:ca|cca|pribl|approx)\\.? +|(?:približno|okoli|okrog|about|etwa|pol|half) +)?[0-9½¼¾⅓⅔.,/⁄ –—-]*(?:(?:do|to|bis|and|in|und) +[0-9½¼¾⅓⅔.,/⁄ –—-]+)?", "");
            for (String name : new String[] {unitPlural, unit}) {
                boolean stands = !name.isEmpty() && rest.regionMatches(true, 0, name, 0, name.length()) && (rest.length() == name.length() || !Character.isLetter(rest.charAt(name.length())));
                if (stands) return rest.substring(name.length()).replaceFirst("^[.,:;]+", "").trim();
            }
            // Another form of the unit than Tandoor keeps: 2 žlici for žlica, 2 cups for cup.
            String word = rest.split(" ", 2)[0];
            boolean sameUnit = unit.length() >= 3 && word.length() <= unit.length() + 2 && word.regionMatches(true, 0, unit, 0, unit.length() - 1);
            return sameUnit ? rest.substring(word.length()).trim() : rest;
        }

        /** The whole line: "200 g flour, sifted". */
        String line(double factor, boolean decimalComma) {
            String measure = measure(factor, decimalComma);
            String name = name(factor);
            return measure.isEmpty() ? name : measure + " " + name;
        }

        /** Plural names are used unless the amount is one, as Tandoor does. */
        private boolean plural(double factor) {
            return hasAmount() && Math.abs(amount * factor - 1) >= 0.0001;
        }
    }

    /** One step of the method. */
    static final class Step {
        /** The step's own name, or an empty string. */
        final String title;
        /** The text as the source wrote it, rendered for the chosen servings by Instructions. */
        final String text;
        final Format format;
        final List<Ingredient> ingredients;
        /** How long the step takes, in seconds, when the source says. 0 otherwise. */
        final int seconds;

        Step(String title, String text, Format format, List<Ingredient> ingredients, int seconds) {
            this.title = title;
            this.text = text;
            this.format = format;
            this.ingredients = Collections.unmodifiableList(new ArrayList<>(ingredients));
            this.seconds = seconds;
        }
    }

    final String id;
    final String name;
    final String description;
    /** An absolute address, or an empty string. */
    final String image;
    /** The servings the amounts are written for, or 0 when unknown. */
    final double servings;
    /** What a serving is called, such as "pieces". Empty means servings. */
    final String servingsText;
    final int workingMinutes;
    final int waitingMinutes;
    /** The recipe's page in its app. */
    final String webUrl;
    /** Where the recipe came from, such as a blog, or an empty string. */
    final String sourceUrl;
    final List<Step> steps;

    Recipe(String id, String name, String description, String image, double servings, String servingsText, int workingMinutes, int waitingMinutes, String webUrl, String sourceUrl, List<Step> steps) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.image = image;
        this.servings = servings;
        this.servingsText = servingsText;
        this.workingMinutes = workingMinutes;
        this.waitingMinutes = waitingMinutes;
        this.webUrl = webUrl;
        this.sourceUrl = sourceUrl;
        this.steps = Collections.unmodifiableList(new ArrayList<>(steps));
    }

    /** Every ingredient in order, with a header before each step's lines when several steps have their own. */
    List<Ingredient> ingredients() {
        List<Ingredient> all = new ArrayList<>();
        int stepsWithIngredients = 0;
        for (Step step : steps) if (!step.ingredients.isEmpty()) stepsWithIngredients++;
        for (Step step : steps) {
            if (step.ingredients.isEmpty()) continue;
            boolean ownHeader = !step.ingredients.get(0).header;
            if (stepsWithIngredients > 1 && ownHeader && !step.title.isEmpty()) all.add(Ingredient.header(step.title));
            all.addAll(step.ingredients);
        }
        return all;
    }

    /** How much to scale amounts for the chosen servings. */
    double factor(double chosenServings) {
        return servings > 0 && chosenServings > 0 ? chosenServings / servings : 1;
    }

    int totalMinutes() {
        return workingMinutes + waitingMinutes;
    }
}
