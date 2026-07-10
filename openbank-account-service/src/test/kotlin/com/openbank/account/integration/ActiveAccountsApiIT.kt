// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.account.integration

import com.openbank.libs.security.Roles
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.hamcrest.Matchers.everyItem
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.`is`
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Security + behaviour contract for the fleet-wide active-account listing (ADR-0143: the
 * "list every billable account" read billing-service's cycle scheduler discovers its batch
 * from). Like `/search`, `/api/v1/accounts/active` is an account-enumeration surface, so the
 * access contract is asserted end-to-end against the real Quarkus stack:
 *
 *  - **401** — an unauthenticated caller is rejected before any query runs.
 *  - **403** — an authenticated caller whose role is outside `{SERVICE, VIEWER, OPERATOR, ADMIN}`
 *    is rejected (here `ROLE_COMPLIANCE`), proving `@RolesAllowed` actually gates the route.
 *  - **200** — an allowed role can sweep; a freshly-opened (ACTIVE) account appears, and every
 *    returned row is ACTIVE — the sweep never leaks CLOSED/FROZEN accounts to a billing run.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.account.it.PostgresRedpandaRedisTestResource::class)
class ActiveAccountsApiIT {

    private val partyId = UUID.fromString("00000000-1111-0000-0000-000000000002")
    private val productId = UUID.fromString("00000000-2222-0000-0000-000000000001")

    @Test
    fun `GET active without authentication returns 401`() {
        When {
            get("/api/v1/accounts/active")
        } Then {
            statusCode(401)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = [Roles.COMPLIANCE])
    fun `GET active with a role outside the allowed set returns 403`() {
        When {
            get("/api/v1/accounts/active")
        } Then {
            statusCode(403)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = [Roles.OPERATOR])
    fun `GET active returns the freshly opened account and only ACTIVE rows`() {
        // Seed an account so there is a real ACTIVE row to find (same fixtures as AccountApiIT).
        val seeded = Given {
            contentType("application/json")
            header("Idempotency-Key", UUID.randomUUID().toString())
            body(
                """
                {
                  "partyId": "$partyId",
                  "productId": "$productId",
                  "accountType": "CURRENT",
                  "currencyCode": "CZK",
                  "legalName": "Billing Sweep Customer"
                }
                """.trimIndent(),
            )
        } When {
            post("/api/v1/accounts")
        } Then {
            statusCode(201)
        }
        val accountId: String = seeded.extract().body().jsonPath().getString("id")

        Given {
            queryParam("limit", 200)
        } When {
            get("/api/v1/accounts/active")
        } Then {
            statusCode(200)
            body("data.id", hasItem(accountId))
            body("data.status", everyItem(`is`("ACTIVE")))
        }
    }
}
