// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.reporting

import com.openbank.libs.idempotency.IdempotencyKeyReusedException
import com.openbank.pension.application.port.out.ParticipantSnapshot
import com.openbank.pension.application.port.out.ParticipantSnapshotRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.mutiny.sqlclient.Pool
import io.vertx.mutiny.sqlclient.Row
import io.vertx.mutiny.sqlclient.SqlConnection
import io.vertx.mutiny.sqlclient.Tuple
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.UUID

/** Durable command receipt and immutable aggregate bytes share one database transaction. */
@ApplicationScoped
class ParticipantSnapshotStore(private val pool: Pool) : ParticipantSnapshotRepository {
    override suspend fun receipt(scopeHash: String, requestHash: String): ParticipantSnapshot? {
        val rows = pool.preparedQuery(
            """SELECT s.* , r.request_hash FROM pension_participant_snapshot_receipts r
               JOIN pension_participant_snapshots s ON s.snapshot_id = r.snapshot_id
               WHERE r.scope_hash = $1""",
        ).execute(Tuple.of(scopeHash)).awaitSuspending()
        val row = rows.firstOrNull() ?: return null
        if (row.getString("request_hash") != requestHash) throw IdempotencyKeyReusedException()
        return row.snapshot()
    }

    override suspend fun byId(snapshotId: UUID, companyId: UUID): ParticipantSnapshot? = pool.preparedQuery(
        "SELECT * FROM pension_participant_snapshots WHERE snapshot_id = $1 AND company_id = $2",
    ).execute(Tuple.of(snapshotId, companyId)).awaitSuspending().firstOrNull()?.snapshot()

    /** Scope lock settles concurrent retries; company/period lock serializes revision allocation. */
    @Suppress("LongParameterList", "TooGenericExceptionCaught", "ThrowingExceptionFromFinally")
    override suspend fun capture(
        scopeHash: String,
        requestHash: String,
        companyId: UUID,
        periodStart: LocalDate,
        periodEnd: LocalDate,
        schemaVersion: String,
        payloadJson: String,
        sha256: String,
    ): ParticipantSnapshot {
        val connection = pool.connection.awaitSuspending()
        var failure: Throwable? = null
        try {
            val tx = connection.begin().awaitSuspending()
            try {
                val saved = captureLocked(
                    connection,
                    CaptureInput(
                        scopeHash,
                        requestHash,
                        companyId,
                        periodStart,
                        periodEnd,
                        schemaVersion,
                        payloadJson,
                        sha256,
                    ),
                )
                tx.commit().awaitSuspending()
                return saved
            } catch (cause: Throwable) {
                withContext(NonCancellable) {
                    runCatching { tx.rollback().awaitSuspending() }.exceptionOrNull()?.let(cause::addSuppressed)
                }
                throw cause
            }
        } catch (cause: Throwable) {
            failure = cause
            throw cause
        } finally {
            withContext(NonCancellable) {
                try {
                    connection.close().awaitSuspending()
                } catch (closeFailure: Throwable) {
                    if (failure != null) failure.addSuppressed(closeFailure) else throw closeFailure
                }
            }
        }
    }

    private suspend fun captureLocked(connection: SqlConnection, input: CaptureInput): ParticipantSnapshot {
        connection.preparedQuery("SELECT pg_advisory_xact_lock(hashtextextended($1, 0))")
            .execute(Tuple.of("participant-snapshot-command:${input.scopeHash}")).awaitSuspending()
        connection.preparedQuery("SELECT pg_advisory_xact_lock(hashtextextended($1, 0))")
            .execute(Tuple.of("participant-snapshot:${input.companyId}:${input.periodStart}:${input.periodEnd}"))
            .awaitSuspending()
        val prior = connection.preparedQuery(
            """SELECT s.*, r.request_hash FROM pension_participant_snapshot_receipts r
               JOIN pension_participant_snapshots s ON s.snapshot_id = r.snapshot_id
               WHERE r.scope_hash = $1""",
        ).execute(Tuple.of(input.scopeHash)).awaitSuspending().firstOrNull()
        if (prior != null) {
            if (prior.getString("request_hash") != input.requestHash) throw IdempotencyKeyReusedException()
            return prior.snapshot()
        }
        val next = connection.preparedQuery(
            """SELECT COALESCE(MAX(revision), 0) + 1 AS next_revision
                 FROM pension_participant_snapshots
                WHERE company_id = $1 AND period_start = $2 AND period_end = $3""",
        ).execute(Tuple.of(input.companyId, input.periodStart, input.periodEnd))
            .awaitSuspending().first().getInteger("next_revision")
        val snapshotId = UUID.randomUUID()
        connection.preparedQuery(
            """INSERT INTO pension_participant_snapshots
               (snapshot_id, company_id, period_start, period_end, revision, payload_schema_version, payload_json, sha256)
               VALUES ($1, $2, $3, $4, $5, $6, $7, $8)""",
        ).execute(
            Tuple.tuple().addValue(snapshotId).addValue(input.companyId).addValue(input.periodStart)
                .addValue(input.periodEnd).addValue(next).addValue(input.schemaVersion)
                .addValue(input.payloadJson).addValue(input.sha256),
        ).awaitSuspending()
        connection.preparedQuery(
            "INSERT INTO pension_participant_snapshot_receipts (scope_hash, request_hash, snapshot_id) VALUES ($1, $2, $3)",
        ).execute(Tuple.of(input.scopeHash, input.requestHash, snapshotId)).awaitSuspending()
        return ParticipantSnapshot(
            snapshotId,
            input.companyId,
            input.periodStart,
            input.periodEnd,
            next,
            input.schemaVersion,
            input.sha256,
            input.payloadJson,
        )
    }

    private data class CaptureInput(
        val scopeHash: String,
        val requestHash: String,
        val companyId: UUID,
        val periodStart: LocalDate,
        val periodEnd: LocalDate,
        val schemaVersion: String,
        val payloadJson: String,
        val sha256: String,
    )

    private fun Row.snapshot() = ParticipantSnapshot(
        getUUID("snapshot_id"),
        getUUID("company_id"),
        getLocalDate("period_start"),
        getLocalDate("period_end"),
        getInteger("revision"),
        getString("payload_schema_version"),
        getString("sha256").trim(),
        getString("payload_json"),
    )
}
