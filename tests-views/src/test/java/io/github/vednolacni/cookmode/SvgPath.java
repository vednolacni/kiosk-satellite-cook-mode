// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import android.graphics.Path;
import android.graphics.RectF;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns an SVG path into a Path, for the icons in the tests. A tablet gets its icons from KS. */
final class SvgPath {
    private static final Pattern TOKEN = Pattern.compile("[a-zA-Z]|-?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?");

    private final String[] tokens;
    private int at;

    private SvgPath(String data) {
        java.util.List<String> found = new java.util.ArrayList<>();
        Matcher matcher = TOKEN.matcher(data);
        while (matcher.find()) found.add(matcher.group());
        tokens = found.toArray(new String[0]);
    }

    static Path parse(String data) {
        return new SvgPath(data).build();
    }

    private boolean number() {
        return at < tokens.length && !Character.isLetter(tokens[at].charAt(0));
    }

    private float next() {
        return Float.parseFloat(tokens[at++]);
    }

    private Path build() {
        Path path = new Path();
        float x = 0, y = 0, startX = 0, startY = 0, controlX = 0, controlY = 0;
        char last = ' ';
        while (at < tokens.length) {
            char command = tokens[at++].charAt(0);
            boolean relative = Character.isLowerCase(command);
            char kind = Character.toUpperCase(command);
            do {
                float baseX = relative ? x : 0;
                float baseY = relative ? y : 0;
                switch (kind) {
                    case 'M':
                        x = baseX + next();
                        y = baseY + next();
                        path.moveTo(x, y);
                        startX = x;
                        startY = y;
                        kind = 'L';
                        break;
                    case 'L':
                        x = baseX + next();
                        y = baseY + next();
                        path.lineTo(x, y);
                        break;
                    case 'H':
                        x = baseX + next();
                        path.lineTo(x, y);
                        break;
                    case 'V':
                        y = baseY + next();
                        path.lineTo(x, y);
                        break;
                    case 'C': {
                        float x1 = baseX + next(), y1 = baseY + next(), x2 = baseX + next(), y2 = baseY + next();
                        x = baseX + next();
                        y = baseY + next();
                        path.cubicTo(x1, y1, x2, y2, x, y);
                        controlX = x2;
                        controlY = y2;
                        break;
                    }
                    case 'S': {
                        float x1 = last == 'C' || last == 'S' ? 2 * x - controlX : x;
                        float y1 = last == 'C' || last == 'S' ? 2 * y - controlY : y;
                        float x2 = baseX + next(), y2 = baseY + next();
                        x = baseX + next();
                        y = baseY + next();
                        path.cubicTo(x1, y1, x2, y2, x, y);
                        controlX = x2;
                        controlY = y2;
                        break;
                    }
                    case 'Q': {
                        float x1 = baseX + next(), y1 = baseY + next();
                        x = baseX + next();
                        y = baseY + next();
                        path.quadTo(x1, y1, x, y);
                        controlX = x1;
                        controlY = y1;
                        break;
                    }
                    case 'T': {
                        float x1 = last == 'Q' || last == 'T' ? 2 * x - controlX : x;
                        float y1 = last == 'Q' || last == 'T' ? 2 * y - controlY : y;
                        x = baseX + next();
                        y = baseY + next();
                        path.quadTo(x1, y1, x, y);
                        controlX = x1;
                        controlY = y1;
                        break;
                    }
                    case 'A': {
                        float rx = next(), ry = next(), rotation = next();
                        boolean large = flag();
                        boolean sweep = flag();
                        float toX = baseX + next(), toY = baseY + next();
                        arc(path, x, y, rx, ry, rotation, large, sweep, toX, toY);
                        x = toX;
                        y = toY;
                        break;
                    }
                    case 'Z':
                        path.close();
                        x = startX;
                        y = startY;
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown path command " + command);
                }
                last = kind;
            } while (kind != 'Z' && number());
        }
        return path;
    }

    /** Arc flags may touch what follows, as in "a1 1 0 011 1", and the tokenizer took them as one number. */
    private boolean flag() {
        String token = tokens[at];
        char flag = token.charAt(0);
        if (token.length() == 1) at++;
        else tokens[at] = token.substring(1);
        return flag == '1';
    }

    /** The endpoint form of an arc to its center form, per the SVG specification. Rotated ellipses do not occur in the icons. */
    private static void arc(Path path, float x0, float y0, float rx, float ry, float rotation, boolean large, boolean sweep, float x, float y) {
        if (rx == 0 || ry == 0) {
            path.lineTo(x, y);
            return;
        }
        double dx = (x0 - x) / 2.0;
        double dy = (y0 - y) / 2.0;
        double a = Math.abs(rx);
        double b = Math.abs(ry);
        double scale = dx * dx / (a * a) + dy * dy / (b * b);
        if (scale > 1) {
            a *= Math.sqrt(scale);
            b *= Math.sqrt(scale);
        }
        double top = a * a * b * b - a * a * dy * dy - b * b * dx * dx;
        double under = a * a * dy * dy + b * b * dx * dx;
        double root = under == 0 ? 0 : Math.sqrt(Math.max(0, top / under));
        if (large == sweep) root = -root;
        double cxPrime = root * a * dy / b;
        double cyPrime = -root * b * dx / a;
        double cx = cxPrime + (x0 + x) / 2.0;
        double cy = cyPrime + (y0 + y) / 2.0;
        double start = Math.toDegrees(Math.atan2((dy - cyPrime) / b, (dx - cxPrime) / a));
        double end = Math.toDegrees(Math.atan2((-dy - cyPrime) / b, (-dx - cxPrime) / a));
        double extent = end - start;
        if (sweep && extent < 0) extent += 360;
        if (!sweep && extent > 0) extent -= 360;
        path.arcTo(new RectF((float) (cx - a), (float) (cy - b), (float) (cx + a), (float) (cy + b)), (float) start, (float) extent, false);
    }
}
