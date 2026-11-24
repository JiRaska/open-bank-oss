// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.keycloak.mcp;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.keycloak.common.VerificationException;
import org.keycloak.jose.jwk.JWK;
import org.keycloak.jose.jws.JWSInput;
import org.keycloak.jose.jws.JWSInputException;
import org.keycloak.jose.jws.crypto.HashUtils;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.representations.AccessToken;
import org.keycloak.representations.dpop.DPoP;
import org.keycloak.services.util.DPoPUtil;
import org.keycloak.util.JWKSUtils;

/** Validates original resource evidence without authorizing downstream issuance. */
final class McpReadDelegationEvidence {
    static final String CONNECTION_CLAIM = "openbank_mcp_connection_id";

    private static final String AUDIENCE = "openbank-mcp-service";
    private static final String METHOD = "POST";
    private static final int MAX_AGE_SECONDS = DPoPUtil.DEFAULT_PROOF_LIFETIME;
    private static final int FUTURE_SKEW_SECONDS = 5;

    enum NonceStatus {
        VALID, INVALID, UNAVAILABLE
    }

    @FunctionalInterface
    interface NonceAuthority {
        /** Checks provenance and expiry against trusted issuer state. */
        NonceStatus verify(String nonce, String keyThumbprint, String resource);
    }

    private final String issuer;
    private final String resource;
    private final Clock clock;
    private final NonceAuthority nonceAuthority;
    private final boolean connectionBinding;

    McpReadDelegationEvidence(String issuer, URI resource, Clock clock, NonceAuthority nonceAuthority,
                              boolean connectionBinding) {
        if (!"https".equals(resource.getScheme()) || resource.getHost() == null
                || resource.getRawUserInfo() != null || resource.getRawQuery() != null
                || resource.getRawFragment() != null || !"/customer/mcp".equals(resource.getRawPath())) {
            throw new IllegalArgumentException("expected the canonical customer MCP HTTPS resource");
        }
        this.issuer = Objects.requireNonNull(issuer);
        this.resource = resource.toASCIIString();
        this.clock = Objects.requireNonNull(clock);
        this.nonceAuthority = Objects.requireNonNull(nonceAuthority);
        this.connectionBinding = connectionBinding;
    }

    record Verified(String subject, UUID partyId, String clientId, String keyThumbprint, String proofId,
                    String issuer, Instant validUntil, UUID connectionId) {
        @Override
        public String toString() {
            return "VerifiedMcpDpop[redacted]";
        }
    }

    /**
     * Keycloak verifies the subject token with its realm signing keys. Validation is for the
     * original resource URI, never for the request to this internal grant endpoint.
     */
    Verified verify(KeycloakSession session, McpReadDelegationRequest request) throws VerificationException {
        try {
            AccessToken token = session.tokens().decode(request.subjectToken(), AccessToken.class);
            long now = clock.instant().getEpochSecond();
            String thumbprint = validateToken(session, token, now);
            UUID partyId = McpReadDelegationRequest.canonicalUuid(token.getOtherClaims().get("party_id"));
            UUID connectionId = verifiedConnection(token, request.connectionId());
            JWSInput input = new JWSInput(request.resourceProof());
            String nonce = validateClaims(input, request.subjectToken(), thumbprint, now);

            // The validator performs signature verification, claims checks and a Keycloak
            // single-use check. This store is not the durable issuance ledger.
            DPoP proof = new DPoPUtil.Validator(session).dPoP(request.resourceProof())
                    .accessToken(request.subjectToken()).uri(resource).method(METHOD).validate();
            DPoPUtil.validateBinding(token, proof);
            validateNonce(nonce, thumbprint);
            return new Verified(token.getSubject(), partyId, token.getIssuedFor(), thumbprint, proof.getId(),
                    issuer, Instant.ofEpochSecond(token.getExp()), connectionId);
        } catch (NonceUnavailable e) {
            throw e;
        } catch (VerificationException | JWSInputException | java.net.URISyntaxException | RuntimeException e) {
            // Avoid propagating messages from JOSE parsers, providers or storage.
            throw new VerificationException("Original MCP DPoP evidence was not verified");
        }
    }

    private UUID verifiedConnection(AccessToken token, UUID requested) throws VerificationException {
        if (!connectionBinding) {
            if (requested != null || token.getOtherClaims().containsKey(CONNECTION_CLAIM)) {
                throw invalid();
            }
            return null;
        }
        UUID claim = McpReadDelegationRequest.canonicalUuid(token.getOtherClaims().get(CONNECTION_CLAIM));
        if (!claim.equals(requested)) {
            throw invalid();
        }
        return claim;
    }

    private String validateToken(KeycloakSession session, AccessToken token, long now) throws VerificationException {
        if (token == null || !issuer.equals(token.getIssuer()) || !DPoPUtil.DPOP_TOKEN_TYPE.equals(token.getType())
                || !Arrays.equals(new String[] {AUDIENCE}, token.getAudience()) || token.getExp() == null
                || token.getExp() <= now || !token.isActive() || !nonBlank(token.getSubject())
                || !nonBlank(token.getIssuedFor()) || token.getConfirmation() == null) {
            throw invalid();
        }
        ClientModel client = session.getContext().getRealm().getClientByClientId(token.getIssuedFor());
        String thumbprint = token.getConfirmation().getKeyThumbprint();
        if (client == null || !client.isEnabled()
                || !"L".equals(client.getAttribute(McpConnectPolicy.TIER_ATTRIBUTE))
                || thumbprint == null || !thumbprint.matches("[A-Za-z0-9_-]{43}")) {
            throw invalid();
        }
        return thumbprint;
    }

    private String validateClaims(JWSInput input, String subjectToken, String thumbprint, long now)
            throws JWSInputException, VerificationException {
        var header = input.getHeader();
        JWK key = header.getKey();
        if (!"ES256".equals(String.valueOf(header.getAlgorithm())) || key == null
                || !thumbprint.equals(JWKSUtils.computeThumbprint(key))) {
            throw invalid();
        }
        Map<?, ?> claims = input.readJsonContent(Map.class);
        String expectedHash = HashUtils.accessTokenHash("ES256", subjectToken, true);
        Object timestamp = claims.get("iat");
        if (!METHOD.equals(claims.get("htm")) || !resource.equals(claims.get("htu"))
                || !expectedHash.equals(claims.get("ath"))
                || (!(timestamp instanceof Integer) && !(timestamp instanceof Long))
                || !fresh(((Number) timestamp).longValue(), now)
                || !(claims.get("jti") instanceof String id) || !nonBlank(id) || id.length() > 128
                || !(claims.get("nonce") instanceof String nonce) || !nonBlank(nonce) || nonce.length() > 256) {
            throw invalid();
        }
        return nonce;
    }

    private void validateNonce(String nonce, String thumbprint) throws VerificationException {
        NonceStatus status;
        try {
            status = nonceAuthority.verify(nonce, thumbprint, resource);
        } catch (RuntimeException e) {
            throw new NonceUnavailable();
        }
        if (status == null || status == NonceStatus.UNAVAILABLE) {
            throw new NonceUnavailable();
        }
        if (status != NonceStatus.VALID) {
            throw invalid();
        }
    }

    private static boolean fresh(long iat, long now) {
        return iat >= now - MAX_AGE_SECONDS && iat <= now + FUTURE_SKEW_SECONDS;
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static VerificationException invalid() {
        return new VerificationException("Original MCP DPoP evidence was not verified");
    }

    static final class NonceUnavailable extends VerificationException {
        private static final long serialVersionUID = 1L;

        NonceUnavailable() {
            super("MCP resource nonce authority is unavailable");
        }
    }
}
