// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing

import com.openbank.billing.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Regression coverage for `@Authorize(action = "billing.read"/"billing.post", ...)` (ADR-0034 D5 /
 * ADR-0143 phase 2c-ii). `authz.enforce` defaults to `false` (advisory) and no OPA sidecar is
 * deployed in the test profile, so these assert the interceptor is a correct no-op in that state
 * — not that a real policy decision is enforced (that is the shared `AuthorizeInterceptor`'s own
 * test suite in openbank-libs-runtime). Needs real Postgres/Redis now that `BillingResource`
 * depends on the persisted `BillingCycleService` (ADR-0143 phase 2c).
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
class BillingResourceAuthzTest {

    @Test
    fun `unauthenticated request to assess is rejected before authorization ever runs`() {
        Given { this } When {
            post("/api/v1/fees/assess?cycleId=c1&accountId=acc1&currency=CZK")
        } Then {
            statusCode(401)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `authenticated request to assess with missing params still reaches validation, not blocked by authz`() {
        // accountId omitted -> the @Authorize resource extraction resolves to null (handled
        // gracefully, does not throw); the method body's own validation then returns 400 —
        // proves the interceptor doesn't interfere with existing request validation.
        Given { this } When {
            post("/api/v1/fees/assess?cycleId=c1&currency=CZK")
        } Then {
            statusCode(400)
        }
    }

    @Test
    fun `unauthenticated request to post is rejected before authorization ever runs`() {
        Given { this } When {
            post("/api/v1/fees/post?cycleId=c1&accountId=acc1&currency=CZK")
        } Then {
            statusCode(401)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_VIEWER"])
    fun `an authenticated caller without ROLE_OPERATOR or ROLE_ADMIN is forbidden from posting`() {
        Given { this } When {
            post("/api/v1/fees/post?cycleId=c1&accountId=acc1&currency=CZK")
        } Then {
            statusCode(403)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `an operator posting with missing params reaches validation, not blocked by authz`() {
        Given { this } When {
            post("/api/v1/fees/post?cycleId=c1&currency=CZK")
        } Then {
            statusCode(400)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `an operator posting for an unresolvable account gets back a skipped assessment, not an error`() {
        // No account-service running in this test profile, so the account context fails closed
        // (skipped=true) rather than erroring — the endpoint itself must still respond 200 with
        // the skip flag, matching the fail-closed contract (ADR-0143 D5).
        val body = (
            Given { this } When {
                post("/api/v1/fees/post?cycleId=it-authz-cycle&accountId=no-such-account&currency=CZK")
            } Then {
                statusCode(200)
            }
            ).extract().body().asString()
        assertThat(body).contains("\"skipped\":true")
    }
}
