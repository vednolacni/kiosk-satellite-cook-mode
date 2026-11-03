// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** A recipe server that answers from memory, with the kind of data a real Tandoor holds. */
final class Kitchen implements RecipeSource {
    static final LocalDate TODAY = LocalDate.of(2026, 11, 2);

    final String pictures;
    final List<String> cooked = new ArrayList<>();
    final List<String> viewed = new ArrayList<>();
    /** The next call throws this, once. */
    SourceException failNext;
    private final Map<String, Recipe> recipes = new LinkedHashMap<>();

    Kitchen(String pictures) {
        this.pictures = pictures;
        recipes.put("1", goulash());
        recipes.put("2", pancakes());
        recipes.put("3", soup());
    }

    private String picture(int number) {
        return pictures + "/" + number + ".png";
    }

    private static Recipe.Ingredient food(double amount, String unit, String food, String note) {
        return new Recipe.Ingredient(false, amount, unit, "", food, "", note, "");
    }

    private Recipe goulash() {
        List<Recipe.Ingredient> first = Arrays.asList(food(800, "g", "goveje meso", "za golaž"), food(500, "g", "čebula", ""), food(30, "g", "mast", ""));
        List<Recipe.Ingredient> second = Arrays.asList(food(2, "žlici", "sladka paprika", ""), food(Double.NaN, "", "sol", "po okusu"), food(0.5, "žličke", "kumina", "mleta"));
        List<Recipe.Ingredient> third = Arrays.asList(food(1, "l", "voda", ""), Recipe.Ingredient.header("Za konec"), food(2, "stroka", "česen", ""));
        String longStep = "Medtem pripravi prilogo. Polento zakuhaj v slanem kropu in jo ob stalnem mešanju kuhaj 5 minut, da se zgosti.\n\n"
            + "Golažu dodaj strt česen in majaron. Če je pregost, prilij malo vode, če je preredek, ga kuhaj odkritega še 10 minut. Kruh pogrej v pečici na 180 °C.\n\n"
            + "Poskusi in po potrebi dosoli. Pusti ga stati 15 minut, da se okusi povežejo, potem pa ga znova pogrej.";
        return new Recipe("1", "Goveji golaž", "Pravi domači golaž, ki se kuha počasi in je naslednji dan še boljši.", picture(1), 4, "", 30, 120,
            "http://tandoor.test/view/recipe/1", "", Arrays.asList(
                new Recipe.Step("Čebula", "<p>Čebulo drobno nasekljaj in jo na <scalable-number v-bind:number=\"30.0\"></scalable-number> g masti praži 10–15 minut, da lepo porumeni.</p>", Recipe.Format.TANDOOR_HTML, first, 0),
                new Recipe.Step("", "Dodaj meso na kocke, ki je čez noč počivalo v marinadi, papriko, sol, kumino in 2 lovorjeva lista. Praži še 5 minut na močnem ognju.", Recipe.Format.PLAIN, second, 0),
                new Recipe.Step("Dušenje", "Dolij vodo, pokrij in duši 1 uro 30 minut na majhnem ognju. Občasno premešaj.", Recipe.Format.PLAIN, third, 90 * 60),
                new Recipe.Step("Priloga in zaključek", longStep, Recipe.Format.PLAIN, Collections.<Recipe.Ingredient>emptyList(), 0),
                new Recipe.Step("Postrezi", "Postrezi s polento in svežim kruhom.", Recipe.Format.PLAIN, Collections.<Recipe.Ingredient>emptyList(), 5 * 60)));
    }

    private Recipe pancakes() {
        return new Recipe("2", "Palačinke", "", picture(2), 4, "kosov", 20, 0, "", "", Arrays.asList(
            new Recipe.Step("", "Zmešaj moko, jajca in mleko, nato pusti testo počivati 30 min.", Recipe.Format.PLAIN, Arrays.asList(food(200, "g", "moka", ""), food(2, "", "jajci", ""), food(300, "ml", "mleko", "")), 0),
            new Recipe.Step("", "Peci na vroči ponvi, približno 1 minuto na vsaki strani.", Recipe.Format.PLAIN, Collections.<Recipe.Ingredient>emptyList(), 0)));
    }

    private Recipe soup() {
        return new Recipe("3", "Zelenjavna juha", "", "", 0, "", 0, 0, "http://tandoor.test/view/recipe/3", "", Collections.<Recipe.Step>emptyList());
    }

    private void maybeFail() throws SourceException {
        if (failNext != null) {
            SourceException failure = failNext;
            failNext = null;
            throw failure;
        }
    }

    @Override public String probe() {
        return "Tandoor 2.7.2, 3 recipes";
    }

    @Override public List<Planned> mealPlan(LocalDate first, LocalDate last) throws SourceException {
        maybeFail();
        return Arrays.asList(
            new Planned("a", TODAY, "Kosilo", 12 * 60, 0xFF43A047, "1", "Goveji golaž", "", 4, picture(1), 150),
            new Planned("b", TODAY, "Večerja", 19 * 60, 0, null, "Ostanki", "Kar je ostalo od kosila", 0, "", 0),
            new Planned("c", TODAY.plusDays(1), "Kosilo", 12 * 60, 0, "3", "Zelenjavna juha", "", 0, "", 40),
            new Planned("d", TODAY.plusDays(2), "Večerja", 19 * 60, 0xFFE53935, "2", "Palačinke", "", 2, picture(2), 20));
    }

    @Override public Page recipes(String query, Order order, int page) throws SourceException {
        maybeFail();
        List<Summary> all = new ArrayList<>();
        all.add(new Summary("1", "Goveji golaž", picture(1), 150));
        all.add(new Summary("2", "Palačinke", picture(2), 20));
        all.add(new Summary("3", "Zelenjavna juha", "", 40));
        String[] names = {"Pečen piščanec z limono in rožmarinom", "Ričet", "Jota", "Štruklji", "Gobova kremna juha", "Lazanja", "Rižota z gobami", "Kruh iz droži", "Potica", "Sirova plošča", "Bučna juha", "Pražen krompir", "Solata s tuno", "Kremšnita"};
        for (int i = 0; i < names.length; i++) all.add(new Summary(String.valueOf(10 + i), names[i], picture(4 + i), i % 3 == 0 ? 0 : 25 + i * 5));
        if (order == Order.RECENT) Collections.reverse(all);
        List<Summary> found = new ArrayList<>();
        for (Summary recipe : all) if (query.isEmpty() || recipe.name.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))) found.add(recipe);
        int size = 12;
        int from = Math.min(found.size(), (page - 1) * size);
        int to = Math.min(found.size(), page * size);
        return new Page(found.subList(from, to), to < found.size(), found.size());
    }

    @Override public Recipe recipe(String id) throws SourceException {
        maybeFail();
        Recipe recipe = recipes.get(id);
        if (recipe == null) throw new SourceException(SourceException.Kind.NOT_FOUND, "No recipe " + id);
        return recipe;
    }

    @Override public void viewed(String recipeId) {
        viewed.add(recipeId);
    }

    @Override public void cooked(String recipeId, double servings) {
        cooked.add(recipeId + " " + servings);
    }

    @Override public Map<String, String> imageHeaders(String url) {
        return Collections.emptyMap();
    }

    @Override public void cancel() {}
}
