// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.keycloak.mcp;

import java.nio.charset.StandardCharsets;

/** References computed from bank-owned OAuth context. Keeps raw family values out of delegation. */
final class McpConnectTokenReferences {
    private McpConnectTokenReferences() {
    }

    static String codeBinding(String realmId, String partyId, String clientUuid, String jkt, String codeChallenge) {
        if (codeChallenge == null || !codeChallenge.matches("[A-Za-z0-9_-]{43}")) {
            throw new IllegalArgumentException("expected an S256 PKCE challenge");
        }
        if (jkt == null) {
            return reference("mcp-connect-hosted-code-binding/v1", realmId, partyId, clientUuid, codeChallenge);
        }
        McpConnectContextV2.requireCanonicalThumbprint(jkt);
        return reference("mcp-connect-code-binding/v1", realmId, partyId, clientUuid, jkt, codeChallenge);
    }

    static McpConnectAuthorityClient.FamilyReference family(String realmId, String userSessionId, String clientUuid,
                                                            String reuseId) {
        return new McpConnectAuthorityClient.FamilyReference(reference("mcp-connect-online-family/v1",
                realmId, userSessionId, clientUuid, reuseId));
    }

    private static String reference(String purpose, String... fields) {
        StringBuilder input = new StringBuilder(purpose);
        for (String value : fields) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("missing bank token context");
            }
            input.append(value.getBytes(StandardCharsets.UTF_8).length).append(':').append(value);
        }
        return McpConnectContext.sha256Hex(input.toString());
    }
}
