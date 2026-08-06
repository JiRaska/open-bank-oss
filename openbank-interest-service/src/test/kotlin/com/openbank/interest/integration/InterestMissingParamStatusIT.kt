// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.interest.integration

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * `POST /api/v1/interest/capitalize/{accountId}` with no `?productId=` must answer 400, not 500
 * (#3104 — this endpoint is the issue's own worked example, and the money-path half fixed by #3625
 * shipped without a test).
 *
 * MECHANISM. `capitalize` is a plain `fun`, not a `suspend fun`, so Kotlin emits
 * `Intrinsics.checkNotNullParameter` at bytecode offset 0 for the non-nullable `productId: String`.
 * JAX-RS binds an absent query parameter to null, the intrinsic throws NPE **before the first
 * statement of the body runs**, and `GenericExceptionMapper` renders 500. Two consequences that are
 * easy to get wrong: a `requireNotNull(productId)` written inside the body compiles to nothing (the
 * intrinsic has already thrown), so the parameter MUST be declared nullable for any guard to be
 * reachable; and the 5xx burns the service's SLO error budget for what is a client mistake.
 *
 * WHY ONLY THIS LAYER CAN SEE IT. A unit test that calls `capitalize(...)` directly supplies the
 * argument JAX-RS does not, so it passes against the broken signature — the defect lives entirely in
 * the binding step. Only a real HTTP request can omit the parameter.
 *
 * The second test is the control: with `productId` present the request gets past the binding and
 * fails (or succeeds) further downstream, so the first test's redness is attributable to the missing
 * parameter alone and not to an unrelated 400 on the route.
 *
 * `contentType` is not optional here and the omission is silent: the resource declares
 * `@Consumes(APPLICATION_JSON)`, so a request without a `Content-Type` is rejected with **415
 * before parameter binding runs at all**. The first draft of this test omitted it and reported 415
 * where the defect is 500 — a probe weaker than the client it is standing in for, which would have
 * gone green against the fix for the wrong reason.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.interest.it.PostgresRedisTestResource::class)
class InterestMissingParamStatusIT {

    @TestSecurity(user = "tester", roles = ["ROLE_OPERATOR", "ROLE_ADMIN"])
    @Test
    fun `capitalize with no productId answers 400, not 500`() {
        val response = RestAssured.given()
            .contentType("application/json")
            .post("/api/v1/interest/capitalize/$ACCOUNT_ID")

        assertThat(response.statusCode)
            .describedAs(
                "an absent required @QueryParam is a client error; 500 here is the #3104 defect " +
                    "(Intrinsics.checkNotNullParameter NPE at offset 0 of a non-suspend handler)",
            )
            .isEqualTo(400)
    }

    @TestSecurity(user = "tester", roles = ["ROLE_OPERATOR", "ROLE_ADMIN"])
    @Test
    fun `capitalize with productId present gets past parameter binding`() {
        val status = RestAssured.given()
            .contentType("application/json")
            .post("/api/v1/interest/capitalize/$ACCOUNT_ID?productId=SAVINGS_STANDARD")
            .statusCode

        assertThat(status)
            .describedAs("with productId supplied the request must get past the #3104 guard")
            .isNotEqualTo(400)
    }

    private companion object {
        val ACCOUNT_ID: UUID = UUID.fromString("11111111-2222-3333-4444-555555555555")
    }
}
