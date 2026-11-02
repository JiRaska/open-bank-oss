// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.copilot.contract

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml

/**
 * #12078: an unauthenticated request is rejected by libs-runtime before the resource runs, so its
 * 401 body is the shared ApiError, not the resource's `{error}`. Verify the served HTTP body
 * against the referenced response schema, not just its YAML text. Runs on the default profile and
 * without test-security so the PR stays spec+test+docs (ADR-0048 correction class).
 */
@QuarkusTest
class CopilotErrorBodySpecConformanceIT {
    private companion object {
        @Suppress("UNCHECKED_CAST")
        val SPEC: Map<String, Any?> by lazy {
            Thread.currentThread().contextClassLoader.getResourceAsStream("openapi.yaml")!!
                .use { Yaml().load<Map<String, Any?>>(it) }
        }
    }

    @Test
    fun `unauthenticated chat carries documented shared authentication body`() {
        val response = given().contentType(ContentType.JSON)
            .body(mapOf("message" to "hello"))
            .`when`().post("/api/v1/copilot/chat")
            .then().statusCode(401).extract().jsonPath().getMap<String, Any?>("")
        assertConforms(response, "/copilot/chat", 401)
        assertThat(response["code"]).isEqualTo("UNAUTHORIZED")
        assertThat(response).doesNotContainKey("error")
    }

    @Test
    fun `unauthenticated confirmation carries documented shared authentication body`() {
        val response = given().contentType(ContentType.JSON)
            .`when`().post("/api/v1/copilot/actions/not-a-uuid/confirm")
            .then().statusCode(401).extract().jsonPath().getMap<String, Any?>("")
        assertConforms(response, "/copilot/actions/{tokenId}/confirm", 401)
        assertThat(response["code"]).isEqualTo("UNAUTHORIZED")
        assertThat(response).doesNotContainKey("error")
    }

    @Test
    fun `the documented 401 schema rejects the shared envelope if only ErrorResponse were listed`() {
        val shared = mapOf(
            "traceId" to "t",
            "status" to 401,
            "code" to "UNAUTHORIZED",
            "message" to "m",
            "timestamp" to "2026-01-01T00:00:00Z",
        )
        val errorResponse = documentedSchemas("/copilot/chat", 501).single()
        assertThat(violations(shared, errorResponse)).contains("error is required")
        assertThat(documentedSchemas("/copilot/chat", 401).map { violations(shared, it) })
            .anySatisfy { assertThat(it).isEmpty() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun documentedSchemas(path: String, status: Int): List<Map<String, Any?>> {
        val paths = SPEC["paths"] as Map<String, Map<String, Any?>>
        val operation = paths.getValue(path)["post"] as Map<String, Any?>
        val responses = operation["responses"] as Map<String, Map<String, Any?>>
        val content = responses.getValue(status.toString())["content"] as Map<String, Map<String, Any?>>
        val schema = content.getValue("application/json")["schema"] as Map<String, Any?>
        val refs = (schema["oneOf"] as? List<Map<String, String>>)
            ?: listOf(schema as Map<String, String>)
        val schemas = (SPEC["components"] as Map<String, Any?>)["schemas"] as Map<String, Map<String, Any?>>
        return refs.map { schemas.getValue(it.getValue("\$ref").substringAfterLast('/')) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun assertConforms(body: Map<String, Any?>, path: String, status: Int) {
        val candidates = documentedSchemas(path, status)
        val failures = candidates.map { schema -> violations(body, schema) }
        assertThat(failures).describedAs("response %s against %s %s", body, path, status)
            .anySatisfy { assertThat(it).isEmpty() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun violations(body: Map<String, Any?>, schema: Map<String, Any?>): List<String> {
        val required = schema["required"] as List<String>
        val props = schema["properties"] as Map<String, Map<String, Any?>>
        val errors = required.filterNot(body::containsKey).map { "$it is required" }.toMutableList()
        if (schema["additionalProperties"] == false) {
            (body.keys - props.keys).forEach { errors += "$it is undeclared" }
        }
        body.forEach { (name, value) ->
            val type = props[name]?.get("type")
            if (type == "string" && value !is String) errors += "$name is not a string"
            if (type == "integer" && value !is Int && value !is Long) errors += "$name is not an integer"
            if (type == "boolean" && value !is Boolean) errors += "$name is not a boolean"
        }
        return errors
    }
}
