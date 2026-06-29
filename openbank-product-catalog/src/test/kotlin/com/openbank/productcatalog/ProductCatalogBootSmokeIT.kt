// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test

/**
 * Boot smoke test (ADR-0105 P1). Boots the full application against a real Postgres (Testcontainers):
 * Flyway runs V1, Hibernate validates ProductEntity against the schema, the reactive + JDBC drivers
 * load, the first-boot seeder persists the canonical catalogue, and health reports UP. Catches the
 * "released but never booted" defect class that unit tests cannot see.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class ProductCatalogBootSmokeIT {

    @Test
    fun `application boots and reports ready against a live database`() {
        given()
            .`when`().get("/q/health/ready")
            .then()
            .statusCode(200)
            .body("status", equalTo("UP"))
    }
}
