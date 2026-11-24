// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.keycloak.mcp;

import org.keycloak.util.JsonSerialization;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.Supplier;

final class McpReadIssuerCredentials implements Supplier<String> {
    private static final Duration TIMEOUT = Duration.ofSeconds(4);
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private static final String CLIENT = "openbank-mcp-read-issuer";

    private final URI endpoint;
    private final String secret;
    private Cached cached;

    private record Cached(String value, Instant expires) {
    }

    McpReadIssuerCredentials(URI endpoint, String secret) {
        this.endpoint = endpoint;
        this.secret = secret;
    }

    @Override
    public synchronized String get() {
        Instant now = Instant.now();
        if (cached != null && cached.expires().isAfter(now.plusSeconds(5))) return cached.value();
        try {
            String form = "grant_type=client_credentials&client_id=" + CLIENT
                    + "&client_secret=" + URLEncoder.encode(secret, StandardCharsets.UTF_8);
            var response = HTTP.send(HttpRequest.newBuilder(endpoint).timeout(TIMEOUT)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return null;
            Map<?, ?> tokens = JsonSerialization.readValue(response.body(), Map.class);
            if (!(tokens.get("access_token") instanceof String token) || token.isBlank()
                    || !(tokens.get("expires_in") instanceof Number seconds) || seconds.longValue() <= 0) return null;
            cached = new Cached(token, now.plusSeconds(Math.min(seconds.longValue(), 300)));
            return token;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
