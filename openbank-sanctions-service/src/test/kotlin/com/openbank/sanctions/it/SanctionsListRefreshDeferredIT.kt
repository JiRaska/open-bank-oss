// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.it

import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * #14780: the single-list refresh is DEFERRED, the #9048 refresh-all mechanism. The trigger flags
 * the list (`refreshRequestedAt`) and answers at once; the scheduler (off under %test) runs the
 * import. The v1 operation used to import inside the request, and API fuzz timed out on
 * PEP_GLOBAL. Every assertion here goes through real HTTP and the real database.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class SanctionsListRefreshDeferredIT {

    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    fun `v2 single-list refresh answers 202 and only flags the list`() {
        val before = list("PEP_GLOBAL")
        val response = given().contentType("application/json")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .post("/api/v2/sanctions/lists/PEP_GLOBAL/refresh")
        assertThat(response.statusCode).isEqualTo(202)
        assertThat(response.jsonPath().getString("requested")).isEqualTo("PEP_GLOBAL")
        // Answered without the import: a deferred request is fast, and nothing was imported.
        assertThat(response.time).isLessThan(5_000)
        val after = list("PEP_GLOBAL")
        assertThat(after["refreshRequestedAt"]).isNotNull()
        assertThat(after["lastUpdatedAt"]).isEqualTo(before["lastUpdatedAt"])
        assertThat(after["lastEntryCount"]).isEqualTo(before["lastEntryCount"])
    }

    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    fun `v1 single-list refresh keeps the 200 list shape but defers the import`() {
        val before = list("UN_CONSOLIDATED")
        val response = given().contentType("application/json")
            .post("/api/v1/sanctions/lists/UN_CONSOLIDATED/refresh")
        assertThat(response.statusCode).isEqualTo(200)
        assertThat(response.jsonPath().getString("listType")).isEqualTo("UN_CONSOLIDATED")
        assertThat(response.jsonPath().getString("refreshRequestedAt")).isNotNull()
        assertThat(list("UN_CONSOLIDATED")["lastUpdatedAt"]).isEqualTo(before["lastUpdatedAt"])
    }

    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    fun `v2 refuses a missing Idempotency-Key and an unknown list`() {
        given().contentType("application/json").post("/api/v2/sanctions/lists/PEP_GLOBAL/refresh")
            .then().statusCode(400)
        given().contentType("application/json").header("Idempotency-Key", UUID.randomUUID().toString())
            .post("/api/v2/sanctions/lists/NO_SUCH_LIST/refresh")
            .then().statusCode(404)
    }

    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    fun `a disabled list is refused rather than accepted and never carried out`() {
        val hm = list("HM_TREASURY")
        val id = hm["id"] as String
        setEnabled(id, false)
        try {
            given().contentType("application/json").header("Idempotency-Key", UUID.randomUUID().toString())
                .post("/api/v2/sanctions/lists/HM_TREASURY/refresh")
                .then().statusCode(422)
            given().contentType("application/json").post("/api/v1/sanctions/lists/HM_TREASURY/refresh")
                .then().statusCode(422)
            assertThat(list("HM_TREASURY")["refreshRequestedAt"]).isNull()
        } finally {
            setEnabled(id, true)
        }
    }

    /** Contract half: the 202 body carries exactly what the committed openapi.yaml publishes. */
    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    fun `the 202 body matches the published v2 contract`() {
        @Suppress("UNCHECKED_CAST")
        val spec = Yaml().load<Map<String, Any?>>(Files.readString(Path.of("src/main/resources/openapi.yaml")))
        val paths = spec["paths"] as Map<String, Any?>
        val op = (paths["/api/v2/sanctions/lists/{listType}/refresh"] as Map<String, Any?>)["post"] as Map<String, Any?>
        val responses = op["responses"] as Map<String, Any?>
        val accepted = responses["202"] as Map<String, Any?>
        val schema = ((accepted["content"] as Map<String, Any?>)["application/json"] as Map<String, Any?>)["schema"]
            as Map<String, Any?>
        val published = (schema["properties"] as Map<String, Any?>).keys
        val required = schema["required"] as List<*>

        val body = given().contentType("application/json")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .post("/api/v2/sanctions/lists/OFAC_SDN/refresh")
            .then().statusCode(202).extract().jsonPath().getMap<String, Any?>("")
        assertThat(published).containsAll(body.keys)
        assertThat(body.keys).containsAll(required.map { it.toString() })
        assertThat(responses.keys).contains("400", "404", "422")
    }

    private fun list(listType: String): Map<String, Any?> =
        given().get("/api/v1/sanctions/lists").then().statusCode(200).extract().jsonPath()
            .getList<Map<String, Any?>>("").single { it["listType"] == listType }

    private fun setEnabled(id: String, enabled: Boolean) {
        given().contentType("application/json").body("""{"enabled":$enabled}""")
            .put("/api/v1/sanctions/lists/$id").then().statusCode(200)
    }
}
