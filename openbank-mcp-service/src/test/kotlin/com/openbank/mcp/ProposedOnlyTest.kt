// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.mcp.application.McpToolRegistry
import com.openbank.mcp.application.ProposedOnly
import com.openbank.mcp.application.port.out.AccountReadPort
import com.openbank.mcp.application.port.out.ConsentContext
import com.openbank.mcp.application.port.out.ProposalPort
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * T-E4 (#2414): an MCP agent may PROPOSE, never dispose. Until now that was a property of
 * `StubProposalPort`'s string literal — these tests make it a property of the call path, so a real
 * `ProposalPort` binding cannot quietly hand an agent a proposal that has already moved past
 * PROPOSED.
 *
 * Every case here was run against the unfixed `McpToolRegistry` first (the call site without
 * `ProposedOnly.enforce`): each transition-past-PROPOSED case passed the disposed proposal straight
 * through to the caller, i.e. every one of them is red without the fix.
 */
class ProposedOnlyTest {

    private val mapper = ObjectMapper()

    // --- the port contract, exercised directly -------------------------------------------------

    @Test
    fun `a PROPOSED proposal passes through unchanged`() {
        val node = mapper.readTree("""{"status":"PROPOSED","proposalId":"p-1"}""")
        assertThat(ProposedOnly.enforce(node)).isSameAs(node)
    }

    @Test
    fun `a proposal that reached a state past PROPOSED is refused`() {
        listOf("EXECUTED", "SETTLED", "ACCEPTED", "COMPLETED", "AUTHORISED", "proposed").forEach { status ->
            assertThatThrownBy { ProposedOnly.enforce(mapper.readTree("""{"status":"$status"}""")) }
                .describedAs("status %s must not be accepted", status)
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("past PROPOSED")
        }
    }

    @Test
    fun `a disposed status NESTED inside the document is refused`() {
        // The likeliest real shape: the proposal wrapper still says PROPOSED while the payment it
        // wraps has already been executed. A root-only check would wave this through.
        val node = mapper.readTree(
            """{"status":"PROPOSED","payment":{"id":"pay-1","status":"EXECUTED"}}""",
        )
        assertThatThrownBy { ProposedOnly.enforce(node) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("EXECUTED")
    }

    @Test
    fun `a disposed status inside an ARRAY element is refused`() {
        val node = mapper.readTree(
            """{"status":"PROPOSED","legs":[{"status":"PROPOSED"},{"status":"SETTLED"}]}""",
        )
        assertThatThrownBy { ProposedOnly.enforce(node) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("SETTLED")
    }

    @Test
    fun `a proposal with no status at all is refused, not assumed PROPOSED`() {
        assertThatThrownBy { ProposedOnly.enforce(mapper.readTree("""{"proposalId":"p-1"}""")) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("declares no status")
    }

    @Test
    fun `a non-textual status cannot slip through unexamined`() {
        assertThatThrownBy { ProposedOnly.enforce(mapper.readTree("""{"status":{"code":"PROPOSED"}}""")) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("non-textual")
    }

    // --- the same invariant through the registry, which is what actually runs ------------------

    @Test
    fun `the registry refuses a port that returns a disposed proposal`() {
        val registry = registryReturning("""{"status":"EXECUTED","paymentId":"pay-1"}""")

        assertThatThrownBy { registry.call("propose_payment", mapper.createObjectNode(), CTX) }
            .isInstanceOf(IllegalStateException::class.java)

        // And nothing about the disposed state reaches the caller: McpEndpoint maps any throw from
        // a tool to an audited "tool error", so the agent cannot even read back that it happened.
    }

    @Test
    fun `the registry still serves a well-behaved PROPOSED port`() {
        val registry = registryReturning("""{"status":"PROPOSED","proposalId":"p-1"}""")

        val result = registry.call("propose_payment", mapper.createObjectNode(), CTX)

        assertThat(result.isError).isFalse()
        assertThat(result.content.single().text).contains("PROPOSED")
    }

    @Test
    fun `the shipped stub port satisfies the invariant`() {
        // Guards the other direction: the enforcement must not make the current binding unusable.
        val stub = com.openbank.mcp.infrastructure.read.StubProposalPort(mapper)
        assertThatCode { ProposedOnly.enforce(stub.proposePayment(CTX, mapper.createObjectNode())) }
            .doesNotThrowAnyException()
    }

    private fun registryReturning(json: String) = McpToolRegistry(
        accounts = UnusedAccountReadPort,
        proposals = object : ProposalPort {
            override fun proposePayment(consentContext: ConsentContext, request: JsonNode): JsonNode =
                mapper.readTree(json)
        },
        mapper = mapper,
    )

    private object UnusedAccountReadPort : AccountReadPort {
        override fun listAccounts(consentContext: ConsentContext): JsonNode = error("not used")
        override fun getBalance(consentContext: ConsentContext, accountId: String): JsonNode = error("not used")
        override fun listTransactions(consentContext: ConsentContext, accountId: String, limit: Int): JsonNode =
            error("not used")
        override fun listConsents(consentContext: ConsentContext): JsonNode = error("not used")
    }

    private companion object {
        val CTX = ConsentContext(agentId = "agent:test", consentId = "c-1", grantedAccounts = emptyList())
    }
}
