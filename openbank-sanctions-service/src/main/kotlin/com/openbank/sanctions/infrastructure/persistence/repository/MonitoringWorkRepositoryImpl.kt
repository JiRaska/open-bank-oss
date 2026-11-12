// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.infrastructure.persistence.repository

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.sanctions.application.port.out.ListedEntry
import com.openbank.sanctions.application.port.out.MonitoringWorkRepository
import com.openbank.sanctions.application.port.out.PendingEntryChange
import com.openbank.sanctions.application.port.out.SanctionsOutboxRepository
import com.openbank.sanctions.domain.model.MonitoringFinding
import com.openbank.sanctions.domain.model.SanctionsListType
import io.quarkus.hibernate.reactive.panache.Panache
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.mutiny.pgclient.PgPool
import io.vertx.mutiny.sqlclient.Tuple
import jakarta.enterprise.context.ApplicationScoped
import java.time.Duration
import java.util.UUID

/**
 * Persistence of the monitoring delta queue and of findings (ADR-0376).
 *
 * [raise] is the one write that goes through Hibernate rather than PgPool, because the outbox
 * row has to commit in the same transaction as the finding and the outbox is a Panache
 * repository.
 */
@ApplicationScoped
class MonitoringWorkRepositoryImpl(
    private val pool: PgPool,
    private val outbox: SanctionsOutboxRepository,
    private val mapper: ObjectMapper,
) : MonitoringWorkRepository {

    override suspend fun pendingCount(): Long =
        pool.query("SELECT count(*) AS pending FROM sanctions_monitoring_pending").execute()
            .awaitSuspending().first().getLong("pending")

    override suspend fun claimPending(lease: Duration, limit: Int): List<PendingEntryChange> {
        if (limit <= 0) return emptyList()
        return pool.preparedQuery(CLAIM_PENDING_SQL).execute(Tuple.of(lease.seconds.toDouble(), limit))
            .awaitSuspending()
            .map {
                PendingEntryChange(
                    id = it.getLong("id"),
                    entryId = it.getUUID("entry_id"),
                    listType = it.getString("list_type"),
                    externalId = it.getString("external_id"),
                    active = it.getBoolean("active"),
                )
            }
            // RETURNING has no order; changes of one entry must be applied in the order they committed.
            .sortedBy { it.id }
    }

    override suspend fun completePending(id: Long) {
        pool.preparedQuery("DELETE FROM sanctions_monitoring_pending WHERE id = $1").execute(Tuple.of(id))
            .awaitSuspending()
    }

    override suspend fun requestResweep() {
        pool.withTransaction { conn ->
            // The dropped queue rows carried delistings too; close findings whose entry is gone.
            conn.query(DELIST_BY_ID_SQL).execute()
                .flatMap { conn.query(DELIST_BY_NAME_SQL).execute() }
                // Deletes the rows this statement can see. A change committing later stays queued.
                .flatMap { conn.query("DELETE FROM sanctions_monitoring_pending").execute() }
                // After the delete, so every subject swept before the dropped changes were
                // visible is older than this instant and therefore due again.
                .flatMap {
                    conn.query(
                        "UPDATE sanctions_monitoring_state SET resweep_requested_at = clock_timestamp() WHERE id = 1",
                    ).execute()
                }
        }.awaitSuspending()
    }

    override suspend fun findEntry(entryId: UUID): ListedEntry? {
        val row = pool.preparedQuery(
            "SELECT list_type, external_id, primary_name, programs, search_text, active, $NAMES_VERSION AS names_version " +
                "FROM sanctions_entries WHERE id = $1",
        ).execute(Tuple.of(entryId)).awaitSuspending().firstOrNull() ?: return null
        val listType = SanctionsListType.entries.firstOrNull { it.name == row.getString("list_type") } ?: return null
        return ListedEntry(
            listType = listType,
            externalId = row.getString("external_id"),
            primaryName = row.getString("primary_name"),
            programs = runCatching { mapper.readValue<List<String>>(row.getString("programs")) }.getOrDefault(emptyList()),
            searchText = row.getString("search_text"),
            active = row.getBoolean("active"),
            namesVersion = row.getString("names_version"),
        )
    }

    override suspend fun namesVersion(listType: SanctionsListType, listEntryRef: String): String? {
        val (predicate, key) = when {
            listEntryRef.startsWith(ID_PREFIX) -> "external_id = $2" to listEntryRef.removePrefix(ID_PREFIX)
            listEntryRef.startsWith(NAME_PREFIX) ->
                "external_id IS NULL AND primary_name = $2" to listEntryRef.removePrefix(NAME_PREFIX)
            else -> return null
        }
        return pool.preparedQuery(
            "SELECT $NAMES_VERSION AS names_version FROM sanctions_entries " +
                "WHERE list_type = $1 AND active AND $predicate ORDER BY id LIMIT 1",
        ).execute(Tuple.of(listType.name, key)).awaitSuspending().firstOrNull()?.getString("names_version")
    }

    override suspend fun raise(finding: MonitoringFinding): Boolean = Panache.withTransaction {
        Panache.getSession().chain { session ->
            session.createNativeQuery(INSERT_FINDING_SQL, String::class.java)
                .setParameter("id", finding.id)
                .setParameter("subjectId", finding.subjectId)
                .setParameter("matchKind", finding.matchKind.name)
                .setParameter("listType", finding.listType.name)
                .setParameter("entryRef", finding.listEntryRef)
                .setParameter("entryVersion", finding.listEntryVersion)
                .setParameter("score", finding.matchScore)
                .setParameter("detection", finding.detection.name)
                .setParameter("detectedAt", finding.detectedAt)
                .resultList
                .chain { inserted ->
                    if (inserted.isEmpty()) {
                        Uni.createFrom().item(false)
                    } else {
                        outbox.persistInTransaction(event(finding)).replaceWith(true)
                    }
                }
        }
    }.awaitSuspending()

    override suspend fun markDelisted(listType: String, listEntryRef: String): Int =
        pool.preparedQuery(
            "UPDATE sanctions_monitoring_findings SET delisted_at = clock_timestamp() " +
                "WHERE list_type = $1 AND list_entry_ref = $2 AND delisted_at IS NULL",
        ).execute(Tuple.of(listType, listEntryRef)).awaitSuspending().rowCount()

    /**
     * The `MONITORED_SUBJECT_MATCHED` event, schema version 1.
     *
     * It names the subject by the owning service's own reference and the list entry by its
     * reference: no customer name, date of birth or nationality leaves this service. A consumer
     * has what it needs to open a review case — whose subject, which entry and version, what kind
     * of match, how strong — and deduplicates on `findingId`.
     */
    private fun event(finding: MonitoringFinding) = OutboxMessage(
        aggregateId = finding.id,
        eventType = EVENT_MATCHED,
        payload = mapper.writeValueAsString(
            mapOf(
                "eventType" to EVENT_MATCHED,
                "schemaVersion" to SCHEMA_VERSION,
                "aggregateId" to finding.id.toString(),
                "aggregateType" to "MonitoringFinding",
                "findingId" to finding.id.toString(),
                "ownerService" to finding.ownerService,
                "subjectRef" to finding.subjectRef,
                "entityType" to finding.entityType.name,
                "matchKind" to finding.matchKind.name,
                "listType" to finding.listType.name,
                "listEntryRef" to finding.listEntryRef,
                "listEntryVersion" to finding.listEntryVersion,
                "matchScore" to finding.matchScore,
                "matchStatus" to finding.matchStatus.name,
                "detection" to finding.detection.name,
                "occurredAt" to finding.detectedAt.toString(),
                "sourceService" to SOURCE_SERVICE,
            ),
        ),
        createdAt = finding.detectedAt,
    )

    companion object {
        const val EVENT_MATCHED = "MONITORED_SUBJECT_MATCHED"
        const val SCHEMA_VERSION = 1
        private const val SOURCE_SERVICE = "sanctions-service"
        private const val ID_PREFIX = "id:"
        private const val NAME_PREFIX = "name:"

        // The names of an entry as an order-independent set, the same canonical form the change
        // journal compares (V15), so a feed that merely reorders aliases is not a new version.
        private const val NAMES_VERSION =
            "md5(sanctions_canonical_values(to_jsonb(string_to_array(search_text, ' | '))::text)::text)"

        private val CLAIM_PENDING_SQL = """
            UPDATE sanctions_monitoring_pending p SET claimed_at = clock_timestamp()
            WHERE p.id IN (
                SELECT id FROM sanctions_monitoring_pending
                WHERE claimed_at IS NULL OR claimed_at < clock_timestamp() - make_interval(secs => $1)
                ORDER BY id LIMIT $2 FOR UPDATE SKIP LOCKED
            )
            RETURNING p.id, p.entry_id, p.list_type, p.external_id, p.active
        """.trimIndent()

        // INSERT ... SELECT from the registry row: a subject deregistered meanwhile yields no row
        // and therefore no finding and no event. ON CONFLICT is the idempotency of both paths.
        private val INSERT_FINDING_SQL = """
            INSERT INTO sanctions_monitoring_findings
                (id, subject_id, match_kind, list_type, list_entry_ref, list_entry_version,
                 match_score, detection, detected_at)
            SELECT CAST(:id AS uuid), s.id, CAST(:matchKind AS varchar), CAST(:listType AS varchar),
                   CAST(:entryRef AS text), CAST(:entryVersion AS text), CAST(:score AS double precision),
                   CAST(:detection AS varchar), CAST(:detectedAt AS timestamptz)
            FROM sanctions_monitored_subjects s WHERE s.id = CAST(:subjectId AS uuid)
            ON CONFLICT (subject_id, list_type, list_entry_ref, list_entry_version) WHERE delisted_at IS NULL
            DO NOTHING
            RETURNING CAST(id AS text)
        """.trimIndent()

        private const val DELIST_BY_ID_SQL = """
            UPDATE sanctions_monitoring_findings f SET delisted_at = clock_timestamp()
            WHERE f.delisted_at IS NULL AND f.match_kind <> 'JURISDICTION' AND f.list_entry_ref LIKE 'id:%'
              AND NOT EXISTS (SELECT 1 FROM sanctions_entries e
                              WHERE e.list_type = f.list_type AND e.active
                                AND e.external_id = substr(f.list_entry_ref, 4))
        """
        private const val DELIST_BY_NAME_SQL = """
            UPDATE sanctions_monitoring_findings f SET delisted_at = clock_timestamp()
            WHERE f.delisted_at IS NULL AND f.match_kind <> 'JURISDICTION' AND f.list_entry_ref LIKE 'name:%'
              AND NOT EXISTS (SELECT 1 FROM sanctions_entries e
                              WHERE e.list_type = f.list_type AND e.active AND e.external_id IS NULL
                                AND e.primary_name = substr(f.list_entry_ref, 6))
        """
    }
}
