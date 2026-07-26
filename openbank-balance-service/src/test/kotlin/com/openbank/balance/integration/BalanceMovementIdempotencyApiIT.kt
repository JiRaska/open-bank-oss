// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.integration

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.restassured.response.ExtractableResponse
import io.restassured.response.Response
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.math.BigDecimal
import java.util.UUID

/**
 * End-to-end HTTP integration test for the DIRECT credit/debit money-movement path — the path the
 * transaction saga retries under at-least-once delivery. Locks the referenceId idempotency ledger
 * (V8 + BalanceMovementPort): the same (account, currency, referenceId, operation) applied twice
 * must move money exactly ONCE; a different referenceId with the same amount must apply again; and
 * the overdraft guard runs through the same path (insufficient funds → 422, never a partial post).
 * Real Postgres with Flyway V1..V8 via Testcontainers (#578); no downstream service is called.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.balance.it.PostgresRedpandaTestResource::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class BalanceMovementIdempotencyApiIT {

    companion object {
        private val accountId: UUID = UUID.randomUUID()
        private const val CREDIT_REF = "saga-credit-0001"
        private const val DEBIT_REF = "saga-debit-0001"

        private fun amount(response: ExtractableResponse<Response>, path: String): BigDecimal =
            BigDecimal(response.body().jsonPath().getString(path))
    }

    private fun currentBooked(): BigDecimal {
        val response = (
            Given { this } When {
                get("/api/v1/balances/$accountId/CZK")
            } Then { statusCode(200) }
            ).extract()
        return amount(response, "bookedAmount")
    }

    private fun postMovement(operation: String, amount: String, referenceId: String): ExtractableResponse<Response> = (
        Given {
            contentType("application/json")
            body("""{"amount": "$amount", "currency": "CZK", "referenceId": "$referenceId"}""")
        } When {
            post("/api/v1/balances/$accountId/$operation")
        } Then {
            statusCode(200)
        }
        ).extract()

    @Test
    @Order(1)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_API"])
    fun `initialize the CZK pocket with a zero balance`() {
        Given {
            contentType("application/json")
            body("""{"currency": "CZK"}""")
        } When {
            post("/api/v1/balances/$accountId/initialize")
        } Then {
            statusCode(201)
            body("currency", equalTo("CZK"))
        }
        assertThat(currentBooked()).isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    @Order(2)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_API"])
    fun `credit with a referenceId books the funds once`() {
        val response = postMovement("credit", "250.00", CREDIT_REF)
        assertThat(amount(response, "bookedAmount")).isEqualByComparingTo(BigDecimal("250.00"))
        assertThat(amount(response, "availableAmount")).isEqualByComparingTo(BigDecimal("250.00"))
    }

    @Test
    @Order(3)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_API"])
    fun `credit replay with the SAME referenceId is NOT re-applied (one posting)`() {
        // At-least-once redelivery: same (account, currency, referenceId, CREDIT) → marker row in
        // balance_movement (V8) short-circuits, the booked amount must not double.
        val replay = postMovement("credit", "250.00", CREDIT_REF)
        assertThat(amount(replay, "bookedAmount")).isEqualByComparingTo(BigDecimal("250.00"))
        assertThat(currentBooked()).isEqualByComparingTo(BigDecimal("250.00"))
    }

    @Test
    @Order(4)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_API"])
    fun `credit with a DIFFERENT referenceId applies again (dedup keys on reference, not amount)`() {
        val response = postMovement("credit", "250.00", "saga-credit-0002")
        assertThat(amount(response, "bookedAmount")).isEqualByComparingTo(BigDecimal("500.00"))
    }

    @Test
    @Order(5)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_API"])
    fun `debit with a referenceId books the outflow once`() {
        val response = postMovement("debit", "100.00", DEBIT_REF)
        assertThat(amount(response, "bookedAmount")).isEqualByComparingTo(BigDecimal("400.00"))
        assertThat(amount(response, "availableAmount")).isEqualByComparingTo(BigDecimal("400.00"))
    }

    @Test
    @Order(6)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_API"])
    fun `debit replay with the SAME referenceId is NOT re-applied (one posting)`() {
        val replay = postMovement("debit", "100.00", DEBIT_REF)
        assertThat(amount(replay, "bookedAmount")).isEqualByComparingTo(BigDecimal("400.00"))
        assertThat(currentBooked()).isEqualByComparingTo(BigDecimal("400.00"))
    }

    @Test
    @Order(7)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_API"])
    fun `debit and credit with the SAME referenceId are independent operations`() {
        // The V8 idempotency key includes the operation: a CREDIT marker must not swallow a DEBIT
        // that happens to reuse the reference string (and vice versa).
        val response = postMovement("debit", "50.00", CREDIT_REF)
        assertThat(amount(response, "bookedAmount")).isEqualByComparingTo(BigDecimal("350.00"))
    }

    @Test
    @Order(8)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_API"])
    fun `debit beyond available funds returns 422 and books nothing`() {
        Given {
            contentType("application/json")
            body("""{"amount": "10000.00", "currency": "CZK", "referenceId": "saga-debit-overdraw"}""")
        } When {
            post("/api/v1/balances/$accountId/debit")
        } Then {
            statusCode(422)
            body("error", equalTo("INSUFFICIENT_FUNDS"))
        }
        assertThat(currentBooked()).isEqualByComparingTo(BigDecimal("350.00"))
    }

    @Test
    @Order(9)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_API"])
    fun `a rejected debit leaves the referenceId free - the retry with funds succeeds`() {
        // The idempotency marker is written in the same transaction as the mutation, so a FAILED
        // attempt must not poison the referenceId for the saga's later (funded) retry.
        val response = postMovement("debit", "350.00", "saga-debit-overdraw")
        assertThat(amount(response, "bookedAmount")).isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    @Order(10)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_API"])
    fun `credit to an unknown currency pocket returns 404, no implicit pocket creation`() {
        Given {
            contentType("application/json")
            body("""{"amount": "10.00", "currency": "USD", "referenceId": "saga-credit-usd"}""")
        } When {
            post("/api/v1/balances/$accountId/credit")
        } Then {
            statusCode(404)
            body("error", equalTo("NOT_FOUND"))
        }
    }
}
