package com.pulse.ingestion.fetch;

/**
 * Outcome of a successful HTTP exchange.
 *
 * @param notModified true on HTTP 304: the feed hasn't changed since our last ETag/Last-Modified → no body
 */
public record FetchResult(int status, boolean notModified, byte[] body, String etag, String lastModified, String finalUrl) {
}
