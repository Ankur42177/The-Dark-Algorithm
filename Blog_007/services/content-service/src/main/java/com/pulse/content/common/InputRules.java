package com.pulse.content.common;

import java.util.Locale;

/**
 * Validation rules shared by the public forms (newsletter, contact). Constants so annotations can use them:
 * {@code @Pattern(regexp = InputRules.SINGLE_LINE)}.
 */
public final class InputRules {

    private InputRules() {
    }

    /**
     * Pragmatic email check: something@something.something, with NO whitespace and NO control characters.
     * The "no line breaks" part is a SECURITY rule, not just tidiness: the address goes into an email header
     * (To: / Reply-To:), and a "\r\n" inside it could inject extra headers ("header injection").
     * The real proof that an address works is the double opt-in email, not a regex.
     *
     * <p>Only the characters RFC 5322 allows in an unquoted local part ("dot-atom") and in a host name. The first
     * version only banned whitespace/control characters, so {@code a1<victim@gmail.com>} was "valid": every
     * variant got its own row (its own 10-minute cooldown), while JavaMail's address parser delivered them all to
     * victim@gmail.com — a way around the anti-bombing cooldown (Phase 7 security review). No {@code < > , " ( )}
     * now, and no quoted local parts (nobody uses them for a newsletter).
     */
    public static final String EMAIL = "^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+$";

    /** One line of text: no control characters at all (no \r, \n, \t, \0 ...). For names. */
    public static final String SINGLE_LINE = "^[^\\p{Cntrl}]*$";

    /** Multi-line text: anything except the NUL character, which PostgreSQL refuses to store in text columns. */
    public static final String NO_NUL = "^[^\\x00]*$";

    /** "  Ankur@Example.COM " → "ankur@example.com": one person = one row, whatever they type. */
    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    /** Trim; blank → null (an empty optional name is the same as no name). */
    public static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
