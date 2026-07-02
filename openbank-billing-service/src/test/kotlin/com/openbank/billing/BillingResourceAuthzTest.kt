// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.junit.jupiter.api.Test

/**
 * Regression coverage for the `@Authorize(action = "billing.read", ...)` addition
 * (ADR-0034 D5). `authz.enforce` defaults to `false` (advisory) and no OPA sidecar
 * is deployed in the test profile, so these assert the interceptor is a correct
 * no-op in that state — not that a real policy decision is enforced (that is the
 * shared `AuthorizeInterceptor`'s own test suite in openbank-libs-runtime).
 */
@QuarkusTest
class BillingResourceAuthzTest {

    @Test
    fun `unauthenticated request is rejected before authorization ever runs`() {
        Given { this } When {
            post("/api/v1/fees/assess?cycleId=c1&accountId=acc1&currency=CZK")
        } Then {
            statusCode(401)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `authenticated request with missing params still reaches validation, not blocked by authz`() {
        // accountId omitted -> the @Authorize resource extraction resolves to null (handled
        // gracefully, does not throw); the method body's own validation then returns 400 —
        // proves the interceptor doesn't interfere with existing request validation.
        Given { this } When {
            post("/api/v1/fees/assess?cycleId=c1&currency=CZK")
        } Then {
            statusCode(400)
        }
    }
}
