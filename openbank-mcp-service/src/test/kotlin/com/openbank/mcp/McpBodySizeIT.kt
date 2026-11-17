// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp

import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.junit.jupiter.api.Test

/** ADR-0383 D5: an oversize body is refused (HTTP 413) before it reaches the JSON-RPC handler. */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class, initArgs = [ResourceArg(name = "db", value = "openbank_mcp_it")])
class McpBodySizeIT {

    @Test
    fun `a body over the cap is rejected with 413`() {
        val padding = "x".repeat(128 * 1024)
        given()
            .contentType(ContentType.JSON)
            .body("""{"jsonrpc":"2.0","id":1,"method":"ping","params":{"pad":"$padding"}}""")
            .post("/mcp")
            .then()
            .statusCode(413)
    }

    @Test
    fun `a normal body under the cap is still served`() {
        given()
            .contentType(ContentType.JSON)
            .body("""{"jsonrpc":"2.0","id":1,"method":"ping","params":{}}""")
            .post("/mcp")
            .then()
            .statusCode(200)
    }
}
