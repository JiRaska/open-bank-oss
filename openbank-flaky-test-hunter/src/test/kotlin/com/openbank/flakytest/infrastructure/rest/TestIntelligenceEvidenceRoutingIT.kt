// SPDX-License-Identifier: AGPL-3.0-only
package com.openbank.flakytest.infrastructure.rest

import com.openbank.flakytest.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.empty
import org.junit.jupiter.api.Test

@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class TestIntelligenceEvidenceRoutingIT {
    @Test
    @TestSecurity(user = "admin", roles = ["ROLE_ADMIN"])
    fun `served analysis route accepts the bounded contract for an admin`() {
        given()
            .contentType("application/json")
            .body("""{"snapshotId":"run-42","collectedAt":"2026-08-22T12:00:00Z","components":[]}""")
            .post("/api/v1/flaky-test-hunter/evidence/analyze")
            .then()
            .statusCode(200)
            .body("$", empty<Any>())
    }

    @Test
    @TestSecurity(user = "viewer", roles = ["ROLE_VIEWER"])
    fun `viewer cannot spend the agent budget`() {
        given()
            .contentType("application/json")
            .body("""{"snapshotId":"run-42","collectedAt":"2026-08-22T12:00:00Z","components":[]}""")
            .post("/api/v1/flaky-test-hunter/evidence/analyze")
            .then()
            .statusCode(403)
    }
}
