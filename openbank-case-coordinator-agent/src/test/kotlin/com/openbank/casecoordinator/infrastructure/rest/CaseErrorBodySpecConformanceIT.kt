// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.casecoordinator.infrastructure.rest

import com.openbank.casecoordinator.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.util.UUID

/** #12078: the declared schema must match the body served over HTTP, including mapper errors. */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class CaseErrorBodySpecConformanceIT {
    private companion object {
        @Suppress("UNCHECKED_CAST")
        val SPEC: Map<String, Any?> by lazy {
            Thread.currentThread().contextClassLoader.getResourceAsStream("openapi.yaml")!!
                .use { Yaml().load<Map<String, Any?>>(it) }
        }
    }

    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    fun `open case validation uses ProblemDetail while a hand-built 404 uses ErrorBody`() {
        val badRequest = given().contentType(ContentType.JSON).body("{}")
            .`when`().post("/api/v1/case-coordinator/cases")
            .then().statusCode(400).extract().jsonPath().getMap<String, Any?>("")
        assertResponseMatchesSpec(badRequest, "/api/v1/case-coordinator/cases", "post", "400", "ProblemDetail")
        assertThat(badRequest).doesNotContainKey("error")

        val missingCase = given()
            .`when`().get("/api/v1/case-coordinator/cases/${UUID.randomUUID()}")
            .then().statusCode(404).extract().jsonPath().getMap<String, Any?>("")
        assertResponseMatchesSpec(missingCase, "/api/v1/case-coordinator/cases/{caseId}", "get", "404", "ErrorBody")
        assertThat(missingCase).containsKey("error")
    }

    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    fun `signal validation uses ProblemDetail`() {
        val body = given().contentType(ContentType.JSON).body("{}")
            .`when`().post("/api/v1/case-coordinator/cases/unknown/signals")
            .then().statusCode(400).extract().jsonPath().getMap<String, Any?>("")
        assertResponseMatchesSpec(
            body,
            "/api/v1/case-coordinator/cases/{caseId}/signals",
            "post",
            "400",
            "ProblemDetail",
        )
        assertThat(body).doesNotContainKey("error")
    }

    @Test
    fun `schema validator rejects the former documented shape`() {
        assertThat(violations(mapOf("error" to "invalid"), "ProblemDetail")).isNotEmpty()
    }

    @Suppress("UNCHECKED_CAST")
    private fun assertResponseMatchesSpec(
        body: Map<String, Any?>,
        path: String,
        method: String,
        status: String,
        expectedSchema: String,
    ) {
        val paths = SPEC.getValue("paths") as Map<String, Map<String, Any?>>
        val operation = paths.getValue(path).getValue(method) as Map<String, Any?>
        val responses = operation.getValue("responses") as Map<String, Map<String, Any?>>
        val response = responses.getValue(status)
        val content = response.getValue("content") as Map<String, Map<String, Any?>>
        val json = content.getValue("application/json")
        val schema = json.getValue("schema") as Map<String, String>
        assertThat(schema.getValue("\$ref")).isEqualTo("#/components/schemas/$expectedSchema")
        assertThat(violations(body, expectedSchema)).describedAs("actual $status body: $body").isEmpty()
    }

    @Suppress("UNCHECKED_CAST")
    private fun violations(body: Map<String, Any?>, schemaName: String): List<String> {
        val components = SPEC.getValue("components") as Map<String, Any?>
        val schemas = components.getValue("schemas") as Map<String, Map<String, Any?>>
        val schema = schemas.getValue(schemaName)
        val properties = schema.getValue("properties") as Map<String, Map<String, Any?>>
        val failures = mutableListOf<String>()
        (schema["required"] as List<String>).filterNot(body::containsKey)
            .forEach { failures += "$it is required" }
        if (schema["additionalProperties"] == false) {
            (body.keys - properties.keys).forEach { failures += "$it is undeclared" }
        }
        body.forEach { (key, value) ->
            when (properties[key]?.get("type")) {
                "string" -> if (value !is String) failures += "$key is not a string"
                "integer" -> if (value !is Int && value !is Long) failures += "$key is not an integer"
                "boolean" -> if (value !is Boolean) failures += "$key is not a boolean"
                "array" -> if (value !is List<*>) failures += "$key is not an array"
            }
        }
        return failures
    }
}
