package com.pulse.ingestion.ingest;

import java.util.regex.Pattern;

/** Turns feed HTML into short plain text. Fair use (PLAN §5.1): excerpts are at most 300 characters. */
final class Text {

    static final int MAX_EXCERPT = 300;
    private static final Pattern TAGS = Pattern.compile("<[^>]*>");
    private static final Pattern SCRIPTS = Pattern.compile("(?is)<(script|style)[^>]*>.*?</\\1>");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private Text() {
    }

    static String excerpt(String html) {
        if (html == null) {
            return null;
        }
        String text = clean(html, Integer.MAX_VALUE);
        if (text.isEmpty()) {
            return null;
        }
        if (text.length() <= MAX_EXCERPT) {
            return text;
        }
        String cut = text.substring(0, MAX_EXCERPT - 1);
        int lastSpace = cut.lastIndexOf(' ');
        return (lastSpace > 200 ? cut.substring(0, lastSpace) : cut).trim() + "…";
    }

    static String clean(String html, int max) {
        String noScripts = SCRIPTS.matcher(html).replaceAll(" ");
        String text = TAGS.matcher(noScripts).replaceAll(" ")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&#8217;", "'").replace("&#8220;", "\"").replace("&#8221;", "\"");
        text = SPACES.matcher(text).replaceAll(" ").trim();
        return text.length() > max ? text.substring(0, max).trim() : text;
    }
}
