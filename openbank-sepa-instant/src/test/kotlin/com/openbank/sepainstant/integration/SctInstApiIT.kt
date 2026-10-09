// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/mpL/2.0/ for details.

package com.openbank.sepainstant.integration

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured
import io.restassured.filter.log.ResponseLoggingFilter
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.util.UUID
import javax.sql.DataSource

@QuarkusTest
@QuarkusTestResource(
    value = com.openbank.libs.testing.containers.PostgresRedpandaRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_sepa_instant_it")],
)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SctInstApiIT {

    @Inject
    lateinit var dataSource: DataSource

    companion object {
        private val debtorAccountId: UUID = UUID.randomUUID()
        private var createdPaymentId: String? = null
        private var receiptKey: String? = null
        private var submittedPayload: String? = null

        init {
            RestAssured.filters(ResponseLoggingFilter())
        }
    }

    @Test
    @Order(1)
    fun `GET health ready returns UP`() {
        Given { this } When { get("/q/health/ready") } Then { statusCode(200) }
    }

    @Test
    @Order(2)
    @TestSecurity(user = "operator-01", roles = ["ROLE_OPERATOR"])
    fun `POST sepa-instant submits payment with CLEAR screening and returns 201`() {
        val idempotencyKey = UUID.randomUUID().toString()
        val endToEndId = "E2E${System.currentTimeMillis()}"
        val payload = """
            {
              "idempotencyKey": "$idempotencyKey",
              "debtorAccountId": "$debtorAccountId",
              "debtorIban": "CZ6508000000192000145399",
              "debtorName": "Test Debtor",
              "creditorIban": "DE89370400440532013000",
              "creditorName": "Test Creditor",
              "creditorBic": "COBADEFFXXX",
              "amount": 99.99,
              "currency": "EUR",
              "remittanceInfo": "SCT Inst test",
              "endToEndId": "$endToEndId"
            }
        """.trimIndent()

        val response = Given {
            contentType("application/json")
            header("Idempotency-Key", idempotencyKey)
            body(payload)
        } When {
            post("/api/v1/sepa-instant")
        } Then {
            statusCode(201)
            body("paymentId", notNullValue())
            body("debtorIban", equalTo("CZ6508000000192000145399"))
            body("creditorIban", equalTo("DE89370400440532013000"))
            body("currency", equalTo("EUR"))
            body("endToEndId", equalTo(endToEndId))
        }

        createdPaymentId = response.extract().body().jsonPath().getString("paymentId")
        receiptKey = idempotencyKey
        submittedPayload = payload
        assertThat(createdPaymentId).isNotNull
    }

    @Test
    @Order(3)
    @TestSecurity(user = "viewer-01", roles = ["ROLE_VIEWER"])
    fun `GET sepa-instant by id returns the submitted payment`() {
        val id = createdPaymentId ?: return
        Given {
            contentType("application/json")
        } When {
            get("/api/v1/sepa-instant/$id")
        } Then {
            statusCode(200)
            body("paymentId", equalTo(id))
            body("amount", notNullValue())
        }
    }

    @Test
    @Order(4)
    @TestSecurity(user = "viewer-01", roles = ["ROLE_VIEWER"])
    fun `GET sepa-instant list returns results`() {
        Given {
            contentType("application/json")
        } When {
            get("/api/v1/sepa-instant")
        } Then {
            statusCode(200)
        }
    }

    @Test
    @Order(5)
    @TestSecurity(user = "viewer-01", roles = ["ROLE_VIEWER"])
    fun `GET sepa-instant by debtor returns results`() {
        Given {
            contentType("application/json")
        } When {
            get("/api/v1/sepa-instant/debtor/$debtorAccountId")
        } Then {
            statusCode(200)
        }
    }

    @Test
    @Order(6)
    @TestSecurity(user = "operator-01", roles = ["ROLE_OPERATOR"])
    fun `durable receipt resolves and exact replay returns the original payment`() {
        val key = requireNotNull(receiptKey)
        val payload = requireNotNull(submittedPayload)
        val id = requireNotNull(createdPaymentId)
        Given {
            contentType("application/json")
            body("""{"idempotencyKey":"$key","debtorAccountId":"$debtorAccountId"}""")
        } When {
            post("/api/v1/sepa-instant/receipts/lookup")
        } Then {
            statusCode(200)
            body("state", equalTo("FOUND"))
            body("paymentId", equalTo(id))
        }
        Given {
            contentType("application/json")
            header("Idempotency-Key", key)
            body(payload)
        } When {
            post("/api/v1/sepa-instant")
        } Then {
            statusCode(201)
            body("paymentId", equalTo(id))
        }
    }

    @Test
    @Order(7)
    @TestSecurity(user = "operator-02", roles = ["ROLE_OPERATOR"])
    fun `another principal cannot resolve or replay the receipt`() {
        val key = requireNotNull(receiptKey)
        val payload = requireNotNull(submittedPayload)
        Given {
            contentType("application/json")
            body("""{"idempotencyKey":"$key","debtorAccountId":"$debtorAccountId"}""")
        } When {
            post("/api/v1/sepa-instant/receipts/lookup")
        } Then {
            statusCode(200)
            body("state", equalTo("UNKNOWN"))
        }
        Given {
            contentType("application/json")
            header("Idempotency-Key", key)
            body(payload)
        } When {
            post("/api/v1/sepa-instant")
        } Then {
            statusCode(409)
        }
    }

    @Test
    @Order(8)
    @TestSecurity(user = "operator-01", roles = ["ROLE_OPERATOR"])
    fun `receipt rejects a different debit account and a changed payload`() {
        val key = requireNotNull(receiptKey)
        val payload = requireNotNull(submittedPayload)
        Given {
            contentType("application/json")
            body("""{"idempotencyKey":"$key","debtorAccountId":"${UUID.randomUUID()}"}""")
        } When {
            post("/api/v1/sepa-instant/receipts/lookup")
        } Then {
            statusCode(200)
            body("state", equalTo("UNKNOWN"))
        }
        Given {
            contentType("application/json")
            header("Idempotency-Key", key)
            body(payload.replace("\"SCT Inst test\"", "\"changed remittance\""))
        } When {
            post("/api/v1/sepa-instant")
        } Then {
            statusCode(409)
        }
    }

    @Test
    @Order(9)
    @TestSecurity(user = "operator-01", roles = ["ROLE_OPERATOR"])
    fun `Redis replay reads the current durable status`() {
        val key = requireNotNull(receiptKey)
        val payload = requireNotNull(submittedPayload)
        updateReceipt(status = "SETTLED", ready = true)

        Given {
            contentType("application/json")
            header("Idempotency-Key", key)
            body(payload)
        } When {
            post("/api/v1/sepa-instant")
        } Then {
            statusCode(201)
            body("status", equalTo("SETTLED"))
        }
    }

    @Test
    @Order(10)
    @TestSecurity(user = "operator-01", roles = ["ROLE_OPERATOR"])
    fun `an unresolved scheme outcome remains PENDING on reads but has no receipt or replay`() {
        val key = requireNotNull(receiptKey)
        val payload = requireNotNull(submittedPayload)
        updateReceipt(status = "PENDING", ready = false, schemeUnknown = true)
        try {
            Given { contentType("application/json") } When {
                get("/api/v1/sepa-instant/${requireNotNull(createdPaymentId)}")
            } Then {
                statusCode(200)
                body("status", equalTo("PENDING"))
            }
            Given { contentType("application/json") } When {
                get("/api/v1/sepa-instant/debtor/$debtorAccountId")
            } Then {
                statusCode(200)
                body(
                    "find { it.paymentId == '${requireNotNull(createdPaymentId)}' }.status",
                    equalTo("PENDING"),
                )
            }
            Given {
                contentType("application/json")
                body("""{"idempotencyKey":"$key","debtorAccountId":"$debtorAccountId"}""")
            } When {
                post("/api/v1/sepa-instant/receipts/lookup")
            } Then {
                statusCode(200)
                body("state", equalTo("UNKNOWN"))
            }
            Given {
                contentType("application/json")
                header("Idempotency-Key", key)
                body(payload)
            } When {
                post("/api/v1/sepa-instant")
            } Then {
                statusCode(409)
            }
        } finally {
            updateReceipt(status = "SETTLED", ready = true, schemeUnknown = false)
        }
    }

    private fun updateReceipt(status: String, ready: Boolean, schemeUnknown: Boolean = false) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "UPDATE sct_inst_payments SET status = ?, receipt_ready = ?, scheme_outcome_unknown = ? WHERE payment_id = ?",
            ).use { statement ->
                statement.setString(1, status)
                statement.setBoolean(2, ready)
                statement.setBoolean(3, schemeUnknown)
                statement.setObject(4, UUID.fromString(requireNotNull(createdPaymentId)))
                assertThat(statement.executeUpdate()).isEqualTo(1)
            }
        }
    }
}
