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

/** ADR-0383 D3/D4: a tool reaches data only for an account whose grant carries its capability. */
class CustomerReadToolsTest {
    private val mapper = ObjectMapper()
    private val exchange: CustomerTokenExchange = mockk()
    private val downstream: CustomerDownstream = mockk()
    private val queries = mutableListOf<AuthzQuery>()
    private var verdict: () -> AuthzDecision = { AuthzDecision(allow = true) }
    private val pdp = object : PolicyDecisionPoint {
        override suspend fun allow(query: AuthzQuery): AuthzDecision {
            queries += query
            return verdict()
        }
    }
    private val tools = CustomerReadTools(pdp, exchange, downstream, McpPiiMasker(mapper), mapper)
    private val caller = CustomerReadTools.Caller(
        "c0ffee00-0000-0000-0000-000000000001",
        "customer-token",
        "mcp-local-test",
    )
    private val account = UUID.randomUUID()

    private fun grant(vararg caps: String, accountId: UUID = account) =
        CustomerGrant(UUID.randomUUID(), accountId, caps.toSet(), null)

    private fun args(vararg pairs: Pair<String, Any>): JsonNode = mapper.valueToTree(mapOf(*pairs))

    @Test
    fun `tools are offered only for the capabilities some grant carries`() {
        assertThat(tools.list(emptyList())).isEmpty()
        assertThat(tools.list(listOf(grant(CustomerCapabilities.READ_BALANCES))).map { it.name })
            .containsExactly(CustomerReadTools.GET_BALANCE)
        assertThat(
            tools.list(listOf(grant(CustomerCapabilities.VIEW_DETAILS, CustomerCapabilities.READ_TRANSACTIONS))).map {
                it.name
            },
        )
            .containsExactlyInAnyOrder(CustomerReadTools.LIST_ACCOUNTS, CustomerReadTools.LIST_TRANSACTIONS)
    }

    @Test
    fun `scope escalation - a balance grant cannot read transactions and nothing is exchanged`() {
        val out = tools.call(
            CustomerReadTools.LIST_TRANSACTIONS,
            args("accountId" to account.toString()),
            caller,
            listOf(grant(CustomerCapabilities.READ_BALANCES)),
        )
        assertThat(out).isInstanceOf(CustomerReadTools.Outcome.Refused::class.java)
        verify(exactly = 0) { exchange.exchange(any(), any(), any()) }
    }

    @Test
    fun `an account outside every grant is refused before any exchange`() {
        val out = tools.call(
            CustomerReadTools.GET_BALANCE,
            args("accountId" to UUID.randomUUID().toString()),
            caller,
            listOf(grant(CustomerCapabilities.READ_BALANCES)),
        )
        assertThat(out).isInstanceOf(CustomerReadTools.Outcome.Refused::class.java)
        verify(exactly = 0) { exchange.exchange(any(), any(), any()) }
    }

    @Test
    fun `a failed exchange refuses and never reads`() {
        every { exchange.exchange(any(), any(), any()) } throws TokenExchangeRefused("nope")
        val out = tools.call(
            CustomerReadTools.GET_BALANCE,
            args("accountId" to account.toString()),
            caller,
            listOf(grant(CustomerCapabilities.READ_BALANCES)),
        )
        assertThat((out as CustomerReadTools.Outcome.Refused).unavailable).isTrue()
        verify(exactly = 0) { downstream.balances(any(), any()) }
    }

    @Test
    fun `an allowed read uses the exchanged balance token and is masked and wrapped`() {
        every { exchange.exchange("customer-token", caller.subject, DownstreamAudience.BALANCE) } returns "exchanged"
        every { downstream.balances("exchanged", account) } returns
            mapper.readTree("""{"accountId":"$account","available":"10.00","iban":"CZ6508000000192000145399"}""")
        val g = grant(CustomerCapabilities.READ_BALANCES)
        val out = tools.call(CustomerReadTools.GET_BALANCE, args("accountId" to account.toString()), caller, listOf(g))
        val allowed = out as CustomerReadTools.Outcome.Allowed
        assertThat(allowed.grantId).isEqualTo(g.grantId)
        val text = allowed.result.content.single().text
        assertThat(text).startsWith(McpUntrustedData.wrap("").substringBefore('\n'))
        assertThat(text).doesNotContain("CZ6508000000192000145399")
    }

    @Test
    fun `a downstream refusal is reported so the caller can drop the cached grant`() {
        every { exchange.exchange(any(), any(), any()) } returns "exchanged"
        every { downstream.transactions(any(), any(), any()) } throws DownstreamRefused(403)
        val out = tools.call(
            CustomerReadTools.LIST_TRANSACTIONS,
            args("accountId" to account.toString()),
            caller,
            listOf(grant(CustomerCapabilities.READ_TRANSACTIONS)),
        )
        assertThat(out).isInstanceOf(CustomerReadTools.Outcome.DownstreamRejected::class.java)
    }

    @Test
    fun `list_accounts returns only the granted accounts and reads nothing downstream`() {
        val other = UUID.randomUUID()
        val out = tools.call(
            CustomerReadTools.LIST_ACCOUNTS,
            args(),
            caller,
            listOf(
                grant(CustomerCapabilities.VIEW_DETAILS, CustomerCapabilities.READ_BALANCES),
                grant(CustomerCapabilities.READ_BALANCES, accountId = other),
            ),
        ) as CustomerReadTools.Outcome.Allowed
        val text = out.result.content.single().text
        assertThat(text).contains(account.toString()).doesNotContain(other.toString())
        verify(exactly = 0) { exchange.exchange(any(), any(), any()) }
    }

    @Test
    fun `a write tool is not on this channel`() {
        val out = tools.call("propose_payment", args(), caller, listOf(grant(CustomerCapabilities.READ_BALANCES)))
        assertThat(out).isInstanceOf(CustomerReadTools.Outcome.Refused::class.java)
    }

    @Test
    fun `the PDP sees the customer on the customer channel with the grant's capabilities`() {
        every { exchange.exchange(any(), any(), any()) } returns "exchanged"
        every { downstream.balances(any(), any()) } returns mapper.readTree("{}")
        tools.call(
            CustomerReadTools.GET_BALANCE,
            args("accountId" to account.toString()),
            caller,
            listOf(grant(CustomerCapabilities.READ_BALANCES)),
        )
        val q = queries.single()
        assertThat(q.action).isEqualTo("query.balance.readonly")
        assertThat(q.principal.type).isEqualTo("HUMAN")
        assertThat(q.principal.serviceAccount).isFalse()
        assertThat(q.principal.roles).isEmpty()
        assertThat(q.attributes["channel"]).isEqualTo("mcp-customer")
        assertThat(q.attributes["client"]).isEqualTo("mcp-local-test")
        assertThat(q.attributes["grantedCapabilities"]).isEqualTo(listOf(CustomerCapabilities.READ_BALANCES))
    }

    @Test
    fun `a policy deny refuses before any exchange`() {
        verdict = { AuthzDecision(allow = false, reason = "no") }
        val out = tools.call(
            CustomerReadTools.GET_BALANCE,
            args("accountId" to account.toString()),
            caller,
            listOf(grant(CustomerCapabilities.READ_BALANCES)),
        )
        assertThat(out).isInstanceOf(CustomerReadTools.Outcome.Refused::class.java)
        verify(exactly = 0) { exchange.exchange(any(), any(), any()) }
    }

    @Test
    fun `a PDP error refuses as unavailable before any exchange`() {
        verdict = { throw IllegalStateException("opa down") }
        val out = tools.call(
            CustomerReadTools.GET_BALANCE,
            args("accountId" to account.toString()),
            caller,
            listOf(grant(CustomerCapabilities.READ_BALANCES)),
        )
        assertThat((out as CustomerReadTools.Outcome.Refused).unavailable).isTrue()
        verify(exactly = 0) { exchange.exchange(any(), any(), any()) }
    }

    @Test
    fun `a downstream 5xx or timeout is an audited unavailable refusal that drops the cached grant`() {
        every { exchange.exchange(any(), any(), any()) } returns "exchanged"
        every { downstream.balances(any(), any()) } throws jakarta.ws.rs.WebApplicationException(503)
        val out = tools.call(
            CustomerReadTools.GET_BALANCE,
            args("accountId" to account.toString()),
            caller,
            listOf(grant(CustomerCapabilities.READ_BALANCES)),
        )
        val refused = out as CustomerReadTools.Outcome.Refused
        assertThat(refused.unavailable).isTrue()
        assertThat(refused.invalidateGrant).isTrue()
    }

    @Test
    fun `an unexpected exchange failure is an unavailable refusal, never an escape`() {
        every { exchange.exchange(any(), any(), any()) } throws IllegalStateException("boom")
        val out = tools.call(
            CustomerReadTools.GET_BALANCE,
            args("accountId" to account.toString()),
            caller,
            listOf(grant(CustomerCapabilities.READ_BALANCES)),
        )
        assertThat((out as CustomerReadTools.Outcome.Refused).unavailable).isTrue()
        verify(exactly = 0) { downstream.balances(any(), any()) }
    }

    @Test
    fun `a cancellation propagates instead of becoming a refusal`() {
        every { exchange.exchange(any(), any(), any()) } returns "exchanged"
        every { downstream.balances(any(), any()) } throws
            kotlin.coroutines.cancellation.CancellationException("cancelled")
        org.assertj.core.api.Assertions.assertThatThrownBy {
            tools.call(
                CustomerReadTools.GET_BALANCE,
                args("accountId" to account.toString()),
                caller,
                listOf(grant(CustomerCapabilities.READ_BALANCES)),
            )
        }.isInstanceOf(kotlin.coroutines.cancellation.CancellationException::class.java)
    }
}
