// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.keycloak.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.keycloak.util.JsonSerialization;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Delegation's internal, machine-authenticated authority for a V2 connect. */
final class McpConnectAuthorityClient {
    private static final String REQUESTS = "/api/v1/delegations/mcp-connect/requests";

    private final String origin;
    private final ScaClient transport;

    McpConnectAuthorityClient(String origin, ScaClient transport) {
        URI uri = URI.create(origin);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !List.of("", "/").contains(uri.getRawPath())) {
            throw new IllegalArgumentException("expected the HTTPS origin of the connect authority");
        }
        this.origin = origin.replaceAll("/+$", "");
        this.transport = transport;
    }

    record Request(McpConnectContext base, String partyId, String parJkt) {
        Request {
            if (base == null || canonicalUuid(partyId) == null) {
                throw new IllegalArgumentException("connect identity is incomplete");
            }
            McpConnectContextV2.requireCanonicalThumbprint(parJkt);
        }

        String reference() {
            return base.requestUriSha256();
        }

        String approvalRequestId() {
            return "mcp-connect:" + reference();
        }

        Map<String, Object> identity() {
            return Map.of("partyId", partyId, "clientId", base.clientId(), "parJkt", parJkt);
        }

        Map<String, Object> registration() {
            var body = new LinkedHashMap<>(identity());
            body.put("requestRef", reference());
            body.put("redirectHost", base.redirectHost());
            body.put("resource", base.resource());
            body.put("scopes", List.of(base.scopes().split(" ")));
            body.put("expiresAtEpochSecond", base.expiresAt());
            return body;
        }
    }

    record Challenge(String id, String contextSha256) {
        Challenge {
            if (canonicalUuid(id) == null || !validReference(contextSha256)) {
                throw new IllegalArgumentException("expected a canonical challenge and digest");
            }
        }

        Map<String, Object> binding(Request request) {
            var body = new LinkedHashMap<>(request.identity());
            body.put("approvalRequestId", request.approvalRequestId());
            body.put("challengeId", id);
            body.put("contextSha256", contextSha256);
            return body;
        }
    }

    /**
     * This reference must come from the verified, issued Keycloak refresh family. It does not
     * identify a browser tab, authentication session or whole client session.
     */
    record FamilyReference(String value) {
        FamilyReference {
            if (!validReference(value)) {
                throw new IllegalArgumentException("expected a verified refresh family reference");
            }
        }
    }

    record Activation(UUID connectionId, FamilyReference family) {
    }

    void register(Request request) throws IOException {
        JsonNode response = post(REQUESTS, request.registration(), 200, 201);
        requireIdentity(request, response);
        if (!textIs(response, "status", "PENDING_SELECTION")
                || !textIs(response, "redirectHost", request.base().redirectHost())
                || !textIs(response, "resource", request.base().resource())
                || !canonicalScopes(response).equals(request.base().scopes())) {
            throw new IOException("connect authority did not register a pending request");
        }
    }

    List<McpConnectContextV2.AccountScope> selectedAccounts(Request request) throws IOException {
        return selectionOf(request, post(path(request, "/selection"), request.identity(), 200));
    }

    void bindChallenge(Request request, Challenge challenge) throws IOException {
        JsonNode response = post(path(request, "/challenge"), challenge.binding(request), 200, 201);
        requireChallenge(request, challenge, response, "CHALLENGE_BOUND");
    }

    void stage(Request request, Challenge challenge, String codeBindingRef) throws IOException {
        var body = challenge.binding(request);
        body.put("codeBindingRef", codeBindingRef);
        JsonNode response = post(path(request, "/stage"), body, 200, 201);
        requireChallenge(request, challenge, response, "APPROVED_AWAITING_FAMILY");
        if (!textIs(response, "codeBindingRef", codeBindingRef)) {
            throw new IOException("connect staging did not attest the code binding");
        }
    }

    record Stage(Request request, Challenge challenge, String codeBindingRef) {
        Stage {
            if (!validReference(codeBindingRef)) {
                throw new IllegalArgumentException("invalid staged code binding");
            }
        }
    }

    Stage staged(String partyId, String clientId, String jkt, String codeBindingRef) throws IOException {
        JsonNode response = post(REQUESTS + "/staged",
                Map.of("partyId", partyId, "clientId", clientId, "parJkt", jkt, "codeBindingRef", codeBindingRef), 200);
        // The bank authority binds the same immutable registered context to the verified code reference.
        var base = new McpConnectContext(clientId, response.path("redirectHost").textValue(),
                response.path("resource").textValue(), canonicalScopes(response),
                response.path("requestRef").textValue(), response.path("expiresAtEpochSecond").longValue());
        Request request = new Request(base, partyId, jkt);
        Challenge challenge = new Challenge(requiredText(response, "challengeId"),
                requiredText(response, "contextSha256"));
        requireChallenge(request, challenge, response, "APPROVED_AWAITING_FAMILY");
        if (!textIs(response, "codeBindingRef", codeBindingRef)) {
            throw new IOException("connect staging did not attest the code binding");
        }
        return new Stage(request, challenge, codeBindingRef);
    }

    UUID activeFamily(String partyId, String clientId, String jkt, FamilyReference family) throws IOException {
        JsonNode response = post(REQUESTS + "/family-status",
                Map.of("partyId", partyId, "clientId", clientId, "parJkt", jkt, "refreshFamilyRef", family.value()), 200);
        UUID connection = canonicalUuid(response.path("connectionId").textValue());
        if (!textIs(response, "status", "ACTIVE") || !textIs(response, "partyId", partyId)
                || !textIs(response, "clientId", clientId) || !textIs(response, "parJkt", jkt)
                || !textIs(response, "refreshFamilyRef", family.value()) || connection == null) {
            throw new IOException("connect authority did not attest an active refresh family");
        }
        return connection;
    }

    Activation activate(Stage stage, FamilyReference family) throws IOException {
        var body = stage.challenge().binding(stage.request());
        body.put("codeBindingRef", stage.codeBindingRef());
        body.put("refreshFamilyRef", family.value());
        JsonNode response = post(path(stage.request(), "/activate"), body, 200, 201);
        requireChallenge(stage.request(), stage.challenge(), response, "ACTIVE");
        if (!textIs(response, "codeBindingRef", stage.codeBindingRef())) {
            throw new IOException("connect activation did not attest the staged code");
        }
        if (!textIs(response, "refreshFamilyRef", family.value())) {
            throw new IOException("connect activation did not attest the refresh family");
        }
        UUID id = canonicalUuid(response.path("connectionId").textValue());
        if (id == null) {
            throw new IOException("connect activation did not attest a connection");
        }
        return new Activation(id, family);
    }

    private static boolean validReference(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static UUID canonicalUuid(String value) {
        if (value == null || value.length() != 36) {
            return null;
        }
        try {
            UUID id = UUID.fromString(value);
            return id.toString().equals(value) ? id : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String canonicalScopes(JsonNode response) throws IOException {
        JsonNode array = response.path("scopes");
        if (!array.isArray() || array.isEmpty()) {
            throw new IOException("connect authority response requires a scope array");
        }
        var scopes = new ArrayList<String>();
        String previous = "";
        for (JsonNode item : array) {
            String scope = item.textValue();
            if (scope == null || !scope.matches("[A-Za-z0-9._:/-]{1,100}") || "offline_access".equals(scope)
                    || previous.compareTo(scope) >= 0) {
                throw new IOException("connect authority did not attest canonical scopes");
            }
            scopes.add(scope);
            previous = scope;
        }
        return String.join(" ", scopes);
    }

    private static String requiredText(JsonNode response, String field) throws IOException {
        String value = response.path(field).textValue();
        if (value == null || value.isBlank()) {
            throw new IOException("connect authority response is incomplete");
        }
        return value;
    }

    static List<McpConnectContextV2.AccountScope> selectionOf(Request request, JsonNode response) throws IOException {
        requireIdentity(request, response);
        JsonNode accounts = response.path("accounts");
        boolean noAccounts = accounts.isMissingNode() || (accounts.isArray() && accounts.isEmpty());
        if (textIs(response, "status", "PENDING_SELECTION") && noAccounts) {
            return List.of();
        }
        if (!textIs(response, "status", "SELECTED") || !accounts.isArray() || accounts.isEmpty()) {
            throw new IOException("connect selection was not attested");
        }
        var selected = new ArrayList<McpConnectContextV2.AccountScope>();
        for (JsonNode account : accounts) {
            if (!account.path("accountId").isTextual() || !account.path("capabilities").isArray()) {
                throw new IOException("connect selection has an invalid account scope");
            }
            var capabilities = new ArrayList<String>();
            for (JsonNode capability : account.path("capabilities")) {
                if (!capability.isTextual()) {
                    throw new IOException("connect selection has an invalid capability");
                }
                capabilities.add(capability.textValue());
            }
            selected.add(new McpConnectContextV2.AccountScope(account.path("accountId").textValue(),
                    List.copyOf(capabilities)));
        }
        return List.copyOf(selected);
    }

    static void requireChallenge(Request request, Challenge challenge, JsonNode response, String expectedStatus)
            throws IOException {
        requireIdentity(request, response);
        if (!textIs(response, "status", expectedStatus)
                || !textIs(response, "approvalRequestId", request.approvalRequestId())
                || !textIs(response, "challengeId", challenge.id())
                || !textIs(response, "contextSha256", challenge.contextSha256())) {
            throw new IOException("connect challenge binding was not attested");
        }
    }

    private static void requireIdentity(Request request, JsonNode response) throws IOException {
        JsonNode expiry = response.path("expiresAtEpochSecond");
        if (!textIs(response, "requestRef", request.reference())
                || !textIs(response, "partyId", request.partyId())
                || !textIs(response, "clientId", request.base().clientId())
                || !textIs(response, "parJkt", request.parJkt())
                || !expiry.isIntegralNumber() || !expiry.canConvertToLong()
                || expiry.longValue() != request.base().expiresAt()) {
            throw new IOException("connect authority did not attest the exact request");
        }
    }

    private static boolean textIs(JsonNode response, String name, String expected) {
        return expected.equals(response.path(name).textValue());
    }

    private static String path(Request request, String suffix) {
        return REQUESTS + "/" + request.reference() + suffix;
    }

    private JsonNode post(String path, Object body, int... successStatuses) throws IOException {
        HttpResponse<String> response = transport.postWithBankIdentity(HttpRequest.newBuilder(URI.create(origin + path)),
                body);
        for (int allowed : successStatuses) {
            if (response.statusCode() == allowed) {
                return JsonSerialization.readValue(response.body(), JsonNode.class);
            }
        }
        throw new IOException("connect authority did not return successful evidence");
    }
}
