// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

/**
 * NorishSource against a local server that answers the way Norish 0.26 does under a base path (/norish), the way 0.25
 * does (/old), with a key past its 500 requests (/busy), with its database down (/down), with a server error (/broken),
 * and the way other things in front of it or at its address answer (/proxy, /web, /other). The answers follow Norish's
 * API reference and the shapes its routes send; nothing of Norish's own code is in here.
 */
public final class NorishTest {
    static final String KEY = "aBcDeFgHiJkLmNoP" + "aBcDeFgHiJkLmNoP" + "aBcDeFgHiJkLmNoP" + "aBcDeFgHiJkLmNoP";
    static final String JOTA = "5c0f3a2e-8b1d-4e6a-9f7c-2d4b6a8e1f03";
    static final String RICET = "6d1e4b3f-9c2e-4f7b-8a0d-3e5c7b9f2a14";
    static final String KRUH = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d";
    static final String MISSING = "0e1f2a3b-4c5d-4e6f-8a7b-9c0d1e2f3a4b";
    static final LocalDate TODAY = LocalDate.of(2026, 11, 6);
    static final List<String> requests = Collections.synchronizedList(new ArrayList<String>());
    /** What the server saw that Norish would refuse or that must never be sent. */
    static final List<String> wrong = Collections.synchronizedList(new ArrayList<String>());
    static String server;

    static String json(String quoted) {
        return quoted.replace('\'', '"');
    }

    static final String RECIPE = json("{'id':'" + JOTA + "','userId':'Hq3nV8tLwZ2pXk5mRb7yJc4dFs9gTe1A','name':'Jota','description':'Kraška enolončnica.',"
        + "'url':'https://example.org/jota','servings':6,'prepMinutes':20,'cookMinutes':90,'totalMinutes':150,'notes':null,'systemUsed':'metric','originalSystem':'metric',"
        + "'calories':410,'fat':'14.50','carbs':'48.00','protein':'21.25','originCountry':'SI','createdAt':'2026-10-12T16:03:27.418Z','categories':['Lunch'],'version':2,"
        + "'recipeIngredients':["
        + "{'id':'a1','joined':null,'amount':null,'unit':null,'order':0,'systemUsed':'metric','version':1,'ingredientName':'# Enolončnica','ingredientId':null,'foods':[]},"
        + "{'id':'a2','joined':null,'amount':500,'unit':'gram','order':1,'systemUsed':'metric','version':1,'ingredientName':'kislo zelje, oprano','ingredientId':'f1','foods':[]},"
        + "{'id':'a3','joined':null,'amount':1.102,'unit':'pound','order':1,'systemUsed':'us','version':1,'ingredientName':'kislo zelje, oprano','ingredientId':'f1','foods':[]},"
        + "{'id':'a4','joined':'alternatives','amount':2,'unit':'tablespoon','order':2,'systemUsed':'metric','version':1,'ingredientName':'mast ali olje','ingredientId':'f2','foods':[]},"
        + "{'id':'a5','joined':null,'amount':0.333,'unit':'teaspoon','order':3,'systemUsed':'metric','version':1,'ingredientName':'mleta **kumina**','ingredientId':'f3','foods':[]},"
        + "{'id':'a6','joined':null,'amount':null,'unit':'to_taste','order':4,'systemUsed':'metric','version':1,'ingredientName':'sol','ingredientId':'f4','foods':[]},"
        + "{'id':'a7','joined':null,'amount':3,'unit':'clove','order':5,'systemUsed':'metric','version':1,'ingredientName':'česen','ingredientId':'f5','foods':[]},"
        + "{'id':'a8','joined':null,'amount':1,'unit':'žlička','order':6,'systemUsed':'metric','version':1,'ingredientName':'sladkor','ingredientId':'f6','foods':[]},"
        + "{'id':'a9','joined':null,'amount':0,'unit':'','order':7,'systemUsed':'metric','version':1,'ingredientName':'voda','ingredientId':'f7','foods':[]}],"
        + "'steps':["
        + "{'step':'# Priprava','systemUsed':'metric','order':0,'version':1,'images':[],'stepIngredients':[]},"
        + "{'step':'Zelje in fižol skuhaj posebej, približno **60 minut**.','systemUsed':'metric','order':1,'version':1,"
        + "'images':[{'id':'i1','image':'/recipes/" + JOTA + "/steps/korak.jpg','order':0,'version':1}],'stepIngredients':[{'ingredientOrder':1,'share':1,'order':0}]},"
        + "{'step':'Cabbage and beans cook apart.','systemUsed':'us','order':1,'version':1,'images':[],'stepIngredients':[{'ingredientOrder':1,'share':1,'order':0}]},"
        + "{'step':'Na polovici masti prepraži kumino, vse združi in soli po okusu. Zraven ponudi [Domači kruh](id:" + KRUH + ").','systemUsed':'metric','order':2,'version':1,"
        + "'images':[],'stepIngredients':[{'ingredientOrder':3,'share':1,'order':1},{'ingredientOrder':2,'share':0.5,'order':0},{'ingredientOrder':99,'share':1,'order':2}]},"
        + "{'step':'## Omaka\\nPreostalo mast zmešaj z moko.','systemUsed':'metric','order':3,'version':1,'images':[],'stepIngredients':[{'ingredientOrder':2,'share':0.5,'order':0}]},"
        + "{'step':'Postrezi.','systemUsed':'metric','order':4,'version':1,'images':[],'stepIngredients':[]}],"
        + "'tags':[],'cuisines':[],'author':{'id':'Hq3nV8tLwZ2pXk5mRb7yJc4dFs9gTe1A','name':'Ana','image':null,'version':3},"
        + "'images':[{'id':'m2','image':'/recipes/" + JOTA + "/drugi.jpg','order':1,'generated':false,'version':1},"
        + "{'id':'m1','image':'/recipes/" + JOTA + "/prvi.jpg','order':0,'generated':false,'version':1}],'videos':[],'nutritionEstimate':null}");

    /** The recipes a search lists: three of their own and 27 more, so the list has pages. */
    static final List<Map<String, Object>> LISTED = new ArrayList<>();

    static {
        LISTED.add(listed(JOTA, "Jota", 20, 90, 150, "/recipes/" + JOTA + "/prvi.jpg", "2026-10-12T16:03:27.418Z"));
        LISTED.add(listed(RICET, "Ričet", 15, 60, null, null, "2026-10-20T08:00:00.000Z"));
        LISTED.add(listed(KRUH, "Domači kruh", null, null, null, "https://cdn.example/kruh.jpg", "2026-11-01T08:00:00.000Z"));
        for (int i = 1; i <= 27; i++) {
            String number = String.format("%02d", i);
            LISTED.add(listed("00000000-0000-4000-8000-0000000000" + number, "Recept " + number, null, null, 30, null, "2026-09-" + number + "T08:00:00.000Z"));
        }
    }

    static Map<String, Object> listed(String id, String name, Integer prep, Integer cook, Integer total, String image, String created) {
        Map<String, Object> recipe = new LinkedHashMap<>();
        recipe.put("id", id);
        recipe.put("userId", null);
        recipe.put("name", name);
        recipe.put("servings", 4);
        recipe.put("prepMinutes", prep);
        recipe.put("cookMinutes", cook);
        recipe.put("totalMinutes", total);
        recipe.put("createdAt", created);
        recipe.put("categories", new ArrayList<Object>());
        recipe.put("image", image);
        recipe.put("tags", new ArrayList<Object>());
        recipe.put("averageRating", null);
        recipe.put("ratingCount", 0);
        return recipe;
    }

    static String planned(String id, String date, String slot, int sortOrder, String type, String recipeId, String title, String name, String image) {
        String type1 = type == null ? "" : "'itemType':'" + type + "',";
        String title1 = type == null ? "" : "'title':" + (title == null ? "null" : "'" + title + "'") + ",";
        return json("{'id':'" + id + "','date':'" + date + "','slot':'" + slot + "','sortOrder':" + sortOrder + "," + type1
            + "'recipeId':" + (recipeId == null ? "null" : "'" + recipeId + "'") + "," + title1 + "'version':1,'recipeName':" + (name == null ? "null" : "'" + name + "'")
            + ",'recipeImage':" + (image == null ? "null" : "'" + image + "'") + ",'servings':" + (recipeId == null ? "null" : "6") + ",'calories':null}");
    }

    static final String WEEK = "[" + String.join(",",
        planned("e7f8a9b0-c1d2-4e3f-9a4b-5c6d7e8f9a01", "2026-11-02", "Lunch", 0, "recipe", JOTA, null, "Jota", "/recipes/" + JOTA + "/prvi.jpg"),
        planned("e7f8a9b0-c1d2-4e3f-9a4b-5c6d7e8f9a02", "2026-11-06", "Lunch", 1, "recipe", JOTA, null, "Jota", "/recipes/" + JOTA + "/prvi.jpg"),
        planned("e7f8a9b0-c1d2-4e3f-9a4b-5c6d7e8f9a03", "2026-11-06", "Lunch", 0, "note", null, "Solata z vrta", null, null),
        planned("e7f8a9b0-c1d2-4e3f-9a4b-5c6d7e8f9a04", "2026-11-06", "Breakfast", 0, "recipe", KRUH, null, "Domači kruh", "https://cdn.example/kruh.jpg"),
        planned("e7f8a9b0-c1d2-4e3f-9a4b-5c6d7e8f9a05", "2026-11-08", "Dinner", 0, "note", RICET, "Ostanki od sobote", "Ričet", null)) + "]";

    static final String MONTH = "[" + String.join(",",
        planned("e7f8a9b0-c1d2-4e3f-9a4b-5c6d7e8f9a02", "2026-11-06", "Lunch", 1, "recipe", JOTA, null, "Jota", "/recipes/" + JOTA + "/prvi.jpg"),
        planned("e7f8a9b0-c1d2-4e3f-9a4b-5c6d7e8f9a03", "2026-11-06", "Lunch", 0, "note", null, "Solata z vrta", null, null),
        planned("e7f8a9b0-c1d2-4e3f-9a4b-5c6d7e8f9a06", "2026-11-10", "Snack", 0, "recipe", RICET, null, "Ričet", null),
        planned("e7f8a9b0-c1d2-4e3f-9a4b-5c6d7e8f9a07", "2026-11-30", "Dinner", 0, "recipe", JOTA, null, "Jota", null),
        planned("e7f8a9b0-c1d2-4e3f-9a4b-5c6d7e8f9a08", "2026-11-07", "Brunch", 0, "recipe", RICET, null, "Ričet", null),
        planned("e7f8a9b0-c1d2-4e3f-9a4b-5c6d7e8f9a09", "2026-11-07", "Dinner", 0, "recipe", null, null, null, null)) + "]";

    static final String UNAUTHORIZED = json("{'message':'You must be logged in to access this resource','code':'UNAUTHORIZED','data':{'code':'UNAUTHORIZED','httpStatus':401,'path':'recipeSearch'}}");
    static final String NO_ROUTE = json("{'message':'Not found','code':'NOT_FOUND','data':{'code':'NOT_FOUND','httpStatus':404}}");

    public static void main(String[] args) throws Exception {
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/", NorishTest::answer);
        http.start();
        server = "http://127.0.0.1:" + http.getAddress().getPort();
        try {
            addresses();
            modern();
            pictures();
            older();
            failures();
            waiting();
            words();
        } finally {
            http.stop(0);
        }
        assert wrong.isEmpty() : wrong;
        System.out.println("NorishTest passed");
    }

    static void addresses() {
        assert "http://h:3000/norish".equals(NorishSource.normalize(" http://h:3000/norish/api/v1/ "));
        assert "http://h:3000".equals(NorishSource.normalize("http://h:3000/api/"));
        assert "http://h".equals(NorishSource.normalize("http://h/API/V1"));
        assert "http://api".equals(NorishSource.normalize("http://api/"));
        assert "".equals(NorishSource.normalize(null));
        Map<String, Object> values = new HashMap<>();
        values.put("recipeApp", "Norish");
        values.put("serverUrl", "https://home.example/norish/api/v1/");
        values.put("apiToken", " " + KEY + "\n");
        values.put("markCooked", "Always");
        Settings norish = new Settings(values);
        assert norish.app == Settings.App.NORISH && "https://home.example/norish".equals(norish.serverUrl) && KEY.equals(norish.apiToken);
        // Norish keeps no cook log.
        assert norish.markCooked == Settings.MarkCooked.NEVER && norish.problem() == null;
        values.put("apiToken", "");
        assert new Settings(values).problem().contains("Settings > User > API keys");
        values.put("serverUrl", "home.example");
        assert new Settings(values).problem().startsWith("The Norish address");
        // The same address in another app is another server.
        values.put("serverUrl", "https://home.example/norish");
        values.put("apiToken", KEY);
        Settings again = new Settings(values);
        values.put("recipeApp", "Tandoor");
        Settings tandoor = new Settings(values);
        assert again.sameServer(norish) && !tandoor.sameServer(again) && tandoor.otherServer(again) && !again.otherServer(norish);
        assert tandoor.markCooked == Settings.MarkCooked.ALWAYS && tandoor.app == Settings.App.TANDOOR;
        assert RecipeSource.of(norish) instanceof NorishSource && RecipeSource.of(tandoor) instanceof TandoorSource;
    }

    static void modern() throws Exception {
        NorishSource norish = new NorishSource(server + "/norish/api/v1/ ", " " + KEY + " ", new Http());
        assert "Connected to Norish, 30 recipes.".equals(norish.probe());
        assert requests.contains("GET /norish/api/v1/health/ready - ") : requests;
        assert last().equals("POST /norish/api/v1/recipes/search key {\"cursor\":0,\"limit\":1,\"sortMode\":\"titleAsc\"}") : last();

        // This week and this month together, each meal on its own date, notes too, and the order of each slot.
        List<RecipeSource.Planned> plan = norish.mealPlan(TODAY, TODAY.plusDays(6));
        assert requests.contains("GET /norish/api/v1/planned-recipes/week?include=notes key ") && requests.contains("GET /norish/api/v1/planned-recipes/month?include=notes key ") : requests;
        List<String> titles = new ArrayList<>();
        for (RecipeSource.Planned meal : plan) titles.add(meal.date.getDayOfMonth() + " " + meal.slot + " " + meal.title);
        assert titles.equals(Arrays.asList("6 Breakfast Domači kruh", "6 Lunch Solata z vrta", "6 Lunch Jota", "7 Brunch Ričet", "8 Dinner Ričet", "10 Snack Ričet")) : titles;
        RecipeSource.Planned bread = plan.get(0);
        assert KRUH.equals(bread.recipeId) && "https://cdn.example/kruh.jpg".equals(bread.image) && bread.servings == 0 && bread.minutes == 0 && bread.color == 0;
        RecipeSource.Planned salad = plan.get(1);
        assert salad.recipeId == null && "Solata z vrta".equals(salad.note) && "".equals(salad.image) && salad.slotOrder == plan.get(2).slotOrder;
        RecipeSource.Planned jota = plan.get(2);
        assert JOTA.equals(jota.recipeId) && "".equals(jota.note) && (server + "/norish/recipes/" + JOTA + "/prvi.jpg").equals(jota.image) : jota.image;
        assert bread.slotOrder < salad.slotOrder && plan.get(3).slotOrder > plan.get(5).slotOrder;
        // A note that names a recipe cooks the recipe and keeps its words.
        RecipeSource.Planned leftovers = plan.get(4);
        assert RICET.equals(leftovers.recipeId) && "Ostanki od sobote".equals(leftovers.note);

        // The list: an offset for each page of 24, the newest first for recent, and the time each takes.
        RecipeSource.Page page = norish.recipes("jota ", RecipeSource.Order.NAME, 1);
        assert last().equals("POST /norish/api/v1/recipes/search key {\"cursor\":0,\"limit\":24,\"search\":\"jota\",\"sortMode\":\"titleAsc\"}") : last();
        assert page.items.size() == 1 && !page.more && page.total == 1 && page.items.get(0).minutes == 150;
        assert (server + "/norish/recipes/" + JOTA + "/prvi.jpg").equals(page.items.get(0).image);
        RecipeSource.Page first = norish.recipes("", RecipeSource.Order.NAME, 1);
        assert first.items.size() == 24 && first.more && first.total == 30;
        assert "Domači kruh".equals(first.items.get(0).name) && first.items.get(0).minutes == 0 && "Jota".equals(first.items.get(1).name);
        RecipeSource.Page newest = norish.recipes("", RecipeSource.Order.RECENT, 2);
        assert last().equals("POST /norish/api/v1/recipes/search key {\"cursor\":24,\"limit\":24,\"sortMode\":\"dateDesc\"}") : last();
        assert newest.items.size() == 6 && !newest.more && newest.total == 30;
        RecipeSource.Summary ricet = null;
        for (RecipeSource.Summary item : norish.recipes("ričet", RecipeSource.Order.NAME, 1).items) ricet = item;
        assert ricet != null && ricet.minutes == 75 && "".equals(ricet.image);

        // A recipe: its own list in the recipe's system, the steps under their headings, and what each step uses.
        Recipe recipe = norish.recipe(JOTA);
        assert last().equals("GET /norish/api/v1/recipes/" + JOTA + " key ") : last();
        assert "Jota".equals(recipe.name) && recipe.servings == 6 && recipe.workingMinutes == 110 && recipe.waitingMinutes == 40 && recipe.totalMinutes() == 150;
        assert (server + "/norish/recipes/" + JOTA + "/prvi.jpg").equals(recipe.image) && (server + "/norish/recipes/" + JOTA).equals(recipe.webUrl);
        assert "https://example.org/jota".equals(recipe.sourceUrl) && JOTA.equals(recipe.id);
        List<Recipe.Ingredient> list = recipe.ingredients();
        List<String> english = new ArrayList<>();
        List<String> slovenian = new ArrayList<>();
        for (Recipe.Ingredient ingredient : list) {
            english.add(ingredient.line(1, false));
            slovenian.add(ingredient.line(1, true));
        }
        assert english.equals(Arrays.asList("Enolončnica", "500 g kislo zelje, oprano", "2 tbsp mast ali olje", "⅓ tsp mleta kumina", "sol, to taste", "3 cloves česen",
            "1 žlička sladkor", "voda")) : english;
        assert slovenian.equals(Arrays.asList("Enolončnica", "500 g kislo zelje, oprano", "2 žlici mast ali olje", "⅓ žličke mleta kumina", "sol, po okusu", "3 stroki česen",
            "1 žlička sladkor", "voda")) : slovenian;
        assert list.get(0).header && !list.get(4).hasAmount() && "po okusu".equals(list.get(4).measure(1, true)) && "".equals(list.get(7).measure(1, true));
        // Rescaled, Slovenian counts again.
        assert "1 žlica".equals(list.get(2).measure(0.5, true)) && "1½ stroka".equals(list.get(5).measure(0.5, true)) && "0,17 žličke".equals(list.get(3).measure(0.5, true));
        assert "5 žlic".equals(list.get(2).measure(2.5, true)) && "1 strok".equals(list.get(5).measure(1.0 / 3, true)) && "½ tbsp".equals(list.get(2).measure(0.25, false));
        assert recipe.steps.size() == 4 : recipe.steps.size();
        Recipe.Step boil = recipe.steps.get(0);
        assert "Priprava".equals(boil.title) && boil.format == Recipe.Format.MARKDOWN && boil.seconds == 0;
        assert "Zelje in fižol skuhaj posebej, približno 60 minut.".equals(Instructions.render(boil, 1, false));
        // All of a line is the line itself, so ticking it in the step ticks it in the list.
        assert boil.ingredients.size() == 1 && boil.ingredients.get(0) == list.get(1);
        Recipe.Step fry = recipe.steps.get(1);
        assert "Priprava".equals(fry.title) && "Na polovici masti prepraži kumino, vse združi in soli po okusu. Zraven ponudi Domači kruh.".equals(Instructions.render(fry, 1, false));
        assert fry.ingredients.size() == 2 && "1 tbsp mast ali olje".equals(fry.ingredients.get(0).line(1, false)) && fry.ingredients.get(1) == list.get(3);
        assert "1 žlica mast ali olje".equals(fry.ingredients.get(0).line(1, true)) && "½ žlice mast ali olje".equals(fry.ingredients.get(0).line(0.5, true));
        Recipe.Step sauce = recipe.steps.get(2);
        assert "Omaka".equals(sauce.title) && "Preostalo mast zmešaj z moko.".equals(Instructions.render(sauce, 1, false)) && sauce.ingredients.size() == 1;
        assert "Omaka".equals(recipe.steps.get(3).title) && recipe.steps.get(3).ingredients.isEmpty();

        // Nothing to record, and nothing sent for it.
        int before = requests.size();
        norish.viewed(JOTA);
        try {
            norish.cooked(JOTA, 4);
            assert false : "Norish keeps no cook log";
        } catch (RecipeSource.SourceException refused) {
            assert refused.kind == RecipeSource.SourceException.Kind.UNSUPPORTED;
        }
        assert requests.size() == before;

        // An id that is not Norish's never reaches a path, and one that is gone says so.
        assert kind(() -> norish.recipe("../../admin")) == RecipeSource.SourceException.Kind.NOT_FOUND && requests.size() == before;
        assert kind(() -> norish.recipe(MISSING)) == RecipeSource.SourceException.Kind.NOT_FOUND;
    }

    static void pictures() throws Exception {
        NorishSource norish = new NorishSource(server + "/norish", KEY, new Http());
        String picture = server + "/norish/recipes/" + JOTA + "/prvi.jpg";
        assert Collections.singletonMap("x-api-key", KEY).equals(norish.imageHeaders(picture));
        // Never to another host, scheme or port, outside the base path, or with a user name in front.
        String port = server.substring(server.lastIndexOf(':') + 1);
        for (String other : new String[] {"https://cdn.example/kruh.jpg", "http://localhost:" + port + "/norish/recipes/x.jpg", "https://127.0.0.1:" + port + "/norish/recipes/x.jpg",
            "http://127.0.0.1:1/norish/recipes/x.jpg", server + "/other/recipes/x.jpg", server + "/norishx/recipes/x.jpg", "http://user@127.0.0.1:" + port + "/norish/recipes/x.jpg", "nonsense"}) {
            assert norish.imageHeaders(other).isEmpty() : other;
        }
        Http http = new Http();
        Http.Response image = http.request("GET", picture, norish.imageHeaders(picture), null, 1024);
        assert image.ok() && "JPEG".equals(image.text());
        // Without the key Norish sends the picture's request to its login page, and the plugin does not follow it there.
        try {
            http.request("GET", picture, Collections.<String, String>emptyMap(), null, 1024);
            assert false : "A picture without the key goes to the login page";
        } catch (Http.Refused refused) {
            assert refused.getMessage().contains("login.invalid") : refused.getMessage();
        }
        // No key to the server at all without one.
        assert new NorishSource(server + "/norish", "", new Http()).imageHeaders(picture).isEmpty();
    }

    static void older() throws Exception {
        // 0.25 has one health route that says its version, and its plan has no notes and no types.
        NorishSource old = new NorishSource(server + "/old", KEY, new Http());
        assert "Connected to Norish 0.25.1-beta, 1 recipe.".equals(old.probe());
        List<RecipeSource.Planned> plan = old.mealPlan(TODAY, TODAY);
        assert plan.size() == 1 && JOTA.equals(plan.get(0).recipeId) && "Jota".equals(plan.get(0).title) && "Dinner".equals(plan.get(0).slot);
        // Its recipes still carry one picture of their own.
        Recipe recipe = old.recipe(JOTA);
        assert (server + "/old/recipes/" + JOTA + "/staro.jpg").equals(recipe.image) && recipe.steps.size() == 1 && recipe.ingredients().size() == 1;
        assert "2 cups moka".equals(recipe.ingredients().get(0).line(1, false)) && recipe.workingMinutes == 45 && recipe.waitingMinutes == 0;
    }

    static void failures() throws Exception {
        // A key that is not whole says what may be missing. A whole one that is wrong does not.
        RecipeSource.SourceException cut = failure(() -> new NorishSource(server + "/norish", "aBcDe", new Http()).probe());
        assert cut.kind == RecipeSource.SourceException.Kind.AUTH && cut.getMessage().contains("This one has 5 characters") : cut.getMessage();
        RecipeSource.SourceException wrongKey = failure(() -> new NorishSource(server + "/norish", KEY.toLowerCase(Locale.ROOT), new Http()).recipe(JOTA));
        assert wrongKey.kind == RecipeSource.SourceException.Kind.AUTH && !wrongKey.getMessage().contains("characters") && wrongKey.getMessage().contains("Settings > User > API keys");
        assert failure(() -> new NorishSource(server + "/norish", "", new Http()).probe()).kind == RecipeSource.SourceException.Kind.AUTH;
        assert failure(() -> new NorishSource("", KEY, new Http()).probe()).kind == RecipeSource.SourceException.Kind.HOST;

        // A Norish that cannot reach its database refuses every key. Its health route says it is that.
        RecipeSource.SourceException down = failure(() -> new NorishSource(server + "/down", KEY, new Http()).recipe(JOTA));
        assert down.kind == RecipeSource.SourceException.Kind.SERVER && down.getMessage().startsWith("Norish is not ready: Database is error.") : down.getMessage();
        RecipeSource.SourceException downProbe = failure(() -> new NorishSource(server + "/down", KEY, new Http()).probe());
        assert downProbe.kind == RecipeSource.SourceException.Kind.SERVER && downProbe.getMessage().equals("Norish is not ready: Database is error.") : downProbe.getMessage();

        // A server error says Norish's own words.
        RecipeSource.SourceException broken = failure(() -> new NorishSource(server + "/broken", KEY, new Http()).recipe(JOTA));
        assert broken.kind == RecipeSource.SourceException.Kind.SERVER && broken.getMessage().contains("Failed to parse FullRecipeDTO") : broken.getMessage();

        // What is not Norish: a proxy's own 404, a web page, and another app's JSON.
        NorishSource proxy = new NorishSource(server + "/proxy", KEY, new Http());
        RecipeSource.SourceException noBase = failure(proxy::probe);
        assert noBase.kind == RecipeSource.SourceException.Kind.NOT_API && noBase.getMessage().contains("base path") : noBase.getMessage();
        // A recipe the proxy has no answer for says nothing about the recipe.
        assert kind(() -> proxy.recipe(JOTA)) == RecipeSource.SourceException.Kind.NETWORK;
        assert kind(() -> new NorishSource(server + "/web", KEY, new Http()).probe()) == RecipeSource.SourceException.Kind.NOT_API;
        assert kind(() -> new NorishSource(server + "/web", KEY, new Http()).mealPlan(TODAY, TODAY)) == RecipeSource.SourceException.Kind.NOT_API;
        RecipeSource.SourceException other = failure(() -> new NorishSource(server + "/other", KEY, new Http()).probe());
        assert other.kind == RecipeSource.SourceException.Kind.NOT_API && other.getMessage().contains("/api/v1/recipes/search") : other.getMessage();
        // Nowhere at all.
        assert kind(() -> new NorishSource("http://127.0.0.1:1", KEY, new Http()).probe()) == RecipeSource.SourceException.Kind.NETWORK;
    }

    static void waiting() throws Exception {
        // Past its 500 requests, Norish says how long to wait. Until then the key goes nowhere.
        long[] now = {5_000};
        NorishSource busy = new NorishSource(server + "/busy", KEY, new Http(), () -> now[0]);
        RecipeSource.SourceException limited = failure(() -> busy.recipes("", RecipeSource.Order.NAME, 1));
        assert limited.kind == RecipeSource.SourceException.Kind.LIMIT && limited.getMessage().startsWith("Norish asks to wait 31 minutes") : limited.getMessage();
        int sent = requests.size();
        now[0] += 1_000_000;
        RecipeSource.SourceException still = failure(() -> busy.mealPlan(TODAY, TODAY));
        assert still.kind == RecipeSource.SourceException.Kind.LIMIT && still.getMessage().startsWith("Norish asks to wait 14 minutes") && requests.size() == sent : still.getMessage();
        assert kind(() -> busy.recipe(JOTA)) == RecipeSource.SourceException.Kind.LIMIT && requests.size() == sent;
        // The health route needs no key, so Test connection still finds Norish there.
        assert kind(busy::probe) == RecipeSource.SourceException.Kind.LIMIT && requests.size() == sent + 1;
        now[0] += 834_000;
        assert kind(() -> busy.recipe(JOTA)) == RecipeSource.SourceException.Kind.LIMIT && requests.size() == sent + 2 : requests.size() - sent;
        assert last().startsWith("GET /busy/api/v1/recipes/" + JOTA + " key");
    }

    static void words() {
        // The views name the app the settings chose.
        Strings english = Strings.of("English", Settings.App.NORISH);
        Strings slovenian = Strings.of("Slovenščina", Settings.App.NORISH);
        assert "Open in Norish".equals(english.openInApp) && "Odpri v Norishu".equals(slovenian.openInApp);
        assert "Norish refused the API key. Create a new one under Settings > User > API keys.".equals(english.problemAuth);
        assert "Norish je zavrnil API ključ. Ustvari novega v Norishu pod Settings > User > API keys.".equals(slovenian.problemAuth);
        assert "Ta naslov ne vodi do Norishevega API-ja. Morda je pred njim prijavna stran.".equals(slovenian.problemNotApi);
        assert "Newest".equals(english.recent) && "Najnovejši".equals(slovenian.recent) && "Set the server address and the API key in the plugin settings.".equals(english.notConfigured);
        assert "Kosilo".equals(slovenian.slot("Lunch")) && "Lunch".equals(english.slot("Lunch")) && "Brunch".equals(slovenian.slot("Brunch"));
        assert english.problemLimit.contains("500 requests") && slovenian.problemLimit.contains("500 zahtev") && !slovenian.problemLimit.contains("{");
        // Tandoor's stay as they were.
        assert Strings.of("English", Settings.App.TANDOOR) == Strings.ENGLISH && Strings.of("Slovenščina", Settings.App.TANDOOR) == Strings.SLOVENIAN;
        assert "Open in Tandoor".equals(Strings.ENGLISH.openInApp) && "Recently opened".equals(Strings.ENGLISH.recent) && "Lunch".equals(Strings.SLOVENIAN.slot("Lunch"));
        assert "Tandoor je zavrnil API žeton. Ustvari novega v Nastavitvah > API.".equals(Strings.SLOVENIAN.problemAuth);
        assert "Ta recept je zaseben in ni deljen z uporabnikom tega žetona.".equals(Strings.SLOVENIAN.problemPrivate);
        assert "Zabeleži v Tandoorju kot skuhano".equals(Strings.SLOVENIAN.logCooked) && "Record in Tandoor as cooked".equals(Strings.ENGLISH.logCooked);
        for (Strings strings : new Strings[] {english, slovenian, Strings.ENGLISH, Strings.SLOVENIAN}) {
            for (String text : new String[] {strings.openInApp, strings.problemAuth, strings.problemHost, strings.problemReadOnly, strings.problemNotApi, strings.problemPrivate,
                strings.problemLimit, strings.notConfigured, strings.recent, strings.logCooked, strings.logged}) {
                assert !text.contains("{") && !text.contains("}") : text;
            }
        }

        // Slovenian counts.
        String[] shown = {"1", "2", "3", "4", "5", "11", "12", "101", "102", "104", "111", "½", "1½", "2,5", "0,17", ""};
        int[] forms = {0, 1, 2, 2, 3, 3, 3, 0, 1, 2, 3, 4, 4, 4, 4, 4};
        for (int i = 0; i < shown.length; i++) assert Units.slovenianForm(shown[i]) == forms[i] : shown[i];
        assert Units.count() == 68;
        assert "tablespoon".equals(Units.key(" Tablespoon ")) && "".equals(Units.key("žlica")) && "".equals(Units.key(null));
        assert "3 žlice".equals(new Recipe.Ingredient(false, 3, "tbsp", "", "moka", "", "", "moka", "tablespoon").measure(1, true));
        assert "1 cup".equals(new Recipe.Ingredient(false, 1, "cup", "", "moka", "", "", "moka", "cup").measure(1, false));
        assert "2 cups".equals(new Recipe.Ingredient(false, 1, "cup", "", "moka", "", "", "moka", "cup").measure(2, false));
        assert "¾ cup".equals(new Recipe.Ingredient(false, 0.75, "cup", "", "moka", "", "", "moka", "cup").measure(1, false));
    }

    // The server.

    static void answer(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getRawPath();
        String query = exchange.getRequestURI().getRawQuery();
        String key = exchange.getRequestHeaders().getFirst("x-api-key");
        String type = exchange.getRequestHeaders().getFirst("Content-Type");
        String body = read(exchange.getRequestBody());
        requests.add(method + " " + path + (query == null ? "" : "?" + query) + " " + (key == null ? "-" : KEY.equals(key) ? "key" : "other") + " " + body);
        if (exchange.getRequestHeaders().getFirst("Authorization") != null) wrong.add("Authorization header to " + path);
        if (path.endsWith("/") && path.length() > 1) wrong.add("Trailing slash " + path);
        if (path.contains("/health") && key != null) wrong.add("A key to a health route " + path);
        if ("POST".equals(method) && (type == null || !type.startsWith("application/json"))) {
            send(exchange, 415, json("{'message':'Unsupported content-type','code':'UNSUPPORTED_MEDIA_TYPE'}"), null);
            return;
        }
        int slash = path.indexOf('/', 1);
        String prefix = slash < 0 ? path : path.substring(0, slash);
        String rest = slash < 0 ? "" : path.substring(slash);
        boolean keyed = key != null && key.length() >= NorishSource.KEY_LENGTH;
        switch (prefix) {
            case "/norish": norish(exchange, method, rest, query, key, body); return;
            case "/old": old(exchange, rest, keyed); return;
            case "/busy":
                if (rest.startsWith("/api/v1/health")) send(exchange, 200, json("{'status':'ok'}"), null);
                else send(exchange, 429, json("{'message':'Rate limit exceeded.','code':'TOO_MANY_REQUESTS','data':{'code':'TOO_MANY_REQUESTS','httpStatus':429,'path':'recipeGet'}}"), "1834");
                return;
            case "/down":
                if (rest.equals("/api/v1/health/ready")) send(exchange, 503, json("{'message':'Database is error','code':'SERVICE_UNAVAILABLE','data':{'code':'SERVICE_UNAVAILABLE','httpStatus':503,'path':'healthReady'}}"), null);
                else send(exchange, 401, UNAUTHORIZED, null);
                return;
            case "/broken":
                if (rest.equals("/api/v1/health/ready")) send(exchange, 200, json("{'status':'ok'}"), null);
                else send(exchange, 500, json("{'message':'Failed to parse FullRecipeDTO','code':'INTERNAL_SERVER_ERROR','data':{'code':'INTERNAL_SERVER_ERROR','httpStatus':500,'path':'recipeGet'}}"), null);
                return;
            case "/web":
                send(exchange, 200, "<!DOCTYPE html><html><body>Norish</body></html>", null);
                return;
            case "/other":
                send(exchange, 404, json("{'detail':'Not Found'}"), null);
                return;
            default:
                send(exchange, 404, "404 page not found", null);
        }
    }

    static void norish(HttpExchange exchange, String method, String rest, String query, String key, String body) throws IOException {
        boolean keyed = KEY.equals(key);
        if (rest.startsWith("/recipes/")) {
            // Pictures: to the login page without a key, not found with a wrong one.
            if (key == null) {
                exchange.getResponseHeaders().add("Location", "http://login.invalid/norish/login?callbackUrl=" + rest);
                send(exchange, 307, "", null);
            } else if (!keyed) {
                send(exchange, 404, json("{'error':'Not found'}"), null);
            } else {
                send(exchange, 200, "JPEG", null);
            }
            return;
        }
        if (rest.equals("/api/v1/health/ready") && "GET".equals(method)) {
            send(exchange, 200, json("{'status':'ok','db':{'status':'ok'},'valkey':{'status':'ok'},'parser':{'status':'ok','recipeScrapersVersion':'15.13.0'}}"), null);
            return;
        }
        boolean search = rest.equals("/api/v1/recipes/search") && "POST".equals(method);
        boolean recipe = rest.startsWith("/api/v1/recipes/") && !search && "GET".equals(method);
        boolean week = rest.equals("/api/v1/planned-recipes/week") && "GET".equals(method);
        boolean month = rest.equals("/api/v1/planned-recipes/month") && "GET".equals(method);
        if (!search && !recipe && !week && !month) {
            send(exchange, 404, rest.startsWith("/api/v1/") ? NO_ROUTE : "<html>Not found</html>", null);
            return;
        }
        if (!keyed) {
            send(exchange, 401, UNAUTHORIZED, null);
            return;
        }
        if (week || month) {
            if (!"include=notes".equals(query)) wrong.add("Plan without notes: " + query);
            send(exchange, 200, week ? WEEK : MONTH, null);
            return;
        }
        if (recipe) {
            String id = rest.substring("/api/v1/recipes/".length());
            if (id.equals(JOTA)) send(exchange, 200, RECIPE, null);
            else send(exchange, 404, json("{'message':'Recipe not found','code':'NOT_FOUND','data':{'code':'NOT_FOUND','httpStatus':404,'path':'recipeGet'}}"), null);
            return;
        }
        Map<String, Object> asked;
        try {
            asked = Json.object(Json.parse(body));
        } catch (IllegalArgumentException unreadable) {
            send(exchange, 400, json("{'message':'Failed to parse request body','code':'PARSE_ERROR'}"), null);
            return;
        }
        for (String field : asked.keySet()) {
            if (!Arrays.asList("cursor", "limit", "search", "searchFields", "tags", "categories", "filterMode", "sortMode", "minRating", "maxCookingTime").contains(field)) wrong.add("Search field " + field);
        }
        int cursor = (int) Json.integer(asked.get("cursor"), 0);
        int limit = (int) Json.integer(asked.get("limit"), 50);
        String words = Json.text(asked.get("search")).trim().toLowerCase(Locale.ROOT);
        String sort = asked.containsKey("sortMode") ? Json.text(asked.get("sortMode")) : "dateDesc";
        List<Map<String, Object>> found = new ArrayList<>();
        for (Map<String, Object> item : LISTED) if (words.isEmpty() || Json.text(item.get("name")).toLowerCase(Locale.ROOT).startsWith(words)) found.add(item);
        if ("titleAsc".equals(sort)) found.sort((a, b) -> Json.text(a.get("name")).compareTo(Json.text(b.get("name"))));
        else if ("dateDesc".equals(sort)) found.sort((a, b) -> Json.text(b.get("createdAt")).compareTo(Json.text(a.get("createdAt"))));
        else wrong.add("Sort " + sort);
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("recipes", new ArrayList<Object>(found.subList(Math.min(cursor, found.size()), Math.min(cursor + limit, found.size()))));
        answer.put("total", found.size());
        answer.put("nextCursor", cursor + limit < found.size() ? (Object) (cursor + limit) : null);
        send(exchange, 200, Json.write(answer), null);
    }

    static void old(HttpExchange exchange, String rest, boolean keyed) throws IOException {
        if (rest.equals("/api/v1/health")) {
            send(exchange, 200, json("{'status':'ok','versions':{'root':'0.25.1-beta','apps':{}}}"), null);
        } else if (!rest.startsWith("/api/v1/recipes") && !rest.startsWith("/api/v1/planned-recipes")) {
            send(exchange, 404, NO_ROUTE, null);
        } else if (!keyed) {
            send(exchange, 401, UNAUTHORIZED, null);
        } else if (rest.equals("/api/v1/recipes/search")) {
            send(exchange, 200, json("{'recipes':[{'id':'" + JOTA + "','name':'Jota','totalMinutes':150,'image':null}],'total':1,'nextCursor':null}"), null);
        } else if (rest.equals("/api/v1/planned-recipes/week")) {
            send(exchange, 200, "[" + planned("f7f8a9b0-c1d2-4e3f-9a4b-5c6d7e8f9a01", "2026-11-06", "Dinner", 0, null, JOTA, null, "Jota", null) + "]", null);
        } else if (rest.equals("/api/v1/planned-recipes/month")) {
            send(exchange, 200, "[]", null);
        } else {
            send(exchange, 200, json("{'id':'" + JOTA + "','name':'Jota','servings':4,'prepMinutes':45,'cookMinutes':null,'totalMinutes':null,'image':'/recipes/" + JOTA + "/staro.jpg',"
                + "'recipeIngredients':[{'amount':2,'unit':'cup','order':0,'systemUsed':'metric','ingredientName':'moka'}],"
                + "'steps':[{'step':'Zmešaj.','systemUsed':'metric','order':0,'images':[]}],'images':[]}"), null);
        }
    }

    static void send(HttpExchange exchange, int status, String text, String retryAfter) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        boolean isJson = text.startsWith("{") || text.startsWith("[");
        exchange.getResponseHeaders().add("Content-Type", isJson ? "application/json" : "text/html");
        if (retryAfter != null) exchange.getResponseHeaders().add("Retry-After", retryAfter);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    static String last() {
        return requests.get(requests.size() - 1);
    }

    interface Call {
        void run() throws Exception;
    }

    static RecipeSource.SourceException failure(Call call) throws Exception {
        try {
            call.run();
        } catch (RecipeSource.SourceException failed) {
            return failed;
        }
        throw new AssertionError("The call was expected to fail");
    }

    static RecipeSource.SourceException.Kind kind(Call call) throws Exception {
        return failure(call).kind;
    }
}
