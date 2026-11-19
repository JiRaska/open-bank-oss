// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp

import com.openbank.libs.testing.containers.PostgresTestResource
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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** Default config: the customer channel is OFF and advertises nothing (ADR-0383 exposure order). */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class, initArgs = [ResourceArg(name = "db", value = "openbank_mcp_it")])
@TestProfile(McpCustomerChannelOffIT.OffWithTokens::class)
class McpCustomerChannelOffIT {

    /** Channel OFF (the default), with per-test tokens so a customers-realm token can be presented. */
    class OffWithTokens : QuarkusTestProfile {
        override fun getEnabledAlternatives(): Set<Class<*>> = setOf(CustomerTokenSource::class.java)
    }

    @AfterEach
    fun reset() {
        CustomerTokenSource.claims = emptyMap()
    }

    @Test
    fun `a customers-realm token is refused 401 while the channel is off, never served as anonymous`() {
        CustomerTokenSource.claims = mapOf(
            "iss" to "https://kc.open-bank.tech/realms/openbank-customers",
            "sub" to "c0ffee00-0000-0000-0000-0000000000f1",
            "aud" to "openbank-mcp-service",
            "azp" to "mcp-local-reference",
        )
        listOf(
            """{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""",
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""",
        ).forEach { body ->
            given().contentType(ContentType.JSON).header("Authorization", "DPoP t").body(body).post("/mcp").then()
                .statusCode(401)
                .header("WWW-Authenticate", containsString("customer channel is not enabled"))
        }
    }

    @Inject
    lateinit var recorder: RecordingAuditEventPublisher

    @Test
    fun `a flood of channel-off tools calls writes at most one audit row per minute window`() {
        CustomerTokenSource.claims = mapOf(
            "iss" to "https://kc.open-bank.tech/realms/openbank-customers",
            "sub" to "c0ffee00-0000-0000-0000-0000000000f2",
            "aud" to "openbank-mcp-service",
        )
        recorder.clear()
        repeat(10) {
            given().contentType(ContentType.JSON).header("Authorization", "DPoP t")
                .body(
                    """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"list_accounts","arguments":{}}}""",
                )
                .post("/mcp").then().statusCode(401)
        }
        // One per minute window; two only if the run straddles a boundary.
        assertThat(recorder.events.size).isBetween(1, 2)
    }

    @Inject
    lateinit var meters: io.micrometer.core.instrument.MeterRegistry

    @Test
    fun `one source's flood does not suppress another source's audit row, and suppressions are counted`() {
        fun flood(sub: String, times: Int) {
            CustomerTokenSource.claims = mapOf(
                "iss" to "https://kc.open-bank.tech/realms/openbank-customers",
                "sub" to sub,
                "aud" to "openbank-mcp-service",
            )
            repeat(times) {
                given().contentType(ContentType.JSON).header("Authorization", "DPoP t")
                    .body(
                        """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"list_accounts","arguments":{}}}""",
                    )
                    .post("/mcp").then().statusCode(401)
            }
        }
        val suppressedBefore = meters.find("openbank.mcp.audit_suppressed").counter()?.count() ?: 0.0
        recorder.clear()
        flood("c0ffee00-0000-0000-0000-0000000000a1", 10)
        val afterFirst = recorder.events.size
        flood("c0ffee00-0000-0000-0000-0000000000a2", 1)
        assertThat(recorder.events.size).isEqualTo(afterFirst + 1)
        val suppressed = (meters.find("openbank.mcp.audit_suppressed").counter()?.count() ?: 0.0) - suppressedBefore
        assertThat(suppressed).isGreaterThanOrEqualTo(8.0)
    }

    @Test
    fun `a tokenless DPoP flood is windowed by client address and every suppression is counted`() {
        // No claims at all: the subject is absent, so the window key is the client-address hash.
        CustomerTokenSource.claims = emptyMap()
        val before = meters.find("openbank.mcp.audit_suppressed").counter()?.count() ?: 0.0
        recorder.clear()
        repeat(10) {
            given().contentType(ContentType.JSON).header("Authorization", "DPoP not-a-token")
                .body("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"list_accounts","arguments":{}}}""")
                .post("/mcp").then().statusCode(401)
        }
        val rows = recorder.events.size
        assertThat(rows).isBetween(0, 2)
        val suppressed = (meters.find("openbank.mcp.audit_suppressed").counter()?.count() ?: 0.0) - before
        // Every refused call is either one audit row or one counted suppression.
        assertThat(suppressed + rows).isEqualTo(10.0)
    }

    @Test
    fun `control - with no token the anonymous path still answers 200`() {
        given().contentType(ContentType.JSON).body("""{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""")
            .post("/mcp").then().statusCode(200)
    }

    @Test
    fun `protected-resource metadata is 404 while the channel is off`() {
        given().get("/.well-known/oauth-protected-resource/mcp").then().statusCode(404)
        given().get("/.well-known/oauth-protected-resource").then().statusCode(404)
    }
}
