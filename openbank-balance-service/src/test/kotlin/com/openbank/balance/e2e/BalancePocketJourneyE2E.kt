// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.e2e

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured
import io.restassured.response.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

/**
 * End-to-end journey for a customer's money as the bank actually moves it: **open a CZK pocket,
 * credit it, put a card authorization hold on it, watch available diverge from booked, release the
 * hold, and end back at a consistent position** — with the booked / available / reserved triple
 * read back from the API after every single step.
 *
 * Driven through the real HTTP surface (`@QuarkusTest` + RestAssured + `@TestSecurity`) against a
 * real Postgres (Flyway applied) and Redpanda via `PostgresRedpandaTestResource`. Nothing is
 * mocked and no use case is called directly.
 *
 * ### Service boundaries
 *
 * None is crossed. The ledger REST client in this service is used only by reconciliation, which
 * this journey does not exercise, and the reconciliation scheduler is disabled under `%test`. The
 * balance-changed event's dispatch to a broker is out of scope: no consumer runs in this process,
 * so the journey stops at this service's own durable state and does not claim the hop happened.
 *
 * ### Why the triple, and not the status code
 *
 * `bookedAmount`, `availableAmount` and `reservedAmount` are three numbers that must stay in a
 * fixed relation (`available = booked + arranged overdraft - reserved`). A hold that decremented
 * BOOKED instead of AVAILABLE — the classic defect — returns exactly the same 201, and only the
 * read-back triple can tell the two apart. Every assertion below is on the triple after the fact,
 * never on the write's own response alone.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.balance.it.PostgresRedpandaTestResource::class)
class BalancePocketJourneyE2E {

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_API"])
    fun `a pocket is opened, credited, held against, and released, staying consistent throughout`() {
        val accountId = UUID.randomUUID()

        // 1. Open the CZK pocket with an opening balance.
        val initialized = RestAssured.given()
            .contentType("application/json")
            .body("""{"currency": "CZK", "initialAmount": "1000.00"}""")
            .post("/api/v1/balances/$accountId/initialize")
        assertThat(initialized.statusCode)
            .describedAs("POST initialize: %s", initialized.body.asString())
            .isEqualTo(201)
        assertPocket(accountId, booked = "1000.00", available = "1000.00", reserved = "0")

        // 2. Money arrives. Booked and available both rise.
        val credited = RestAssured.given()
            .contentType("application/json")
            .body("""{"amount": "250.00", "currency": "CZK", "referenceId": "e2e-journey-credit"}""")
            .post("/api/v1/balances/$accountId/credit")
        assertThat(credited.statusCode)
            .describedAs("POST credit: %s", credited.body.asString())
            .isEqualTo(200)
        assertPocket(accountId, booked = "1250.00", available = "1250.00", reserved = "0")

        // 3. A card authorization reserves funds. Available falls, booked does NOT — this is the
        //    step where a wrong implementation still answers 201.
        val hold = RestAssured.given()
            .contentType("application/json")
            .body(
                """{"amount": "400.00", "currency": "CZK", "reason": "card authorization",
                    "referenceId": "e2e-journey-hold"}""",
            )
            .post("/api/v1/balances/$accountId/holds")
        assertThat(hold.statusCode)
            .describedAs("POST holds: %s", hold.body.asString())
            .isEqualTo(201)
        val holdId = hold.jsonPath().getString("id")
        assertThat(holdId).isNotNull()
        assertPocket(accountId, booked = "1250.00", available = "850.00", reserved = "400.00")

        // 4. A second hold beyond what is now AVAILABLE must fail closed, and must not move a
        //    single one of the three numbers. Available is 850, so 900 is affordable against
        //    BOOKED and unaffordable against AVAILABLE — the amount is chosen to discriminate.
        val overdrawn = RestAssured.given()
            .contentType("application/json")
            .body(
                """{"amount": "900.00", "currency": "CZK", "reason": "beyond available",
                    "referenceId": "e2e-journey-overdrawn"}""",
            )
            .post("/api/v1/balances/$accountId/holds")
        assertThat(overdrawn.statusCode).isEqualTo(422)
        assertThat(overdrawn.jsonPath().getString("error")).isEqualTo("INSUFFICIENT_FUNDS")
        assertPocket(accountId, booked = "1250.00", available = "850.00", reserved = "400.00")

        // 5. The authorization expires / is released. The reservation is given back in full.
        val released = RestAssured.given().delete("/api/v1/balances/holds/$holdId")
        assertThat(released.statusCode)
            .describedAs("DELETE hold: %s", released.body.asString())
            .isEqualTo(200)
        assertPocket(accountId, booked = "1250.00", available = "1250.00", reserved = "0")

        // 6. Releasing the same hold twice must not credit the customer twice.
        RestAssured.given().delete("/api/v1/balances/holds/$holdId")
        assertPocket(accountId, booked = "1250.00", available = "1250.00", reserved = "0")
    }

    /**
     * The same account carries independent per-currency pockets (ADR-0024): a second currency is
     * added and the CZK pocket's numbers are unchanged, read back through both the per-currency
     * and the all-pockets endpoints.
     */
    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_API"])
    fun `a second currency pocket does not disturb the first`() {
        val accountId = UUID.randomUUID()
        RestAssured.given().contentType("application/json")
            .body("""{"currency": "CZK", "initialAmount": "500.00"}""")
            .post("/api/v1/balances/$accountId/initialize").then().statusCode(201)
        RestAssured.given().contentType("application/json")
            .body("""{"currency": "EUR", "initialAmount": "20.00"}""")
            .post("/api/v1/balances/$accountId/initialize").then().statusCode(201)

        assertPocket(accountId, booked = "500.00", available = "500.00", reserved = "0")

        val all = RestAssured.given().get("/api/v1/balances/$accountId")
        assertThat(all.statusCode).isEqualTo(200)
        assertThat(all.jsonPath().getList<String>("balances.currency"))
            .containsExactlyInAnyOrder("CZK", "EUR")
    }

    /**
     * Known-negative control for the observable every assertion above depends on. If `pocket`
     * silently returned an empty/zero triple, `assertPocket` would pass against anything.
     */
    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_API"])
    fun `the pocket observable answers 404 for an account that was never initialized`() {
        val response: Response = RestAssured.given().get("/api/v1/balances/${UUID.randomUUID()}/CZK")
        assertThat(response.statusCode).isEqualTo(404)
        assertThat(response.jsonPath().getString("error")).isEqualTo("NOT_FOUND")
    }

    private fun assertPocket(accountId: UUID, booked: String, available: String, reserved: String) {
        val response = RestAssured.given().get("/api/v1/balances/$accountId/CZK")
        assertThat(response.statusCode)
            .describedAs("GET pocket: %s", response.body.asString())
            .isEqualTo(200)
        val path = response.jsonPath()
        assertThat(BigDecimal(path.getString("bookedAmount")))
            .describedAs("bookedAmount").isEqualByComparingTo(BigDecimal(booked))
        assertThat(BigDecimal(path.getString("availableAmount")))
            .describedAs("availableAmount").isEqualByComparingTo(BigDecimal(available))
        assertThat(BigDecimal(path.getString("reservedAmount")))
            .describedAs("reservedAmount").isEqualByComparingTo(BigDecimal(reserved))
    }

    private companion object {
        const val ACTOR = "00000000-0000-0000-0000-000000000099"
    }
}
