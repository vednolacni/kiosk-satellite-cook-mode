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
 * TandoorSource against a local server that answers the way Tandoor 2.x (/v2) and 1.5.x (/v15) do, plus a server that
 * refuses the host (/bad), one that refuses the token (/denied) and one that answers with its web app (/web).
 */
public final class TandoorTest {
    static final LocalDate TODAY = LocalDate.of(2026, 10, 31);
    static final List<String> requests = Collections.synchronizedList(new ArrayList<String>());
    static final List<String> bodies = Collections.synchronizedList(new ArrayList<String>());
    static String base;

    static String entry(int id, String from, String to, String recipe, String title, String note, String type, String time, int order, String color, double servings) {
        return "{\"id\":" + id + ",\"title\":\"" + title + "\",\"recipe\":" + recipe + ",\"servings\":" + servings + ",\"note\":\"" + note + "\",\"note_markdown\":\"\","
            + "\"from_date\":\"" + from + "\",\"to_date\":\"" + to + "\",\"meal_type\":{\"id\":" + order + ",\"name\":\"" + type + "\",\"order\":" + order
            + ",\"time\":" + (time == null ? "null" : "\"" + time + "\"") + ",\"color\":" + (color == null ? "null" : "\"" + color + "\"") + ",\"created_by\":1},"
            + "\"created_by\":1,\"recipe_name\":\"\",\"meal_type_name\":\"" + type + "\",\"shopping\":false}";
    }

    static String overview(int id, String name, String image) {
        return "{\"id\":" + id + ",\"name\":\"" + name + "\",\"description\":\"\",\"image\":" + (image == null ? "null" : "\"" + image + "\"")
            + ",\"keywords\":[],\"working_time\":20,\"waiting_time\":10,\"servings\":4,\"servings_text\":\"\",\"rating\":null,\"last_cooked\":null,\"new\":false}";
    }

    static final String RECIPE = "{\"id\":7,\"name\":\"Golaž\",\"description\":\"Hearty\",\"image\":\"/media/recipes/a_7.jpg\",\"servings\":4,\"servings_text\":\"\","
        + "\"working_time\":20,\"waiting_time\":90,\"source_url\":null,\"steps\":["
        + "{\"id\":1,\"name\":\"\",\"instruction\":\"Brown {{ ingredients[1] }}\",\"instructions_markdown\":\"<p>Brown <scalable-number v-bind:number=\\\"500.0\\\" v-bind:factor=\\\"ingredient_factor\\\"></scalable-number> g beef and "
        + "<plural-name singular=\\\"onion\\\" plural=\\\"onions\\\" v-bind:amount=\\\"2.0\\\" v-bind:factor=\\\"ingredient_factor\\\" :no-amount=\\\"false\\\"></plural-name> for 10 min.<br />\\nThen stir &amp; season.</p>\","
        + "\"time\":10,\"order\":0,\"show_as_header\":false,\"step_recipe\":null,\"step_recipe_data\":null,\"ingredients\":["
        + "{\"id\":11,\"food\":{\"id\":1,\"name\":\"onion\",\"plural_name\":\"onions\"},\"unit\":null,\"amount\":2.0,\"note\":\"\",\"is_header\":false,\"no_amount\":false,\"original_text\":\"2 onions\"},"
        + "{\"id\":12,\"food\":{\"id\":2,\"name\":\"beef\",\"plural_name\":null},\"unit\":{\"id\":1,\"name\":\"g\",\"plural_name\":null},\"amount\":500.0,\"note\":\"cubed\",\"is_header\":false,\"no_amount\":false,\"original_text\":\"500 g beef\"},"
        + "{\"id\":13,\"food\":null,\"unit\":null,\"amount\":0,\"note\":\"Spices\",\"is_header\":true,\"no_amount\":false,\"original_text\":\"\"},"
        + "{\"id\":14,\"food\":{\"id\":3,\"name\":\"salt\",\"plural_name\":null},\"unit\":null,\"amount\":0,\"note\":\"to taste\",\"is_header\":false,\"no_amount\":true,\"original_text\":\"salt\"}]},"
        + "{\"id\":2,\"name\":\"Sauce\",\"instruction\":\"Mix {{ ingredients[0] }} well\",\"instructions_markdown\":\"Could not parse template code. Error: Template Syntax broken\","
        + "\"time\":0,\"ingredients\":[],\"step_recipe_data\":{\"id\":9,\"name\":\"Roux\",\"steps\":[{\"id\":3,\"name\":\"\",\"instruction\":\"Melt\","
        + "\"instructions_markdown\":\"<p>Melt <scalable-number v-bind:number='50.0000000000000000' v-bind:factor='ingredient_factor'></scalable-number> g butter</p>\",\"time\":5,\"ingredients\":[]}]}}]}";

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", TandoorTest::answer);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            modern();
            legacy();
            failures();
            instructions();
        } finally {
            server.stop(0);
        }
        System.out.println("TandoorTest passed");
    }

    static void modern() throws Exception {
        TandoorSource tandoor = new TandoorSource(base + "/v2/api/ ", "tda_secret", new Http());
        assert "Connected to Tandoor 2.7.2, 140 recipes.".equals(tandoor.probe());
        String last = requests.get(requests.size() - 1);
        assert last.startsWith("GET /v2/api/recipe/?page_size=1 Bearer tda_secret application/json") : last;

        // The meal plan: a wider window, two pages with a next link naming another host, a note without a recipe and
        // a three-day entry that shows on each day asked for.
        List<RecipeSource.Planned> plan = tandoor.mealPlan(TODAY, TODAY.plusDays(6));
        assert requests.contains("GET /v2/api/meal-plan/?from_date=2026-10-30&to_date=2026-11-20&page_size=200 Bearer tda_secret application/json") : requests;
        assert requests.contains("GET /v2/api/meal-plan/?page=2 Bearer tda_secret application/json") : requests;
        assert plan.size() == 4 : plan.size();
        RecipeSource.Planned coffee = plan.get(0);
        assert coffee.date.equals(TODAY) && "Zajtrk".equals(coffee.slot) && coffee.recipeId == null && "Kava".equals(coffee.title) && coffee.slotOrder == 8 * 60;
        RecipeSource.Planned lunch = plan.get(1);
        assert "7".equals(lunch.recipeId) && "Golaž".equals(lunch.title) && lunch.servings == 2.5 && lunch.minutes == 30 && lunch.color == 0xFFFF9800;
        assert (base + "/media/recipes/a_7.jpg").equals(lunch.image) : lunch.image;
        RecipeSource.Planned soup = plan.get(2);
        assert "Juha".equals(soup.title) && soup.date.equals(TODAY) && soup.slotOrder == 24 * 60 + 3;
        assert plan.get(3).date.equals(TODAY.plusDays(1)) && "Juha".equals(plan.get(3).title);

        // Search and recent lists.
        RecipeSource.Page page = tandoor.recipes("golaž ", RecipeSource.Order.NAME, 1);
        assert requests.get(requests.size() - 1).startsWith("GET /v2/api/recipe/?page=1&page_size=24&query=gola%C5%BE ") : requests.get(requests.size() - 1);
        assert page.items.size() == 2 && page.more && page.total == 30 && "Golaž".equals(page.items.get(0).name) && page.items.get(0).minutes == 30;
        assert "".equals(page.items.get(1).image);
        tandoor.recipes("", RecipeSource.Order.RECENT, 2);
        assert requests.get(requests.size() - 1).startsWith("GET /v2/api/recipe/?page=2&page_size=24&sort_order=-lastviewed ");

        // A recipe: steps, a broken template that falls back to the raw instruction, a sub-recipe and the ingredients.
        Recipe recipe = tandoor.recipe("7");
        assert "Golaž".equals(recipe.name) && recipe.servings == 4 && recipe.totalMinutes() == 110;
        assert (base + "/media/recipes/a_7.jpg").equals(recipe.image) && (base + "/v2/view/recipe/7").equals(recipe.webUrl);
        assert recipe.steps.size() == 3;
        Recipe.Step brown = recipe.steps.get(0);
        assert brown.format == Recipe.Format.TANDOOR_HTML && brown.seconds == 600 && brown.ingredients.size() == 4;
        assert "Brown 250 g beef and onion for 10 min.\nThen stir & season.".equals(Instructions.render(brown, 0.5, false)) : Instructions.render(brown, 0.5, false);
        assert "Brown 500 g beef and onions for 10 min.\nThen stir & season.".equals(Instructions.render(brown, 1, false));
        Recipe.Step sauce = recipe.steps.get(1);
        assert "Sauce".equals(sauce.title) && sauce.format == Recipe.Format.MARKDOWN && "Mix well".equals(Instructions.render(sauce, 1, false)) : Instructions.render(sauce, 1, false);
        Recipe.Step roux = recipe.steps.get(2);
        assert "Roux".equals(roux.title) && roux.seconds == 300 && "Melt 25 g butter".equals(Instructions.render(roux, 0.5, false));
        List<Recipe.Ingredient> ingredients = recipe.ingredients();
        assert ingredients.size() == 4 : ingredients.size();
        assert "1 onion".equals(ingredients.get(0).line(0.5, false)) && "2 onions".equals(ingredients.get(0).line(1, false));
        assert "250 g beef, cubed".equals(ingredients.get(1).line(0.5, false));
        assert ingredients.get(2).header && "Spices".equals(ingredients.get(2).line(1, false));
        assert !ingredients.get(3).hasAmount() && "salt, to taste".equals(ingredients.get(3).line(2, false));

        // Telling Tandoor: a view log, then a cook log with the servings as they were cooked.
        tandoor.viewed("7");
        assert bodies.get(bodies.size() - 1).equals("POST /v2/api/view-log/ {\"recipe\":7}");
        tandoor.cooked("7", 2.5);
        assert bodies.get(bodies.size() - 1).equals("POST /v2/api/cook-log/ {\"recipe\":7,\"servings\":2.5}") : bodies.get(bodies.size() - 1);
        assert tandoor.imageHeaders(recipe.image).isEmpty();
        boolean refused = false;
        try {
            tandoor.recipe("../../admin");
        } catch (RecipeSource.SourceException expected) {
            refused = expected.kind == RecipeSource.SourceException.Kind.NOT_FOUND;
        }
        assert refused;
    }

    static void legacy() throws Exception {
        TandoorSource tandoor = new TandoorSource(base + "/v15", "old", new Http());
        assert "Connected to Tandoor, 3 recipes.".equals(tandoor.probe());
        List<RecipeSource.Planned> plan = tandoor.mealPlan(TODAY, TODAY);
        assert plan.size() == 1 && "Golaž".equals(plan.get(0).title) && plan.get(0).slotOrder == 12 * 60;
        // 1.5.x takes whole servings only.
        tandoor.cooked("7", 2.5);
        assert bodies.get(bodies.size() - 1).equals("POST /v15/api/cook-log/ {\"recipe\":7,\"servings\":3}") : bodies.get(bodies.size() - 1);
    }

    static void failures() throws Exception {
        assert kind(new TandoorSource(base + "/bad", "t", new Http())) == RecipeSource.SourceException.Kind.HOST;
        assert message(new TandoorSource(base + "/bad", "t", new Http())).contains("ALLOWED_HOSTS setting must include 127.0.0.1:");
        assert kind(new TandoorSource(base + "/denied", "t", new Http())) == RecipeSource.SourceException.Kind.AUTH;
        assert message(new TandoorSource(base + "/denied", "t", new Http())).contains("(Authentication credentials were not provided or access is forbidden.)");
        assert kind(new TandoorSource(base + "/web", "t", new Http())) == RecipeSource.SourceException.Kind.NOT_API;
        assert kind(new TandoorSource("http://no-such-server.invalid.local:1", "t", new Http())) == RecipeSource.SourceException.Kind.LOCAL_NAME;
        assert kind(new TandoorSource("http://127.0.0.1:1", "t", new Http())) == RecipeSource.SourceException.Kind.NETWORK;
        assert kind(new TandoorSource("", "t", new Http())) == RecipeSource.SourceException.Kind.HOST;
        assert kind(new TandoorSource(base + "/v2", "", new Http())) == RecipeSource.SourceException.Kind.AUTH;

        // What one server can answer about one thing, none of which means the token or the address is wrong.
        HttpServer odd = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        odd.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getRawPath();
            int status = 200;
            String answer = "{}";
            if (path.equals("/api/recipe/7/")) {
                status = 403;
                answer = "{\"detail\":\"You do not have the required permissions to view this page!\"}";
            } else if (path.equals("/api/recipe/8/")) {
                answer = "{\"id\":8,\"name\":\"Pizza\",\"servings\":2,\"steps\":["
                    + "{\"id\":1,\"name\":\"\",\"instruction\":\"\",\"instructions_markdown\":\"\",\"time\":0,\"ingredients\":[],\"step_recipe_data\":{\"id\":9,\"name\":\"Testo\",\"steps\":[{\"id\":2,\"name\":\"\",\"instruction\":\"Zmešaj.\",\"time\":0,\"ingredients\":[]}]}},"
                    + "{\"id\":3,\"name\":\"Omaka\",\"instruction\":\"\",\"instructions_markdown\":\"\",\"time\":0,\"ingredients\":[],\"step_recipe_data\":{\"id\":10,\"name\":\"\",\"steps\":[{\"id\":4,\"name\":\"\",\"instruction\":\"Kuhaj.\",\"time\":0,\"ingredients\":[]}]}},"
                    + "{\"id\":5,\"name\":\"\",\"instruction\":\"\",\"instructions_markdown\":\"\",\"time\":20,\"ingredients\":[],\"step_recipe_data\":{\"id\":11,\"name\":\"Sir\",\"steps\":[{\"id\":6,\"name\":\"\",\"instruction\":\"Nariban.\",\"time\":0,\"ingredients\":[]}]}},"
                    + "{\"id\":7,\"name\":\"\",\"instruction\":\"Peci.\",\"time\":0,\"ingredients\":[]}]}";
            } else if (path.equals("/api/server-settings/current/")) {
                answer = "{\"version\":\"2.7.2\"}";
            } else if (path.equals("/api/cook-log/")) {
                status = 400;
                answer = "{\"recipe\":[\"Invalid pk \\\"99\\\" - object does not exist.\"]}";
            } else if (path.equals("/api/meal-plan/")) {
                // A next link that a plain join would turn into a user name at another host.
                answer = "{\"count\":0,\"next\":\"http:@" + base.substring("http://".length()) + "/steal\",\"results\":[]}";
            }
            byte[] bytes = answer.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (java.io.OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        odd.start();
        try {
            TandoorSource source = new TandoorSource("http://127.0.0.1:" + odd.getAddress().getPort(), "tda_secret", new Http());
            try {
                source.recipe("7");
                assert false : "A private recipe must fail";
            } catch (RecipeSource.SourceException refused) {
                assert refused.kind == RecipeSource.SourceException.Kind.PRIVATE && refused.getMessage().contains("required permissions") : refused.kind;
            }
            try {
                source.cooked("99", 2);
                assert false : "A recipe gone from the server cannot be recorded";
            } catch (RecipeSource.SourceException refused) {
                assert refused.kind == RecipeSource.SourceException.Kind.SERVER && refused.getMessage().contains("Invalid pk") : refused.kind + " " + refused.getMessage();
            }
            source.mealPlan(TODAY, TODAY);
            for (String request : new ArrayList<>(requests)) assert !request.contains("/steal") : request;
            // A step that only pulls in another recipe is no page of its own. Its name goes on, and a time keeps it.
            Recipe pizza = source.recipe("8");
            List<String> titles = new ArrayList<>();
            for (Recipe.Step step : pizza.steps) titles.add(step.title);
            assert titles.equals(Arrays.asList("Testo", "Omaka", "", "Sir", "")) : titles;
            assert pizza.steps.get(2).seconds == 1200 && "Peci.".equals(Instructions.render(pizza.steps.get(4), 1, false));
        } finally {
            odd.stop(0);
        }

        // A token that can only read: reading works, and recording says why it cannot.
        HttpServer readOnly = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        readOnly.createContext("/", exchange -> {
            boolean reading = "GET".equals(exchange.getRequestMethod());
            byte[] answer = (reading ? "{\"version\":\"2.7.2\"}" : "{\"detail\":\"You do not have permission.\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(reading ? 200 : 403, answer.length);
            try (java.io.OutputStream out = exchange.getResponseBody()) {
                out.write(answer);
            }
        });
        readOnly.start();
        try {
            new TandoorSource("http://127.0.0.1:" + readOnly.getAddress().getPort(), "t", new Http()).cooked("7", 2);
            assert false : "Recording must fail";
        } catch (RecipeSource.SourceException refused) {
            assert refused.kind == RecipeSource.SourceException.Kind.READ_ONLY && refused.getMessage().contains("You do not have permission.") : refused.getMessage();
        } finally {
            readOnly.stop(0);
        }
    }

    static void instructions() {
        // 1.5.x had no line breaks; source newlines are spaces. Lists become bullets, entities are decoded.
        assert "Mix the flour and water.".equals(Instructions.tandoor("<p>Mix the flour\nand water.</p>", 1, false));
        String list = Instructions.tandoor("<p>Steps:</p>\n<ul>\n<li>one</li>\n<li>two</li>\n</ul>", 1, false);
        assert "Steps:\n\n• one\n• two".equals(list) : list;
        assert "Add 1½ l water at 180 °C".equals(Instructions.tandoor("<p>Add <scalable-number v-bind:number=\"3.0\" v-bind:factor=\"ingredient_factor\"></scalable-number> l water at 180 &deg;C</p>", 0.5, true));
        assert "Add 12,5 g salt".equals(Instructions.tandoor("Add <scalable-number v-bind:number=\"25\"></scalable-number> g salt", 0.5, true));
        assert "an egg".equals(Instructions.tandoor("an <plural-name plural=\"eggs\" singular=\"egg\" :no-amount=\"true\" v-bind:amount=\"3\"></plural-name>", 1, false));
        assert "3 eggs".equals(Instructions.tandoor("<scalable-number v-bind:number='3'></scalable-number> <plural-name singular='egg' plural='eggs' v-bind:amount='3'></plural-name>", 1, false));
        // 2.6.7 and 2.6.8 escape the plural tag, and 2.6.7 sends the raw Markdown with real line breaks.
        assert "2 eggs".equals(Instructions.tandoor("<p><scalable-number v-bind:number=\"2.0000000000000000\"></scalable-number> &lt;plural-name singular=&quot;egg&quot; plural=&quot;eggs&quot; v-bind:amount=&quot;2.0&quot;&gt;&lt;/plural-name&gt;</p>", 1, false));
        assert "Mix well\nthen rest.".equals(Instructions.tandoor("Mix **well**\nthen rest.", 1, false)) : Instructions.tandoor("Mix **well**\nthen rest.", 1, false);
        // Cook logs take decimal servings only from 2.7.
        assert TandoorSource.takesDecimalServings("2.7.0") && TandoorSource.takesDecimalServings("2.7.2") && TandoorSource.takesDecimalServings("3.0.1");
        assert !TandoorSource.takesDecimalServings("2.6.15") && !TandoorSource.takesDecimalServings("1.5.35") && !TandoorSource.takesDecimalServings("") && !TandoorSource.takesDecimalServings(null);
        assert "Tom &amp; Jerry".equals(Instructions.unescape("Tom &amp;amp; Jerry")) && "é ž".equals(Instructions.unescape("&#233; &#x17E;"));
        assert "".equals(Instructions.tandoor(null, 1, false)) && "Mix  well".equals(Instructions.withoutTemplates("Mix {{ ingredients[0] }} well{% if x %}"));
    }

    static RecipeSource.SourceException.Kind kind(TandoorSource source) {
        try {
            source.probe();
            return null;
        } catch (RecipeSource.SourceException failed) {
            return failed.kind;
        }
    }

    static String message(TandoorSource source) {
        try {
            source.probe();
            return "";
        } catch (RecipeSource.SourceException failed) {
            return failed.getMessage();
        }
    }

    static void answer(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getRawPath();
        String query = exchange.getRequestURI().getRawQuery();
        String full = path + (query == null ? "" : "?" + query);
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        String accept = exchange.getRequestHeaders().getFirst("Accept");
        String method = exchange.getRequestMethod();
        requests.add(method + " " + full + " " + auth + " " + accept);
        byte[] in = read(exchange.getRequestBody());
        if (in.length > 0) bodies.add(method + " " + path + " " + new String(in, StandardCharsets.UTF_8));
        if (path.startsWith("/bad/")) {
            send(exchange, 400, "<h1>Bad Request (400)</h1>");
        } else if (path.startsWith("/denied/")) {
            send(exchange, 403, "{\"detail\":\"Authentication credentials were not provided or access is forbidden.\"}");
        } else if (path.startsWith("/web/")) {
            send(exchange, 200, "<!DOCTYPE html><html><body>Tandoor</body></html>");
        } else if (path.startsWith("/v2/")) {
            modernAnswer(exchange, path.substring(3), query);
        } else if (path.startsWith("/v15/")) {
            legacyAnswer(exchange, path.substring(4));
        } else {
            send(exchange, 404, "<h1>Not Found</h1>");
        }
    }

    static void modernAnswer(HttpExchange exchange, String path, String query) throws IOException {
        if (path.equals("/api/server-settings/current/")) {
            send(exchange, 200, "{\"shopping_min_autosync_interval\":5,\"version\":\"2.7.2\"}");
        } else if (path.equals("/api/recipe/") && query.contains("page_size=1") && !query.contains("page_size=1&") && !query.contains("page_size=12")) {
            send(exchange, 200, "{\"count\":140,\"next\":\"" + base + "/v2/api/recipe/?page=2&page_size=1\",\"previous\":null,\"results\":[" + overview(7, "Golaž", null) + "]}");
        } else if (path.equals("/api/recipe/")) {
            send(exchange, 200, "{\"count\":30,\"next\":\"" + base + "/v2/api/recipe/?page=2\",\"previous\":null,\"results\":["
                + overview(7, "Golaž", base + "/media/recipes/a_7.jpg") + "," + overview(8, "Palačinke", null) + "]}");
        } else if (path.equals("/api/meal-plan/") && query.startsWith("page=2")) {
            send(exchange, 200, "{\"count\":3,\"next\":null,\"previous\":null,\"timestamp\":\"2026-10-31T10:00:00+00:00\",\"results\":["
                + entry(3, "2026-10-30T19:00:00+01:00", "2026-11-01T19:00:00+01:00", overview(9, "Juha", null), "", "", "Večerja", null, 3, null, 4) + "]}");
        } else if (path.equals("/api/meal-plan/")) {
            send(exchange, 200, "{\"count\":3,\"next\":\"http://tandoor.example:8080/v2/api/meal-plan/?page=2\",\"previous\":null,\"timestamp\":\"2026-10-31T10:00:00+00:00\",\"results\":["
                + entry(1, "2026-10-31T12:00:00+01:00", "2026-10-31T12:00:00+01:00", overview(7, "Golaž", "/media/recipes/a_7.jpg"), "", "", "Kosilo", "12:00:00", 1, "#ff9800", 2.5) + ","
                + entry(2, "2026-10-31T08:00:00+01:00", "2026-10-31T08:00:00+01:00", "null", "", "Kava", "Zajtrk", "08:00:00", 0, null, 1) + ","
                + entry(4, "2026-11-12T12:00:00+01:00", "2026-11-12T12:00:00+01:00", overview(7, "Golaž", null), "", "", "Kosilo", "12:00:00", 1, null, 2) + "]}");
        } else if (path.equals("/api/recipe/7/")) {
            send(exchange, 200, RECIPE);
        } else if (path.equals("/api/view-log/") || path.equals("/api/cook-log/")) {
            send(exchange, 201, "{\"id\":31}");
        } else {
            // 2.x answers unknown paths with its web app.
            send(exchange, 200, "<!DOCTYPE html><html></html>");
        }
    }

    static void legacyAnswer(HttpExchange exchange, String path) throws IOException {
        if (path.equals("/api/recipe/")) {
            send(exchange, 200, "{\"count\":3,\"next\":null,\"previous\":null,\"results\":[" + overview(7, "Golaž", null) + "]}");
        } else if (path.equals("/api/meal-plan/")) {
            // 1.5.x: a plain array, dates at midnight-free times.
            send(exchange, 200, "[" + entry(1, "2026-10-31T00:00:00+01:00", "2026-10-31T00:00:00+01:00", overview(7, "Golaž", null), "", "", "Kosilo", "12:00:00", 0, null, 2) + "]");
        } else if (path.equals("/api/cook-log/")) {
            send(exchange, 201, "{\"id\":1}");
        } else {
            send(exchange, 404, "<h1>Not Found</h1>");
        }
    }

    static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    static byte[] read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
        return out.toByteArray();
    }
}
