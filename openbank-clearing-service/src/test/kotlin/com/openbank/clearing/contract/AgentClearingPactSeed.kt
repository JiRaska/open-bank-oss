// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clearing.contract

import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource

/**
 * Provider-state seed for the agent-service consumer's clearing READ pact
 * (`AgentClearingReadPactConsumerTest`): one SETTLED batch with exactly one SETTLED item, so
 * `GET /api/v1/clearing/batches` and `/batches/{id}/items` both return at least one row and
 * `GET /api/v1/clearing/batches/{id}` answers a pinned 200 for the agent's `clearing_get_batch`
 * tool.
 *
 * Inserted directly against `clearing_batches`/`clearing_items` (V1's schema + V10's
 * `aggregate_revision` + V12's `rail`) because a `@State` callback carries no Vert.x context and
 * this service's writes go through reactive Panache — the documented HR000068 trap. Direct JDBC
 * is the same escape hatch ledger's `NostroPactSeed` and sanctions' `AgentSanctionsPactSeed`
 * use. The batch is SETTLED (not PENDING) so it can never be picked up by a settlement cycle
 * that another test's scheduler might trigger mid-replay.
 *
 * `ON CONFLICT (id) DO NOTHING` makes re-runs safe: pact-jvm 4.7.3 invokes a `@State` method
 * twice, and a bare INSERT would die on the second invocation.
 */
object AgentClearingPactSeed {
    val BATCH_ID: UUID = UUID.fromString("7a7a7a7a-7a7a-4a7a-8a7a-7a7a7a7a7a7a")
    val ITEM_ID: UUID = UUID.fromString("8a8a8a8a-8a8a-4a8a-8a8a-8a8a8a8a8a8a")
    val PAYMENT_ID: UUID = UUID.fromString("9a9a9a9a-9a9a-4a9a-8a9a-9a9a9a9a9a9a")
    const val BATCH_REFERENCE = "PACT-AGENT-BATCH-001"
    const val PAYMENT_REFERENCE = "PACT-PAY-001"
    const val CYCLE_ID = "pact-cycle-001"
    val SETTLED_AT: OffsetDateTime = OffsetDateTime.parse("2026-10-16T12:00:00Z")

    fun seedPactBatch(dataSource: DataSource) = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "INSERT INTO clearing_batches " +
                "(id, batch_reference, rail, settlement_type, status, total_debit, total_credit, " +
                "net_position, currency, item_count, cycle_id, settlement_date, settled_at, " +
                "created_at, updated_at) " +
                "VALUES (?, ?, 'SEPA_SCT', 'NET', 'SETTLED', 100.0000, 100.0000, 0.0000, " +
                "'EUR', 1, ?, '2026-10-16', ?, ?, ?) " +
                "ON CONFLICT (id) DO NOTHING",
        ).use { statement ->
            statement.setObject(1, BATCH_ID)
            statement.setString(2, BATCH_REFERENCE)
            statement.setString(3, CYCLE_ID)
            statement.setObject(4, SETTLED_AT)
            statement.setObject(5, SETTLED_AT)
            statement.setObject(6, SETTLED_AT)
            statement.executeUpdate()
        }
        connection.prepareStatement(
            "INSERT INTO clearing_items " +
                "(id, batch_id, payment_id, payment_reference, debtor_iban, creditor_iban, " +
                "debtor_bic, creditor_bic, amount, currency, rail, status, aggregate_revision, " +
                "value_date, end_to_end_id, remittance_info, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, 'CZ6508000000192000145399', 'DE89370400440532013000', " +
                "'AAAA CZ PP', 'BBBB DE PP', 100.0000, 'EUR', 'SEPA_SCT', 'SETTLED', 1, " +
                "'2026-10-16', 'PACT-E2E-001', 'pact clearing probe', ?, ?) " +
                "ON CONFLICT (id) DO NOTHING",
        ).use { statement ->
            statement.setObject(1, ITEM_ID)
            statement.setObject(2, BATCH_ID)
            statement.setObject(3, PAYMENT_ID)
            statement.setString(4, PAYMENT_REFERENCE)
            statement.setObject(5, SETTLED_AT)
            statement.setObject(6, SETTLED_AT)
            statement.executeUpdate()
        }
        Unit
    }
}
