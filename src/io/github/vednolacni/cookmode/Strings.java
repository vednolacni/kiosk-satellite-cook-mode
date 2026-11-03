// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Everything the views say, in English or Slovenian, with Slovenian's four plural forms. */
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
    private final DateTimeFormatter longDate;
    private final DateTimeFormatter shortDate;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    static final Strings ENGLISH = new Strings(Locale.ENGLISH, false, "EEEE, MMMM d", "EEEE, MMM d", new String[] {
        "Today", "Tomorrow", "Meal plan", "Nothing planned for today.", "Recipes", "Search recipes", "Recently opened", "All",
        "Load more", "No recipes found.", "Try again", "Set the server address and the API token in the plugin settings.",
        "Ingredients", "Prep", "Waiting", "Start cooking", "Continue at step %d", "Open in Tandoor",
        "This recipe has no steps.", "Back", "Close", "Ready around %s", "Next step", "Finish", "Read", "For this step",
        "Enjoy your meal!", "Record in Tandoor as cooked", "Recorded in Tandoor.", "Back to the meal plan", "End cooking",
        "Timers", "New timer", "Timer", "Stop", "Pause", "Resume", "Cancel", "At most 6 timers can run.", "Time is up", "Cooking",
        "Tandoor refused the API token. Create a new one under Settings > API.",
        "Tandoor refused this address. Use the address you open it with in a browser.",
        "The recipe server cannot be reached.", "The recipe server had a problem.", "This recipe is no longer available.", "Step", "Loading…",
        "Overnight", "Amounts in the text are for %s.", "%s is at step %d. Switch recipes?", "Switch", "%s is at step %d. End cooking?",
        "The API token can only read, so Tandoor cannot record this.",
        "This address does not lead to Tandoor's API. A login page may be in front of it.",
        "This tablet cannot find the server by that name. Android before version 12 cannot resolve .local names, so use the IP address.",
        "The secure connection failed. The tablet may not trust the certificate or know its TLS version.",
        "Space", "Search",
        "This recipe is private and not shared with the token's user.",
    });

    static final Strings SLOVENIAN = new Strings(new Locale("sl"), true, "EEEE, d. MMMM", "EEEE, d. M.", new String[] {
        "Danes", "Jutri", "Jedilnik", "Za danes ni načrtovanih obrokov.", "Recepti", "Išči recepte", "Nazadnje odprti", "Vsi",
        "Naloži več", "Ni receptov.", "Poskusi znova", "V nastavitvah vtičnika vpiši naslov strežnika in API žeton.",
        "Sestavine", "Priprava", "Čakanje", "Začni kuhati", "Nadaljuj pri koraku %d", "Odpri v Tandoorju",
        "Ta recept nima korakov.", "Nazaj", "Zapri", "Pripravljeno okoli %s", "Naslednji korak", "Končaj", "Preberi", "Za ta korak",
        "Dober tek!", "Zabeleži v Tandoorju kot skuhano", "Zabeleženo v Tandoorju.", "Nazaj na jedilnik", "Končaj kuhanje",
        "Časovniki", "Nov časovnik", "Časovnik", "Ustavi", "Premor", "Nadaljuj", "Prekliči", "Teče lahko največ 6 časovnikov.", "Čas je potekel", "Kuhanje",
        "Tandoor je zavrnil API žeton. Ustvari novega v Nastavitvah > API.",
        "Tandoor je zavrnil ta naslov. Uporabi naslov, s katerim ga odpreš v brskalniku.",
        "Strežnik z recepti ni dosegljiv.", "Strežnik z recepti ima težavo.", "Ta recept ni več na voljo.", "Korak", "Nalagam …",
        "Čez noč", "Količine v besedilu veljajo za %s.", "%s je pri koraku %d. Zamenjam recept?", "Zamenjaj", "%s je pri koraku %d. Končam kuhanje?",
        "API žeton je samo za branje, zato Tandoor tega ne more zabeležiti.",
        "Ta naslov ne vodi do Tandoorjevega API-ja. Morda je pred njim prijavna stran.",
        "Tablica strežnika s tem imenom ne najde. Android pred različico 12 ne razume imen .local, zato uporabi naslov IP.",
        "Varna povezava ni uspela. Tablica morda ne zaupa certifikatu ali ne pozna njegove različice TLS.",
        "Presledek", "Išči",
        "Ta recept je zaseben in ni deljen z uporabnikom tega žetona.",
    });

    private Strings(Locale locale, boolean decimalComma, String longDate, String shortDate, String[] words) {
        this.locale = locale;
        this.decimalComma = decimalComma;
        this.longDate = DateTimeFormatter.ofPattern(longDate, locale);
        this.shortDate = DateTimeFormatter.ofPattern(shortDate, locale);
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
    }

    /** The Language setting's choice. Auto follows the device. */
    static Strings of(String language) {
        if ("Slovenščina".equals(language)) return SLOVENIAN;
        if ("English".equals(language)) return ENGLISH;
        return "sl".equals(Locale.getDefault().getLanguage()) ? SLOVENIAN : ENGLISH;
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
