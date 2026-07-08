// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.integration

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured
import io.restassured.response.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Concurrency / double-effect coverage for transaction initiation and the R-transaction
 * reversal path (#465). Races real HTTP requests against the IT Postgres; the in-process
 * Temporal [com.openbank.transaction.infrastructure.temporal.WorkflowClientTestProducer]
 * completes every payment workflow synchronously, so initiated transactions land COMPLETED
 * and the reversal races run against real terminal states.
 *
 * Barrier-released races, no sleeps; assertions count committed effects afterwards, so a
 * scheduler that happens to serialise the requests still proves "no double effect".
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.transaction.it.PostgresRedpandaTestResource::class)
class TransactionConcurrencyIT {

    private val today = LocalDate.now().toString()

    private fun transferPayload(idempotencyKey: String, sourceAccountId: UUID, targetAccountId: UUID) = """
        {
          "idempotencyKey": "$idempotencyKey",
          "type": "TRANSFER",
          "sourceAccountId": "$sourceAccountId",
          "targetAccountId": "$targetAccountId",
          "amount": "150.00",
          "currencyCode": "CZK",
          "description": "Concurrency IT transfer",
          "valueDate": "$today"
        }
    """.trimIndent()

    private fun initiate(payload: String): Response = RestAssured.given()
        .contentType("application/json")
        .body(payload)
        .post("/api/v1/transactions")

    /** Fire [n] request suppliers simultaneously (barrier-released) and collect the responses. */
    private fun race(n: Int, request: (Int) -> Response): List<Response> {
        val barrier = CyclicBarrier(n)
        val pool = Executors.newFixedThreadPool(n)
        try {
            val futures = (0 until n).map { i ->
                pool.submit<Response> {
                    barrier.await(30, TimeUnit.SECONDS)
                    request(i)
                }
            }
            return futures.map { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun transactionsOfAccount(accountId: UUID): List<Map<String, Any>> {
        val resp = RestAssured.given()
            .queryParam("accountId", accountId.toString())
            .queryParam("limit", "50")
            .get("/api/v1/transactions")
        check(resp.statusCode == 200) { "GET transactions -> ${resp.statusCode}: ${resp.body.asString()}" }
        return resp.jsonPath().getList("data")
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `racing duplicate initiations with one idempotency key create exactly one transaction`() {
        val n = 6
        val source = UUID.randomUUID()
        val target = UUID.randomUUID()
        val payload = transferPayload(UUID.randomUUID().toString(), source, target)

        val responses = race(n) { initiate(payload) }

        // The loser path must resolve to the winner's transaction — never a second payment
        // (a second Temporal workflow moving the money twice) and never a 5xx.
        assertThat(responses.map { it.statusCode })
            .describedAs("statuses %s", responses.map { "${it.statusCode}: ${it.body.asString().take(120)}" })
            .containsOnly(201)
        assertThat(responses.map { it.jsonPath().getString("id") }.toSet())
            .describedAs("every contender must see the SAME transaction")
            .hasSize(1)

        assertThat(transactionsOfAccount(target)).hasSize(1)
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `parallel distinct initiations all land exactly once with unique reference numbers`() {
        val n = 8
        val source = UUID.randomUUID()
        val target = UUID.randomUUID()

        val responses = race(n) {
            initiate(transferPayload(UUID.randomUUID().toString(), source, target))
        }

        assertThat(responses.map { it.statusCode })
            .describedAs("statuses %s", responses.map { "${it.statusCode}: ${it.body.asString().take(120)}" })
            .containsOnly(201)
        val transactions = transactionsOfAccount(target)
        assertThat(transactions).hasSize(n)
        assertThat(transactions.map { it["referenceNumber"] }).doesNotHaveDuplicates()
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `racing reversals with distinct keys refund a completed transaction exactly once`() {
        val source = UUID.randomUUID()
        val target = UUID.randomUUID()
        val initiated = initiate(transferPayload(UUID.randomUUID().toString(), source, target))
            .then().statusCode(201)
            .extract().jsonPath()
        val transactionId = initiated.getString("id")
        assertThat(initiated.getString("status"))
            .describedAs("failureReason=%s", initiated.getString("failureReason"))
            .isEqualTo("COMPLETED")

        // DISTINCT idempotency keys — two impatient operators, or a retry storm that re-mints
        // keys. The idempotent-replay path cannot help here; only the original's COMPLETED ->
        // REVERSED transition can arbitrate, and it must do so exactly once: every extra winner
        // initiates one extra reversal credit (double refund).
        val n = 4
        val responses = race(n) {
            RestAssured.given()
                .contentType("application/json")
                .body("""{"idempotencyKey": "${UUID.randomUUID()}", "reason": "Concurrency IT return"}""")
                .post("/api/v1/transactions/$transactionId/reverse")
        }

        val winners = responses.count { it.statusCode == 200 }
        assertThat(winners)
            .describedAs("statuses %s", responses.map { "${it.statusCode}: ${it.body.asString().take(120)}" })
            .isEqualTo(1)
        assertThat(responses.map { it.statusCode }.filter { it != 200 })
            .allSatisfy { assertThat(it).isIn(409, 422) }

        // Exactly ONE reversal credit reached the source account, and the original flipped
        // to REVERSED exactly once.
        val reversals = transactionsOfAccount(source).filter { it["type"] == "REVERSAL" }
        assertThat(reversals).hasSize(1)
        val original = RestAssured.given()
            .get("/api/v1/transactions/$transactionId")
            .then().statusCode(200)
            .extract().jsonPath().getString("status")
        assertThat(original).isEqualTo("REVERSED")
    }
}
