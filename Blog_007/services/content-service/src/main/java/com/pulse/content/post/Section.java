package com.pulse.content.post;

/**
 * The sections of the site. An {@code enum} is a fixed list of allowed values:
 * the compiler stops us from ever writing a section that does not exist (e.g. "SPORTS").
 * Stored in Postgres as text ('TECH', 'FOOTBALL', 'AI') with a CHECK constraint.
 *
 * <p><b>AI is a LEGACY value.</b> Migration V6 merged the AI section into TECH (AI articles are tech
 * articles; two thin sections were worse than one rich one). We KEEP the constant on purpose:
 * <ul>
 *   <li>other services (ingestion, assistant) and old links may still send "AI" — removing the constant would
 *       turn those into 400 errors or failed Kafka events (backward compatibility);</li>
 *   <li>the database CHECK constraint still allows it, so old rows/events never break a deploy.</li>
 * </ul>
 * Every place that WRITES or FILTERS by a section calls {@link #canonical()}, so after V6 no row uses AI.
 */
public enum Section {
    TECH,
    FOOTBALL,
    /** @deprecated merged into {@link #TECH} by V6; accepted on input, mapped by {@link #canonical()}. */
    @Deprecated
    AI;

    /** The section a value really means today: AI → TECH, everything else unchanged. */
    @SuppressWarnings("deprecation")
    public Section canonical() {
        return this == AI ? TECH : this;
    }

    /** Null-safe helper for optional query parameters ({@code ?section=AI} keeps working: it shows TECH). */
    public static Section canonical(Section s) {
        return s == null ? null : s.canonical();
    }

    /** Same mapping for raw strings coming from other services' events (e.g. "AI" → "TECH"). */
    public static String canonical(String s) {
        return "AI".equals(s) ? TECH.name() : s;
    }
}
