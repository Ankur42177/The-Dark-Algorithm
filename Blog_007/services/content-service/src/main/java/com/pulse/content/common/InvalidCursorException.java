package com.pulse.content.common;

/** The client sent a cursor we cannot decode (edited, truncated, or from another API). -> HTTP 400. */
public class InvalidCursorException extends RuntimeException {

    public InvalidCursorException(String cursor) {
        super("Invalid pagination cursor: " + cursor);
    }
}
