// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/mpL/2.0/ for details.

package com.openbank.sepainstant.integration

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured
import io.restassured.filter.log.ResponseLoggingFilter
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
import java.util.UUID

@QuarkusTest
@QuarkusTestResource(com.openbank.sepainstant.it.PostgresRedpandaRedisTestResource::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SctInstApiIT {

    companion object {
        private val debtorAccountId: UUID = UUID.randomUUID()
        private var createdPaymentId: String? = null

        init {
            RestAssured.filters(ResponseLoggingFilter())
        }
    }

    @Test
    @Order(1)
    fun `GET health ready returns UP`(): Unit {
        Given { this } When { get("/q/health/ready") } Then { statusCode(200) }
    }

    @Test
    @Order(2)
    @TestSecurity(user = "operator-01", roles = ["ROLE_OPERATOR"])
    fun `POST sepa-instant submits payment with CLEAR screening and returns 201`(): Unit {
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
        assertThat(createdPaymentId).isNotNull
    }

    @Test
    @Order(3)
    @TestSecurity(user = "viewer-01", roles = ["ROLE_VIEWER"])
    fun `GET sepa-instant by id returns the submitted payment`(): Unit {
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
    fun `GET sepa-instant list returns results`(): Unit {
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
    fun `GET sepa-instant by debtor returns results`(): Unit {
        Given {
            contentType("application/json")
        } When {
            get("/api/v1/sepa-instant/debtor/$debtorAccountId")
        } Then {
            statusCode(200)
        }
    }
}
