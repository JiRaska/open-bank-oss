// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.domain.model

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.File

/**
 * ADR-0385 D3: the canonical connect context, held to the fixture the Keycloak extension's own
 * tests read (and whose expected values a third implementation computed). If either side changes
 * the canonical bytes, its own suite goes red against the same file.
 */
class McpConnectContextTest {
    private val fixture: JsonNode = run {
        val file = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "openbank-infra/docker/keycloak/extension/src/test/resources/mcp-connect-context.fixture.json") }
            .first { it.isFile }
        ObjectMapper().readTree(file)
    }

    private fun build(input: JsonNode) = McpConnectContext.of(
        clientId = input["client_id"].asText(),
        redirectUri = input["redirect_uri"].asText(),
        resource = input["resource"].asText(),
        scope = input["scope"].asText(),
        requestUri = input["request_uri"].asText(),
        expiresAt = input["expires_at"].asLong(),
    )

    @Test
    fun `fixture version is the one this code implements`() {
        assertThat(fixture["version"].asText()).isEqualTo(McpConnectContext.VERSION)
    }

    @Test
    fun `every fixture case hashes to the shared canonical bytes`() {
        assertThat(fixture["cases"]).isNotEmpty()
        fixture["cases"].forEach { case ->
            val name = case["name"].asText()
            val expect = case["expect"]
            val ctx = build(case["input"])
            assertThat(ctx.redirectHost).`as`(name).isEqualTo(expect["redirect_host"].asText())
            assertThat(ctx.scopes).`as`(name).isEqualTo(expect["scopes"].asText())
            assertThat(ctx.canonical()).`as`(name).isEqualTo(expect["canonical"].asText())
            assertThat(ctx.payloadSha256()).`as`(name).isEqualTo(expect["payload_sha256"].asText())
            assertThat(McpConnectContext.approvalRequestId(case["input"]["request_uri"].asText()))
                .`as`(name).isEqualTo(expect["approval_request_id"].asText())
        }
    }

    @Test
    fun `the approval request id is well formed in the reserved connect namespace`() {
        fixture["cases"].forEach { case ->
            assertThat(ReservedNamespace.MCP_CONNECT.isWellFormed(case["expect"]["approval_request_id"].asText()))
                .`as`(case["name"].asText()).isTrue()
        }
    }

    @Test
    fun `every fixture invalid case is refused`() {
        assertThat(fixture["invalid"]).isNotEmpty()
        fixture["invalid"].forEach { case ->
            assertThatThrownBy { build(case["input"]) }.`as`(case["name"].asText())
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }
}
