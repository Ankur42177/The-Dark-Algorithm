package com.pulse.assistant.embed;

import java.util.ArrayList;
import java.util.List;

/**
 * CHUNKING: split a post into pieces small enough to embed precisely (PLAN §13.4).
 *
 * <p>Why not embed the whole post as one vector? One vector for 3,000 words is a blurry "average" of
 * everything in it; a question about one paragraph matches poorly. Small chunks = sharp matches.
 * Why not single sentences? Too little context per chunk. ~500 tokens (≈ 2,000 characters) is a common
 * middle ground, split at HEADINGS first (natural topic boundaries), with a small OVERLAP so a sentence
 * cut at a boundary still appears whole in one of the two chunks.
 */
public final class Chunker {

    static final int MAX_CHARS = 2000;      // ≈ 500 tokens (1 token ≈ 4 characters of English)
    static final int OVERLAP_CHARS = 200;

    private Chunker() {
    }

    /** HTML (rendered by content-service) → plain-text chunks, split at h1–h3 first, then by size. */
    public static List<String> chunkHtml(String html) {
        if (html == null || html.isBlank()) {
            return List.of();
        }
        List<String> sections = new ArrayList<>();
        for (String part : html.split("(?i)(?=<h[1-3][ >])")) {
            String text = toText(part);
            if (!text.isBlank()) {
                sections.add(text);
            }
        }
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String s : sections) {
            if (current.length() + s.length() + 1 <= MAX_CHARS) {
                current.append(current.isEmpty() ? "" : "\n").append(s);   // merge small sections
                continue;
            }
            if (!current.isEmpty()) {
                chunks.add(current.toString());
                current.setLength(0);
            }
            if (s.length() <= MAX_CHARS) {
                current.append(s);
            } else {
                chunks.addAll(splitLong(s));                                 // one huge section: sliding window
            }
        }
        if (!current.isEmpty()) {
            chunks.add(current.toString());
        }
        return chunks;
    }

    /** Fixed-size windows with overlap, cut at a space so words stay whole. */
    static List<String> splitLong(String text) {
        List<String> out = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + MAX_CHARS);
            if (end < text.length()) {
                int space = text.lastIndexOf(' ', end);
                if (space > start + MAX_CHARS / 2) {
                    end = space;
                }
            }
            out.add(text.substring(start, end).trim());
            if (end >= text.length()) {
                break;
            }
            start = Math.max(end - OVERLAP_CHARS, start + 1);
        }
        return out;
    }

    static String toText(String html) {
        return html.replaceAll("(?i)<(br|/p|/li|/h[1-6]|/pre)>", "\n")
                .replaceAll("<[^>]+>", " ")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\\n\\s*\\n+", "\n")
                .trim();
    }
}
