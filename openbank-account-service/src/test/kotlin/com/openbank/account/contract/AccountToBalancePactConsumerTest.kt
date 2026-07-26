// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Consumer-driven contract for the three balance-service calls account-service makes
 * ([com.openbank.account.infrastructure.client.BalanceServiceRestClient], ADR-0063 P2):
 * - GET /api/v1/balances/{accountId}          — list all balances for an account
 * - GET /api/v1/balances/{accountId}/{currency} — single currency balance
 * - POST /api/v1/balances/{accountId}/initialize — create the opening balance on account open
 *
 * Distinct accountIds per interaction avoid unique-constraint collisions when the provider
 * replays all three against the same Testcontainer DB. The provider verification is in
 * BalancePactProviderVerificationTest (balance-service).
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-balance-service", pactVersion = PactSpecVersion.V3)
class AccountToBalancePactConsumerTest {

    // Distinct per-interaction IDs — must match BalancePactProviderVerificationTest @State seeds.
    private val listAccountId = "d4d4d4d4-d4d4-d4d4-d4d4-d4d4d4d4d4d4"
    private val singleAccountId = "d5d5d5d5-d5d5-d5d5-d5d5-d5d5d5d5d5d5"
    private val initAccountId = "e5e5e5e5-e5e5-e5e5-e5e5-e5e5e5e5e5e5"

    private val initBody = """{"currency":"CZK","initialAmount":0.00,"arrangedOverdraftLimit":0.00}"""

    // --- GET /api/v1/balances/{accountId} ---

    @Pact(consumer = "openbank-account-service", provider = "openbank-balance-service")
    fun getBalancesPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("balances exist for the balance account")
        .uponReceiving("GET all balances for an account")
        .path("/api/v1/balances/$listAccountId")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.array("balances") { a ->
                    a.`object` { b ->
                        b.uuid("accountId")
                        // stringType, DELIBERATELY (issue #2425): this endpoint returns EVERY
                        // balance the account holds and the request names no currency, so an
                        // account may legitimately answer with CZK, EUR and USD rows. Pinning
                        // the value here would assert "every balance is CZK", which is a
                        // stronger claim than the contract makes. The two single-currency
                        // interactions below DO pin it — there the currency is an echo of the
                        // request.
                        b.stringType("currency", "CZK")
                        b.decimalType("bookedAmount", 5000.00)
                        b.decimalType("availableAmount", 4900.00)
                        b.decimalType("reservedAmount", 100.00)
                        b.decimalType("pendingAmount", 0.00)
                        b.decimalType("arrangedOverdraftLimit", 0.00)
                        b.stringType("updatedAt", "2026-01-15T10:00:00Z")
                    }
                }
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "getBalancesPact")
    fun `getBalances returns the balances envelope with at least one balance entry`(mockServer: MockServer) {
        val body = given()
            .baseUri(mockServer.getUrl())
            .get("/api/v1/balances/$listAccountId")
            .then()
            .statusCode(200)
            .extract().jsonPath()

        assertThat(body.getList<Any>("balances")).isNotEmpty()
        assertThat(body.getString("balances[0].currency")).isNotBlank()
    }

    // --- GET /api/v1/balances/{accountId}/{currency} ---

    @Pact(consumer = "openbank-account-service", provider = "openbank-balance-service")
    fun getBalancePact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("a CZK balance exists for the balance account")
        .uponReceiving("GET single CZK balance for an account")
        .path("/api/v1/balances/$singleAccountId/CZK")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.uuid("accountId")
                // stringValue, NOT stringType: the currency is a path segment of the request
                // (/balances/{accountId}/CZK), so the response echoing a DIFFERENT currency is a
                // real defect — the consumer would read a EUR balance as the account's CZK
                // cover. A type matcher accepted any string and made the check vacuous (#2425).
                o.stringValue("currency", "CZK")
                o.decimalType("bookedAmount", 5000.00)
                o.decimalType("availableAmount", 4900.00)
                o.decimalType("reservedAmount", 100.00)
                o.decimalType("pendingAmount", 0.00)
                o.decimalType("arrangedOverdraftLimit", 0.00)
                o.stringType("updatedAt", "2026-01-15T10:00:00Z")
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "getBalancePact")
    fun `getBalance returns the BalanceDto for a specific currency`(mockServer: MockServer) {
        val body = given()
            .baseUri(mockServer.getUrl())
            .get("/api/v1/balances/$singleAccountId/CZK")
            .then()
            .statusCode(200)
            .extract().jsonPath()

        assertThat(body.getString("currency")).isEqualTo("CZK")
        assertThat(body.getString("accountId")).isNotBlank()
    }

    // --- POST /api/v1/balances/{accountId}/initialize ---

    @Pact(consumer = "openbank-account-service", provider = "openbank-balance-service")
    fun initializeBalancePact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("no CZK balance exists for the initialize account")
        .uponReceiving("POST initialize opening balance for an account")
        .path("/api/v1/balances/$initAccountId/initialize")
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(initBody)
        .willRespondWith()
        .status(201)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.uuid("accountId")
                // stringValue, NOT stringType: the request body asks to open a CZK balance, so
                // the response currency is an echo of it — a different currency back means the
                // provider opened the wrong pocket (#2425).
                o.stringValue("currency", "CZK")
                o.decimalType("bookedAmount", 0.00)
                o.decimalType("availableAmount", 0.00)
                o.decimalType("reservedAmount", 0.00)
                o.decimalType("pendingAmount", 0.00)
                o.decimalType("arrangedOverdraftLimit", 0.00)
                o.stringType("updatedAt", "2026-01-15T10:00:00Z")
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "initializeBalancePact")
    fun `initialize returns 201 with the opening BalanceDto`(mockServer: MockServer) {
        val body = given()
            .baseUri(mockServer.getUrl())
            .contentType("application/json")
            .body(initBody)
            .post("/api/v1/balances/$initAccountId/initialize")
            .then()
            .statusCode(201)
            .extract().jsonPath()

        assertThat(body.getString("accountId")).isNotBlank()
        assertThat(body.getString("currency")).isEqualTo("CZK")
    }
}
