// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

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
}
