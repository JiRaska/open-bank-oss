// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.keycloak.mcp;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Produces an issuer-only reference invariant across requested accounts and actions. */
final class McpIssuerProofReference {
    private static final String ALGORITHM = "HmacSHA256";
    private final SecretKeySpec secret;
    private final String deployment;

    McpIssuerProofReference(byte[] secret, String deployment) {
        if (secret.length < 32 || deployment.isBlank()) {
            throw new IllegalArgumentException("a dedicated proof-reference key and deployment namespace are required");
        }
        this.secret = new SecretKeySpec(secret, ALGORITHM);
        this.deployment = deployment;
    }

    String key(String realm, McpReadDelegationEvidence.Verified proof) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(secret);
            for (String field : new String[] {"mcp-read-proof/v1", deployment, realm, proof.keyThumbprint(), proof.proofId()}) {
                byte[] encoded = field.getBytes(StandardCharsets.UTF_8);
                mac.update(ByteBuffer.allocate(Integer.BYTES).putInt(encoded.length).array());
                mac.update(encoded);
            }
            return HexFormat.of().formatHex(mac.doFinal());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("issuer proof reference unavailable");
        }
    }
}
