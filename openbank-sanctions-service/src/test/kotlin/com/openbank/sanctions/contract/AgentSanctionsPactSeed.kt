// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.contract

import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource

/**
 * Provider-state seed for the agent-service consumer's sanctions READ pact
 * (`AgentSanctionsReadPactConsumerTest`): one `POTENTIAL_HIT` check for a probe name no sanctions
 * list carries, so `GET /api/v1/sanctions` and `/pending` both return at least one row and
 * `GET /api/v1/sanctions/{id}` answers a pinned 200 for the agent's `get_check` tool.
 *
 * Inserted directly against `sanctions_checks` (V1's schema) because this service's repositories
 * are reactive (`PgPool` / reactive Panache) and a plain `@State` callback carries no Vert.x
 * context — the documented HR000068 trap the existing screen-based states avoid by naming boot
 * state. Direct JDBC is the same escape hatch ledger's `NostroPactSeed` uses.
 *
 * `POTENTIAL_HIT` (not `CLEAR`) so the same row satisfies `listPending()`'s
 * `findByStatus(POTENTIAL_HIT)` filter; the name shares no trigram with any seeded
 * `search_text`, so it can never interfere with the screen-based consumers' similarity queries.
 * `matches` stays `[]` — the check is a fixture, not a screening verdict.
 *
 * `ON CONFLICT (id) DO NOTHING` makes re-runs against a warm container safe: pact-jvm 4.7.3
 * invokes a `@State` method twice, and a bare INSERT would die on the second invocation.
 */
object AgentSanctionsPactSeed {
    val CHECK_ID: UUID = UUID.fromString("5d5d5d5d-5d5d-4d5d-8d5d-5d5d5d5d5d5d")
    const val IDEMPOTENCY_KEY = "pact-agent-sanctions-001"
    const val PROBE_NAME = "Agent Pact Probe Person"
    val CHECKED_AT: OffsetDateTime = OffsetDateTime.parse("2026-10-16T12:00:00Z")

    fun seedPactCheck(dataSource: DataSource) = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "INSERT INTO sanctions_checks " +
                "(id, idempotency_key, entity_type, name, aliases, date_of_birth, nationality, " +
                "identifiers, status, matches, overall_score, checked_lists, reviewed_by, " +
                "review_note, checked_at, reviewed_at) " +
                "VALUES (?, ?, 'INDIVIDUAL', ?, '[]', NULL, NULL, '{}', " +
                "'POTENTIAL_HIT', '[]', 0.9, '[\"OFAC_SDN\"]', NULL, NULL, ?, NULL) " +
                "ON CONFLICT (id) DO NOTHING",
        ).use { statement ->
            statement.setObject(1, CHECK_ID)
            statement.setString(2, IDEMPOTENCY_KEY)
            statement.setString(3, PROBE_NAME)
            statement.setObject(4, CHECKED_AT)
            statement.executeUpdate()
        }
        Unit
    }
}
