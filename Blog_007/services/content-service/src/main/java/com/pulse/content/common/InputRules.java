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
     */
    public static final String EMAIL = "^[^@\\s\\p{Cntrl}]+@[^@\\s\\p{Cntrl}]+\\.[^@\\s\\p{Cntrl}]+$";

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
