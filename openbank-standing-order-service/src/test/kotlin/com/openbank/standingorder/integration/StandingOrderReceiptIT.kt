// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.standingorder.integration

import com.openbank.standingorder.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured
import io.restassured.response.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real HTTP + Postgres proof of the migrated binding and fail-closed receipt lookup. */
@QuarkusTest
@QuarkusTestResource(StandingOrderReplaceIT.NoDispatchResource::class)
@QuarkusTestResource(PostgresRedisTestResource::class)
@TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API"])
class StandingOrderReceiptIT {
    private val party = UUID.randomUUID()
    private val account = UUID.randomUUID()
    private val actor = UUID.randomUUID()

    @Test
    fun `receipt only resolves for original account party actor and payload`() {
        val key = "receipt-${UUID.randomUUID()}"
        val first = RestAssured.given().contentType("application/json")
            .header("X-Customer-Actor-Id", actor.toString())
            .body(body(key, 2_500))
            .post("/api/v1/standing-orders")
        assertThat(first.statusCode).describedAs(first.body.asString()).isEqualTo(201)
        val id = first.jsonPath().getString("id")

        val valid = lookup(key, party, account, actor)
        assertThat(valid.statusCode).isEqualTo(200)
        assertThat(valid.jsonPath().getString("outcome")).isEqualTo("FOUND")
        assertThat(valid.jsonPath().getString("id")).isEqualTo(id)
        assertThat(valid.jsonPath().getString("status")).isEqualTo("ACTIVE")

        listOf(
            lookup(key, UUID.randomUUID(), account, actor),
            lookup(key, party, UUID.randomUUID(), actor),
            lookup(key, party, account, UUID.randomUUID()),
            lookup("missing-${UUID.randomUUID()}", party, account, actor),
        ).forEach { response ->
            assertThat(response.statusCode).isEqualTo(200)
            assertThat(response.jsonPath().getString("outcome")).isEqualTo("UNKNOWN")
            assertThat(response.jsonPath().getString("id")).isNull()
        }

        val changed = RestAssured.given().contentType("application/json")
            .header("X-Customer-Actor-Id", actor.toString())
            .body(body(key, 2_501))
            .post("/api/v1/standing-orders")
        assertThat(changed.statusCode).isEqualTo(400)
        assertThat(lookup(key, party, account, actor).jsonPath().getString("id")).isEqualTo(id)
    }

    @Test
    fun `simultaneous same-key submits replay or reject without a server error`() {
        repeat(3) {
            val sameKey = "concurrent-same-${UUID.randomUUID()}"
            val same = concurrentPosts(sameKey, 2_500, 2_500)
            assertThat(same.map { it.statusCode }).containsExactlyInAnyOrder(201, 201)
            assertThat(same.map { it.jsonPath().getString("id") }.distinct()).hasSize(1)

            val changedKey = "concurrent-changed-${UUID.randomUUID()}"
            val changed = concurrentPosts(changedKey, 2_500, 2_501)
            assertThat(changed.map { it.statusCode }).containsExactlyInAnyOrder(201, 400)
            assertThat(lookup(changedKey, party, account, actor).jsonPath().getString("outcome"))
                .isEqualTo("FOUND")
        }
    }

    @Test
    fun `direct caller cannot alter an explicit date by claiming it was defaulted`() {
        val key = "date-${UUID.randomUUID()}"
        val originalDate = LocalDate.now().plusDays(1)
        val first = RestAssured.given().contentType("application/json")
            .header("X-Customer-Actor-Id", actor.toString())
            .body(body(key, 2_500, originalDate))
            .post("/api/v1/standing-orders")
        assertThat(first.statusCode).isEqualTo(201)

        val changed = RestAssured.given().contentType("application/json")
            .header("X-Customer-Actor-Id", actor.toString())
            .header("X-Start-Date-Defaulted", "true")
            .body(body(key, 2_500, originalDate.plusDays(1)))
            .post("/api/v1/standing-orders")
        assertThat(changed.statusCode).isEqualTo(400)
        assertThat(lookup(key, party, account, actor).jsonPath().getString("id"))
            .isEqualTo(first.jsonPath().getString("id"))
    }

    private fun concurrentPosts(key: String, firstAmount: Int, secondAmount: Int): List<Response> {
        val executor = Executors.newFixedThreadPool(2)
        val barrier = CyclicBarrier(2)
        return try {
            listOf(firstAmount, secondAmount).map { amount ->
                executor.submit(
                    Callable {
                        barrier.await(10, TimeUnit.SECONDS)
                        RestAssured.given().contentType("application/json")
                            .header("X-Customer-Actor-Id", actor.toString())
                            .body(body(key, amount))
                            .post("/api/v1/standing-orders")
                    },
                )
            }.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun lookup(key: String, partyId: UUID, accountId: UUID, actorId: UUID) =
        RestAssured.given().contentType("application/json")
            .header("X-Customer-Party-Id", partyId.toString())
            .header("X-Customer-Actor-Id", actorId.toString())
            .body("""{"idempotencyKey":"$key","debitAccountId":"$accountId"}""")
            .post("/api/v1/standing-orders/receipt-lookup")

    private fun body(key: String, amount: Int, startDate: LocalDate? = LocalDate.now().plusDays(1)) = """
        {
          "idempotencyKey": "$key",
          "partyId": "$party",
          "debitAccountId": "$account",
          "creditorIban": "CZ6508000000192000145399",
          "creditorName": "Test Creditor",
          "creditorBic": null,
          "amountMinorUnits": $amount,
          "currency": "CZK",
          "frequency": "MONTHLY",
          "paymentType": "DOMESTIC",
          "remittanceInfo": null,
          ${startDate?.let { "\"startDate\": \"$it\"," }.orEmpty()}
          "endDate": null
        }
    """.trimIndent()
}
