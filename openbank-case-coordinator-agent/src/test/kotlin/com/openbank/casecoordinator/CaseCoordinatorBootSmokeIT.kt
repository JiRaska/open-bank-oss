// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.casecoordinator

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml

/**
 * Boot smoke test (ADR-0244). Boots the full application against a real Postgres (Testcontainers):
 * Flyway runs V1, Hibernate validates the entity against the schema, the JDBC driver loads, and the
 * health endpoint reports UP. Catches the "released but never booted" defect class.
 *
 * Temporal is disabled in %test, so the workflow registrar no-ops; this test is purely the boot+DB path.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class CaseCoordinatorBootSmokeIT {

    @Test
    fun `application boots and reports ready against a live database`() {
        given()
            .`when`().get("/q/health/ready")
            .then()
            .statusCode(200)
            .body("status", equalTo("UP"))
    }

    @Test
    @TestSecurity(user = "test-viewer", roles = ["ROLE_VIEWER"])
    fun `status endpoint is reachable and returns service identity`() {
        given()
            .`when`().get("/api/v1/case-coordinator/status")
            .then()
            .statusCode(200)
            .body("service", equalTo("case-coordinator-agent"))
            .body("status", equalTo("up"))
    }

    @Test
    @TestSecurity(user = "test-operator", roles = ["ROLE_OPERATOR"])
    fun `invalid case request conforms to its published 400 schema`() {
        assertValidationErrorConforms("/api/v1/case-coordinator/cases")
    }

    @Test
    @TestSecurity(user = "test-operator", roles = ["ROLE_OPERATOR"])
    fun `invalid signal request conforms to its published 400 schema`() {
        assertValidationErrorConforms("/api/v1/case-coordinator/cases/missing/signals")
    }

    @Test
    @TestSecurity(user = "test-viewer", roles = ["ROLE_VIEWER"])
    fun `missing case retains its published custom 404 error body`() {
        val path = "/api/v1/case-coordinator/cases/missing"
        val body: Map<String, Any?> = given()
            .`when`().get(path)
            .then()
            .statusCode(404)
            .extract().jsonPath().getMap("")

        assertResponseSchema(path.replace("/missing", "/{caseId}"), "get", "404", "ErrorBody")
        assertThat(schemaViolations(body, "ErrorBody")).isEmpty()
        assertThat(body["error"]).isEqualTo("no case with id 'missing'")
    }

    @Test
    fun `custom error body cannot pass as the shared validation schema`() {
        assertThat(schemaViolations(mapOf("error" to "missing"), "ProblemDetail")).isNotEmpty()
    }

    @Suppress("UNCHECKED_CAST")
    private fun assertValidationErrorConforms(path: String) {
        val body: Map<String, Any?> = given()
            .contentType("application/json")
            .body("{}")
            .`when`().post(path)
            .then()
            .statusCode(400)
            .extract().jsonPath().getMap("")

        assertResponseSchema(path.replace(Regex("/missing(?=/signals)"), "/{caseId}"), "post", "400", "ProblemDetail")
        assertThat(schemaViolations(body, "ProblemDetail")).isEmpty()
        assertThat(body["status"]).isEqualTo(400)
        assertThat(body["code"]).isEqualTo("VALIDATION_ERROR")
        assertThat(body).doesNotContainKey("error")
    }

    @Suppress("UNCHECKED_CAST")
    private fun assertResponseSchema(path: String, method: String, status: String, schemaName: String) {
        val spec = loadSpec()
        val paths = spec["paths"] as Map<String, Map<String, Any?>>
        val operation = paths.getValue(path)[method] as Map<String, Any?>
        val responses = operation["responses"] as Map<String, Map<String, Any?>>
        val response = responses.getValue(status)
        val content = response["content"] as Map<String, Map<String, Any?>>
        val media = content.getValue("application/json")
        val ref = (media["schema"] as Map<String, String>).getValue("\$ref")
        assertThat(ref).isEqualTo("#/components/schemas/$schemaName")
    }

    @Suppress("UNCHECKED_CAST")
    private fun schemaViolations(body: Map<String, Any?>, schemaName: String): List<String> {
        val spec = loadSpec()
        val components = spec["components"] as Map<String, Any?>
        val schemas = components["schemas"] as Map<String, Map<String, Any?>>
        val schema = schemas.getValue(schemaName)
        val required = schema["required"] as List<String>
        val properties = schema["properties"] as Map<String, Map<String, Any?>>
        val missing = required.filterNot(body::containsKey)
        val unknown = if (schema["additionalProperties"] == false) body.keys - properties.keys else emptySet()
        return missing.map { "missing $it" } + unknown.map { "unknown $it" }
    }

    private fun loadSpec(): Map<String, Any?> = javaClass.classLoader.getResourceAsStream("openapi.yaml")!!.use {
        Yaml().load(it)
    }
}
