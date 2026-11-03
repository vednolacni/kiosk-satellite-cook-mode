// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The letters for searching, drawn by the plugin. A kiosk may have no Android keyboard, or one without Č, Š and Ž, and
 * opening one resizes the overlay on a tablet that redraws slowly. Main thread only.
 */
final class Keypad {
    /** What the keys do. */
    interface Keys {
        void typed(String letter);
        void erase();
        void close();
        void search();
    }

    private static final String[][] QWERTZ = {
        {"Q", "W", "E", "R", "T", "Z", "U", "I", "O", "P", "Š"},
        {"A", "S", "D", "F", "G", "H", "J", "K", "L", "Č", "Ž"},
        {"Y", "X", "C", "V", "B", "N", "M"},
    };
    private static final String[][] QWERTY = {
        {"Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P", "Š"},
        {"A", "S", "D", "F", "G", "H", "J", "K", "L", "Č", "Ž"},
        {"Z", "X", "C", "V", "B", "N", "M"},
    };
    private static final int GAP_DP = 4;

    private final Parts parts;
    private final Keys keys;
    private final int height;
    private final LinearLayout view;

    Keypad(Parts parts, Strings strings, int keyHeightDp, Keys keys) {
        this.parts = parts;
        this.keys = keys;
        height = parts.px(keyHeightDp);
        view = parts.column();
        view.setPadding(0, parts.px(GAP_DP), 0, parts.px(GAP_DP));
        view.setVisibility(View.GONE);
        String[][] rows = strings.decimalComma ? QWERTZ : QWERTY;
        view.addView(letters(rows[0]), rowAt());
        view.addView(letters(rows[1]), rowAt());
        LinearLayout third = letters(rows[2]);
        ImageView erase = parts.iconButton("mdi:backspace-outline", strings.back, "onSurface", keyHeightDp, () -> {});
        erase.setBackground(parts.ripple(parts.theme().color("surfaceContainerHighest"), parts.theme().radiusControl()));
        parts.repeating(erase, keys::erase);
        // The last letters and the erase key fill the row like the eleven above them.
        third.addView(erase, keyAt(4, true));
        view.addView(third, rowAt());
        LinearLayout last = parts.row();
        last.addView(word(strings.close, false, keys::close), keyAt(3, false));
        last.addView(word(strings.space, false, () -> keys.typed(" ")), keyAt(5, true));
        last.addView(word(strings.search, true, keys::search), keyAt(3, true));
        view.addView(last, rowAt());
    }

    View view() {
        return view;
    }

    boolean open() {
        return view.getVisibility() == View.VISIBLE;
    }

    void show(boolean open) {
        view.setVisibility(open ? View.VISIBLE : View.GONE);
    }

    private LinearLayout letters(String[] letters) {
        LinearLayout row = parts.row();
        for (int i = 0; i < letters.length; i++) {
            String letter = letters[i];
            row.addView(word(letter, false, () -> keys.typed(letter)), keyAt(1, i > 0));
        }
        return row;
    }

    private TextView word(String label, boolean filled, Runnable action) {
        TextView key = parts.text(label, 19, 500, filled ? "onPrimary" : "onSurface", 1);
        key.setGravity(Gravity.CENTER);
        key.setBackground(parts.ripple(filled ? parts.theme().color("primary") : parts.theme().color("surfaceContainerHigh"), parts.theme().radiusControl()));
        Parts.onTap(key, action);
        return key;
    }

    private LinearLayout.LayoutParams keyAt(float weight, boolean gap) {
        LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(0, height, weight);
        if (gap) at.setMarginStart(parts.px(GAP_DP));
        return at;
    }

    private LinearLayout.LayoutParams rowAt() {
        LinearLayout.LayoutParams at = parts.wide();
        at.topMargin = parts.px(GAP_DP);
        return at;
    }
}
