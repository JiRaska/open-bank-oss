// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp

import com.openbank.libs.audit.AuditResult
import com.openbank.libs.testing.containers.PostgresTestResource
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
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * RFC 8707 / ADR-0383 D1 (#16622): `/mcp` accepts only tokens issued FOR this server. A token that
 * is otherwise valid for the realm but carries another `aud` is token passthrough and must be
 * refused with 401 on every method, before any tool is listed or called.
 *
 * Driven over real HTTP through the real CDI graph. `@OidcSecurity` supplies the claims; signature
 * and issuer validation are Quarkus OIDC's job and not under test here — the audience decision is
 * this service's own code, which is what the test pins.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class, initArgs = [ResourceArg(name = "db", value = "openbank_mcp_it")])
class McpTokenAudienceIT {

    @Inject
    lateinit var recorder: RecordingAuditEventPublisher

    @BeforeEach
    fun reset() = recorder.clear()

    private fun post(body: String) = given().contentType(ContentType.JSON).body(body).post("/mcp").then()

    @Test
    @TestSecurity(user = "agent:test-agent")
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "agent:test-agent"),
            Claim(key = "aud", value = "openbank-admin-ui"),
            Claim(key = "consent_id", value = CONSENT),
        ],
    )
    fun `a token for another audience is refused with 401 and lists no tools`() {
        post("""{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""")
            .statusCode(401)
            .header("WWW-Authenticate", containsString("invalid_token"))
        assertThat(recorder.events).isEmpty()
    }

    @Test
    @TestSecurity(user = "agent:test-agent")
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "agent:test-agent"),
            Claim(key = "aud", value = "openbank-admin-ui"),
            Claim(key = "consent_id", value = CONSENT),
        ],
    )
    fun `a token for another audience cannot call a tool and the refusal is audited`() {
        post(
            """{"jsonrpc":"2.0","id":1,"method":"tools/call",""" +
                """"params":{"name":"${TestPolicyDecisionPoint.ALLOWED_TOOL}","arguments":{}}}""",
        ).statusCode(401)
        val event = recorder.events.single()
        assertThat(event.result).isEqualTo(AuditResult.DENIED)
        assertThat(event.actorId).isEqualTo("unknown")
    }

    @Test
    @TestSecurity(user = "agent:test-agent")
    @OidcSecurity(claims = [Claim(key = "sub", value = "agent:test-agent"), Claim(key = "consent_id", value = CONSENT)])
    fun `a token with no audience at all is refused with 401`() {
        post("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""").statusCode(401)
    }

    @Test
    @TestSecurity(user = "agent:test-agent")
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "agent:test-agent"),
            Claim(key = "aud", value = "openbank-mcp-service"),
            Claim(key = "consent_id", value = CONSENT),
        ],
    )
    fun `a token issued for this server lists the tools its charter allows`() {
        val tools = post("""{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""")
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList<String>("result.tools.name")
        assertThat(tools).isNotEmpty
    }

    @Test
    fun `an anonymous call keeps its existing deny without 401`() {
        post("""{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""")
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList<String>("result.tools")
            .let { assertThat(it).isEmpty() }
    }

    private companion object {
        const val CONSENT = "11111111-1111-1111-1111-111111111111"
    }
}
