// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp.infrastructure.customer

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class McpClientAllowlistTest {

    private val valid = """
        schema_version: 1
        resource: https://mcp.test.example/mcp
        audience: openbank-mcp-service
        clients:
          - client_id: a
            tier: L
            enabled: true
          - client_id: b
            tier: H
            enabled: false
    """.trimIndent()

    @Test
    fun `a valid allowlist admits only enabled declared clients`() {
        val list = McpClientAllowlist.parse(valid)
        assertThat(list.resource).isEqualTo("https://mcp.test.example/mcp")
        assertThat(list.admitted("a")?.tier).isEqualTo(McpClientAllowlist.Tier.L)
        assertThat(list.admitted("b")).isNull()
        assertThat(list.admitted("unknown")).isNull()
        assertThat(list.admitted(null)).isNull()
    }

    @Test
    fun `the main allowlist shape from ADR-0385 parses`() {
        val text = javaClass.classLoader.getResource("test-mcp-client-allowlist.yaml")!!.readText()
        assertThat(McpClientAllowlist.parse(text).clients).hasSize(3)
    }

    @Test
    fun `malformed allowlists are refused, never read as empty`() {
        listOf(
            "",
            "- just a list",
            valid.replace("schema_version: 1", "schema_version: 2"),
            valid.replace("resource: https://mcp.test.example/mcp", "resource: http://mcp.test.example/mcp"),
            valid.replace("audience: openbank-mcp-service\n", ""),
            valid.replace("tier: L", "tier: X"),
            valid.replace("enabled: true", "enabled: yes-please"),
            valid.replace("client_id: b", "client_id: a"),
            valid.substringBefore("clients:"),
        ).forEach { text ->
            assertThatThrownBy { McpClientAllowlist.parse(text) }
                .describedAs(text)
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `metadata URL inserts the well-known prefix before the resource path (RFC 9728 section 3_1)`() {
        assertThat(CustomerChannelAdmission.metadataUrlFor("https://api.example.org/mcp"))
            .isEqualTo("https://api.example.org/.well-known/oauth-protected-resource/mcp")
        assertThat(CustomerChannelAdmission.metadataUrlFor("https://api.example.org:8443"))
            .isEqualTo("https://api.example.org:8443/.well-known/oauth-protected-resource")
    }
}
