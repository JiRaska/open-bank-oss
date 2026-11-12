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
    ): List<MonitoredSubjectCandidate> =
        // toFloat(): the live search binds its threshold as a float4 too, so the boundary agrees.
        pool.preparedQuery(MATCH_SQL).execute(Tuple.of(entrySearchText, threshold.toFloat(), limit))
            .awaitSuspending()
            .map { MonitoredSubjectCandidate(it.toSubject(), it.getDouble("match_score") ?: 0.0) }

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
        val UPSERT_SQL = """
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
        """.trimIndent()

        // The reverse of SanctionsEntryRepositoryImpl.search: there one name is matched against
        // every entry, here one entry against every subject, with the same function and operand
        // order. No index serves it (the indexable side of word_similarity is the haystack, and
        // here the haystack is the constant), so this is one pass over the registry per entry.
        // No ORDER BY: with one, the planner walks the primary key to feed the LIMIT and reads the
        // heap in random order, which measured 9.7 s per entry on a million rows.
        val MATCH_SQL = """
            SELECT $COLUMNS, m.match_score
            FROM sanctions_monitored_subjects s
            CROSS JOIN LATERAL (
                SELECT max(word_similarity(n, $1)) AS match_score FROM unnest(s.search_names) AS n
            ) m
            WHERE m.match_score >= $2
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
