// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/** Ingredient amounts the way a cook reads them: ½ rather than 0.5, 438 rather than 437.5. */
final class Amounts {
    private static final double[] PARTS = {0, 1.0 / 8, 1.0 / 4, 1.0 / 3, 1.0 / 2, 2.0 / 3, 3.0 / 4, 7.0 / 8, 1};
    private static final String[] GLYPHS = {"", "⅛", "¼", "⅓", "½", "⅔", "¾", "⅞", ""};
    /** How close an amount must be to a common fraction to be written as one. */
    private static final double TOLERANCE = 0.02;

    private Amounts() {}

    /** The amount scaled from one number of servings to another. A missing or zero base leaves it as it is. */
    static double scale(double amount, double baseServings, double servings) {
        if (baseServings <= 0 || servings <= 0) return amount;
        return amount * servings / baseServings;
    }

    /** The same with a decimal comma where the language writes one, such as 2,5 in Slovenian. */
    static String format(double amount, boolean decimalComma) {
        String text = format(amount);
        return decimalComma ? text.replace('.', ',') : text;
    }

    /** A positive amount as text. Small amounts become fractions, large ones lose their decimals. */
    static String format(double amount) {
        if (Double.isNaN(amount) || Double.isInfinite(amount) || amount <= 0) return "";
        if (amount >= 100) return Long.toString(Math.round(amount));
        if (amount >= 10) return trimmed(amount, 1);
        long whole = (long) Math.floor(amount);
        double rest = amount - whole;
        for (int i = 0; i < PARTS.length; i++) {
            if (Math.abs(rest - PARTS[i]) <= TOLERANCE) {
                if (i == PARTS.length - 1) return Long.toString(whole + 1);
                // A little yeast is still some: 0.02 kg must not read as 0 kg.
                if (i == 0 && whole == 0) return new BigDecimal(amount).round(new MathContext(2)).stripTrailingZeros().toPlainString();
                if (i == 0) return Long.toString(whole);
                return (whole == 0 ? "" : Long.toString(whole)) + GLYPHS[i];
            }
        }
        return trimmed(amount, 2);
    }

    /** Servings as text: 4, 2.5 or 1½. */
    static String servings(double servings) {
        String text = format(servings);
        return text.isEmpty() ? "0" : text;
    }

    private static String trimmed(double amount, int decimals) {
        BigDecimal value = BigDecimal.valueOf(amount).setScale(decimals, RoundingMode.HALF_UP).stripTrailingZeros();
        return value.scale() <= 0 ? value.setScale(0).toPlainString() : value.toPlainString();
    }
}
