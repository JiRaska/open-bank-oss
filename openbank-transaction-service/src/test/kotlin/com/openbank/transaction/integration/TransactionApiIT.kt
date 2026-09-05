// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.integration

import com.openbank.libs.testing.trace.RecordingSpanExporter
import com.openbank.transaction.application.usecase.TransactionService
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
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
import java.time.LocalDate
import java.util.UUID

@QuarkusTest
@QuarkusTestResource(com.openbank.transaction.it.PostgresRedpandaTestResource::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class TransactionApiIT {

    @Inject
    lateinit var transactionService: TransactionService

    companion object {
        private val sourceAccountId = UUID.randomUUID()
        private val targetAccountId = UUID.randomUUID()
        private var createdTransactionId: String? = null
        private val today = LocalDate.now().toString()
    }

    @Test
    @Order(1)
    fun `GET info returns service metadata`() {
        val body = (Given { this } When { get("/api/v1/info") } Then { statusCode(200) }).extract().body().asString()
        assertThat(body).contains("openbank-transaction-service")
    }

    @Test
    @Order(2)
    fun `GET health ready returns UP`() {
        Given { this } When { get("/q/health/ready") } Then { statusCode(200) }
    }

    @Test
    @Order(3)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `POST transactions creates CREDIT transaction`() {
        val payload = """
            {
              "idempotencyKey": "${UUID.randomUUID()}",
              "type": "CREDIT",
              "targetAccountId": "$targetAccountId",
              "amount": "5000.00",
              "currencyCode": "CZK",
              "baseAmount": "5000.00",
              "baseCurrencyCode": "CZK",
              "description": "Initial deposit",
              "valueDate": "$today",
              "bookingDate": "$today"
            }
        """.trimIndent()

        val response = Given {
            contentType("application/json")
            body(payload)
        } When {
            post("/api/v1/transactions")
        } Then {
            statusCode(201)
            body("id", notNullValue())
            body("type", equalTo("CREDIT"))
            body("amount", equalTo(5000.0f))
            body("currencyCode", equalTo("CZK"))
        }

        createdTransactionId = response.extract().body().jsonPath().getString("id")
        assertThat(createdTransactionId).isNotNull
    }

    @Test
    @Order(4)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_VIEWER"])
    fun `GET transaction by id returns created transaction`() {
        val id = createdTransactionId ?: return
        Given { this } When {
            get("/api/v1/transactions/$id")
        } Then {
            statusCode(200)
            body("id", equalTo(id))
            body("type", equalTo("CREDIT"))
        }
    }

    @Test
    @Order(5)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `POST transactions creates TRANSFER between accounts`() {
        val payload = """
            {
              "idempotencyKey": "${UUID.randomUUID()}",
              "type": "TRANSFER",
              "sourceAccountId": "$sourceAccountId",
              "targetAccountId": "$targetAccountId",
              "amount": "1000.00",
              "currencyCode": "CZK",
              "baseAmount": "1000.00",
              "baseCurrencyCode": "CZK",
              "description": "Internal transfer",
              "valueDate": "$today",
              "bookingDate": "$today"
            }
        """.trimIndent()

        Given {
            contentType("application/json")
            body(payload)
        } When {
            post("/api/v1/transactions")
        } Then {
            statusCode(201)
            body("type", equalTo("TRANSFER"))
            body("sourceAccountId", equalTo(sourceAccountId.toString()))
            body("targetAccountId", equalTo(targetAccountId.toString()))
        }
    }

    @Test
    @Order(6)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_VIEWER"])
    fun `GET transactions search by accountId returns results`() {
        Given {
            queryParam("accountId", targetAccountId.toString())
            queryParam("limit", 20)
        } When {
            get("/api/v1/transactions/search")
        } Then {
            statusCode(200)
        }
        val body = (
            Given {
                queryParam("accountId", targetAccountId.toString())
            } When {
                get("/api/v1/transactions/search")
            } Then { statusCode(200) }
            ).extract().body().asString()
        assertThat(body).contains(targetAccountId.toString())
    }

    @Test
    @Order(7)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_VIEWER"])
    fun `GET transactions by unknown id returns 404`() {
        Given { this } When {
            get("/api/v1/transactions/${UUID.randomUUID()}")
        } Then {
            statusCode(404)
        }
    }

    @Test
    @Order(8)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `POST transactions rejects negative amount`() {
        val payload = """
            {
              "idempotencyKey": "${UUID.randomUUID()}",
              "type": "DEBIT",
              "sourceAccountId": "$sourceAccountId",
              "amount": "-100.00",
              "currencyCode": "CZK",
              "baseAmount": "-100.00",
              "baseCurrencyCode": "CZK",
              "description": "Invalid negative",
              "valueDate": "$today",
              "bookingDate": "$today"
            }
        """.trimIndent()

        Given {
            contentType("application/json")
            body(payload)
        } When {
            post("/api/v1/transactions")
        } Then {
            statusCode(422)
        }
    }

    @Test
    @Order(9)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `POST idempotency same key returns same transaction`() {
        val idempotencyKey = UUID.randomUUID().toString()
        val payload = """
            {
              "idempotencyKey": "$idempotencyKey",
              "type": "CREDIT",
              "targetAccountId": "$targetAccountId",
              "amount": "250.00",
              "currencyCode": "EUR",
              "baseAmount": "250.00",
              "baseCurrencyCode": "EUR",
              "description": "Idempotency test",
              "valueDate": "$today",
              "bookingDate": "$today"
            }
        """.trimIndent()

        val id1 = (
            Given {
                contentType("application/json")
                body(payload)
            } When { post("/api/v1/transactions") } Then { statusCode(201) }
            ).extract().body().jsonPath().getString("id")

        val id2 = (
            Given {
                contentType("application/json")
                body(payload)
            } When { post("/api/v1/transactions") } Then { statusCode(201) }
            ).extract().body().jsonPath().getString("id")

        assertThat(id1).isEqualTo(id2)
    }

    @Test
    @Order(10)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `real transaction initiation emits a bounded trace contract`() {
        val exporter = RecordingSpanExporter()
        val provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()
        try {
            transactionService.tracer = provider.get("transaction-trace-contract-it")
            val payload = """
                {
                  "idempotencyKey": "${UUID.randomUUID()}",
                  "type": "CREDIT",
                  "targetAccountId": "${UUID.randomUUID()}",
                  "amount": "10.00",
                  "currencyCode": "CZK",
                  "baseAmount": "10.00",
                  "baseCurrencyCode": "CZK",
                  "description": "Trace contract transaction",
                  "valueDate": "$today",
                  "bookingDate": "$today"
                }
            """.trimIndent()

            Given {
                contentType("application/json")
                body(payload)
            } When {
                post("/api/v1/transactions")
            } Then {
                statusCode(201)
            }

            exporter.contract()
                .requiresSpan("transaction.initiate")
                .requiresAttribute("transaction.initiate", "openbank.transaction.status")
                .hasNoErrorSpan()
                .verifiedAs("transaction-initiate")
        } finally {
            provider.close()
        }
    }
}
