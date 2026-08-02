// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.integration

import com.openbank.agent.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.ws.rs.Path
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * The MCP surface is only real if RESTEasy actually registered it. `McpEndpointIdentityTest`
 * calls the class directly and therefore cannot tell a served route from an unserved one — it
 * passed for the entire time POST /mcp answered **404** on the deployed pod, because
 * `@Path("/mcp")` had bound to a top-level function that sat between the annotation and the
 * class. Everything else about the class was intact: it compiled, it was a CDI bean, its unit
 * tests were green, and its siblings (`/agent/chat`, `/api/v1/proposals`) served normally, so
 * the admin-ui MCP screen rendered "agent-service is not deployed" against a healthy service.
 *
 * These two cases are the missing layer: one asserts the annotation is on the class, the other
 * drives the route over HTTP through the booted app. Both fail against the pre-fix source.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class, restrictToAnnotatedClass = true)
class McpEndpointRoutingIT {

    @Test
    fun `McpEndpoint carries the JAX-RS Path annotation on the class itself`() {
        val path = com.openbank.agent.infrastructure.mcp.McpEndpoint::class.java.getAnnotation(Path::class.java)
        assertNotNull(path, "@Path is missing from McpEndpoint — the resource will not be registered")
        assertEquals("/mcp", path.value)
    }

    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    fun `POST mcp initialize is served, not 404`() {
        given()
            .contentType(ContentType.JSON)
            .header("X-Agent-Id", "ui-assistant")
            .header("X-Agent-Plane", "control")
            .body(
                """
                {"jsonrpc":"2.0","id":1,"method":"initialize",
                 "params":{"protocolVersion":"2024-11-05",
                           "clientInfo":{"name":"routing-it","version":"1.0.0"},
                           "capabilities":{}}}
                """.trimIndent(),
            )
            .`when`().post("/mcp")
            .then()
            // The assertion that matters is "the route exists". A policy or charter decision may
            // legitimately shape the body, but an unregistered resource can only ever be 404.
            .statusCode(not(equalTo(404)))
            .body("jsonrpc", equalTo("2.0"))
    }
}
