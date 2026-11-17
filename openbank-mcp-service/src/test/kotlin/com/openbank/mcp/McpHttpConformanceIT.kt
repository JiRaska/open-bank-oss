// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp

import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

/**
 * HTTP-level half of the MCP conformance layer (issue #16617, layer 1), over the REAL HTTP stack
 * and CDI graph. The unit half is `McpSpecConformanceTest`; this class owns what only the wire can
 * show: status codes, headers, malformed-JSON handling and the unauthenticated posture.
 *
 * Specification references are to revision 2026-07-28 (Streamable HTTP + Authorization pages).
 * `@Disabled` tests are KNOWN DEVIATIONS kept as executable expectations — see the PR body; they
 * are follow-ups, not passes. Production code is untouched by this change.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class, initArgs = [ResourceArg(name = "db", value = "openbank_mcp_it")])
class McpHttpConformanceIT {

    private fun post(body: String, contentType: String = "application/json"): Response =
        given().contentType(contentType).body(body).post("/mcp")

    // ---- enforced today ----------------------------------------------------------------------

    @Test
    fun `malformed JSON is a client error, never a 5xx, and leaks no internals`() {
        listOf("{", "{\"jsonrpc\":", "not json", "{\"a\":}", "", "\u0000", "{\"a\":\"\\u00zz\"}").forEach { body ->
            val resp = post(body)
            assertThat(resp.statusCode).`as`("body %s -> %s", body, resp.body.asString()).isBetween(400, 499)
            assertNoInternals(resp.body.asString())
        }
    }

    @Test
    fun `a non-JSON content type is refused with 4xx`() {
        val resp = post("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", contentType = "text/plain")
        assertThat(resp.statusCode).isBetween(400, 499)
    }

    @Test
    fun `GET on the MCP endpoint is Method Not Allowed`() {
        // 2026-07-28 removed the standalone GET stream; servers SHOULD answer 405.
        assertThat(given().get("/mcp").statusCode).isEqualTo(405)
    }

    @Test
    fun `an unauthenticated caller gets an empty tool list and a refused call, with HTTP 200 envelope`() {
        val list = post("""{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""")
        assertThat(list.statusCode).isEqualTo(200)
        assertThat(list.jsonPath().getList<Any>("result.tools")).isEmpty()

        val call = post(
            """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"list_accounts","arguments":{}}}""",
        )
        assertThat(call.statusCode).isEqualTo(200)
        assertThat(call.jsonPath().getBoolean("result.isError")).isTrue()
        assertThat(call.body.asString()).doesNotContain("\"test\":true")
    }

    @Test
    fun `a notification is accepted with no body`() {
        val resp = post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        assertThat(resp.statusCode).isIn(202, 204)
        assertThat(resp.body.asString()).isEmpty()
    }

    // ---- known deviations (disabled) -----------------------------------------------------------

    @Disabled(
        "Deviation H1 (#16617): unauthenticated POST /mcp answers 200; RFC 9728 / MCP authorization require 401 + WWW-Authenticate Bearer resource_metadata=...",
    )
    @Test
    fun `an unauthenticated request is challenged with 401 and a resource_metadata pointer`() {
        val resp = post("""{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""")
        assertThat(resp.statusCode).isEqualTo(401)
        assertThat(resp.header("WWW-Authenticate")).startsWith("Bearer").contains("resource_metadata=")
    }

    @Disabled("Deviation H2 (#16617): no RFC 9728 protected-resource metadata document is served")
    @Test
    fun `protected resource metadata is published at the well-known URI`() {
        val resp = given().get("/.well-known/oauth-protected-resource/mcp")
        assertThat(resp.statusCode).isEqualTo(200)
        assertThat(resp.jsonPath().getString("resource")).isNotBlank()
        assertThat(resp.jsonPath().getList<String>("authorization_servers")).isNotEmpty()
    }

    @Disabled(
        "Deviation H3 (#16617): a notification is answered 204; Streamable HTTP requires 202 Accepted with no body",
    )
    @Test
    fun `a notification is answered 202 Accepted`() {
        assertThat(post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""").statusCode).isEqualTo(202)
    }

    @Disabled(
        "Deviation H4 (#16617): malformed JSON answers a platform 400 body, not a JSON-RPC -32700 Parse error object",
    )
    @Test
    fun `malformed JSON is answered with a JSON-RPC parse error`() {
        val resp = post("{")
        assertThat(resp.statusCode).isEqualTo(400)
        assertThat(resp.jsonPath().getInt("error.code")).isEqualTo(-32700)
    }

    @Disabled(
        "Deviation H5 (#16617): Origin is not validated; a present, invalid Origin MUST be refused with 403 (DNS rebinding)",
    )
    @Test
    fun `an invalid Origin header is refused with 403`() {
        val resp = given().contentType(ContentType.JSON).header("Origin", "https://evil.example")
            .body("""{"jsonrpc":"2.0","id":1,"method":"ping"}""").post("/mcp")
        assertThat(resp.statusCode).isEqualTo(403)
    }

    @Disabled(
        "Deviation H6 (#16617): MCP-Protocol-Version is neither required nor validated; an unsupported value MUST be 400",
    )
    @Test
    fun `an unsupported MCP-Protocol-Version header is refused with 400`() {
        val resp = given().contentType(ContentType.JSON).header("MCP-Protocol-Version", "1999-01-01")
            .body("""{"jsonrpc":"2.0","id":1,"method":"ping"}""").post("/mcp")
        assertThat(resp.statusCode).isEqualTo(400)
    }

    @Disabled(
        "Deviation H7 (#16617): an unknown method answers HTTP 200 + -32601; revision 2026-07-28 binds it to HTTP 404 with the JSON-RPC error body",
    )
    @Test
    fun `an unknown method is HTTP 404 carrying -32601`() {
        val resp = post("""{"jsonrpc":"2.0","id":1,"method":"no/such/method"}""")
        assertThat(resp.statusCode).isEqualTo(404)
        assertThat(resp.jsonPath().getInt("error.code")).isEqualTo(-32601)
    }

    private fun assertNoInternals(text: String) {
        assertThat(text).doesNotContain("\tat ", "Exception", "com.openbank", "java.lang", "kotlin.", "jakarta.")
    }
}
