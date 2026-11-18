// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLException;

/**
 * Mealie over its REST API under /api, from v2.3 on, when households and structured servings are both there. The token
 * goes as a Bearer header to the configured server only, and never to /api/app/about or the pictures, which need none.
 * Mealie's own ways stay here: it runs only at the root of its address, so a path typed after it is left out and said
 * so; the meal plan is always the token's household's, with a type for each meal and no time; a recipe keeps its own
 * ingredient list, and its steps refer to the lines by id; its times are seconds from 3.29 and free text before; and a
 * picture's address stays the same when the picture changes, so the key Mealie gives each picture rides along after a
 * #, which names it apart in the tablet's cache and never reaches the server. Mealie keeps no list of what was viewed,
 * and records a meal as the day its recipe was last made.
 */
final class MealieSource implements RecipeSource {
    /** Recipes with nutrition data are large. Nothing in this API should come near it. */
    private static final int LIMIT = 4 * 1024 * 1024;
    private static final int PAGE_SIZE = 24;
    /** More than any recipe has in one step. Text is read through patterns under the plugin's lock, so it has a limit. */
    private static final int STEP_TEXT_MOST = 64 * 1024;
    /** No time in a recipe is longer than this many minutes. */
    private static final long MINUTES_MOST = 100_000;
    /** A step's own time becomes a timer, which runs a day at most. */
    private static final long STEP_SECONDS_MOST = 24 * 60 * 60;
    /** A meal over several days shows on each, at most this many, so a bad entry cannot flood the plan. */
    private static final int DAYS_MOST = 31;
    /** Mealie's ids are UUIDs. Anything else never reaches a path. */
    private static final Pattern ID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    /** A version as Mealie names it, such as v3.31.0. A nightly build says only that. */
    private static final Pattern VERSION = Pattern.compile("v?([0-9]{1,4})\\.([0-9]{1,4})(?:[^0-9].*)?", Pattern.CASE_INSENSITIVE);
    /** The key Mealie gives each picture, which changes with the picture. */
    private static final Pattern PICTURE_KEY = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    /** An amount written at the start of a line's words, which then were never parsed into a quantity. */
    private static final Pattern WRITTEN_AMOUNT = Pattern.compile("^[0-9½¼¾⅓⅔⅛]");
    /** How many recipes' slugs are remembered, since recording a meal needs the slug and the plugin knows the id. */
    private static final int SLUGS_MOST = 256;

    private final String base;
    /** A path typed after the address, which Mealie cannot be served under, or an empty string. */
    private final String dropped;
    private final String token;
    private final Http http;
    private final Clock clock;
    /** The token's group, which names the recipes' web pages, or null until a call tells. */
    private volatile String groupSlug;
    /** Whether the server refused to order by last made, so recent there is the newest added. */
    private volatile boolean newestInstead;
    /** Each recipe's slug by its id, from what the server has sent. */
    private final Map<String, String> slugs = new ConcurrentHashMap<>();

    MealieSource(String address, String token, Http http) {
        this(address, token, http, Clock.systemUTC());
    }

    MealieSource(String address, String token, Http http, Clock clock) {
        String typed = normalize(address);
        this.base = root(typed);
        this.dropped = typed.length() > base.length() && typed.startsWith(base) ? typed.substring(base.length()) : "";
        this.token = token == null ? "" : token.trim();
        this.http = http;
        this.clock = clock;
    }

    /** The address without trailing slashes or a pasted /api. A path before it stays, so the source can say it left it out. */
    static String normalize(String address) {
        String text = withoutSlashes(address == null ? "" : address.trim());
        int scheme = text.indexOf("://");
        // Never the host itself, such as http://api.
        boolean after = scheme < 0 || text.length() - 4 > scheme + 3;
        if (after && text.toLowerCase(Locale.ROOT).endsWith("/api")) text = withoutSlashes(text.substring(0, text.length() - 4));
        return text;
    }

    /** The scheme, host and port alone, since Mealie runs only at the root of an address. Anything that is no address stays as it is. */
    static String root(String address) {
        try {
            URL url = new URL(address);
            if (url.getHost().isEmpty()) return address;
            return url.getProtocol() + "://" + url.getAuthority();
        } catch (MalformedURLException notAnAddress) {
            return address;
        }
    }

    private static String withoutSlashes(String text) {
        String rest = text;
        while (rest.endsWith("/")) rest = rest.substring(0, rest.length() - 1);
        return rest;
    }

    /** What to say about a path left out of the address, or nothing. */
    private String rooted() {
        return dropped.isEmpty() ? "" : " Mealie runs only at the root of an address, so " + dropped + " was left out of it.";
    }

    @Override
    public String probe() throws SourceException {
        if (base.isEmpty()) throw new SourceException(SourceException.Kind.HOST, "Set the Mealie address first.");
        if (token.isEmpty()) throw new SourceException(SourceException.Kind.AUTH, "Set the Mealie API token first.");
        String version = Json.text(Json.object(call("GET", "/api/app/about", null, false, false)).get("version")).trim();
        if (older(version)) {
            throw new SourceException(SourceException.Kind.UNSUPPORTED, "This is Mealie " + version + ". Cook Mode needs Mealie v2.3 or newer, which plans meals"
                + " for each household and keeps servings as numbers. Update Mealie.");
        }
        user();
        Map<String, Object> page = Json.object(call("GET", "/api/recipes?page=1&perPage=1", null, true, false));
        long count = Json.integer(page.get("total"), -1);
        StringBuilder message = new StringBuilder("Connected to Mealie");
        if (!version.isEmpty()) message.append(' ').append(version);
        if (count >= 0) message.append(", ").append(count).append(count == 1 ? " recipe" : " recipes");
        return message.append('.').append(rooted()).toString();
    }

    /** Whether a version is older than v2.3. A version the plugin cannot read, such as a nightly build, is taken as new. */
    static boolean older(String version) {
        Matcher parts = VERSION.matcher(version == null ? "" : version.trim());
        if (!parts.matches()) return false;
        int major = Integer.parseInt(parts.group(1));
        int minor = Integer.parseInt(parts.group(2));
        return major < 2 || (major == 2 && minor < 3);
    }

    /** The token's user, which says the token works and names its group. */
    private void user() throws SourceException {
        Map<String, Object> user = Json.object(call("GET", "/api/users/self", null, true, false));
        String group = Json.text(user.get("groupSlug")).trim();
        if (!group.isEmpty()) groupSlug = group;
    }

    @Override
    public List<Planned> mealPlan(LocalDate first, LocalDate last) throws SourceException {
        // Every page at once. A meal for a whole week, from 3.30, comes with its end date, and older servers ignore the word.
        String path = "/api/households/mealplans?start_date=" + first + "&end_date=" + last + "&perPage=-1&include_week_entries=true";
        Map<String, Object> answer = Json.object(call("GET", path, null, true, false));
        List<Planned> plan = new ArrayList<>();
        final Map<Planned, Long> places = new IdentityHashMap<>();
        long place = 0;
        for (Object item : Json.list(answer.get("items"))) {
            Map<String, Object> entry = Json.object(item);
            place++;
            LocalDate from = date(entry.get("date"));
            if (from == null) continue;
            LocalDate to = date(entry.get("endDate"));
            if (to == null || to.isBefore(from)) to = from;
            Map<String, Object> recipe = Json.object(entry.get("recipe"));
            String recipeId = Json.text(entry.get("recipeId")).trim();
            if (recipeId.isEmpty()) recipeId = Json.text(recipe.get("id")).trim();
            if (!ID.matcher(recipeId).matches()) recipeId = "";
            String name = Json.text(recipe.get("name")).trim();
            String title = Json.text(entry.get("title")).trim();
            String text = Json.text(entry.get("text")).trim();
            String shown;
            String note;
            if (!recipeId.isEmpty()) {
                // The recipe is the meal, and any words of the entry go under it.
                remember(recipeId, recipe);
                shown = !name.isEmpty() ? name : !title.isEmpty() ? title : text;
                note = !text.isEmpty() ? text : title.equals(shown) ? "" : title;
            } else {
                // A note, such as "Eat out", with its title and its words, or either alone.
                shown = !title.isEmpty() ? title : text;
                note = !text.isEmpty() ? text : title;
            }
            if (shown.isEmpty()) continue;
            String type = Json.text(entry.get("entryType")).trim().toLowerCase(Locale.ROOT);
            double servings = 0;
            if (!recipeId.isEmpty()) {
                // From 3.29 a meal can be planned at a multiple of its recipe, which is how many servings it means.
                double scale = Json.number(entry.get("recipeScale"), 1);
                double own = servings(recipe);
                if (scale > 0 && !Double.isInfinite(scale) && Math.abs(scale - 1) > 1e-9 && own > 0) servings = scale * own;
            }
            String image = recipeId.isEmpty() ? "" : picture(recipeId, recipe.get("image"), "min-original");
            int minutes = recipeId.isEmpty() ? 0 : minutes(recipe);
            long id = Json.integer(entry.get("id"), place);
            LocalDate day = from.isBefore(first) ? first : from;
            LocalDate end = to.isAfter(last) ? last : to;
            for (int days = 0; !day.isAfter(end) && days < DAYS_MOST; day = day.plusDays(1), days++) {
                Planned meal = new Planned(Json.text(entry.get("id")), day, type, slotOrder(type), 0, recipeId.isEmpty() ? null : recipeId, shown, note, servings, image, minutes);
                plan.add(meal);
                places.put(meal, id);
            }
        }
        // Each day by its meals' usual hours, and within a type in the order the meals were planned.
        Collections.sort(plan, (a, b) -> {
            int byDate = a.date.compareTo(b.date);
            if (byDate != 0) return byDate;
            if (a.slotOrder != b.slotOrder) return Integer.compare(a.slotOrder, b.slotOrder);
            return Long.compare(places.get(a), places.get(b));
        });
        return plan;
    }

    /**
     * A meal type's place in the day. Mealie's types have no time, so the main three and a snack count as the hours they
     * are usually eaten, and Cook today's meal finds the one that comes next. A side goes with a meal the plan does not
     * name, so it comes right after dinner, the meal most cooked for, and a dessert after that. A drink has no hour and
     * comes after the day's meals, and a type the plugin does not know after it.
     */
    static int slotOrder(String type) {
        switch (type) {
            case "breakfast": return 8 * 60;
            case "lunch": return 12 * 60;
            case "dinner": return 18 * 60;
            case "side": return 18 * 60 + 1;
            case "dessert": return 19 * 60;
            case "snack": return 21 * 60;
            case "drink": return 24 * 60;
            default: return 24 * 60 + 1;
        }
    }

    @Override
    public Page recipes(String query, Order order, int page) throws SourceException {
        StringBuilder path = new StringBuilder("/api/recipes?page=").append(Math.max(1, page)).append("&perPage=").append(PAGE_SIZE);
        String text = query == null ? "" : query.trim();
        // Mealie folds accents and case, so ricet finds Ričet.
        if (!text.isEmpty()) path.append("&search=").append(encode(text));
        Object body;
        if (order == Order.RECENT && !newestInstead) {
            // What the household made last, and what it never made after that.
            Http.Response made = send("GET", path + "&orderBy=lastMade&orderDirection=desc&orderByNullPosition=last", null, true);
            if (made.status == 400 && made.text().trim().startsWith("{")) {
                // A server that cannot order by it says so, and its recent is the newest added from then on.
                newestInstead = true;
                body = call("GET", path + "&orderBy=createdAt&orderDirection=desc", null, true, false);
            } else {
                body = answer(made, "GET", path.toString(), false);
            }
        } else if (order == Order.RECENT) {
            body = call("GET", path + "&orderBy=createdAt&orderDirection=desc", null, true, false);
        } else {
            body = call("GET", path + "&orderBy=name&orderDirection=asc", null, true, false);
        }
        Map<String, Object> answer = Json.object(body);
        List<Summary> items = new ArrayList<>();
        for (Object item : Json.list(answer.get("items"))) {
            Map<String, Object> recipe = Json.object(item);
            String id = Json.text(recipe.get("id")).trim();
            if (!ID.matcher(id).matches()) continue;
            remember(id, recipe);
            items.add(new Summary(id, Json.text(recipe.get("name")).trim(), picture(id, recipe.get("image"), "min-original"), minutes(recipe)));
        }
        long number = Json.integer(answer.get("page"), Math.max(1, page));
        boolean more = !Json.text(answer.get("next")).isEmpty() || number < Json.integer(answer.get("total_pages"), 0);
        int total = (int) Json.integer(answer.get("total"), items.size());
        return new Page(items, more, total);
    }

    @Override
    public Recipe recipe(String id) throws SourceException {
        String uuid = id(id);
        Map<String, Object> recipe = Json.object(call("GET", "/api/recipes/" + uuid, null, true, true));
        String slug = remember(uuid, recipe);
        // Before 3.1 a recipe could keep its lines as text only, with an amount that means nothing.
        boolean unparsed = Json.bool(Json.path(recipe, "settings", "disableAmount"), false);
        List<Recipe.Ingredient> list = new ArrayList<>();
        // Steps name a line by its reference id.
        Map<String, Recipe.Ingredient> byReference = new HashMap<>();
        for (Object item : Json.list(recipe.get("recipeIngredient"))) {
            Map<String, Object> line = Json.object(item);
            // A title starts a section of the lines from this one on.
            String title = Json.text(line.get("title")).trim();
            if (!title.isEmpty()) list.add(Recipe.Ingredient.header(title));
            Recipe.Ingredient ingredient = ingredient(line, unparsed);
            if (ingredient == null) continue;
            list.add(ingredient);
            String reference = Json.text(line.get("referenceId")).trim();
            if (!reference.isEmpty() && !byReference.containsKey(reference)) byReference.put(reference, ingredient);
        }
        List<Draft> drafts = new ArrayList<>();
        // How many steps name each line, since a line more than one step names is ticked off in each apart.
        Map<Recipe.Ingredient, Integer> named = new IdentityHashMap<>();
        String section = "";
        for (Object item : Json.list(recipe.get("recipeInstructions"))) {
            Map<String, Object> step = Json.object(item);
            // A title starts a section of the steps from this one on, as Mealie shows it.
            String title = Json.text(step.get("title")).trim();
            if (!title.isEmpty()) section = title;
            String summary = Json.text(step.get("summary")).trim();
            String text = capped(Json.text(step.get("text")).replace("\r\n", "\n").replace('\r', '\n'));
            long seconds = Json.integer(step.get("durationSeconds"), 0);
            List<Recipe.Ingredient> uses = new ArrayList<>();
            for (Object reference : Json.list(step.get("ingredientReferences"))) {
                Recipe.Ingredient line = byReference.get(Json.text(Json.object(reference).get("referenceId")).trim());
                if (line == null || uses.contains(line)) continue;
                uses.add(line);
                named.put(line, named.containsKey(line) ? named.get(line) + 1 : 1);
            }
            boolean timed = seconds > 0 && seconds <= STEP_SECONDS_MOST;
            if (text.trim().isEmpty() && uses.isEmpty() && !timed) continue;
            String name = summary.isEmpty() ? section : section.isEmpty() ? summary : section + ": " + summary;
            drafts.add(new Draft(name, text, uses, timed ? (int) seconds : 0));
        }
        List<Recipe.Step> steps = new ArrayList<>();
        for (Draft draft : drafts) {
            List<Recipe.Ingredient> uses = new ArrayList<>();
            // A line only this step names is the line itself, so ticking it in the step ticks it in the list.
            for (Recipe.Ingredient line : draft.uses) uses.add(named.get(line) == 1 ? line : copy(line));
            steps.add(new Recipe.Step(draft.title, draft.text, Recipe.Format.MARKDOWN, uses, draft.seconds));
        }
        int working = (int) (minutes(recipe.get("prepTimeSeconds"), recipe.get("prepTime")) + minutes(recipe.get("performTimeSeconds"), recipe.get("performTime")));
        long total = minutes(recipe.get("totalTimeSeconds"), recipe.get("totalTime"));
        int waiting = 0;
        // With only a total, that is the time it takes. A total longer than the rest is time spent waiting.
        if (working == 0) working = (int) total;
        else if (total > working) waiting = (int) (total - working);
        String own = Json.text(recipe.get("id")).trim();
        String group = slug.isEmpty() ? "" : group();
        String web = group.isEmpty() ? "" : base + "/g/" + segment(group) + "/r/" + segment(slug);
        return new Recipe(ID.matcher(own).matches() ? own : uuid, Json.text(recipe.get("name")).trim(), Json.text(recipe.get("description")).trim(),
            picture(uuid, recipe.get("image"), "original"), servings(recipe), servingsText(recipe), working, waiting, web, Json.text(recipe.get("orgURL")).trim(), steps, list);
    }

    /** A step before its lines are shared out. */
    private static final class Draft {
        final String title;
        final String text;
        final List<Recipe.Ingredient> uses;
        final int seconds;

        Draft(String title, String text, List<Recipe.Ingredient> uses, int seconds) {
            this.title = title;
            this.text = text;
            this.uses = uses;
            this.seconds = seconds;
        }
    }

    /**
     * One line as the recipe keeps it, or null for an empty one. Units and foods are the household's own words, which
     * Mealie does not translate, so they come as stored. The amount is the plugin's to write, with a decimal comma or a
     * fraction, so Mealie's own display of the line is only a last resort.
     */
    private static Recipe.Ingredient ingredient(Map<String, Object> line, boolean unparsed) {
        String note = Json.text(line.get("note")).trim();
        String display = Json.text(line.get("display")).trim();
        String original = Json.text(line.get("originalText")).trim();
        if (unparsed) {
            String text = !note.isEmpty() ? note : !display.isEmpty() ? display : original;
            return text.isEmpty() ? null : new Recipe.Ingredient(false, Double.NaN, "", "", "", "", "", text);
        }
        Map<String, Object> unit = Json.object(line.get("unit"));
        Map<String, Object> food = Json.object(line.get("food"));
        double amount = Json.number(line.get("quantity"), Double.NaN);
        if (!(amount > 0) || Double.isInfinite(amount)) amount = Double.NaN;
        String unitName;
        String unitPlural;
        String abbreviation = Json.text(unit.get("abbreviation")).trim();
        if (Json.bool(unit.get("useAbbreviation"), false) && !abbreviation.isEmpty()) {
            unitName = abbreviation;
            unitPlural = Json.text(unit.get("pluralAbbreviation")).trim();
        } else {
            unitName = Json.text(unit.get("name")).trim();
            unitPlural = Json.text(unit.get("pluralName")).trim();
        }
        String foodName = Json.text(food.get("name")).trim();
        String foodPlural = Json.text(food.get("pluralName")).trim();
        // A line can stand for another recipe, named in place of a food.
        if (foodName.isEmpty()) foodName = Json.text(Json.path(line, "referencedRecipe", "name")).trim();
        // A food counts in its plural only by itself: 3 onions, but 300 g onion.
        if (!unitName.isEmpty()) foodPlural = "";
        String raw = !original.isEmpty() ? original : !note.isEmpty() ? note : display;
        // Words never parsed keep a default amount that is not theirs: 1 and "2 cups flour".
        if (unitName.isEmpty() && foodName.isEmpty() && WRITTEN_AMOUNT.matcher(note).find()) amount = Double.NaN;
        if (Double.isNaN(amount) && !unitName.isEmpty()) {
            // A unit with no amount, such as a pinch, still says how much after the name.
            note = note.isEmpty() ? unitName : note + ", " + unitName;
            unitName = "";
            unitPlural = "";
        }
        if (foodName.isEmpty() && note.isEmpty() && raw.isEmpty()) return null;
        return new Recipe.Ingredient(false, amount, unitName, unitPlural, foodName, foodPlural, note, raw);
    }

    /** A line as one of the several steps that name it has it, ticked off apart from the others. */
    private static Recipe.Ingredient copy(Recipe.Ingredient line) {
        return new Recipe.Ingredient(false, line.amount, line.unit, line.unitPlural, line.food, line.foodPlural, line.note, line.raw, line.unitKey);
    }

    /** The servings the amounts are for: the servings, or else the yield, such as 24 for 24 cookies. */
    private static double servings(Map<String, Object> recipe) {
        double servings = Json.number(recipe.get("recipeServings"), 0);
        if (servings > 0 && !Double.isInfinite(servings)) return servings;
        double yield = Json.number(recipe.get("recipeYieldQuantity"), 0);
        return yield > 0 && !Double.isInfinite(yield) ? yield : 0;
    }

    /** What a serving is called when the yield counts them, such as cookies. Empty for plain servings. */
    private static String servingsText(Map<String, Object> recipe) {
        double servings = Json.number(recipe.get("recipeServings"), 0);
        if (servings > 0 && !Double.isInfinite(servings)) return "";
        if (!(Json.number(recipe.get("recipeYieldQuantity"), 0) > 0)) return "";
        // The yield's words, without a number written before them as older recipes have it.
        return Json.text(recipe.get("recipeYield")).trim().replaceFirst("^[0-9½¼¾⅓⅔.,/ ]+", "").trim();
    }

    /** The whole time a recipe takes: its total, or preparing and cooking when it has none. */
    private static int minutes(Map<String, Object> recipe) {
        long total = minutes(recipe.get("totalTimeSeconds"), recipe.get("totalTime"));
        if (total <= 0) total = minutes(recipe.get("prepTimeSeconds"), recipe.get("prepTime")) + minutes(recipe.get("performTimeSeconds"), recipe.get("performTime"));
        return (int) Math.max(0, Math.min(MINUTES_MOST, total));
    }

    /** A time in minutes: its seconds from 3.29, or else what its words say. */
    static long minutes(Object seconds, Object words) {
        long given = Json.integer(seconds, 0);
        long counted = given > 0 ? given : seconds(Json.text(words));
        if (counted <= 0) return 0;
        return Math.max(1, Math.min(MINUTES_MOST, (counted + 30) / 60));
    }

    /**
     * The seconds a time written as text says: "1 hour 30 minutes", "45 min", "1½ ure" or "PT20M". A number alone is
     * minutes, as Mealie's form asks for them.
     */
    static long seconds(String words) {
        String text = words == null ? "" : words.trim();
        if (text.isEmpty()) return 0;
        if (text.matches("[0-9]{1,5}")) return Long.parseLong(text) * 60;
        if (text.matches("(?i)P(?:[0-9]+D)?(?:T(?:[0-9]+H)?(?:[0-9]+M)?(?:[0-9]+(?:\\.[0-9]+)?S)?)?") && text.length() > 2) {
            try {
                return Math.max(0, Duration.parse(text.toUpperCase(Locale.ROOT)).getSeconds());
            } catch (DateTimeParseException unreadable) {
                return 0;
            }
        }
        long total = 0;
        for (Durations.Found found : Durations.find(text, (int) (MINUTES_MOST * 60))) total += found.seconds;
        return total;
    }

    private static String capped(String text) {
        return text.length() > STEP_TEXT_MOST ? text.substring(0, STEP_TEXT_MOST) : text;
    }

    /** The recipe's slug, remembered for recording it, or an empty string. */
    private String remember(String id, Map<String, Object> recipe) {
        String slug = Json.text(recipe.get("slug")).trim();
        if (slug.isEmpty()) return "";
        String key = id.toLowerCase(Locale.ROOT);
        if (slugs.size() >= SLUGS_MOST && !slugs.containsKey(key)) slugs.clear();
        slugs.put(key, slug);
        return slug;
    }

    /** The token's group, asked for once, or an empty string when the server does not say. The recipe shows without its page. */
    private String group() {
        String group = groupSlug;
        if (group != null) return group;
        try {
            user();
        } catch (SourceException unsaid) {
            return "";
        }
        group = groupSlug;
        return group == null ? "" : group;
    }

    @Override
    public void viewed(String recipeId) {
        // Mealie keeps no list of what was viewed.
    }

    /**
     * Mealie records a meal as the day its recipe was last made, for the token's household from 2.5 and for the recipe
     * before. It writes over what is there, so the day goes only when it is later, and a meal recorded on another tablet
     * meanwhile stays. Servings it does not keep. Each record also reaches the household's notifiers and webhooks.
     */
    @Override
    public void cooked(String recipeId, double servings) throws SourceException {
        String uuid = id(recipeId);
        String slug = slugs.get(uuid);
        Map<String, Object> recipe = null;
        if (slug == null || slug.isEmpty()) {
            recipe = Json.object(call("GET", "/api/recipes/" + uuid, null, true, true));
            slug = remember(uuid, recipe);
        }
        if (slug.isEmpty()) throw new SourceException(SourceException.Kind.NOT_FOUND, "Mealie sent no slug for recipe " + uuid + ", so it cannot be recorded.");
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant made;
        try {
            made = instant(Json.object(call("GET", "/api/households/self/recipes/" + segment(slug), null, true, false)).get("lastMade"));
        } catch (SourceException missing) {
            // Before 2.5 the route is not there, and the day is the recipe's own.
            if (missing.kind != SourceException.Kind.NOT_API) throw missing;
            if (recipe == null) recipe = Json.object(call("GET", "/api/recipes/" + uuid, null, true, true));
            made = instant(recipe.get("lastMade"));
        }
        if (made != null && !made.isBefore(now)) return;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", now.toString());
        call("PATCH", "/api/recipes/" + segment(slug) + "/last-made", body, true, true);
    }

    /** Mealie's pictures are public, so nothing goes with them, and a picture elsewhere gets nothing either. */
    @Override
    public Map<String, String> imageHeaders(String url) {
        return Collections.emptyMap();
    }

    @Override
    public void cancel() {
        http.cancelAll();
    }

    // HTTP.

    private Object call(String method, String path, Object body, boolean withToken, boolean aRecipe) throws SourceException {
        return answer(send(method, path, body, withToken), method, path, aRecipe);
    }

    private Http.Response send(String method, String path, Object body, boolean withToken) throws SourceException {
        if (base.isEmpty()) throw new SourceException(SourceException.Kind.HOST, "Set the Mealie address first.");
        Map<String, String> headers = new LinkedHashMap<>();
        if (withToken) headers.put("Authorization", "Bearer " + token);
        headers.put("Accept", "application/json");
        byte[] bytes = null;
        if (body != null) {
            headers.put("Content-Type", "application/json; charset=utf-8");
            bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        }
        try {
            return http.request(method, base + path, headers, bytes, LIMIT);
        } catch (Http.Refused refused) {
            throw new SourceException(SourceException.Kind.NOT_API, refused.getMessage() + ". The token was not sent there. A login page may be in front of Mealie." + rooted(), refused);
        } catch (UnknownHostException unknown) {
            boolean local = host().toLowerCase(Locale.ROOT).contains(".local");
            throw new SourceException(local ? SourceException.Kind.LOCAL_NAME : SourceException.Kind.NETWORK, "This tablet cannot find " + host() + "."
                + (local ? " Android before version 12 cannot resolve .local names, so use the IP address." : ""), unknown);
        } catch (SSLException secure) {
            throw new SourceException(SourceException.Kind.TLS, "The secure connection to " + host() + " failed: " + reason(secure)
                + ". A private certificate must be installed in Android, and Android 8.1 cannot use a server that only offers TLS 1.3. Plain http on the home network also works.", secure);
        } catch (IOException failed) {
            throw new SourceException(SourceException.Kind.NETWORK, "Could not reach Mealie at " + host() + ": " + reason(failed), failed);
        }
    }

    /**
     * What Mealie answered, or why not. Mealie answers its API in JSON, its errors as {"detail": ...}, where the detail is
     * words, an object with a message, or a list of what a request got wrong. A 404 for a recipe is one that is gone.
     */
    private Object answer(Http.Response response, String method, String path, boolean aRecipe) throws SourceException {
        int status = response.status;
        String text = response.text().trim();
        boolean json = text.startsWith("{") || text.startsWith("[");
        boolean write = !"GET".equals(method);
        if (!json) {
            // Not Mealie's own, but something in front of it, or another app at the address.
            if (status == 404) throw new SourceException(SourceException.Kind.NETWORK, "Something in front of Mealie answered 404 for " + path + "." + rooted());
            if (status >= 500) throw new SourceException(SourceException.Kind.NETWORK, "Mealie, or something in front of it, answered HTTP " + status + ". Mealie may be stopped or still starting.");
            if (response.ok() && text.isEmpty() && write) return null;
            throw new SourceException(SourceException.Kind.NOT_API, "The address answered" + (status >= 300 ? " HTTP " + status : "") + (text.isEmpty() ? " with nothing" : " with a web page")
                + ", not Mealie's API. Check the Mealie address, and that no login page or password prompt is in front of it." + rooted());
        }
        String said = said(text);
        if (status == 401) {
            throw new SourceException(SourceException.Kind.AUTH, "Mealie refused the API token" + said + ". Create one under your profile > API Tokens and copy it at once: Mealie shows it only once.");
        }
        if (status == 403 && write) throw new SourceException(SourceException.Kind.READ_ONLY, "Mealie refused to record this" + said + ". The token's user may not change this recipe.");
        if (status == 403 && aRecipe) throw new SourceException(SourceException.Kind.PRIVATE, "Mealie does not let the token's user open this recipe" + said + ".");
        if (status == 403) throw new SourceException(SourceException.Kind.AUTH, "Mealie refused the token's user this" + said + ".");
        if (status == 404 && aRecipe) throw new SourceException(SourceException.Kind.NOT_FOUND, "Mealie has no recipe at " + path + said + ".");
        if (status == 404) {
            throw new SourceException(SourceException.Kind.NOT_API, "Mealie has nothing at " + path + said + ". Check the address, and that this is Mealie v2.3 or newer." + rooted());
        }
        if (!response.ok()) throw new SourceException(SourceException.Kind.SERVER, "Mealie answered HTTP " + status + said + ".");
        try {
            return Json.parse(text);
        } catch (IllegalArgumentException unreadable) {
            throw new SourceException(SourceException.Kind.PARSE, "Mealie sent an answer the plugin cannot read.", unreadable);
        }
    }

    /** An error's own words, as ": words", or nothing. */
    private static String said(String text) {
        String words = detail(text);
        return words.isEmpty() ? "" : ": " + (words.length() > 200 ? words.substring(0, 200) : words);
    }

    /** The detail of one of Mealie's errors: its words, an object's message, or each item of a list with the field it is about. */
    static String detail(String text) {
        Object detail;
        try {
            detail = Json.path(Json.parse(text), "detail");
        } catch (IllegalArgumentException unreadable) {
            return "";
        }
        if (detail instanceof Map) return Json.text(Json.object(detail).get("message")).trim();
        if (detail instanceof List) {
            StringBuilder words = new StringBuilder();
            for (Object item : Json.list(detail)) {
                Map<String, Object> problem = Json.object(item);
                String message = problem.isEmpty() ? Json.text(item).trim() : Json.text(problem.get("msg")).trim();
                if (message.isEmpty()) continue;
                List<Object> where = Json.list(problem.get("loc"));
                String field = where.isEmpty() ? "" : Json.text(where.get(where.size() - 1)).trim();
                if (words.length() > 0) words.append("; ");
                words.append(field.isEmpty() ? message : field + ": " + message);
            }
            return words.toString();
        }
        return Json.text(detail).trim();
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

    /** A recipe id that may go into a path. Anything else is no recipe of Mealie's. */
    private static String id(String id) throws SourceException {
        String uuid = id == null ? "" : id.trim();
        if (!ID.matcher(uuid).matches()) throw new SourceException(SourceException.Kind.NOT_FOUND, "Mealie has no recipe " + id + ".");
        return uuid.toLowerCase(Locale.ROOT);
    }

    /**
     * A recipe's picture at one of Mealie's sizes, or an empty string when it has none. The address stays the same when
     * the picture changes, and Mealie's own page adds the picture's key as a query. Here it follows a #, so the tablet
     * keeps each version of a picture by its own name, while the request, which never carries what follows a #, stays
     * the plain address. Pictures with a query would not be kept on the tablet at all.
     */
    private String picture(String recipeId, Object key, String size) {
        String version = Json.text(key).trim();
        if (base.isEmpty() || !PICTURE_KEY.matcher(version).matches() || !ID.matcher(recipeId).matches()) return "";
        return base + "/api/media/recipes/" + recipeId.toLowerCase(Locale.ROOT) + "/images/" + size + ".webp#" + version;
    }

    /** A plan's date, which Mealie sends as a plain YYYY-MM-DD. */
    private static LocalDate date(Object value) {
        String text = Json.text(value);
        if (text.length() < 10) return null;
        try {
            return LocalDate.parse(text.substring(0, 10));
        } catch (DateTimeParseException unreadable) {
            return null;
        }
    }

    /** A moment as Mealie sends it, with an offset or, as it often stores them, without one in UTC. Null for none. */
    static Instant instant(Object value) {
        String text = Json.text(value).trim();
        if (text.isEmpty()) return null;
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException noOffset) {
            try {
                return LocalDateTime.parse(text).toInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException noTime) {
                LocalDate day = date(text);
                return day == null ? null : day.atStartOfDay().toInstant(ZoneOffset.UTC);
            }
        }
    }

    /** One segment of a path, such as a slug. */
    private static String segment(String text) {
        return encode(text).replace("+", "%20");
    }

    private static String encode(String text) {
        try {
            return URLEncoder.encode(text, "UTF-8");
        } catch (UnsupportedEncodingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
