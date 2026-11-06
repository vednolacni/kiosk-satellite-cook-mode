// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Everything the views say, in English or Slovenian, with Slovenian's four plural forms, for Tandoor or Norish. */
final class Strings {
    final Locale locale;
    final boolean decimalComma;
    final String today;
    final String tomorrow;
    final String plan;
    final String nothingToday;
    final String recipes;
    final String searchHint;
    final String recent;
    final String all;
    final String loadMore;
    final String noRecipes;
    final String retry;
    final String notConfigured;
    final String ingredients;
    final String prep;
    final String waiting;
    final String startCooking;
    /** Takes the step number. */
    final String continueAt;
    final String openInApp;
    final String noSteps;
    final String back;
    final String close;
    /** Takes the time. */
    final String readyAround;
    final String nextStep;
    final String finish;
    final String read;
    final String forThisStep;
    final String enjoy;
    final String logCooked;
    final String logged;
    final String backToPlan;
    final String endCooking;
    final String timers;
    final String newTimer;
    final String timer;
    final String stop;
    final String pause;
    final String resume;
    final String cancel;
    final String tooManyTimers;
    final String timeIsUp;
    final String cooking;
    final String problemAuth;
    final String problemHost;
    final String problemNetwork;
    final String problemOther;
    final String recipeMissing;
    final String stepWord;
    final String loading;
    final String overnight;
    /** Takes the recipe's own servings. */
    final String fixedNumbers;
    /** Takes the recipe's name and the step it is at. */
    final String replaceRecipe;
    final String replace;
    /** Takes the recipe's name and the step it is at. */
    final String endAsk;
    final String problemReadOnly;
    final String problemNotApi;
    final String problemLocalName;
    final String problemTls;
    final String space;
    final String search;
    final String problemPrivate;
    final String problemLimit;
    private final DateTimeFormatter longDate;
    private final DateTimeFormatter shortDate;
    /** Norish's meal slots in this language, by the name Norish sends. Empty where they stay as they come. */
    private final Map<String, String> slots;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    /** Words in braces name the app, its key and its recent list. They are filled in for each app. */
    private static final String[] ENGLISH_WORDS = {
        "Today", "Tomorrow", "Meal plan", "Nothing planned for today.", "Recipes", "Search recipes", "{recent}", "All",
        "Load more", "No recipes found.", "Try again", "Set the server address and the API {key} in the plugin settings.",
        "Ingredients", "Prep", "Waiting", "Start cooking", "Continue at step %d", "Open {in app}",
        "This recipe has no steps.", "Back", "Close", "Ready around %s", "Next step", "Finish", "Read", "For this step",
        "Enjoy your meal!", "Record {in app} as cooked", "Recorded {in app}.", "Back to the meal plan", "End cooking",
        "Timers", "New timer", "Timer", "Stop", "Pause", "Resume", "Cancel", "At most 6 timers can run.", "Time is up", "Cooking",
        "{app} refused the API {key}. {new key}",
        "{app} refused this address. Use the address you open it with in a browser.",
        "The recipe server cannot be reached.", "The recipe server had a problem.", "This recipe is no longer available.", "Step", "Loading…",
        "Overnight", "Amounts in the text are for %s.", "%s is at step %d. Switch recipes?", "Switch", "%s is at step %d. End cooking?",
        "The API {key} can only read, so {app} cannot record this.",
        "This address does not lead to {app's} API. A login page may be in front of it.",
        "This tablet cannot find the server by that name. Android before version 12 cannot resolve .local names, so use the IP address.",
        "The secure connection failed. The tablet may not trust the certificate or know its TLS version.",
        "Space", "Search",
        "This recipe is private and not shared with the {key's} user.",
        "This API {key} has made its 500 requests for the hour, so {app} will not answer it for a while. Give each tablet its own {key}.",
    };

    private static final String[] SLOVENIAN_WORDS = {
        "Danes", "Jutri", "Jedilnik", "Za danes ni načrtovanih obrokov.", "Recepti", "Išči recepte", "{recent}", "Vsi",
        "Naloži več", "Ni receptov.", "Poskusi znova", "V nastavitvah vtičnika vpiši naslov strežnika in API {key}.",
        "Sestavine", "Priprava", "Čakanje", "Začni kuhati", "Nadaljuj pri koraku %d", "Odpri {in app}",
        "Ta recept nima korakov.", "Nazaj", "Zapri", "Pripravljeno okoli %s", "Naslednji korak", "Končaj", "Preberi", "Za ta korak",
        "Dober tek!", "Zabeleži {in app} kot skuhano", "Zabeleženo {in app}.", "Nazaj na jedilnik", "Končaj kuhanje",
        "Časovniki", "Nov časovnik", "Časovnik", "Ustavi", "Premor", "Nadaljuj", "Prekliči", "Teče lahko največ 6 časovnikov.", "Čas je potekel", "Kuhanje",
        "{app} je zavrnil API {key}. {new key}",
        "{app} je zavrnil ta naslov. Uporabi naslov, s katerim ga odpreš v brskalniku.",
        "Strežnik z recepti ni dosegljiv.", "Strežnik z recepti ima težavo.", "Ta recept ni več na voljo.", "Korak", "Nalagam …",
        "Čez noč", "Količine v besedilu veljajo za %s.", "Recept »%s« je pri koraku %d. Zamenjam recept?", "Zamenjaj", "Recept »%s« je pri koraku %d. Končam kuhanje?",
        "API {key} je samo za branje, zato {app} tega ne more zabeležiti.",
        "Ta naslov ne vodi do {app's} API-ja. Morda je pred njim prijavna stran.",
        "Tablica strežnika s tem imenom ne najde. Android pred različico 12 ne razume imen .local, zato uporabi naslov IP.",
        "Varna povezava ni uspela. Tablica morda ne zaupa certifikatu ali ne pozna njegove različice TLS.",
        "Presledek", "Išči",
        "Ta recept je zaseben in ni deljen z uporabnikom tega {key's}.",
        "Ta API {key} je to uro porabil svojih 500 zahtev, zato mu {app} nekaj časa ne bo odgovarjal. Vsaka tablica naj ima svoj {key}.",
    };

    static final Strings ENGLISH = english(Settings.App.TANDOOR);
    static final Strings SLOVENIAN = slovenian(Settings.App.TANDOOR);
    private static final Strings ENGLISH_NORISH = english(Settings.App.NORISH);
    private static final Strings SLOVENIAN_NORISH = slovenian(Settings.App.NORISH);

    private static Strings english(Settings.App app) {
        boolean norish = app == Settings.App.NORISH;
        Map<String, String> words = new LinkedHashMap<>();
        words.put("{app}", app.title);
        words.put("{in app}", "in " + app.title);
        words.put("{app's}", app.title + "'s");
        words.put("{key}", norish ? "key" : "token");
        words.put("{key's}", norish ? "key's" : "token's");
        words.put("{new key}", norish ? "Create a new one under Settings > User > API keys." : "Create a new one under Settings > API.");
        words.put("{recent}", norish ? "Newest" : "Recently opened");
        return new Strings(Locale.ENGLISH, false, "EEEE, MMMM d", "EEEE, MMM d", filled(ENGLISH_WORDS, words), Collections.<String, String>emptyMap());
    }

    private static Strings slovenian(Settings.App app) {
        boolean norish = app == Settings.App.NORISH;
        Map<String, String> words = new LinkedHashMap<>();
        words.put("{app}", app.title);
        words.put("{in app}", norish ? "v Norishu" : "v Tandoorju");
        words.put("{app's}", norish ? "Norishevega" : "Tandoorjevega");
        words.put("{key}", norish ? "ključ" : "žeton");
        words.put("{key's}", norish ? "ključa" : "žetona");
        // Norish does not speak Slovenian, so its menu keeps its English names.
        words.put("{new key}", norish ? "Ustvari novega v Norishu pod Settings > User > API keys." : "Ustvari novega v Nastavitvah > API.");
        words.put("{recent}", norish ? "Najnovejši" : "Nazadnje odprti");
        Map<String, String> slots = new HashMap<>();
        if (norish) {
            slots.put("Breakfast", "Zajtrk");
            slots.put("Lunch", "Kosilo");
            slots.put("Dinner", "Večerja");
            slots.put("Snack", "Prigrizek");
        }
        return new Strings(new Locale("sl"), true, "EEEE, d. MMMM", "EEEE, d. M.", filled(SLOVENIAN_WORDS, words), slots);
    }

    private static String[] filled(String[] templates, Map<String, String> words) {
        String[] out = new String[templates.length];
        for (int i = 0; i < templates.length; i++) {
            String text = templates[i];
            for (Map.Entry<String, String> word : words.entrySet()) text = text.replace(word.getKey(), word.getValue());
            out[i] = text;
        }
        return out;
    }

    private Strings(Locale locale, boolean decimalComma, String longDate, String shortDate, String[] words, Map<String, String> slots) {
        this.locale = locale;
        this.decimalComma = decimalComma;
        this.longDate = DateTimeFormatter.ofPattern(longDate, locale);
        this.shortDate = DateTimeFormatter.ofPattern(shortDate, locale);
        this.slots = slots;
        int i = 0;
        today = words[i++];
        tomorrow = words[i++];
        plan = words[i++];
        nothingToday = words[i++];
        recipes = words[i++];
        searchHint = words[i++];
        recent = words[i++];
        all = words[i++];
        loadMore = words[i++];
        noRecipes = words[i++];
        retry = words[i++];
        notConfigured = words[i++];
        ingredients = words[i++];
        prep = words[i++];
        waiting = words[i++];
        startCooking = words[i++];
        continueAt = words[i++];
        openInApp = words[i++];
        noSteps = words[i++];
        back = words[i++];
        close = words[i++];
        readyAround = words[i++];
        nextStep = words[i++];
        finish = words[i++];
        read = words[i++];
        forThisStep = words[i++];
        enjoy = words[i++];
        logCooked = words[i++];
        logged = words[i++];
        backToPlan = words[i++];
        endCooking = words[i++];
        timers = words[i++];
        newTimer = words[i++];
        timer = words[i++];
        stop = words[i++];
        pause = words[i++];
        resume = words[i++];
        cancel = words[i++];
        tooManyTimers = words[i++];
        timeIsUp = words[i++];
        cooking = words[i++];
        problemAuth = words[i++];
        problemHost = words[i++];
        problemNetwork = words[i++];
        problemOther = words[i++];
        recipeMissing = words[i++];
        stepWord = words[i++];
        loading = words[i++];
        overnight = words[i++];
        fixedNumbers = words[i++];
        replaceRecipe = words[i++];
        replace = words[i++];
        endAsk = words[i++];
        problemReadOnly = words[i++];
        problemNotApi = words[i++];
        problemLocalName = words[i++];
        problemTls = words[i++];
        space = words[i++];
        search = words[i++];
        problemPrivate = words[i++];
        problemLimit = words[i++];
    }

    /** The Language setting's choice, for the recipe app. Auto follows the device. */
    static Strings of(String language, Settings.App app) {
        boolean norish = app == Settings.App.NORISH;
        boolean slovenian = "Slovenščina".equals(language) || (!"English".equals(language) && "sl".equals(Locale.getDefault().getLanguage()));
        if (slovenian) return norish ? SLOVENIAN_NORISH : SLOVENIAN;
        return norish ? ENGLISH_NORISH : ENGLISH;
    }

    /** A meal's slot as this language says it. Norish's own four are translated, and anything else stays as it is. */
    String slot(String name) {
        String own = slots.get(name);
        return own == null ? name : own;
    }

    /** The language tag read-aloud speaks. */
    Locale voiceLocale() {
        return locale;
    }

    /** Today, Tomorrow or the weekday with its date. */
    String day(LocalDate date, LocalDate now) {
        if (date.equals(now)) return today;
        if (date.equals(now.plusDays(1))) return tomorrow;
        return capitalized(shortDate.format(date));
    }

    /** The header's date, such as Wednesday, October 28. */
    String date(LocalDate date) {
        return capitalized(longDate.format(date));
    }

    String time(LocalTime time) {
        return TIME.format(time);
    }

    /** 45 min, 2 h or 1 h 30 min. */
    String duration(int minutes) {
        if (minutes < 60) return minutes + " min";
        int rest = minutes % 60;
        return minutes / 60 + " h" + (rest == 0 ? "" : " " + rest + " min");
    }

    /** "4 servings" or, in Slovenian, 1 porcija, 2 porciji, 3 porcije, 5 porcij. A recipe's own word replaces it. */
    String servings(double count, String own) {
        String number = Amounts.format(count, decimalComma);
        if (number.isEmpty()) number = "0";
        if (own != null && !own.isEmpty()) return number + " " + own;
        boolean whole = count == Math.rint(count);
        long n = Math.round(count);
        if (decimalComma) {
            long hundred = Math.abs(n) % 100;
            String word;
            if (!whole) word = "porcije";
            else if (hundred == 1) word = "porcija";
            else if (hundred == 2) word = "porciji";
            else if (hundred == 3 || hundred == 4) word = "porcije";
            else word = "porcij";
            return number + " " + word;
        }
        return number + (whole && n == 1 ? " serving" : " servings");
    }

    /** The same after "for": in Slovenian, 1 porcijo. */
    String servingsFor(double count, String own) {
        String text = servings(count, own);
        return text.endsWith(" porcija") ? text.substring(0, text.length() - 1) + "o" : text;
    }

    private String capitalized(String text) {
        return text.isEmpty() ? text : text.substring(0, 1).toUpperCase(locale) + text.substring(1);
    }
}
