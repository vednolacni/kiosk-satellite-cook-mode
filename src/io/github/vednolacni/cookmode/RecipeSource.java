// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A recipe app the plugin can cook from. Tandoor ships first. Mealie, Norish, KitchenOwl and Grocy fit the same
 * shape: see docs/DESIGN.md for how each maps. Every method blocks on the network, so the plugin calls them only on
 * its own network thread, and each throws SourceException with a message fit for the user.
 */
interface RecipeSource {
    /** Why a call failed, with a message the plugin can show as it is. */
    final class SourceException extends Exception {
        private static final long serialVersionUID = 1L;

        enum Kind { AUTH, READ_ONLY, HOST, NOT_API, NOT_FOUND, NETWORK, LOCAL_NAME, TLS, SERVER, PARSE, UNSUPPORTED }

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

    /** How the recipe list is ordered. */
    enum Order { NAME, RECENT }

    /** Check the address and the token. Returns what Test connection reports, such as "Tandoor 2.7.2, 140 recipes". */
    String probe() throws SourceException;

    /** The meals planned from first to last, both included, in date and slot order. */
    List<Planned> mealPlan(LocalDate first, LocalDate last) throws SourceException;

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
