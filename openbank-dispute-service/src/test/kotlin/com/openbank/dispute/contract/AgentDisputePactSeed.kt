// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.dispute.contract

import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource

/**
 * Provider-state seed for the agent-service consumer's dispute READ pact
 * (`AgentDisputeReadPactConsumerTest`): one UNDER_REVIEW DUPLICATE dispute with exactly one
 * timeline event, so `GET /api/v1/disputes`, `/account/{accountId}` and `/{id}/timeline` all
 * return at least one row and `GET /api/v1/disputes/{id}` answers a pinned 200 for the agent's
 * `dispute_get` tool.
 *
 * Inserted directly against `disputes` (V1's schema) and `dispute_timeline` (V1) because a
 * `@State` callback carries no Vert.x context and this service's writes go through reactive
 * Panache — the documented HR000068 trap. Direct JDBC is the same escape hatch clearing's
 * `AgentClearingPactSeed` and sanctions' `AgentSanctionsPactSeed` use. The dispute is
 * UNDER_REVIEW (not OPEN) and resolution PENDING: read-only pact traffic can never be mistaken
 * for a filed dispute by SLA sweepers, and the seeded lifecycle is a stable read surface.
 *
 * `ON CONFLICT (id) DO NOTHING` makes re-runs safe: pact-jvm 4.7.3 invokes a `@State` method
 * twice, and a bare INSERT would die on the second invocation.
 */
object AgentDisputePactSeed {
    val DISPUTE_ID: UUID = UUID.fromString("1d1d1d1d-1d1d-4d1d-8d1d-1d1d1d1d1d1d")
    val TRANSACTION_ID: UUID = UUID.fromString("2d2d2d2d-2d2d-4d2d-8d2d-2d2d2d2d2d2d")
    val ACCOUNT_ID: UUID = UUID.fromString("3d3d3d3d-3d3d-4d3d-8d3d-3d3d3d3d3d3d")
    val PARTY_ID: UUID = UUID.fromString("4d4d4d4d-4d4d-4d4d-8d4d-4d4d4d4d4d4d")
    val TIMELINE_ID: UUID = UUID.fromString("5d5d5d5d-5d5d-4d5d-8d5d-5d5d5d5d5d5d")
    const val REFERENCE = "PACT-AGENT-DISP-001"
    val CREATED_AT: OffsetDateTime = OffsetDateTime.parse("2026-10-16T12:00:00Z")

    fun seedPactDispute(dataSource: DataSource) = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "INSERT INTO disputes " +
                "(id, reference, transaction_id, account_id, party_id, dispute_type, status, " +
                "resolution, amount, currency, description, transaction_date, filing_date, " +
                "created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, 'DUPLICATE', 'UNDER_REVIEW', 'PENDING', 250.0000, " +
                "'EUR', 'pact dispute probe', '2026-10-01', '2026-10-02', ?, ?) " +
                "ON CONFLICT (id) DO NOTHING",
        ).use { statement ->
            statement.setObject(1, DISPUTE_ID)
            statement.setString(2, REFERENCE)
            statement.setObject(3, TRANSACTION_ID)
            statement.setObject(4, ACCOUNT_ID)
            statement.setObject(5, PARTY_ID)
            statement.setObject(6, CREATED_AT)
            statement.setObject(7, CREATED_AT)
            statement.executeUpdate()
        }
        connection.prepareStatement(
            "INSERT INTO dispute_timeline " +
                "(id, dispute_id, event_type, description, actor, created_at) " +
                "VALUES (?, ?, 'DISPUTE_FILED', 'Dispute filed by pact agent probe', " +
                "'pact-verifier', ?) " +
                "ON CONFLICT (id) DO NOTHING",
        ).use { statement ->
            statement.setObject(1, TIMELINE_ID)
            statement.setObject(2, DISPUTE_ID)
            statement.setObject(3, CREATED_AT)
            statement.executeUpdate()
        }
        Unit
    }
}
