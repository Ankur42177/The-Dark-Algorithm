package com.pulse.ingestion.fetch;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.time.Duration;
import java.util.Set;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The fetcher against a hostile/broken fake server (WireMock). "localhost" is allow-listed ONLY here so we
 * can reach WireMock; every other private address stays blocked.
 */
class SafeFetcherTest {

    static WireMockServer wm;
    static SafeFetcher fetcher;
    static String base;

    @BeforeAll
    static void start() {
        wm = new WireMockServer(options().dynamicPort());
        wm.start();
        base = "http://localhost:" + wm.port();
        fetcher = new SafeFetcher(new SsrfGuard(Set.of("localhost")), 2 * 1024 * 1024, Duration.ofSeconds(2));
    }

    @AfterAll
    static void stop() {
        wm.stop();
    }

    @Test
    void conditionalGetReturns304AndSendsOurUserAgent() {
        wm.stubFor(get(urlEqualTo("/feed")).withHeader("If-None-Match", com.github.tomakehurst.wiremock.client.WireMock.equalTo("\"v1\""))
                .withHeader("User-Agent", com.github.tomakehurst.wiremock.client.WireMock.containing("PulseBot/1.0"))
                .willReturn(aResponse().withStatus(304)));
        FetchResult r = fetcher.fetch(base + "/feed", "\"v1\"", null);
        assertThat(r.notModified()).isTrue();
    }

    @Test
    void serverErrorsAndTimeoutsAreTransient() {
        wm.stubFor(get(urlEqualTo("/500")).willReturn(aResponse().withStatus(500)));
        wm.stubFor(get(urlEqualTo("/slow")).willReturn(aResponse().withStatus(200).withFixedDelay(4000).withBody("late")));
        assertThatThrownBy(() -> fetcher.fetch(base + "/500", null, null))
                .isInstanceOfSatisfying(FetchException.class, e -> assertThat(e.isTransient()).isTrue());
        long t0 = System.nanoTime();
        assertThatThrownBy(() -> fetcher.fetch(base + "/slow", null, null))
                .isInstanceOfSatisfying(FetchException.class, e -> assertThat(e.isTransient()).isTrue());
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofMillis(3500));   // gave up at ~2 s
    }

    @Test
    void notFoundIsPermanent() {
        wm.stubFor(get(urlEqualTo("/404")).willReturn(aResponse().withStatus(404)));
        assertThatThrownBy(() -> fetcher.fetch(base + "/404", null, null))
                .isInstanceOfSatisfying(FetchException.class, e -> assertThat(e.isTransient()).isFalse());
    }

    @Test
    void hugeBodiesAreCutOffAtTheCap() {
        wm.stubFor(get(urlEqualTo("/huge")).willReturn(aResponse().withStatus(200).withBody(new byte[50 * 1024 * 1024])));   // 50 MB
        assertThatThrownBy(() -> fetcher.fetch(base + "/huge", null, null))
                .isInstanceOf(FetchException.class).hasMessageContaining("too large");
    }

    @Test
    void redirectToCloudMetadataIsBlocked() {
        wm.stubFor(get(urlEqualTo("/redirect")).willReturn(aResponse().withStatus(302).withHeader("Location", "http://169.254.169.254/latest/meta-data/")));
        assertThatThrownBy(() -> fetcher.fetch(base + "/redirect", null, null))
                .isInstanceOfSatisfying(FetchException.class, e -> {
                    assertThat(e.isTransient()).isFalse();
                    assertThat(e.getMessage()).containsAnyOf("SSRF", "Blocked");
                });
    }

    @Test
    void privateAndOddUrlsAreRefusedBeforeAnyRequest() {
        SafeFetcher strict = new SafeFetcher(new SsrfGuard(Set.of()), 1024, Duration.ofSeconds(1));
        for (String url : new String[]{"file:///etc/passwd", "gopher://x", "http://127.0.0.1/", "http://10.0.0.5/feed",
                "http://[::1]/", "http://user:pw@example.com/", "http://example.com:6379/", base + "/feed"}) {
            assertThatThrownBy(() -> strict.fetch(url, null, null)).as(url)
                    .isInstanceOfSatisfying(FetchException.class, e -> assertThat(e.isTransient()).isFalse());
        }
    }

    @Test
    void addressClassification() throws Exception {
        for (String ip : new String[]{"127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.169.254", "100.64.0.1", "0.0.0.0", "::1", "fd00::1"}) {
            assertThat(SsrfGuard.isBlocked(InetAddress.getByName(ip))).as(ip).isTrue();
        }
        assertThat(SsrfGuard.isBlocked(InetAddress.getByName("93.184.216.34"))).isFalse();
    }
}
