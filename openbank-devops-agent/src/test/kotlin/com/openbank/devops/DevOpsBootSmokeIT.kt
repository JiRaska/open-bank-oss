// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.devops

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test

/**
 * Boot smoke test (ADR-0119). Boots the full application against a real Postgres (Testcontainers):
 * Flyway runs V1, Hibernate validates the entity against the schema, the JDBC driver loads, and the
 * health endpoint reports UP. Catches the "released but never booted" defect class (missing runtime
 * driver, duplicate config key, broken migration) that unit tests cannot see.
 *
 * Temporal is disabled in %test, so the worker registrar no-ops; this test is purely the boot+DB path.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class DevOpsBootSmokeIT {

    @Test
    fun `application boots and reports ready against a live database`() {
        given()
            .`when`().get("/q/health/ready")
            .then()
            .statusCode(200)
            .body("status", equalTo("UP"))
    }
}
