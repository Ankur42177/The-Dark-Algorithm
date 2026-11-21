package com.pulse.content.common;

/**
 * Generic "404 Not Found" for the newsletter and contact features. {@link ApiExceptionHandler} turns it into an
 * RFC 9457 Problem Details body, e.g. {@code "type": "https://pulse.dev/problems/invalid-token"}.
 *
 * @param problemType last part of the problem "type" URI (a stable, machine-readable error code)
 * @param title       short human summary ("Invalid or expired link")
 */
public class ResourceNotFoundException extends RuntimeException {

    private final String problemType;
    private final String title;

    public ResourceNotFoundException(String problemType, String title, String detail) {
        super(detail);
        this.problemType = problemType;
        this.title = title;
    }

    public String problemType() {
        return problemType;
    }

    public String title() {
        return title;
    }
}
