// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * sca-service, called as the authorization server's own scoped consumer
 * ({@code service-account-openbank-keycloak-mcp}, rules.yaml: scoped_sca_consumers). It may only
 * initiate and consume {@code MCP_CLIENT_CONNECT} challenges; sca-service enforces that, not this.
 */
class ScaClient {
    enum Outcome {
        /** sca-service consumed THIS challenge for THIS party and request (single-use CAS won). */
        CONSUMED,
        /** Not approved yet: nothing was spent, polling may continue. */
        PENDING,
        /** Anything else — refused, expired, already spent, malformed, unreachable. Terminal. */
        REFUSED,
    }

    record Settings(String scaUrl, String tokenUrl, String clientId, String clientSecret) {
        boolean complete() {
            return notBlank(scaUrl) && notBlank(tokenUrl) && notBlank(clientId) && notBlank(clientSecret);
        }

        private static boolean notBlank(String s) {
            return s != null && !s.isBlank();
        }
    }

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private static volatile CachedToken cached;

    private record CachedToken(String key, String value, long expiresAtMillis) {
    }

    private final Settings settings;

    ScaClient(Settings settings) {
        this.settings = settings;
    }

    /** Raises the connect challenge; returns its id. Throws on any non-201. */
    String initiate(String partyId, String approvalRequestId, String payloadSha256) throws IOException {
        Map<String, Object> linking = new LinkedHashMap<>();
        linking.put("approvalRequestId", approvalRequestId);
        linking.put("payloadSha256", payloadSha256);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("partyId", partyId);
        body.put("purpose", "MCP_CLIENT_CONNECT");
        body.put("preferredMethod", "PUSH_NOTIFICATION");
        body.put("dynamicLinkingData", linking);
        HttpResponse<String> resp = send(HttpRequest.newBuilder(uri("/api/v1/sca/challenges"))
                // Same connect => same challenge on a retry; a different context => a new one (D3).
                .header("Idempotency-Key", McpConnectContext.sha256Hex(approvalRequestId + "|" + payloadSha256)),
                body);
        if (resp.statusCode() != 201) {
            throw new IOException("sca initiate answered " + resp.statusCode());
        }
        Object id = JsonSerialization.readValue(resp.body(), Map.class).get("id");
        if (!(id instanceof String s) || s.isBlank()) {
            throw new IOException("sca initiate returned no challenge id");
        }
        return s;
    }

    /**
     * Spends the challenge. 200 counts only when the body attests to exactly what was asked:
     * same id, same party, the connect purpose, COMPLETED and consumed.
     */
    Outcome consume(String challengeId, String partyId, String approvalRequestId, String payloadSha256) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("partyId", partyId);
        body.put("approvalRequestId", approvalRequestId);
        body.put("payloadSha256", payloadSha256);
        try {
            HttpResponse<String> resp = send(HttpRequest.newBuilder(
                    uri("/api/v1/sca/challenges/" + URLEncoder.encode(challengeId, StandardCharsets.UTF_8) + "/consume")),
                    body);
            if (resp.statusCode() == 400) {
                // ScaChallengeNotApprovedException: the device has not approved (yet).
                return Outcome.PENDING;
            }
            if (resp.statusCode() != 200) {
                return Outcome.REFUSED;
            }
            Map<?, ?> c = JsonSerialization.readValue(resp.body(), Map.class);
            boolean attested = Objects.equals(c.get("id"), challengeId)
                    && Objects.equals(c.get("partyId"), partyId)
                    && Objects.equals(c.get("purpose"), "MCP_CLIENT_CONNECT")
                    && Objects.equals(c.get("status"), "COMPLETED")
                    && c.get("consumedAt") instanceof String consumed && !consumed.isBlank();
            return attested ? Outcome.CONSUMED : Outcome.REFUSED;
        } catch (IOException | RuntimeException e) {
            return Outcome.REFUSED;
        }
    }

    private URI uri(String path) {
        return URI.create(settings.scaUrl().replaceAll("/+$", "") + path);
    }

    private HttpResponse<String> send(HttpRequest.Builder builder, Object body) throws IOException {
        try {
            return HTTP.send(builder.timeout(TIMEOUT)
                    .header("Authorization", "Bearer " + token())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JsonSerialization.writeValueAsString(body)))
                    .build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    private String token() throws IOException, InterruptedException {
        String key = settings.tokenUrl() + "|" + settings.clientId();
        CachedToken t = cached;
        long now = System.currentTimeMillis();
        if (t != null && t.key().equals(key) && t.expiresAtMillis() > now + 30_000) {
            return t.value();
        }
        String form = "grant_type=client_credentials&client_id="
                + URLEncoder.encode(settings.clientId(), StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(settings.clientSecret(), StandardCharsets.UTF_8);
        HttpResponse<String> resp = HTTP.send(HttpRequest.newBuilder(URI.create(settings.tokenUrl()))
                .timeout(TIMEOUT).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IOException("token endpoint answered " + resp.statusCode());
        }
        Map<?, ?> tokens = JsonSerialization.readValue(resp.body(), Map.class);
        Object access = tokens.get("access_token");
        if (!(access instanceof String value) || value.isBlank()) {
            throw new IOException("token endpoint returned no access_token");
        }
        long ttl = tokens.get("expires_in") instanceof Number n ? n.longValue() : 60;
        cached = new CachedToken(key, value, now + ttl * 1000);
        return value;
    }
}
