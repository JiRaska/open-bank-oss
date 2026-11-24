// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.keycloak.mcp;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Verifies MCP-issued nonces with a key dedicated to nonce authentication. */
final class McpSignedResourceNonce implements McpReadDelegationEvidence.NonceAuthority {
    private static final String ALGORITHM = "HmacSHA256";
    private static final long MAX_FUTURE_SECONDS = 120;
    private final SecretKeySpec key;
    private final Clock clock;

    McpSignedResourceNonce(byte[] secret, Clock clock) {
        if (secret.length < 32) throw new IllegalArgumentException("nonce key is too short");
        this.key = new SecretKeySpec(secret, ALGORITHM);
        this.clock = clock;
    }

    @Override
    public McpReadDelegationEvidence.NonceStatus verify(String nonce, String thumbprint, String resource) {
        String[] fields = nonce.split("\\.", -1);
        if (fields.length != 4 || !"v1".equals(fields[0]) || !fields[1].matches("[1-9][0-9]{0,11}")
                || !fields[2].matches("[A-Za-z0-9_-]{22}") || !fields[3].matches("[A-Za-z0-9_-]{43}")) {
            return McpReadDelegationEvidence.NonceStatus.INVALID;
        }
        long expires = Long.parseLong(fields[1]);
        long now = clock.instant().getEpochSecond();
        if (expires <= now || expires > now + MAX_FUTURE_SECONDS) {
            return McpReadDelegationEvidence.NonceStatus.INVALID;
        }
        String input = String.join("\n", "mcp-resource-nonce/v1", resource, thumbprint,
                String.join(".", fields[0], fields[1], fields[2]));
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            byte[] expected = Base64.getUrlEncoder().withoutPadding()
                    .encode(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(expected, fields[3].getBytes(StandardCharsets.US_ASCII))
                    ? McpReadDelegationEvidence.NonceStatus.VALID : McpReadDelegationEvidence.NonceStatus.INVALID;
        } catch (GeneralSecurityException e) {
            return McpReadDelegationEvidence.NonceStatus.UNAVAILABLE;
        }
    }
}
