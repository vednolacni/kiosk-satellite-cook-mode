// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.net.ssl.SSLException;

/**
 * Tandoor Recipes over its REST API, for 1.5.x and 2.x. The token is a Tandoor API token with the scope "read write",
 * sent as a Bearer header to the configured server only. Tandoor's own quirks stay here: every route needs its
 * trailing slash, 2.x answers unknown paths with its web app, a refused token is a 403, a host missing from
 * ALLOWED_HOSTS is a 400, meal plans filter on the end date, and 1.5.x lists are plain arrays.
 */
final class TandoorSource implements RecipeSource {
    /** Recipes with nutrition data are large. Nothing in this API should come near it. */
    private static final int LIMIT = 4 * 1024 * 1024;
    private static final int PAGE_SIZE = 24;
    /** Meal plans arrive 200 to a page. A household plans far fewer in a fortnight. */
    private static final int MAX_PAGES = 5;
    /** More than any recipe has in one step. Text is read through patterns under the plugin's lock, so it has a limit. */
    private static final int STEP_TEXT_MOST = 64 * 1024;

    private final String base;
    private final String token;
    private final Http http;
    /** Tandoor's version, empty when it does not say, null until a call tells. */
    private volatile String version;

    TandoorSource(String base, String token, Http http) {
        this.base = normalize(base);
        this.token = token == null ? "" : token.trim();
        this.http = http;
    }

    /** The server address without a trailing slash or a pasted /api. */
    static String normalize(String address) {
        String text = address == null ? "" : address.trim();
        while (text.endsWith("/")) text = text.substring(0, text.length() - 1);
        if (text.endsWith("/api")) text = text.substring(0, text.length() - 4);
        while (text.endsWith("/")) text = text.substring(0, text.length() - 1);
        return text;
    }

    @Override
    public String probe() throws SourceException {
        if (base.isEmpty()) throw new SourceException(SourceException.Kind.HOST, "Set the Tandoor address first.");
        if (token.isEmpty()) throw new SourceException(SourceException.Kind.AUTH, "Set the Tandoor API token first.");
        String found = version();
        Map<String, Object> page = Json.object(get("/api/recipe/?page_size=1"));
        long count = Json.integer(page.get("count"), -1);
        StringBuilder message = new StringBuilder("Connected to Tandoor");
        if (!found.isEmpty()) message.append(' ').append(found);
        if (count >= 0) message.append(", ").append(count).append(count == 1 ? " recipe" : " recipes");
        return message.append('.').toString();
    }

    /** Tandoor's version from its public settings, or an empty string on 1.5.x, which lacks the route. */
    private String version() throws SourceException {
        try {
            version = Json.text(Json.object(get("/api/server-settings/current/")).get("version"));
        } catch (SourceException missing) {
            if (missing.kind != SourceException.Kind.NOT_FOUND && missing.kind != SourceException.Kind.NOT_API && missing.kind != SourceException.Kind.PARSE) throw missing;
            version = "";
        }
        return version;
    }

    @Override
    public List<Planned> mealPlan(LocalDate first, LocalDate last) throws SourceException {
        // Both bounds filter on an entry's end date, and 1.5.x compares against midnight, so ask for a wider window
        // and keep what overlaps the days asked for.
        String path = "/api/meal-plan/?from_date=" + first.minusDays(1) + "&to_date=" + last.plusDays(14) + "&page_size=200";
        List<Planned> plan = new ArrayList<>();
        for (Object item : results(path)) {
            Map<String, Object> entry = Json.object(item);
            LocalDate from = date(entry.get("from_date"));
            LocalDate to = date(entry.get("to_date"));
            if (from == null) continue;
            if (to == null || to.isBefore(from)) to = from;
            Map<String, Object> recipe = Json.object(entry.get("recipe"));
            Map<String, Object> type = Json.object(entry.get("meal_type"));
            String recipeId = recipe.isEmpty() ? null : Json.text(recipe.get("id"));
            String title = Json.text(recipe.get("name"));
            if (title.isEmpty()) title = Json.text(entry.get("title"));
            if (title.isEmpty()) title = Json.text(entry.get("recipe_name"));
            String note = Json.text(entry.get("note"));
            if (title.isEmpty()) title = note;
            int minutes = (int) (Json.integer(recipe.get("working_time"), 0) + Json.integer(recipe.get("waiting_time"), 0));
            int order = slotOrder(Json.text(type.get("time")), (int) Json.integer(type.get("order"), 0));
            LocalDate day = from.isBefore(first) ? first : from;
            LocalDate end = to.isAfter(last) ? last : to;
            // A long entry, such as soup for the week, shows on each of its days. Capped so a bad entry cannot flood.
            for (int days = 0; !day.isAfter(end) && days < 31; day = day.plusDays(1), days++) {
                plan.add(new Planned(Json.text(entry.get("id")), day, Json.text(type.get("name")), order, color(Json.text(type.get("color"))),
                    recipeId == null || recipeId.isEmpty() ? null : recipeId, title, note, Json.number(entry.get("servings"), 0), image(recipe.get("image")), minutes));
            }
        }
        Collections.sort(plan, (a, b) -> {
            int byDate = a.date.compareTo(b.date);
            if (byDate != 0) return byDate;
            if (a.slotOrder != b.slotOrder) return Integer.compare(a.slotOrder, b.slotOrder);
            return a.title.compareToIgnoreCase(b.title);
        });
        return plan;
    }

    @Override
    public Page recipes(String query, Order order, int page) throws SourceException {
        StringBuilder path = new StringBuilder("/api/recipe/?page=").append(Math.max(1, page)).append("&page_size=").append(PAGE_SIZE);
        String text = query == null ? "" : query.trim();
        if (!text.isEmpty()) path.append("&query=").append(encode(text));
        // Recently viewed first; both versions sort recipes never viewed last.
        if (order == Order.RECENT) path.append("&sort_order=-lastviewed");
        Object body = get(path.toString());
        Map<String, Object> wrapper = Json.object(body);
        List<Object> results = body instanceof List ? Json.list(body) : Json.list(wrapper.get("results"));
        List<Summary> items = new ArrayList<>();
        for (Object item : results) {
            Map<String, Object> recipe = Json.object(item);
            String id = Json.text(recipe.get("id"));
            if (id.isEmpty()) continue;
            int minutes = (int) (Json.integer(recipe.get("working_time"), 0) + Json.integer(recipe.get("waiting_time"), 0));
            items.add(new Summary(id, Json.text(recipe.get("name")), image(recipe.get("image")), minutes));
        }
        boolean more = wrapper.get("next") != null;
        int total = (int) Json.integer(wrapper.get("count"), items.size());
        return new Page(items, more, total);
    }

    @Override
    public Recipe recipe(String id) throws SourceException {
        Map<String, Object> recipe = Json.object(get("/api/recipe/" + number(id) + "/"));
        List<Recipe.Step> steps = new ArrayList<>();
        for (Object item : Json.list(recipe.get("steps"))) {
            Map<String, Object> step = Json.object(item);
            Recipe.Step own = step(step, "");
            // A step can pull in another recipe. Tandoor shows its steps right after, at the same servings.
            Map<String, Object> sub = Json.object(step.get("step_recipe_data"));
            String subName = Json.text(sub.get("name"));
            List<Object> subSteps = Json.list(sub.get("steps"));
            // One that does nothing else would be an empty page. Its name goes on to the steps it stands for.
            boolean only = !subSteps.isEmpty() && own.ingredients.isEmpty() && own.seconds == 0 && Instructions.render(own, 1, false).isEmpty();
            if (only && subName.isEmpty()) subName = own.title;
            if (!only) steps.add(own);
            for (Object subItem : subSteps) steps.add(step(Json.object(subItem), subName));
        }
        return new Recipe(Json.text(recipe.get("id")), Json.text(recipe.get("name")), Json.text(recipe.get("description")), image(recipe.get("image")),
            Json.number(recipe.get("servings"), 0), Json.text(recipe.get("servings_text")), (int) Json.integer(recipe.get("working_time"), 0),
            (int) Json.integer(recipe.get("waiting_time"), 0), base + "/view/recipe/" + number(id), Json.text(recipe.get("source_url")), steps);
    }

    private Recipe.Step step(Map<String, Object> step, String recipeName) {
        String name = Json.text(step.get("name")).trim();
        String title = recipeName.isEmpty() ? name : name.isEmpty() ? recipeName : recipeName + ": " + name;
        List<Recipe.Ingredient> ingredients = new ArrayList<>();
        for (Object item : Json.list(step.get("ingredients"))) {
            Map<String, Object> ingredient = Json.object(item);
            String note = Json.text(ingredient.get("note")).trim();
            // A header row keeps its text in the note.
            if (Json.bool(ingredient.get("is_header"), false)) {
                ingredients.add(Recipe.Ingredient.header(note));
                continue;
            }
            Map<String, Object> food = Json.object(ingredient.get("food"));
            Map<String, Object> unit = Json.object(ingredient.get("unit"));
            double amount = Json.number(ingredient.get("amount"), Double.NaN);
            if (Json.bool(ingredient.get("no_amount"), false) || amount == 0) amount = Double.NaN;
            ingredients.add(new Recipe.Ingredient(false, amount, Json.text(unit.get("name")), Json.text(unit.get("plural_name")),
                Json.text(food.get("name")), Json.text(food.get("plural_name")), note, Json.text(ingredient.get("original_text"))));
        }
        String html = capped(Json.text(step.get("instructions_markdown")));
        String instruction = capped(Json.text(step.get("instruction")));
        String text;
        Recipe.Format format;
        if (!html.trim().isEmpty() && !html.contains(Instructions.TEMPLATE_ERROR)) {
            text = html;
            format = Recipe.Format.TANDOOR_HTML;
        } else {
            text = Instructions.withoutTemplates(instruction);
            format = Recipe.Format.MARKDOWN;
        }
        int minutes = (int) Json.integer(step.get("time"), 0);
        return new Recipe.Step(title, text, format, ingredients, Math.max(0, minutes) * 60);
    }

    private static String capped(String text) {
        return text.length() > STEP_TEXT_MOST ? text.substring(0, STEP_TEXT_MOST) : text;
    }

    @Override
    public void viewed(String recipeId) throws SourceException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("recipe", number(recipeId));
        send("POST", "/api/view-log/", body);
    }

    @Override
    public void cooked(String recipeId, double servings) throws SourceException {
        if (version == null) version();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("recipe", number(recipeId));
        if (servings > 0) body.put("servings", takesDecimalServings(version) ? (Object) servings : (Object) Math.max(1L, Math.round(servings)));
        send("POST", "/api/cook-log/", body);
    }

    /** A cook log's servings are whole numbers before 2.7. A version that does not say gets a whole number too. */
    static boolean takesDecimalServings(String version) {
        String[] parts = version == null ? new String[0] : version.trim().split("\\.");
        if (parts.length < 2) return false;
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            return major > 2 || (major == 2 && minor >= 7);
        } catch (NumberFormatException notAVersion) {
            return false;
        }
    }

    /** Recipe pictures are public under /media/, and S3 links are signed, so no token is ever sent with them. */
    @Override
    public Map<String, String> imageHeaders(String url) {
        return Collections.emptyMap();
    }

    @Override
    public void cancel() {
        http.cancelAll();
    }

    // HTTP.

    /** Every item of a list, following next links, whether Tandoor sends a plain array (1.5.x) or pages (2.x). */
    private List<Object> results(String path) throws SourceException {
        List<Object> all = new ArrayList<>();
        String next = path;
        for (int page = 0; next != null && page < MAX_PAGES; page++) {
            Object body = get(next);
            if (body instanceof List) {
                all.addAll(Json.list(body));
                break;
            }
            Map<String, Object> wrapper = Json.object(body);
            all.addAll(Json.list(wrapper.get("results")));
            Object link = wrapper.get("next");
            next = link instanceof String && !((String) link).isEmpty() ? sameServer((String) link) : null;
        }
        return all;
    }

    /** A next link names the host Tandoor saw. Behind a proxy that may differ, so keep the configured server. */
    private String sameServer(String link) {
        try {
            URL next = new URL(link);
            URL server = new URL(base);
            String file = next.getFile();
            // Anything else would turn the server's address into a user name at some other host.
            if (!file.startsWith("/")) return null;
            String origin = server.getProtocol() + "://" + server.getAuthority();
            return origin + file;
        } catch (MalformedURLException relative) {
            return link.startsWith("/") ? link : null;
        }
    }

    private Object get(String path) throws SourceException {
        return send("GET", path, null);
    }

    private Object send(String method, String path, Object body) throws SourceException {
        if (base.isEmpty()) throw new SourceException(SourceException.Kind.HOST, "Set the Tandoor address first.");
        String url = path.startsWith("http://") || path.startsWith("https://") ? path : base + path;
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + token);
        headers.put("Accept", "application/json");
        byte[] bytes = null;
        if (body != null) {
            headers.put("Content-Type", "application/json; charset=utf-8");
            bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        }
        Http.Response response;
        try {
            response = http.request(method, url, headers, bytes, LIMIT);
        } catch (Http.Refused refused) {
            throw new SourceException(SourceException.Kind.NOT_API, refused.getMessage() + ". The token was not sent there. A login page may be in front of Tandoor.", refused);
        } catch (UnknownHostException unknown) {
            boolean local = host().toLowerCase(Locale.ROOT).contains(".local");
            throw new SourceException(local ? SourceException.Kind.LOCAL_NAME : SourceException.Kind.NETWORK, "This tablet cannot find " + host() + "."
                + (local ? " Android before version 12 cannot resolve .local names, so use the IP address." : ""), unknown);
        } catch (SSLException secure) {
            throw new SourceException(SourceException.Kind.TLS, "The secure connection to " + host() + " failed: " + reason(secure)
                + ". A private certificate must be installed in Android, and Android 8.1 cannot use a server that only offers TLS 1.3. Plain http on the home network also works.", secure);
        } catch (IOException failed) {
            throw new SourceException(SourceException.Kind.NETWORK, "Could not reach Tandoor at " + host() + ": " + reason(failed), failed);
        }
        int status = response.status;
        if (status == 400) {
            // Django's own page for a host it does not know. Tandoor's API answers its refusals in JSON.
            String answer = response.text().trim();
            if (!answer.startsWith("{") && !answer.startsWith("[")) {
                throw new SourceException(SourceException.Kind.HOST, "Tandoor answered 400 Bad Request. Its ALLOWED_HOSTS setting must include " + host()
                    + ". Use the address you open Tandoor with in a browser.");
            }
            throw new SourceException(SourceException.Kind.SERVER, "Tandoor refused this: " + (answer.length() > 300 ? answer.substring(0, 300) : answer));
        }
        if (status == 403 && body != null) {
            throw new SourceException(SourceException.Kind.READ_ONLY, "Tandoor refused to record this. The API token needs the scope \"read write\"." + detail(response));
        }
        if (status == 403 && path.matches("/api/recipe/[0-9]+/")) {
            throw new SourceException(SourceException.Kind.PRIVATE, "Tandoor does not let the token's user open this recipe. It is private and not shared with them." + detail(response));
        }
        if (status == 401 || status == 403) {
            throw new SourceException(SourceException.Kind.AUTH, "Tandoor refused the API token. Create one under Settings > API with the scope \"read write\" and copy it right after saving." + detail(response));
        }
        if (status == 404) throw new SourceException(SourceException.Kind.NOT_FOUND, "Tandoor has nothing at " + path + "." + detail(response));
        if (status >= 300) throw new SourceException(SourceException.Kind.SERVER, "Tandoor answered HTTP " + status + "." + detail(response));
        if (response.body.length == 0) return null;
        String text = response.text().trim();
        if (!text.startsWith("{") && !text.startsWith("[")) {
            throw new SourceException(SourceException.Kind.NOT_API, "The address answered with a web page, not Tandoor's API. Check the Tandoor address, and that no login page is in front of it.");
        }
        try {
            return Json.parse(text);
        } catch (IllegalArgumentException unreadable) {
            throw new SourceException(SourceException.Kind.PARSE, "Tandoor sent an answer the plugin cannot read.", unreadable);
        }
    }

    /** Tandoor's own reason from a JSON error body, such as {"detail": "..."}. */
    private static String detail(Http.Response response) {
        String text = response.text().trim();
        if (!text.startsWith("{")) return "";
        try {
            String detail = Json.text(Json.object(Json.parse(text)).get("detail"));
            return detail.isEmpty() ? "" : " (" + (detail.length() > 200 ? detail.substring(0, 200) : detail) + ")";
        } catch (IllegalArgumentException unreadable) {
            return "";
        }
    }

    private String host() {
        try {
            return new URL(base).getAuthority();
        } catch (MalformedURLException bad) {
            return base;
        }
    }

    private static String reason(IOException failed) {
        String message = failed.getMessage();
        String kind = failed.getClass().getSimpleName();
        return message == null || message.isEmpty() ? kind : message;
    }

    // Values.

    /** An absolute picture address. 1.5.x and some setups send paths relative to the server. */
    private String image(Object value) {
        String text = Json.text(value);
        if (text.isEmpty()) return "";
        if (text.startsWith("http://") || text.startsWith("https://")) return text;
        try {
            URL server = new URL(base);
            return server.getProtocol() + "://" + server.getAuthority() + (text.startsWith("/") ? "" : "/") + text;
        } catch (MalformedURLException bad) {
            return "";
        }
    }

    /** The date part of Tandoor's ISO date-time, in the server's time zone, which is the day the cook planned. */
    private static LocalDate date(Object value) {
        String text = Json.text(value);
        if (text.length() < 10) return null;
        try {
            return LocalDate.parse(text.substring(0, 10));
        } catch (DateTimeParseException unreadable) {
            return null;
        }
    }

    /** Minutes after midnight from "HH:MM:SS", else after every timed slot in the slot's own order. */
    static int slotOrder(String time, int order) {
        if (time.length() >= 5 && time.charAt(2) == ':') {
            try {
                return Integer.parseInt(time.substring(0, 2)) * 60 + Integer.parseInt(time.substring(3, 5));
            } catch (NumberFormatException notATime) {
                // Fall through to the order.
            }
        }
        return 24 * 60 + Math.max(0, order);
    }

    static int color(String hex) {
        if (hex.matches("#[0-9a-fA-F]{6}")) return 0xFF000000 | Integer.parseInt(hex.substring(1), 16);
        return 0;
    }

    /** Recipe ids are numbers in Tandoor. Anything else is refused before it reaches a path. */
    private static long number(String id) throws SourceException {
        try {
            return Long.parseLong(id.trim());
        } catch (NumberFormatException | NullPointerException notAnId) {
            throw new SourceException(SourceException.Kind.NOT_FOUND, "Tandoor has no recipe " + id + ".");
        }
    }

    private static String encode(String text) {
        try {
            return URLEncoder.encode(text, "UTF-8");
        } catch (UnsupportedEncodingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
