// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.fetchbroker

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.authz.AuthzDecision
import com.openbank.libs.authz.AuthzQuery
import com.openbank.libs.authz.PolicyDecisionPoint
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Stands in for the OPA sidecar: allows everyone except `agent:opa-denied`. */
@ApplicationScoped
@Alternative
@Priority(1)
class TestPolicyDecisionPoint : PolicyDecisionPoint {
    override suspend fun allow(query: AuthzQuery): AuthzDecision =
        if (query.principal.id == "agent:opa-denied" || query.action != "read.public_web") {
            AuthzDecision(allow = false, reason = "no matching allow rule")
        } else {
            AuthzDecision(allow = true)
        }
}

/**
 * Drives POST /mcp over real HTTP: a unit test that calls the resource class cannot tell a served
 * route from an unserved one (root CLAUDE.md, McpEndpointRoutingIT). No call here reaches the
 * internet — every case is decided before a socket would open.
 */
@QuarkusTest
class FetchBrokerMcpRoutingIT {
    private val mapper = ObjectMapper()

    private fun call(url: String, tool: String = "web.fetch"): Map<*, *> {
        val body = given().contentType(ContentType.JSON)
            .body(
                """{"jsonrpc":"2.0","id":7,"method":"tools/call",""" +
                    """"params":{"name":"$tool","arguments":{"url":"$url"}}}""",
            )
            .post("/mcp").then().statusCode(200).extract().asString()
        return mapper.readValue(body, Map::class.java)
    }

    private fun envelope(response: Map<*, *>): Map<*, *> {
        val result = response["result"] as Map<*, *>
        assertThat(result["isError"]).isEqualTo(true)
        val text = ((result["content"] as List<*>).single() as Map<*, *>)["text"] as String
        return mapper.readValue(text, Map::class.java)
    }

    @Test
    @TestSecurity(user = "agent:web-reader")
    @OidcSecurity(claims = [Claim(key = "sub", value = "agent:web-reader"), Claim(key = "aud", value = AUD)])
    fun `tools list advertises exactly web fetch to an agent`() {
        given().contentType(ContentType.JSON).body("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
            .post("/mcp").then().statusCode(200)
            .extract().path<List<String>>("result.tools.name").let { assertThat(it).containsExactly("web.fetch") }
    }

    @Test
    fun `an anonymous caller is refused before any tool is listed or called`() {
        given().contentType(ContentType.JSON).body("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
            .post("/mcp").then().statusCode(401)
        given().contentType(ContentType.JSON)
            .body(
                """{"jsonrpc":"2.0","id":2,"method":"tools/call",""" +
                    """"params":{"name":"web.fetch","arguments":{"url":"https://example.org/"}}}""",
            )
            .post("/mcp").then().statusCode(401)
    }

    @Test
    @TestSecurity(user = "agent:web-reader")
    @OidcSecurity(claims = [Claim(key = "sub", value = "agent:web-reader"), Claim(key = "aud", value = AUD)])
    fun `an off-list host is DENIED by the broker even though the policy allowed it`() {
        val env = envelope(call("https://other.example/"))
        assertThat(env["outcome"]).isEqualTo("DENIED")
        assertThat(env["reason"] as String).contains("allowlist")
    }

    @Test
    @TestSecurity(user = "agent:web-reader")
    @OidcSecurity(claims = [Claim(key = "sub", value = "agent:web-reader"), Claim(key = "aud", value = AUD)])
    fun `a query string is DENIED`() {
        assertThat(envelope(call("https://example.org/a?x=1"))["outcome"]).isEqualTo("DENIED")
    }

    @Test
    @TestSecurity(user = "agent:opa-denied")
    @OidcSecurity(claims = [Claim(key = "sub", value = "agent:opa-denied"), Claim(key = "aud", value = AUD)])
    fun `a policy deny is DENIED before the broker fetches anything`() {
        val env = envelope(call("https://example.org/"))
        assertThat(env["outcome"]).isEqualTo("DENIED")
        assertThat(env["reason"] as String).startsWith("denied by policy")
    }

    @Test
    @TestSecurity(user = "agent:web-reader")
    @OidcSecurity(claims = [Claim(key = "sub", value = "agent:web-reader"), Claim(key = "aud", value = AUD)])
    fun `a js host with no renderer configured is NOT_RENDERABLE`() {
        val env = envelope(call("https://js.example.org/portal"))
        assertThat(env["outcome"]).isEqualTo("NOT_RENDERABLE")
        assertThat(env["renderer"]).isEqualTo("kitesurf")
        assertThat(env["content"]).isNull()
    }

    @Test
    @TestSecurity(user = "agent:web-reader")
    @OidcSecurity(claims = [Claim(key = "sub", value = "agent:web-reader"), Claim(key = "aud", value = AUD)])
    fun `an unknown tool is refused`() {
        val result = call("https://example.org/", tool = "web.post")["result"] as Map<*, *>
        assertThat(result["isError"]).isEqualTo(true)
    }

    @Test
    @TestSecurity(user = "agent:web-reader")
    @OidcSecurity(
        claims = [Claim(key = "sub", value = "agent:web-reader"), Claim(key = "aud", value = "openbank-mcp-service")],
    )
    fun `a token issued for another audience is refused with 401 on list and call`() {
        given().contentType(ContentType.JSON).body("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
            .post("/mcp").then().statusCode(401)
            .header("WWW-Authenticate", org.hamcrest.Matchers.containsString("invalid_token"))
        given().contentType(ContentType.JSON)
            .body(
                """{"jsonrpc":"2.0","id":2,"method":"tools/call",""" +
                    """"params":{"name":"web.fetch","arguments":{"url":"https://example.org/"}}}""",
            )
            .post("/mcp").then().statusCode(401)
    }

    @Test
    @TestSecurity(user = "agent:web-reader")
    @OidcSecurity(claims = [Claim(key = "sub", value = "agent:opa-denied"), Claim(key = "aud", value = AUD)])
    fun `the agent identity is the token sub, not the principal name`() {
        assertThat(envelope(call("https://example.org/"))["reason"] as String).startsWith("denied by policy")
    }

    @Test
    fun `pdf extraction is off in the running application`() {
        val enabled = org.eclipse.microprofile.config.ConfigProvider.getConfig()
            .getValue("openbank.fetch-broker.pdf.enabled", Boolean::class.java)
        assertThat(enabled).isFalse()
    }

    @Test
    fun `the metric and liveness gauge are exported`() {
        val metrics = given().get("/q/metrics").then().statusCode(200).extract().asString()
        assertThat(metrics).contains("openbank_workflow_last_success_age_seconds{workflow=\"web-fetch\"")
    }

    private companion object {
        const val AUD = "openbank-fetch-broker"
    }
}
