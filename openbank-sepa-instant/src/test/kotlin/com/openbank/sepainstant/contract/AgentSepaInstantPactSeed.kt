// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepainstant.contract

import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource

/**
 * Provider-state seed for the agent-service consumer's SCT Inst READ pact
 * (`AgentSepaInstantReadPactConsumerTest`): one SETTLED payment, so
 * `GET /api/v1/sepa-instant` and `/debtor/{debtorAccountId}` both return at least one row and
 * `GET /api/v1/sepa-instant/{paymentId}` answers a pinned 200 for the agent's `sepa_instant_get`
 * tool.
 *
 * Inserted directly against `sct_inst_payments` (V1's schema) because a `@State` callback carries
 * no Vert.x context and this service's writes go through reactive Panache — the documented
 * HR000068 trap. Direct JDBC is the same escape hatch clearing's `AgentClearingPactSeed` and
 * dispute's `AgentDisputePactSeed` use. The payment is SETTLED (not PENDING) so no scheme
 * submission or timeout sweeper can ever pick it up mid-replay.
 *
 * `ON CONFLICT (payment_id) DO NOTHING` makes re-runs safe: pact-jvm 4.7.3 invokes a `@State`
 * method twice, and a bare INSERT would die on the second invocation.
 */
object AgentSepaInstantPactSeed {
    val PAYMENT_ID: UUID = UUID.fromString("6a6a6a6a-6a6a-4a6a-8a6a-6a6a6a6a6a6a")
    val DEBTOR_ACCOUNT_ID: UUID = UUID.fromString("3a3a3a3a-3a3a-4a3a-8a3a-3a3a3a3a3a3a")
    const val IDEMPOTENCY_KEY = "pact-sctinst-001"
    const val END_TO_END_ID = "PACT-E2E-SCTINST-001"
    val SETTLED_AT: OffsetDateTime = OffsetDateTime.parse("2026-10-16T12:00:00Z")

    fun seedPactPayment(dataSource: DataSource) = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "INSERT INTO sct_inst_payments " +
                "(payment_id, idempotency_key, status, debtor_account_id, debtor_iban, debtor_name, " +
                "creditor_iban, creditor_name, creditor_bic, amount, currency, remittance_info, " +
                "end_to_end_id, settled_at, submitted_at, created_at, updated_at) " +
                "VALUES (?, ?, 'SETTLED', ?, 'CZ6508000000192000145399', 'Pact Debtor', " +
                "'DE89370400440532013000', 'Pact Creditor', 'COBADEFFXXX', 100.000000, 'EUR', " +
                "'pact sct inst probe', ?, ?, ?, ?, ?) " +
                "ON CONFLICT (payment_id) DO NOTHING",
        ).use { statement ->
            statement.setObject(1, PAYMENT_ID)
            statement.setString(2, IDEMPOTENCY_KEY)
            statement.setObject(3, DEBTOR_ACCOUNT_ID)
            statement.setString(4, END_TO_END_ID)
            statement.setObject(5, SETTLED_AT)
            statement.setObject(6, SETTLED_AT)
            statement.setObject(7, SETTLED_AT)
            statement.setObject(8, SETTLED_AT)
            statement.executeUpdate()
        }
        Unit
    }
}
