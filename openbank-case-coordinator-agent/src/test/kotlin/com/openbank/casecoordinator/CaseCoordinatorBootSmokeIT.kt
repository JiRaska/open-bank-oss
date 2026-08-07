// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.casecoordinator

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test

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
}
