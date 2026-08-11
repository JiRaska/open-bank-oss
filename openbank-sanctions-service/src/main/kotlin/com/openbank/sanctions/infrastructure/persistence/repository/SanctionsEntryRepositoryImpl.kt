// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.infrastructure.persistence.repository

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.sanctions.application.port.out.SanctionsEntryRepository
import com.openbank.sanctions.domain.model.*
import io.quarkus.logging.Log
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.mutiny.pgclient.PgPool
import io.vertx.mutiny.sqlclient.Row
import io.vertx.mutiny.sqlclient.RowSet
import io.vertx.mutiny.sqlclient.Tuple
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.Instant
import java.util.Locale
import java.util.UUID
import io.vertx.sqlclient.Tuple as CoreTuple

/**
 * PgPool-based implementation of [SanctionsEntryRepository].
 * Uses native PostgreSQL pg_trgm similarity for fuzzy name matching.
 * PgPool is used (not Panache) because similarity() native queries
 * need direct row-level result access with score column.
 */
@ApplicationScoped
class SanctionsEntryRepositoryImpl(private val pool: PgPool, private val clock: Clock) : SanctionsEntryRepository {

    private val mapper = jacksonObjectMapper().findAndRegisterModules()

    override suspend fun search(
        normalizedQuery: String,
        listTypes: List<SanctionsListType>,
        threshold: Double,
        limit: Int,
    ): List<SanctionsEntryMatch> {
        if (listTypes.isEmpty()) return emptyList()
        // Build IN clause from controlled enum values (no injection risk)
        val inClause = listTypes.joinToString(",") { "'${it.name}'" }
        val sql = """
            SELECT id::text, list_type, external_id, entity_type, primary_name, aliases_json,
                   date_of_birth, nationalities, programs, active, created_at, updated_at,
                   word_similarity($1, search_text) AS match_score
            FROM sanctions_entries
            WHERE list_type IN ($inClause)
              AND active = true
              AND $1 <% search_text
              AND word_similarity($1, search_text) >= $2
            ORDER BY match_score DESC
            LIMIT $3
        """.trimIndent()

        // `<%` is what makes idx_entries_search_trgm usable; the word_similarity() call above it is
        // NOT an indexable predicate, so without the operator this is a parallel sequential scan of
        // every active row — measured at 4002 ms over 814,705 rows against 134 ms with the index,
        // on every screen, twice per payment (#3265).
        //
        // `<%` takes its cutoff from the session GUC pg_trgm.word_similarity_threshold, never from a
        // bind parameter, and the default (0.6) is NOT the caller's [threshold]. Leaving it inherited
        // would make a sanctions screen's matching depend on ambient session state: raised above the
        // caller's value it silently DROPS true matches, which is the one failure mode here that must
        // not be possible. So the GUC is set per call, from the same [threshold] the exact filter
        // uses, inside a transaction so `SET LOCAL` cannot leak to the next borrower of the pooled
        // connection. The word_similarity() predicate is kept as well: it is the authoritative filter,
        // and it makes the result set identical whatever the operator admits.
        val rows = pool.withTransaction { conn ->
            conn.query("SET LOCAL pg_trgm.word_similarity_threshold = ${threshold.toGucLiteral()}")
                .execute()
                .flatMap {
                    conn.preparedQuery(sql).execute(Tuple.of(normalizedQuery, threshold.toFloat(), limit))
                }
        }.awaitSuspending()

        return rows.map { row ->
            val score = row.getDouble("match_score") ?: 0.0
            val entry = rowToEntry(row)
            SanctionsEntryMatch(entry = entry, matchedName = entry.primaryName, score = score)
        }
    }

    override suspend fun upsertAll(entries: List<SanctionsEntry>): Int {
        if (entries.isEmpty()) return 0
        val sql = """
            INSERT INTO sanctions_entries
                (list_type, external_id, entity_type, primary_name, aliases_json,
                 date_of_birth, nationalities, programs, search_text, active)
            VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, true)
            ON CONFLICT (list_type, external_id) WHERE external_id IS NOT NULL DO UPDATE SET
                entity_type  = EXCLUDED.entity_type,
                primary_name = EXCLUDED.primary_name,
                aliases_json = EXCLUDED.aliases_json,
                date_of_birth= EXCLUDED.date_of_birth,
                nationalities= EXCLUDED.nationalities,
                programs     = EXCLUDED.programs,
                search_text  = EXCLUDED.search_text,
                active       = true,
                updated_at   = NOW()
            WHERE
                sanctions_entries.active        IS NOT TRUE
                OR sanctions_entries.entity_type   IS DISTINCT FROM EXCLUDED.entity_type
                OR sanctions_entries.primary_name  IS DISTINCT FROM EXCLUDED.primary_name
                OR sanctions_entries.aliases_json  IS DISTINCT FROM EXCLUDED.aliases_json
                OR sanctions_entries.date_of_birth IS DISTINCT FROM EXCLUDED.date_of_birth
                OR sanctions_entries.nationalities IS DISTINCT FROM EXCLUDED.nationalities
                OR sanctions_entries.programs      IS DISTINCT FROM EXCLUDED.programs
                OR sanctions_entries.search_text   IS DISTINCT FROM EXCLUDED.search_text
            RETURNING 1
        """.trimIndent()

        // CoreTuple.wrap(List) handles arbitrary number of params; then wrap in mutiny Tuple
        val tuples: List<Tuple> = entries.map { e ->
            Tuple.newInstance(
                CoreTuple.wrap(
                    listOf(
                        e.listType.name,
                        e.externalId,
                        e.entityType.name,
                        e.primaryName,
                        mapper.writeValueAsString(e.aliases),
                        e.dateOfBirth,
                        mapper.writeValueAsString(e.nationalities),
                        mapper.writeValueAsString(e.programs),
                        e.searchText,
                    ),
                ),
            )
        }

        // Batch in chunks of 500 to avoid oversized messages. Count via RETURNING, not chunk.size:
        // before the WHERE-guard above existed, every conflicting row always got written, so
        // "rows submitted" and "rows written" happened to be the same number and this bug was
        // invisible. Now that an unchanged row is correctly skipped, chunk.size would silently
        // over-report — "Imported 776,000 entries" every single refresh even when almost nothing
        // changed, which is exactly the kind of number nobody would think to question.
        var total = 0
        for (chunk in tuples.chunked(500)) {
            var rowSet: RowSet<Row>? = pool.preparedQuery(sql).executeBatch(chunk).awaitSuspending()
            while (rowSet != null) {
                total += rowSet.size()
                rowSet = rowSet.next()
            }
        }
        return total
    }

    override suspend fun upsertAllReturningChanged(entries: List<SanctionsEntry>): Set<String> {
        if (entries.isEmpty()) return emptySet()
        // Same INSERT … ON CONFLICT … WHERE-guard as upsertAll, but RETURNING external_id so the
        // caller learns *which* entries changed, not just how many. Kept as a separate statement
        // (not a flag on upsertAll) so the hot refresh path pays zero cost for a set it ignores.
        val sql = """
            INSERT INTO sanctions_entries
                (list_type, external_id, entity_type, primary_name, aliases_json,
                 date_of_birth, nationalities, programs, search_text, active)
            VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, true)
            ON CONFLICT (list_type, external_id) WHERE external_id IS NOT NULL DO UPDATE SET
                entity_type  = EXCLUDED.entity_type,
                primary_name = EXCLUDED.primary_name,
                aliases_json = EXCLUDED.aliases_json,
                date_of_birth= EXCLUDED.date_of_birth,
                nationalities= EXCLUDED.nationalities,
                programs     = EXCLUDED.programs,
                search_text  = EXCLUDED.search_text,
                active       = true,
                updated_at   = NOW()
            WHERE
                sanctions_entries.active        IS NOT TRUE
                OR sanctions_entries.entity_type   IS DISTINCT FROM EXCLUDED.entity_type
                OR sanctions_entries.primary_name  IS DISTINCT FROM EXCLUDED.primary_name
                OR sanctions_entries.aliases_json  IS DISTINCT FROM EXCLUDED.aliases_json
                OR sanctions_entries.date_of_birth IS DISTINCT FROM EXCLUDED.date_of_birth
                OR sanctions_entries.nationalities IS DISTINCT FROM EXCLUDED.nationalities
                OR sanctions_entries.programs      IS DISTINCT FROM EXCLUDED.programs
                OR sanctions_entries.search_text   IS DISTINCT FROM EXCLUDED.search_text
            RETURNING external_id
        """.trimIndent()

        val tuples: List<Tuple> = entries.map { e ->
            Tuple.newInstance(
                CoreTuple.wrap(
                    listOf(
                        e.listType.name,
                        e.externalId,
                        e.entityType.name,
                        e.primaryName,
                        mapper.writeValueAsString(e.aliases),
                        e.dateOfBirth,
                        mapper.writeValueAsString(e.nationalities),
                        mapper.writeValueAsString(e.programs),
                        e.searchText,
                    ),
                ),
            )
        }

        val changed = mutableSetOf<String>()
        for (chunk in tuples.chunked(500)) {
            changed += pool.preparedQuery(sql).executeBatch(chunk).awaitSuspending().collectChangedIds()
        }
        return changed
    }

    /** Drain a batched `RETURNING external_id` result set (possibly multi-rowset) into a set. */
    private fun RowSet<Row>.collectChangedIds(): Set<String> {
        val ids = mutableSetOf<String>()
        var rowSet: RowSet<Row>? = this
        while (rowSet != null) {
            for (row in rowSet) {
                row.getString("external_id")?.let { ids += it }
            }
            rowSet = rowSet.next()
        }
        return ids
    }

    override suspend fun deactivateMissing(listType: SanctionsListType, presentExternalIds: Set<String>): Int {
        // Array-parameter anti-join, not a temp table: vertx-pg-client binds a Kotlin
        // Array<String> as a native Postgres text[] parameter, so this is one round trip
        // regardless of set size (~776k for PEP_GLOBAL) and needs no session-scoped state to
        // clean up. `NOT (external_id = ANY($2))` — not `NOT IN` — because `NOT IN` returns
        // NULL (not TRUE) the moment the array contains any NULL, silently matching zero rows;
        // `= ANY` has no such trap, and an empty presentExternalIds array still deactivates
        // everything as intended (a genuinely empty upstream feed).
        val result = pool
            .preparedQuery(
                """
                UPDATE sanctions_entries
                SET active = false, updated_at = NOW()
                WHERE list_type = $1
                  AND active = true
                  AND external_id IS NOT NULL
                  AND NOT (external_id = ANY($2))
                """.trimIndent(),
            )
            .execute(Tuple.of(listType.name, presentExternalIds.toTypedArray()))
            .awaitSuspending()
        return result.rowCount()
    }

    override suspend fun deactivateMissingReturning(
        listType: SanctionsListType,
        presentExternalIds: Set<String>,
    ): Set<String> {
        // Same anti-join predicate as deactivateMissing (see its comment for why `NOT (= ANY)`),
        // with RETURNING external_id so the caller learns which entries were dropped upstream.
        val rows = pool
            .preparedQuery(
                """
                UPDATE sanctions_entries
                SET active = false, updated_at = NOW()
                WHERE list_type = $1
                  AND active = true
                  AND external_id IS NOT NULL
                  AND NOT (external_id = ANY($2))
                RETURNING external_id
                """.trimIndent(),
            )
            .execute(Tuple.of(listType.name, presentExternalIds.toTypedArray()))
            .awaitSuspending()
        val changed = mutableSetOf<String>()
        for (row in rows) {
            row.getString("external_id")?.let { changed += it }
        }
        return changed
    }

    override suspend fun countByListType(listType: SanctionsListType): Long {
        val rows = pool
            .preparedQuery(
                "SELECT COUNT(*) AS cnt FROM sanctions_entries WHERE list_type = $1 AND active = true",
            )
            .execute(Tuple.of(listType.name))
            .awaitSuspending()
        return rows.iterator().next().getLong("cnt") ?: 0L
    }

    /**
     * Parses a stored enum/JSON column, falling back to [default] on a malformed value — but
     * LOUDLY: a silent `runCatching{}.getOrDefault()` here previously made a corrupted DB value
     * (e.g. a `list_type` string that no longer matches [SanctionsListType], or malformed
     * `aliases_json`) indistinguishable from genuinely absent data, with zero operator-visible
     * signal. [rowId] is logged so a bad row can actually be found and repaired.
     */
    internal fun <T> parseColumnOrWarn(
        rowId: String,
        column: String,
        raw: String,
        default: T,
        parse: (String) -> T,
    ): T = runCatching { parse(raw) }.getOrElse {
        Log.warnf(
            "sanctions_entries row %s has an unparseable '%s' column (%s: %s) — falling back to %s",
            rowId,
            column,
            it.javaClass.simpleName,
            it.message,
            default,
        )
        default
    }

    private fun rowToEntry(row: io.vertx.mutiny.sqlclient.Row): SanctionsEntry {
        val idStr = row.getString("id") ?: UUID.randomUUID().toString()
        val listType =
            parseColumnOrWarn(idStr, "list_type", row.getString("list_type") ?: "", SanctionsListType.OFAC_SDN) {
                SanctionsListType.valueOf(it)
            }
        val entityType =
            parseColumnOrWarn(
                idStr,
                "entity_type",
                row.getString("entity_type") ?: "INDIVIDUAL",
                EntityType.INDIVIDUAL,
            ) {
                EntityType.valueOf(it)
            }
        val aliases =
            parseColumnOrWarn(idStr, "aliases_json", row.getString("aliases_json") ?: "[]", emptyList<String>()) {
                mapper.readValue<List<String>>(it)
            }
        val nationalities =
            parseColumnOrWarn(idStr, "nationalities", row.getString("nationalities") ?: "[]", emptyList<String>()) {
                mapper.readValue<List<String>>(it)
            }
        val programs = parseColumnOrWarn(idStr, "programs", row.getString("programs") ?: "[]", emptyList<String>()) {
            mapper.readValue<List<String>>(it)
        }

        val createdAt = row.getOffsetDateTime("created_at")?.toInstant() ?: Instant.now(clock)
        val updatedAt = row.getOffsetDateTime("updated_at")?.toInstant() ?: Instant.now(clock)

        return SanctionsEntry(
            id = UUID.fromString(idStr),
            listType = listType,
            externalId = row.getString("external_id"),
            entityType = entityType,
            primaryName = row.getString("primary_name") ?: "",
            aliases = aliases,
            dateOfBirth = row.getString("date_of_birth"),
            nationalities = nationalities,
            programs = programs,
            searchText = "", // not needed after fetch, only used on insert
            active = row.getBoolean("active") ?: true,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }

    /**
     * Render [this] as a literal for `SET LOCAL pg_trgm.word_similarity_threshold`.
     *
     * `SET` does not accept bind parameters, so the value has to be interpolated. It is made safe by
     * construction rather than by trusting the caller: the receiver is a [Double], it is clamped
     * into `0.0..1.0` (the operator's own domain), and it is formatted with an explicit `Locale.ROOT`
     * pattern. A `Double` cannot carry SQL, and the clamp means a nonsensical threshold cannot widen
     * the match set either.
     *
     * `Locale.ROOT` is load-bearing, not decoration: under a comma-decimal default locale
     * `"%.4f".format(0.85)` yields `0,8500`, which Postgres reads as the integer list `0,8500` and
     * the statement fails at runtime — in a service that runs with `-Duser.language=en` in CI and
     * whatever the pod carries in production.
     */
    private fun Double.toGucLiteral(): String = String.format(Locale.ROOT, "%.4f", coerceIn(0.0, 1.0))
}
