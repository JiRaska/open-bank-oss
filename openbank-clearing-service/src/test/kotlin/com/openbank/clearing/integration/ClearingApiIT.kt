// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clearing.integration

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
import java.math.BigDecimal
import java.util.UUID

/**
 * End-to-end clearing lifecycle against the real HTTP API with real Postgres (Flyway-migrated)
 * and Redpanda (issue #578 per-job Testcontainers pattern):
 *
 *   submit payment -> PENDING item -> trigger clearing cycle (batch aggregation, NET totals)
 *   -> settle batch -> SETTLED + settlement positions readable for the cycle.
 *
 * Money-path service: this is the gross/net settlement spine, so the aggregate amounts are
 * asserted exactly (BigDecimal compareTo, not float equality).
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.clearing.it.PostgresRedpandaRedisTestResource::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ClearingApiIT {

    companion object {
        private val paymentId1 = UUID.randomUUID()
        private val paymentId2 = UUID.randomUUID()
        private var itemId1: String? = null
        private var batchId: String? = null
        private var cycleId: String? = null
    }

    private fun submitPayload(paymentId: UUID, reference: String, amount: String) = """
        {
          "paymentId": "$paymentId",
          "paymentReference": "$reference",
          "debtorIban": "CZ6508000000192000145399",
          "creditorIban": "DE89370400440532013000",
          "debtorBic": "GIBACZPX",
          "creditorBic": "COBADEFF",
          "amount": "$amount",
          "currency": "EUR",
          "rail": "SEPA_SCT",
          "endToEndId": "E2E-$reference",
          "remittanceInfo": "ApiIT $reference"
        }
    """.trimIndent()

    @Test
    @Order(1)
    fun `GET info returns service metadata`() {
        val body = (Given { this } When { get("/api/v1/info") } Then { statusCode(200) }).extract().body().asString()
        assertThat(body).contains("openbank-clearing-service")
    }

    @Test
    @Order(2)
    fun `GET health ready returns UP`() {
        Given { this } When { get("/q/health/ready") } Then { statusCode(200) }
    }

    @Test
    @Order(3)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_PAYMENTS"])
    fun `POST submit accepts payment for clearing and returns PENDING item`() {
        val response = Given {
            contentType("application/json")
            body(submitPayload(paymentId1, "PAY-IT-001", "100.50"))
        } When {
            post("/api/v1/clearing/submit")
        } Then {
            statusCode(201)
            body("id", notNullValue())
            body("paymentId", equalTo(paymentId1.toString()))
            body("status", equalTo("PENDING"))
            body("currency", equalTo("EUR"))
        }

        itemId1 = response.extract().body().jsonPath().getString("id")
        assertThat(itemId1).isNotNull
    }

    @Test
    @Order(4)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_PAYMENTS"])
    fun `POST submit accepts a second payment`() {
        Given {
            contentType("application/json")
            body(submitPayload(paymentId2, "PAY-IT-002", "200.25"))
        } When {
            post("/api/v1/clearing/submit")
        } Then {
            statusCode(201)
            body("status", equalTo("PENDING"))
        }
    }

    @Test
    @Order(5)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_VIEWER"])
    fun `GET item by id returns submitted item`() {
        val id = itemId1 ?: return
        Given { this } When {
            get("/api/v1/clearing/items/$id")
        } Then {
            statusCode(200)
            body("id", equalTo(id))
            body("paymentReference", equalTo("PAY-IT-001"))
        }
    }

    @Test
    @Order(6)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_VIEWER"])
    fun `GET items by payment id returns submitted item`() {
        val response = Given { this } When {
            get("/api/v1/clearing/items/by-payment/$paymentId1")
        } Then { statusCode(200) }
        val body = response.extract().body().asString()
        assertThat(body).contains(paymentId1.toString()).contains("PAY-IT-001")
    }

    @Test
    @Order(7)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_VIEWER"])
    fun `GET item by unknown id returns 404`() {
        Given { this } When {
            get("/api/v1/clearing/items/${UUID.randomUUID()}")
        } Then {
            statusCode(404)
        }
    }

    @Test
    @Order(8)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_PAYMENTS"])
    fun `POST cycle trigger aggregates pending items into a NET batch with exact totals`() {
        val response = Given { contentType("application/json") } When {
            post("/api/v1/clearing/cycle/trigger?rail=SEPA_SCT")
        } Then {
            statusCode(200)
            body("id", notNullValue())
            body("status", equalTo("IN_CLEARING"))
            body("settlementType", equalTo("NET"))
            body("itemCount", equalTo(2))
            body("cycleId", notNullValue())
        }

        val json = response.extract().body().jsonPath()
        batchId = json.getString("id")
        cycleId = json.getString("cycleId")
        // Money-path: batch totals must equal the exact sum of the cleared items.
        assertThat(BigDecimal(json.getString("totalDebit"))).isEqualByComparingTo(BigDecimal("300.75"))
        assertThat(BigDecimal(json.getString("totalCredit"))).isEqualByComparingTo(BigDecimal("300.75"))
        assertThat(BigDecimal(json.getString("netPosition"))).isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    @Order(9)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_VIEWER"])
    fun `GET batch by id returns the in-clearing batch`() {
        val id = batchId ?: return
        Given { this } When {
            get("/api/v1/clearing/batches/$id")
        } Then {
            statusCode(200)
            body("id", equalTo(id))
            body("status", equalTo("IN_CLEARING"))
            body("rail", equalTo("SEPA_SCT"))
        }
    }

    @Test
    @Order(10)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_VIEWER"])
    fun `GET batch items returns both items assigned to the batch`() {
        val id = batchId ?: return
        Given { this } When {
            get("/api/v1/clearing/batches/$id/items")
        } Then {
            statusCode(200)
            body("size()", equalTo(2))
            body("status", equalTo(listOf("IN_CLEARING", "IN_CLEARING")))
        }
    }

    @Test
    @Order(11)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_VIEWER"])
    fun `GET batches filtered by status lists the batch`() {
        val response = Given {
            queryParam("status", "IN_CLEARING")
        } When {
            get("/api/v1/clearing/batches")
        } Then { statusCode(200) }
        val body = response.extract().body().asString()
        assertThat(body).contains(batchId ?: "")
    }

    @Test
    @Order(12)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_PAYMENTS"])
    fun `POST settle marks the batch SETTLED`() {
        val id = batchId ?: return
        val response = Given { contentType("application/json") } When {
            post("/api/v1/clearing/batches/$id/settle")
        } Then {
            statusCode(200)
            body("id", equalTo(id))
            body("status", equalTo("SETTLED"))
            body("settledAt", notNullValue())
        }
        val json = response.extract().body().jsonPath()
        assertThat(BigDecimal(json.getString("totalDebit"))).isEqualByComparingTo(BigDecimal("300.75"))
    }

    @Test
    @Order(13)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_VIEWER"])
    fun `GET positions for the cycle returns 200`() {
        val cycle = cycleId ?: return
        // Settlement-position netting per participant BIC is not wired into the cycle yet;
        // the endpoint must still answer the cycle query (empty list, not an error).
        Given { this } When {
            get("/api/v1/clearing/positions/$cycle")
        } Then {
            statusCode(200)
        }
    }

    @Test
    @Order(14)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_PAYMENTS"])
    fun `POST cycle trigger with no pending items settles an empty batch`() {
        Given { contentType("application/json") } When {
            post("/api/v1/clearing/cycle/trigger?rail=SEPA_SCT")
        } Then {
            statusCode(200)
            body("status", equalTo("SETTLED"))
            body("itemCount", equalTo(0))
        }
    }
}
