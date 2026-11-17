package io.github.vednolacni.cookmode;

import java.util.HashMap;
import java.util.Map;
import me.jxl.kiosk.plugins.KsTheme;

/**
 * The panel's colors. By default they are KS's own. Warm kitchen recolors the panel after Norish's look: a deep brown or
 * cream ground, warm white cards and a herb green, with KS's typeface, icons, shapes and scale. The timer card over the
 * dashboard keeps KS's colors, since it sits among the dashboard's.
 */
final class Looks {
    private Looks() {}

    /** KS's theme as it is, or recolored for Warm kitchen. */
    static KsTheme of(KsTheme kiosk, boolean warm) {
        if (kiosk == null || !warm) return kiosk;
        // KS's density already holds the kiosk's Scale UI. px rounds, so a long length gives it back to four places.
        float density = kiosk.px(10000) / 10000f;
        return new KsTheme(kiosk.isDark(), density, warm(kiosk.isDark()), kiosk::typeface, kiosk::iconPath);
    }

    /**
     * Every Material 3 role KS sends, and success, so the panel finds each one it asks for. Text roles reach 4.5:1 on the
     * grounds they sit on and outline 3:1; white on primary is 4.77:1 in dark and 6.81:1 in light. Primary as small text on
     * the dark ground is 3.91:1, like KS's own teal, so small text is not set in it.
     */
    static Map<String, Integer> warm(boolean dark) {
        Map<String, Integer> colors = new HashMap<>();
        put(colors, dark, "surface", 0xFF1E0F07, 0xFFFBEFDA);
        put(colors, dark, "onSurface", 0xFFFAF7EE, 0xFF231C15);
        put(colors, dark, "onSurfaceVariant", 0xFFD8C8AA, 0xFF6B5E4E);
        put(colors, dark, "surfaceContainerLowest", 0xFF170A04, 0xFFFFFFFF);
        put(colors, dark, "surfaceContainerLow", 0xFF241712, 0xFFFDF5E8);
        put(colors, dark, "surfaceContainer", 0xFF271C17, 0xFFFFF9EF);
        put(colors, dark, "surfaceContainerHigh", 0xFF2A211C, 0xFFFFFCF4);
        put(colors, dark, "surfaceContainerHighest", 0xFF362B24, 0xFFF2E3CB);
        put(colors, dark, "primary", 0xFF447F52, 0xFF35653F);
        put(colors, dark, "onPrimary", 0xFFFFFFFF, 0xFFFFFFFF);
        put(colors, dark, "primaryContainer", 0xFF2C4A33, 0xFFD8E8D5);
        put(colors, dark, "onPrimaryContainer", 0xFFCDE8D2, 0xFF12301B);
        put(colors, dark, "secondary", 0xFF9DBFA3, 0xFF4D6B54);
        put(colors, dark, "onSecondary", 0xFF10261A, 0xFFFFFFFF);
        put(colors, dark, "secondaryContainer", 0xFF2C4A33, 0xFFD8E8D5);
        put(colors, dark, "onSecondaryContainer", 0xFFCDE8D2, 0xFF12301B);
        put(colors, dark, "tertiary", 0xFFE6B08A, 0xFF9E5D34);
        put(colors, dark, "onTertiary", 0xFF2E1606, 0xFFFFFFFF);
        put(colors, dark, "tertiaryContainer", 0xFF5A3418, 0xFFFFDCC4);
        put(colors, dark, "onTertiaryContainer", 0xFFFFDCC4, 0xFF3A1A05);
        put(colors, dark, "error", 0xFFE07B55, 0xFFA6421B);
        put(colors, dark, "onError", 0xFF2B0F04, 0xFFFFFFFF);
        put(colors, dark, "errorContainer", 0xFF5E2512, 0xFFFFDBCB);
        put(colors, dark, "onErrorContainer", 0xFFFFD9C8, 0xFF4A1A06);
        put(colors, dark, "outline", 0xFF80705F, 0xFF94826B);
        put(colors, dark, "outlineVariant", 0xFF3E322A, 0xFFEADBC2);
        put(colors, dark, "inverseSurface", 0xFFFAF7EE, 0xFF33291F);
        put(colors, dark, "onInverseSurface", 0xFF2A211C, 0xFFFBEFDA);
        put(colors, dark, "inversePrimary", 0xFF35653F, 0xFF9DCFA8);
        put(colors, dark, "shadow", 0xFF000000, 0xFF000000);
        put(colors, dark, "scrim", 0xFF000000, 0xFF000000);
        put(colors, dark, "success", 0xFF6FAE7C, 0xFF3A7246);
        return colors;
    }

    private static void put(Map<String, Integer> colors, boolean dark, String role, int inDark, int inLight) {
        colors.put(role, dark ? inDark : inLight);
    }
}
