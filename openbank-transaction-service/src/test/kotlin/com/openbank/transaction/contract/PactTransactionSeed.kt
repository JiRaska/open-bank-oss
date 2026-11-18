// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.contract

import io.restassured.RestAssured.given
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

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

    /**
     * Deployed and main consumer versions share these keys but not their request bodies.
     * An existing row correctly wins an idempotent replay, so every provider state must own
     * fresh synthetic data. Never change runtime idempotency or rewrite a consumer request.
     */
    fun resetInitiationState(dataSource: DataSource) {
        dataSource.connection.use { connection ->
            check(connection.catalog == "openbank_transactions_it") {
                "Pact state reset requires the isolated transaction Testcontainers database"
            }
            connection.autoCommit = false
            try {
                val registryExists = hasIdempotencyRegistry(connection)
                // Fixed key AND synthetic source account; never a caller-supplied identifier.
                listOf(
                    "pact-domestic-txn-001" to "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                    "pact-sepa-txn-001" to "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                ).forEach { (key, account) ->
                    resetInitiationKey(connection, key, UUID.fromString(account), registryExists)
                }
                connection.commit()
            } catch (error: Exception) {
                connection.rollback()
                throw error
            }
        }
    }

    private fun hasIdempotencyRegistry(connection: Connection): Boolean =
        connection.createStatement().use { statement ->
            statement.executeQuery("select to_regclass('transaction_idempotency_keys') is not null").use { rows ->
                rows.next()
                rows.getBoolean(1)
            }
        }

    private fun resetInitiationKey(connection: Connection, key: String, account: UUID, registryExists: Boolean) {
        val predicates = "t.idempotency_key = ? and t.source_account_id = ?"
        val deletes = mutableListOf(
            "delete from transaction_outbox o using transactions t " +
                "where o.aggregate_id = t.id and $predicates",
        )
        // Old deployed P predates V22. On current P remove only claims pointing
        // at this exact synthetic row; unrelated/retained claims remain intact.
        if (registryExists) {
            deletes += "delete from transaction_idempotency_keys r using transactions t " +
                "where r.idempotency_key = t.idempotency_key and r.transaction_id = t.id " +
                "and r.booking_date = t.booking_date and $predicates"
        }
        deletes += "delete from transactions t where $predicates"
        deletes.forEach { sql ->
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, key)
                statement.setObject(2, account)
                statement.executeUpdate()
            }
        }
    }

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
