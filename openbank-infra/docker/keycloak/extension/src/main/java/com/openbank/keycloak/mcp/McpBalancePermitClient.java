// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.keycloak.mcp;

import org.keycloak.util.JsonSerialization;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Calls delegation-service after the issuer has verified the original request evidence. */
final class McpBalancePermitClient {
    private static final Duration TIMEOUT = Duration.ofSeconds(4);
    private static final int MAX_RESPONSE_BYTES = 4096;
    private static final HttpClient HTTP = McpOutboundHttp.create(TIMEOUT);

    private final URI endpoint;
    private final Clock clock;

    McpBalancePermitClient(URI origin, Clock clock) {
        if (!"https".equals(origin.getScheme()) || origin.getHost() == null || origin.getRawUserInfo() != null
                || origin.getRawQuery() != null || origin.getRawFragment() != null
                || !(origin.getRawPath().isEmpty() || "/".equals(origin.getRawPath()))) {
            throw new IllegalArgumentException("expected an internal delegation HTTPS origin");
        }
        this.endpoint = origin.resolve("/api/v1/delegations/mcp-client/authorize-balance-read");
        this.clock = clock;
    }

    record Permit(UUID grantId, UUID accountId, Instant validUntil, UUID connectionId) {
    }

    Permit authorize(String issuerToken, McpReadDelegationEvidence.Verified evidence,
                     UUID accountId, String proofKey) throws IOException {
        var body = permitBody(evidence, accountId, proofKey);
        var request = HttpRequest.newBuilder(endpoint).timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", proofKey)
                .header("Authorization", "Bearer " + issuerToken)
                .POST(HttpRequest.BodyPublishers.ofString(JsonSerialization.writeValueAsString(body)))
                .build();
        try {
            var response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
            return checkedPermit(response.statusCode(), response.body(), accountId, evidence.connectionId(), clock.instant());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (IOException e) {
            throw unavailable();
        }
    }

    static Map<String, String> permitBody(McpReadDelegationEvidence.Verified evidence, UUID accountId, String proofKey) {
        var body = new HashMap<>(Map.of("partyId", evidence.partyId().toString(), "clientId", evidence.clientId(),
                "keyThumbprint", evidence.keyThumbprint(), "accountId", accountId.toString(), "proofKey", proofKey));
        if (evidence.connectionId() != null) {
            body.put("connectionId", evidence.connectionId().toString());
        }
        return body;
    }

    static Permit checkedPermit(int status, byte[] body, UUID accountId, UUID connectionId, Instant now)
            throws IOException {
        if (status != 200 || body.length > MAX_RESPONSE_BYTES) throw unavailable();
        try {
            Map<?, ?> values = JsonSerialization.readValue(body, Map.class);
            UUID grantId = McpReadDelegationRequest.canonicalUuid(values.get("grantId"));
            UUID permittedAccount = McpReadDelegationRequest.canonicalUuid(values.get("accountId"));
            Object rawConnection = values.get("connectionId");
            UUID permittedConnection = rawConnection == null ? null : McpReadDelegationRequest.canonicalUuid(rawConnection);
            Instant validUntil = OffsetDateTime.parse((String) values.get("validUntil")).toInstant();
            if (!accountId.equals(permittedAccount) || !validUntil.isAfter(now)
                    || !Objects.equals(connectionId, permittedConnection)) throw unavailable();
            return new Permit(grantId, permittedAccount, validUntil, permittedConnection);
        } catch (RuntimeException | IOException e) {
            throw unavailable();
        }
    }

    private static IOException unavailable() {
        return new IOException("No valid balance-read permit was returned");
    }
}
