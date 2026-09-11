// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.integration

import com.openbank.notification.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * #8993/#9009: without a `@Provider` opt-in for the shared `UnauthorizedExceptionMapper`,
 * Quarkus's built-in security handling renders an anonymous `@RolesAllowed` rejection as the
 * plain-text body `Not Authenticated` under `application/json` — a JSON-parsing client throws on
 * exactly the response it is most likely to receive. This drives a REAL anonymous HTTP request
 * (not a direct call into the mapper) against `DELETE /api/v1/devices/{id}`, which needs no
 * external dependency beyond Postgres (no Redis, unlike the approvals endpoints) since the
 * `@RolesAllowed` check runs before the handler ever touches the repository.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class NotificationUnauthorizedExceptionMapperIT {

    @Test
    fun `an anonymous DELETE on a role-gated endpoint gets the JSON UNAUTHORIZED envelope, not plain text`() {
        given()
            .`when`()
            .delete("/api/v1/devices/${UUID.randomUUID()}")
            .then()
            .statusCode(401)
            .contentType("application/json")
            .body("code", equalTo("UNAUTHORIZED"))
            .body("status", equalTo(401))
            .body("traceId", notNullValue())
    }
}
