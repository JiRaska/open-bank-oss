// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.copilot.contract

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml

/** Served auth failures run before the resource's hand-built error responses. */
@QuarkusTest
@Suppress("UNCHECKED_CAST")
class CopilotAuthErrorBodyIT {
    @Test
    fun `documented 401 includes shared auth body and resource error body`() {
        val spec = javaClass.classLoader.getResourceAsStream("openapi.yaml")!!.use {
            Yaml().load<Map<String, Any>>(it)
        }
        val paths = spec["paths"] as Map<String, Map<String, Any>>
        for (path in listOf("/copilot/chat", "/copilot/actions/{tokenId}/confirm")) {
            val operation = paths.getValue(path)["post"] as Map<String, Any>
            val responses = operation["responses"] as Map<String, Map<String, Any>>
            val content = responses.getValue("401")["content"] as Map<String, Map<String, Any>>
            val schema = content.getValue("application/json")["schema"] as Map<String, List<Map<String, String>>>
            org.junit.jupiter.api.Assertions.assertEquals(
                listOf("#/components/schemas/ProblemDetail", "#/components/schemas/ErrorResponse"),
                schema.getValue("oneOf").map { it.getValue("\$ref") },
            )
        }
        val components = spec["components"] as Map<String, Any>
        val schemas = components["schemas"] as Map<String, Map<String, Any>>
        val problem = schemas.getValue("ProblemDetail")
        org.junit.jupiter.api.Assertions.assertEquals(
            listOf("status", "code", "message", "traceId", "timestamp"),
            problem["required"],
        )
        val excluded = problem["not"] as Map<String, Any>
        org.junit.jupiter.api.Assertions.assertEquals(listOf("error"), excluded["required"])
    }

    @Test
    fun `chat without bearer uses shared API error body`() {
        given().contentType(ContentType.JSON).body("{}")
            .post("/api/v1/copilot/chat")
            .then().statusCode(401)
            .body("status", equalTo(401))
            .body("code", notNullValue())
            .body("message", notNullValue())
            .body("traceId", notNullValue())
            .body("timestamp", notNullValue())
            .body("error", nullValue())
    }

    @Test
    fun `confirm without bearer uses shared API error body`() {
        given().contentType(ContentType.JSON).body("{}")
            .post("/api/v1/copilot/actions/00000000-0000-0000-0000-000000000001/confirm")
            .then().statusCode(401)
            .body("status", equalTo(401))
            .body("code", notNullValue())
            .body("message", notNullValue())
            .body("traceId", notNullValue())
            .body("timestamp", notNullValue())
            .body("error", nullValue())
    }
}
