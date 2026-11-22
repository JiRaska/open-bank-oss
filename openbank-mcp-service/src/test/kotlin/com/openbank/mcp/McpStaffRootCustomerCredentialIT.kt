// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp

import com.openbank.libs.audit.AuditResult
import com.openbank.libs.testing.containers.PostgresTestResource
import com.openbank.mcp.infrastructure.ratelimit.McpRateLimiter
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.http.Header
import io.restassured.http.Headers
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration

/**
 * #16590 (review of #17086): the staff root with the customer channel OFF and the REAL
 * `mcp.customer.issuer` from application.yaml (no override here), over real HTTP.
 */
@QuarkusTest
@TestProfile(McpStaffRootCustomerCredentialIT.ChannelOff::class)
@QuarkusTestResource(PostgresTestResource::class, initArgs = [ResourceArg(name = "db", value = "openbank_mcp_it")])
class McpStaffRootCustomerCredentialIT {

    /**
     * Only the token source is swapped and the anonymous budget shrunk; the customer channel and
     * `mcp.customer.issuer` are the shipped defaults.
     */
    class ChannelOff : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> =
            mapOf("openbank.mcp.rate-limit.anonymous-calls-per-minute" to "2")

        override fun getEnabledAlternatives(): Set<Class<*>> = setOf(CustomerTokenSource::class.java)
    }

    @Inject
    lateinit var recorder: RecordingAuditEventPublisher

    @Inject
    lateinit var registry: MeterRegistry

    @Inject
    lateinit var limiter: McpRateLimiter

    @BeforeEach
    fun reset() {
        CustomerTokenSource.claims = emptyMap()
        recorder.clear()
        // A fresh minute window per test, so no case inherits another's spent anonymous budget.
        limiter.clock = Clock.offset(Clock.systemUTC(), Duration.ofMinutes(10L * window.incrementAndGet()))
    }

    @AfterEach
    fun restoreClock() {
        limiter.clock = Clock.systemUTC()
    }

    private fun rejected(reason: String): Double =
        registry.find("openbank.mcp.customer.rejected").tag("reason", reason).counter()?.count() ?: 0.0

    private fun post(vararg authorization: String, body: String = LIST) = given().contentType(ContentType.JSON)
        .headers(Headers(authorization.map { Header("Authorization", it) }))
        .body(body).post("/mcp").then()

    @Test
    fun `a Bearer customers-realm token is refused on the staff root with the channel off`() {
        CustomerTokenSource.claims = mapOf(
            "iss" to SHIPPED_CUSTOMER_ISSUER,
            "sub" to "agent:test-agent",
            "aud" to "openbank-mcp-service",
            "consent_id" to CONSENT,
        )
        val before = rejected("customer_channel_off")
        val pdpBefore = TestPolicyDecisionPoint.queryCount.get()
        post("Bearer t").statusCode(401).header("WWW-Authenticate", containsString("/customer/mcp"))
        post("Bearer t", body = CALL).statusCode(401).header("WWW-Authenticate", containsString("/customer/mcp"))
        assertThat(rejected("customer_channel_off") - before).isEqualTo(2.0)
        assertThat(TestPolicyDecisionPoint.queryCount.get()).isEqualTo(pdpBefore)
        val row = recorder.events.single()
        assertThat(row.result).isEqualTo(AuditResult.DENIED)
        assertThat(row.payload["reason"]).isEqualTo(STAFF_ROOT_REASON)
    }

    @Test
    fun `junk DPoP calls beyond the anonymous budget do not each write an audit row`() {
        // Unverified credential: `DPoP x` with no customers-realm token behind it.
        CustomerTokenSource.claims = emptyMap()
        val before = rejected("customer_channel_off")
        repeat(20) { post("DPoP x", body = CALL).statusCode(401) }
        assertThat(rejected("customer_channel_off") - before).isEqualTo(20.0)
        // The 2-call budget, plus one throttle row per window (two if the run straddles a minute).
        assertThat(recorder.events.size).isBetween(3, 4)
        val refusals = recorder.events.filter { it.payload["reason"] == STAFF_ROOT_REASON }
        assertThat(refusals).hasSize(2).allSatisfy { assertThat(it.result).isEqualTo(AuditResult.DENIED) }
    }

    @Test
    fun `two Authorization headers are refused with 400 invalid_request in either order`() {
        CustomerTokenSource.claims = staffClaims
        listOf(arrayOf("Bearer t", "DPoP t"), arrayOf("DPoP t", "Bearer t"), arrayOf("Bearer t", "Bearer u"))
            .forEach { headers ->
                post(*headers).statusCode(400).header("WWW-Authenticate", containsString("invalid_request"))
            }
    }

    @Test
    fun `control - one Bearer staff token is served`() {
        CustomerTokenSource.claims = staffClaims
        val tools = post("Bearer t").statusCode(200).extract().jsonPath().getList<String>("result.tools.name")
        assertThat(tools).isNotEmpty
    }

    private val staffClaims = mapOf(
        "iss" to "https://kc.test.example/realms/openbank",
        "sub" to "agent:test-agent",
        "aud" to "openbank-mcp-service",
        "consent_id" to CONSENT,
    )

    private companion object {
        /** application.yaml `mcp.customer.issuer` default, deliberately NOT overridden. */
        const val SHIPPED_CUSTOMER_ISSUER = "https://kc.open-bank.tech/realms/openbank-customers"
        const val STAFF_ROOT_REASON = "customer credential on the staff root"
        val window = java.util.concurrent.atomic.AtomicLong()
        const val CONSENT = "11111111-1111-1111-1111-111111111111"
        const val LIST = """{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}"""
        const val CALL = """{"jsonrpc":"2.0","id":1,"method":"tools/call",""" +
            """"params":{"name":"list_accounts","arguments":{}}}"""
    }
}
