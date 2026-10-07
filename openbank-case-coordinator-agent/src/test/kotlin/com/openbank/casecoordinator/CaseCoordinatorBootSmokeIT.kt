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

    @Suppress("UNCHECKED_CAST")
    private fun assertValidationErrorConforms(path: String) {
        val body: Map<String, Any?> = given()
            .contentType("application/json")
            .body("{}")
            .`when`().post(path)
            .then()
            .statusCode(400)
            .extract().jsonPath().getMap("")

        val spec = javaClass.classLoader.getResourceAsStream("openapi.yaml")!!.use {
            Yaml().load<Map<String, Any?>>(it)
        }
        val paths = spec["paths"] as Map<String, Map<String, Any?>>
        val operation = paths.getValue(path.replace(Regex("/missing(?=/signals)"), "/{caseId}"))["post"]
            as Map<String, Any?>
        val responses = operation["responses"] as Map<String, Map<String, Any?>>
        val response = responses.getValue("400")
        val content = response["content"] as Map<String, Map<String, Any?>>
        val media = content.getValue("application/json")
        val ref = (media["schema"] as Map<String, String>).getValue("\$ref")
        assertThat(ref).isEqualTo("#/components/schemas/ProblemDetail")
        val components = spec["components"] as Map<String, Any?>
        val schemas = components["schemas"] as Map<String, Map<String, Any?>>
        val schema = schemas.getValue(ref.substringAfterLast('/'))
        val required = schema["required"] as List<String>
        val properties = schema["properties"] as Map<String, Map<String, Any?>>
        assertThat(body.keys).containsAll(required).isSubsetOf(properties.keys)
        assertThat(schema["additionalProperties"]).isEqualTo(false)
        assertThat(body["status"]).isEqualTo(400)
        assertThat(body["code"]).isEqualTo("VALIDATION_ERROR")
        assertThat(body).doesNotContainKey("error")
    }
}
