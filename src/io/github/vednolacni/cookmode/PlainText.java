// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import java.util.regex.Pattern;

/** Recipe instructions arrive as Markdown. The cooking view and read-aloud want plain sentences. */
final class PlainText {
    // Each of these has a limit, so a text full of openers does not take a pass over the rest of it for every one.
    private static final Pattern IMAGE = Pattern.compile("!\\[([^\\]]{0,300})\\]\\([^)]{0,1000}\\)");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]{0,300})\\]\\([^)]{0,1000}\\)");
    private static final Pattern HTML = Pattern.compile("<[^>]{1,200}>");
    // Line patterns use [ \t] rather than \s, which would also swallow the blank lines before them.
    private static final Pattern HEADING = Pattern.compile("(?m)^[ \\t]{0,3}#{1,6}[ \\t]*");
    private static final Pattern QUOTE = Pattern.compile("(?m)^[ \\t]{0,3}>[ \\t]?");
    private static final Pattern BULLET = Pattern.compile("(?m)^[ \\t]*[-*+][ \\t]+");
    private static final Pattern RULE = Pattern.compile("(?m)^[ \\t]*([-*_])([ \\t]*\\1){2,}[ \\t]*$");
    private static final Pattern STRONG = Pattern.compile("(\\*\\*|__)(?=\\S)(.{1,500}?)(?<=\\S)\\1");
    private static final Pattern EMPHASIS = Pattern.compile("(?<![\\w*])([*_])(?=\\S)(.{1,500}?)(?<=\\S)\\1(?![\\w*])");
    private static final Pattern CODE = Pattern.compile("`+([^`]*)`+");
    private static final Pattern SPACES = Pattern.compile("[ \\t\\x0B\\f]+");
    private static final Pattern BLANK_LINES = Pattern.compile("\\n{3,}");

    private PlainText() {}

    /** Markdown as plain text: no markup, links reduced to their words, lists as bullets, paragraphs kept. */
    static String fromMarkdown(String markdown) {
        if (markdown == null || markdown.isEmpty()) return "";
        String text = markdown.replace("\r\n", "\n").replace('\r', '\n');
        text = IMAGE.matcher(text).replaceAll("");
        text = LINK.matcher(text).replaceAll("$1");
        text = text.replaceAll("(?i)<br\\s*/?>", "\n");
        text = HTML.matcher(text).replaceAll("");
        text = RULE.matcher(text).replaceAll("");
        text = HEADING.matcher(text).replaceAll("");
        text = QUOTE.matcher(text).replaceAll("");
        text = BULLET.matcher(text).replaceAll("• ");
        text = STRONG.matcher(text).replaceAll("$2");
        text = EMPHASIS.matcher(text).replaceAll("$2");
        text = CODE.matcher(text).replaceAll("$1");
        text = text.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"");
        text = SPACES.matcher(text).replaceAll(" ");
        text = text.replaceAll(" *\\n *", "\n");
        text = BLANK_LINES.matcher(text).replaceAll("\n\n");
        return text.trim();
    }

    /** Text to read aloud: bullets and line breaks become pauses. */
    static String forSpeech(String plain) {
        if (plain == null) return "";
        return plain.replace("• ", "").replaceAll("\\n+", ". ").replaceAll("\\.\\s*\\.", ".").trim();
    }
}
