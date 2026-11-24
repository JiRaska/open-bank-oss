// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.keycloak.mcp;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import jakarta.ws.rs.core.Response;
import org.keycloak.authentication.authenticators.client.X509ClientAuthenticator;
import org.keycloak.common.VerificationException;
import org.keycloak.events.EventType;
import org.keycloak.models.ClientModel;
import org.keycloak.protocol.oidc.grants.OAuth2GrantType;
import org.keycloak.services.CorsErrorResponseException;

/** Internal OAuth grant implementation. Registration is kept separate from the grant itself. */
final class McpReadDelegationGrantType implements OAuth2GrantType {
    private final McpReadDelegationEvidence evidence;
    private final McpBalancePermitClient permits;
    private final McpIssuerProofReference proofReferences;
    private final Supplier<String> issuerToken;

    McpReadDelegationGrantType(McpReadDelegationEvidence evidence, McpBalancePermitClient permits,
                               McpIssuerProofReference proofReferences, Supplier<String> issuerToken) {
        this.evidence = Objects.requireNonNull(evidence);
        this.permits = Objects.requireNonNull(permits);
        this.proofReferences = Objects.requireNonNull(proofReferences);
        this.issuerToken = Objects.requireNonNull(issuerToken);
    }

    @Override
    public EventType getEventType() {
        return EventType.TOKEN_EXCHANGE;
    }

    @Override
    public Set<String> getTokenParameterNames() {
        return Set.of(McpReadDelegationRequest.SUBJECT_TOKEN, McpReadDelegationRequest.RESOURCE_PROOF);
    }

    @Override
    public boolean isConfidentialOnlyGrantType() {
        return true;
    }

    @Override
    public Response process(Context context) {
        ClientModel client = context.getClient();
        if (client == null || !McpReadDelegationRequest.admitsCaller(
                context.getRealm().getName(), client.getClientId(), client.isEnabled(), client.isPublicClient(),
                client.getClientAuthenticatorType(),
                configured(client, X509ClientAuthenticator.ATTR_SUBJECT_DN)
                        && !Boolean.parseBoolean(client.getAttribute(
                                X509ClientAuthenticator.ATTR_ALLOW_REGEX_PATTERN_COMPARISON)),
                configured(client, X509ClientAuthenticator.ATTR_CA_SUBJECT_DN))) {
            throw new CorsErrorResponseException(context.getCors(), "unauthorized_client",
                    "Client is not authorized for read delegation", Response.Status.FORBIDDEN);
        }
        McpReadDelegationRequest request;
        try {
            request = McpReadDelegationRequest.parse(context.getFormParams());
        } catch (IllegalArgumentException e) {
            throw new CorsErrorResponseException(context.getCors(), "invalid_request",
                    "Invalid read delegation request", Response.Status.BAD_REQUEST);
        }
        try {
            var session = context.getSession();
            var confirmation = McpReadClientCertificate.confirmation(
                    context.getRequest().getClientCertificateChain(),
                    client.getAttribute(X509ClientAuthenticator.ATTR_SUBJECT_DN),
                    client.getAttribute(X509ClientAuthenticator.ATTR_CA_SUBJECT_DN));
            if (confirmation == null) {
                throw new CorsErrorResponseException(context.getCors(), "invalid_grant",
                        "A dedicated MCP read client certificate is required", Response.Status.BAD_REQUEST);
            }
            var verified = evidence.verify(session, request);
            String token = issuerToken.get();
            if (token == null || token.isBlank()) throw unavailable(context);
            String proofKey = proofReferences.key(context.getRealm().getId(), verified);
            var permit = permits.authorize(token, verified, request.accountId(), proofKey);
            Instant now = Instant.now();
            var readToken = McpBalanceReadToken.create(verified, permit, confirmation, now);
            return context.getCors().add(Response.ok(Map.of(
                    "access_token", session.tokens().encode(readToken),
                    "token_type", "Bearer",
                    "expires_in", readToken.getExp() - now.getEpochSecond(),
                    "scope", McpBalanceReadToken.SCOPE))
                    .header("Cache-Control", "no-store")
                    .header("Pragma", "no-cache"));
        } catch (McpReadDelegationEvidence.NonceUnavailable | IOException e) {
            throw unavailable(context);
        } catch (VerificationException e) {
            throw new CorsErrorResponseException(context.getCors(), "invalid_grant",
                    "Original MCP DPoP evidence was not verified", Response.Status.BAD_REQUEST);
        }
    }

    private static CorsErrorResponseException unavailable(Context context) {
        return new CorsErrorResponseException(context.getCors(), "temporarily_unavailable",
                "Read delegation issuance is unavailable", Response.Status.SERVICE_UNAVAILABLE);
    }

    private static boolean configured(ClientModel client, String attribute) {
        String value = client.getAttribute(attribute);
        return value != null && !value.isBlank();
    }

    @Override
    public void close() {
    }
}
