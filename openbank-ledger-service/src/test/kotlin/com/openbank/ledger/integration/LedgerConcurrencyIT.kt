// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.integration

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured
import io.restassured.response.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Concurrency / double-spend coverage for the posting and reversal paths (#465). The domain
 * invariants are proven single-threaded in [com.openbank.ledger.domain.model.JournalEntryTest];
 * this suite races real HTTP requests against the IT Postgres so the transactional guards —
 * the ledger_idempotency primary key, the POSTED→REVERSED status flip, and the
 * uq_journal_entries_reversal_of backstop index — are exercised where they actually live.
 *
 * Every race uses a [CyclicBarrier] so all contenders hit the endpoint together; there is no
 * sleeping and no timing dependence — the assertions only count committed effects afterwards,
 * so a scheduler that happens to serialise the requests still proves "no double effect".
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.ledger.it.PostgresRedpandaTestResource::class)
class LedgerConcurrencyIT {

    // Deterministic posting accounts seeded by V3__ledger_governance.sql (same as LedgerApiIT).
    private val glAssetId = "a0000000-0000-0000-0000-000000000001"
    private val glLiabilityId = "a0000000-0000-0000-0000-000000000002"
    private val operatorId = UUID.randomUUID()

    private fun journalPayload(idempotencyKey: String, transactionId: UUID, amount: String): String {
        val today = LocalDate.now().toString()
        return """
            {
              "idempotencyKey": "$idempotencyKey",
              "transactionId": "$transactionId",
              "entryDate": "$today",
              "valueDate": "$today",
              "description": "Concurrency IT posting",
              "createdBy": "$operatorId",
              "lines": [
                {
                  "glAccountId": "$glAssetId",
                  "side": "DEBIT",
                  "amount": "$amount",
                  "currencyCode": "CZK",
                  "baseAmount": "$amount",
                  "baseCurrencyCode": "CZK"
                },
                {
                  "glAccountId": "$glLiabilityId",
                  "side": "CREDIT",
                  "amount": "$amount",
                  "currencyCode": "CZK",
                  "baseAmount": "$amount",
                  "baseCurrencyCode": "CZK"
                }
              ]
            }
        """.trimIndent()
    }

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

    private fun journalsForTransaction(transactionId: UUID): List<Map<String, Any>> {
        val resp = RestAssured.given().get("/api/v1/journals/transaction/$transactionId")
        check(resp.statusCode == 200) {
            "GET journals/transaction -> ${resp.statusCode}, body: ${resp.body.asString()}, headers: ${resp.headers}"
        }
        return resp.jsonPath().getList("")
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `parallel distinct postings all land exactly once and the trial balance stays tied`() {
        val n = 8
        val transactionId = UUID.randomUUID()

        val responses = race(n) { i ->
            RestAssured.given()
                .contentType("application/json")
                .body(journalPayload(UUID.randomUUID().toString(), transactionId, "100.00"))
                .post("/api/v1/journals")
        }

        assertThat(responses.map { it.statusCode }).containsOnly(201)

        val entries = journalsForTransaction(transactionId)
        assertThat(entries).hasSize(n)
        // Entry numbers are sequence-assigned under concurrency — no duplicates.
        assertThat(entries.map { it["entryNumber"] }).doesNotHaveDuplicates()

        // Global double-entry invariant survives concurrent posting: the trial balance
        // (which also folds in every other test's postings) still ties out to the cent.
        val trialBalance = RestAssured.given()
            .get("/api/v1/journals/trial-balance?asOf=${LocalDate.now()}")
            .then().statusCode(200)
            .extract().jsonPath()
        assertThat(trialBalance.getBoolean("balanced")).isTrue()
        assertThat(BigDecimal(trialBalance.getString("totalDebit")))
            .isEqualByComparingTo(BigDecimal(trialBalance.getString("totalCredit")))
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `racing duplicate submissions with one idempotency key post exactly once`() {
        val n = 6
        val transactionId = UUID.randomUUID()
        val idempotencyKey = UUID.randomUUID().toString()
        val payload = journalPayload(idempotencyKey, transactionId, "250.00")

        val responses = race(n) {
            RestAssured.given()
                .contentType("application/json")
                .body(payload)
                .post("/api/v1/journals")
        }

        // The loser path must be a clean idempotent replay of the winner's entry — the same
        // contract the sequential replay already has — never a 5xx and never a second posting.
        assertThat(responses.map { it.statusCode })
            .describedAs(
                "concurrent duplicates must all resolve to the idempotent 201, got %s",
                responses.map {
                    it.statusCode
                },
            )
            .containsOnly(201)
        assertThat(responses.map { it.jsonPath().getString("id") }.toSet())
            .describedAs("every contender must see the SAME journal entry")
            .hasSize(1)

        assertThat(journalsForTransaction(transactionId)).hasSize(1)
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `racing reversals of one posted entry reverse it exactly once`() {
        val transactionId = UUID.randomUUID()
        val journalId = RestAssured.given()
            .contentType("application/json")
            .body(journalPayload(UUID.randomUUID().toString(), transactionId, "500.00"))
            .post("/api/v1/journals")
            .then().statusCode(201)
            .extract().jsonPath().getString("id")

        val n = 4
        val responses = race(n) {
            RestAssured.given()
                .contentType("application/json")
                .body("""{"reason": "Concurrency IT reversal", "reversedBy": "$operatorId"}""")
                .post("/api/v1/journals/$journalId/reverse")
        }

        // Exactly one contender books the compensation; every other one must surface the
        // conflict (409 via the IllegalStateException mapper), never a second reversal —
        // a double reversal double-credits the counterparty downstream (AccountBookedChanged
        // deltas are emitted per reversal).
        val byStatus = responses.groupBy { it.statusCode }
        assertThat(byStatus[200])
            .describedAs("exactly one reversal must win, got statuses %s", responses.map { it.statusCode })
            .hasSize(1)
        assertThat(byStatus.keys - setOf(200, 409))
            .describedAs(
                "losers must fail with a clean 409 conflict, got %s",
                responses.map { "${it.statusCode}: ${it.body.asString()}" },
            )
            .isEmpty()

        // Original + exactly ONE reversal entry — never two compensations.
        val entries = journalsForTransaction(transactionId)
        assertThat(entries).hasSize(2)
        assertThat(entries.count { (it["description"] as? String)?.startsWith("Reversal of entry") == true })
            .isEqualTo(1)
    }
}
