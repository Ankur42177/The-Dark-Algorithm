package com.pulse.content.post;

/**
 * The three sections of the site. An {@code enum} is a fixed list of allowed values:
 * the compiler stops us from ever writing a section that does not exist (e.g. "SPORTS").
 * Stored in Postgres as text ('TECH', 'FOOTBALL', 'AI') with a CHECK constraint.
 */
public enum Section {
    TECH,
    FOOTBALL,
    AI
}
