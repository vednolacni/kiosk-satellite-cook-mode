// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import javax.net.ssl.SSLException;

/**
 * Norish over its public REST API under /api/v1, from 0.25 on. The key goes as x-api-key to the configured server only,
 * and never to the health routes, which need none. Norish's own ways stay here: a key has 500 requests an hour, pictures
 * included, after which Norish answers 429 with the seconds to wait; the plan comes only as this week and this month in
 * the server's time zone; the recipe list pages by offset; a recipe keeps its own ingredient list, for both measuring
 * systems at once, and its steps refer to the lines; and pictures are paths under the server's address, which need the
 * key too. Norish keeps no cook log and no list of what was viewed. 0.25 refuses a key past its limit as if it were
 * wrong, so a refusal of a key that has worked is taken for the limit there.
 */
final class NorishSource implements RecipeSource {
    /** Recipes with nutrition data are large. Nothing in this API should come near it. */
    private static final int LIMIT = 4 * 1024 * 1024;
    private static final int PAGE_SIZE = 24;
    /** More than any recipe has in one step. Text is read through patterns under the plugin's lock, so it has a limit. */
    private static final int STEP_TEXT_MOST = 64 * 1024;
    /** No time in a recipe is longer than this many minutes. */
    private static final long MINUTES_MOST = 100_000;
    /** Norish's keys are 64 letters, and it takes a shorter one for no key at all. */
    static final int KEY_LENGTH = 64;
    /** How long to wait when Norish asks to without saying, and the longest it is believed. */
    private static final long WAIT_UNSAID_SECONDS = 60;
    private static final long WAIT_MOST_SECONDS = 2 * 3600;
    /** Norish's rule for an id: a UUID of any version, or the nil or the all-f one. Anything else never reaches a path. */
    private static final Pattern ID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}"
        + "|00000000-0000-0000-0000-000000000000|ffffffff-ffff-ffff-ffff-ffffffffffff");
    /** Norish's meal slots in the day's order. */
    static final List<String> SLOTS = Collections.unmodifiableList(Arrays.asList("Breakfast", "Lunch", "Dinner", "Snack"));
    /**
     * When each slot is usually cooked, in minutes after midnight, since Norish's slots have no time. Cook today's meal
     * goes by these to the meal that comes next, and they keep Norish's own order, with Snack last.
     */
    private static final int[] SLOT_MINUTES = {8 * 60, 12 * 60, 18 * 60, 21 * 60};
    /** The longest 0.25 refuses a key past its limit: an hour from the last request it allowed. */
    private static final long OLDER_WAIT_SECONDS = 3600;
    /**
     * How often to read the plan from 0.25 while nobody asks. Its hour starts again at each request it allows, so a key
     * read every half hour never has the pause that resets its 500, and is refused every few days.
     */
    static final long OLDER_PLAN_MS = 65 * 60_000L;

    private final String base;
    private final String key;
    private final Http http;
    /** A steady clock in milliseconds that also counts while the tablet sleeps, for how long a wait lasts. */
    private final LongSupplier millis;
    /** When the key may be used again, on millis' clock, or null while Norish has not asked to wait. */
    private volatile Long quietUntil;
    /** Whether the key has had an answer since the last wait, so a refusal now is no wrong key on 0.25. */
    private volatile boolean keyWorked;
    /** Whether the server is 0.25, which lacks /health/ready and refuses a key past its limit as if it were wrong. */
    private volatile boolean older;
    /** Whether a health route has said which Norish this is. */
    private volatile boolean checked;
    /** Whether the wait is the plugin's own, after 0.25 refused a key that had worked, rather than one Norish asked for. */
    private volatile boolean olderWait;

    NorishSource(String base, String key, Http http) {
        this(base, key, http, () -> System.nanoTime() / 1_000_000L);
    }

    NorishSource(String base, String key, Http http, LongSupplier millis) {
        this.base = normalize(base);
        this.key = key == null ? "" : key.trim();
        this.http = http;
        this.millis = millis;
    }

    /** The address without trailing slashes or a pasted /api/v1 or /api. Any other path is Norish's base path and stays. */
    static String normalize(String address) {
        String text = withoutSlashes(address == null ? "" : address.trim());
        for (String pasted : new String[] {"/api/v1", "/api"}) {
            int scheme = text.indexOf("://");
            // Never the host itself, such as http://api.
            boolean after = scheme < 0 || text.length() - pasted.length() > scheme + 3;
            if (after && text.toLowerCase(Locale.ROOT).endsWith(pasted)) {
                text = withoutSlashes(text.substring(0, text.length() - pasted.length()));
                break;
            }
        }
        return text;
    }

    private static String withoutSlashes(String text) {
        String rest = text;
        while (rest.endsWith("/")) rest = rest.substring(0, rest.length() - 1);
        return rest;
    }

    @Override
    public String probe() throws SourceException {
        if (base.isEmpty()) throw new SourceException(SourceException.Kind.HOST, "Set the Norish address first.");
        if (key.isEmpty()) throw new SourceException(SourceException.Kind.AUTH, "Set the Norish API key first.");
        String version = version();
        Map<String, Object> page = Json.object(call("POST", "/api/v1/recipes/search", search("", Order.NAME, 0, 1)));
        long count = Json.integer(page.get("total"), -1);
        StringBuilder message = new StringBuilder("Connected to Norish");
        if (!version.isEmpty()) message.append(' ').append(version);
        if (count >= 0) message.append(", ").append(count).append(count == 1 ? " recipe" : " recipes");
        return message.append('.').toString();
    }

    /**
     * Whether Norish is there and ready, from its health routes, which take no key. Only 0.25 tells its version, so it
     * is an empty string from 0.26 on.
     */
    private String version() throws SourceException {
        Http.Response ready = send("GET", "/api/v1/health/ready", null, false);
        String text = ready.text().trim();
        if (!text.startsWith("{")) {
            // A proxy's own page while Norish is stopped or starting says nothing about the address.
            if (ready.status >= 500) throw inFront(ready.status);
            throw notApi(ready.status, text.isEmpty());
        }
        if (ready.status == 503) throw new SourceException(SourceException.Kind.SERVER, "Norish is not ready" + said(ready) + ".");
        checked = true;
        older = ready.status == 404;
        // Anything else but 0.25's missing route is for the search to say.
        if (!older) return "";
        Http.Response health = send("GET", "/api/v1/health", null, false);
        if (!health.ok()) return "";
        try {
            String root = Json.text(Json.path(Json.parse(health.text().trim()), "versions", "root")).trim();
            return root.equals("unavailable") ? "" : root;
        } catch (IllegalArgumentException unreadable) {
            return "";
        }
    }

    @Override
    public List<Planned> mealPlan(LocalDate first, LocalDate last) throws SourceException {
        learnVersion();
        // Norish answers this week and this month in its own time zone, and nothing further. The two together reach
        // furthest, also around a midnight that is not the tablet's, and each meal's own date says where it goes.
        Map<String, Map<String, Object>> meals = new LinkedHashMap<>();
        for (String period : new String[] {"week", "month"}) {
            for (Object item : Json.list(call("GET", "/api/v1/planned-recipes/" + period + "?include=notes", null))) {
                Map<String, Object> meal = Json.object(item);
                String id = Json.text(meal.get("id"));
                if (!meals.containsKey(id)) meals.put(id, meal);
            }
        }
        List<Planned> plan = new ArrayList<>();
        final Map<Planned, Double> places = new IdentityHashMap<>();
        for (Map<String, Object> meal : meals.values()) {
            LocalDate date = date(meal.get("date"));
            if (date == null || date.isBefore(first) || date.isAfter(last)) continue;
            // 0.26.0 and earlier send recipes only, with no type.
            boolean note = "note".equals(Json.text(meal.get("itemType")));
            String recipeId = Json.text(meal.get("recipeId")).trim();
            String recipeName = Json.text(meal.get("recipeName")).trim();
            String words = Json.text(meal.get("title")).trim();
            if (!note && recipeId.isEmpty()) continue;
            // A note can name a recipe too. Then the recipe is the meal and the note goes under it.
            String title = !recipeId.isEmpty() && !recipeName.isEmpty() ? recipeName : words;
            if (title.isEmpty()) continue;
            String slot = Json.text(meal.get("slot")).trim();
            Planned planned = new Planned(Json.text(meal.get("id")), date, slot, slotOrder(slot), 0, recipeId.isEmpty() ? null : recipeId,
                title, note ? words : "", 0, recipeId.isEmpty() ? "" : picture(meal.get("recipeImage")), 0);
            plan.add(planned);
            places.put(planned, Json.number(meal.get("sortOrder"), 0));
        }
        Collections.sort(plan, (a, b) -> {
            int byDate = a.date.compareTo(b.date);
            if (byDate != 0) return byDate;
            if (a.slotOrder != b.slotOrder) return Integer.compare(a.slotOrder, b.slotOrder);
            return Double.compare(places.get(a), places.get(b));
        });
        return plan;
    }

    /** A slot's place in the day: its usual minute for Norish's four, and after them all for any other. */
    static int slotOrder(String slot) {
        int index = SLOTS.indexOf(slot);
        return index < 0 ? 24 * 60 + SLOTS.size() : SLOT_MINUTES[index];
    }

    /**
     * Norish tells this week and this month, as its server counts them, and nothing after the later of their ends. Counted
     * from the day before, since the server's day may not have turned yet when the tablet's has.
     */
    @Override
    public LocalDate planReach(LocalDate first) {
        LocalDate day = first.minusDays(1);
        LocalDate sunday = day.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
        LocalDate monthEnd = day.with(TemporalAdjusters.lastDayOfMonth());
        return sunday.isAfter(monthEnd) ? sunday : monthEnd;
    }

    /** 0.25 needs an hour's pause now and then, which a plan read every half hour never leaves it. */
    @Override
    public long planRefresh(long usual) {
        return older ? Math.max(usual, OLDER_PLAN_MS) : usual;
    }

    /** Norish counts every request against the key, failures too. */
    @Override
    public boolean countsRequests() {
        return true;
    }

    /** A source for the same server and key that knows what this one has learned, with connections of its own. */
    @Override
    public RecipeSource twin() {
        NorishSource twin = new NorishSource(base, key, new Http(), millis);
        twin.keyWorked = keyWorked;
        twin.older = older;
        twin.checked = checked;
        twin.olderWait = olderWait;
        twin.quietUntil = quietUntil;
        return twin;
    }

    /** Which Norish this is, from its health route, which takes no key and costs nothing. Asked until it says. */
    private void learnVersion() {
        if (checked) return;
        Http.Response ready = health("/api/v1/health/ready");
        if (ready == null || ready.status == 503) return;
        checked = true;
        older = ready.status == 404;
    }

    @Override
    public Page recipes(String query, Order order, int page) throws SourceException {
        int number = Math.max(1, page);
        Map<String, Object> answer = Json.object(call("POST", "/api/v1/recipes/search", search(query, order, (number - 1) * PAGE_SIZE, PAGE_SIZE)));
        List<Summary> items = new ArrayList<>();
        for (Object item : Json.list(answer.get("recipes"))) {
            Map<String, Object> recipe = Json.object(item);
            String id = Json.text(recipe.get("id"));
            if (id.isEmpty()) continue;
            items.add(new Summary(id, Json.text(recipe.get("name")), picture(recipe.get("image")), minutes(recipe)));
        }
        boolean more = answer.get("nextCursor") != null;
        int total = (int) Json.integer(answer.get("total"), items.size());
        return new Page(items, more, total);
    }

    /** A search body. Norish pages by an offset, and with words it ranks first and orders only what ties. */
    private static Map<String, Object> search(String query, Order order, int cursor, int limit) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cursor", cursor);
        body.put("limit", limit);
        String text = query == null ? "" : query.trim();
        if (!text.isEmpty()) body.put("search", text);
        // Norish records no views, so its recent is the newest added.
        body.put("sortMode", order == Order.RECENT ? "dateDesc" : "titleAsc");
        return body;
    }

    /** The whole time a recipe takes: its total, or preparing and cooking when it has none. */
    private static int minutes(Map<String, Object> recipe) {
        long total = Json.integer(recipe.get("totalMinutes"), 0);
        if (total <= 0) total = minutes(recipe.get("prepMinutes")) + minutes(recipe.get("cookMinutes"));
        return (int) Math.max(0, Math.min(MINUTES_MOST, total));
    }

    private static long minutes(Object value) {
        return Math.max(0, Math.min(MINUTES_MOST, Json.integer(value, 0)));
    }

    @Override
    public Recipe recipe(String id) throws SourceException {
        String uuid = id == null ? "" : id.trim();
        if (!ID.matcher(uuid).matches()) throw new SourceException(SourceException.Kind.NOT_FOUND, "Norish has no recipe " + id + ".");
        Map<String, Object> recipe = Json.object(call("GET", "/api/v1/recipes/" + uuid, null));
        List<Map<String, Object>> lines = ordered(recipe.get("recipeIngredients"));
        List<Map<String, Object>> steps = ordered(recipe.get("steps"));
        String system = system(Json.text(recipe.get("systemUsed")), lines, steps);
        List<Recipe.Ingredient> list = new ArrayList<>();
        // Steps name a line by its order in their own system.
        Map<Double, Recipe.Ingredient> byOrder = new HashMap<>();
        for (Map<String, Object> line : lines) {
            if (!system.equals(Json.text(line.get("systemUsed")))) continue;
            String written = Json.text(line.get("ingredientName")).trim();
            if (written.startsWith("#")) {
                // A heading, which groups the lines after it. No step refers to one.
                String title = heading(written);
                if (!title.isEmpty()) list.add(Recipe.Ingredient.header(title));
                continue;
            }
            Recipe.Ingredient ingredient = ingredient(line);
            list.add(ingredient);
            double order = Json.number(line.get("order"), Double.NaN);
            if (!Double.isNaN(order) && !byOrder.containsKey(order)) byOrder.put(order, ingredient);
        }
        List<Draft> drafts = new ArrayList<>();
        // How many references each line has, since a line more than one step names is ticked off in each apart.
        Map<Recipe.Ingredient, Integer> named = new IdentityHashMap<>();
        String section = "";
        for (Map<String, Object> step : steps) {
            if (!system.equals(Json.text(step.get("systemUsed")))) continue;
            String text = capped(Json.text(step.get("step")).replace("\r\n", "\n").replace('\r', '\n'));
            String trimmed = text.trim();
            if (trimmed.startsWith("#")) {
                // A heading between the steps, which Norish does not number. It names the steps after it, and anything
                // under its first line is still a step.
                int end = trimmed.indexOf('\n');
                section = heading(end < 0 ? trimmed : trimmed.substring(0, end));
                if (end < 0 || trimmed.substring(end + 1).trim().isEmpty()) continue;
                text = trimmed.substring(end + 1);
            }
            Draft draft = new Draft(section, text);
            for (Map<String, Object> use : ordered(step.get("stepIngredients"))) {
                Recipe.Ingredient line = byOrder.get(Json.number(use.get("ingredientOrder"), Double.NaN));
                if (line == null) continue;
                draft.lines.add(line);
                draft.shares.add(Json.number(use.get("share"), 1));
                named.put(line, named.containsKey(line) ? named.get(line) + 1 : 1);
            }
            drafts.add(draft);
        }
        List<Recipe.Step> method = new ArrayList<>();
        for (Draft draft : drafts) {
            List<Recipe.Ingredient> uses = new ArrayList<>();
            for (int i = 0; i < draft.lines.size(); i++) uses.add(part(draft.lines.get(i), draft.shares.get(i), named.get(draft.lines.get(i)) == 1));
            method.add(new Recipe.Step(draft.title, draft.text, Recipe.Format.MARKDOWN, uses, 0));
        }
        int working = (int) (minutes(recipe.get("prepMinutes")) + minutes(recipe.get("cookMinutes")));
        long total = minutes(recipe.get("totalMinutes"));
        int waiting = 0;
        // With only a total, that is the time it takes. A total longer than the rest is time spent waiting.
        if (working == 0) working = (int) total;
        else if (total > working) waiting = (int) (total - working);
        String image = primary(recipe.get("images"));
        // 0.25 still sends one picture of its own.
        if (image.isEmpty()) image = picture(recipe.get("image"));
        String own = Json.text(recipe.get("id")).trim();
        return new Recipe(own.isEmpty() ? uuid : own, Json.text(recipe.get("name")), Json.text(recipe.get("description")).trim(), image,
            Json.number(recipe.get("servings"), 0), "", working, waiting, base + "/recipes/" + uuid, Json.text(recipe.get("url")), method, list);
    }

    /** A step before its lines are shared out: its title, its words, and the lines it names with the share of each. */
    private static final class Draft {
        final String title;
        final String text;
        final List<Recipe.Ingredient> lines = new ArrayList<>();
        final List<Double> shares = new ArrayList<>();

        Draft(String title, String text) {
            this.title = title;
            this.text = text;
        }
    }

    /**
     * The measuring system to show. A converted recipe holds its lines and steps for both, and Norish shows those of the
     * recipe's own. One with nothing in that system shows the first it has.
     */
    private static String system(String own, List<Map<String, Object>> lines, List<Map<String, Object>> steps) {
        for (List<Map<String, Object>> rows : Arrays.asList(lines, steps)) {
            for (Map<String, Object> row : rows) if (own.equals(Json.text(row.get("systemUsed")))) return own;
        }
        for (List<Map<String, Object>> rows : Arrays.asList(lines, steps)) {
            if (!rows.isEmpty()) return Json.text(rows.get(0).get("systemUsed"));
        }
        return own;
    }

    /** One line as written, with its amount for the recipe's own servings and its unit by Norish's id where the plugin knows it. */
    private static Recipe.Ingredient ingredient(Map<String, Object> line) {
        double amount = Json.number(line.get("amount"), Double.NaN);
        // No amount is null, and sometimes 0.
        if (!(amount > 0) || Double.isInfinite(amount)) amount = Double.NaN;
        String unit = Json.text(line.get("unit")).trim();
        String name = PlainText.fromMarkdown(Json.text(line.get("ingredientName")));
        String key = Units.key(unit);
        if (!key.isEmpty()) return new Recipe.Ingredient(false, amount, Units.name(key, 1, "1", false), "", name, "", "", name, key);
        // A unit kept as it was typed, with no amount to go with, still says how much after the name.
        if (Double.isNaN(amount) && !unit.isEmpty()) return new Recipe.Ingredient(false, amount, "", "", name, "", unit, name);
        return new Recipe.Ingredient(false, amount, unit, "", name, "", "", name);
    }

    /**
     * The share of a line one step uses. All of a line that no other step names is the line itself, so ticking it off in
     * the step ticks it in the list. Anything else is a copy with the step's own amount.
     */
    private static Recipe.Ingredient part(Recipe.Ingredient line, double share, boolean onlyHere) {
        boolean all = !(share > 0) || Double.isInfinite(share) || Math.abs(share - 1) < 1e-9 || !line.hasAmount();
        if (all && onlyHere) return line;
        // Norish rounds the share to four decimals before it scales it for the servings.
        double amount = all ? line.amount : BigDecimal.valueOf(line.amount * share).setScale(4, RoundingMode.HALF_UP).doubleValue();
        return new Recipe.Ingredient(false, amount, line.unit, line.unitPlural, line.food, line.foodPlural, line.note, line.raw, line.unitKey);
    }

    /** A heading's words without its # marks and other Markdown. */
    private static String heading(String line) {
        return PlainText.fromMarkdown(line.replaceFirst("^\\s*#+\\s*", "")).trim();
    }

    /** A list's objects in their own order field's order, keeping the server's for ties and for those without one. */
    private static List<Map<String, Object>> ordered(Object value) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object item : Json.list(value)) rows.add(Json.object(item));
        Collections.sort(rows, (a, b) -> Double.compare(Json.number(a.get("order"), Double.MAX_VALUE), Json.number(b.get("order"), Double.MAX_VALUE)));
        return rows;
    }

    /** The recipe's main picture, the one first in order. */
    private String primary(Object images) {
        for (Map<String, Object> image : ordered(images)) {
            String address = picture(image.get("image"));
            if (!address.isEmpty()) return address;
        }
        return "";
    }

    private static String capped(String text) {
        return text.length() > STEP_TEXT_MOST ? text.substring(0, STEP_TEXT_MOST) : text;
    }

    @Override
    public void viewed(String recipeId) {
        // Norish keeps no list of what was viewed.
    }

    @Override
    public void cooked(String recipeId, double servings) throws SourceException {
        throw new SourceException(SourceException.Kind.UNSUPPORTED, "Norish keeps no cook log.");
    }

    /**
     * Norish's pictures need the key as much as its API. They get it only on the configured server, under its base path,
     * and not while Norish has asked to wait, when each would only count against the key.
     */
    @Override
    public Map<String, String> imageHeaders(String url) {
        return ours(url) && !waiting() ? Collections.singletonMap("x-api-key", key) : Collections.<String, String>emptyMap();
    }

    /** Whether an address is on the configured server, with its scheme and port, under its base path. */
    boolean ours(String url) {
        if (url == null || base.isEmpty() || key.isEmpty()) return false;
        try {
            URL target = new URL(url);
            URL server = new URL(base);
            String path = target.getPath();
            // Dot segments, written out or encoded, leave the base path once the request resolves them.
            String plain = path.replaceAll("(?i)%2e", ".").replaceAll("(?i)%2f", "/").replaceAll("(?i)%5c", "\\\\");
            if (plain.contains("\\") || ("/" + plain + "/").matches("(?s).*/\\.{1,2}/.*")) return false;
            return target.getProtocol().equals(server.getProtocol()) && target.getHost().equalsIgnoreCase(server.getHost()) && port(target) == port(server)
                && target.getUserInfo() == null && path.startsWith(server.getPath() + "/");
        } catch (MalformedURLException notAnAddress) {
            return false;
        }
    }

    private static int port(URL url) {
        return url.getPort() == -1 ? url.getDefaultPort() : url.getPort();
    }

    @Override
    public void cancel() {
        http.cancelAll();
    }

    // HTTP.

    private Object call(String method, String path, Object body) throws SourceException {
        return answer(send(method, path, body, true), method, path);
    }

    /** One request, with the key or without it, and none with it while Norish has asked to wait. */
    private Http.Response send(String method, String path, Object body, boolean withKey) throws SourceException {
        if (base.isEmpty()) throw new SourceException(SourceException.Kind.HOST, "Set the Norish address first.");
        if (withKey) stillWaiting();
        Map<String, String> headers = new LinkedHashMap<>();
        if (withKey) headers.put("x-api-key", key);
        headers.put("Accept", "application/json");
        byte[] bytes = null;
        if (body != null) {
            headers.put("Content-Type", "application/json; charset=utf-8");
            bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        }
        try {
            return http.request(method, base + path, headers, bytes, LIMIT);
        } catch (Http.Refused refused) {
            throw new SourceException(SourceException.Kind.NOT_API, refused.getMessage() + ". The key was not sent there. A login page may be in front of Norish.", refused);
        } catch (UnknownHostException unknown) {
            boolean local = host().toLowerCase(Locale.ROOT).contains(".local");
            throw new SourceException(local ? SourceException.Kind.LOCAL_NAME : SourceException.Kind.NETWORK, "This tablet cannot find " + host() + "."
                + (local ? " Android before version 12 cannot resolve .local names, so use the IP address." : ""), unknown);
        } catch (SSLException secure) {
            throw new SourceException(SourceException.Kind.TLS, "The secure connection to " + host() + " failed: " + reason(secure)
                + ". A private certificate must be installed in Android, and Android 8.1 cannot use a server that only offers TLS 1.3. Plain http on the home network also works.", secure);
        } catch (IOException failed) {
            throw new SourceException(SourceException.Kind.NETWORK, "Could not reach Norish at " + host() + ": " + reason(failed), failed);
        }
    }

    /** What Norish answered, or why not, the way its errors say it: {"message", "code", "data": {"httpStatus", "path"}}. */
    private Object answer(Http.Response response, String method, String path) throws SourceException {
        int status = response.status;
        String text = response.text().trim();
        boolean json = text.startsWith("{") || text.startsWith("[");
        if (status == 429) {
            // Norish's own, or a proxy's in front of it: either way the key waits.
            long seconds = seconds(response.header("Retry-After"));
            olderWait = false;
            quietUntil = millis.getAsLong() + seconds * 1000;
            throw limited(seconds);
        }
        // Norish asks for no password, so a 401 that does is a prompt in front of it. The JDK drops the body of a refused
        // POST, which Android's HTTP keeps, so only there is an empty 401 Norish's.
        if (status == 401 && !response.header("WWW-Authenticate").isEmpty()) throw notApi(status, text.isEmpty());
        if (status == 401 && (json || (text.isEmpty() && "POST".equals(method)))) {
            notReady();
            // 0.25 refuses a key that has made 500 requests with no hour's pause as it refuses a wrong one. A key that has
            // worked is that, and so is one refused while another call's refusal already started the wait.
            if (older && (keyWorked || waiting())) {
                keyWorked = false;
                if (!waiting()) {
                    olderWait = true;
                    quietUntil = millis.getAsLong() + OLDER_WAIT_SECONDS * 1000;
                }
                stillWaiting();
            }
            String length = key.length() < KEY_LENGTH ? " This one has " + key.length() + " characters and Norish's have " + KEY_LENGTH + ", so part of it may be missing." : "";
            String older = this.older ? " Norish 0.25 also refuses a good key for up to an hour once it has made 500 requests with no hour's pause between them." : "";
            throw new SourceException(SourceException.Kind.AUTH, "Norish refused the API key. Create one under Settings > User > API keys and copy all of it: Norish shows it only once."
                + length + older);
        }
        if (!json) {
            // Norish answers everything under /api/v1 in JSON. This came from something in front of it.
            if (status == 404) throw new SourceException(SourceException.Kind.NETWORK, "Something in front of Norish answered 404 for " + path + ".");
            if (status >= 500) throw inFront(status);
            throw notApi(status, text.isEmpty());
        }
        if (response.ok()) {
            keyWorked = true;
            try {
                return Json.parse(text);
            } catch (IllegalArgumentException unreadable) {
                throw new SourceException(SourceException.Kind.PARSE, "Norish sent an answer the plugin cannot read.", unreadable);
            }
        }
        if (status == 404) {
            // Norish's own 404 names the route it came from. One without a route is for a path it does not have.
            if (Json.path(error(text), "data", "path") != null) {
                throw new SourceException(SourceException.Kind.NOT_FOUND, "Norish has no recipe at " + path + " that the key's user may open.");
            }
            throw new SourceException(SourceException.Kind.NOT_API, "Norish has nothing at " + path + ". Check the address, with Norish's base path if it has one.");
        }
        String said = Json.text(error(text).get("message")).trim();
        throw new SourceException(SourceException.Kind.SERVER, "Norish answered HTTP " + status + (said.isEmpty() ? "." : ": " + clip(said) + "."));
    }

    /**
     * A 401 also comes when Norish cannot reach its own database or cache. Its health route, which takes no key, tells,
     * and on 0.25, which lacks that route, the older one does.
     */
    private void notReady() throws SourceException {
        Http.Response ready = health("/api/v1/health/ready");
        if (ready == null) return;
        if (ready.status == 503) throw unready(ready);
        // A server updated from 0.25 has the route now.
        checked = true;
        older = ready.status == 404;
        if (!older) return;
        // 0.25's one route also fails while only its parser is down, which has nothing to do with the key.
        Http.Response health = health("/api/v1/health");
        if (health != null && health.status >= 500 && said(health).toLowerCase(Locale.ROOT).contains("database")) throw unready(health);
    }

    /** A health route's own answer in JSON, or null for anything else, which says nothing here. */
    private Http.Response health(String path) {
        try {
            Http.Response answer = send("GET", path, null, false);
            return answer.text().trim().startsWith("{") ? answer : null;
        } catch (SourceException unreachable) {
            return null;
        }
    }

    private static SourceException unready(Http.Response health) {
        return new SourceException(SourceException.Kind.SERVER, "Norish is not ready" + said(health) + ". That, and not the key, is why it refused it.");
    }

    /** A proxy's own answer while Norish is stopped or starting, which Norish neither sees nor counts. */
    private static SourceException inFront(int status) {
        return new SourceException(SourceException.Kind.NETWORK, "Something in front of Norish answered HTTP " + status + ". Norish may be stopped or still starting.");
    }

    /** Whether Norish has asked to wait and the wait is not over. */
    private boolean waiting() {
        Long until = quietUntil;
        return until != null && until - millis.getAsLong() > 0;
    }

    /** Norish asked to wait. Until then the key goes nowhere: each request would count, and none would be answered. */
    private void stillWaiting() throws SourceException {
        Long until = quietUntil;
        if (until == null) return;
        long left = until - millis.getAsLong();
        if (left > 0) throw limited((left + 999) / 1000);
        quietUntil = null;
    }

    private SourceException limited(long seconds) {
        long minutes = (seconds + 59) / 60;
        String wait = seconds < 60 ? seconds + (seconds == 1 ? " second" : " seconds") : minutes + (minutes == 1 ? " minute" : " minutes");
        if (olderWait) {
            return new SourceException(SourceException.Kind.LIMIT, "The plugin waits " + wait + " before it uses this API key again. Norish 0.25 refused the key after it"
                + " had worked, which it does once a key has made 500 requests with no hour's pause between them. Norish 0.26 and later count a plain hour.");
        }
        return new SourceException(SourceException.Kind.LIMIT, "Norish asks to wait " + wait + ": this API key has made its 500 requests for the hour, pictures included."
            + " Give each tablet its own key.");
    }

    /** Retry-After in seconds. Norish always says it, and a wait it does not say is short. */
    private static long seconds(String retryAfter) {
        try {
            return Math.max(1, Math.min(WAIT_MOST_SECONDS, Long.parseLong(retryAfter.trim())));
        } catch (NumberFormatException unsaid) {
            return WAIT_UNSAID_SECONDS;
        }
    }

    private static SourceException notApi(int status, boolean empty) {
        return new SourceException(SourceException.Kind.NOT_API, "The address answered" + (status >= 300 ? " HTTP " + status : "") + (empty ? " with nothing" : " with a web page")
            + ", not Norish's API. Check the Norish address, with its base path if it has one, and that no login page or password prompt is in front of it.");
    }

    /** An error's own words, as ": words", or nothing. */
    private static String said(Http.Response response) {
        String words = Json.text(error(response.text().trim()).get("message")).trim();
        return words.isEmpty() ? "" : ": " + clip(words);
    }

    private static Map<String, Object> error(String text) {
        try {
            return Json.object(Json.parse(text));
        } catch (IllegalArgumentException unreadable) {
            return Collections.emptyMap();
        }
    }

    private static String clip(String text) {
        return text.length() > 200 ? text.substring(0, 200) : text;
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
        return message == null || message.isEmpty() ? failed.getClass().getSimpleName() : message;
    }

    // Values.

    /** A picture's address. Norish sends paths under its own address, base path included, and other addresses whole. */
    private String picture(Object value) {
        String text = Json.text(value).trim();
        if (text.isEmpty()) return "";
        if (text.startsWith("http://") || text.startsWith("https://")) return text;
        return base + (text.startsWith("/") ? "" : "/") + text;
    }

    /** A plan's date, which Norish sends as a plain YYYY-MM-DD. */
    private static LocalDate date(Object value) {
        String text = Json.text(value);
        if (text.length() < 10) return null;
        try {
            return LocalDate.parse(text.substring(0, 10));
        } catch (DateTimeParseException unreadable) {
            return null;
        }
    }
}
