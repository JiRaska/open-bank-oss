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
