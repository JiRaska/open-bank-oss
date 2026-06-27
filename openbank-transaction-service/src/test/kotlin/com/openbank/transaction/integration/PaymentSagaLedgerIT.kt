// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.integration

import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.time.LocalDate
import java.util.UUID
import com.github.tomakehurst.wiremock.client.WireMock.equalTo as wmEqualTo

// Exercises the real transaction -> PaymentSaga -> HTTP -> ledger path that the heavily
// mocked TransactionServiceTest never touches: DTO contract, oidc-client token attachment,
// reactive (non-blocking) call, and the compensation branch on a ledger failure.
//
// LedgerCallGuard.postJournal carries a shared @CircuitBreaker (requestVolumeThreshold=5,
// delay=10s). The ledger-failure test trips failures into that rolling window, so it MUST run
// last (@Order) — otherwise its open circuit fails the success-expecting tests that follow within
// the 10s window. Each success test makes one clean postJournal, so the circuit stays closed for
// them. Any new failure-injecting test must likewise be ordered after the success tests.
@QuarkusTest
@QuarkusTestResource(LedgerWireMockResource::class)
@QuarkusTestResource(com.openbank.transaction.it.PostgresRedpandaTestResource::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class PaymentSagaLedgerIT {

    private val today = LocalDate.now().toString()

    private val cashClearing = "a0000000-0000-0000-0000-000000000001"
    private val depositControl = "a0000000-0000-0000-0000-000000000002"

    private fun creditPayload() = """
        {
          "idempotencyKey": "${UUID.randomUUID()}",
          "type": "CREDIT",
          "targetAccountId": "${UUID.randomUUID()}",
          "amount": "1250.50",
          "currencyCode": "CZK",
          "baseAmount": "1250.50",
          "baseCurrencyCode": "CZK",
          "description": "Saga ledger contract",
          "valueDate": "$today",
          "bookingDate": "$today"
        }
    """.trimIndent()

    private fun outboundPayload(source: UUID) = """
        {
          "idempotencyKey": "${UUID.randomUUID()}",
          "type": "DEBIT",
          "sourceAccountId": "$source",
          "amount": "900.00",
          "currencyCode": "CZK",
          "baseAmount": "900.00",
          "baseCurrencyCode": "CZK",
          "description": "Outbound settlement",
          "valueDate": "$today",
          "bookingDate": "$today"
        }
    """.trimIndent()

    private fun internalTransferPayload(source: UUID, target: UUID) = """
        {
          "idempotencyKey": "${UUID.randomUUID()}",
          "type": "TRANSFER",
          "sourceAccountId": "$source",
          "targetAccountId": "$target",
          "amount": "300.00",
          "currencyCode": "CZK",
          "baseAmount": "300.00",
          "baseCurrencyCode": "CZK",
          "description": "Internal transfer",
          "valueDate": "$today",
          "bookingDate": "$today"
        }
    """.trimIndent()

    @Test
    @Order(1)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `successful ledger posting drives transaction to COMPLETED with balanced double-entry journal`() {
        LedgerWireMockResource.server.resetRequests()
        LedgerWireMockResource.stubLedgerSuccess()

        Given {
            contentType("application/json")
            body(creditPayload())
        } When {
            post("/api/v1/transactions")
        } Then {
            statusCode(201)
            body("status", equalTo("COMPLETED"))
        }

        // Contract assertion: the saga must post exactly one balanced journal carrying the
        // well-known GL clearing/control accounts, the SYSTEM actor, and a saga-scoped
        // idempotency key — the field names ledger requires (notably createdBy).
        LedgerWireMockResource.server.verify(
            1,
            postRequestedFor(urlEqualTo(LedgerWireMockResource.JOURNALS_PATH))
                .withRequestBody(matchingJsonPath("$.createdBy", wmEqualTo("00000000-0000-0000-0000-000000000001")))
                .withRequestBody(matchingJsonPath("$.idempotencyKey", containing("saga-")))
                .withRequestBody(
                    matchingJsonPath(
                        "$.lines[?(@.side == 'DEBIT' && @.glAccountId == 'a0000000-0000-0000-0000-000000000001')]",
                    ),
                )
                .withRequestBody(
                    matchingJsonPath(
                        "$.lines[?(@.side == 'CREDIT' && @.glAccountId == 'a0000000-0000-0000-0000-000000000002')]",
                    ),
                )
                .withRequestBody(matchingJsonPath("$.lines[?(@.currencyCode == 'CZK' && @.amount == 1250.50)]")),
        )
    }

    @Test
    @Order(2)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `outbound payment debits the payer deposit-control and reserves a cover hold`() {
        // The settlement-direction regression (ADR-0039): an outbound (source-only) payment must DEBIT
        // the payer's deposit-control sub-ledger (booked −amount via the projection), CREDIT bank
        // cash-clearing — NOT credit the payer. The saga also places a cover hold on the source.
        LedgerWireMockResource.server.resetRequests()
        LedgerWireMockResource.stubLedgerSuccess()
        LedgerWireMockResource.stubBalanceCoverSuccess()
        val source = UUID.randomUUID()

        Given {
            contentType("application/json")
            body(outboundPayload(source))
        } When {
            post("/api/v1/transactions")
        } Then {
            statusCode(201)
            body("status", equalTo("COMPLETED"))
        }

        LedgerWireMockResource.server.verify(
            1,
            postRequestedFor(urlEqualTo(LedgerWireMockResource.JOURNALS_PATH))
                // Payer deposit-control is DEBITed and carries the source sub-account.
                .withRequestBody(
                    matchingJsonPath(
                        "$.lines[?(@.side == 'DEBIT' && @.glAccountId == '$depositControl' && " +
                            "@.subAccountId == '$source')]",
                    ),
                )
                // Bank cash-clearing is the CREDIT counter-leg (no sub-account).
                .withRequestBody(
                    matchingJsonPath("$.lines[?(@.side == 'CREDIT' && @.glAccountId == '$cashClearing')]"),
                ),
        )
        // Synchronous overspend gate: a cover hold is placed on the source pocket.
        LedgerWireMockResource.server.verify(1, postRequestedFor(urlPathMatching("/api/v1/balances/[^/]+/holds")))
    }

    @Test
    @Order(3)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `internal transfer moves the sub-ledger from source to target on two deposit-control legs`() {
        // Same-currency internal transfer: two deposit-control legs (DEBIT source, CREDIT target) so
        // the projection moves booked from source (−) to target (+); no bank cash-clearing leg.
        LedgerWireMockResource.server.resetRequests()
        LedgerWireMockResource.stubLedgerSuccess()
        LedgerWireMockResource.stubBalanceCoverSuccess()
        val source = UUID.randomUUID()
        val target = UUID.randomUUID()

        Given {
            contentType("application/json")
            body(internalTransferPayload(source, target))
        } When {
            post("/api/v1/transactions")
        } Then {
            statusCode(201)
            body("status", equalTo("COMPLETED"))
        }

        LedgerWireMockResource.server.verify(
            1,
            postRequestedFor(urlEqualTo(LedgerWireMockResource.JOURNALS_PATH))
                .withRequestBody(
                    matchingJsonPath(
                        "$.lines[?(@.side == 'DEBIT' && @.glAccountId == '$depositControl' && " +
                            "@.subAccountId == '$source')]",
                    ),
                )
                .withRequestBody(
                    matchingJsonPath(
                        "$.lines[?(@.side == 'CREDIT' && @.glAccountId == '$depositControl' && " +
                            "@.subAccountId == '$target')]",
                    ),
                ),
        )
    }

    @Test
    @Order(4)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `ledger failure drives saga compensation and marks transaction FAILED`() {
        LedgerWireMockResource.server.resetRequests()
        LedgerWireMockResource.stubLedgerServerError()

        Given {
            contentType("application/json")
            body(creditPayload())
        } When {
            post("/api/v1/transactions")
        } Then {
            statusCode(201)
            body("status", equalTo("FAILED"))
        }
    }
}
