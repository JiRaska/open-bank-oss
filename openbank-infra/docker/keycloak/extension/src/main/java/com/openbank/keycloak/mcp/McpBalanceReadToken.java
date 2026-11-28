// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.keycloak.mcp;

import org.keycloak.representations.AccessToken;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Claims issued only after authentication, original DPoP verification and a committed permit. */
final class McpBalanceReadToken {
    static final String SCOPE = "mcp-customer-balance-read";
    private static final String AUDIENCE = "openbank-balance-service";
    private static final long MAX_SECONDS = 60;

    private McpBalanceReadToken() {
    }

    static AccessToken create(McpReadDelegationEvidence.Verified evidence, McpBalancePermitClient.Permit permit,
                              AccessToken.Confirmation certificate, Instant now) throws IOException {
        long iat = now.getEpochSecond();
        long exp = Math.min(iat + MAX_SECONDS,
                Math.min(evidence.validUntil().getEpochSecond(), permit.validUntil().getEpochSecond()));
        if (exp <= iat || certificate == null || certificate.getCertThumbprint() == null
                || !certificate.getCertThumbprint().matches("[A-Za-z0-9_-]{43}") || certificate.getKeyThumbprint() != null
                || !Objects.equals(evidence.connectionId(), permit.connectionId())) {
            throw new IOException("No valid certificate-bound balance-read token can be issued");
        }

        var token = new AccessToken();
        token.id(UUID.randomUUID().toString());
        token.issuer(evidence.issuer());
        token.subject(evidence.subject());
        token.issuedFor(McpReadDelegationRequest.CLIENT_ID);
        token.audience(AUDIENCE);
        token.type("Bearer");
        token.iat(iat);
        token.exp(exp);
        token.setScope(SCOPE);
        token.setConfirmation(certificate);
        token.setOtherClaims("party_id", evidence.partyId().toString());
        token.setOtherClaims("account_id", permit.accountId().toString());
        token.setOtherClaims("grant_id", permit.grantId().toString());
        if (permit.connectionId() != null) {
            token.setOtherClaims(McpReadDelegationEvidence.CONNECTION_CLAIM, permit.connectionId().toString());
        }
        token.setOtherClaims("act", Map.of("sub", McpReadDelegationRequest.CLIENT_ID,
                "act", Map.of("sub", evidence.clientId())));
        return token;
    }
}
