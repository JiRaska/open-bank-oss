// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.infrastructure.persistence.repository

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.sanctions.application.port.out.MonitoredSubjectCandidate
import com.openbank.sanctions.application.port.out.MonitoredSubjectRepository
import com.openbank.sanctions.application.port.out.MonitoredSubjectUpsert
import com.openbank.sanctions.application.port.out.MonitoringRegistryStats
import com.openbank.sanctions.domain.model.EntityType
import com.openbank.sanctions.domain.model.MonitoredSubject
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.mutiny.pgclient.PgPool
import io.vertx.mutiny.sqlclient.Row
import io.vertx.mutiny.sqlclient.SqlConnection
import io.vertx.mutiny.sqlclient.Tuple
import jakarta.enterprise.context.ApplicationScoped
import java.time.Duration
import java.time.ZoneOffset
import java.util.UUID
import io.vertx.sqlclient.Tuple as CoreTuple

/**
 * PgPool persistence of the monitored-subject registry (ADR-0376).
 *
 * PgPool rather than Panache for the reason [SanctionsEntryRepositoryImpl] gives: the match is a
 * native pg_trgm query whose score column has to be read off the row.
 *
 * A subject's names are stored twice on purpose: `search_names` on the registry row is what the
 * exact score reads, and one row per name in `sanctions_monitored_subject_names` is what the
 * delta's trigram indexes cover ([MonitoringNameIndex]). One statement writes both.
 *
 * Sweep timing uses the database clock throughout (`clock_timestamp()`), so the instant a
 * subject was swept and the instant a re-sweep was requested are comparable whatever the pods'
 * clocks say.
 */
@ApplicationScoped
class MonitoredSubjectRepositoryImpl(private val pool: PgPool) : MonitoredSubjectRepository {

    private val mapper = jacksonObjectMapper()

    override suspend fun upsert(subject: MonitoredSubject, searchNames: List<String>): MonitoredSubjectUpsert {
        val at = subject.registeredAt.atOffset(ZoneOffset.UTC)
        val args = Tuple.newInstance(
            CoreTuple.wrap(
                listOf(
                    subject.id,
                    subject.ownerService,
                    subject.subjectRef,
                    subject.entityType.name,
                    subject.name,
                    mapper.writeValueAsString(subject.aliases),
                    searchNames.toTypedArray(),
                    subject.dateOfBirth,
                    subject.nationality,
                    subject.country,
                    at,
                ),
            ),
        )
        val written = pool.preparedQuery(UPSERT_SQL).execute(args).awaitSuspending().firstOrNull()
        if (written != null) return MonitoredSubjectUpsert(written.toSubject(), written.getBoolean("created"))
        // The conflicting row already holds exactly these attributes: nothing was written.
        val existing = pool.preparedQuery("SELECT $COLUMNS FROM sanctions_monitored_subjects s $BY_REF")
            .execute(Tuple.of(subject.ownerService, subject.subjectRef)).awaitSuspending().first()
        return MonitoredSubjectUpsert(existing.toSubject(), created = false)
    }

    override suspend fun delete(ownerService: String, subjectRef: String): Boolean =
        pool.preparedQuery("DELETE FROM sanctions_monitored_subjects s $BY_REF")
            .execute(Tuple.of(ownerService, subjectRef)).awaitSuspending().rowCount() > 0

    override suspend fun findMatching(
        entrySearchText: String,
        threshold: Double,
        limit: Int,
    ): List<MonitoredSubjectCandidate> {
        if (limit <= 0) return emptyList()
        val conn = pool.connection.awaitSuspending()
        try {
            // One transaction: `set_config(.., true)` is SET LOCAL, so a probe threshold cannot
            // leak to the next borrower of the pooled connection.
            val tx = conn.begin().awaitSuspending()
            val found = probeSizeClasses(conn, entrySearchText, threshold, limit)
            tx.commit().awaitSuspending()
            return found
        } finally {
            // Closing a connection with an open transaction rolls it back; nothing here writes.
            conn.close().awaitSuspending()
        }
    }

    private suspend fun probeSizeClasses(
        conn: SqlConnection,
        entrySearchText: String,
        threshold: Double,
        limit: Int,
    ): List<MonitoredSubjectCandidate> {
        val entryTrigrams = conn.preparedQuery("SELECT cardinality(show_trgm($1)) AS trigrams")
            .execute(Tuple.of(entrySearchText)).awaitSuspending().first().getInteger("trigrams")
        // A text without a trigram scores 0 against every name.
        if (entryTrigrams == 0) return emptyList()
        val probes = MonitoringNameIndex.CLASSES.mapNotNull { sizeClass ->
            MonitoringNameIndex.probeThreshold(sizeClass, entryTrigrams, threshold)?.let { sizeClass to it }
        }
        val found = LinkedHashMap<UUID, MonitoredSubjectCandidate>()
        for ((sizeClass, probe) in probes) {
            if (found.size >= limit) break
            conn.preparedQuery(PROBE_GUC_SQL).execute(Tuple.of(probe.toPlainString())).awaitSuspending()
            // toFloat(): the live search binds its threshold as a float4 too, so the boundary agrees.
            conn.preparedQuery(matchSql(sizeClass))
                .execute(Tuple.of(entrySearchText, threshold.toFloat(), limit)).awaitSuspending()
                // A subject with names in two classes is found in both, with the same score.
                .forEach { found.putIfAbsent(it.getUUID("id"), it.toCandidate()) }
        }
        return found.values.take(limit)
    }

    /** The same answer as [findMatching] by one pass over the registry: what the index must agree with. */
    internal suspend fun findMatchingByFullScan(
        entrySearchText: String,
        threshold: Double,
        limit: Int,
    ): List<MonitoredSubjectCandidate> =
        pool.preparedQuery(FULL_SCAN_SQL).execute(Tuple.of(entrySearchText, threshold.toFloat(), limit))
            .awaitSuspending().map { it.toCandidate() }

    override suspend fun claimDueForSweep(interval: Duration, lease: Duration, limit: Int): List<MonitoredSubject> {
        if (limit <= 0) return emptyList()
        val leaseSeconds = lease.seconds.toDouble()
        val unswept = pool.preparedQuery(CLAIM_UNSWEPT_SQL).execute(Tuple.of(leaseSeconds, limit))
            .awaitSuspending().map { it.toSubject() }
        if (unswept.size >= limit) return unswept
        val stale = pool.preparedQuery(CLAIM_STALE_SQL)
            .execute(Tuple.of(leaseSeconds, limit - unswept.size, interval.seconds.toDouble()))
            .awaitSuspending().map { it.toSubject() }
        return unswept + stale
    }

    override suspend fun markSwept(id: UUID) {
        // `sweep_claimed_at IS NOT NULL`: a registration that replaced the attributes meanwhile
        // cleared the claim, and that subject must be swept again with its new attributes.
        pool.preparedQuery(
            "UPDATE sanctions_monitored_subjects SET last_swept_at = sweep_claimed_at, sweep_claimed_at = NULL " +
                "WHERE id = $1 AND sweep_claimed_at IS NOT NULL",
        ).execute(Tuple.of(id)).awaitSuspending()
    }

    override suspend fun stats(): MonitoringRegistryStats {
        val row = pool.query(STATS_SQL).execute().awaitSuspending().first()
        return MonitoringRegistryStats(row.getLong("subjects"), row.getDouble("oldest_age").toLong())
    }

    private fun Row.toCandidate() = MonitoredSubjectCandidate(toSubject(), getDouble("match_score") ?: 0.0)

    private fun Row.toSubject() = MonitoredSubject(
        id = getUUID("id"),
        ownerService = getString("owner_service"),
        subjectRef = getString("subject_ref"),
        entityType = EntityType.valueOf(getString("entity_type")),
        name = getString("name"),
        aliases = mapper.readValue<List<String>>(getString("aliases_json")),
        dateOfBirth = getString("date_of_birth"),
        nationality = getString("nationality"),
        country = getString("country"),
        registeredAt = getOffsetDateTime("registered_at").toInstant(),
        updatedAt = getOffsetDateTime("updated_at").toInstant(),
        lastSweptAt = getOffsetDateTime("last_swept_at")?.toInstant(),
    )

    private companion object {
        const val COLUMNS = "s.id, s.owner_service, s.subject_ref, s.entity_type, s.name, s.aliases_json, " +
            "s.date_of_birth, s.nationality, s.country, s.registered_at, s.updated_at, s.last_swept_at"
        const val BY_REF = "WHERE s.owner_service = $1 AND s.subject_ref = $2"

        // The WHERE on the conflict arm is what makes registration idempotent: the same attributes
        // write nothing, so they neither move updated_at nor force a new baseline sweep.
        // The names rows are replaced in the same statement, so the index the delta reads can
        // never describe other names than the row the exact score reads. An unchanged
        // registration returns no row from `written` and therefore touches no name either.
        val UPSERT_SQL = """
            WITH written AS (
                INSERT INTO sanctions_monitored_subjects AS s
                    (id, owner_service, subject_ref, entity_type, name, aliases_json, search_names,
                     date_of_birth, nationality, country, registered_at, updated_at)
                VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $11)
                ON CONFLICT (owner_service, subject_ref) DO UPDATE SET
                    entity_type = EXCLUDED.entity_type,
                    name = EXCLUDED.name,
                    aliases_json = EXCLUDED.aliases_json,
                    search_names = EXCLUDED.search_names,
                    date_of_birth = EXCLUDED.date_of_birth,
                    nationality = EXCLUDED.nationality,
                    country = EXCLUDED.country,
                    updated_at = EXCLUDED.updated_at,
                    last_swept_at = NULL,
                    sweep_claimed_at = NULL
                WHERE (s.entity_type, s.name, s.aliases_json, s.search_names, s.date_of_birth, s.nationality, s.country)
                      IS DISTINCT FROM
                      (EXCLUDED.entity_type, EXCLUDED.name, EXCLUDED.aliases_json, EXCLUDED.search_names,
                       EXCLUDED.date_of_birth, EXCLUDED.nationality, EXCLUDED.country)
                RETURNING $COLUMNS, (xmax = 0) AS created
            ), replaced AS (
                DELETE FROM sanctions_monitored_subject_names n USING written w WHERE n.subject_id = w.id
            ), indexed AS (
                INSERT INTO sanctions_monitored_subject_names (subject_id, search_name)
                SELECT w.id, n FROM written w CROSS JOIN unnest($7::text[]) AS n
            )
            SELECT * FROM written
        """.trimIndent()

        const val PROBE_GUC_SQL = "SELECT set_config('pg_trgm.similarity_threshold', $1, true)"

        // The exact score: the reverse of SanctionsEntryRepositoryImpl.search. There one name is
        // matched against every entry, here one entry against every subject, with the same
        // function and operand order.
        const val EXACT_SCORE = """
            CROSS JOIN LATERAL (
                SELECT max(word_similarity(n, $1)) AS match_score FROM unnest(s.search_names) AS n
            ) m
        """

        // No ORDER BY: with one, the planner walks the primary key to feed the LIMIT and reads the
        // heap in random order, which measured 9.7 s per entry on a million rows.
        val FULL_SCAN_SQL = """
            SELECT $COLUMNS, m.match_score
            FROM sanctions_monitored_subjects s
            $EXACT_SCORE
            WHERE m.match_score >= $2
            LIMIT $3
        """.trimIndent()

        // `%` is what the partial index of the class serves; its cutoff is the session GUC set by
        // PROBE_GUC_SQL just before. The class is a literal so that the planner can prove the
        // index predicate. `%` only narrows: the exact score decides, exactly as in FULL_SCAN_SQL.
        // The candidates are handed over as an array and not joined: the planner cannot estimate
        // `%`, and with a join it read the whole registry to hash it (100 ms per probe on a
        // million rows) where the array is a handful of primary-key lookups.
        fun matchSql(sizeClass: Int) = """
            SELECT $COLUMNS, m.match_score
            FROM sanctions_monitored_subjects s
            $EXACT_SCORE
            WHERE s.id = ANY (ARRAY(
                    SELECT n.subject_id
                    FROM sanctions_monitored_subject_names n
                    WHERE n.size_class = $sizeClass AND n.search_name % $1))
              AND m.match_score >= $2
            LIMIT $3
        """.trimIndent()

        const val LEASE_FREE =
            "(sweep_claimed_at IS NULL OR sweep_claimed_at < clock_timestamp() - make_interval(secs => $1))"

        // The cutoff uses now(), not clock_timestamp(): a volatile function cannot be an index
        // condition, and with it an idle tick read all of the index (1.5 s on a million rows).
        // Two statements, one per index, so a tick with nothing due reads a few index entries
        // instead of the registry: never-swept rows live in a partial index, the rest are ordered
        // by last_swept_at. FOR UPDATE SKIP LOCKED keeps two pods from claiming the same rows.
        val CLAIM_UNSWEPT_SQL = """
            UPDATE sanctions_monitored_subjects s SET sweep_claimed_at = clock_timestamp()
            WHERE s.id IN (
                SELECT id FROM sanctions_monitored_subjects
                WHERE last_swept_at IS NULL AND $LEASE_FREE
                ORDER BY id LIMIT $2 FOR UPDATE SKIP LOCKED
            )
            RETURNING $COLUMNS
        """.trimIndent()

        val CLAIM_STALE_SQL = """
            UPDATE sanctions_monitored_subjects s SET sweep_claimed_at = clock_timestamp()
            WHERE s.id IN (
                SELECT id FROM sanctions_monitored_subjects
                WHERE last_swept_at IS NOT NULL
                  AND last_swept_at < GREATEST(
                        now() - make_interval(secs => $3),
                        COALESCE((SELECT resweep_requested_at FROM sanctions_monitoring_state WHERE id = 1),
                                 '-infinity'::timestamptz))
                  AND $LEASE_FREE
                ORDER BY last_swept_at, id LIMIT $2 FOR UPDATE SKIP LOCKED
            )
            RETURNING $COLUMNS
        """.trimIndent()

        val STATS_SQL = """
            SELECT (SELECT count(*) FROM sanctions_monitored_subjects) AS subjects,
                   COALESCE(EXTRACT(EPOCH FROM clock_timestamp() - LEAST(
                       (SELECT min(registered_at) FROM sanctions_monitored_subjects WHERE last_swept_at IS NULL),
                       (SELECT min(last_swept_at) FROM sanctions_monitored_subjects WHERE last_swept_at IS NOT NULL)
                   )), 0)::float8 AS oldest_age
        """.trimIndent()
    }
}
