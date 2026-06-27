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
import org.hamcrest.Matchers.hasItem
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Security + behaviour contract for the trigram account-search endpoint (PR #237, money-path).
 * `/api/v1/accounts/search` is an account-enumeration surface, so the access contract is asserted
 * end-to-end against the real Quarkus stack — not just the mocked use-case unit tests:
 *
 *  - **401** — an unauthenticated caller is rejected before any query runs.
 *  - **403** — an authenticated caller whose role is outside `{SERVICE, VIEWER, OPERATOR, ADMIN}`
 *    is rejected (here `ROLE_COMPLIANCE`), proving `@RolesAllowed` actually gates the route.
 *  - **200** — an allowed role can search, and a fragment of a freshly-opened account's number
 *    finds that account, proving the pg_trgm path returns real rows (not an empty stub).
 *
 * NOTE for the money-path approver: this proves the role *gate*, not the cross-party scope — a
 * `VIEWER` can currently match any party's account (threat-model S?/open item). That residual is a
 * deliberate design decision left to the second approver, not something this contract asserts away.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.account.it.PostgresRedpandaRedisTestResource::class)
class AccountSearchApiIT {

    private val partyId = UUID.fromString("00000000-1111-0000-0000-000000000001")
    private val productId = UUID.fromString("00000000-2222-0000-0000-000000000001")
    private val user = "00000000-0000-0000-0000-000000000099"

    @Test
    fun `GET search without authentication returns 401`() {
        Given {
            queryParam("q", "0800")
        } When {
            get("/api/v1/accounts/search")
        } Then {
            statusCode(401)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = [Roles.COMPLIANCE])
    fun `GET search with a role outside the allowed set returns 403`() {
        Given {
            queryParam("q", "0800")
        } When {
            get("/api/v1/accounts/search")
        } Then {
            statusCode(403)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = [Roles.OPERATOR])
    fun `GET search by account-number fragment returns the matching account`() {
        // Seed an account so there is a real row to match (same fixtures as AccountApiIT).
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
                  "legalName": "Test Customer"
                }
                """.trimIndent(),
            )
        } When {
            post("/api/v1/accounts")
        } Then {
            statusCode(201)
        }
        val accountNumber: String = seeded.extract().body().jsonPath().getString("accountNumber")

        // A trailing substring of the account number is a valid (>= MIN_FRAGMENT) trigram query.
        val fragment = accountNumber.takeLast(6)

        Given {
            queryParam("q", fragment)
            queryParam("limit", 20)
        } When {
            get("/api/v1/accounts/search")
        } Then {
            statusCode(200)
            body("data.accountNumber", hasItem(accountNumber))
        }
    }
}
