// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A step's text as plain sentences for the chosen servings. Tandoor renders its instruction templates on the server
 * into HTML in which every amount is a scalable-number tag and, from 2.6, every food with a plural is a plural-name
 * tag, so the client scales them the way Tandoor's own web app does.
 */
final class Instructions {
    /** What Tandoor puts in the rendered text when a template in the instruction is broken. */
    static final String TEMPLATE_ERROR = "Could not parse template code";

    private static final Pattern SCALABLE = Pattern.compile("<scalable-number\\b([^>]*)>\\s*</scalable-number>", Pattern.CASE_INSENSITIVE);
    private static final Pattern PLURAL = Pattern.compile("<plural-name\\b([^>]*)>\\s*</plural-name>", Pattern.CASE_INSENSITIVE);
    /** Tandoor 2.6.7 and 2.6.8 escape the plural tag, so it arrives as visible text. */
    private static final Pattern ESCAPED_PLURAL = Pattern.compile("&lt;plural-name\\b.*?&lt;/plural-name&gt;", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    /** A tag that shows the text went through Tandoor's Markdown. 2.6.7 skips that and sends the raw source. */
    private static final Pattern BLOCK_TAG = Pattern.compile("<(p|br|ul|ol|li|h[1-6]|div|table|blockquote|pre)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern TEMPLATE = Pattern.compile("\\{\\{.*?\\}\\}|\\{%.*?%\\}", Pattern.DOTALL);
    private static final Pattern ENTITY = Pattern.compile("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);");

    private Instructions() {}

    static String render(Recipe.Step step, double factor, boolean decimalComma) {
        switch (step.format) {
            case TANDOOR_HTML: return tandoor(step.text, factor, decimalComma);
            case MARKDOWN: return PlainText.fromMarkdown(step.text);
            default: return step.text.trim();
        }
    }

    /** Tandoor's rendered instruction HTML, scaled by factor, as plain text. */
    static String tandoor(String html, double factor, boolean decimalComma) {
        if (html == null || html.isEmpty()) return "";
        StringBuffer out = new StringBuffer();
        Matcher escaped = ESCAPED_PLURAL.matcher(html);
        while (escaped.find()) escaped.appendReplacement(out, Matcher.quoteReplacement(unescape(escaped.group())));
        escaped.appendTail(out);
        String source = out.toString();
        out = new StringBuffer();
        Matcher scalable = SCALABLE.matcher(source);
        while (scalable.find()) {
            double number = number(attribute(scalable.group(1), "v-bind:number"));
            String text = Double.isNaN(number) ? "" : Amounts.format(number * factor, decimalComma);
            scalable.appendReplacement(out, Matcher.quoteReplacement(text));
        }
        scalable.appendTail(out);
        String scaled = out.toString();
        out = new StringBuffer();
        Matcher plural = PLURAL.matcher(scaled);
        while (plural.find()) {
            String attributes = plural.group(1);
            String singular = unescape(attribute(attributes, "singular"));
            String many = unescape(attribute(attributes, "plural"));
            double amount = number(attribute(attributes, "v-bind:amount"));
            boolean noAmount = "true".equalsIgnoreCase(attribute(attributes, ":no-amount"));
            boolean one = noAmount || Double.isNaN(amount) || Math.abs(amount * factor - 1) < 0.0001;
            plural.appendReplacement(out, Matcher.quoteReplacement(one || many.isEmpty() ? singular : many));
        }
        plural.appendTail(out);
        String text = out.toString();
        return BLOCK_TAG.matcher(text).find() ? html(text) : PlainText.fromMarkdown(unescape(text));
    }

    /** A raw Tandoor instruction whose templates could not be rendered: the Markdown without its template tags. */
    static String withoutTemplates(String instruction) {
        return instruction == null ? "" : TEMPLATE.matcher(instruction).replaceAll("");
    }

    /** HTML as plain text: paragraphs and line breaks kept, list items as bullets, tags and entities gone. */
    static String html(String html) {
        // Source line breaks are only spaces in HTML. Tandoor marks real ones with br since 2.0.
        String text = html.replace("\r\n", "\n").replace('\r', '\n').replace('\n', ' ');
        text = text.replaceAll("(?i)<br\\s*/?>", "\n");
        text = text.replaceAll("(?i)</p\\s*>", "\n\n");
        // Each item starts its own line, so a closing li adds nothing.
        text = text.replaceAll("(?i)<li\\b[^>]*>", "\n• ");
        text = text.replaceAll("(?i)</(ul|ol|h[1-6]|div|tr|table|blockquote|pre)\\s*>", "\n");
        text = text.replaceAll("(?i)<(h[1-6]|div|tr|table|blockquote|pre)\\b[^>]*>", "\n");
        text = text.replaceAll("<[^>]*>", "");
        text = unescape(text);
        text = text.replaceAll("[ \\t\\x0B\\f\\u00A0]+", " ");
        text = text.replaceAll(" *\\n *", "\n");
        text = text.replaceAll("\\n{3,}", "\n\n");
        return text.trim();
    }

    /** The value of one attribute in either quote style, or an empty string. */
    static String attribute(String attributes, String name) {
        Matcher matcher = Pattern.compile("(?:^|\\s)" + Pattern.quote(name) + "\\s*=\\s*([\"'])(.*?)\\1", Pattern.DOTALL).matcher(attributes);
        return matcher.find() ? matcher.group(2) : "";
    }

    private static double number(String text) {
        try {
            return text.isEmpty() ? Double.NaN : Double.parseDouble(text.trim());
        } catch (NumberFormatException notANumber) {
            return Double.NaN;
        }
    }

    static String unescape(String text) {
        if (text.indexOf('&') < 0) return text;
        StringBuffer out = new StringBuffer();
        Matcher matcher = ENTITY.matcher(text);
        while (matcher.find()) {
            String entity = matcher.group(1);
            String replacement;
            if (entity.startsWith("#x") || entity.startsWith("#X")) replacement = codePoint(entity.substring(2), 16, matcher.group());
            else if (entity.startsWith("#")) replacement = codePoint(entity.substring(1), 10, matcher.group());
            else {
                switch (entity) {
                    case "amp": replacement = "&"; break;
                    case "lt": replacement = "<"; break;
                    case "gt": replacement = ">"; break;
                    case "quot": replacement = "\""; break;
                    case "apos": replacement = "'"; break;
                    case "nbsp": replacement = " "; break;
                    case "frasl": replacement = "⁄"; break;
                    case "deg": replacement = "°"; break;
                    case "hellip": replacement = "…"; break;
                    case "ndash": replacement = "–"; break;
                    case "mdash": replacement = "—"; break;
                    default: replacement = matcher.group();
                }
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String codePoint(String digits, int radix, String original) {
        try {
            return new String(Character.toChars(Integer.parseInt(digits, radix)));
        } catch (IllegalArgumentException bad) {
            return original;
        }
    }
}
