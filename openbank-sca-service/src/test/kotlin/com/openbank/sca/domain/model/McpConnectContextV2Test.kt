// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.domain.model

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.security.McpConnectContext
import com.openbank.libs.security.McpConnectContextV2
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.File

class McpConnectContextV2Test {
    private val fixture: JsonNode = run {
        val file = generateSequence(File("").absoluteFile) { it.parentFile }
            .map {
                File(
                    it,
                    "openbank-infra/docker/keycloak/extension/src/test/resources/mcp-connect-context-v2.fixture.json",
                )
            }
            .first { it.isFile }
        ObjectMapper().readTree(file)
    }

    @Test
    fun `shared V2 fixture pins bytes and immutable account-specific scope`() {
        assertEquals(McpConnectContextV2.VERSION, fixture["version"].asText())
        fixture["cases"].forEach { row ->
            val input = row["input"]
            val capabilities = input["accounts"].map { account ->
                account["capabilities"].map(JsonNode::asText).toMutableList()
            }
            val accounts = input["accounts"].mapIndexed { index, account ->
                McpConnectContextV2.AccountScope(account["id"].asText(), capabilities[index])
            }.toMutableList()
            val context = build(input, accounts)
            val name = row["name"].asText()
            assertEquals(row["expect"]["canonical"].asText(), context.canonical(), name)
            assertEquals(row["expect"]["payload_sha256"].asText(), context.payloadSha256(), name)
            capabilities.first().clear()
            accounts.clear()
            assertEquals(row["expect"]["payload_sha256"].asText(), context.payloadSha256(), name)
        }
    }

    @Test
    fun `shared V2 fixture rejects invalid scope and provenance shape`() {
        fixture["invalid"].forEach { row ->
            val input = row["input"]
            val accounts = input["accounts"].map { account ->
                McpConnectContextV2.AccountScope(account["id"].asText(), account["capabilities"].map(JsonNode::asText))
            }
            assertThrows(IllegalArgumentException::class.java, { build(input, accounts) }, row["name"].asText())
        }
    }

    private fun build(input: JsonNode, accounts: Collection<McpConnectContextV2.AccountScope>): McpConnectContextV2 {
        val base = McpConnectContext.of(
            clientId = input["client_id"].asText(),
            redirectUri = input["redirect_uri"].asText(),
            resource = input["resource"].asText(),
            scope = input["scope"].asText(),
            requestUri = input["request_uri"].asText(),
            expiresAt = input["expires_at"].asLong(),
        )
        val thumbprint = input["par_jkt"].takeUnless(JsonNode::isNull)?.asText()
        return McpConnectContextV2.of(base, input["party_id"].asText(), thumbprint, accounts)
    }
}
