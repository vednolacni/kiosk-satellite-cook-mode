// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The manifest against the code: nothing declared that the plugin does not handle, and nothing used that is not declared. */
public final class ManifestTest {
    static final String SOURCE = "src/io/github/vednolacni/cookmode/";

    public static void main(String[] args) throws Exception {
        Map<String, Object> manifest = Json.object(Json.parse(read("kiosk-satellite-plugin.json")));
        String cook = read(SOURCE + "Cook.java");
        String settings = read(SOURCE + "Settings.java");

        // Every command runs, in any state.
        CookTest.fresh(CookTest.settings());
        CookTest.net.runAll();
        for (Object command : Json.list(manifest.get("commands"))) CookTest.cook.execute(Json.text(Json.object(command).get("id")));
        CookTest.net.runAll();

        // Every trigger fired is declared, and every declared one fires.
        Set<String> declared = ids(manifest.get("triggers"));
        assert declared.equals(found(cook, "fire\\(\"(\\w+)\"\\)")) : declared;
        assert declared.size() <= 20;

        // Every setting is read, and everything read is a setting.
        Set<String> keys = new LinkedHashSet<>();
        for (Object setting : Json.list(manifest.get("settings"))) keys.add(Json.text(Json.object(setting).get("key")));
        Set<String> read = found(settings, "values\\.get\\(\"(\\w+)\"\\)");
        read.addAll(found(cook, "values\\.get\\(\"(\\w+)\"\\)"));
        // Only the settings page uses it, to keep a long-unused field out of the way.
        read.add("showAdvanced");
        assert keys.equals(read) : "Settings " + keys + " read " + read;

        // Every button is handled.
        for (String button : ids(manifest.get("buttons"))) assert cook.contains("\"" + button + "\"") : button;

        // KS's limits, which it would otherwise only report when the plugin is installed.
        assert Json.list(manifest.get("settings")).size() <= 50 && Json.list(manifest.get("commands")).size() <= 20;
        assert Json.text(manifest.get("description")).length() <= 1000;
        assert Files.exists(Paths.get("assets", Json.text(manifest.get("icon"))));
        Set<String> earlier = new HashSet<>();
        Set<String> secrets = new HashSet<>();
        for (Object item : Json.list(manifest.get("settings"))) {
            Map<String, Object> setting = Json.object(item);
            String key = Json.text(setting.get("key"));
            assert Json.text(setting.get("title")).length() <= 80 && Json.text(setting.get("description")).length() <= 400 : key;
            Map<String, Object> when = Json.object(setting.get("visibleWhen"));
            if (!when.isEmpty()) {
                String target = Json.text(when.get("key"));
                assert earlier.contains(target) && !secrets.contains(target) : key + " depends on " + target;
            }
            if ("select".equals(setting.get("type"))) {
                assert Json.list(setting.get("options")).contains(setting.get("default")) : key;
            }
            if ("secret".equals(setting.get("type"))) {
                assert "".equals(setting.get("default")) : key;
                secrets.add(key);
            }
            earlier.add(key);
        }
        System.out.println("ManifestTest passed");
    }

    static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    static Set<String> ids(Object items) {
        Set<String> ids = new LinkedHashSet<>();
        for (Object item : Json.list(items)) ids.add(Json.text(Json.object(item).get("id")));
        return ids;
    }

    static Set<String> found(String text, String pattern) {
        Set<String> found = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile(pattern).matcher(text);
        while (matcher.find()) found.add(matcher.group(1));
        return found;
    }
}
