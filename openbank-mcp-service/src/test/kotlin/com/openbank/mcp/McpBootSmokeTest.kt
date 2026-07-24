// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test

/**
 * BOOT smoke test — the real Quarkus CDI container starts (unlike the plain-unit tests), so
 * boot-time defects a never-deployed service accumulates (ConfigProperty resolution, the
 * AuthzProducer, OIDC wiring) surface HERE, not as a crashloop on first deploy. Also the honest
 * local e2e: a live JSON-RPC `initialize` over the real HTTP stack. `tools/call` is not exercised
 * here — it fails closed without an OPA sidecar, which is the intended posture.
 */
@QuarkusTest
class McpBootSmokeTest {

    @Test
    fun `the app boots and answers a live initialize over HTTP`() {
        given()
            .contentType(ContentType.JSON)
            .body("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""")
            .post("/mcp")
            .then()
            .statusCode(200)
            .body("result.serverInfo.name", equalTo("openbank-mcp"))
    }

    @Test
    fun `tools list is served over HTTP`() {
        given()
            .contentType(ContentType.JSON)
            .body("""{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""")
            .post("/mcp")
            .then()
            .statusCode(200)
            .body("result.tools.size()", equalTo(5))
    }
}
