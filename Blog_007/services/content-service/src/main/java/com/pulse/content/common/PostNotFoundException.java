package com.pulse.content.common;

/**
 * Thrown when a slug does not match a published post. {@link ApiExceptionHandler} turns it into
 * HTTP 404 with a Problem Details body. A {@code RuntimeException} is "unchecked": callers are
 * not forced to catch it, which keeps the service code clean.
 */
public class PostNotFoundException extends RuntimeException {

    private final String slug;

    public PostNotFoundException(String slug) {
        super("No published post with slug '" + slug + "'");
        this.slug = slug;
    }

    public String getSlug() {
        return slug;
    }
}
