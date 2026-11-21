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
 * and records a meal as the day its recipe was last made. No message carries the token, whatever a server sends back.
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
    /** Days in a time written as words, which the timers' own reading does not count: "1 day 2 hours". */
    private static final Pattern DAYS = Pattern.compile("([0-9]{1,3}(?:[.,][0-9]{1,2})?)[ \\t]*(?:days?|d|dan|dni|dneva|dnevi|tag|tage|tagen)(?![\\p{L}])",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /**
     * The ways to list what was made last, tried in turn: Mealie takes field names in camelCase from 2.5 and in snake_case
     * before, and a server that has neither lists the newest added.
     */
    private static final String[] RECENT = {"orderBy=lastMade&orderDirection=desc&orderByNullPosition=last", "orderBy=last_made&orderDirection=desc&orderByNullPosition=last",
        "orderBy=createdAt&orderDirection=desc", "orderBy=created_at&orderDirection=desc"};

    private final String base;
    /** A path typed after the address, which Mealie cannot be served under, or an empty string. */
    private final String dropped;
    private final String token;
    private final Http http;
    private final Clock clock;
    /** The token's group, which names the recipes' web pages, or null until a call tells. */
    private volatile String groupSlug;
    /** Which of RECENT this server takes, as far as the source has learned. */
    private volatile int recent;

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
                // The recipe is the meal, and the entry's words go under it.
                shown = !name.isEmpty() ? name : !title.isEmpty() ? title : text;
                String said = title.equals(shown) ? "" : title;
                String more = text.equals(shown) ? "" : text;
                note = said.isEmpty() ? more : more.isEmpty() ? said : said + ": " + more;
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
                if (scale > 0 && !Double.isInfinite(scale) && Math.abs(scale - 1) > 1e-9) servings = scale * servings(recipe);
            }
            String image = recipeId.isEmpty() ? "" : picture(recipeId, recipe.get("image"), "min-original");
            int minutes = recipeId.isEmpty() ? 0 : minutes(recipe);
            long id = Json.integer(entry.get("id"), place);
            // A meal for a whole week shows on each of its days, though none of them is its own.
            boolean spread = to.isAfter(from);
            LocalDate day = from.isBefore(first) ? first : from;
            LocalDate end = to.isAfter(last) ? last : to;
            for (int days = 0; !day.isAfter(end) && days < DAYS_MOST; day = day.plusDays(1), days++) {
                Planned meal = new Planned(Json.text(entry.get("id")), day, type, slotOrder(type), 0, recipeId.isEmpty() ? null : recipeId, shown, note, servings, image, minutes,
                    spread);
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
        if (order == Order.RECENT) {
            // What the household made last, and what it never made after that, asked for the way this server takes it.
            Http.Response made = null;
            for (int way = recent; way < RECENT.length; way++) {
                made = send("GET", path + "&" + RECENT[way], null, true);
                if (way + 1 == RECENT.length || !badOrder(made)) break;
                recent = way + 1;
            }
            body = answer(made, "GET", path.toString(), false);
        } else {
            body = call("GET", path + "&orderBy=name&orderDirection=asc", null, true, false);
        }
        Map<String, Object> answer = Json.object(body);
        List<Summary> items = new ArrayList<>();
        for (Object item : Json.list(answer.get("items"))) {
            Map<String, Object> recipe = Json.object(item);
            String id = Json.text(recipe.get("id")).trim();
            if (!ID.matcher(id).matches()) continue;
            items.add(new Summary(id, Json.text(recipe.get("name")).trim(), picture(id, recipe.get("image"), "min-original"), minutes(recipe)));
        }
        long number = Json.integer(answer.get("page"), Math.max(1, page));
        boolean more = !Json.text(answer.get("next")).isEmpty() || number < Json.integer(answer.get("total_pages"), 0);
        int total = (int) Json.integer(answer.get("total"), items.size());
        return new Page(items, more, total);
    }

    /** Mealie's refusal of a field to order by, which it says is an invalid order_by statement. Any other trouble is no such refusal. */
    private static boolean badOrder(Http.Response response) {
        return response.status == 400 && detail(response.text().trim()).toLowerCase(Locale.ROOT).startsWith("invalid order_by");
    }

    @Override
    public Recipe recipe(String id) throws SourceException {
        String uuid = id(id);
        Map<String, Object> recipe = Json.object(call("GET", "/api/recipes/" + uuid, null, true, true));
        String slug = Json.text(recipe.get("slug")).trim();
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
        // Notes a step names go with it. The others come after the last step, where Mealie shows its notes.
        Map<String, String> notes = new HashMap<>();
        Map<String, String> unnamed = new LinkedHashMap<>();
        int noteIndex = 0;
        for (Object item : Json.list(recipe.get("notes"))) {
            Map<String, Object> note = Json.object(item);
            String reference = Json.text(note.get("referenceId")).trim();
            String title = Json.text(note.get("title")).trim();
            String text = Json.text(note.get("text")).trim();
            String words = title.isEmpty() ? text : text.isEmpty() ? title : title + ": " + text;
            if (words.isEmpty()) continue;
            if (!reference.isEmpty()) notes.put(reference, words);
            unnamed.put(reference.isEmpty() ? "#" + noteIndex++ : reference, words);
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
            String text = Json.text(step.get("text")).replace("\r\n", "\n").replace('\r', '\n').trim();
            // A step that has only its summary says that.
            if (text.isEmpty()) {
                text = summary;
                summary = "";
            }
            for (Object reference : Json.list(step.get("noteReferences"))) {
                String noteId = Json.text(Json.object(reference).get("referenceId")).trim();
                String note = notes.get(noteId);
                if (note != null) text = text.isEmpty() ? note : text + "\n\n" + note;
                unnamed.remove(noteId);
            }
            text = capped(text);
            long seconds = Json.integer(step.get("durationSeconds"), 0);
            List<Recipe.Ingredient> uses = new ArrayList<>();
            for (Object reference : Json.list(step.get("ingredientReferences"))) {
                Recipe.Ingredient line = byReference.get(Json.text(Json.object(reference).get("referenceId")).trim());
                if (line == null || uses.contains(line)) continue;
                uses.add(line);
                named.put(line, named.containsKey(line) ? named.get(line) + 1 : 1);
            }
            boolean timed = seconds > 0 && seconds <= STEP_SECONDS_MOST;
            if (text.isEmpty() && uses.isEmpty() && !timed) continue;
            String name = summary.isEmpty() ? section : section.isEmpty() ? summary : section + ": " + summary;
            drafts.add(new Draft(name, text, uses, timed ? (int) seconds : 0));
        }
        if (!unnamed.isEmpty()) {
            String rest = String.join("\n\n", unnamed.values());
            Draft last = drafts.isEmpty() ? null : drafts.remove(drafts.size() - 1);
            drafts.add(last == null ? new Draft("", capped(rest), new ArrayList<Recipe.Ingredient>(), 0)
                : new Draft(last.title, capped(last.text.isEmpty() ? rest : last.text + "\n\n" + rest), last.uses, last.seconds));
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
        String unitName = Json.text(unit.get("name")).trim();
        String unitPlural = Json.text(unit.get("pluralName")).trim();
        if (Json.bool(unit.get("useAbbreviation"), false)) {
            // Each form is its abbreviation where it has one, and else its name.
            String abbreviation = Json.text(unit.get("abbreviation")).trim();
            String abbreviations = Json.text(unit.get("pluralAbbreviation")).trim();
            if (!abbreviations.isEmpty()) unitPlural = abbreviations;
            else if (!abbreviation.isEmpty()) unitPlural = abbreviation;
            if (!abbreviation.isEmpty()) unitName = abbreviation;
        }
        String foodName = Json.text(food.get("name")).trim();
        String foodPlural = Json.text(food.get("pluralName")).trim();
        // A line can stand for another recipe, named in place of a food.
        if (foodName.isEmpty()) foodName = Json.text(Json.path(line, "referencedRecipe", "name")).trim();
        // A food counts in its plural only by itself: 3 onions, but 300 g onion.
        if (!unitName.isEmpty()) foodPlural = "";
        String raw = !original.isEmpty() ? original : !note.isEmpty() ? note : display;
        if (Double.isNaN(amount) && !unitName.isEmpty()) {
            // A unit with no amount, such as a pinch, still says how much after the name.
            note = note.isEmpty() ? unitName : note + ", " + unitName;
            unitName = "";
            unitPlural = "";
        }
        if (foodName.isEmpty() && note.isEmpty() && raw.isEmpty()) return null;
        // Mealie writes plurals only for more than one: ½ cup, 2 cups.
        return new Recipe.Ingredient(false, amount, unitName, unitPlural, foodName, foodPlural, note, raw, "", true);
    }

    /** A line as one of the several steps that name it has it, ticked off apart from the others. */
    private static Recipe.Ingredient copy(Recipe.Ingredient line) {
        return new Recipe.Ingredient(false, line.amount, line.unit, line.unitPlural, line.food, line.foodPlural, line.note, line.raw, line.unitKey, line.pluralOverOne);
    }

    /**
     * The servings the amounts are for: the servings, or else the yield, such as 24 for 24 cookies, or else one. Mealie
     * scales a recipe that says neither by a multiple of itself, which is what one serving of it stands for here, so a
     * meal planned at twice such a recipe opens at twice its amounts.
     */
    private static double servings(Map<String, Object> recipe) {
        double servings = Json.number(recipe.get("recipeServings"), 0);
        if (servings > 0 && !Double.isInfinite(servings)) return servings;
        double yield = Json.number(recipe.get("recipeYieldQuantity"), 0);
        return yield > 0 && !Double.isInfinite(yield) ? yield : 1;
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
     * The seconds a time written as text says: "1 day 2 hours", "1 hour 30 minutes", "45 min", "1½ ure" or "PT20M". A
     * number alone is minutes, as Mealie's form asks for them.
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
        Matcher days = DAYS.matcher(text);
        StringBuffer rest = new StringBuffer();
        while (days.find()) {
            total += Math.round(Double.parseDouble(days.group(1).replace(',', '.')) * 24 * 3600);
            days.appendReplacement(rest, " ");
        }
        days.appendTail(rest);
        for (Durations.Found found : Durations.find(rest.toString(), (int) (MINUTES_MOST * 60))) total += found.seconds;
        return Math.min(total, MINUTES_MOST * 60);
    }

    private static String capped(String text) {
        return text.length() > STEP_TEXT_MOST ? text.substring(0, STEP_TEXT_MOST) : text;
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
     * before. It writes over what is there, so the day goes only when it is later. A later day is there when the meal
     * was recorded since, also on another tablet, and then the meal counts as recorded with nothing sent. Servings it
     * does not keep. Each record also reaches the household's notifiers and webhooks. The write goes by the slug, which
     * changes with the recipe's name, so the slug is read by the recipe's id just before.
     */
    @Override
    public void cooked(String recipeId, double servings) throws SourceException {
        String uuid = id(recipeId);
        Map<String, Object> recipe = Json.object(call("GET", "/api/recipes/" + uuid, null, true, true));
        String slug = Json.text(recipe.get("slug")).trim();
        if (slug.isEmpty()) throw new SourceException(SourceException.Kind.NOT_FOUND, "Mealie sent no slug for recipe " + uuid + ", so it cannot be recorded.");
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        String household = "/api/households/self/recipes/" + segment(slug);
        Http.Response ours = send("GET", household, null, true);
        Instant made;
        // Before 2.5 the route is not there, which FastAPI says in its own words, and the day is the recipe's own.
        // Mealie's own 404 for a recipe it does not find says that instead.
        if (ours.status == 404 && ours.text().trim().startsWith("{") && "Not Found".equals(detail(ours.text().trim()))) {
            made = instant(recipe.get("lastMade"));
        } else {
            Map<String, Object> own = Json.object(answer(ours, "GET", household, true));
            String about = Json.text(own.get("recipeId")).trim();
            // The slug now names another recipe, as a rename meanwhile can leave it.
            if (!about.isEmpty() && !about.equalsIgnoreCase(uuid)) {
                throw new SourceException(SourceException.Kind.SERVER, "Mealie's recipe " + slug + " changed while it was being recorded. Try again.");
            }
            made = instant(own.get("lastMade"));
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
            throw new SourceException(SourceException.Kind.NOT_API, safe(refused.getMessage()) + ". The token was not sent there. A login page may be in front of Mealie." + rooted(), refused);
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

    /** An error's own words, as ": words", or nothing, without the token, should something have sent it back. */
    private String said(String text) {
        String words = safe(detail(text));
        return words.isEmpty() ? "" : ": " + (words.length() > 200 ? words.substring(0, 200) : words);
    }

    /** Words for a message with the token taken out, wherever it is in them. */
    private String safe(String words) {
        return token.isEmpty() || words == null ? words : words.replace(token, "[token]");
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

    /** The server's host and port, without a user name or password typed in front of them. */
    private String host() {
        try {
            URL server = new URL(base);
            return server.getHost() + (server.getPort() == -1 ? "" : ":" + server.getPort());
        } catch (MalformedURLException bad) {
            return "the Mealie address";
        }
    }

    private String reason(IOException failed) {
        String message = failed.getMessage();
        return message == null || message.isEmpty() ? failed.getClass().getSimpleName() : safe(message);
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
