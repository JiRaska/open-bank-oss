// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.e2e

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

/**
 * End-to-end journey for the ledger money path: **post a balanced journal, see the trial balance
 * move by exactly that amount, reverse it, and see the trial balance return to where it started.**
 *
 * Every step is driven through the service's real HTTP surface (`@QuarkusTest` + RestAssured +
 * `@TestSecurity`) against a real Postgres with the full Flyway chain applied
 * (`PostgresRedpandaTestResource`, per-job Testcontainers) — no use case, repository or domain
 * object is called directly, and nothing is mocked. Each assertion is on state **read back through
 * the API** (`GET /api/v1/journals/{id}` and `GET /api/v1/journals/trial-balance`), never on the
 * status code of the write alone.
 *
 * ### Service boundaries
 *
 * Nothing here crosses one. Posting a journal is self-contained: the entry, its lines and its
 * outbox row are written by this service against its own database. The outbox DISPATCH (the hop
 * that would put `ledger.journal.posted` on a broker and reach balance-service) is deliberately
 * NOT part of this journey — Redpanda is started by the shared test resource but no consumer
 * exists in this process, so the journey stops at the durable state this service owns. It does not
 * claim the downstream hop happened.
 *
 * ### Why the trial balance is the observable
 *
 * A 201 proves an HTTP handler ran. The per-account net on the trial balance is the number the
 * bank is actually run on, and it is derived by a different code path (aggregation query) from the
 * one that wrote the rows — so it can disagree with the write, which is what makes asserting on it
 * worth doing. Balance-neutrality of a post/reverse pair is the ledger's defining invariant
 * (#939): the original flips to REVERSED and a mirrored compensating entry posts, so the net is
 * unchanged while history stays immutable.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.ledger.it.PostgresRedpandaTestResource::class)
class LedgerPostingJourneyE2E {

    /**
     * The whole journey in one test, in order, because each step's precondition is the previous
     * step's observed outcome — a split into independent tests would either re-post the setup or
     * depend on method ordering, and neither states the invariant.
     */
    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_OPERATOR"])
    fun `posting a journal moves the trial balance and reversing it restores the opening position`() {
        val today = LocalDate.now().toString()
        val transactionId = UUID.randomUUID()

        val netBefore = trialBalanceNet(today)

        // 1. Post a balanced CZK journal through the public endpoint.
        val posted = post(
            "/api/v1/journals",
            journalPayload(transactionId, today, AMOUNT),
        )
        assertThat(posted.statusCode)
            .describedAs("POST /api/v1/journals: %s", posted.body.asString())
            .isEqualTo(201)
        assertThat(posted.jsonPath().getString("status")).isEqualTo("POSTED")
        val journalId = posted.jsonPath().getString("id")
        assertThat(journalId).isNotNull()

        // 2. Read the entry back through the API — the write must be durable and complete.
        val readBack = RestAssured.given().get("/api/v1/journals/$journalId")
        assertThat(readBack.statusCode).isEqualTo(200)
        assertThat(readBack.jsonPath().getString("status")).isEqualTo("POSTED")
        assertThat(readBack.jsonPath().getString("transactionId")).isEqualTo(transactionId.toString())
        assertThat(readBack.jsonPath().getList<Any>("lines")).hasSize(2)

        // 3. The entry is also reachable by its business key, not only by its surrogate id.
        val byTransaction = RestAssured.given().get("/api/v1/journals/transaction/$transactionId")
        assertThat(byTransaction.statusCode).isEqualTo(200)
        assertThat(byTransaction.body.asString()).contains(journalId)

        // 4. The trial balance moved by exactly the posted amount, on exactly the two accounts.
        val netAfterPost = trialBalanceNet(today)
        assertThat(delta(netBefore, netAfterPost))
            .describedAs("per-account net movement caused by the posting")
            .isEqualTo(
                mapOf(
                    codeOf(GL_ASSET) to AMOUNT.negate(),
                    codeOf(GL_LIABILITY) to AMOUNT,
                ),
            )

        // 5. Reverse it.
        val reversed = RestAssured.given()
            .contentType("application/json")
            .body("""{"reason": "E2E journey reversal", "reversedBy": "$ACTOR"}""")
            .post("/api/v1/journals/$journalId/reverse")
        assertThat(reversed.statusCode)
            .describedAs("POST reverse: %s", reversed.body.asString())
            .isEqualTo(200)
        assertThat(reversed.jsonPath().getString("status")).isEqualTo("REVERSED")

        // 6. Read back: the original entry is REVERSED, immutably — history is not deleted.
        val afterReversal = RestAssured.given().get("/api/v1/journals/$journalId")
        assertThat(afterReversal.statusCode).isEqualTo(200)
        assertThat(afterReversal.jsonPath().getString("status")).isEqualTo("REVERSED")
        assertThat(afterReversal.jsonPath().getList<Any>("lines")).hasSize(2)

        // 7. And the trial balance is back to the opening position, per account.
        assertThat(trialBalanceNet(today))
            .describedAs("a post/reverse pair must be balance-neutral on every account (#939)")
            .isEqualTo(netBefore)

        // 8. The journey's terminal state fails closed: a second reversal is rejected and the
        //    per-account net does not move again.
        val repeat = RestAssured.given()
            .contentType("application/json")
            .body("""{"reason": "E2E journey repeat", "reversedBy": "$ACTOR"}""")
            .post("/api/v1/journals/$journalId/reverse")
        assertThat(repeat.statusCode).isEqualTo(409)
        assertThat(trialBalanceNet(today)).isEqualTo(netBefore)
    }

    /**
     * Known-negative control for the observable this journey depends on. If `trialBalanceNet` ever
     * returned a constant (an empty map, say), every equality above would pass vacuously. Posting
     * a second, different amount must therefore be VISIBLE to it.
     */
    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_OPERATOR"])
    fun `the trial-balance observable is capable of registering a change`() {
        val today = LocalDate.now().toString()
        val before = trialBalanceNet(today)
        val response = post("/api/v1/journals", journalPayload(UUID.randomUUID(), today, CONTROL_AMOUNT))
        assertThat(response.statusCode).isEqualTo(201)

        assertThat(trialBalanceNet(today))
            .describedAs("control: an unreversed posting MUST change the trial balance")
            .isNotEqualTo(before)
    }

    private fun post(path: String, body: String): Response = RestAssured.given()
        .contentType("application/json")
        .body(body)
        .post(path)

    /** Per-account signed net (credit - debit) in CZK, as the API renders it. */
    private fun trialBalanceNet(asOf: String): Map<String, BigDecimal> {
        val response = RestAssured.given().get("/api/v1/journals/trial-balance?asOf=$asOf")
        assertThat(response.statusCode)
            .describedAs("GET trial-balance: %s", response.body.asString())
            .isEqualTo(200)
        val lines = response.jsonPath().getList<Map<String, Any>>("lines")
        return lines.filter { it["currency"] == "CZK" }.associate {
            val credit = BigDecimal(it["totalCredit"].toString())
            val debit = BigDecimal(it["totalDebit"].toString())
            // stripTrailingZeros: BigDecimal.equals is scale-sensitive (0.0 != 0.00) and the JSON
            // scale grows with everything posted before this test.
            it["code"] as String to (credit - debit).stripTrailingZeros()
        }
    }

    /** Accounts whose net changed, and by how much. Silent about untouched accounts. */
    private fun delta(before: Map<String, BigDecimal>, after: Map<String, BigDecimal>): Map<String, BigDecimal> =
        (before.keys + after.keys).mapNotNull { code ->
            val movement = (after[code] ?: BigDecimal.ZERO) - (before[code] ?: BigDecimal.ZERO)
            if (movement.compareTo(BigDecimal.ZERO) == 0) null else code to movement.stripTrailingZeros()
        }.toMap()

    /**
     * The trial balance is keyed by account CODE while a journal line names the account by id, so
     * the two seeded posting accounts have to be resolved to their codes. Read through the same
     * API surface as everything else rather than by a direct SQL lookup.
     */
    private fun codeOf(glAccountId: String): String {
        val response = RestAssured.given().get("/api/v1/journals/trial-balance?asOf=${LocalDate.now()}")
        assertThat(response.statusCode).isEqualTo(200)
        val line = response.jsonPath().getList<Map<String, Any>>("lines")
            .firstOrNull { it["glAccountId"] == glAccountId }
        assertThat(line)
            .describedAs("seeded GL account %s must appear on the trial balance", glAccountId)
            .isNotNull()
        return line!!["code"] as String
    }

    private fun journalPayload(transactionId: UUID, date: String, amount: BigDecimal): String =
        """
        {
          "idempotencyKey": "${UUID.randomUUID()}",
          "transactionId": "$transactionId",
          "entryDate": "$date",
          "valueDate": "$date",
          "description": "E2E posting journey",
          "createdBy": "$ACTOR",
          "lines": [
            {"glAccountId": "$GL_ASSET", "side": "DEBIT", "amount": "$amount",
             "currencyCode": "CZK", "baseAmount": "$amount", "baseCurrencyCode": "CZK"},
            {"glAccountId": "$GL_LIABILITY", "side": "CREDIT", "amount": "$amount",
             "currencyCode": "CZK", "baseAmount": "$amount", "baseCurrencyCode": "CZK"}
          ]
        }
        """.trimIndent()

    private companion object {
        const val ACTOR = "00000000-0000-0000-0000-000000000099"

        /** Deterministic posting accounts seeded by `V3__ledger_governance.sql`. */
        const val GL_ASSET = "a0000000-0000-0000-0000-000000000001"
        const val GL_LIABILITY = "a0000000-0000-0000-0000-000000000002"

        val AMOUNT: BigDecimal = BigDecimal("742.13")
        val CONTROL_AMOUNT: BigDecimal = BigDecimal("11.11")
    }
}
