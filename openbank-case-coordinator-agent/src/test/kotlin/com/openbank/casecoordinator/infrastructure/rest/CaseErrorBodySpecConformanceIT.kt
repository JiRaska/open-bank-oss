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

/** Checks the published schemas against both a libs-runtime 400 and a resource-owned error. */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class CaseErrorBodySpecConformanceIT {
    private companion object {
        const val CASES = "/api/v1/case-coordinator/cases"

        @Suppress("UNCHECKED_CAST")
        val SPEC: Map<String, Any?> by lazy {
            Thread.currentThread().contextClassLoader.getResourceAsStream("openapi.yaml")!!.use {
                Yaml().load<Map<String, Any?>>(it)
            }
        }

        @Suppress("UNCHECKED_CAST")
        val SCHEMAS: Map<String, Map<String, Any?>> by lazy {
            ((SPEC["components"] as Map<String, Any?>)["schemas"] as Map<String, Map<String, Any?>>)
        }
    }

    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    fun `case-open validation is documented as ProblemDetail`() {
        val body = given().contentType(ContentType.JSON).body("{}")
            .`when`().post(CASES)
            .then().statusCode(400).extract().jsonPath().getMap<String, Any?>("")
        assertResponse(body, CASES, "post", "400", "ProblemDetail")
        assertThat(body["code"]).isEqualTo("VALIDATION_ERROR")
    }

    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    fun `signal validation is documented as ProblemDetail`() {
        val path = "$CASES/example/signals"
        val body = given().contentType(ContentType.JSON).body("{}")
            .`when`().post(path)
            .then().statusCode(400).extract().jsonPath().getMap<String, Any?>("")
        assertResponse(body, "$CASES/{caseId}/signals", "post", "400", "ProblemDetail")
    }

    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    fun `case-open resource denial retains ErrorBody`() {
        val body = given().contentType(ContentType.JSON)
            .body(
                mapOf(
                    "caseClass" to "INCIDENT_RESPONSE",
                    "subjectRef" to "subject",
                    "openedBy" to "unbound-agent",
                    "dispositionTarget" to "target",
                ),
            )
            .`when`().post(CASES)
            .then().statusCode(403).extract().jsonPath().getMap<String, Any?>("")
        assertResponse(body, CASES, "post", "403", "ErrorBody")
    }

    @Test
    fun `the schemas reject the opposite response shape`() {
        assertThat(violations(mapOf("error" to "invalid"), "ProblemDetail")).isNotEmpty()
        assertThat(violations(mapOf("code" to "VALIDATION_ERROR"), "ErrorBody")).isNotEmpty()
    }

    @Suppress("UNCHECKED_CAST")
    private fun assertResponse(body: Map<String, Any?>, path: String, method: String, status: String, schema: String) {
        val paths = SPEC["paths"] as Map<String, Any?>
        val operation = (paths.getValue(path) as Map<String, Any?>)[method] as Map<String, Any?>
        val response = (operation["responses"] as Map<String, Any?>).getValue(status) as Map<String, Any?>
        val content = (response["content"] as Map<String, Any?>).getValue("application/json") as Map<String, Any?>
        val ref = ((content["schema"] as Map<String, Any?>)["\$ref"] as String).substringAfterLast('/')
        assertThat(ref).isEqualTo(schema)
        assertThat(violations(body, ref)).describedAs("%s %s body %s", method, path, body).isEmpty()
    }

    @Suppress("UNCHECKED_CAST")
    private fun violations(body: Map<String, Any?>, schemaName: String): List<String> {
        val schema = SCHEMAS.getValue(schemaName)
        val properties = schema["properties"] as Map<String, Map<String, Any?>>
        val missing = (schema["required"] as? List<String> ?: emptyList()).filterNot(body::containsKey)
        val unknown = if (schema["additionalProperties"] == false) body.keys - properties.keys else emptySet()
        val wrongTypes = body.mapNotNull { (name, value) ->
            val type = properties[name]?.get("type")
            val valid = when (type) {
                null -> true
                "string" -> value is String
                "integer" -> value is Int || value is Long
                "boolean" -> value is Boolean
                "array" -> value is List<*>
                else -> false
            }
            if (valid) null else name
        }
        return missing.map { "missing $it" } + unknown.map { "unknown $it" } + wrongTypes.map { "wrong type $it" }
    }
}
