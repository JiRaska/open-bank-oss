// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.testing.containers.PostgresTestResource
import com.openbank.mcp.application.McpAccountRefs
import com.openbank.mcp.application.port.out.ConsentContext
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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * #16678 over the REAL HTTP stack and CDI graph: `list_accounts` returns a masked IBAN plus an
 * `accountRef`, and that ref chains into `get_balance` for the SAME caller. A ref minted for another
 * caller (other agent, other consent, other session), or a tampered one, is refused with -32602 and
 * never reaches the read port.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class, initArgs = [ResourceArg(name = "db", value = "openbank_mcp_it")])
class McpAccountRefChainingIT {

    @Inject
    lateinit var refs: McpAccountRefs

    @Inject
    lateinit var limiter: McpRateLimiter

    @Inject
    lateinit var mapper: ObjectMapper

    @BeforeEach
    fun open() {
        TestPolicyDecisionPoint.allowEverything = true
        limiter.enabled = false
    }

    @AfterEach
    fun close() {
        TestPolicyDecisionPoint.allowEverything = false
        limiter.enabled = true
    }

    private fun call(tool: String, arguments: String): com.fasterxml.jackson.databind.JsonNode {
        val body = given().contentType(ContentType.JSON)
            .body("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$tool","arguments":$arguments}}""")
            .post("/mcp").then().statusCode(200).extract().body().asString()
        return mapper.readTree(body)
    }

    private fun text(resp: com.fasterxml.jackson.databind.JsonNode) =
        resp.path("result").path("content")[0].path("text").asText()

    @Test
    @TestSecurity(user = CALLER_A)
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = CALLER_A), Claim(key = "aud", value = "openbank-mcp-service"),
            Claim(key = "consent_id", value = CONSENT_A), Claim(key = "sid", value = SESSION_1),
        ],
    )
    fun `list_accounts then get_balance chains through accountRef for the same caller`() {
        val listed = text(call("list_accounts", "{}"))
        assertThat(listed).doesNotContain(TestAccountReadPort.GRANTED_IBAN).contains("****5399")
        val ref = REF.find(listed)?.value
        assertThat(ref).`as`("list_accounts carries an accountRef: %s", listed).isNotNull()
        assertThat(ref).isEqualTo(refs.refFor(ctxA, TestAccountReadPort.GRANTED_IBAN)!!)

        val balance = call("get_balance", """{"accountRef":"$ref"}""")
        assertThat(balance.has("error")).`as`("chained call refused: %s", balance).isFalse()
        val balanceText = text(balance)
        assertThat(balanceText).contains("\"tool\":\"get_balance\"").contains("****5399")
        assertThat(balanceText).doesNotContain(TestAccountReadPort.GRANTED_IBAN)
    }

    @Test
    @TestSecurity(user = CALLER_B)
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = CALLER_B), Claim(key = "aud", value = "openbank-mcp-service"),
            Claim(key = "consent_id", value = CONSENT_A), Claim(key = "sid", value = SESSION_1),
        ],
    )
    fun `a ref issued to another agent is refused even for the same IBAN and consent`() {
        val foreign = refs.refFor(ctxA, TestAccountReadPort.GRANTED_IBAN)!!
        assertRefused(call("get_balance", """{"accountRef":"$foreign"}"""))
    }

    @Test
    @TestSecurity(user = CALLER_A)
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = CALLER_A), Claim(key = "aud", value = "openbank-mcp-service"),
            Claim(key = "consent_id", value = CONSENT_B), Claim(key = "sid", value = SESSION_1),
        ],
    )
    fun `a ref issued under another consent grant is refused`() {
        val foreign = refs.refFor(ctxA, TestAccountReadPort.GRANTED_IBAN)!!
        assertRefused(call("list_transactions", """{"accountRef":"$foreign"}"""))
    }

    @Test
    @TestSecurity(user = CALLER_A)
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = CALLER_A), Claim(key = "aud", value = "openbank-mcp-service"),
            Claim(key = "consent_id", value = CONSENT_A), Claim(key = "sid", value = "session-2"),
        ],
    )
    fun `a ref issued in another session is refused`() {
        val foreign = refs.refFor(ctxA, TestAccountReadPort.GRANTED_IBAN)!!
        assertRefused(call("get_statement", """{"accountRef":"$foreign"}"""))
    }

    @Test
    @TestSecurity(user = CALLER_A)
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = CALLER_A), Claim(key = "aud", value = "openbank-mcp-service"),
            Claim(key = "consent_id", value = CONSENT_A), Claim(key = "sid", value = SESSION_1),
        ],
    )
    fun `a tampered ref is refused`() {
        val own = refs.refFor(ctxA, TestAccountReadPort.GRANTED_IBAN)!!
        val flipped = own.dropLast(1) + (if (own.last() == 'A') 'B' else 'A')
        assertRefused(call("get_balance", """{"accountRef":"$flipped"}"""))
    }

    @Test
    @TestSecurity(user = CALLER_A)
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = CALLER_A), Claim(key = "aud", value = "openbank-mcp-service"),
            Claim(key = "consent_id", value = CONSENT_A),
        ],
    )
    fun `a token without sid gets no accountRef and cannot use one`() {
        val listed = text(call("list_accounts", "{}"))
        assertThat(REF.find(listed)).`as`("no ref without a session: %s", listed).isNull()
        assertThat(listed).contains("****5399")
        assertRefused(
            call("get_balance", """{"accountRef":"${refs.refFor(ctxA, TestAccountReadPort.GRANTED_IBAN)!!}"}"""),
        )
    }

    private fun assertRefused(resp: com.fasterxml.jackson.databind.JsonNode) {
        assertThat(resp.path("error").path("code").asInt()).`as`("%s", resp).isEqualTo(INVALID_PARAMS)
        assertThat(resp.path("error").path("message").asText()).isEqualTo("accountRef is not valid for this caller")
        assertThat(resp.toString()).doesNotContain(TestAccountReadPort.GRANTED_IBAN)
    }

    companion object {
        const val CALLER_A = "agent:ref-a"
        const val CALLER_B = "agent:ref-b"
        const val CONSENT_A = "aaaaaaaa-1111-1111-1111-111111111111"
        const val CONSENT_B = "bbbbbbbb-2222-2222-2222-222222222222"
        const val INVALID_PARAMS = -32602
        const val SESSION_1 = "session-1"
        val ctxA = ConsentContext(
            agentId = CALLER_A,
            consentId = CONSENT_A,
            grantedAccounts = emptyList(),
            sessionId = SESSION_1,
        )
        val REF = Regex("acr_[A-Za-z0-9_-]{43}")
    }
}
