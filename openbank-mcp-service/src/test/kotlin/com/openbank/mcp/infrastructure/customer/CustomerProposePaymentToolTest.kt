// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.infrastructure.customer

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.authz.AuthzDecision
import com.openbank.libs.authz.AuthzQuery
import com.openbank.libs.authz.PolicyDecisionPoint
import com.openbank.mcp.application.McpPiiMasker
import com.openbank.mcp.application.McpUntrustedData
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * ADR-0383 D6 on the customer channel: `propose_payment` is offered and reaches the store only for
 * the debtor account a grant carrying ACCOUNT_PROPOSE_PAYMENT covers, after the PDP; never on a
 * read capability, never on a PDP deny, and never exchanging the customer's token.
 */
class CustomerProposePaymentToolTest {
    private val mapper = ObjectMapper()
    private val exchange: CustomerTokenExchange = mockk()
    private val downstream: CustomerDownstream = mockk()
    private val store: PaymentProposalStore = mockk()
    private val queries = mutableListOf<AuthzQuery>()
    private var verdict: () -> AuthzDecision = { AuthzDecision(allow = true) }
    private val pdp = object : PolicyDecisionPoint {
        override suspend fun allow(query: AuthzQuery): AuthzDecision {
            queries += query
            return verdict()
        }
    }
    private val tools = CustomerReadTools(
        pdp,
        exchange,
        downstream,
        McpPiiMasker(mapper),
        mapper,
        CustomerPaymentProposer(store, mapper),
        boundBalance = null,
        boundProposalEnabled = false,
    )
    private val account = UUID.randomUUID()
    private val key = GrantKey(UUID.randomUUID(), "mcp-local-test", "0".repeat(43))
    private val caller = CustomerReadTools.Caller("c0ffee00", "customer-token", "mcp-local-test", key, "sid-1")

    private fun grant(vararg caps: String, accountId: UUID = account) =
        CustomerGrant(UUID.randomUUID(), accountId, caps.toSet(), null)

    private fun args(debtor: UUID = account): JsonNode = mapper.valueToTree(
        mapOf(
            "debtorAccountId" to debtor.toString(),
            "creditorIban" to "CZ6508000000192000145399",
            "creditorName" to "Jan Novak",
            "amount" to "10.00",
            "currency" to "CZK",
            "clientRequestId" to "req-00000001",
        ),
    )

    private fun call(
        grants: List<CustomerGrant>,
        arguments: JsonNode = args(),
        who: CustomerReadTools.Caller = caller,
    ) = tools.call(CustomerReadTools.PROPOSE_PAYMENT, arguments, who, grants)

    private fun pending() = McpProposalReceipt(UUID.randomUUID(), "PENDING", false, "2026-11-20T10:00:00Z", "s")

    @Test
    fun `offered only to a grant that carries the propose capability`() {
        assertThat(tools.list(listOf(grant(CustomerCapabilities.READ_BALANCES))).map { it.name })
            .doesNotContain(CustomerReadTools.PROPOSE_PAYMENT)
        assertThat(tools.list(listOf(grant(CustomerCapabilities.PROPOSE_PAYMENT))).map { it.name })
            .containsExactly(CustomerReadTools.PROPOSE_PAYMENT)
    }

    @Test
    fun `connected proposals need the separate rollout flag and the exact verified connection`() {
        val grants = listOf(grant(CustomerCapabilities.PROPOSE_PAYMENT))
        val bound = key.copy(connectionId = UUID.randomUUID())
        val who = caller.copy(grantKey = bound)
        val tool = CustomerReadTools.PROPOSE_PAYMENT
        assertThat(tools.list(grants, connectionBound = true)).isEmpty()
        assertThat(tools.call(tool, args(), who, grants)).isInstanceOf(CustomerReadTools.Outcome.Refused::class.java)
        verify(exactly = 0) { store.create(any(), any()) }

        val enabled = CustomerReadTools(
            pdp,
            exchange,
            downstream,
            McpPiiMasker(mapper),
            mapper,
            CustomerPaymentProposer(store, mapper),
            boundBalance = null,
            boundProposalEnabled = true,
        )
        every { store.create(any(), match { it.connectionId == bound.connectionId }) } returns pending()
        assertThat(enabled.list(grants, connectionBound = true).map { it.name }).containsExactly(tool)
        assertThat(enabled.call(tool, args(), who, grants)).isInstanceOf(CustomerReadTools.Outcome.Allowed::class.java)
        verify(exactly = 1) { store.create(any(), match { it.connectionId == bound.connectionId }) }
    }

    @Test
    fun `a read-only grant cannot propose and the store is never called`() {
        val out = call(listOf(grant(CustomerCapabilities.READ_BALANCES, CustomerCapabilities.READ_TRANSACTIONS)))
        assertThat(out).isInstanceOf(CustomerReadTools.Outcome.Refused::class.java)
        assertThat(queries).isEmpty()
        verify(exactly = 0) { store.create(any(), any()) }
    }

    @Test
    fun `a propose grant over ANOTHER account does not cover this debtor`() {
        val out = call(listOf(grant(CustomerCapabilities.PROPOSE_PAYMENT, accountId = UUID.randomUUID())))
        assertThat(out).isInstanceOf(CustomerReadTools.Outcome.Refused::class.java)
        verify(exactly = 0) { store.create(any(), any()) }
    }

    @Test
    fun `invalid arguments are refused before the grant, the PDP and the store`() {
        val bad = (args() as com.fasterxml.jackson.databind.node.ObjectNode).put("amount", "1e3")
        val out = call(listOf(grant(CustomerCapabilities.PROPOSE_PAYMENT)), bad) as CustomerReadTools.Outcome.Refused
        assertThat(out.message).startsWith("Invalid arguments:")
        assertThat(queries).isEmpty()
        verify(exactly = 0) { store.create(any(), any()) }
    }

    @Test
    fun `a token without a session cannot propose and the store is never called`() {
        val out = call(listOf(grant(CustomerCapabilities.PROPOSE_PAYMENT)), who = caller.copy(sessionId = null))
        assertThat(out).isInstanceOf(CustomerReadTools.Outcome.Refused::class.java)
        assertThat((out as CustomerReadTools.Outcome.Refused).reason).contains("sid")
        assertThat(queries).isEmpty()
        verify(exactly = 0) { store.create(any(), any()) }
    }

    @Test
    fun `a PDP deny stops the call before the store`() {
        verdict = { AuthzDecision(allow = false, reason = "no") }
        val out = call(listOf(grant(CustomerCapabilities.PROPOSE_PAYMENT)))
        assertThat(out).isInstanceOf(CustomerReadTools.Outcome.Refused::class.java)
        verify(exactly = 0) { store.create(any(), any()) }
    }

    @Test
    fun `the PDP is asked for propose payment on the customer channel with the grant's capabilities`() {
        every { store.create(any(), any()) } returns pending()
        call(listOf(grant(CustomerCapabilities.PROPOSE_PAYMENT)))
        val q = queries.single()
        assertThat(q.action).isEqualTo("propose.payment")
        assertThat(q.attributes["channel"]).isEqualTo("mcp-customer")
        assertThat(q.attributes["tool"]).isEqualTo(CustomerReadTools.PROPOSE_PAYMENT)
        assertThat(q.attributes["grantedCapabilities"]).isEqualTo(listOf(CustomerCapabilities.PROPOSE_PAYMENT))
        assertThat(q.principal.serviceAccount).isFalse()
    }

    @Test
    fun `an allowed proposal is masked, wrapped as untrusted data and names its grant - no token exchanged`() {
        every { store.create(any(), any()) } returns pending()
        val g = grant(CustomerCapabilities.PROPOSE_PAYMENT)
        val out = call(listOf(g)) as CustomerReadTools.Outcome.Allowed
        val text = out.result.content.single().text
        assertThat(out.grantId).isEqualTo(g.grantId)
        assertThat(out.capability).isEqualTo(CustomerCapabilities.PROPOSE_PAYMENT)
        assertThat(text).startsWith(McpUntrustedData.wrap("").substringBefore("\n"))
        assertThat(text).contains("PENDING").contains("****5399").doesNotContain("CZ6508000000192000145399")
        verify(exactly = 0) { exchange.exchange(any(), any(), any()) }
    }

    @Test
    fun `a store refusal for an expired grant asks the caller to drop the cached grant`() {
        every { store.create(any(), any()) } throws ProposalStoreRefusal(403, "PROPOSAL_NOT_PERMITTED")
        val out = call(listOf(grant(CustomerCapabilities.PROPOSE_PAYMENT))) as CustomerReadTools.Outcome.Refused
        assertThat(out.invalidateGrant).isTrue()
        assertThat(out.unavailable).isFalse()
    }

    @Test
    fun `without a verified grant key nothing is proposed`() {
        val out = call(
            listOf(grant(CustomerCapabilities.PROPOSE_PAYMENT)),
            who = caller.copy(grantKey = null),
        ) as CustomerReadTools.Outcome.Refused
        assertThat(out.unavailable).isTrue()
        verify(exactly = 0) { store.create(any(), any()) }
    }
}
