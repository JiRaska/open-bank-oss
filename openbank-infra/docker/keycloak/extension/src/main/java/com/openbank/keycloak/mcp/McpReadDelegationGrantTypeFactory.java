// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.keycloak.mcp;

import java.net.URI;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.Set;
import java.util.function.Function;

import jakarta.ws.rs.core.Response;
import org.keycloak.Config;
import org.keycloak.events.EventType;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.protocol.oidc.grants.OAuth2GrantType;
import org.keycloak.protocol.oidc.grants.OAuth2GrantTypeFactory;
import org.keycloak.services.CorsErrorResponseException;

public final class McpReadDelegationGrantTypeFactory implements OAuth2GrantTypeFactory {
    private OAuth2GrantType grant = new DisabledGrant();

    @Override
    public void init(Config.Scope config) {
        grant = configured(System::getenv);
    }

    static OAuth2GrantType configured(Function<String, String> env) {
        if (!"true".equals(env.apply("MCP_READ_GRANT_ENABLED"))) return new DisabledGrant();
        try {
            byte[] nonceKey = key(env.apply("MCP_READ_NONCE_KEY"));
            byte[] proofKey = key(env.apply("MCP_READ_PROOF_KEY"));
            if (MessageDigest.isEqual(nonceKey, proofKey)) return new DisabledGrant();
            String binding = env.apply("MCP_READ_CONNECTION_BINDING_ENABLED");
            if (binding != null && !"true".equals(binding) && !"false".equals(binding)) return new DisabledGrant();
            Clock clock = Clock.systemUTC();
            var evidence = new McpReadDelegationEvidence(
                    https(env.apply("MCP_READ_CUSTOMER_ISSUER_URL")).toASCIIString(),
                    https(env.apply("MCP_READ_RESOURCE_URL")), clock, new McpSignedResourceNonce(nonceKey, clock),
                    "true".equals(binding));
            var credentials = new McpReadIssuerCredentials(https(env.apply("MCP_READ_ISSUER_TOKEN_URL")),
                    required(env.apply("MCP_READ_ISSUER_CLIENT_SECRET")));
            return new McpReadDelegationGrantType(evidence,
                    new McpBalancePermitClient(https(env.apply("MCP_READ_DELEGATION_ORIGIN")), clock),
                    new McpIssuerProofReference(proofKey, required(env.apply("MCP_READ_DEPLOYMENT"))), credentials);
        } catch (IllegalArgumentException e) {
            return new DisabledGrant();
        }
    }

    private static URI https(String value) {
        URI uri = URI.create(required(value));
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("expected a configured HTTPS URL");
        }
        return uri;
    }

    private static byte[] key(String value) {
        return Base64.getUrlDecoder().decode(required(value));
    }

    private static String required(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("required configuration");
        return value;
    }

    @Override
    public OAuth2GrantType create(KeycloakSession session) {
        return grant;
    }

    @Override
    public String getId() {
        return McpReadDelegationRequest.GRANT_TYPE;
    }

    @Override
    public String getShortcut() {
        return "obmcp";
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
    }

    @Override
    public void close() {
    }

    private static final class DisabledGrant implements OAuth2GrantType {
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
            throw new CorsErrorResponseException(context.getCors(), "unsupported_grant_type",
                    "MCP read delegation is not configured", Response.Status.BAD_REQUEST);
        }

        @Override
        public void close() {
        }
    }
}
