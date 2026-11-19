// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp

import com.openbank.libs.audit.AuditResult
import com.openbank.libs.testing.containers.PostgresTestResource
import com.openbank.mcp.infrastructure.customer.CreatePaymentProposalRequest
import com.openbank.mcp.infrastructure.customer.CustomerCapabilities
import com.openbank.mcp.infrastructure.customer.CustomerGrant
import com.openbank.mcp.infrastructure.customer.CustomerGrantSource
import com.openbank.mcp.infrastructure.customer.CustomerPaymentProposer
import com.openbank.mcp.infrastructure.customer.DpopTestProofs
import com.openbank.mcp.infrastructure.customer.GrantKey
import com.openbank.mcp.infrastructure.customer.McpProposalReceipt
import com.openbank.mcp.infrastructure.customer.PaymentProposalStore
import com.openbank.mcp.infrastructure.customer.ProposalStoreRefusal
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * ADR-0383 D6 over real HTTP through the real CDI graph: `propose_payment` on the customer channel
 * writes ONE PENDING proposal to the store under a propose grant, with the verified grant triple and
 * a derived Idempotency-Key; a retry reuses the key; a read grant, a PDP deny or a store refusal
 * writes nothing new and answers a fixed text. Only delegation-service's two ports are doubled.
 */
@QuarkusTest
@TestProfile(McpCustomerProposePaymentIT.StoreDoubles::class)
@QuarkusTestResource(PostgresTestResource::class, initArgs = [ResourceArg(name = "db", value = "openbank_mcp_it")])
class McpCustomerProposePaymentIT {

    class StoreDoubles : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = McpCustomerChannelIT.CustomerOn().configOverrides

        override fun getEnabledAlternatives(): Set<Class<*>> = setOf(
            CustomerTokenSource::class.java,
            Grants::class.java,
            Store::class.java,
        )
    }

    @Inject
    lateinit var recorder: RecordingAuditEventPublisher

    @Inject
    lateinit var registry: MeterRegistry

    private val key = DpopTestProofs.newKey()
    private val account: UUID = UUID.randomUUID()
    private val party: UUID = UUID.randomUUID()
    private val subject: String = UUID.randomUUID().toString()

    @Alternative
    @ApplicationScoped
    class Grants : CustomerGrantSource {
        override fun resolve(key: GrantKey): List<CustomerGrant> = answer(key)

        companion object {
            @Volatile
            var answer: (GrantKey) -> List<CustomerGrant> = { emptyList() }
        }
    }

    @Alternative
    @ApplicationScoped
    class Store : PaymentProposalStore {
        override fun create(idempotencyKey: String, request: CreatePaymentProposalRequest): McpProposalReceipt {
            calls += idempotencyKey to request
            refusal?.let { throw it }
            return McpProposalReceipt(PROPOSAL, "PENDING", calls.size > 1, "2026-11-20T10:00:00Z", "store text")
        }

        companion object {
            val calls = CopyOnWriteArrayList<Pair<String, CreatePaymentProposalRequest>>()

            @Volatile
            var refusal: ProposalStoreRefusal? = null
        }
    }

    @BeforeEach
    fun install() {
        recorder.clear()
        Store.calls.clear()
        Store.refusal = null
        Grants.answer = { emptyList() }
        TestPolicyDecisionPoint.customerQueries.clear()
        TestPolicyDecisionPoint.customerDeny = false
        TestPolicyDecisionPoint.customerOutage = false
        CustomerTokenSource.claims = mapOf(
            "iss" to DpopTestProofs.ISSUER,
            "sub" to subject,
            "aud" to "openbank-mcp-service",
            "azp" to "mcp-local-test",
            "party_id" to party.toString(),
            "sid" to "session-1",
            "cnf" to mapOf("jkt" to DpopTestProofs.jkt(key)),
        )
    }

    private fun grant(vararg caps: String) = { k: GrantKey ->
        if (k.partyId == party) listOf(CustomerGrant(GRANT, account, caps.toSet(), null)) else emptyList()
    }

    private fun propose(requestId: String = "req-00000001", amount: String = "125.50") = given()
        .contentType(ContentType.JSON)
        .header("Authorization", "DPoP ${DpopTestProofs.ACCESS_TOKEN}")
        .header("DPoP", DpopTestProofs.proof(key))
        .body(
            """{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"propose_payment","arguments":{""" +
                """"debtorAccountId":"$account","creditorIban":"CZ6508000000192000145399",""" +
                """"creditorName":"Jan Novak","amount":"$amount","currency":"CZK",""" +
                """"clientRequestId":"$requestId"}}}""",
        )
        .post("/mcp").then().statusCode(200)

    private fun toolCalls(decision: String): Double = registry.find("openbank.mcp.tool_calls")
        .tags("tool", "propose_payment", "decision", decision).counters().sumOf { it.count() }

    @Test
    fun `a propose grant records one PENDING proposal with the grant triple and a derived key`() {
        Grants.answer = grant(CustomerCapabilities.PROPOSE_PAYMENT)
        val allowedBefore = toolCalls("ALLOW")
        propose().body("result.isError", equalTo(false))
            .body("result.content[0].text", containsString("PENDING"))
            .body("result.content[0].text", containsString("****5399"))
        val (sentKey, sent) = Store.calls.single()
        assertThat(sent.partyId).isEqualTo(party)
        assertThat(sent.mcpClientId).isEqualTo("mcp-local-test")
        assertThat(sent.keyThumbprint).isEqualTo(DpopTestProofs.jkt(key))
        assertThat(sent.debtorAccountId).isEqualTo(account)
        assertThat(sentKey).isEqualTo(
            CustomerPaymentProposer.idempotencyKey(
                GrantKey(party, "mcp-local-test", DpopTestProofs.jkt(key)),
                "session-1",
                "req-00000001",
            ),
        )
        val audit = recorder.events.last()
        assertThat(audit.result).isEqualTo(AuditResult.SUCCESS)
        assertThat(audit.payload["consent_id"]).isEqualTo("delegation:$GRANT")
        assertThat(audit.payload["capability"]).isEqualTo(CustomerCapabilities.PROPOSE_PAYMENT)
        assertThat(toolCalls("ALLOW")).isEqualTo(allowedBefore + 1)
    }

    @Test
    fun `an MCP retry sends the same Idempotency-Key, a new request id a new one`() {
        Grants.answer = grant(CustomerCapabilities.PROPOSE_PAYMENT)
        propose()
        propose()
        propose(requestId = "req-00000002")
        val keys = Store.calls.map { it.first }
        assertThat(keys[0]).isEqualTo(keys[1])
        assertThat(keys[2]).isNotEqualTo(keys[0])
    }

    @Test
    fun `a read grant cannot propose and the store is never called`() {
        Grants.answer = grant(CustomerCapabilities.READ_BALANCES, CustomerCapabilities.READ_TRANSACTIONS)
        val deniedBefore = toolCalls("DENY")
        propose().body("result.isError", equalTo(true))
        assertThat(Store.calls).isEmpty()
        assertThat(recorder.events.last().result).isEqualTo(AuditResult.DENIED)
        assertThat(toolCalls("DENY")).isEqualTo(deniedBefore + 1)
    }

    @Test
    fun `a PDP deny refuses before the store`() {
        Grants.answer = grant(CustomerCapabilities.PROPOSE_PAYMENT)
        TestPolicyDecisionPoint.customerDeny = true
        propose().body("result.isError", equalTo(true))
        assertThat(Store.calls).isEmpty()
        assertThat(TestPolicyDecisionPoint.customerQueries.single().action).isEqualTo("propose.payment")
    }

    @Test
    fun `an invalid amount is refused before the store`() {
        Grants.answer = grant(CustomerCapabilities.PROPOSE_PAYMENT)
        propose(amount = "1e3").body("result.isError", equalTo(true))
            .body("result.content[0].text", containsString("Invalid arguments"))
        assertThat(Store.calls).isEmpty()
    }

    @Test
    fun `a store limit refusal is a fixed safe text`() {
        Grants.answer = grant(CustomerCapabilities.PROPOSE_PAYMENT)
        Store.refusal = ProposalStoreRefusal(429, "PROPOSAL_LIMIT_REACHED")
        propose().body("result.isError", equalTo(true))
            .body("result.content[0].text", equalTo(CustomerPaymentProposer.LIMIT_REACHED))
    }

    private companion object {
        val GRANT: UUID = UUID.fromString("d6d6d6d6-0000-4000-8000-000000000001")
        val PROPOSAL: UUID = UUID.fromString("d6d6d6d6-0000-4000-8000-000000000002")
    }
}
