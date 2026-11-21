package com.pulse.content.post;

import com.pulse.content.common.InvalidCursorException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.UUID;

/**
 * A keyset-pagination cursor: "the last row I already showed you".
 *
 * <p>Lists are sorted by {@code (published_at DESC, id DESC)}. To get the next page we ask the
 * database for rows that come strictly AFTER the last row of this page:
 * <pre>WHERE (published_at, id) &lt; (:lastPublishedAt, :lastId)</pre>
 * The {@code id} is the tie-breaker: two posts can share the same timestamp, ids never repeat.
 *
 * <p>We hand the client an <b>opaque</b> string (Base64 of "timestamp|uuid") so it treats the
 * cursor as a token rather than something to build or edit by hand — that leaves us free to
 * change its contents later.
 */
public record Cursor(Instant publishedAt, UUID id) {

    private static final String SEPARATOR = "|";

    /** Turn the cursor into a URL-safe token, e.g. "MjAyNi0xMS0yMFQxMDowMDowMFp8..." */
    public String encode() {
        String raw = publishedAt + SEPARATOR + id;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** Parse a token the client sent back. Bad input becomes a 400 Bad Request, never a 500. */
    public static Cursor decode(String token) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            int split = raw.indexOf(SEPARATOR);
            if (split < 0) {
                throw new InvalidCursorException(token);
            }
            return new Cursor(
                    Instant.parse(raw.substring(0, split)),
                    UUID.fromString(raw.substring(split + 1)));
        } catch (IllegalArgumentException | DateTimeParseException e) {
            // IllegalArgumentException covers bad Base64 and bad UUID text
            throw new InvalidCursorException(token);
        }
    }
}
