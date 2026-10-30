// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.kitchen;

import java.time.LocalDate;
import java.util.Map;

public final class RecipeLinksTest {
    static Meal meal(String summary, String description, String location) {
        return new Meal(LocalDate.of(2026, 10, 28), null, summary, description, location, 0, 0);
    }

    public static void main(String[] args) {
        // A link anywhere in the description, without the punctuation that ends its sentence.
        assert "https://recipes.local/view/recipe/12".equals(RecipeLinks.first("Recept: https://recipes.local/view/recipe/12."));
        assert "https://a.example/c".equals(RecipeLinks.first("(see https://a.example/c)"));
        assert "http://tandoor_web:8080/view/recipe/3".equals(RecipeLinks.first("http://tandoor_web:8080/view/recipe/3"));
        assert RecipeLinks.first("javascript:alert(1)") == null;
        assert RecipeLinks.first("ftp://files.example/recipe") == null;
        assert RecipeLinks.first("") == null && RecipeLinks.first(null) == null;
        // KS refuses addresses with a user name or password, so they are skipped.
        assert RecipeLinks.first("https://me:secret@recipes.local/x https://recipes.local/y").equals("https://recipes.local/y");

        assert RecipeLinks.valid("http://192.168.1.20:8080");
        assert !RecipeLinks.valid("http:///no-host") && !RecipeLinks.valid("http://:8080/") && !RecipeLinks.valid("recipes.local");
        assert RecipeLinks.validTemplate("http://recipes.local/search/?query={title}");
        assert !RecipeLinks.validTemplate("http://recipes.local/search/") && !RecipeLinks.validTemplate("");

        Map<String, Object> values = MealPlanTest.settings("calendar.meals", "");
        Settings home = new Settings(values);
        // The event's link wins, then the location's, then the app's home page.
        assert "https://r.local/1".equals(RecipeLinks.target(meal("Dinner: Pasta", "https://r.local/1", "https://r.local/2"), home));
        assert "https://r.local/2".equals(RecipeLinks.target(meal("Dinner: Pasta", "No link", "https://r.local/2"), home));
        assert "http://recipes.local:8080".equals(RecipeLinks.target(meal("Dinner: Pasta", "", ""), home));
        assert RecipeLinks.recipe(meal("Dinner: Pasta", "", ""), home) == null;

        // The search address gets the dish, encoded, without the meal type.
        values.put("searchUrl", "http://recipes.local:8080/search/?query={title}");
        Settings search = new Settings(values);
        assert "http://recipes.local:8080/search/?query=Lazanja%20s%20%C5%A1pina%C4%8Do".equals(RecipeLinks.recipe(meal("Večerja: Lazanja s špinačo", "", ""), search));
        // Without an app address and a search address nothing opens.
        values.put("searchUrl", "");
        values.put("appUrl", "");
        assert RecipeLinks.target(meal("Dinner: Pasta", "", ""), new Settings(values)) == null;
        System.out.println("RecipeLinksTest passed");
    }
}
