// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.audit.AuditResult
import com.openbank.libs.testing.containers.PostgresTestResource
import com.openbank.mcp.infrastructure.customer.CustomerCapabilities
import com.openbank.mcp.infrastructure.customer.CustomerDownstream
import com.openbank.mcp.infrastructure.customer.CustomerGrant
import com.openbank.mcp.infrastructure.customer.CustomerGrantSource
import com.openbank.mcp.infrastructure.customer.CustomerTokenExchange
import com.openbank.mcp.infrastructure.customer.DownstreamAudience
import com.openbank.mcp.infrastructure.customer.DownstreamRefused
import com.openbank.mcp.infrastructure.customer.DpopTestProofs
import com.openbank.mcp.infrastructure.customer.GrantKey
import com.openbank.mcp.infrastructure.customer.GrantLookupUnavailable
import com.openbank.mcp.infrastructure.customer.TokenExchangeRefused
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
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * ADR-0383 D3/D4 over real HTTP through the real CDI graph: the customer's tools are exactly what
 * its active grants allow, every read runs on an exchanged customer token, and every failure on that
 * path is a refusal. delegation-service, the customers-realm token endpoint and the downstream reads
 * are replaced at their ports; the grant cache, the tools and the branch are the production beans.
 * Each negative was also run with its guard removed and went red (PR evidence).
 */
@QuarkusTest
@TestProfile(McpCustomerDelegationIT.DataPlaneDoubles::class)
@QuarkusTestResource(PostgresTestResource::class, initArgs = [ResourceArg(name = "db", value = "openbank_mcp_it")])
class McpCustomerDelegationIT {

    /** The customer channel ON, with the three ports replaced by the doubles below. */
    class DataPlaneDoubles : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = McpCustomerChannelIT.CustomerOn().configOverrides

        override fun getEnabledAlternatives(): Set<Class<*>> = setOf(
            CustomerTokenSource::class.java,
            Grants::class.java,
            Exchange::class.java,
            Downstream::class.java,
        )
    }

    @Inject
    lateinit var recorder: RecordingAuditEventPublisher

    private val key = DpopTestProofs.newKey()
    private val account: UUID = UUID.randomUUID()

    /** A distinct party and subject per test: the grant cache and the throttle windows are shared. */
    private val party: UUID = UUID.randomUUID()
    private val subject: String = UUID.randomUUID().toString()

    @Alternative
    @ApplicationScoped
    class Grants : CustomerGrantSource {
        override fun resolve(key: GrantKey): List<CustomerGrant> {
            asked += key
            return answer(key)
        }

        companion object {
            val asked = CopyOnWriteArrayList<GrantKey>()

            @Volatile
            var answer: (GrantKey) -> List<CustomerGrant> = { emptyList() }
        }
    }

    @Alternative
    @ApplicationScoped
    class Exchange : CustomerTokenExchange {
        override fun exchange(subjectToken: String, subject: String, audience: DownstreamAudience): String {
            calls += Triple(subjectToken, subject, audience)
            if (fail) throw TokenExchangeRefused("realm refused")
            return "exchanged-for-${audience.clientId}"
        }

        companion object {
            val calls = CopyOnWriteArrayList<Triple<String, String, DownstreamAudience>>()

            @Volatile
            var fail = false
        }
    }

    @Alternative
    @ApplicationScoped
    class Downstream : CustomerDownstream {
        private val mapper = ObjectMapper()

        override fun balances(token: String, accountId: UUID): JsonNode {
            tokens += token
            if (refuse) throw DownstreamRefused(403)
            return mapper.readTree("""{"accountId":"$accountId","available":"42.00"}""")
        }

        override fun transactions(token: String, accountId: UUID, limit: Int): JsonNode {
            tokens += token
            return mapper.readTree("""{"items":[]}""")
        }

        companion object {
            val tokens = CopyOnWriteArrayList<String>()

            @Volatile
            var refuse = false
        }
    }

    private val grants = Grants.Companion
    private val exchange = Exchange.Companion
    private val downstream = Downstream.Companion

    @BeforeEach
    fun install() {
        recorder.clear()
        Grants.asked.clear()
        Grants.answer = { emptyList() }
        Exchange.calls.clear()
        Exchange.fail = false
        Downstream.tokens.clear()
        Downstream.refuse = false
        TestPolicyDecisionPoint.customerQueries.clear()
        TestPolicyDecisionPoint.customerDeny = false
        TestPolicyDecisionPoint.customerOutage = false
        CustomerTokenSource.claims = mapOf(
            "iss" to DpopTestProofs.ISSUER,
            "sub" to subject,
            "aud" to "openbank-mcp-service",
            "azp" to "mcp-local-test",
            "party_id" to party.toString(),
            "cnf" to mapOf("jkt" to DpopTestProofs.jkt(key)),
        )
    }

    private fun grant(vararg caps: String, grantor: UUID = party) = { k: GrantKey ->
        if (k.partyId == grantor) listOf(CustomerGrant(GRANT, account, caps.toSet(), null)) else emptyList()
    }

    private fun post(body: String) = given().contentType(ContentType.JSON)
        .header("Authorization", "DPoP ${DpopTestProofs.ACCESS_TOKEN}")
        .header("DPoP", DpopTestProofs.proof(key))
        .body(body).post("/mcp").then().statusCode(200)

    private fun call(tool: String, accountId: UUID = account) = post(
        """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$tool","arguments":{"accountId":"$accountId"}}}""",
    )

    private fun toolNames(): List<String> = post(LIST).extract().jsonPath().getList("result.tools.name")

    @Test
    fun `an active balance grant offers get_balance and reads on an exchanged customer token`() {
        grants.answer = grant(CustomerCapabilities.READ_BALANCES)
        assertThat(toolNames()).containsExactly("get_balance")
        call("get_balance").body("result.isError", equalTo(false))
        // The grant was looked up by the TOKEN's party, client and key — never by an argument.
        assertThat(grants.asked).allMatch {
            it.partyId == party && it.clientId == "mcp-local-test" && it.keyThumbprint == DpopTestProofs.jkt(key)
        }
        assertThat(exchange.calls.single()).isEqualTo(
            Triple(DpopTestProofs.ACCESS_TOKEN, subject, DownstreamAudience.BALANCE),
        )
        assertThat(downstream.tokens.single()).isEqualTo("exchanged-for-openbank-balance-service")
        val audit = recorder.events.last()
        assertThat(audit.result).isEqualTo(AuditResult.SUCCESS)
        assertThat(audit.payload["consent_id"]).isEqualTo("delegation:$GRANT")
    }

    @Test
    fun `a revoked grant - no active grant - offers no tool and refuses every call`() {
        grants.answer = { emptyList() }
        assertThat(toolNames()).isEmpty()
        call("get_balance").body("result.isError", equalTo(true))
        assertThat(exchange.calls).isEmpty()
        assertThat(recorder.events.last().result).isEqualTo(AuditResult.DENIED)
    }

    @Test
    fun `a foreign party's grant to the same client never answers for this customer`() {
        grants.answer = grant(CustomerCapabilities.READ_BALANCES, grantor = UUID.randomUUID())
        assertThat(toolNames()).isEmpty()
        call("get_balance").body("result.isError", equalTo(true))
        assertThat(downstream.tokens).isEmpty()
    }

    @Test
    fun `scope escalation - a balance grant cannot list transactions or reach another account`() {
        grants.answer = grant(CustomerCapabilities.READ_BALANCES)
        call("list_transactions").body("result.isError", equalTo(true))
        call("get_balance", accountId = UUID.randomUUID()).body("result.isError", equalTo(true))
        call("propose_payment").body("result.isError", equalTo(true))
        assertThat(exchange.calls).isEmpty()
        assertThat(downstream.tokens).isEmpty()
    }

    @Test
    fun `an exchange failure refuses and never falls back to another credential`() {
        grants.answer = grant(CustomerCapabilities.READ_BALANCES)
        exchange.fail = true
        call("get_balance").body("result.isError", equalTo(true))
            .body("result.content[0].text", equalTo("Authorization unavailable"))
        assertThat(downstream.tokens).isEmpty()
    }

    @Test
    fun `a grant lookup failure refuses and lists nothing`() {
        grants.answer = { throw GrantLookupUnavailable("delegation-service down") }
        assertThat(toolNames()).isEmpty()
        call("get_balance").body("result.isError", equalTo(true))
        assertThat(exchange.calls).isEmpty()
    }

    @Test
    fun `a downstream refusal drops the cached grant so the next call asks again`() {
        grants.answer = grant(CustomerCapabilities.READ_BALANCES)
        downstream.refuse = true
        call("get_balance").body("result.isError", equalTo(true))
        val before = grants.asked.size
        call("get_balance")
        assertThat(grants.asked.size).isGreaterThan(before)
    }

    @Test
    fun `a token without a party_id is refused before any lookup`() {
        CustomerTokenSource.claims = CustomerTokenSource.claims - "party_id"
        grants.answer = grant(CustomerCapabilities.READ_BALANCES)
        assertThat(toolNames()).isEmpty()
        assertThat(grants.asked).isEmpty()
    }

    @Test
    fun `mcp-service's PDP is consulted as the customer on the customer channel, and its deny refuses`() {
        grants.answer = grant(CustomerCapabilities.READ_BALANCES)
        call("get_balance").body("result.isError", equalTo(false))
        val q = TestPolicyDecisionPoint.customerQueries.single()
        assertThat(q.principal.id).isEqualTo(subject)
        assertThat(q.attributes["client"]).isEqualTo("mcp-local-test")
        TestPolicyDecisionPoint.customerDeny = true
        exchange.calls.clear()
        call("get_balance").body("result.isError", equalTo(true))
        assertThat(exchange.calls).isEmpty()
    }

    @Test
    fun `a PDP outage on the customer channel refuses before any exchange`() {
        grants.answer = grant(CustomerCapabilities.READ_BALANCES)
        TestPolicyDecisionPoint.customerOutage = true
        call("get_balance").body("result.content[0].text", equalTo("Authorization unavailable"))
        assertThat(exchange.calls).isEmpty()
    }

    private companion object {
        val GRANT: UUID = UUID.fromString("d4d4d4d4-0000-4000-8000-000000000001")
        const val LIST = """{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}"""
    }
}
