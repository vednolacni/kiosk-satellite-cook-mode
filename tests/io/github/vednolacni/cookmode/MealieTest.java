// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

/**
 * MealieSource against local servers, each at the root of its own port as Mealie must be, that answer the way Mealie
 * v3.31, v3.0 and v2.3 do, the way v2.2 and a nightly build name themselves, and the way things in front of Mealie or
 * at its address answer. Where the versions differ: step times (durationSeconds) and seconds for the recipe's times
 * from 3.30 and 3.29, a meal planned at a multiple of its recipe (recipeScale) from 3.29, meals for a whole week from
 * 3.30, the snack, drink and dessert types from 3.7, last made for each household from 2.5, and lines kept as text only
 * (settings.disableAmount) until 3.1. The v2.3 server also refuses to order by lastMade, so the fallback is tried. The
 * answers follow Mealie's OpenAPI description and documentation; nothing of Mealie's own code is in here.
 */
public final class MealieTest {
    static final String TOKEN = "eyJhbGciOiJIUzI1NiJ9.kuhinja.podpis";
    static final String GOLAZ = "1f0e2d3c-4b5a-4968-8776-a5b4c3d2e1f0";
    static final String RICET = "2a1b0c9d-8e7f-4a6b-9c5d-4e3f2a1b0c9d";
    static final String JUHA = "3b2c1d0e-9f8a-4b7c-8d6e-5f4a3b2c1d0e";
    static final String MISSING = "4c3d2e1f-0a9b-4c8d-9e7f-6a5b4c3d2e1f";
    /** A Wednesday. */
    static final LocalDate TODAY = LocalDate.of(2026, 11, 18);
    static final Clock NOW = Clock.fixed(Instant.parse("2026-11-18T19:30:00Z"), ZoneOffset.UTC);
    static final List<String> requests = Collections.synchronizedList(new ArrayList<String>());
    /** What a server saw that Mealie would refuse or that must never be sent. */
    static final List<String> wrong = Collections.synchronizedList(new ArrayList<String>());
    /** Each server's address by what it answers like. */
    static final Map<String, String> servers = new LinkedHashMap<>();
    /** When Golaž was last made, on each server. */
    static final Map<String, String> made = new HashMap<>();
    /** Golaž's picture key, which a new picture changes. */
    static volatile String golazPicture = "Xy12";
    static final List<HttpServer> running = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        for (String flavor : new String[] {"v3.31", "v3.0", "v2.3", "v2.2", "nightly", "proxy", "web", "gate", "broken", "failing", "strict", "revoked", "readonly", "noslug"}) {
            start(flavor);
        }
        try {
            settings();
            words();
            probes();
            plans();
            lists();
            recipes();
            older();
            pictures();
            cooked();
            failures();
        } finally {
            for (HttpServer server : running) server.stop(0);
        }
        assert wrong.isEmpty() : wrong;
        System.out.println("MealieTest passed");
    }

    static MealieSource source(String flavor) {
        return new MealieSource(servers.get(flavor), TOKEN, new Http(), NOW);
    }

    static void settings() {
        assert "http://h:9000".equals(MealieSource.normalize(" http://h:9000/api/ ")) && "http://h:9000/mealie".equals(MealieSource.normalize("http://h:9000/mealie/API"));
        assert "http://api".equals(MealieSource.normalize("http://api/")) && "".equals(MealieSource.normalize(null));
        assert "http://h:9000".equals(MealieSource.root("http://h:9000/mealie")) && "home.example".equals(MealieSource.root("home.example"));
        Map<String, Object> values = new HashMap<>();
        values.put("recipeApp", "Mealie");
        values.put("serverUrl", "https://home.example/mealie/api/");
        values.put("apiToken", " Bearer " + TOKEN + "\n");
        values.put("markCooked", "Always");
        Settings mealie = new Settings(values);
        // The path stays in the setting, so the source can say it left it out.
        assert mealie.app == Settings.App.MEALIE && "https://home.example/mealie".equals(mealie.serverUrl) && TOKEN.equals(mealie.apiToken) : mealie.serverUrl;
        // Mealie records meals, so the setting holds.
        assert mealie.markCooked == Settings.MarkCooked.ALWAYS && mealie.problem() == null;
        assert RecipeSource.of(mealie, () -> 0L) instanceof MealieSource;
        values.put("apiToken", "");
        assert new Settings(values).problem().contains("in Mealie under your profile > API Tokens");
        values.put("apiToken", TOKEN);
        values.put("recipeApp", "Norish");
        Settings norish = new Settings(values);
        assert norish.otherServer(mealie) && norish.markCooked == Settings.MarkCooked.NEVER;
        // The types have the hours they are usually eaten at, a side right after dinner and a drink after the day's meals.
        assert MealieSource.slotOrder("breakfast") == 8 * 60 && MealieSource.slotOrder("lunch") == 12 * 60 && MealieSource.slotOrder("dinner") == 18 * 60;
        assert MealieSource.slotOrder("side") == 18 * 60 + 1 && MealieSource.slotOrder("dessert") == 19 * 60 && MealieSource.slotOrder("snack") == 21 * 60;
        assert MealieSource.slotOrder("drink") == 24 * 60 && MealieSource.slotOrder("brunch") == 24 * 60 + 1;
        assert new MealieSource("http://h", TOKEN, new Http()).planReach(TODAY).equals(LocalDate.MAX);
        // Versions: 2.3 and newer, and a nightly build, which says no number.
        for (String old : new String[] {"v2.2.0", "v1.12.0", "v0.5.6", "2.2.1"}) assert MealieSource.older(old) : old;
        for (String fine : new String[] {"v2.3.0", "v2.8.0", "v3.0.2", "v3.31.0", "v10.0.0", "nightly", "", "v3.31.0-rc1"}) assert !MealieSource.older(fine) : fine;
        // Times written as words, before 3.29 sent their seconds.
        assert MealieSource.seconds("1 hour 30 minutes") == 5400 && MealieSource.seconds("45 min") == 2700 && MealieSource.seconds("2½ hours") == 9000;
        assert MealieSource.seconds("PT20M") == 1200 && MealieSource.seconds("P1DT2H") == 26 * 3600 && MealieSource.seconds("20") == 1200;
        assert MealieSource.seconds("") == 0 && MealieSource.seconds("quick") == 0 && MealieSource.seconds("1 ura in pol") == 5400 && MealieSource.seconds("P") == 0;
        assert MealieSource.minutes(1200, "1 hour") == 20 && MealieSource.minutes(null, "1 hour") == 60 && MealieSource.minutes(0, null) == 0 && MealieSource.minutes(20, "") == 1;
        // Last made, with an offset or without one, which is UTC.
        assert Instant.parse("2026-11-17T18:00:00Z").equals(MealieSource.instant("2026-11-17T18:00:00"));
        assert Instant.parse("2026-11-17T17:00:00Z").equals(MealieSource.instant("2026-11-17T18:00:00+01:00")) && MealieSource.instant(null) == null;
        assert Instant.parse("2026-11-17T00:00:00Z").equals(MealieSource.instant("2026-11-17")) && MealieSource.instant("soon") == null;
        // An error's detail: words, an object's message, or what a request got wrong.
        assert "Could not validate credentials".equals(MealieSource.detail("{\"detail\":\"Could not validate credentials\"}"));
        assert "No Entry Found".equals(MealieSource.detail("{\"detail\":{\"message\":\"No Entry Found\",\"error\":true,\"exception\":null}}"));
        assert "start_date: Input should be a valid date; perPage: required".equals(MealieSource.detail(
            "{\"detail\":[{\"loc\":[\"query\",\"start_date\"],\"msg\":\"Input should be a valid date\",\"type\":\"date_from_datetime_parsing\"},{\"msg\":\"required\",\"loc\":[\"perPage\"]}]}"));
        assert "".equals(MealieSource.detail("<html></html>")) && "".equals(MealieSource.detail("{}"));
    }

    static void words() {
        Strings english = Strings.of("English", Settings.App.MEALIE);
        Strings slovenian = Strings.of("Slovenščina", Settings.App.MEALIE);
        assert "Open in Mealie".equals(english.openInApp) && "Recently made".equals(english.recent) && "Prep and cook".equals(english.prep);
        assert "Odpri v Mealieju".equals(slovenian.openInApp) && "Nedavno skuhano".equals(slovenian.recent) && "Zabeleži v Mealieju kot skuhano".equals(slovenian.logCooked);
        assert english.problemAuth.equals("Mealie refused the API token. Create a new one under your profile > API Tokens.") : english.problemAuth;
        assert slovenian.problemNotApi.equals("Ta naslov ne vodi do Mealiejevega API-ja. Morda je pred njim prijavna stran.") : slovenian.problemNotApi;
        // Mealie's seven types, named in each language.
        String[] types = {"breakfast", "lunch", "dinner", "side", "snack", "drink", "dessert"};
        List<String> inEnglish = new ArrayList<>();
        List<String> inSlovenian = new ArrayList<>();
        for (String type : types) {
            inEnglish.add(english.slot(type));
            inSlovenian.add(slovenian.slot(type));
        }
        assert inEnglish.equals(Arrays.asList("Breakfast", "Lunch", "Dinner", "Side", "Snack", "Drink", "Dessert")) : inEnglish;
        assert inSlovenian.equals(Arrays.asList("Zajtrk", "Kosilo", "Večerja", "Priloga", "Prigrizek", "Pijača", "Sladica")) : inSlovenian;
        assert "brunch".equals(english.slot("brunch"));
        // Norish's four as before, and Tandoor's own names stay the household's.
        assert "Večerja".equals(Strings.of("Slovenščina", Settings.App.NORISH).slot("Dinner")) && "Side".equals(Strings.of("Slovenščina", Settings.App.NORISH).slot("Side"));
        assert "Lunch".equals(Strings.of("Slovenščina", Settings.App.TANDOOR).slot("Lunch")) && "dinner".equals(Strings.of("Slovenščina", Settings.App.NORISH).slot("dinner"));
        assert "Open in Tandoor".equals(Strings.of("English", Settings.App.TANDOOR).openInApp) && "Newest".equals(Strings.of("English", Settings.App.NORISH).recent);
    }

    static void probes() throws Exception {
        requests.clear();
        assert "Connected to Mealie v3.31.0, 30 recipes.".equals(source("v3.31").probe());
        assert requests.equals(Arrays.asList("v3.31 GET /api/app/about - ", "v3.31 GET /api/users/self token ", "v3.31 GET /api/recipes?page=1&perPage=1 token ")) : requests;
        assert "Connected to Mealie v3.0.2, 30 recipes.".equals(source("v3.0").probe());
        assert "Connected to Mealie v2.3.0, 31 recipes.".equals(source("v2.3").probe());
        assert "Connected to Mealie nightly, 30 recipes.".equals(source("nightly").probe());
        // Too old, said before the token goes anywhere.
        requests.clear();
        RecipeSource.SourceException old = failure(() -> source("v2.2").probe());
        assert old.kind == RecipeSource.SourceException.Kind.UNSUPPORTED && old.getMessage().startsWith("This is Mealie v2.2.0. Cook Mode needs Mealie v2.3 or newer") : old.getMessage();
        assert requests.equals(Collections.singletonList("v2.2 GET /api/app/about - ")) : requests;
        // A path after the address goes, since Mealie cannot be served under one, and Test connection says so.
        MealieSource under = new MealieSource(servers.get("v3.31") + "/mealie/api/", TOKEN, new Http(), NOW);
        requests.clear();
        assert "Connected to Mealie v3.31.0, 30 recipes. Mealie runs only at the root of an address, so /mealie was left out of it.".equals(under.probe());
        for (String request : requests) assert !request.contains("/mealie") : request;
        // A wrong token, and nothing set.
        RecipeSource.SourceException refused = failure(() -> new MealieSource(servers.get("v3.31"), "wrong", new Http(), NOW).probe());
        assert refused.kind == RecipeSource.SourceException.Kind.AUTH && refused.getMessage().startsWith("Mealie refused the API token: Could not validate credentials.") : refused.getMessage();
        assert kind(() -> new MealieSource(servers.get("v3.31"), "", new Http(), NOW).probe()) == RecipeSource.SourceException.Kind.AUTH;
        assert kind(() -> new MealieSource("", TOKEN, new Http(), NOW).probe()) == RecipeSource.SourceException.Kind.HOST;
    }

    static void plans() throws Exception {
        MealieSource mealie = source("v3.31");
        requests.clear();
        List<RecipeSource.Planned> plan = mealie.mealPlan(TODAY, TODAY.plusDays(6));
        assert requests.get(0).equals("v3.31 GET /api/households/mealplans?start_date=2026-11-18&end_date=2026-11-24&perPage=-1&include_week_entries=true token ") : requests;
        List<String> titles = new ArrayList<>();
        for (RecipeSource.Planned meal : plan) titles.add(meal.date.getDayOfMonth() + " " + meal.slot + " " + meal.title + (meal.note.isEmpty() ? "" : " (" + meal.note + ")"));
        assert titles.equals(Arrays.asList("18 lunch Kosilo v službi (Kosilo v službi)", "18 lunch Golaž (Ostanki)", "18 dinner Golaž", "18 dinner Juha za ves teden (Juha za ves teden)",
            "18 side Ričet", "18 dessert Sladoled (iz skrinje)", "19 dinner Juha za ves teden (Juha za ves teden)", "19 snack Ričet",
            "20 dinner Juha za ves teden (Juha za ves teden)", "20 drink Kava (Kava)", "21 dinner Juha za ves teden (Juha za ves teden)",
            "22 dinner Juha za ves teden (Juha za ves teden)")) : titles;
        RecipeSource.Planned work = plan.get(0);
        assert work.recipeId == null && "".equals(work.image) && work.servings == 0 && work.minutes == 0 && work.slotOrder == 12 * 60 && "13".equals(work.id);
        RecipeSource.Planned dinner = plan.get(2);
        // Planned at one and a half times the recipe: six servings of its four.
        assert GOLAZ.equals(dinner.recipeId) && dinner.servings == 6 && dinner.minutes == 150 && dinner.color == 0 && dinner.slotOrder == 18 * 60 : dinner.servings;
        assert (servers.get("v3.31") + "/api/media/recipes/" + GOLAZ + "/images/min-original.webp#Xy12").equals(dinner.image) : dinner.image;
        RecipeSource.Planned side = plan.get(4);
        // Planned at the recipe's own: no servings of its own, and no picture where the recipe has none.
        assert RICET.equals(side.recipeId) && side.servings == 0 && "".equals(side.image) && side.minutes == 45;
        assert plan.get(3).recipeId == null && "17".equals(plan.get(3).id) && plan.get(3).slotOrder == dinner.slotOrder;
        // Only what was asked for.
        assert mealie.mealPlan(TODAY.plusDays(1), TODAY.plusDays(1)).size() == 2;

        // v3.0: no week entries, no recipeScale, and none of the types 3.7 added. The meal is planned at the recipe's own.
        List<RecipeSource.Planned> three = source("v3.0").mealPlan(TODAY, TODAY.plusDays(6));
        titles.clear();
        for (RecipeSource.Planned meal : three) titles.add(meal.date.getDayOfMonth() + " " + meal.slot + " " + meal.title);
        assert titles.equals(Arrays.asList("18 lunch Kosilo v službi", "18 lunch Golaž", "18 dinner Golaž", "18 side Ričet")) : titles;
        assert three.get(2).servings == 0 && three.get(2).minutes == 150;
    }

    static void lists() throws Exception {
        MealieSource mealie = source("v3.31");
        requests.clear();
        RecipeSource.Page first = mealie.recipes("", RecipeSource.Order.NAME, 1);
        assert last().equals("v3.31 GET /api/recipes?page=1&perPage=24&orderBy=name&orderDirection=asc token ") : last();
        assert first.items.size() == 24 && first.more && first.total == 30;
        assert "Golaž".equals(first.items.get(0).name) && "Recept 01".equals(first.items.get(1).name) && first.items.get(0).minutes == 150 : first.items.get(0).name;
        assert (servers.get("v3.31") + "/api/media/recipes/" + GOLAZ + "/images/min-original.webp#Xy12").equals(first.items.get(0).image);
        RecipeSource.Page second = mealie.recipes("", RecipeSource.Order.NAME, 2);
        assert second.items.size() == 6 && !second.more && second.total == 30 && "Ričet".equals(second.items.get(5).name);
        // Accents folded by Mealie, so ricet finds Ričet. The search is sent as typed.
        RecipeSource.Page found = mealie.recipes(" ričet ", RecipeSource.Order.NAME, 1);
        assert last().equals("v3.31 GET /api/recipes?page=1&perPage=24&search=ri%C4%8Det&orderBy=name&orderDirection=asc token ") : last();
        assert found.items.size() == 1 && RICET.equals(found.items.get(0).id) && found.items.get(0).minutes == 45 && "".equals(found.items.get(0).image);
        assert mealie.recipes("ricet", RecipeSource.Order.NAME, 1).items.size() == 1;
        // Recently made: last made first, never made last.
        RecipeSource.Page recent = mealie.recipes("", RecipeSource.Order.RECENT, 1);
        assert last().equals("v3.31 GET /api/recipes?page=1&perPage=24&orderBy=lastMade&orderDirection=desc&orderByNullPosition=last token ") : last();
        assert "Golaž".equals(recent.items.get(0).name) && "Ričet".equals(recent.items.get(1).name) && "Recept 05".equals(recent.items.get(2).name) && recent.more;

        // A server that cannot order by last made says so, and from then on recent is the newest added.
        MealieSource older = source("v2.3");
        requests.clear();
        RecipeSource.Page newest = older.recipes("", RecipeSource.Order.RECENT, 1);
        assert requests.equals(Arrays.asList("v2.3 GET /api/recipes?page=1&perPage=24&orderBy=lastMade&orderDirection=desc&orderByNullPosition=last token ",
            "v2.3 GET /api/recipes?page=1&perPage=24&orderBy=createdAt&orderDirection=desc token ")) : requests;
        assert "Stara juha".equals(newest.items.get(0).name) && "Ričet".equals(newest.items.get(1).name) && newest.total == 31;
        requests.clear();
        older.recipes("", RecipeSource.Order.RECENT, 2);
        assert requests.equals(Collections.singletonList("v2.3 GET /api/recipes?page=2&perPage=24&orderBy=createdAt&orderDirection=desc token ")) : requests;
        // Its times are words.
        RecipeSource.Summary golaz = null;
        for (RecipeSource.Summary item : older.recipes("golaz", RecipeSource.Order.NAME, 1).items) golaz = item;
        assert golaz != null && golaz.minutes == 150;
    }

    static void recipes() throws Exception {
        for (String flavor : new String[] {"v3.31", "v3.0"}) {
            boolean newest = flavor.equals("v3.31");
            MealieSource mealie = source(flavor);
            Recipe recipe = mealie.recipe(GOLAZ);
            assert last().equals(flavor + " GET /api/users/self token ") : last();
            assert requests.get(requests.size() - 2).equals(flavor + " GET /api/recipes/" + GOLAZ + " token ") : requests;
            String base = servers.get(flavor);
            assert GOLAZ.equals(recipe.id) && "Golaž".equals(recipe.name) && "Kot pri babici.".equals(recipe.description) && recipe.servings == 4 && "".equals(recipe.servingsText);
            // Prep and cook are the work, from seconds or from words, and the rest of the total is the wait.
            assert recipe.workingMinutes == 110 && recipe.waitingMinutes == 40 : flavor + " " + recipe.workingMinutes;
            assert (base + "/api/media/recipes/" + GOLAZ + "/images/original.webp#Xy12").equals(recipe.image) && (base + "/g/home/r/golaz").equals(recipe.webUrl) : recipe.webUrl;
            assert "https://example.org/golaz".equals(recipe.sourceUrl);
            List<Recipe.Ingredient> list = recipe.ingredients();
            List<String> lines = new ArrayList<>();
            for (Recipe.Ingredient ingredient : list) lines.add(ingredient.line(1, false));
            assert lines.equals(Arrays.asList("Meso", "800 g goveje meso, na kocke", "3 čebule", "1 tablespoon paprika", "sol in poper po okusu", "kumina, pinch",
                "Za postrežbo", "1 Polenta", "2 dl vode")) : lines;
            // A unit's plural with more, a food's only by itself, and a decimal comma and fractions of the plugin's own.
            assert "2 tablespoons paprika".equals(list.get(3).line(2, false)) && "1 čebula".equals(list.get(2).line(1.0 / 3, true)) && "1200 g goveje meso, na kocke".equals(list.get(1).line(1.5, true));
            assert "4½ čebule".equals(list.get(2).line(1.5, true)) && list.get(0).header && list.get(6).header;
            assert !list.get(4).hasAmount() && !list.get(8).hasAmount() && "".equals(list.get(5).measure(1, false));
            assert recipe.steps.size() == 4 : recipe.steps.size();
            Recipe.Step brown = recipe.steps.get(0);
            assert "Priprava".equals(brown.title) && brown.format == Recipe.Format.MARKDOWN && "Meso popeci na masti.".equals(Instructions.render(brown, 1, false));
            assert brown.seconds == (newest ? 600 : 0) && brown.ingredients.size() == 1 && brown.ingredients.get(0) == list.get(1);
            Recipe.Step add = recipe.steps.get(1);
            assert "Priprava".equals(add.title) && add.ingredients.size() == 3 && add.ingredients.get(0) == list.get(2) && add.ingredients.get(1) == list.get(3) && add.seconds == 0;
            Recipe.Step stew = recipe.steps.get(2);
            assert "Kuhanje: Dušenje".equals(stew.title) && "Duši 90 minut.".equals(Instructions.render(stew, 1, false)) && stew.seconds == (newest ? 5400 : 0);
            // Salt that two steps name is ticked off in each apart, and apart from the list.
            Recipe.Ingredient early = add.ingredients.get(2);
            Recipe.Ingredient late = stew.ingredients.get(0);
            assert early != late && early != list.get(4) && late != list.get(4) && "sol in poper po okusu".equals(late.line(1, false));
            Recipe.Step serve = recipe.steps.get(3);
            assert "Kuhanje".equals(serve.title) && serve.ingredients.size() == 1 && serve.ingredients.get(0) == list.get(7);
            // The group is asked for once.
            mealie.recipe(GOLAZ);
            assert last().equals(flavor + " GET /api/recipes/" + GOLAZ + " token ") : last();

            // Counted in bowls, and no picture.
            Recipe ricet = mealie.recipe(RICET);
            assert ricet.servings == 6 && "bowls".equals(ricet.servingsText) && "".equals(ricet.image) && (base + "/g/home/r/ricet").equals(ricet.webUrl);
            assert ricet.workingMinutes == 15 && ricet.waitingMinutes == 30 && ricet.steps.size() == 1 && ricet.steps.get(0).ingredients.isEmpty();
            // Nothing to tell when one is opened.
            int before = requests.size();
            mealie.viewed(GOLAZ);
            assert requests.size() == before && mealie.imageHeaders(recipe.image).isEmpty() && !mealie.countsRequests();
        }
    }

    static void older() throws Exception {
        MealieSource mealie = source("v2.3");
        // Lines kept as text only, before 3.1: each shows as written, and none scales.
        Recipe juha = mealie.recipe(JUHA);
        List<String> lines = new ArrayList<>();
        for (Recipe.Ingredient ingredient : juha.ingredients()) lines.add(ingredient.line(2, true));
        assert lines.equals(Arrays.asList("Juha", "2 korenja", "1 l vode")) : lines;
        assert !juha.ingredients().get(1).hasAmount() && juha.servings == 4 && juha.steps.size() == 1;
        Recipe golaz = mealie.recipe(GOLAZ);
        assert golaz.workingMinutes == 110 && golaz.waitingMinutes == 40 && golaz.steps.get(2).seconds == 0;
        List<RecipeSource.Planned> plan = mealie.mealPlan(TODAY, TODAY);
        assert plan.size() == 4 && plan.get(2).servings == 0;
    }

    static void pictures() throws Exception {
        MealieSource mealie = source("v3.31");
        String picture = mealie.recipe(GOLAZ).image;
        // No query, which the tablet would not keep, and nothing with it, since Mealie's pictures are public.
        assert picture.indexOf('?') < 0 && mealie.imageHeaders(picture).isEmpty() && mealie.imageHeaders("https://cdn.example/x.jpg").isEmpty();
        requests.clear();
        Http http = new Http();
        Http.Response image = http.request("GET", picture, mealie.imageHeaders(picture), null, 1024);
        assert image.ok() && "WEBP".equals(image.text()) && "image/webp".equals(image.header("Content-Type"));
        // The key after the # never reaches the server.
        assert requests.equals(Collections.singletonList("v3.31 GET /api/media/recipes/" + GOLAZ + "/images/original.webp - ")) : requests;
        // A new picture has a new key, so its address is another, which the tablet fetches and keeps apart.
        golazPicture = "Ab34";
        try {
            String changed = mealie.recipe(GOLAZ).image;
            assert !changed.equals(picture) && changed.endsWith("/images/original.webp#Ab34") && changed.startsWith(picture.substring(0, picture.indexOf('#')));
        } finally {
            golazPicture = "Xy12";
        }
        // A picture that is not there is Mealie's own 404, in JSON.
        Http.Response gone = http.request("GET", servers.get("v3.31") + "/api/media/recipes/" + RICET + "/images/original.webp", Collections.<String, String>emptyMap(), null, 1024);
        assert gone.status == 404 && gone.text().startsWith("{");
    }

    static void cooked() throws Exception {
        // Last made only moves on: the household's day is older, so today goes.
        MealieSource mealie = source("v3.31");
        mealie.recipe(GOLAZ);
        requests.clear();
        mealie.cooked(GOLAZ, 6);
        assert requests.equals(Arrays.asList("v3.31 GET /api/households/self/recipes/golaz token ",
            "v3.31 PATCH /api/recipes/golaz/last-made token {\"timestamp\":\"2026-11-18T19:30:00Z\"}")) : requests;
        // Recorded now, so again now changes nothing.
        requests.clear();
        mealie.cooked(GOLAZ, 6);
        assert requests.equals(Collections.singletonList("v3.31 GET /api/households/self/recipes/golaz token ")) : requests;
        // Another tablet recorded a later day meanwhile, which stays.
        made.put("v3.31", "2026-11-20T08:00:00+01:00");
        requests.clear();
        new MealieSource(servers.get("v3.31"), TOKEN, new Http(), Clock.fixed(Instant.parse("2026-11-19T12:00:00Z"), ZoneOffset.UTC)).cooked(GOLAZ, 4);
        // A source that has not read the recipe asks for its slug first.
        assert requests.equals(Arrays.asList("v3.31 GET /api/recipes/" + GOLAZ + " token ", "v3.31 GET /api/households/self/recipes/golaz token ")) : requests;

        // Before 2.5 the day is the recipe's own.
        MealieSource older = source("v2.3");
        requests.clear();
        older.cooked(GOLAZ, 4);
        assert requests.equals(Arrays.asList("v2.3 GET /api/recipes/" + GOLAZ + " token ", "v2.3 GET /api/households/self/recipes/golaz token ",
            "v2.3 PATCH /api/recipes/golaz/last-made token {\"timestamp\":\"2026-11-18T19:30:00Z\"}")) : requests;
        assert "2026-11-18T19:30:00Z".equals(made.get("v2.3"));

        // A user who may not change the recipe, a recipe that is gone, one with no slug, and an id that is none.
        RecipeSource.SourceException refused = failure(() -> source("readonly").cooked(GOLAZ, 4));
        assert refused.kind == RecipeSource.SourceException.Kind.READ_ONLY && refused.getMessage().startsWith("Mealie refused to record this: Permission denied.") : refused.getMessage();
        assert kind(() -> source("v3.31").cooked(MISSING, 4)) == RecipeSource.SourceException.Kind.NOT_FOUND;
        assert kind(() -> source("noslug").cooked(GOLAZ, 4)) == RecipeSource.SourceException.Kind.NOT_FOUND;
        int before = requests.size();
        assert kind(() -> source("v3.31").cooked("../users/self", 4)) == RecipeSource.SourceException.Kind.NOT_FOUND && requests.size() == before;
    }

    static void failures() throws Exception {
        MealieSource mealie = source("v3.31");
        int before = requests.size();
        assert kind(() -> mealie.recipe("golaz/../../users")) == RecipeSource.SourceException.Kind.NOT_FOUND && requests.size() == before;
        RecipeSource.SourceException gone = failure(() -> mealie.recipe(MISSING));
        assert gone.kind == RecipeSource.SourceException.Kind.NOT_FOUND && gone.getMessage().equals("Mealie has no recipe at /api/recipes/" + MISSING + ": No Entry Found.") : gone.getMessage();
        // A token refused, also one revoked.
        RecipeSource.SourceException wrongToken = failure(() -> new MealieSource(servers.get("v3.31"), "wrong", new Http(), NOW).mealPlan(TODAY, TODAY));
        assert wrongToken.kind == RecipeSource.SourceException.Kind.AUTH && wrongToken.getMessage().contains("profile > API Tokens") : wrongToken.getMessage();
        RecipeSource.SourceException revoked = failure(() -> source("revoked").recipes("", RecipeSource.Order.NAME, 1));
        assert revoked.kind == RecipeSource.SourceException.Kind.AUTH && revoked.getMessage().startsWith("Mealie refused the API token: Unauthorized.") : revoked.getMessage();
        // Things in front of Mealie: a proxy while it is stopped, its 404, a web page, a password prompt.
        RecipeSource.SourceException proxy = failure(() -> source("proxy").mealPlan(TODAY, TODAY));
        assert proxy.kind == RecipeSource.SourceException.Kind.NETWORK && proxy.getMessage().startsWith("Mealie, or something in front of it, answered HTTP 502.") : proxy.getMessage();
        RecipeSource.SourceException proxyMissing = failure(() -> source("proxy").recipe(GOLAZ));
        assert proxyMissing.kind == RecipeSource.SourceException.Kind.NETWORK && proxyMissing.getMessage().startsWith("Something in front of Mealie answered 404") : proxyMissing.getMessage();
        RecipeSource.SourceException page = failure(() -> source("web").probe());
        assert page.kind == RecipeSource.SourceException.Kind.NOT_API && page.getMessage().startsWith("The address answered with a web page, not Mealie's API.") : page.getMessage();
        RecipeSource.SourceException gate = failure(() -> source("gate").recipes("", RecipeSource.Order.NAME, 1));
        assert gate.kind == RecipeSource.SourceException.Kind.NOT_API && gate.getMessage().startsWith("The address answered HTTP 401 with a web page") : gate.getMessage();
        // With a path that was left out, the trouble also says so.
        RecipeSource.SourceException under = failure(() -> new MealieSource(servers.get("web") + "/mealie", TOKEN, new Http(), NOW).recipes("", RecipeSource.Order.NAME, 1));
        assert under.getMessage().endsWith("Mealie runs only at the root of an address, so /mealie was left out of it.") : under.getMessage();
        // Mealie's own trouble: its plain 500, one it says in JSON, and a request it found wrong.
        RecipeSource.SourceException broken = failure(() -> source("broken").recipe(GOLAZ));
        assert broken.kind == RecipeSource.SourceException.Kind.NETWORK && broken.getMessage().contains("HTTP 500") : broken.getMessage();
        RecipeSource.SourceException failing = failure(() -> source("failing").recipe(GOLAZ));
        assert failing.kind == RecipeSource.SourceException.Kind.SERVER && failing.getMessage().equals("Mealie answered HTTP 500: database is locked.") : failing.getMessage();
        RecipeSource.SourceException strict = failure(() -> source("strict").mealPlan(TODAY, TODAY));
        assert strict.kind == RecipeSource.SourceException.Kind.SERVER && strict.getMessage().equals("Mealie answered HTTP 422: start_date: Input should be a valid date.") : strict.getMessage();
        // A route an old Mealie lacks, and a server nobody answers at.
        RecipeSource.SourceException noRoute = failure(() -> new MealieSource(servers.get("v2.2"), TOKEN, new Http(), NOW).mealPlan(TODAY, TODAY));
        assert noRoute.kind == RecipeSource.SourceException.Kind.NOT_API && noRoute.getMessage().startsWith("Mealie has nothing at /api/households/mealplans") : noRoute.getMessage();
        RecipeSource.SourceException away = failure(() -> new MealieSource("http://127.0.0.1:1", TOKEN, new Http(), NOW).recipes("", RecipeSource.Order.NAME, 1));
        assert away.kind == RecipeSource.SourceException.Kind.NETWORK : away.kind;
    }

    // The servers.

    static void start(String flavor) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                answer(flavor, exchange);
            } catch (RuntimeException bug) {
                wrong.add(flavor + " " + bug);
                send(exchange, 599, "", null);
            }
        });
        server.start();
        running.add(server);
        servers.put(flavor, "http://127.0.0.1:" + server.getAddress().getPort());
        made.put(flavor, "2026-11-17T18:00:00");
    }

    static void answer(String flavor, HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String override = exchange.getRequestHeaders().getFirst("X-HTTP-Method-Override");
        if ("POST".equals(method) && "PATCH".equals(override)) method = "PATCH";
        String path = exchange.getRequestURI().getRawPath();
        String query = exchange.getRequestURI().getRawQuery();
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        boolean keyed = ("Bearer " + TOKEN).equals(auth);
        String body = read(exchange.getRequestBody());
        requests.add(flavor + " " + method + " " + path + (query == null ? "" : "?" + query) + " " + (auth == null ? "-" : keyed ? "token" : "other") + " " + body);
        if (path.endsWith("/") && path.length() > 1) wrong.add("Trailing slash " + path);
        if (body.length() > 0 && !exchange.getRequestHeaders().getFirst("Content-Type").startsWith("application/json")) wrong.add("Not JSON to " + path);
        if (path.startsWith("/api/media/")) {
            if (auth != null) wrong.add("A token with a picture " + path);
            if (query != null) wrong.add("A query with a picture " + path);
            if (path.equals("/api/media/recipes/" + GOLAZ + "/images/original.webp") || path.equals("/api/media/recipes/" + GOLAZ + "/images/min-original.webp")) {
                sendAs(exchange, 200, "WEBP", "image/webp");
            } else {
                send(exchange, 404, "{\"detail\":\"Not Found\"}", null);
            }
            return;
        }
        if (path.equals("/api/app/about") && auth != null) wrong.add("A token to " + path);
        if (path.startsWith("/api/recipes/timeline")) wrong.add("A timeline event");
        switch (flavor) {
            case "proxy":
                if (path.startsWith("/api/recipes/")) send(exchange, 404, "404 page not found", null);
                else send(exchange, 502, "<html><body><h1>502 Bad Gateway</h1></body></html>", null);
                return;
            case "web":
                send(exchange, 200, "<!DOCTYPE html><html><body>Home</body></html>", null);
                return;
            case "gate":
                send(exchange, 401, "<html><body>Sign in to continue</body></html>", null);
                return;
            case "broken":
                sendAs(exchange, 500, "Internal Server Error", "text/plain");
                return;
            case "failing":
                send(exchange, 500, "{\"detail\":\"database is locked\"}", null);
                return;
            case "strict":
                send(exchange, 422, "{\"detail\":[{\"type\":\"date_from_datetime_parsing\",\"loc\":[\"query\",\"start_date\"],\"msg\":\"Input should be a valid date\",\"input\":\"x\"}]}", null);
                return;
            default:
        }
        if (path.equals("/api/app/about")) {
            String version = flavor.equals("v3.0") ? "v3.0.2" : flavor.equals("v2.3") ? "v2.3.0" : flavor.equals("v2.2") ? "v2.2.0" : flavor.equals("nightly") ? "nightly" : "v3.31.0";
            send(exchange, 200, "{\"production\":true,\"version\":\"" + version + "\",\"demoStatus\":false,\"allowSignup\":false,\"defaultGroupSlug\":\"home\",\"defaultHouseholdSlug\":\"family\"}", null);
            return;
        }
        if (!keyed || flavor.equals("revoked")) {
            exchange.getResponseHeaders().add("WWW-Authenticate", "Bearer");
            send(exchange, 401, flavor.equals("revoked") ? "{\"detail\":\"Unauthorized\"}" : "{\"detail\":\"Could not validate credentials\"}", null);
            return;
        }
        Map<String, String> params = params(query);
        boolean v331 = flavor.equals("v3.31") || flavor.equals("nightly") || flavor.equals("readonly") || flavor.equals("noslug");
        boolean v2 = flavor.equals("v2.3");
        if (flavor.equals("v2.2") && path.startsWith("/api/households")) {
            send(exchange, 404, "{\"detail\":\"Not Found\"}", null);
            return;
        }
        if ("GET".equals(method) && path.equals("/api/users/self")) {
            send(exchange, 200, "{\"id\":\"9e8d7c6b-5a49-4382-9172-6a5b4c3d2e1f\",\"username\":\"kuhinja\",\"fullName\":\"Kuhinja\",\"group\":\"Home\",\"household\":\"Family\","
                + "\"groupId\":\"0a1b2c3d-4e5f-4061-8172-93a4b5c6d7e8\",\"groupSlug\":\"home\",\"householdSlug\":\"family\",\"admin\":false}", null);
            return;
        }
        if ("GET".equals(method) && path.equals("/api/recipes")) {
            recipeList(flavor, exchange, params, v2);
            return;
        }
        if ("GET".equals(method) && path.startsWith("/api/recipes/")) {
            String key = path.substring("/api/recipes/".length());
            Map<String, Object> recipe = recipe(flavor, key, v331, v2);
            if (recipe == null) send(exchange, 404, "{\"detail\":{\"message\":\"No Entry Found\",\"error\":true,\"exception\":null}}", null);
            else send(exchange, 200, Json.write(recipe), null);
            return;
        }
        if ("GET".equals(method) && path.startsWith("/api/households/self/recipes/")) {
            if (v2) send(exchange, 404, "{\"detail\":\"Not Found\"}", null);
            else if (path.endsWith("/golaz")) send(exchange, 200, "{\"recipeId\":\"" + GOLAZ + "\",\"lastMade\":\"" + made.get(flavor) + "\"}", null);
            else send(exchange, 404, "{\"detail\":{\"message\":\"No Entry Found\",\"error\":true,\"exception\":null}}", null);
            return;
        }
        if ("PATCH".equals(method) && path.matches("/api/recipes/[a-z-]+/last-made")) {
            if (flavor.equals("readonly")) {
                send(exchange, 403, "{\"detail\":{\"message\":\"Permission denied\",\"error\":true,\"exception\":null}}", null);
                return;
            }
            Object timestamp = Json.object(Json.parse(body)).get("timestamp");
            if (!(timestamp instanceof String) || MealieSource.instant(timestamp) == null) {
                send(exchange, 422, "{\"detail\":[{\"loc\":[\"body\",\"timestamp\"],\"msg\":\"Field required\"}]}", null);
                return;
            }
            made.put(flavor, (String) timestamp);
            send(exchange, 200, Json.write(recipe(flavor, path.split("/")[3], v331, v2)), null);
            return;
        }
        if ("GET".equals(method) && path.equals("/api/households/mealplans")) {
            if (!"-1".equals(params.get("perPage"))) wrong.add("A plan in pages");
            send(exchange, 200, Json.write(plan(flavor, params, v331, v2)), null);
            return;
        }
        send(exchange, 404, "{\"detail\":\"Not Found\"}", null);
    }

    /** The recipes, each as its summary: Golaž, Ričet and 28 more, and on v2.3 an old soup kept as text. */
    static List<Map<String, Object>> all(String flavor, boolean v331, boolean v2) {
        List<Map<String, Object>> all = new ArrayList<>();
        all.add(summary(recipe(flavor, GOLAZ, v331, v2)));
        all.add(summary(recipe(flavor, RICET, v331, v2)));
        if (v2) all.add(summary(recipe(flavor, JUHA, v331, v2)));
        for (int i = 1; i <= 28; i++) {
            String number = String.format("%02d", i);
            Map<String, Object> recipe = map("id", "00000000-0000-4000-8000-0000000000" + number, "slug", "recept-" + number, "name", "Recept " + number, "image", null,
                "recipeServings", 2, "recipeYieldQuantity", 0, "recipeYield", "", "totalTime", null, "prepTime", null, "performTime", null,
                "createdAt", "2026-09-" + number + "T08:00:00", "lastMade", i == 5 ? "2026-11-01T12:00:00" : null);
            if (v331) recipe.put("totalTimeSeconds", null);
            all.add(recipe);
        }
        return all;
    }

    static Map<String, Object> summary(Map<String, Object> recipe) {
        Map<String, Object> summary = new LinkedHashMap<>(recipe);
        for (String detail : new String[] {"recipeIngredient", "recipeInstructions", "settings", "notes", "nutrition"}) summary.remove(detail);
        return summary;
    }

    static void recipeList(String flavor, HttpExchange exchange, Map<String, String> params, boolean v2) throws IOException {
        boolean v331 = !flavor.equals("v3.0") && !v2;
        List<Map<String, Object>> items = new ArrayList<>(all(flavor, v331, v2));
        String search = params.get("search");
        if (search != null) {
            List<Map<String, Object>> found = new ArrayList<>();
            for (Map<String, Object> recipe : items) {
                for (String word : fold(search).split(" +")) if (!word.isEmpty() && fold(Json.text(recipe.get("name"))).contains(word) && !found.contains(recipe)) found.add(recipe);
            }
            items = found;
        }
        String by = params.get("orderBy");
        if ("lastMade".equals(by) && v2) {
            send(exchange, 400, "{\"detail\":\"Invalid order_by statement 'lastMade': no such attribute\"}", null);
            return;
        }
        final String field = by == null ? "name" : by;
        final boolean down = "desc".equals(params.get("orderDirection"));
        final boolean nullsLast = "last".equals(params.get("orderByNullPosition"));
        Collections.sort(items, (a, b) -> {
            String x = Json.text(a.get(field));
            String y = Json.text(b.get(field));
            if (x.isEmpty() != y.isEmpty()) return x.isEmpty() == nullsLast ? 1 : -1;
            int order = field.equals("name") ? fold(x).compareTo(fold(y)) : x.compareTo(y);
            return down ? -order : order;
        });
        int page = Integer.parseInt(params.getOrDefault("page", "1"));
        int perPage = Integer.parseInt(params.getOrDefault("perPage", "50"));
        int pages = (items.size() + perPage - 1) / perPage;
        List<Map<String, Object>> shown = page < 1 ? new ArrayList<Map<String, Object>>() : items.subList(Math.min(items.size(), (page - 1) * perPage), Math.min(items.size(), page * perPage));
        Map<String, Object> answer = map("page", page, "per_page", perPage, "total", items.size(), "total_pages", pages, "items", shown,
            "next", page < pages ? "/recipes?page=" + (page + 1) + "&perPage=" + perPage : null, "previous", page > 1 ? "/recipes?page=" + (page - 1) + "&perPage=" + perPage : null);
        send(exchange, 200, Json.write(answer), null);
    }

    static Map<String, Object> recipe(String flavor, String key, boolean v331, boolean v2) {
        if (key.equals(GOLAZ) || key.equals("golaz")) {
            Map<String, Object> recipe = map("id", GOLAZ, "slug", flavor.equals("noslug") ? "" : "golaz", "name", "Golaž", "description", "Kot pri babici.", "image", golazPicture,
                "recipeServings", 4, "recipeYieldQuantity", 0, "recipeYield", "", "orgURL", "https://example.org/golaz",
                "prepTime", "20 minutes", "performTime", "1 hour 30 minutes", "totalTime", "2½ hours", "createdAt", "2026-10-01T08:00:00", "lastMade", made.get(flavor));
            if (v331) {
                recipe.put("prepTimeSeconds", 1200);
                recipe.put("performTimeSeconds", 5400);
                recipe.put("totalTimeSeconds", 9000);
                // The words say otherwise, so the seconds are seen to win.
                recipe.put("totalTime", "3 hours");
            }
            recipe.put("recipeIngredient", list(
                line("Meso", 800, unit("gram", "grams", "g", "", true), food("goveje meso", ""), "na kocke", "R1"),
                line(null, 3, null, food("čebula", "čebule"), "", "R2"),
                line(null, 1, unit("tablespoon", "tablespoons", "tbsp", "tbsps", false), food("paprika", "paprike"), "", "R3"),
                line(null, 0, null, null, "sol in poper po okusu", "R4"),
                line(null, null, unit("pinch", "pinches", "", "", false), food("kumina", ""), "", "R5"),
                withRecipe(line("Za postrežbo", 1, null, null, "", "R6"), "Polenta"),
                line(null, 1, null, null, "2 dl vode", "R7"),
                line(null, 0, null, null, "", "R8")));
            recipe.put("recipeInstructions", list(
                step("Priprava", "", "Meso **popeci** na masti.", v331 ? 600 : null, "R1"),
                step("", "", "Dodaj čebulo in papriko.", v331 ? 0 : null, "R2", "R3", "R4", "R404"),
                step("Kuhanje", "Dušenje", "Duši 90 minut.", v331 ? 5400 : null, "R4"),
                step("", "", "  ", null),
                step("", "", "Postrezi s polento.", null, "R6", "R6")));
            recipe.put("settings", v2 || flavor.equals("v3.0") ? map("public", true, "showNutrition", false, "disableAmount", false) : map("public", true, "showNutrition", false));
            return recipe;
        }
        if (key.equals(RICET) || key.equals("ricet")) {
            Map<String, Object> recipe = map("id", RICET, "slug", "ricet", "name", "Ričet", "description", "", "image", null, "recipeServings", 0, "recipeYieldQuantity", 6,
                "recipeYield", "6 bowls", "orgURL", null, "prepTime", "15 min", "performTime", "", "totalTime", "45 min", "createdAt", "2026-10-05T08:00:00", "lastMade", "2026-11-10T12:00:00");
            if (v331) recipe.put("totalTimeSeconds", 2700);
            recipe.put("recipeIngredient", list());
            recipe.put("recipeInstructions", list(step("", "", "Vse skuhaj skupaj.", null)));
            return recipe;
        }
        if (v2 && (key.equals(JUHA) || key.equals("stara-juha"))) {
            Map<String, Object> recipe = map("id", JUHA, "slug", "stara-juha", "name", "Stara juha", "image", null, "recipeServings", 4, "recipeYieldQuantity", 0, "recipeYield", "",
                "createdAt", "2026-11-11T08:00:00", "lastMade", null);
            Map<String, Object> carrots = line("Juha", 1, null, null, "2 korenja", "J1");
            carrots.put("display", "1 2 korenja");
            recipe.put("recipeIngredient", list(carrots, line(null, 1, null, null, "1 l vode", "J2")));
            recipe.put("recipeInstructions", list(step("", "", "Kuhaj.", null)));
            recipe.put("settings", map("public", true, "disableAmount", true));
            return recipe;
        }
        return null;
    }

    static Map<String, Object> plan(String flavor, Map<String, String> params, boolean v331, boolean v2) {
        LocalDate start = LocalDate.parse(params.get("start_date"));
        LocalDate end = LocalDate.parse(params.get("end_date"));
        boolean week = v331 && "true".equals(params.get("include_week_entries"));
        // Newer types and fields only where the version has them.
        List<Map<String, Object>> entries = new ArrayList<>();
        entries.add(entry(11, TODAY, null, "dinner", "", "", recipe(flavor, GOLAZ, v331, v2), v331 ? 1.5 : null));
        entries.add(entry(12, TODAY, null, "side", "", "", recipe(flavor, RICET, v331, v2), v331 ? 1.0 : null));
        entries.add(entry(13, TODAY, null, "lunch", "Kosilo v službi", "", null, v331 ? 1.0 : null));
        entries.add(entry(18, TODAY, null, "lunch", "Ostanki", "", recipe(flavor, GOLAZ, v331, v2), v331 ? 1.0 : null));
        if (v331) {
            entries.add(entry(14, TODAY, null, "dessert", "Sladoled", "iz skrinje", null, 1.0));
            entries.add(entry(15, TODAY.plusDays(1), null, "snack", "", "", recipe(flavor, RICET, v331, v2), 1.0));
            entries.add(entry(16, TODAY.plusDays(2), null, "drink", "", "Kava", null, 1.0));
            entries.add(entry(19, TODAY.plusDays(9), null, "dinner", "Daleč", "", null, 1.0));
            // An empty one, with nothing to show.
            entries.add(entry(20, TODAY, null, "lunch", "", "", null, 1.0));
        }
        if (week) entries.add(entry(17, LocalDate.of(2026, 11, 16), LocalDate.of(2026, 11, 22), "dinner", "Juha za ves teden", "", null, 1.0));
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> entry : entries) {
            LocalDate date = LocalDate.parse(Json.text(entry.get("date")));
            Object until = entry.get("endDate");
            boolean reaches = !date.isBefore(start) || (until != null && !LocalDate.parse((String) until).isBefore(start));
            if (!date.isAfter(end) && reaches) items.add(entry);
        }
        // Newest first, as Mealie orders it by default.
        Collections.reverse(items);
        return map("page", 1, "per_page", -1, "total", items.size(), "total_pages", 1, "items", items, "next", null, "previous", null);
    }

    static Map<String, Object> entry(int id, LocalDate date, LocalDate until, String type, String title, String text, Map<String, Object> recipe, Double scale) {
        Map<String, Object> entry = map("date", date.toString(), "entryType", type, "title", title, "text", text, "recipeId", recipe == null ? null : recipe.get("id"),
            "id", id, "groupId", "0a1b2c3d-4e5f-4061-8172-93a4b5c6d7e8", "userId", "9e8d7c6b-5a49-4382-9172-6a5b4c3d2e1f", "householdId", "5d4c3b2a-1908-4f7e-8d6c-5b4a39281706",
            "recipe", recipe == null ? null : summary(recipe));
        if (scale != null) entry.put("recipeScale", scale);
        if (until != null) entry.put("endDate", until.toString());
        return entry;
    }

    static Map<String, Object> line(String title, Number quantity, Map<String, Object> unit, Map<String, Object> food, String note, String reference) {
        StringBuilder display = new StringBuilder();
        if (quantity != null && quantity.doubleValue() > 0) display.append(quantity).append(' ');
        if (unit != null) display.append(unit.get("name")).append(' ');
        if (food != null) display.append(food.get("name")).append(' ');
        display.append(note);
        return map("quantity", quantity, "unit", unit, "food", food, "note", note, "isFood", food != null, "disableAmount", false, "display", display.toString().trim(),
            "title", title, "originalText", null, "referenceId", reference, "referencedRecipe", null);
    }

    static Map<String, Object> withRecipe(Map<String, Object> line, String name) {
        line.put("referencedRecipe", map("id", "5e6f7a8b-9c0d-4e1f-8a2b-3c4d5e6f7a8b", "name", name, "slug", "polenta"));
        return line;
    }

    static Map<String, Object> unit(String name, String plural, String abbreviation, String pluralAbbreviation, boolean useAbbreviation) {
        return map("id", UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)).toString(), "name", name, "pluralName", plural, "description", "", "fraction", true,
            "abbreviation", abbreviation, "pluralAbbreviation", pluralAbbreviation, "useAbbreviation", useAbbreviation);
    }

    static Map<String, Object> food(String name, String plural) {
        return map("id", UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)).toString(), "name", name, "pluralName", plural, "description", "");
    }

    static Map<String, Object> step(String title, String summary, String text, Integer seconds, String... references) {
        List<Object> uses = new ArrayList<>();
        for (String reference : references) uses.add(map("referenceId", reference));
        Map<String, Object> step = map("id", UUID.randomUUID().toString(), "title", title, "summary", summary, "text", text, "ingredientReferences", uses);
        if (seconds != null) step.put("durationSeconds", seconds);
        return step;
    }

    static Map<String, Object> map(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put((String) pairs[i], pairs[i + 1]);
        return map;
    }

    static List<Object> list(Object... items) {
        return new ArrayList<>(Arrays.asList(items));
    }

    static String fold(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).trim();
    }

    static Map<String, String> params(String query) throws IOException {
        Map<String, String> params = new HashMap<>();
        if (query == null) return params;
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            if (equals < 0) continue;
            params.put(pair.substring(0, equals), URLDecoder.decode(pair.substring(equals + 1), "UTF-8"));
        }
        return params;
    }

    static void send(HttpExchange exchange, int status, String text, String unused) throws IOException {
        boolean isJson = text.startsWith("{") || text.startsWith("[");
        sendAs(exchange, status, text, isJson ? "application/json" : "text/html");
    }

    static void sendAs(HttpExchange exchange, int status, String text, String type) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", type);
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
