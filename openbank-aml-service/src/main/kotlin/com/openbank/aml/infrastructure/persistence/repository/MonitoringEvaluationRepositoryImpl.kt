// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.infrastructure.persistence.repository

import com.openbank.aml.application.port.out.MonitoringEvaluationRepository
import com.openbank.aml.application.port.out.RecordedHit
import com.openbank.aml.domain.monitoring.MonitoringEvaluationRecord
import com.openbank.aml.domain.monitoring.MonitoringHit
import com.openbank.aml.domain.monitoring.PackIdentity
import io.quarkus.hibernate.reactive.panache.Panache
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Native upserts, for the same reason as [MovementIntakeRepositoryImpl]: every write is idempotent. */
@ApplicationScoped
class MonitoringEvaluationRepositoryImpl : MonitoringEvaluationRepository {

    override suspend fun activeParties(since: Instant, afterPartyId: UUID?, limit: Int): List<UUID> = Panache.withSession {
        Panache.getSession().chain { session ->
            session.createNativeQuery(
                """
                SELECT DISTINCT party_id FROM aml_movement_counter
                 WHERE bucket_start >= :since AND party_id > :after
                 ORDER BY party_id LIMIT :limit
                """.trimIndent(),
                UUID::class.java,
            )
                .setParameter("since", utc(since))
                .setParameter("after", afterPartyId ?: LOWEST)
                .setParameter("limit", limit)
                .resultList
        }
    }.awaitSuspending()

    override suspend fun recordEvaluation(record: MonitoringEvaluationRecord) {
        Panache.withTransaction {
            mutate(
                """
                INSERT INTO aml_monitoring_evaluation
                  (party_id, pack_hash, pack_id, pack_revision, outcome, failure, input_hash, evaluated_at)
                VALUES (:partyId, :packHash, :packId, :packRevision, :outcome,
                        nullif(:failure, ''), nullif(:inputHash, ''), :evaluatedAt)
                ON CONFLICT (party_id, pack_hash) DO UPDATE
                   SET outcome = excluded.outcome, failure = excluded.failure,
                       input_hash = excluded.input_hash, evaluated_at = excluded.evaluated_at
                """,
                mapOf(
                    "partyId" to record.partyId,
                    "packHash" to record.pack.contentHash,
                    "packId" to record.pack.id,
                    "packRevision" to record.pack.revision,
                    "outcome" to record.outcome.name,
                    "failure" to (record.failure ?: ""),
                    "inputHash" to (record.inputHash ?: ""),
                    "evaluatedAt" to utc(record.evaluatedAt),
                ),
            )
        }.awaitSuspending()
    }

    override suspend fun recordHit(hit: MonitoringHit, pack: PackIdentity, snapshotJson: String, at: Instant): RecordedHit {
        val candidate = UUID.randomUUID()
        return Panache.withTransaction {
            mutate(
                """
                INSERT INTO aml_monitoring_hit
                  (hit_id, party_id, scenario_code, window_start, pack_id, pack_revision, pack_hash, reason_code,
                   severity_code, counter_snapshot, disposition, detection_count, first_detected_at, last_detected_at)
                VALUES (:hitId, :partyId, :scenarioCode, :windowStart, :packId, :packRevision, :packHash, :reasonCode,
                   :severityCode, :snapshot, 'SHADOW_RECORDED', 1, :at, :at)
                ON CONFLICT (party_id, scenario_code, window_start) DO UPDATE
                   SET detection_count = aml_monitoring_hit.detection_count + 1, last_detected_at = :at,
                       counter_snapshot = :snapshot, pack_id = :packId, pack_revision = :packRevision,
                       pack_hash = :packHash
                """,
                mapOf(
                    "hitId" to candidate,
                    "partyId" to hit.partyId,
                    "scenarioCode" to hit.scenarioCode,
                    "windowStart" to utc(hit.windowStart),
                    "packId" to pack.id,
                    "packRevision" to pack.revision,
                    "packHash" to pack.contentHash,
                    "reasonCode" to hit.reasonCode,
                    "severityCode" to hit.severityCode,
                    "snapshot" to snapshotJson,
                    "at" to utc(at),
                ),
            ).chain { _ ->
                Panache.getSession().chain { session ->
                    session.createNativeQuery(
                        """
                        SELECT hit_id, case_id FROM aml_monitoring_hit
                         WHERE party_id = :partyId AND scenario_code = :scenarioCode AND window_start = :windowStart
                        """.trimIndent(),
                        Array<Any>::class.java,
                    )
                        .setParameter("partyId", hit.partyId)
                        .setParameter("scenarioCode", hit.scenarioCode)
                        .setParameter("windowStart", utc(hit.windowStart))
                        .singleResult
                }
            }
        }.awaitSuspending().let { row ->
            val stored = row[0] as UUID
            RecordedHit(stored, created = stored == candidate, caseId = (row as Array<*>)[1] as? UUID)
        }
    }

    override suspend fun markCaseOpened(hitId: UUID, caseId: UUID) {
        Panache.withTransaction {
            mutate(
                """
                UPDATE aml_monitoring_hit SET disposition = 'CASE_OPENED', case_id = :caseId
                 WHERE hit_id = :hitId AND case_id IS NULL
                """,
                mapOf("hitId" to hitId, "caseId" to caseId),
            )
        }.awaitSuspending()
    }

    override suspend fun oldestUndecidedMonitoringCase(): Instant? = Panache.withSession {
        Panache.getSession().chain { session ->
            session.createNativeQuery(
                """
                SELECT count(*), min(created_at) FROM aml_cases
                 WHERE screening_type = 'TRANSACTION_MONITORING' AND status IN ('OPEN', 'UNDER_REVIEW', 'ESCALATED')
                """.trimIndent(),
                Array<Any>::class.java,
            ).resultList
        }
    }.awaitSuspending().firstOrNull()?.let { row ->
        when (val value = (row as Array<*>)[1]) {
            null -> null
            is Instant -> value
            is OffsetDateTime -> value.toInstant()
            is java.sql.Timestamp -> value.toInstant()
            else -> error("unexpected timestamp type ${value.javaClass}")
        }
    }

    private fun mutate(sql: String, values: Map<String, Any>): Uni<Int> = Panache.getSession().chain { session ->
        val query = session.createNativeMutationQuery(sql.trimIndent())
        values.forEach { (name, value) -> query.setParameter(name, value) }
        query.executeUpdate()
    }

    private companion object {
        val LOWEST: UUID = UUID(0L, 0L)

        fun utc(at: Instant): OffsetDateTime = at.atOffset(ZoneOffset.UTC)
    }
}
