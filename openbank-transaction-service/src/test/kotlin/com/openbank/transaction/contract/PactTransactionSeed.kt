// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.contract

import io.restassured.RestAssured.given

/**
 * Provider-state seeding shared by the git-pact and broker verification classes (keep the two in sync).
 *
 * mcp-service's transaction-list pact used to replay against an EMPTY page, so its element shape -
 * including the `currency` field the provider has never sent (it sends `currencyCode`) - was never
 * compared with anything (#13225). Seeding one real transaction for a dedicated account makes the
 * replay check the element. Through the real REST route, with the verification class's own test
 * identity, so the row is exactly what a booking produces; the fixed idempotency key makes a repeated
 * state call replay the same transaction instead of adding a second one.
 */
object PactTransactionSeed {
    const val SEEDED_STATE = "transaction-service holds a transaction for the pact account"

    /** mcp-service's `TransactionListPactConsumerTest.PACT_SEEDED_ACCOUNT_ID`. */
    const val SEEDED_ACCOUNT_ID = "e2e2e2e2-f3f3-4b4b-8c8c-d0d0d0d0d0d0"

    fun seed(port: Int) {
        given()
            .baseUri("http://localhost:$port")
            .contentType("application/json")
            .body(
                """
                {
                  "idempotencyKey": "pact-mcp-list-seed",
                  "type": "DEBIT",
                  "sourceAccountId": "$SEEDED_ACCOUNT_ID",
                  "amount": 42.00,
                  "currencyCode": "CZK",
                  "description": "pact seed for the MCP transaction list",
                  "valueDate": "2026-01-20",
                  "rail": "INTERNAL",
                  "instructionType": "ONE_OFF"
                }
                """.trimIndent(),
            )
            .post("/api/v1/transactions")
            .then()
            .statusCode(201)
    }
}
