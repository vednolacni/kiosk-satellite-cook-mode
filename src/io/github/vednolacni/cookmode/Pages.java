// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The cooking view's pages: one per step, except that a long step becomes one page per paragraph. Recipes imported
 * from the web often hold the whole method in a single step, which is useless at two metres. Splitting looks only at
 * paragraph and line breaks, which do not depend on the servings, so the page count stays the same when they change.
 */
final class Pages {
    /** Shorter steps stay whole, whatever their paragraphs. */
    static final int LONG_STEP = 280;

    /** One page: part of step number step, counted from 0. */
    static final class Page {
        final int step;
        final int part;
        final int parts;

        Page(int step, int part, int parts) {
            this.step = step;
            this.part = part;
            this.parts = parts;
        }
    }

    private Pages() {}

    static List<Page> of(Recipe recipe, boolean split) {
        List<Page> pages = new ArrayList<>();
        for (int step = 0; step < recipe.steps.size(); step++) {
            int parts = split ? parts(Instructions.render(recipe.steps.get(step), 1, false)).size() : 1;
            for (int part = 0; part < parts; part++) pages.add(new Page(step, part, parts));
        }
        return pages;
    }

    /** The text of one page for these servings. */
    static String text(Recipe recipe, Page page, double factor, boolean decimalComma) {
        String whole = Instructions.render(recipe.steps.get(page.step), factor, decimalComma);
        if (page.parts <= 1) return whole;
        List<String> parts = parts(whole);
        return page.part < parts.size() ? parts.get(page.part) : whole;
    }

    /** A step's text cut at paragraphs, or at lines when it has none, or whole when it is short. */
    static List<String> parts(String text) {
        if (text.length() < LONG_STEP) return Collections.singletonList(text);
        List<String> parts = pieces(text.split("\\n\\s*\\n"));
        if (parts.size() < 2) parts = pieces(text.split("\\n"));
        return parts.size() < 2 ? Collections.singletonList(text) : parts;
    }

    private static List<String> pieces(String[] raw) {
        List<String> pieces = new ArrayList<>();
        for (String piece : Arrays.asList(raw)) {
            String trimmed = piece.trim();
            if (!trimmed.isEmpty()) pieces.add(trimmed);
        }
        return pieces;
    }
}
