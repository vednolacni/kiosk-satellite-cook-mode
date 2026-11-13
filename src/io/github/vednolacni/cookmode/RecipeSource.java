// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * A recipe app the plugin can cook from: Tandoor and Norish. Mealie, KitchenOwl and Grocy fit the same shape: see
 * docs/DESIGN.md for how each maps. Every method blocks on the network, so the plugin calls them only on its own
 * network thread, and each throws SourceException with a message fit for the user.
 */
interface RecipeSource {
    /** Why a call failed, with a message the plugin can show as it is. */
    final class SourceException extends Exception {
        private static final long serialVersionUID = 1L;

        /** LIMIT: the app has had too many requests with this key for now, and the source sends none until it may. */
        enum Kind { AUTH, READ_ONLY, HOST, NOT_API, NOT_FOUND, PRIVATE, NETWORK, LOCAL_NAME, TLS, SERVER, PARSE, UNSUPPORTED, LIMIT }

        final Kind kind;

        SourceException(Kind kind, String message) {
            super(message);
            this.kind = kind;
        }

        SourceException(Kind kind, String message, Throwable cause) {
            super(message, cause);
            this.kind = kind;
        }
    }

    /** One meal on one day of the meal plan. */
    final class Planned {
        final String id;
        final LocalDate date;
        /** The meal's slot, such as Lunch, or an empty string. */
        final String slot;
        /** Orders the slots of a day: the slot's time in minutes after midnight when known, else its own order. */
        final int slotOrder;
        /** The slot's color as ARGB, or 0. */
        final int color;
        /** The recipe to cook, or null for a note such as "Eat out". */
        final String recipeId;
        final String title;
        final String note;
        /** The servings planned, or 0 for the recipe's own. */
        final double servings;
        final String image;
        final int minutes;

        Planned(String id, LocalDate date, String slot, int slotOrder, int color, String recipeId, String title, String note, double servings, String image, int minutes) {
            this.id = id;
            this.date = date;
            this.slot = slot;
            this.slotOrder = slotOrder;
            this.color = color;
            this.recipeId = recipeId;
            this.title = title;
            this.note = note;
            this.servings = servings;
            this.image = image;
            this.minutes = minutes;
        }

        /** By value, so a list read again with nothing new in it leaves the view alone. */
        @Override public boolean equals(Object other) {
            if (!(other instanceof Planned)) return false;
            Planned meal = (Planned) other;
            return slotOrder == meal.slotOrder && color == meal.color && servings == meal.servings && minutes == meal.minutes && Objects.equals(id, meal.id)
                && Objects.equals(date, meal.date) && Objects.equals(slot, meal.slot) && Objects.equals(recipeId, meal.recipeId) && Objects.equals(title, meal.title)
                && Objects.equals(note, meal.note) && Objects.equals(image, meal.image);
        }

        @Override public int hashCode() {
            return Objects.hash(id, date, title);
        }
    }

    /** A recipe in a list: search results, all recipes, recently viewed. */
    final class Summary {
        final String id;
        final String name;
        final String image;
        final int minutes;

        Summary(String id, String name, String image, int minutes) {
            this.id = id;
            this.name = name;
            this.image = image;
            this.minutes = minutes;
        }

        /** By value, like Planned. */
        @Override public boolean equals(Object other) {
            if (!(other instanceof Summary)) return false;
            Summary recipe = (Summary) other;
            return minutes == recipe.minutes && Objects.equals(id, recipe.id) && Objects.equals(name, recipe.name) && Objects.equals(image, recipe.image);
        }

        @Override public int hashCode() {
            return Objects.hash(id, name);
        }
    }

    /** One page of a recipe list. */
    final class Page {
        final List<Summary> items;
        final boolean more;
        final int total;

        Page(List<Summary> items, boolean more, int total) {
            this.items = Collections.unmodifiableList(new ArrayList<>(items));
            this.more = more;
            this.total = total;
        }
    }

    /** The source for the app the settings name. steadyMillis is a clock nobody can set that counts while the tablet sleeps. */
    static RecipeSource of(Settings settings, LongSupplier steadyMillis) {
        if (settings.app == Settings.App.NORISH) return new NorishSource(settings.serverUrl, settings.apiToken, new Http(), steadyMillis);
        return new TandoorSource(settings.serverUrl, settings.apiToken, new Http());
    }

    /** How the recipe list is ordered. RECENT is what the app knows as recent: viewed in Tandoor, added in Norish. */
    enum Order { NAME, RECENT }

    /** Check the address and the token. Returns what Test connection reports, such as "Connected to Tandoor 2.7.2, 140 recipes." */
    String probe() throws SourceException;

    /** The meals planned from first to last, both included, in date and slot order. */
    List<Planned> mealPlan(LocalDate first, LocalDate last) throws SourceException;

    /** The last day the app can tell the meals of, when the plan is read from first on. Most can tell any day. */
    default LocalDate planReach(LocalDate first) {
        return LocalDate.MAX;
    }

    /** How often to read the plan while nobody asks for it, given how often the plugin would. */
    default long planRefresh(long usual) {
        return usual;
    }

    /** Whether the app counts each request against a limit, also one it answers with an error. */
    default boolean countsRequests() {
        return false;
    }

    /** A source for the same server that knows what this one has learned, with connections of its own, or null. */
    default RecipeSource twin() {
        return null;
    }

    /** Recipes matching query, or all recipes when it is empty. page counts from 1. */
    Page recipes(String query, Order order, int page) throws SourceException;

    Recipe recipe(String id) throws SourceException;

    /** Tell the app the recipe was opened, for its recently viewed list. Optional: does nothing when unsupported. */
    void viewed(String recipeId) throws SourceException;

    /** Tell the app the recipe was cooked. Throws UNSUPPORTED when the app cannot record it. */
    void cooked(String recipeId, double servings) throws SourceException;

    /** Headers an image request needs, such as a key for apps that protect their pictures. Never the token for another host. */
    Map<String, String> imageHeaders(String url);

    /** Close open connections, so a stopping plugin is not held up by a slow server. */
    void cancel();
}
