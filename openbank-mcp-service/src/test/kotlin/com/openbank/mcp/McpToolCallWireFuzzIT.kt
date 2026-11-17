// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.testing.containers.PostgresTestResource
import com.openbank.mcp.FuzzPayloads.Payload
import com.openbank.mcp.infrastructure.ratelimit.McpRateLimiter
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

private const val WIRE_FUZZ_CONSENT_ID = "22222222-2222-2222-2222-222222222222"

/**
 * Issue #16617, layer 5 (wire half): the same hostile corpus as [McpToolCallFuzzTest], sent over
 * REAL HTTP into the REAL CDI graph. This is the only layer that sees what the platform does BEFORE
 * the endpoint runs: the JSON parser's nesting limit, the fleet NUL guard (a NUL arrives as the
 * escape `\u0000`, so only the decoded value reveals it), lone-surrogate re-encoding, and the body
 * limit — each of which can turn an input into an HTTP 500 that no in-process test can produce.
 *
 * Invariants for every request: status is 2xx or 4xx (never 5xx); the body carries no stack trace or
 * internal class name; the response is bounded. Refusal of a payload containing U+0000 is not asserted for U+0000 because it does not hold today — see `a NUL in any argument is refused`
 * (disabled, deviation W1).
 *
 * The PDP is opened to every tool ([TestPolicyDecisionPoint.allowEverything]) only for the duration
 * of this class, so argument handling of each tool is reachable; the rate limiter is disabled for
 * the same reason and both are restored afterwards.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class, initArgs = [ResourceArg(name = "db", value = "openbank_mcp_it")])
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestSecurity(user = "agent:fuzz")
@OidcSecurity(
    claims = [Claim(key = "sub", value = "agent:fuzz"), Claim(key = "consent_id", value = WIRE_FUZZ_CONSENT_ID)],
)
class McpToolCallWireFuzzIT {

    @Inject
    lateinit var recorder: RecordingAuditEventPublisher

    @Inject
    lateinit var limiter: McpRateLimiter

    @Inject
    lateinit var mapper: ObjectMapper

    @BeforeAll
    fun open() {
        TestPolicyDecisionPoint.allowEverything = true
        limiter.enabled = false
    }

    @AfterAll
    fun close() {
        TestPolicyDecisionPoint.allowEverything = false
        limiter.enabled = true
    }

    @BeforeEach
    fun reset() = recorder.clear()

    private val firstSlot = mapOf(
        "get_balance" to "accountId",
        "list_transactions" to "accountId",
        "get_statement" to "accountId",
        "get_payment_confirmation" to "paymentId",
        "propose_payment" to "toIban",
    )

    private fun call(tool: String, argumentsJson: String) =
        """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$tool","arguments":$argumentsJson}}"""

    private fun send(body: String) = given().contentType(ContentType.JSON).body(body).post("/mcp")

    @Suppress("UNUSED_PARAMETER")
    private fun assertSurvived(label: String, body: String, payload: Payload, requestEchoes: Boolean = false) {
        recorder.clear()
        val resp = send(body)
        val text = resp.body.asString()
        assertThat(resp.statusCode).`as`("%s -> %s", label, text.take(300)).isLessThan(500)
        LEAK_MARKERS.forEach { assertThat(text).`as`("%s leaks %s", label, it).doesNotContain(it) }
        if (!requestEchoes) {
            assertThat(text.length).`as`("%s response size", label).isLessThan(RESPONSE_CAP_BYTES)
        }
        if (resp.statusCode == 200) {
            val node = mapper.readTree(text)
            assertThat(node.has("result") != node.has("error")).`as`("%s: one of result/error", label).isTrue()
        }
    }

    @Test
    fun `every tool survives every small hostile value in its first required slot`() {
        firstSlot.forEach { (tool, slot) ->
            FuzzPayloads.small.forEach { p ->
                val body = call(tool, """{"$slot":${p.json}}""")
                assertSurvived("$tool.$slot=${p.label}", body, p)
            }
        }
    }

    @Test
    fun `every tool survives every small hostile value as the whole arguments member`() {
        (firstSlot.keys + listOf("list_accounts", "list_consents", "count_marketing_consents")).forEach { tool ->
            FuzzPayloads.small.forEach { p ->
                val body = call(tool, p.json)
                assertSurvived("$tool.arguments=${p.label}", body, p)
            }
        }
    }

    @Test
    fun `huge values survive in the slots that matter`() {
        val huge: List<Payload> = FuzzPayloads.all.filter { it.isHuge }
        assertThat(huge).isNotEmpty
        listOf("get_balance" to "accountId", "get_payment_confirmation" to "paymentId").forEach { (tool, slot) ->
            huge.forEach { p ->
                val body = call(tool, """{"$slot":${p.json}}""")
                assertSurvived("$tool.$slot=${p.label}", body, p, requestEchoes = tool == "get_payment_confirmation")
            }
        }
    }

    @Test
    fun `hostile values in params, name and method survive`() {
        FuzzPayloads.small.forEach { p ->
            assertSurvived(
                "params=${p.label}",
                """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":${p.json}}""",
                p,
            )
            assertSurvived(
                "name=${p.label}",
                """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":${p.json},"arguments":{}}}""",
                p,
                requestEchoes = true,
            )
            assertSurvived(
                "method=${p.label}",
                """{"jsonrpc":"2.0","id":1,"method":${p.json}}""",
                p,
                requestEchoes = true,
            )
            assertSurvived(
                "id=${p.label}",
                """{"jsonrpc":"2.0","id":${p.json},"method":"ping"}""",
                p,
                requestEchoes = true,
            )
        }
    }

    @Test
    fun `nesting beyond the parser limit is a client error, not a 500`() {
        FuzzPayloads.all.filter { it.isUnparseableDeep }.forEach { p ->
            val resp =
                send(
                    """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"get_balance","arguments":{"accountId":${p.json}}}}""",
                )
            assertThat(resp.statusCode).`as`(p.label).isBetween(400, 499)
            LEAK_MARKERS.forEach { assertThat(resp.body.asString()).doesNotContain(it) }
        }
        // And the whole body being deep, not just one argument.
        val deep = "[".repeat(5_000) + "]".repeat(5_000)
        assertThat(send(deep).statusCode).isBetween(400, 499)
    }

    @Test
    fun `an argument name over the parser limit is a client error, not a 500`() {
        val resp = send(
            """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"list_accounts","arguments":{"${"K".repeat(
                70_000,
            )}":1}}}""",
        )
        assertThat(resp.statusCode).isBetween(400, 499)
    }

    @Disabled(
        "Deviation W1 (#16617): the fleet NUL guard (#5913) hooks the String deserializer, but McpEndpoint binds the body as a JsonNode tree, so U+0000 in any argument is accepted (HTTP 200) and flows to the tool, the downstream request and the audit record",
    )
    @Test
    fun `a NUL in any argument is refused before the endpoint runs`() {
        val nul = FuzzPayloads.all.first { it.label == "nul" }
        listOf(
            """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"get_balance","arguments":{"accountId":${nul.json}}}}""",
            """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"get_balance","arguments":{${nul.json}:1}}}""",
            """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":${nul.json},"arguments":{}}}""",
        ).forEach { body ->
            recorder.clear()
            assertThat(send(body).statusCode).`as`(body).isBetween(400, 499)
            assertThat(recorder.events).`as`("a NUL request reached the endpoint").isEmpty()
        }
    }

    @Test
    fun `a body far above any sane size is bounded, not a 500`() {
        val huge = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"get_balance","arguments":{"accountId":"${"A".repeat(
            11 * FuzzPayloads.MEGABYTE,
        )}"}}}"""
        // Over the platform body limit the server may answer 413 OR drop the connection while the
        // client is still writing; both are a refusal. What must not happen is a 5xx — and the
        // service must still answer afterwards.
        val status = try {
            send(huge).statusCode
        } catch (ex: java.io.IOException) {
            // "Broken pipe" / "connection reset": the server stopped reading at its body limit.
            assertThat(ex).isInstanceOf(java.io.IOException::class.java)
            REFUSED_BY_CLOSE
        }
        assertThat(status).isIn(400, 413, REFUSED_BY_CLOSE)
        assertThat(send("""{"jsonrpc":"2.0","id":1,"method":"ping"}""").statusCode).isEqualTo(200)
    }

    @Test
    fun `the corpus is not vacuous over the wire - a hostile call really reaches the endpoint and is audited`() {
        // Known-positive for the sweep itself: with the PDP opened, an ordinary get_balance call is
        // processed (one audit event). If the harness silently stopped reaching the endpoint, every
        // "survived" verdict above would be decoration.
        recorder.clear()
        val resp =
            send(
                """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"get_balance","arguments":{"accountId":"x"}}}""",
            )
        assertThat(resp.statusCode).isEqualTo(200)
        assertThat(recorder.events).hasSize(1)
        assertThat(recorder.events.single().payload).containsEntry("policy_decision", "ALLOW")
    }

    private companion object {
        const val REFUSED_BY_CLOSE = -1
        const val RESPONSE_CAP_BYTES = 64 * 1024
        val LEAK_MARKERS =
            listOf("\tat ", "Caused by", "java.lang", "java.util", "kotlin.", "io.quarkus", "com.openbank")
    }
}
