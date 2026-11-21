package com.pulse.ingestion.fetch;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;

/**
 * The ONLY way ingestion-service talks to the internet. Every request is:
 * <ul>
 *   <li>SSRF-checked ({@link SsrfGuard}) — including every redirect hop (max 3, followed manually);</li>
 *   <li>time-boxed — 3 s to connect, 5 s total per hop;</li>
 *   <li>size-capped — stop reading after {@code pulse.fetch.max-bytes} (5 MB), so a 50 MB "feed" can't fill memory;</li>
 *   <li>polite — identifies itself with a User-Agent and uses conditional GET (ETag / If-Modified-Since),
 *       so unchanged feeds cost the publisher a tiny 304 response.</li>
 * </ul>
 */
@Component
public class SafeFetcher {

    public static final String USER_AGENT = "PulseBot/1.0 (+https://github.com/Ankur42177/The-Dark-Algorithm)";
    private static final int MAX_REDIRECTS = 3;

    private final SsrfGuard guard;
    private final CloseableHttpClient client;
    private final long maxBytes;
    private final Duration timeout;

    public SafeFetcher(SsrfGuard guard,
                       @Value("${pulse.fetch.max-bytes:5242880}") long maxBytes,
                       @Value("${pulse.fetch.timeout:5s}") Duration timeout) {
        this.guard = guard;
        this.maxBytes = maxBytes;
        this.timeout = timeout;
        this.client = HttpClients.custom()
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                        .setDnsResolver(guard.dnsResolver())                       // SSRF check at connect time
                        .setDefaultConnectionConfig(ConnectionConfig.custom()
                                .setConnectTimeout(Timeout.ofSeconds(3))
                                .setSocketTimeout(Timeout.of(timeout))
                                .build())
                        .setMaxConnTotal(50).setMaxConnPerRoute(4)
                        .build())
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setRedirectsEnabled(false)                                // we follow redirects ourselves
                        .setResponseTimeout(Timeout.of(timeout))
                        .build())
                .setUserAgent(USER_AGENT)
                .disableCookieManagement()
                // HttpClient 5 silently retries 429/503 once (after a 1 s sleep) by default. Retries are owned by
                // the RabbitMQ retry queues (backoff, attempt count, DLQ), so a hidden second request per attempt
                // would double the load on the publisher and hold a worker thread asleep.
                .disableAutomaticRetries()
                .build();
    }

    public FetchResult fetch(String url, String etag, String lastModified) {
        URI uri = guard.checkUrl(url);
        long deadline = System.nanoTime() + timeout.toNanos();
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpGet get = new HttpGet(uri);
            get.setHeader("Accept", "application/rss+xml, application/atom+xml, application/xml;q=0.9, text/xml;q=0.8, */*;q=0.1");
            if (hop == 0 && etag != null) {
                get.setHeader("If-None-Match", etag);
            }
            if (hop == 0 && lastModified != null) {
                get.setHeader("If-Modified-Since", lastModified);
            }
            try {
                final URI current = uri;
                Object outcome = client.execute(get, response -> handle(response, current, deadline));
                if (outcome instanceof FetchResult r) {
                    return r;
                }
                uri = guard.checkUrl(current.resolve((String) outcome).toString());   // redirect: check the new URL
            } catch (FetchException e) {
                throw e;
            } catch (SsrfGuard.BlockedAddressException e) {
                throw FetchException.permanent("SSRF blocked: " + e.getMessage());
            } catch (SocketTimeoutException e) {
                throw FetchException.transientFailure("Timeout fetching " + uri.getHost(), null, e);
            } catch (IOException e) {
                throw FetchException.transientFailure("Network error: " + e.getClass().getSimpleName() + " " + e.getMessage(), null, e);
            }
        }
        throw FetchException.permanent("Too many redirects (max " + MAX_REDIRECTS + ")");
    }

    /** Returns a FetchResult, or the Location (String) to follow. */
    private Object handle(ClassicHttpResponse response, URI uri, long deadline) throws IOException {
        int status = response.getCode();
        if (status == 304) {
            return new FetchResult(304, true, new byte[0], header(response, "ETag"), header(response, "Last-Modified"), uri.toString());
        }
        if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
            String location = header(response, "Location");
            if (location == null) {
                throw FetchException.permanent("Redirect without Location", status);
            }
            return location;
        }
        if (status == 429 || status == 408 || status >= 500) {
            throw FetchException.transientFailure("HTTP " + status, status, null);   // try again later
        }
        if (status >= 400) {
            throw FetchException.permanent("HTTP " + status, status);
        }
        HttpEntity entity = response.getEntity();
        if (entity != null && entity.getContentLength() > maxBytes) {
            throw FetchException.permanent("Feed too large: " + entity.getContentLength() + " bytes (limit " + maxBytes + ")", status);
        }
        byte[] body = entity == null ? new byte[0] : readCapped(entity.getContent(), deadline);
        return new FetchResult(status, false, body, header(response, "ETag"), header(response, "Last-Modified"), uri.toString());
    }

    /** Read at most maxBytes, and give up when the total time budget is spent (a server dribbling 1 byte/s). */
    private byte[] readCapped(InputStream in, long deadline) throws IOException {
        try (in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16_384];
            int n;
            while ((n = in.read(buf)) != -1) {
                if (out.size() + n > maxBytes) {
                    throw FetchException.permanent("Feed too large: more than " + maxBytes + " bytes");
                }
                if (System.nanoTime() > deadline) {
                    throw FetchException.transientFailure("Download took longer than " + timeout.toSeconds() + " s", null, null);
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static String header(ClassicHttpResponse r, String name) {
        Header h = r.getFirstHeader(name);
        return h == null ? null : h.getValue();
    }
}
