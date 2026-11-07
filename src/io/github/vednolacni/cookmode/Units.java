// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Names for the units Norish keeps as ids, such as tablespoon, in English and in Slovenian, which Norish itself does
 * not speak. Slovenian counts in four forms and takes a fifth after a fraction: 1 žlica, 2 žlici, 3 žlice, 5 žlic,
 * 1½ žlice. A few ids are no measure but words that go with the name, such as to_taste.
 */
final class Units {
    /** The id, English for one and for more, then Slovenian for one, two, three or four, five and more, and after a fraction. */
    private static final String[][] MEASURES = {
        {"gram", "g", "g", "g", "g", "g", "g", "g"},
        {"kilogram", "kg", "kg", "kg", "kg", "kg", "kg", "kg"},
        {"milliliter", "ml", "ml", "ml", "ml", "ml", "ml", "ml"},
        {"centiliter", "cl", "cl", "cl", "cl", "cl", "cl", "cl"},
        {"deciliter", "dl", "dl", "dl", "dl", "dl", "dl", "dl"},
        {"liter", "l", "l", "l", "l", "l", "l", "l"},
        {"centimeter", "cm", "cm", "cm", "cm", "cm", "cm", "cm"},
        {"pound", "lb", "lb", "lb", "lb", "lb", "lb", "lb"},
        {"ounce", "oz", "oz", "oz", "oz", "oz", "oz", "oz"},
        {"teaspoon", "tsp", "tsp", "žlička", "žlički", "žličke", "žličk", "žličke"},
        {"tablespoon", "tbsp", "tbsp", "žlica", "žlici", "žlice", "žlic", "žlice"},
        {"heaping_teaspoon", "heaping tsp", "heaping tsp", "zvrhana žlička", "zvrhani žlički", "zvrhane žličke", "zvrhanih žličk", "zvrhane žličke"},
        {"scant_teaspoon", "scant tsp", "scant tsp", "nepolna žlička", "nepolni žlički", "nepolne žličke", "nepolnih žličk", "nepolne žličke"},
        {"heaping_tablespoon", "heaping tbsp", "heaping tbsp", "zvrhana žlica", "zvrhani žlici", "zvrhane žlice", "zvrhanih žlic", "zvrhane žlice"},
        {"scant_tablespoon", "scant tbsp", "scant tbsp", "nepolna žlica", "nepolni žlici", "nepolne žlice", "nepolnih žlic", "nepolne žlice"},
        {"cup", "cup", "cups", "skodelica", "skodelici", "skodelice", "skodelic", "skodelice"},
        {"glass", "glass", "glasses", "kozarec", "kozarca", "kozarci", "kozarcev", "kozarca"},
        {"bowl", "bowl", "bowls", "skleda", "skledi", "sklede", "skled", "sklede"},
        {"pot", "pot", "pots", "lonec", "lonca", "lonci", "loncev", "lonca"},
        {"knife_tip", "knife tip", "knife tips", "konica noža", "konici noža", "konice noža", "konic noža", "konice noža"},
        {"pinch", "pinch", "pinches", "ščepec", "ščepca", "ščepci", "ščepcev", "ščepca"},
        {"generous_pinch", "generous pinch", "generous pinches", "dober ščepec", "dobra ščepca", "dobri ščepci", "dobrih ščepcev", "dobrega ščepca"},
        {"dash", "dash", "dashes", "kanček", "kančka", "kančki", "kančkov", "kančka"},
        {"drop", "drop", "drops", "kapljica", "kapljici", "kapljice", "kapljic", "kapljice"},
        {"splash", "splash", "splashes", "curek", "curka", "curki", "curkov", "curka"},
        {"small_splash", "small splash", "small splashes", "manjši curek", "manjša curka", "manjši curki", "manjših curkov", "manjšega curka"},
        {"large_splash", "large splash", "large splashes", "večji curek", "večja curka", "večji curki", "večjih curkov", "večjega curka"},
        {"drizzle", "drizzle", "drizzles", "curek", "curka", "curki", "curkov", "curka"},
        {"handful", "handful", "handfuls", "pest", "pesti", "pesti", "pesti", "pesti"},
        {"scoop", "scoop", "scoops", "kepica", "kepici", "kepice", "kepic", "kepice"},
        {"capful", "capful", "capfuls", "pokrovček", "pokrovčka", "pokrovčki", "pokrovčkov", "pokrovčka"},
        {"piece", "piece", "pieces", "kos", "kosa", "kosi", "kosov", "kosa"},
        {"chunk", "chunk", "chunks", "kos", "kosa", "kosi", "kosov", "kosa"},
        {"slice", "slice", "slices", "rezina", "rezini", "rezine", "rezin", "rezine"},
        {"round_slice", "round slice", "round slices", "kolut", "koluta", "koluti", "kolutov", "koluta"},
        {"ring", "ring", "rings", "kolobar", "kolobarja", "kolobarji", "kolobarjev", "kolobarja"},
        {"strip", "strip", "strips", "trak", "trakova", "trakovi", "trakov", "traku"},
        {"wedge", "wedge", "wedges", "krhelj", "krhlja", "krhlji", "krhljev", "krhlja"},
        {"cube", "cube", "cubes", "kocka", "kocki", "kocke", "kock", "kocke"},
        {"ball", "ball", "balls", "kroglica", "kroglici", "kroglice", "kroglic", "kroglice"},
        {"fillet", "fillet", "fillets", "file", "fileja", "fileji", "filejev", "fileja"},
        {"stick", "stick", "sticks", "palčka", "palčki", "palčke", "palčk", "palčke"},
        {"bar", "bar", "bars", "tablica", "tablici", "tablice", "tablic", "tablice"},
        {"sheet", "sheet", "sheets", "list", "lista", "listi", "listov", "lista"},
        {"roll", "roll", "rolls", "zvitek", "zvitka", "zvitki", "zvitkov", "zvitka"},
        {"clove", "clove", "cloves", "strok", "stroka", "stroki", "strokov", "stroka"},
        {"bulb", "bulb", "bulbs", "glavica", "glavici", "glavice", "glavic", "glavice"},
        {"head", "head", "heads", "glava", "glavi", "glave", "glav", "glave"},
        {"leaf", "leaf", "leaves", "list", "lista", "listi", "listov", "lista"},
        {"sprig", "sprig", "sprigs", "vejica", "vejici", "vejice", "vejic", "vejice"},
        {"bunch", "bunch", "bunches", "šopek", "šopka", "šopki", "šopkov", "šopka"},
        {"stalk", "stalk", "stalks", "steblo", "stebli", "stebla", "stebel", "stebla"},
        {"ear", "ear", "ears", "storž", "storža", "storži", "storžev", "storža"},
        {"serving", "serving", "servings", "porcija", "porciji", "porcije", "porcij", "porcije"},
        {"dozen", "dozen", "dozen", "ducat", "ducata", "ducati", "ducatov", "ducata"},
        {"pack", "pack", "packs", "zavitek", "zavitka", "zavitki", "zavitkov", "zavitka"},
        {"sleeve", "sleeve", "sleeves", "zavitek", "zavitka", "zavitki", "zavitkov", "zavitka"},
        {"bag", "bag", "bags", "vrečka", "vrečki", "vrečke", "vrečk", "vrečke"},
        {"pouch", "pouch", "pouches", "vrečka", "vrečki", "vrečke", "vrečk", "vrečke"},
        {"box", "box", "boxes", "škatla", "škatli", "škatle", "škatel", "škatle"},
        {"can", "can", "cans", "pločevinka", "pločevinki", "pločevinke", "pločevink", "pločevinke"},
        {"jar", "jar", "jars", "kozarec", "kozarca", "kozarci", "kozarcev", "kozarca"},
        {"bottle", "bottle", "bottles", "steklenica", "steklenici", "steklenice", "steklenic", "steklenice"},
        {"tub", "tub", "tubs", "lonček", "lončka", "lončki", "lončkov", "lončka"},
    };

    /** Units that are words about the line, which needs no amount: the id, English and Slovenian. */
    private static final String[][] WORDS = {
        {"to_taste", "to taste", "po okusu"},
        {"to_serve", "to serve", "za serviranje"},
        {"for_garnish", "for garnish", "za okras"},
        {"optional", "optional", "po želji"},
    };

    private static final Map<String, String[]> MEASURE = new HashMap<>();
    private static final Map<String, String[]> WORD = new HashMap<>();

    static {
        for (String[] row : MEASURES) MEASURE.put(row[0], row);
        for (String[] row : WORDS) WORD.put(row[0], row);
    }

    private Units() {}

    /** How many ids the plugin names. */
    static int count() {
        return MEASURE.size() + WORD.size();
    }

    /** The id as the plugin knows it, or an empty string for a unit Norish keeps as it was typed. */
    static String key(String unit) {
        String id = unit == null ? "" : unit.trim().toLowerCase(Locale.ROOT);
        return MEASURE.containsKey(id) || WORD.containsKey(id) ? id : "";
    }

    /** Words such as "to taste", or an empty string for a measure. */
    static String words(String key, boolean slovenian) {
        String[] row = WORD.get(key);
        return row == null ? "" : row[slovenian ? 2 : 1];
    }

    /** The unit's name after the amount, which is written as shown, such as 2 žlici or ½ cup. */
    static String name(String key, double amount, String shown, boolean slovenian) {
        String[] row = MEASURE.get(key);
        if (row == null) return words(key, slovenian);
        if (!slovenian) return amount < 1 || shown.equals("1") ? row[1] : row[2];
        return row[3 + slovenianForm(shown)];
    }

    /** 0 for one, 1 for two, 2 for three and four, 3 for five and more, 4 after a fraction or a decimal, as Slovenian counts. */
    static int slovenianForm(String shown) {
        if (shown.isEmpty() || !shown.matches("[0-9]+")) return 4;
        int hundred = Integer.parseInt(shown.length() > 2 ? shown.substring(shown.length() - 2) : shown);
        if (hundred == 1) return 0;
        if (hundred == 2) return 1;
        if (hundred == 3 || hundred == 4) return 2;
        return 3;
    }
}
