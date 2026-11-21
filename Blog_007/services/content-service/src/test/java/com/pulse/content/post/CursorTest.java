package com.pulse.content.post;

import com.pulse.content.common.InvalidCursorException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A plain UNIT test: no Spring, no database, runs in milliseconds.
 * Pattern for every test: Arrange (given) -> Act (when) -> Assert (then).
 */
class CursorTest {

    @Test
    void encodeThenDecodeGivesBackTheSameCursor() {
        // given
        Cursor original = new Cursor(Instant.parse("2026-11-20T10:15:30.123456Z"), UUID.randomUUID());

        // when
        Cursor decoded = Cursor.decode(original.encode());

        // then
        assertThat(decoded).isEqualTo(original); // records get equals() for free
    }

    @Test
    void encodedCursorIsUrlSafe() {
        Cursor cursor = new Cursor(Instant.now(), UUID.randomUUID());

        assertThat(cursor.encode()).matches("[A-Za-z0-9_-]+"); // no '+', '/', '=' that break URLs
    }

    @Test
    void garbageCursorIsRejectedWithOurOwnException() {
        assertThatThrownBy(() -> Cursor.decode("not-a-real-cursor!!"))
                .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void cursorWithoutSeparatorIsRejected() {
        String token = java.util.Base64.getUrlEncoder().encodeToString("just-text".getBytes());

        assertThatThrownBy(() -> Cursor.decode(token))
                .isInstanceOf(InvalidCursorException.class);
    }
}
