// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.integration

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured
import io.restassured.response.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Concurrency / double-effect coverage for account opening and lifecycle transitions (#465).
 * Races real HTTP requests against the IT Postgres so the transactional guards live where the
 * defects lived: the account_idempotency primary key (V14) and the version-matched update.
 *
 * Barrier-released races, no sleeps; assertions count committed effects afterwards, so a
 * scheduler that happens to serialise the requests still proves "no double effect".
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.account.it.PostgresRedpandaRedisTestResource::class)
class AccountConcurrencyIT {

    private val productId = UUID.fromString("00000000-2222-0000-0000-000000000001")

    private fun openPayload(partyId: UUID) = """
        {
          "partyId": "$partyId",
          "productId": "$productId",
          "accountType": "CURRENT",
          "currencyCode": "CZK",
          "legalName": "Concurrency IT s.r.o."
        }
    """.trimIndent()

    private fun openRequest(partyId: UUID, idempotencyKey: String): Response = RestAssured.given()
        .contentType("application/json")
        .header("Idempotency-Key", idempotencyKey)
        .body(openPayload(partyId))
        .post("/api/v1/accounts")

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

    private fun accountsOfParty(partyId: UUID): List<Map<String, Any>> {
        val resp = RestAssured.given()
            .queryParam("partyId", partyId.toString())
            .get("/api/v1/accounts")
        check(resp.statusCode == 200) { "GET accounts -> ${resp.statusCode}: ${resp.body.asString()}" }
        return resp.jsonPath().getList("data")
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `racing duplicate opens with one idempotency key open exactly one account`() {
        val n = 6
        val partyId = UUID.randomUUID()
        val idempotencyKey = UUID.randomUUID().toString()

        val responses = race(n) { openRequest(partyId, idempotencyKey) }

        // The loser path must resolve to the winner's account — never a second account
        // (silent double effect: two IBANs, two AccountCreated events) and never a 5xx.
        assertThat(responses.map { it.statusCode })
            .describedAs("statuses %s", responses.map { "${it.statusCode}: ${it.body.asString().take(120)}" })
            .containsOnly(201)
        assertThat(responses.map { it.jsonPath().getString("id") }.toSet())
            .describedAs("every contender must see the SAME account")
            .hasSize(1)

        assertThat(accountsOfParty(partyId)).hasSize(1)
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `parallel distinct opens all land exactly once with unique IBANs`() {
        val n = 8
        val partyId = UUID.randomUUID()

        val responses = race(n) { openRequest(partyId, UUID.randomUUID().toString()) }

        assertThat(responses.map { it.statusCode }).containsOnly(201)
        val accounts = accountsOfParty(partyId)
        assertThat(accounts).hasSize(n)
        assertThat(accounts.map { it["accountNumber"] }).doesNotHaveDuplicates()
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `racing freeze against close never resurrects a closed account`() {
        val partyId = UUID.randomUUID()
        val accountId = openRequest(partyId, UUID.randomUUID().toString())
            .then().statusCode(201)
            .extract().jsonPath().getString("id")

        val freeze = { _: Int ->
            RestAssured.given()
                .contentType("application/json")
                .body("""{"reason": "fraud alert"}""")
                .post("/api/v1/accounts/$accountId/freeze")
        }
        val close = { _: Int ->
            RestAssured.given()
                .contentType("application/json")
                .body("""{"reason": "customer request"}""")
                .post("/api/v1/accounts/$accountId/close")
        }
        val responses = race(2) { i -> if (i == 0) freeze(i) else close(i) }

        // Exactly one transition wins; the loser surfaces a clean conflict — either the 409
        // version guard (truly concurrent) or the domain state check (arrived after commit,
        // libs maps it 422). It must NOT silently succeed: freeze-after-close previously
        // resurrected the CLOSED account as FROZEN (lost update through re-read-and-copy).
        val winners = responses.count { it.statusCode == 200 }
        assertThat(winners)
            .describedAs("statuses %s", responses.map { "${it.statusCode}: ${it.body.asString().take(120)}" })
            .isEqualTo(1)
        assertThat(responses.map { it.statusCode }.filter { it != 200 })
            .allSatisfy { assertThat(it).isIn(409, 422) }

        // Final state matches the winner — and a CLOSED account stayed CLOSED if close won.
        val winnerStatus = responses.first { it.statusCode == 200 }.jsonPath().getString("status")
        val finalStatus = RestAssured.given()
            .get("/api/v1/accounts/$accountId")
            .then().statusCode(200)
            .extract().jsonPath().getString("status")
        assertThat(finalStatus).isEqualTo(winnerStatus)
    }
}
