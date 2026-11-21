package com.pulse.ingestion.fetch;

/**
 * A fetch failed. {@code transientFailure = true} means "might work later" (timeout, 5xx, 429, network)
 * → retry with backoff. false means "will never work as is" (SSRF block, 404, too large, not a feed)
 * → straight to the dead-letter queue.
 */
public class FetchException extends RuntimeException {

    private final boolean transientFailure;
    private final Integer httpStatus;

    public FetchException(String message, boolean transientFailure, Integer httpStatus, Throwable cause) {
        super(message, cause);
        this.transientFailure = transientFailure;
        this.httpStatus = httpStatus;
    }

    public static FetchException permanent(String msg) {
        return new FetchException(msg, false, null, null);
    }

    public static FetchException permanent(String msg, Integer status) {
        return new FetchException(msg, false, status, null);
    }

    public static FetchException transientFailure(String msg, Integer status, Throwable cause) {
        return new FetchException(msg, true, status, cause);
    }

    public boolean isTransient() {
        return transientFailure;
    }

    public Integer httpStatus() {
        return httpStatus;
    }
}
