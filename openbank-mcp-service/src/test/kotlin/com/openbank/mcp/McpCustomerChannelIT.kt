// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp

import com.openbank.libs.audit.AuditResult
import com.openbank.libs.testing.containers.PostgresTestResource
import com.openbank.mcp.infrastructure.customer.DpopTestProofs
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * ADR-0383 D1/D2 + ADR-0385 D1 over real HTTP through the real CDI graph, with the customer channel
 * ON. Each negative test was also run with its guard removed and went red (PR evidence).
 */
@QuarkusTest
@TestProfile(McpCustomerChannelIT.CustomerOn::class)
@QuarkusTestResource(PostgresTestResource::class, initArgs = [ResourceArg(name = "db", value = "openbank_mcp_it")])
class McpCustomerChannelIT {

    /** Literals only: a profile loads in a different classloader from the test class (CLAUDE.md). */
    class CustomerOn : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "mcp.customer.enabled" to "true",
            "mcp.customer.shared-store" to "memory",
            "mcp.customer.issuer" to "https://kc.test.example/realms/openbank-customers",
            "mcp.customer.allowlist" to "classpath:test-mcp-client-allowlist.yaml",
            // Every test below spends this window; the throttle test uses its own subject.
            "openbank.mcp.rate-limit.calls-per-minute" to "8",
        ) + GatewayTls.listener()

        override fun testResources() = GatewayTls.resources

        override fun getEnabledAlternatives(): Set<Class<*>> = setOf(CustomerTokenSource::class.java)
    }

    @Inject
    lateinit var recorder: RecordingAuditEventPublisher

    private val key = DpopTestProofs.newKey()

    @BeforeEach
    fun reset() {
        recorder.clear()
        CustomerTokenSource.claims = emptyMap()
    }

    private fun customerClaims(
        azp: String = "mcp-local-test",
        sub: String = CUSTOMER,
        extra: Map<String, Any?> = emptyMap(),
    ) = mapOf(
        "iss" to DpopTestProofs.ISSUER,
        "sub" to sub,
        "aud" to "openbank-mcp-service",
        "azp" to azp,
        "cnf" to mapOf("jkt" to DpopTestProofs.jkt(key)),
    ) + extra

    private fun post(body: String, proof: String? = DpopTestProofs.proof(key), scheme: String? = "DPoP") =
        GatewayTls.spec().contentType(ContentType.JSON)
            .header("Host", "mcp.test.example")
            .apply { scheme?.let { header("Authorization", "$it ${DpopTestProofs.ACCESS_TOKEN}") } }
            .apply { proof?.let { header("DPoP", it) } }
            .body(body).post("/customer/mcp").then()

    @Test
    fun `protected-resource metadata is published at both RFC 9728 locations`() {
        listOf(
            "/.well-known/oauth-protected-resource",
            "/.well-known/oauth-protected-resource/customer/mcp",
            "/.well-known/oauth-protected-resource/mcp",
        ).forEach { path ->
            GatewayTls.spec().get(path).then().statusCode(200)
                .body("resource", equalTo(DpopTestProofs.RESOURCE))
                .body("authorization_servers[0]", equalTo(DpopTestProofs.ISSUER))
                .body("dpop_bound_access_tokens_required", equalTo(true))
        }
    }

    @Test
    fun `the customer root and its metadata are not served on the plain-HTTP listener`() {
        CustomerTokenSource.claims = customerClaims()
        given().contentType(ContentType.JSON)
            .header("Authorization", "DPoP ${DpopTestProofs.ACCESS_TOKEN}")
            .header("DPoP", DpopTestProofs.proof(key))
            .body(LIST).post("/customer/mcp").then().statusCode(404)
        listOf("/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/customer/mcp")
            .forEach { given().get(it).then().statusCode(404) }
    }

    @Test
    fun `an unauthenticated call gets 401 pointing at the metadata, and no tools`() {
        post(LIST, proof = null, scheme = null)
            .statusCode(401)
            .header("WWW-Authenticate", containsString("DPoP"))
            .header(
                "WWW-Authenticate",
                containsString(
                    "resource_metadata=\"https://mcp.test.example/.well-known/oauth-protected-resource/customer/mcp\"",
                ),
            )
    }

    @Test
    fun `an admitted customer reaches no tool and the refusal is audited under the customer`() {
        CustomerTokenSource.claims = customerClaims()
        post(LIST).statusCode(200).body("result.tools.size()", equalTo(0))
        post(CALL).statusCode(200).body("result.isError", equalTo(true))
        val call = recorder.events.last()
        assertThat(call.result).isEqualTo(AuditResult.DENIED)
        assertThat(call.actorId).isEqualTo(CUSTOMER)
    }

    @Test
    fun `a customer token shaped like an agent token never reaches the staff tools`() {
        CustomerTokenSource.claims = customerClaims(sub = "agent:test-agent", extra = mapOf("consent_id" to CONSENT))
        post(LIST).statusCode(200).body("result.tools.size()", equalTo(0))
    }

    @Test
    fun `control - a staff agent token still lists its charter tools with the channel on`() {
        CustomerTokenSource.claims = mapOf(
            "iss" to DpopTestProofs.STAFF_ISSUER,
            "sub" to "agent:test-agent",
            "aud" to "openbank-mcp-service",
            "consent_id" to CONSENT,
        )
        // The staff root, plain HTTP: the customer root is a separate path the staff path never shares.
        val tools = given().contentType(ContentType.JSON)
            .header("Authorization", "Bearer ${DpopTestProofs.ACCESS_TOKEN}")
            .body(LIST).post("/mcp").then().statusCode(200)
            .extract().jsonPath().getList<String>("result.tools.name")
        assertThat(tools).isNotEmpty
    }

    @Test
    fun `a staff agent token on the customer root is refused, never dispatched to the staff tools`() {
        CustomerTokenSource.claims = mapOf(
            "iss" to DpopTestProofs.STAFF_ISSUER,
            "sub" to "agent:test-agent",
            "aud" to "openbank-mcp-service",
            "consent_id" to CONSENT,
        )
        post(LIST, proof = null, scheme = "Bearer").statusCode(401)
            .header("WWW-Authenticate", containsString("resource_metadata="))
    }

    @Test
    fun `a customer token on the staff root never reaches the staff tools`() {
        CustomerTokenSource.claims = customerClaims(sub = "agent:test-agent", extra = mapOf("consent_id" to CONSENT))
        given().contentType(ContentType.JSON)
            .header("Authorization", "DPoP ${DpopTestProofs.ACCESS_TOKEN}")
            .header("DPoP", DpopTestProofs.proof(key))
            .body(LIST).post("/mcp").then().statusCode(200).body("result.tools.size()", equalTo(0))
    }

    @Test
    fun `a staff-realm token with customer-shaped claims and a valid proof is not admitted as a customer`() {
        CustomerTokenSource.claims = customerClaims() + ("iss" to DpopTestProofs.STAFF_ISSUER)
        // On the customer root a non-customers-realm token is refused outright (#16847).
        post(CALL).statusCode(401)
    }

    @Test
    fun `a call without a DPoP proof is refused with invalid_dpop_proof`() {
        CustomerTokenSource.claims = customerClaims()
        post(LIST, proof = null).statusCode(401).header("WWW-Authenticate", containsString("invalid_dpop_proof"))
    }

    @Test
    fun `a replayed proof is refused`() {
        CustomerTokenSource.claims = customerClaims()
        val proof = DpopTestProofs.proof(key)
        post(LIST, proof = proof).statusCode(200)
        post(LIST, proof = proof).statusCode(401).header("WWW-Authenticate", containsString("invalid_dpop_proof"))
    }

    @Test
    fun `a proof bound to another URI is refused`() {
        CustomerTokenSource.claims = customerClaims()
        post(LIST, proof = DpopTestProofs.proof(key, htu = "https://other.example/mcp")).statusCode(401)
    }

    @Test
    fun `an unknown client is refused and the attempt is audited`() {
        // Its own subject: refusal audits share one window per subject (see the flood test).
        CustomerTokenSource.claims =
            customerClaims(azp = "mcp-not-declared", sub = "unknown-client-0000-0000-000000000004")
        post(CALL).statusCode(401).header("WWW-Authenticate", containsString("invalid_token"))
        assertThat(recorder.events.single().result).isEqualTo(AuditResult.DENIED)
    }

    @Test
    fun `a declared but disabled client is refused`() {
        CustomerTokenSource.claims = customerClaims(azp = "mcp-disabled-test")
        post(LIST).statusCode(401)
    }

    private companion object {
        const val CUSTOMER = "c0ffee00-0000-0000-0000-000000000001"
        const val CONSENT = "11111111-1111-1111-1111-111111111111"
        const val LIST = """{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}"""
        val CALL = """{"jsonrpc":"2.0","id":1,"method":"tools/call",""" +
            """"params":{"name":"${TestPolicyDecisionPoint.ALLOWED_TOOL}","arguments":{}}}"""
    }

    @Test
    fun `a NUL in the JSON-RPC id is refused before the customer branch and never echoed`() {
        CustomerTokenSource.claims = customerClaims()
        val body = post("""{"jsonrpc":"2.0","id":"a\u0000b","method":"tools/list","params":{}}""")
            .statusCode(400)
            .extract().asString()
        assertThat(body).doesNotContain("\\u0000").doesNotContain("\u0000")
        assertThat(body).contains("\"id\":null")
    }

    @Test
    fun `an admitted customer is throttled per subject and client before the proof is checked`() {
        CustomerTokenSource.claims = customerClaims(sub = "throttle-subject-0000-0000-000000000002")
        val statuses = (1..20).map { post(LIST).extract().statusCode() }
        assertThat(statuses).contains(429)
        assertThat(statuses.first()).isEqualTo(200)
    }

    @Test
    fun `a flood of refused calls writes at most one audit row per subject per window`() {
        CustomerTokenSource.claims =
            customerClaims(azp = "mcp-not-declared", sub = "flood-subject-0000-0000-000000000003")
        repeat(10) { post(CALL).statusCode(401) }
        // One per minute window; two only if the run straddles a minute boundary.
        assertThat(recorder.events.size).isBetween(1, 2)
    }
}
