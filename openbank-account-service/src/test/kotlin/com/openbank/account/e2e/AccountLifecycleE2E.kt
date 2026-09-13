// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.e2e

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured
import io.restassured.response.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * End-to-end journey for an account's whole life: **open it, find it in the party's account list,
 * freeze it, unfreeze it, close it — and after every transition, read the account back through the
 * API and check the state the customer would actually see.**
 *
 * Driven entirely through the real HTTP surface (`@QuarkusTest` + RestAssured + `@TestSecurity`)
 * against a real Postgres, Redpanda and Valkey (`PostgresRedpandaRedisTestResource`, per-job
 * Testcontainers). No use case or repository is called directly and nothing is mocked.
 *
 * ### Service boundaries — what is NOT claimed here
 *
 * Balance is owned by balance-service (N3 / ADR-0024) and the account-service balance endpoint
 * delegates over REST, so this journey never asserts a balance: that hop is not stubbed, it is
 * simply not travelled. Likewise the account-opened event's DISPATCH to a broker is out of scope —
 * the outbox row is this service's own durable state, but no consumer runs in this process, so the
 * journey stops at what this service owns.
 *
 * ### Why the read-back matters
 *
 * The transition endpoints return the new state in their own response body, which is the same code
 * path that just computed it. Reading the account back with a separate `GET` after each transition
 * is what proves the change was persisted rather than merely rendered — the failure mode of an
 * aggregate with an application-assigned `@Id` written with `persist` instead of `merge` is
 * precisely that the response looks right and no row changed.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.account.it.PostgresRedpandaRedisTestResource::class)
class AccountLifecycleE2E {

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_OPERATOR"])
    fun `an account is opened, discoverable, frozen, unfrozen and closed, with every state read back`() {
        val partyId = UUID.randomUUID()

        // 1. Open.
        val opened = RestAssured.given()
            .contentType("application/json")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .body(openRequest(partyId))
            .post("/api/v1/accounts")
        assertThat(opened.statusCode)
            .describedAs("POST /api/v1/accounts: %s", opened.body.asString())
            .isEqualTo(201)
        val accountId = opened.jsonPath().getString("id")
        assertThat(accountId).isNotNull()
        assertThat(opened.jsonPath().getString("status")).isEqualTo("ACTIVE")

        // 2. Read back — the account exists with the attributes it was opened with.
        assertThat(statusOf(accountId)).isEqualTo("ACTIVE")
        val detail = RestAssured.given().get("/api/v1/accounts/$accountId")
        assertThat(detail.jsonPath().getString("accountType")).isEqualTo("CURRENT")
        assertThat(detail.jsonPath().getString("currencyCode")).isEqualTo("CZK")

        // 3. Discoverable through the party's list — the query path, not the by-id path.
        val list = RestAssured.given().queryParam("partyId", partyId.toString()).get("/api/v1/accounts")
        assertThat(list.statusCode).isEqualTo(200)
        assertThat(list.jsonPath().getList<String>("data.id"))
            .describedAs("the newly opened account must appear in its own party's list")
            .contains(accountId)

        // 4. Freeze, and read back.
        assertThat(transition(accountId, "freeze", "Suspicious activity")).isEqualTo(200)
        assertThat(statusOf(accountId))
            .describedAs("a frozen account must READ BACK as FROZEN, not merely answer FROZEN")
            .isEqualTo("FROZEN")

        // 5. Unfreeze, and read back.
        assertThat(transition(accountId, "unfreeze", "Review completed")).isEqualTo(200)
        assertThat(statusOf(accountId)).isEqualTo("ACTIVE")

        // 6. Close, and read back. A closed account stays readable — closure is a state, not a
        //    deletion; an audit trail that disappears on close is not an audit trail.
        assertThat(transition(accountId, "close", "Customer request")).isEqualTo(200)
        assertThat(statusOf(accountId)).isEqualTo("CLOSED")
    }

    /**
     * The journey's ownership boundary, end to end: the same account, read with a customer-scoped
     * identity header, is visible to its owner and absent for anyone else — 404 rather than 403, so
     * the endpoint is not an existence oracle (IDOR defense-in-depth, A1).
     */
    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_OPERATOR"])
    fun `a customer-scoped read sees only its own account`() {
        val partyId = UUID.randomUUID()
        val opened = RestAssured.given()
            .contentType("application/json")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .body(openRequest(partyId))
            .post("/api/v1/accounts")
        assertThat(opened.statusCode).isEqualTo(201)
        val accountId = opened.jsonPath().getString("id")

        assertThat(
            RestAssured.given()
                .header("X-Customer-Party-Id", partyId.toString())
                .get("/api/v1/accounts/$accountId").statusCode,
        ).isEqualTo(200)

        assertThat(
            RestAssured.given()
                .header("X-Customer-Party-Id", UUID.randomUUID().toString())
                .get("/api/v1/accounts/$accountId").statusCode,
        ).isEqualTo(404)
    }

    /**
     * Known-negative control for the observable the journey leans on: `statusOf` must be able to
     * fail. An account id that was never opened has no status to read at all.
     */
    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_OPERATOR"])
    fun `the status observable answers 404 for an account that was never opened`() {
        val response: Response = RestAssured.given().get("/api/v1/accounts/${UUID.randomUUID()}")
        assertThat(response.statusCode).isEqualTo(404)
    }

    private fun transition(accountId: String, action: String, reason: String): Int = RestAssured.given()
        .contentType("application/json")
        .body("""{"reason": "$reason"}""")
        .post("/api/v1/accounts/$accountId/$action")
        .statusCode

    private fun statusOf(accountId: String): String {
        val response = RestAssured.given().get("/api/v1/accounts/$accountId")
        assertThat(response.statusCode)
            .describedAs("GET /api/v1/accounts/%s: %s", accountId, response.body.asString())
            .isEqualTo(200)
        return response.jsonPath().getString("status")
    }

    private fun openRequest(partyId: UUID): String =
        """
        {
          "partyId": "$partyId",
          "productId": "$PRODUCT_ID",
          "accountType": "CURRENT",
          "currencyCode": "CZK",
          "legalName": "E2E Journey Customer"
        }
        """.trimIndent()

    private companion object {
        const val ACTOR = "00000000-0000-0000-0000-000000000099"

        /** Seeded CZK current-account product (see AccountApiIT). */
        const val PRODUCT_ID = "00000000-2222-0000-0000-000000000001"
    }
}
