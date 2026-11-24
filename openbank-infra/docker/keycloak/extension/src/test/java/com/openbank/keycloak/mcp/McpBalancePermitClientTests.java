// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.keycloak.mcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

final class McpBalancePermitClientTests {
    private static final UUID ACCOUNT = UUID.fromString("4e81c479-62b1-4534-8f22-fd12d6ae4ffe");
    private static final UUID GRANT = UUID.fromString("94ffe852-e055-46ed-8d56-c4ac9d75681b");
    private static final UUID CONNECTION = UUID.fromString("f02cb79a-57a5-4cee-94df-5f7a7dd6c925");
    private static final Instant NOW = Instant.parse("2026-11-23T10:00:00Z");
    private static final String BODY = """
            {"grantId":"%s","accountId":"%s","validUntil":"2026-11-23T10:01:00Z"}
            """.formatted(GRANT, ACCOUNT);

    public static void main(String[] args) throws IOException {
        var permit = McpBalancePermitClient.checkedPermit(200, bytes(BODY), ACCOUNT, null, NOW);
        if (!GRANT.equals(permit.grantId()) || !ACCOUNT.equals(permit.accountId())) throw new AssertionError();
        refused(403, BODY, ACCOUNT, NOW);
        refused(302, BODY, ACCOUNT, NOW);
        refused(200, BODY, GRANT, NOW);
        refused(200, BODY, ACCOUNT, NOW.plusSeconds(60));
        refused(200, "{}", ACCOUNT, NOW);
        refused(200, " ".repeat(4097), ACCOUNT, NOW);
        String bound = """
                {"grantId":"%s","accountId":"%s","validUntil":"2026-11-23T10:01:00Z","connectionId":"%s"}
                """.formatted(GRANT, ACCOUNT, CONNECTION);
        var exact = McpBalancePermitClient.checkedPermit(200, bytes(bound), ACCOUNT, CONNECTION, NOW);
        if (!CONNECTION.equals(exact.connectionId())) throw new AssertionError();
        refused(200, BODY, ACCOUNT, CONNECTION, NOW);
        refused(200, bound, ACCOUNT, GRANT, NOW);
        refused(200, bound, ACCOUNT, NOW);
        System.out.println("MCP balance permit: 11 checks passed");
    }

    private static byte[] bytes(String body) {
        return body.getBytes(StandardCharsets.UTF_8);
    }

    private static void refused(int status, String body, UUID account, Instant now) {
        refused(status, body, account, null, now);
    }

    private static void refused(int status, String body, UUID account, UUID connectionId, Instant now) {
        try {
            McpBalancePermitClient.checkedPermit(status, bytes(body), account, connectionId, now);
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("invalid response yielded a permit");
    }
}
