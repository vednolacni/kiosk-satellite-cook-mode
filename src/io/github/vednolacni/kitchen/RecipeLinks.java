// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.kitchen;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where a meal opens, without knowing any recipe app: a link in its event, then the recipe search address with the
 * dish filled in, then the app's home page.
 */
final class RecipeLinks {
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"'()\\[\\]{}|\\\\^`]+", Pattern.CASE_INSENSITIVE);

    private RecipeLinks() {}

    /** The meal's own recipe page, or null when it has none. */
    static String recipe(Meal meal, Settings settings) {
        String link = first(meal.description);
        if (link == null) link = first(meal.location);
        if (link != null) return link;
        String dish = meal.dish(settings.splitTitles);
        if (!dish.isEmpty() && validTemplate(settings.searchUrl)) return settings.searchUrl.replace("{title}", encode(dish));
        return null;
    }

    /** Where a tap on the meal goes: its recipe page or the app's home page. Null when neither is set. */
    static String target(Meal meal, Settings settings) {
        String recipe = recipe(meal, settings);
        return recipe != null ? recipe : home(settings);
    }

    /** The recipe app's home page, or null when it is not set. */
    static String home(Settings settings) {
        return valid(settings.appUrl) ? settings.appUrl : null;
    }

    /** The first http or https address KS can open in some text, without the punctuation that ends a sentence. */
    static String first(String text) {
        if (text == null || text.isEmpty()) return null;
        Matcher matcher = URL.matcher(text);
        while (matcher.find()) {
            String url = matcher.group();
            int end = url.length();
            while (end > 0 && ".,;:!?".indexOf(url.charAt(end - 1)) >= 0) end--;
            url = url.substring(0, end);
            if (valid(url)) return url;
        }
        return null;
    }

    /**
     * The addresses KS opens as a page: http or https with a host and no user name or password. Hosts may hold
     * underscores, as container names do.
     */
    static boolean valid(String url) {
        if (url == null || url.isEmpty() || url.length() > 2048) return false;
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException invalid) {
            return false;
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return false;
        String authority = uri.getRawAuthority();
        return authority != null && !authority.isEmpty() && !authority.contains("@") && !authority.startsWith(":");
    }

    /** A search address with {title} where the dish goes. */
    static boolean validTemplate(String template) {
        return template != null && template.contains("{title}") && valid(template.replace("{title}", "dish"));
    }

    static String encode(String text) {
        try {
            return URLEncoder.encode(text, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
