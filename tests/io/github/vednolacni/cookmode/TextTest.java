// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import java.util.*;

/** Json, Amounts, Durations and PlainText: the pure helpers every other part relies on. */
public final class TextTest {
    public static void main(String[] args) {
        json();
        amounts();
        durations();
        plainText();
        stepText();
        hints();
        settings();
        System.out.println("TextTest passed");
    }

    static Recipe.Step plain(String text, int seconds) {
        return new Recipe.Step("", text, Recipe.Format.PLAIN, Collections.<Recipe.Ingredient>emptyList(), seconds);
    }

    static void hints() {
        Recipe recipe = new Recipe("1", "Bread", "", "", 4, "", 0, 0, "", "", Arrays.asList(
            plain("Leave the dough overnight in the fridge.", 0),
            plain("Prove for 2 hours, then for 3 hours more. Bake 40 min at 220 °C, then at 180°.", 0),
            plain("Rest.", 90 * 60),
            plain("A 500 g loaf. Serve at 20 °C with 100 friends.", 0)));
        List<Hints.Hint> hints = Hints.of(recipe, Strings.ENGLISH);
        List<String> words = new ArrayList<>();
        for (Hints.Hint hint : hints) words.add(hint.text);
        assert words.equals(Arrays.asList("Overnight", "3 h", "2 h", "220 °C", "180 °C")) : words;
        assert "mdi:weather-night".equals(hints.get(0).icon) && "mdi:timer-sand".equals(hints.get(1).icon) && "mdi:thermometer".equals(hints.get(3).icon);
        assert Hints.of(new Recipe("2", "Soup", "", "", 4, "", 0, 0, "", "", Collections.singletonList(plain("Boil for 10 minutes.", 0))), Strings.SLOVENIAN).isEmpty();
        List<Hints.Hint> slovenian = Hints.of(new Recipe("3", "Kruh", "", "", 4, "", 0, 0, "", "", Collections.singletonList(plain("Testo pusti čez noč in peci v pečici na 200 stopinjah, ali pri 350F.", 0))), Strings.SLOVENIAN);
        assert "Čez noč".equals(slovenian.get(0).text) && "200 °F".equals(slovenian.get(1).text) && "350 °F".equals(slovenian.get(2).text) : slovenian.get(1).text;

        // Numbers in the words stay as written. Times and temperatures are meant to, and scaled amounts do change.
        assert Instructions.hasFixedNumbers(plain("Add 200 g flour.", 0));
        assert Instructions.hasFixedNumbers(plain("Add ½ cup of milk.", 0));
        assert !Instructions.hasFixedNumbers(plain("Bake 40 min at 220 °C until golden.", 0));
        assert !Instructions.hasFixedNumbers(plain("Mix well.", 0));
        assert !Instructions.hasFixedNumbers(new Recipe.Step("", "<p>Add <scalable-number v-bind:number=\"200.0\"></scalable-number> g and simmer 10–15 min.</p>", Recipe.Format.TANDOOR_HTML, Collections.<Recipe.Ingredient>emptyList(), 0));
        assert Instructions.hasFixedNumbers(new Recipe.Step("", "<p>Add <scalable-number v-bind:number=\"200.0\"></scalable-number> g and 2 eggs.</p>", Recipe.Format.TANDOOR_HTML, Collections.<Recipe.Ingredient>emptyList(), 0));
        assert !Instructions.hasFixedNumbers(new Recipe.Step("", "<p><plural-name singular=\"egg\" plural=\"eggs\" v-bind:amount=\"3\"></plural-name></p>", Recipe.Format.TANDOOR_HTML, Collections.<Recipe.Ingredient>emptyList(), 0));
        for (String text : new String[] {"Mleko segrej na 40 °C.", "Peci na 180-200 °C.", "Peci pri 180/160 °C.", "Pri 180 st. pečemo.", "Kuhaj 1/2 ure.", "Peci 3/4 ure na 200 stopinjah."}) {
            assert !Instructions.hasFixedNumbers(plain(text, 0)) : text;
        }

        // Each temperature has its own scale. An explicit one always wins.
        assert ovens("Preheat the oven to 350 degrees F.").equals(Collections.singletonList("350 °F"));
        assert ovens("Preheat the oven to 350 degrees Fahrenheit.").equals(Collections.singletonList("350 °F"));
        assert ovens("Pečico segrej na 180 °C (350 °F).").equals(Arrays.asList("180 °C", "350 °F"));
        assert ovens("Peci na 180-200 °C.").equals(Arrays.asList("180 °C", "200 °C"));
        assert ovens("Peci pri 180/160 °C.").equals(Arrays.asList("180 °C", "160 °C"));
        assert ovens("Pri 180 st. pečemo.").equals(Collections.singletonList("180 °C"));
        assert ovens("Bake at 200° for 10 minutes, covered at 180° c.").equals(Arrays.asList("200 °C", "180 °C")) : ovens("Bake at 200° for 10 minutes, covered at 180° c.");
        assert ovens("Bake at 180° covered.").equals(Collections.singletonList("180 °C"));
        assert ovens("Bake at 400 degrees for an hour.").equals(Collections.singletonList("400 °F"));
        assert ovens("Mleko segrej na 40 °C.").isEmpty();
    }

    static List<String> ovens(String words) {
        List<String> found = new ArrayList<>();
        for (Hints.Hint hint : Hints.of(new Recipe("9", "x", "", "", 4, "", 0, 0, "", "", Collections.singletonList(plain(words, 0))), Strings.ENGLISH)) {
            if ("mdi:thermometer".equals(hint.icon)) found.add(hint.text);
        }
        return found;
    }

    static void settings() {
        Map<String, Object> values = new HashMap<>();
        values.put("apiToken", "  Bearer tda_abc ");
        values.put("textSize", "Extra large");
        values.put("alarmTone", "Beeps");
        values.put("alarmStream", "Media");
        values.put("ringMinutes", 50.0);
        values.put("swipeSteps", false);
        values.put("dockOnTop", false);
        values.put("kioskNode", "esphome.ks-kitchen_announce");
        Settings settings = new Settings(values);
        assert "tda_abc".equals(settings.apiToken) && settings.textSize == 34 && settings.textMost == 56;
        assert settings.tone == Settings.Tone.BEEPS && settings.mediaStream && settings.ringMinutes == 10 && !settings.swipeSteps && !settings.dockOnTop;
        assert "ks_kitchen".equals(settings.kioskNode);
        Settings defaults = new Settings(new HashMap<String, Object>());
        assert defaults.apiToken.isEmpty() && defaults.textSize == 28 && defaults.textMost == 44 && defaults.tone == Settings.Tone.ALARM && !defaults.mediaStream && defaults.ringMinutes == 3 && defaults.swipeSteps && defaults.dockOnTop;
        // A line break pasted along with the token would end up in the header's error, and so in the status.
        values.put("apiToken", " tda_\nabc\r\n");
        assert "tda_abc".equals(new Settings(values).apiToken);
        assert "1 porcijo".equals(Strings.SLOVENIAN.servingsFor(1, "")) && "2 porciji".equals(Strings.SLOVENIAN.servingsFor(2, "")) && "1 serving".equals(Strings.ENGLISH.servingsFor(1, ""));
        assert "ks_a".equals(Settings.node("KS_A_announce")) && "dedo_babi".equals(Settings.slug("Dedo&babi")) && "".equals(Settings.slug("  "));
    }

    static void stepText() {
        List<StepText.Run> runs = StepText.runs("Brown the chicken for about 4 to 5 minutes per side. Rest 10 min.");
        assert runs.size() == 5 : runs.size();
        assert "Brown the chicken for about ".equals(runs.get(0).text) && !runs.get(0).timer();
        assert "4 to 5 minutes".equals(runs.get(1).text) && runs.get(1).seconds == 240;
        assert " per side. Rest ".equals(runs.get(2).text);
        // The full stop after the last duration stays with the sentence, not the chip.
        assert "Kuhaj ".equals(StepText.runs("Kuhaj 1 Std. 30 Min.").get(0).text) && "1 Std. 30 Min".equals(StepText.runs("Kuhaj 1 Std. 30 Min.").get(1).text);
        assert "10 min".equals(runs.get(3).text) && runs.get(3).seconds == 600 && ".".equals(runs.get(4).text);
        assert StepText.runs("Mix well.").size() == 1 && StepText.runs("").isEmpty() && StepText.runs(null).isEmpty();
        // The runs always add up to the text.
        StringBuilder joined = new StringBuilder();
        for (StepText.Run run : StepText.runs("Kuhaj 10–15 min. Nato 5 minut počivaj.")) joined.append(run.text);
        assert "Kuhaj 10–15 min. Nato 5 minut počivaj.".equals(joined.toString()) : joined;

        // A step's own time comes first. The same duration in its text is not offered twice.
        Recipe.Step step = new Recipe.Step("", "Bake 25 minutes, then rest 5 min.", Recipe.Format.PLAIN, Collections.<Recipe.Ingredient>emptyList(), 25 * 60);
        List<StepText.Run> timers = StepText.timers(step, step.text, "min");
        assert timers.size() == 2 && timers.get(0).seconds == 1500 && "25 min".equals(timers.get(0).text) && timers.get(1).seconds == 300;
        Recipe recipe = new Recipe("1", "Pasta", "", "", 4, "", 20, 10, "", "", Collections.singletonList(step));
        assert StepText.readyAt(1000, recipe) == 1000 + 30 * 60_000L;
        Recipe quick = new Recipe("2", "Salad", "", "", 2, "", 0, 0, "", "", Collections.<Recipe.Step>emptyList());
        assert StepText.readyAt(1000, quick) == -1;
    }

    static void json() {
        Object value = Json.parse(" {\"a\": [1, 2.5, -3e2, true, false, null], \"b\": {\"c\": \"x\\ny \\u017e \\\"q\\\"\"}, \"d\": \"\"} ");
        Map<String, Object> object = Json.object(value);
        List<Object> list = Json.list(object.get("a"));
        assert list.size() == 6 && list.get(0).equals(1.0) && list.get(1).equals(2.5) && list.get(2).equals(-300.0);
        assert Boolean.TRUE.equals(list.get(3)) && Boolean.FALSE.equals(list.get(4)) && list.get(5) == null;
        assert "x\ny ž \"q\"".equals(Json.path(value, "b", "c"));
        assert Json.path(value, "b", "missing") == null && Json.path(value, "a", "x") == null;
        assert Json.object("not an object").isEmpty() && Json.list(null).isEmpty();
        assert "12".equals(Json.text(12.0)) && "1.5".equals(Json.text(1.5)) && "".equals(Json.text(null));
        assert Json.integer(7.0, -1) == 7 && Json.integer("x", -1) == -1 && Json.number("2,5", 0) == 2.5;
        // Order is kept, and writing reads back to the same value.
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("recipe", 12);
        out.put("servings", 2.5);
        out.put("note", "Tab\tand \"quote\"\n");
        out.put("list", Arrays.asList(1, null, true));
        String written = Json.write(out);
        assert "{\"recipe\":12,\"servings\":2.5,\"note\":\"Tab\\tand \\\"quote\\\"\\n\",\"list\":[1,null,true]}".equals(written) : written;
        assert Json.object(Json.parse(written)).get("note").equals("Tab\tand \"quote\"\n");
        assert Json.list(Json.parse("[]")).isEmpty() && Json.object(Json.parse("{}")).isEmpty();
        for (String bad : new String[] {"", "{", "[1,]", "{\"a\" 1}", "tru", "01x", "\"open", "[1] 2", "{\"a\":1,}"}) {
            boolean refused = false;
            try {
                Json.parse(bad);
            } catch (IllegalArgumentException expected) {
                refused = true;
            }
            assert refused : bad;
        }
        // Deep nesting is refused instead of overflowing the stack.
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 100; i++) deep.append('[');
        boolean refused = false;
        try {
            Json.parse(deep.toString());
        } catch (IllegalArgumentException expected) {
            refused = true;
        }
        assert refused;
    }

    static void amounts() {
        assert "½".equals(Amounts.format(0.5)) && "1½".equals(Amounts.format(1.5)) && "⅓".equals(Amounts.format(1.0 / 3));
        assert "2¾".equals(Amounts.format(2.75)) && "2".equals(Amounts.format(2)) && "1".equals(Amounts.format(0.99));
        assert "0.1".equals(Amounts.format(0.1)) && "12.3".equals(Amounts.format(12.25)) && "438".equals(Amounts.format(437.5));
        assert "".equals(Amounts.format(0)) && "".equals(Amounts.format(-1)) && "".equals(Amounts.format(Double.NaN));
        assert "2½".equals(Amounts.format(2.5, true)) && "12,3".equals(Amounts.format(12.25, true)) && "0,1".equals(Amounts.format(0.1, true));
        assert Amounts.scale(200, 4, 2) == 100 && Amounts.scale(1, 0, 3) == 1;
        assert "4".equals(Amounts.servings(4)) && "1½".equals(Amounts.servings(1.5));
        // A little of something is not nothing.
        assert "0.02".equals(Amounts.format(0.02)) && "0.016".equals(Amounts.format(0.015625)) && "0,01".equals(Amounts.format(0.01, true)) : Amounts.format(0.015625);

        // A line with no food says what it has, and never two amounts.
        assert "Sol in poper".equals(new Recipe.Ingredient(false, Double.NaN, "", "", "", "", "Sol in poper", "").line(1, false));
        assert "400 g presejana".equals(new Recipe.Ingredient(false, 200, "g", "", "", "", "presejana", "").line(2, false));
        assert "200 g moke".equals(new Recipe.Ingredient(false, 200, "g", "", "", "", "", "200 g moke").line(2, false));
        assert "po okusu".equals(new Recipe.Ingredient(false, Double.NaN, "", "", "", "", "po okusu", "sol po okusu").line(1, false));
    }

    static void durations() {
        assert seconds("Bake for 25 minutes.").equals(Collections.singletonList(1500));
        assert seconds("Kuhaj 10–15 min, nato pusti stati 5 minut.").equals(Arrays.asList(600, 300));
        assert seconds("Peci 1 uro in 30 minut na 180 stopinjah.").equals(Collections.singletonList(5400));
        assert seconds("1 Std. 30 Min. ruhen lassen").equals(Collections.singletonList(5400));
        assert seconds("Simmer 1 h 15 min").equals(Collections.singletonList(4500));
        assert seconds("Počivaj ½ ure").equals(Collections.singletonList(1800));
        assert seconds("Pusti 1,5 ure").equals(Collections.singletonList(5400));
        assert seconds("Stir for 30 sec").equals(Collections.singletonList(30));
        // Not durations: temperatures, sizes, minced meat, words ending in units.
        assert seconds("350 °F, 2 cm slices, 500 g minced meat, 3 sl spoons").isEmpty();
        assert seconds("Add 2 eggs and 10 m").equals(Collections.singletonList(600));
        assert seconds("Rest for 2 days").isEmpty() && seconds(null).isEmpty();
        Durations.Found found = Durations.find("Kuhaj 10–15 min.").get(0);
        assert "10–15 min".equals(found.text) : found.text;
        assert "1 Std. 30 Min".equals(Durations.find("1 Std. 30 Min. ruhen").get(0).text);

        // Fractions, written any way.
        assert seconds("Kuhaj 1/2 ure.").equals(Collections.singletonList(1800));
        assert seconds("Peci 3/4 ure.").equals(Collections.singletonList(2700));
        assert seconds("Peci 3⁄4 ure.").equals(Collections.singletonList(2700));
        assert seconds("Bake 1 1/2 hours.").equals(Collections.singletonList(5400));
        assert seconds("Pusti 1 ½ ure.").equals(Collections.singletonList(5400));
        assert seconds("Pusti 1½ ure.").equals(Collections.singletonList(5400));
        assert seconds("Kuhaj 1/0 ure.").isEmpty();
        // A time on the clock is no length of time.
        assert seconds("2:15 h").isEmpty() && seconds("ob 18.30 h").isEmpty() && seconds("Ob 18 h postrezi.").isEmpty() && seconds("Serve at 7 h").isEmpty();
        assert seconds("Peci 2.5 h").equals(Collections.singletonList(9000));
        // Two durations stay two, across a sentence, a line or a word between them.
        assert seconds("Peci 30 minut. 10 minut pred koncem dodaj sir.").equals(Arrays.asList(1800, 600));
        assert seconds("Bake for 30 minutes. 10 minutes before the end add the cheese.").equals(Arrays.asList(1800, 600));
        assert seconds("Kuhaj 5 minut in 2 minuti počivaj.").equals(Arrays.asList(300, 120));
        assert seconds("Peci 40 min\n10 min pred koncem dodaj.").equals(Arrays.asList(2400, 600));
        assert seconds("Kuhaj 1 uro. 10 minut pred koncem dodaj.").equals(Arrays.asList(3600, 600));
        // Slovenian's other forms, and the ways of saying half an hour.
        assert seconds("Počivaj 1 uro in pol.").equals(Collections.singletonList(5400));
        assert seconds("Peci 2 uri in pol.").equals(Collections.singletonList(9000));
        assert seconds("Peci 1 uro in polovico.").equals(Collections.singletonList(3600));
        assert seconds("po 10 minutah").equals(Collections.singletonList(600)) && seconds("po 2 urah").equals(Collections.singletonList(7200)) && seconds("čez 30 sekundah").equals(Collections.singletonList(30));
        assert seconds("Pusti pol ure.").equals(Collections.singletonList(1800)) && seconds("Pusti četrt ure.").equals(Collections.singletonList(900));
        assert seconds("Pusti tri četrt ure.").equals(Collections.singletonList(2700)) && seconds("Pusti uro in pol.").equals(Collections.singletonList(5400));
        assert seconds("Pusti eno uro in pol.").equals(Collections.singletonList(5400)) && seconds("Pusti eno uro.").equals(Collections.singletonList(3600));
        assert seconds("Rest for half an hour.").equals(Collections.singletonList(1800)) && seconds("Rest for an hour and a half.").equals(Collections.singletonList(5400)) && seconds("Rest for an hour.").equals(Collections.singletonList(3600));
        assert seconds("Eine halbe Stunde ruhen, dann eine Viertelstunde.").equals(Arrays.asList(1800, 900));
        assert seconds("1h30").equals(Collections.singletonList(5400)) && seconds("1h30min").equals(Collections.singletonList(5400));
        // S and h are also Slovenian's "with" and "to".
        assert seconds("Razdeli na 4 s pomočjo žlice.").isEmpty() && seconds("Stepi 2 s sladkorjem").isEmpty();
        assert seconds("Peci 1 h pri 200 °C").equals(Collections.singletonList(3600)) && seconds("mešaj 30 s pri nizki hitrosti").equals(Collections.singletonList(30));
        assert seconds("Na 4\n\nS kuhalnico premešaj.").isEmpty() && seconds("Vzemi 2 M jajci.").isEmpty();
        // Digits a double cannot be parsed from are just text, also where the regular expressions would take them.
        assert seconds("٣ min").isEmpty() && seconds("５ min").isEmpty();
        assert "0:45".equals(Durations.clock(45)) && "12:00".equals(Durations.clock(720)) && "1:05:00".equals(Durations.clock(3900));
    }

    static List<Integer> seconds(String text) {
        List<Integer> seconds = new ArrayList<>();
        for (Durations.Found found : Durations.find(text)) seconds.add(found.seconds);
        return seconds;
    }

    static void plainText() {
        String markdown = "# Testo\r\n\r\nZmešaj **moko** in _sol_ v [skledi](https://x.y/z).\n\n\n- jajca\n* mleko\n\n![slika](https://x.y/a.jpg)<br>Pusti `10 min` počivati.\n\n---\nsnake_case_word ostane.";
        String plain = PlainText.fromMarkdown(markdown);
        assert plain.equals("Testo\n\nZmešaj moko in sol v skledi.\n\n• jajca\n• mleko\n\nPusti 10 min počivati.\n\nsnake_case_word ostane.") : plain;
        assert "Testo. Zmešaj moko in sol v skledi. jajca. mleko. Pusti 10 min počivati. snake_case_word ostane.".equals(PlainText.forSpeech(plain)) : PlainText.forSpeech(plain);
        assert "".equals(PlainText.fromMarkdown(null)) && "a < b & c".equals(PlainText.fromMarkdown("a &lt; b &amp; c"));
    }
}
