package com.pulse.content.auth;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** Shared helper: a login attempt that claims to come from {@code forwardedFor} via X-Forwarded-For. */
final class ForwardedIpSupport {

    static final String PASSWORD = "forwarded ip test password";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private ForwardedIpSupport() {
    }

    static int login(int port, String password, String forwardedFor) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/login"))
                .header("Content-Type", "application/json")
                .header("X-Forwarded-For", forwardedFor)
                .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"ankur\",\"password\":\"" + password + "\"}"))
                .build();
        return HTTP.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
