// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader and writer. Android's org.json is only a stub on the JVM, where the tests run, so the plugin
 * reads and writes JSON itself. Objects become ordered maps, arrays lists, numbers doubles and null null.
 */
final class Json {
    private final String text;
    private int at;

    private Json(String text) {
        this.text = text;
    }

    /** Read one JSON value. Throws IllegalArgumentException for anything that is not exactly one value. */
    static Object parse(String text) {
        Json json = new Json(text);
        json.space();
        Object value = json.value(0);
        json.space();
        if (json.at != text.length()) throw json.error("Unexpected text after the value");
        return value;
    }

    static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value);
        return out.toString();
    }

    /** The value as an object, or an empty one. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Collections.<String, Object>emptyMap();
    }

    /** The value as an array, or an empty one. */
    @SuppressWarnings("unchecked")
    static List<Object> list(Object value) {
        return value instanceof List ? (List<Object>) value : Collections.emptyList();
    }

    /** A string, a number without a needless decimal point, or an empty string. */
    static String text(Object value) {
        if (value instanceof String) return (String) value;
        if (value instanceof Number) {
            double number = ((Number) value).doubleValue();
            return number == Math.rint(number) && Math.abs(number) < 1e15 ? Long.toString((long) number) : value.toString();
        }
        if (value instanceof Boolean) return value.toString();
        return "";
    }

    static double number(Object value, double fallback) {
        if (value instanceof Number) return ((Number) value).doubleValue();
        if (value instanceof String) {
            try {
                return Double.parseDouble(((String) value).trim().replace(',', '.'));
            } catch (NumberFormatException notANumber) {
                return fallback;
            }
        }
        return fallback;
    }

    static long integer(Object value, long fallback) {
        double number = number(value, Double.NaN);
        return Double.isNaN(number) || Double.isInfinite(number) ? fallback : Math.round(number);
    }

    static boolean bool(Object value, boolean fallback) {
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    /** Follow object keys from value, such as path(recipe, "food", "name"). Missing steps give null. */
    static Object path(Object value, String... keys) {
        Object current = value;
        for (String key : keys) {
            if (!(current instanceof Map)) return null;
            current = ((Map<?, ?>) current).get(key);
        }
        return current;
    }

    // Reading.

    /** Nesting deeper than this is refused, so a hostile answer cannot overflow the stack. */
    private static final int MAX_DEPTH = 64;

    private Object value(int depth) {
        if (at >= text.length()) throw error("Unexpected end");
        char c = text.charAt(at);
        switch (c) {
            case '{': return object(depth + 1);
            case '[': return array(depth + 1);
            case '"': return string();
            case 't': return literal("true", Boolean.TRUE);
            case 'f': return literal("false", Boolean.FALSE);
            case 'n': return literal("null", null);
            default:
                if (c == '-' || (c >= '0' && c <= '9')) return number();
                throw error("Unexpected character '" + c + "'");
        }
    }

    private Map<String, Object> object(int depth) {
        if (depth > MAX_DEPTH) throw error("Too deeply nested");
        Map<String, Object> map = new LinkedHashMap<>();
        at++;
        space();
        if (peek() == '}') {
            at++;
            return map;
        }
        while (true) {
            space();
            if (peek() != '"') throw error("Expected a key");
            String key = string();
            space();
            expect(':');
            space();
            map.put(key, value(depth));
            space();
            char next = peek();
            at++;
            if (next == '}') return map;
            if (next != ',') throw error("Expected , or }");
        }
    }

    private List<Object> array(int depth) {
        if (depth > MAX_DEPTH) throw error("Too deeply nested");
        List<Object> list = new ArrayList<>();
        at++;
        space();
        if (peek() == ']') {
            at++;
            return list;
        }
        while (true) {
            space();
            list.add(value(depth));
            space();
            char next = peek();
            at++;
            if (next == ']') return list;
            if (next != ',') throw error("Expected , or ]");
        }
    }

    private String string() {
        at++;
        StringBuilder out = new StringBuilder();
        while (true) {
            if (at >= text.length()) throw error("Unterminated string");
            char c = text.charAt(at++);
            if (c == '"') return out.toString();
            if (c != '\\') {
                if (c < 0x20) throw error("Control character in string");
                out.append(c);
                continue;
            }
            if (at >= text.length()) throw error("Unterminated escape");
            char escape = text.charAt(at++);
            switch (escape) {
                case '"': out.append('"'); break;
                case '\\': out.append('\\'); break;
                case '/': out.append('/'); break;
                case 'b': out.append('\b'); break;
                case 'f': out.append('\f'); break;
                case 'n': out.append('\n'); break;
                case 'r': out.append('\r'); break;
                case 't': out.append('\t'); break;
                case 'u':
                    if (at + 4 > text.length()) throw error("Short unicode escape");
                    try {
                        out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                    } catch (NumberFormatException bad) {
                        throw error("Bad unicode escape");
                    }
                    at += 4;
                    break;
                default: throw error("Bad escape \\" + escape);
            }
        }
    }

    private Double number() {
        int start = at;
        if (peek() == '-') at++;
        digits();
        if (at < text.length() && text.charAt(at) == '.') {
            at++;
            digits();
        }
        if (at < text.length() && (text.charAt(at) == 'e' || text.charAt(at) == 'E')) {
            at++;
            if (at < text.length() && (text.charAt(at) == '+' || text.charAt(at) == '-')) at++;
            digits();
        }
        try {
            return Double.valueOf(text.substring(start, at));
        } catch (NumberFormatException bad) {
            throw error("Bad number");
        }
    }

    private void digits() {
        int start = at;
        while (at < text.length() && text.charAt(at) >= '0' && text.charAt(at) <= '9') at++;
        if (at == start) throw error("Expected a digit");
    }

    private Object literal(String word, Object value) {
        if (!text.startsWith(word, at)) throw error("Unexpected word");
        at += word.length();
        return value;
    }

    private void space() {
        while (at < text.length()) {
            char c = text.charAt(at);
            if (c != ' ' && c != '\n' && c != '\r' && c != '\t') return;
            at++;
        }
    }

    private char peek() {
        if (at >= text.length()) throw error("Unexpected end");
        return text.charAt(at);
    }

    private void expect(char c) {
        if (peek() != c) throw error("Expected " + c);
        at++;
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException(message + " at " + at);
    }

    // Writing.

    private static void write(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String) {
            quote(out, (String) value);
        } else if (value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Number) {
            double number = ((Number) value).doubleValue();
            if (Double.isNaN(number) || Double.isInfinite(number)) out.append("null");
            else if (number == Math.rint(number) && Math.abs(number) < 1e15) out.append((long) number);
            else out.append(number);
        } else if (value instanceof Map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!first) out.append(',');
                first = false;
                quote(out, String.valueOf(entry.getKey()));
                out.append(':');
                write(out, entry.getValue());
            }
            out.append('}');
        } else if (value instanceof List) {
            out.append('[');
            boolean first = true;
            for (Object item : (List<?>) value) {
                if (!first) out.append(',');
                first = false;
                write(out, item);
            }
            out.append(']');
        } else {
            quote(out, value.toString());
        }
    }

    private static void quote(StringBuilder out, String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
            }
        }
        out.append('"');
    }
}
