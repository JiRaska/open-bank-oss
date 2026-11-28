// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.keycloak.mcp;

import java.time.Duration;
import java.util.Set;

import org.keycloak.models.ClientModel;

/**
 * ADR-0385 D2.3/D3 decisions, kept free of Keycloak types so they are unit-tested directly.
 * Every bound here is a hard floor or ceiling: configuration may only make it stricter.
 */
public final class McpConnectPolicy {
    /** The client attribute every derived MCP client carries (gen-mcp-realm-clients.py). */
    public static final String TIER_ATTRIBUTE = "openbank.mcp.tier";
    /** The resource the client is bound to (RFC 8707), derived from the allowlist. */
    public static final String RESOURCE_ATTRIBUTE = "openbank.mcp.resource";

    /** D3.6: the auth session may not outlive the 300 s SCA challenge TTL. */
    public static final long MAX_SESSION_SECONDS = 300;
    /** D2.3: a passkey enrolled less than 72 h ago cannot authorize a connect. */
    public static final Duration MIN_PASSKEY_AGE = Duration.ofHours(72);
    /** D3.7: the poll endpoint answers at most once per this interval per auth session. */
    public static final long MIN_POLL_INTERVAL_MS = 2_000;
    /** D3.7: and at most this many times in one session (300 s / 2 s). */
    public static final int MAX_POLLS = 150;

    private McpConnectPolicy() {
    }

    public static boolean isMcpClient(String tierAttribute) {
        return tierAttribute != null && !tierAttribute.isBlank();
    }

    private static final Set<String> BANK_ADAPTERS = Set.of("mcp-bank-hosted-chatgpt", "mcp-bank-hosted-claude");
    private static final String BANK_ADAPTER_CALLBACK = "https://mcp.open-bank.tech/oauth/hosted/callback";
    private static final String PROFILE_ATTRIBUTE = "openbank.mcp.profile";

    private static boolean bankAdapterId(ClientModel client) {
        String id = client.getClientId();
        return id != null && BANK_ADAPTERS.contains(id);
    }

    static boolean bankAdapterReserved(ClientModel client) {
        return bankAdapterId(client)
                || "bank-hosted-v2".equals(client.getAttribute(PROFILE_ATTRIBUTE));
    }

    static boolean bankAdapterAllowed(ClientModel client) {
        return bankAdapterId(client) && client.isEnabled()
                && "bank-hosted-v2".equals(client.getAttribute(PROFILE_ATTRIBUTE))
                && "H".equals(client.getAttribute(TIER_ATTRIBUTE)) && !client.isPublicClient()
                && "client-jwt".equals(client.getClientAuthenticatorType())
                && "true".equals(client.getAttribute("use.jwks.string"))
                && "false".equals(client.getAttribute("use.jwks.url"))
                && "ES256".equals(client.getAttribute("token.endpoint.auth.signing.alg"))
                && "true".equals(client.getAttribute("dpop.bound.access.tokens"))
                && "true".equals(client.getAttribute("require.pushed.authorization.requests"))
                && "S256".equals(client.getAttribute("pkce.code.challenge.method"))
                && Set.of(BANK_ADAPTER_CALLBACK).equals(client.getRedirectUris());
    }

    /** Lifespan in seconds: configuration may shorten it, never lengthen it past 300 s. */
    public static long sessionLifespan(long configured) {
        return configured <= 0 ? MAX_SESSION_SECONDS : Math.min(configured, MAX_SESSION_SECONDS);
    }

    public static boolean sessionExpired(long startedEpochSeconds, long nowEpochSeconds, long lifespanSeconds) {
        return startedEpochSeconds <= 0 || nowEpochSeconds < startedEpochSeconds
                || nowEpochSeconds - startedEpochSeconds >= sessionLifespan(lifespanSeconds);
    }

    /** A credential with no creation time is treated as brand new (fail closed). */
    public static boolean passkeyOldEnough(Long createdEpochMillis, long nowEpochMillis) {
        return createdEpochMillis != null && createdEpochMillis > 0
                && nowEpochMillis - createdEpochMillis >= MIN_PASSKEY_AGE.toMillis();
    }

    public static boolean pollTooSoon(long lastPollEpochMillis, long nowEpochMillis) {
        return lastPollEpochMillis > 0 && nowEpochMillis - lastPollEpochMillis < MIN_POLL_INTERVAL_MS;
    }

    /** True when any requested audience/resource is one of the MCP targets. */
    public static boolean targetsAny(java.util.List<String> requested, java.util.Set<String> mcpTargets) {
        return requested != null && mcpTargets != null && requested.stream().anyMatch(mcpTargets::contains);
    }

    /** The requested RFC 8707 resource: absent, or exactly the one the client is bound to. */
    public static boolean resourceAllowed(java.util.List<String> requested, String bound) {
        if (requested == null || requested.isEmpty()) {
            return true;
        }
        return bound != null && requested.stream().allMatch(bound::equals);
    }
}
