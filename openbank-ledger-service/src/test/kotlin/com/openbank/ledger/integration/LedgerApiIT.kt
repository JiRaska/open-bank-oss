// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.integration

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.time.LocalDate
import java.util.UUID

@QuarkusTest
@QuarkusTestResource(com.openbank.ledger.it.PostgresRedpandaTestResource::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class LedgerApiIT {

    companion object {
        private var createdJournalId: String? = null
        private val transactionId = UUID.randomUUID()
        private val operatorId = UUID.randomUUID()
    }

    @Test
    @Order(1)
    fun `GET info returns service metadata`() {
        val body = (Given { this } When { get("/api/v1/info") } Then { statusCode(200) }).extract().body().asString()
        assertThat(body).contains("openbank-ledger-service")
    }

    @Test
    @Order(2)
    fun `GET health ready returns UP`() {
        Given { this } When { get("/q/health/ready") } Then { statusCode(200) }
    }

    @Test
    @Order(3)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_VIEWER"])
    fun `GET journals returns empty page initially`() {
        Given {
            queryParam("fromDate", "2026-01-01")
            queryParam("toDate", LocalDate.now().toString())
        } When {
            get("/api/v1/journals")
        } Then {
            statusCode(200)
            body("data", notNullValue())
        }
    }

    @Test
    @Order(4)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `POST journals posts a balanced journal entry`() {
        // Deterministic posting accounts seeded by V3__ledger_governance.sql.
        val glAssetId = "a0000000-0000-0000-0000-000000000001"
        val glLiabilityId = "a0000000-0000-0000-0000-000000000002"
        val today = LocalDate.now().toString()

        val payload = """
            {
              "idempotencyKey": "${UUID.randomUUID()}",
              "transactionId": "$transactionId",
              "entryDate": "$today",
              "valueDate": "$today",
              "description": "Integration test posting",
              "createdBy": "$operatorId",
              "lines": [
                {
                  "glAccountId": "$glAssetId",
                  "side": "DEBIT",
                  "amount": "1000.00",
                  "currencyCode": "CZK",
                  "baseAmount": "1000.00",
                  "baseCurrencyCode": "CZK"
                },
                {
                  "glAccountId": "$glLiabilityId",
                  "side": "CREDIT",
                  "amount": "1000.00",
                  "currencyCode": "CZK",
                  "baseAmount": "1000.00",
                  "baseCurrencyCode": "CZK"
                }
              ]
            }
        """.trimIndent()

        val response = Given {
            contentType("application/json")
            body(payload)
        } When {
            post("/api/v1/journals")
        } Then {
            statusCode(201)
            body("id", notNullValue())
            body("status", equalTo("POSTED"))
            body("transactionId", equalTo(transactionId.toString()))
        }

        createdJournalId = response.extract().body().jsonPath().getString("id")
        assertThat(createdJournalId).isNotNull
    }

    @Test
    @Order(5)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_VIEWER"])
    fun `GET journal by id returns posted entry`() {
        val id = createdJournalId ?: return
        Given { this } When {
            get("/api/v1/journals/$id")
        } Then {
            statusCode(200)
            body("id", equalTo(id))
            body("status", equalTo("POSTED"))
            body("lines.size()", equalTo(2))
        }
    }

    @Test
    @Order(6)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_VIEWER"])
    fun `GET journals by transactionId returns matching entry`() {
        Given { this } When {
            get("/api/v1/journals/transaction/$transactionId")
        } Then {
            statusCode(200)
        }
        val body = (
            Given { this } When {
                get("/api/v1/journals/transaction/$transactionId")
            } Then { statusCode(200) }
            ).extract().body().asString()
        assertThat(body).contains(transactionId.toString())
    }

    @Test
    @Order(7)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_VIEWER"])
    fun `GET journal by unknown id returns 404`() {
        Given { this } When {
            get("/api/v1/journals/${UUID.randomUUID()}")
        } Then {
            statusCode(404)
        }
    }

    @Test
    @Order(8)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `POST journals rejects unbalanced entry`() {
        val today = LocalDate.now().toString()
        val payload = """
            {
              "idempotencyKey": "${UUID.randomUUID()}",
              "transactionId": "${UUID.randomUUID()}",
              "entryDate": "$today",
              "valueDate": "$today",
              "description": "Unbalanced — should fail",
              "createdBy": "$operatorId",
              "lines": [
                {
                  "glAccountId": "${UUID.randomUUID()}",
                  "side": "DEBIT",
                  "amount": "500.00",
                  "currencyCode": "CZK",
                  "baseAmount": "500.00",
                  "baseCurrencyCode": "CZK"
                }
              ]
            }
        """.trimIndent()

        Given {
            contentType("application/json")
            body(payload)
        } When {
            post("/api/v1/journals")
        } Then {
            statusCode(422)
        }
    }

    @Test
    @Order(9)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `POST reverse journal reverses posted entry`() {
        val id = createdJournalId ?: return
        Given {
            contentType("application/json")
            body("""{"reason": "Test reversal", "reversedBy": "$operatorId"}""")
        } When {
            post("/api/v1/journals/$id/reverse")
        } Then {
            statusCode(200)
            body("status", equalTo("REVERSED"))
        }
    }

    // Sequential (non-concurrent) double reversal against the real DB, ordered right after the
    // happy-path reversal above. LedgerConcurrencyIT proves the SIMULTANEOUS race is serialized by
    // the conditional UPDATE in PanacheJournalRepository.saveReversal (#465); this proves the plain
    // repeat-call path also fails closed end-to-end (use-case pre-check AND the persistence guard
    // both agree the entry is no longer POSTED) rather than silently no-op'ing or double-booking a
    // second compensation entry. A mutant that weakened either guard (e.g. dropped `status = ?4`
    // from the conditional UPDATE, or the use-case's own status check) would only be caught by a
    // race under exact scheduling luck in the concurrency suite — this sequential call exercises
    // the same guard deterministically, every run.
    @Test
    @Order(10)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `POST reverse journal on an already-REVERSED entry is rejected with 409 and books no second compensation`() {
        val id = createdJournalId ?: return

        Given { this } When {
            get("/api/v1/journals/$id")
        } Then {
            statusCode(200)
            body("status", equalTo("REVERSED"))
            body("id", equalTo(id))
        }

        Given {
            contentType("application/json")
            body("""{"reason": "Repeat reversal", "reversedBy": "$operatorId"}""")
        } When {
            post("/api/v1/journals/$id/reverse")
        } Then {
            statusCode(409)
        }

        // Still REVERSED — the failed repeat attempt did not flip state or leave a partial write.
        Given { this } When {
            get("/api/v1/journals/$id")
        } Then {
            statusCode(200)
            body("status", equalTo("REVERSED"))
        }
    }

    // Regression for #939: a posted-then-reversed pair must be balance-neutral on the trial
    // balance. The original entry flips to REVERSED and the compensating entry posts with
    // mirrored sides — immutable history, netting to zero. A status filter of POSTED alone
    // drops the original's legs but keeps the reversal's, skewing every touched account by the
    // original's net. The global debit==credit tie-out CANNOT catch that (the surviving
    // reversal entry is internally balanced too), so this asserts the per-account NET.
    @Test
    @Order(11)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `a reversed pair is balance-neutral on the trial balance per account`() {
        val glAssetId = "a0000000-0000-0000-0000-000000000001"
        val glLiabilityId = "a0000000-0000-0000-0000-000000000002"
        val today = LocalDate.now().toString()

        fun netByAccount(): Map<String, java.math.BigDecimal> {
            val resp = Given { this } When { get("/api/v1/journals/trial-balance?asOf=$today") } Then {
                statusCode(200)
            }
            val lines = resp.extract().jsonPath().getList<Map<String, Any>>("lines")
            return lines.filter { it["currency"] == "CZK" }.associate {
                val credit = java.math.BigDecimal(it["totalCredit"].toString())
                val debit = java.math.BigDecimal(it["totalDebit"].toString())
                // stripTrailingZeros: BigDecimal.equals is scale-sensitive (300.0 != 300.00) and
                // the JSON scale grows with the decimals of the amounts posted so far.
                it["code"] as String to (credit - debit).stripTrailingZeros()
            }
        }

        val netBefore = netByAccount()

        val payload = """
            {
              "idempotencyKey": "${UUID.randomUUID()}",
              "transactionId": "${UUID.randomUUID()}",
              "entryDate": "$today",
              "valueDate": "$today",
              "description": "Reversal-neutrality regression (#939)",
              "createdBy": "$operatorId",
              "lines": [
                {"glAccountId": "$glAssetId", "side": "DEBIT", "amount": "123.45",
                 "currencyCode": "CZK", "baseAmount": "123.45", "baseCurrencyCode": "CZK"},
                {"glAccountId": "$glLiabilityId", "side": "CREDIT", "amount": "123.45",
                 "currencyCode": "CZK", "baseAmount": "123.45", "baseCurrencyCode": "CZK"}
              ]
            }
        """.trimIndent()
        val journalId = (
            Given {
                contentType("application/json")
                body(payload)
            } When {
                post("/api/v1/journals")
            } Then {
                statusCode(201)
            }
            ).extract().jsonPath().getString("id")

        Given {
            contentType("application/json")
            body("""{"reason": "Reversal-neutrality regression", "reversedBy": "$operatorId"}""")
        } When {
            post("/api/v1/journals/$journalId/reverse")
        } Then {
            statusCode(200)
            body("status", equalTo("REVERSED"))
        }

        // Post + reverse must leave every account's net position exactly where it started.
        assertThat(netByAccount()).isEqualTo(netBefore)
    }

    /**
     * A `null` element inside the `lines` array, and an absent body.
     *
     * Kotlin's `List<PostJournalLineRequest>` is a compile-time promise Jackson does not keep: the
     * Kotlin module null-checks CONSTRUCTOR PARAMETERS, never the ELEMENTS of a collection, so
     * `"lines": [null]` deserialises to a list holding a null. `request.lines.map { it.toCommand() }`
     * then threw NPE and `GenericExceptionMapper` answered 500 (#5913).
     *
     * The absent body is the same defect one level up. `postJournal` is a `suspend fun`, and the
     * Kotlin compiler emits NO `Intrinsics.checkNotNullParameter` for a suspending function, so the
     * null does not fail at offset 0 -- it flows into the body and dies at the first dereference.
     *
     * Both are malformed input, so both must be 400. A client cannot tell "I sent a bad request"
     * from "the server is broken", and on a money path that difference decides whether it retries.
     */
    @Test
    @Order(20)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `POST journals answers 400 for a null line and for an absent body`() {
        val today = LocalDate.now().toString()
        val withNullLine = """
            {
              "idempotencyKey": "${UUID.randomUUID()}",
              "transactionId": "${UUID.randomUUID()}",
              "entryDate": "$today",
              "valueDate": "$today",
              "createdBy": "$operatorId",
              "lines": [null]
            }
        """.trimIndent()

        Given {
            contentType("application/json")
            body(withNullLine)
        } When {
            post("/api/v1/journals")
        } Then {
            statusCode(400)
        }

        Given {
            contentType("application/json")
        } When {
            post("/api/v1/journals")
        } Then {
            statusCode(400)
        }
    }
}
