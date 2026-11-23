// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.keycloak.mcp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.keycloak.util.JsonSerialization;

public final class McpConnectContextV2Tests {
    private McpConnectContextV2Tests() {
    }

    public static void main(String[] args) throws Exception {
        Map<?, ?> fixture = JsonSerialization.readValue(Files.readString(Path.of(args[0])), Map.class);
        require(McpConnectContextV2.VERSION.equals(fixture.get("version")), "fixture version");
        List<?> cases = (List<?>) fixture.get("cases");
        for (Object row : cases) {
            Map<?, ?> test = (Map<?, ?>) row;
            String name = (String) test.get("name");
            Map<?, ?> input = (Map<?, ?>) test.get("input");
            Map<?, ?> expected = (Map<?, ?>) test.get("expect");
            List<McpConnectContextV2.AccountScope> accounts = accounts(input);
            McpConnectContextV2 context = build(input, accounts);
            require(expected.get("canonical").equals(context.canonical()), name + ": canonical");
            require(expected.get("payload_sha256").equals(context.payloadSha256()), name + ": digest");
            accounts.iterator().next().capabilities().clear();
            accounts.clear();
            require(expected.get("payload_sha256").equals(context.payloadSha256()), name + ": immutable snapshot");
        }
        List<?> invalid = (List<?>) fixture.get("invalid");
        for (Object row : invalid) {
            Map<?, ?> test = (Map<?, ?>) row;
            Map<?, ?> input = (Map<?, ?>) test.get("input");
            try {
                build(input, accounts(input));
            } catch (IllegalArgumentException expected) {
                continue;
            }
            throw new AssertionError("accepted invalid context: " + test.get("name"));
        }
        System.out.printf("MCP connect context V2: %d valid cases and %d invalid cases passed%n",
                cases.size(), invalid.size());
    }

    private static List<McpConnectContextV2.AccountScope> accounts(Map<?, ?> input) {
        var result = new ArrayList<McpConnectContextV2.AccountScope>();
        for (Object row : (List<?>) input.get("accounts")) {
            Map<?, ?> account = (Map<?, ?>) row;
            var capabilities = new ArrayList<String>();
            for (Object value : (List<?>) account.get("capabilities")) {
                capabilities.add((String) value);
            }
            result.add(new McpConnectContextV2.AccountScope((String) account.get("id"), capabilities));
        }
        return result;
    }

    private static McpConnectContextV2 build(Map<?, ?> input, List<McpConnectContextV2.AccountScope> accounts) {
        McpConnectContext base = McpConnectContext.of(
                (String) input.get("client_id"), (String) input.get("redirect_uri"), (String) input.get("resource"),
                (String) input.get("scope"), (String) input.get("request_uri"), ((Number) input.get("expires_at")).longValue());
        return McpConnectContextV2.of(base, (String) input.get("party_id"), (String) input.get("par_jkt"), accounts);
    }

    private static void require(boolean valid, String name) {
        if (!valid) {
            throw new AssertionError(name);
        }
    }
}
