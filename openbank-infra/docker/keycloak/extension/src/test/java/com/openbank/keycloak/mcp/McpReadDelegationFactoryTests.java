// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.keycloak.mcp;

import org.keycloak.protocol.oidc.grants.OAuth2GrantTypeFactory;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class McpReadDelegationFactoryTests {
    public static void main(String[] args) throws Exception {
        String issuer = "https://identity.test/realms/openbank-customers";
        String resource = "https://mcp.test/customer/mcp";
        byte[] nonceKey = new byte[32];
        byte[] proofKey = new byte[32];
        proofKey[0] = 1;
        Base64.Encoder base64 = Base64.getUrlEncoder().withoutPadding();
        var settings = new HashMap<>(Map.of(
                "MCP_READ_GRANT_ENABLED", "true",
                "MCP_READ_CUSTOMER_ISSUER_URL", issuer,
                "MCP_READ_RESOURCE_URL", resource,
                "MCP_READ_DELEGATION_ORIGIN", "https://delegation.test",
                "MCP_READ_ISSUER_TOKEN_URL", "https://identity.test/realms/openbank/protocol/openid-connect/token",
                "MCP_READ_ISSUER_CLIENT_SECRET", "synthetic-fixture",
                "MCP_READ_NONCE_KEY", base64.encodeToString(nonceKey),
                "MCP_READ_PROOF_KEY", base64.encodeToString(proofKey),
                "MCP_READ_DEPLOYMENT", "test"));
        boolean registered = ServiceLoader.load(OAuth2GrantTypeFactory.class).stream()
                .anyMatch(provider -> provider.type() == McpReadDelegationGrantTypeFactory.class);
        require(registered);
        require(McpReadDelegationGrantTypeFactory.configured(settings::get) instanceof McpReadDelegationGrantType);
        settings.put("MCP_READ_CONNECTION_BINDING_ENABLED", "true");
        require(McpReadDelegationGrantTypeFactory.configured(settings::get) instanceof McpReadDelegationGrantType);
        settings.put("MCP_READ_CONNECTION_BINDING_ENABLED", "invalid");
        require(!(McpReadDelegationGrantTypeFactory.configured(settings::get) instanceof McpReadDelegationGrantType));
        settings.remove("MCP_READ_CONNECTION_BINDING_ENABLED");
        settings.put("MCP_READ_PROOF_KEY", base64.encodeToString(nonceKey));
        require(!(McpReadDelegationGrantTypeFactory.configured(settings::get) instanceof McpReadDelegationGrantType));
        settings.put("MCP_READ_PROOF_KEY", base64.encodeToString(proofKey));
        settings.remove("MCP_READ_ISSUER_CLIENT_SECRET");
        require(!(McpReadDelegationGrantTypeFactory.configured(settings::get) instanceof McpReadDelegationGrantType));
        require(!(McpReadDelegationGrantTypeFactory.configured(name -> null) instanceof McpReadDelegationGrantType));

        var now = Instant.parse("2026-11-23T10:00:00Z");
        var clock = Clock.fixed(now, ZoneOffset.UTC);
        String thumbprint = "A".repeat(43);
        String prefix = "v1." + now.plusSeconds(60).getEpochSecond() + "." + "A".repeat(22);
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(nonceKey, "HmacSHA256"));
        String input = String.join("\n", "mcp-resource-nonce/v1", resource, thumbprint, prefix);
        String nonce = prefix + "." + base64.encodeToString(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
        var verifier = new McpSignedResourceNonce(nonceKey, clock);
        require(verifier.verify(nonce, thumbprint, resource) == McpReadDelegationEvidence.NonceStatus.VALID);
        require(verifier.verify(nonce, "B".repeat(43), resource) == McpReadDelegationEvidence.NonceStatus.INVALID);
        var later = new McpSignedResourceNonce(nonceKey, Clock.offset(clock, java.time.Duration.ofSeconds(60)));
        require(later.verify(nonce, thumbprint, resource) == McpReadDelegationEvidence.NonceStatus.INVALID);
        System.out.println("MCP grant factory and resource nonce: 10 checks passed");
    }

    private static void require(boolean value) {
        if (!value) throw new AssertionError("MCP grant factory invariant failed");
    }
}
