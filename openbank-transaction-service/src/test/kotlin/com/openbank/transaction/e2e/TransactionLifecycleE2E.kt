// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.e2e

import com.openbank.transaction.it.PostgresRedpandaTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * End-to-end journeys of the booking ingest over the real HTTP surface against real Postgres +
 * Redpanda. The booking spine is where every rail lands, so the E2E pins the behaviours a unit
 * test around the use case cannot see:
 *
 *  - **Idempotent replay (ADR-0298, #8351)**: a retried initiate with the SAME `idempotencyKey`
 *    but a DIFFERENT amount replays the stored transaction — one row, the stored amount, never
 *    a second debit.
 *  - **The observable shape**: the rail-less booking completes synchronously (`COMPLETED` — the
 *    test JVM runs the saga in-process), the generated `referenceNumber` is non-blank, and reads —
 *    get, account list, BIAN search — all answer the same row. A completed credit is reversible:
 *    the R-transaction return path (ADR-0111) links the reversal back to the original.
 *  - **Input guards as client errors, not 500s**: listing without `accountId` is 400 (#3104);
 *    an unparseable `status` enum is 400, not a silently DROPPED filter (#8699); an unknown
 *    `rail` is 400; a non-positive amount is 422; a customer-initiated movement without an SCA
 *    challenge or exemption is 400 (ADR-0021).
 *  - **Least privilege (K7 / ADR-0018)**: `ROLE_VIEWER` reads the history but is refused 403 on
 *    initiate.
 *
 * `authz.enforce` defaults to `false` (advisory), so no OPA mirror is needed.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedpandaTestResource::class)
class TransactionLifecycleE2E {

    @Test
    @TestSecurity(user = "e2e-operator-1", roles = ["ROLE_OPERATOR"])
    fun `a booking is initiated read back and replayed idempotently end to end`() {
        val key = "tx-e2e-${UUID.randomUUID()}"
        val accountId = UUID.randomUUID()
        val today = LocalDate.now().toString()

        val created = initiate(creditBody(key, accountId, "5000.00", today))
        val id = created["id"] as String
        // The rail-less booking runs its saga in-process in the test JVM and completes synchronously.
        assertThat(created["status"]).isEqualTo("COMPLETED")
        assertThat(created["referenceNumber"] as String).isNotBlank()
        assertThat(BigDecimal(created["amount"].toString())).isEqualByComparingTo("5000.00")
        assertThat(created["currencyCode"]).isEqualTo("CZK")
        assertThat(created["rail"]).isEqualTo(null)

        // Same key, different amount: the stored booking replays, no second write.
        val replay = initiate(creditBody(key, accountId, "999.00", today))
        assertThat(replay["id"]).isEqualTo(id)
        assertThat(BigDecimal(replay["amount"].toString())).isEqualByComparingTo("5000.00")

        val readBack: Map<String, Any?> = Given {
            this
        } When {
            get("/api/v1/transactions/$id")
        } Then {
            statusCode(200)
        } Extract {
            jsonPath().getMap("")
        }
        assertThat(readBack["status"]).isEqualTo("COMPLETED")
        assertThat(readBack["referenceNumber"]).isEqualTo(created["referenceNumber"])

        val listing: Map<String, Any?> = Given {
            this
        } When {
            get("/api/v1/transactions?accountId=$accountId")
        } Then {
            statusCode(200)
        } Extract {
            jsonPath().getMap("")
        }
        val rows = listing["data"] as List<Map<String, Any?>>
        assertThat(rows.map { it["id"] }).containsExactly(id)

        val searched: Map<String, Any?> = Given {
            this
        } When {
            get("/api/v1/transactions/search?accountId=$accountId&status=COMPLETED")
        } Then {
            statusCode(200)
        } Extract {
            jsonPath().getMap("")
        }
        val hits = searched["data"] as List<Map<String, Any?>>
        assertThat((searched["count"] as Number).toInt()).isEqualTo(hits.size)
        assertThat(hits.map { it["id"] }).contains(id)
    }

    @Test
    @TestSecurity(user = "e2e-operator-1", roles = ["ROLE_OPERATOR"])
    fun `a completed debit is reversible exactly once end to end`() {
        val accountId = UUID.randomUUID()
        val today = LocalDate.now().toString()
        val created = initiate(debitBody("tx-e2e-revbase-${UUID.randomUUID()}", accountId, "5000.00", today))
        val id = created["id"] as String
        assertThat(created["status"]).isEqualTo("COMPLETED")

        // ADR-0111: reverse the completed debit; the R-transaction credits the source back.
        val revKey = "tx-e2e-rev-${UUID.randomUUID()}"
        val reversal = reverse(id, revKey, "E2E customer return")
        assertThat(reversal["status"]).isEqualTo("COMPLETED")
        assertThat(reversal["type"]).isEqualTo("REVERSAL")
        assertThat(BigDecimal(reversal["amount"].toString())).isEqualByComparingTo("5000.00")
        assertThat(reversal["description"] as String).contains("E2E customer return")

        // The reversal replays on its own idempotency key; a fresh key on the REVERSED
        // original refuses 422.
        val replayed = reverse(id, revKey, "replay")
        assertThat(replayed["id"]).isEqualTo(reversal["id"])
        assertThat(replayed["description"]).isEqualTo(reversal["description"])

        Given {
            contentType("application/json")
            body(
                """
                {
                  "idempotencyKey": "tx-e2e-rev2-${UUID.randomUUID()}",
                  "reason": "double reverse"
                }
                """.trimIndent(),
            )
        } When {
            post("/api/v1/transactions/$id/reverse")
        } Then {
            statusCode(422)
        }
    }

    @Test
    @TestSecurity(user = "e2e-operator-1", roles = ["ROLE_OPERATOR"])
    fun `malformed queries and broken invariants are client errors not 500s`() {
        val accountId = UUID.randomUUID()

        // #3104: "transactions for an account" without the account is a 400.
        Given {
            this
        } When {
            get("/api/v1/transactions")
        } Then {
            statusCode(400)
        }

        // #8699: an unparseable enum must 400, not silently drop the filter.
        Given {
            this
        } When {
            get("/api/v1/transactions/search?status=FAILDE")
        } Then {
            statusCode(400)
        }

        val today = LocalDate.now().toString()

        // Unknown rail name — strict enum parsing, same 400 contract.
        postExpectingStatus(creditBody("tx-e2e-rail-${UUID.randomUUID()}", accountId, "10.00", today, "TELEPORT"), 400)

        // A non-positive amount is a business-rule violation: 422, not 400, not 500.
        postExpectingStatus(creditBody("tx-e2e-zero-${UUID.randomUUID()}", accountId, "0.00", today), 422)

        // ADR-0021: a customer-initiated movement with no SCA challenge and no exemption is 400.
        postExpectingStatus(
            """
            {
              "idempotencyKey": "tx-e2e-sca-${UUID.randomUUID()}",
              "type": "TRANSFER",
              "sourceAccountId": "$accountId",
              "targetAccountId": "${UUID.randomUUID()}",
              "amount": "100.00",
              "currencyCode": "CZK",
              "description": "no sca",
              "valueDate": "$today",
              "initiatedByPartyId": "${UUID.randomUUID()}"
            }
            """.trimIndent(),
            400,
        )
    }

    @Test
    @TestSecurity(user = "e2e-viewer-1", roles = ["ROLE_VIEWER"])
    fun `viewers can read the history but never initiate`() {
        Given {
            this
        } When {
            get("/api/v1/transactions/search?limit=5")
        } Then {
            statusCode(200)
        }

        Given {
            contentType("application/json")
            body(
                """
                {
                  "idempotencyKey": "tx-e2e-viewer-${UUID.randomUUID()}",
                  "type": "CREDIT",
                  "targetAccountId": "${UUID.randomUUID()}",
                  "amount": "10.00",
                  "currencyCode": "CZK",
                  "description": "viewer write",
                  "valueDate": "${LocalDate.now()}"
                }
                """.trimIndent(),
            )
        } When {
            post("/api/v1/transactions")
        } Then {
            statusCode(403)
        }
    }

    private fun reverse(id: String, key: String, reason: String): Map<String, Any?> = Given {
        contentType("application/json")
        body(
            """
            {
              "idempotencyKey": "$key",
              "reason": "$reason"
            }
            """.trimIndent(),
        )
    } When {
        post("/api/v1/transactions/$id/reverse")
    } Then {
        statusCode(200)
    } Extract {
        jsonPath().getMap("")
    }

    private fun initiate(payload: String): Map<String, Any?> = Given {
        contentType("application/json")
        body(payload)
    } When {
        post("/api/v1/transactions")
    } Then {
        statusCode(201)
    } Extract {
        jsonPath().getMap("")
    }

    private fun postExpectingStatus(payload: String, status: Int) {
        Given {
            contentType("application/json")
            body(payload)
        } When {
            post("/api/v1/transactions")
        } Then {
            statusCode(status)
        }
    }

    private fun debitBody(key: String, sourceAccountId: UUID, amount: String, valueDate: String) = """
        {
          "idempotencyKey": "$key",
          "type": "DEBIT",
          "sourceAccountId": "$sourceAccountId",
          "amount": "$amount",
          "currencyCode": "CZK",
          "baseAmount": "$amount",
          "baseCurrencyCode": "CZK",
          "description": "E2E withdrawal",
          "valueDate": "$valueDate"
        }
    """.trimIndent()

    private fun creditBody(
        key: String,
        accountId: UUID,
        amount: String,
        valueDate: String,
        rail: String? = null,
    ): String {
        val railLine = rail?.let { ",\n          \"rail\": \"$it\"" } ?: ""
        return """
        {
          "idempotencyKey": "$key",
          "type": "CREDIT",
          "targetAccountId": "$accountId",
          "amount": "$amount",
          "currencyCode": "CZK",
          "baseAmount": "$amount",
          "baseCurrencyCode": "CZK",
          "description": "E2E deposit",
          "valueDate": "$valueDate"$railLine
        }
        """.trimIndent()
    }
}
