// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.integration

import com.openbank.engagement.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test

/**
 * Boots the service against a real Postgres and verifies Flyway ran + the service is healthy.
 * The "released but never booted" defect class: CDI wiring errors and broken migrations are
 * invisible to unit tests and surface only here.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class EngagementBootSmokeIT {

    @Test
    fun `service boots and reports ready against a live database`() {
        given()
            .`when`().get("/q/health/ready")
            .then()
            .statusCode(200)
            .body("status", equalTo("UP"))
    }
}
