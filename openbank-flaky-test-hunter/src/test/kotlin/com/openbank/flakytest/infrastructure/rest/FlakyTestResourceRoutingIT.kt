// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.flakytest.infrastructure.rest

import com.openbank.flakytest.PostgresTestResource
import com.openbank.flakytest.application.port.incoming.OperatorWorkflowStatus
import com.openbank.flakytest.application.usecase.FlakyTestHunterService
import io.mockk.every
import io.mockk.mockk
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneOffset

@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class FlakyTestResourceRoutingIT {

    @Test
    @TestSecurity(user = "admin", roles = ["ROLE_ADMIN"])
    fun `operator status endpoint reads the exact bounded workflow handle`() {
        val workflowId = "flaky-test-hunter-check-operator_manual-${LocalDate.now(ZoneOffset.UTC)}"
        val status = mockk<FlakyTestHunterService>()
        every { status.getStatus(workflowId) } returns OperatorWorkflowStatus(workflowId, "RUNNING")
        QuarkusMock.installMockForType(status, FlakyTestHunterService::class.java)

        given()
            .`when`().get("/api/v1/flaky-test-hunter/check/workflows/{workflowId}", workflowId)
            .then()
            .statusCode(200)
            .body("workflowId", org.hamcrest.Matchers.equalTo(workflowId))
            .body("status", org.hamcrest.Matchers.equalTo("RUNNING"))
    }

    @Test
    @TestSecurity(user = "viewer", roles = ["ROLE_VIEWER"])
    fun `workflow status is restricted to admins`() {
        given()
            .`when`().get("/api/v1/flaky-test-hunter/check/workflows/{workflowId}", "arbitrary-workflow")
            .then().statusCode(403)
    }

    @Test
    @TestSecurity(user = "admin", roles = ["ROLE_ADMIN"])
    fun `workflow status rejects ids outside the operator workflow namespace`() {
        given()
            .`when`().get("/api/v1/flaky-test-hunter/check/workflows/{workflowId}", "arbitrary-workflow")
            .then().statusCode(400)
    }

    @Test
    @TestSecurity(user = "viewer", roles = ["ROLE_VIEWER"])
    fun `findings read is served from the reactive Vertx context`() {
        given()
            .`when`().get("/api/v1/flaky-test-hunter/findings")
            .then().statusCode(200)
    }

    @Test
    fun `async trigger is a served route that rejects an anonymous caller`() {
        given()
            .`when`().post("/api/v1/flaky-test-hunter/check/trigger-async")
            .then().statusCode(401)
    }

    @Test
    @TestSecurity(user = "viewer", roles = ["ROLE_VIEWER"])
    fun `async trigger rejects a viewer before workflow admission`() {
        given()
            .`when`().post("/api/v1/flaky-test-hunter/check/trigger-async")
            .then().statusCode(403)
    }

    @Test
    @TestSecurity(user = "admin", roles = ["ROLE_ADMIN"])
    fun `async trigger rejects an invalid idempotency key before workflow admission`() {
        given()
            .header("Idempotency-Key", "unbounded-operator-key")
            .`when`().post("/api/v1/flaky-test-hunter/check/trigger-async-idempotent")
            .then().statusCode(400)
    }

    @Test
    @TestSecurity(user = "admin", roles = ["ROLE_ADMIN"])
    fun `idempotent async trigger rejects a missing header before workflow admission`() {
        given()
            .`when`().post("/api/v1/flaky-test-hunter/check/trigger-async-idempotent")
            .then().statusCode(400)
    }
}
