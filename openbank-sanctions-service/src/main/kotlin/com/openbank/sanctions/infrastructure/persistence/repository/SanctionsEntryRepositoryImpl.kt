// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.sanctions.infrastructure.persistence.repository

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.sanctions.application.port.out.SanctionsEntryRepository
import com.openbank.sanctions.domain.model.*
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.mutiny.pgclient.PgPool
import io.vertx.mutiny.sqlclient.Tuple
import io.vertx.sqlclient.Tuple as CoreTuple
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.Instant
import java.util.UUID

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
              AND word_similarity($1, search_text) >= $2
            ORDER BY match_score DESC
            LIMIT $3
        """.trimIndent()

        val rows = pool
            .preparedQuery(sql)
            .execute(Tuple.of(normalizedQuery, threshold.toFloat(), limit))
            .awaitSuspending()

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

        // Batch in chunks of 500 to avoid oversized messages
        var total = 0
        for (chunk in tuples.chunked(500)) {
            pool.preparedQuery(sql).executeBatch(chunk).awaitSuspending()
            total += chunk.size
        }
        return total
    }

    override suspend fun deactivateByListType(listType: SanctionsListType): Int {
        val result = pool
            .preparedQuery(
                "UPDATE sanctions_entries SET active = false, updated_at = NOW()" +
                    " WHERE list_type = $1 AND active = true",
            )
            .execute(Tuple.of(listType.name))
            .awaitSuspending()
        return result.rowCount()
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

    private fun rowToEntry(row: io.vertx.mutiny.sqlclient.Row): SanctionsEntry {
        val idStr = row.getString("id") ?: UUID.randomUUID().toString()
        val listTypeStr = row.getString("list_type") ?: ""
        val listType = runCatching { SanctionsListType.valueOf(listTypeStr) }.getOrDefault(SanctionsListType.OFAC_SDN)
        val entityTypeStr = row.getString("entity_type") ?: "INDIVIDUAL"
        val entityType = runCatching { EntityType.valueOf(entityTypeStr) }.getOrDefault(EntityType.INDIVIDUAL)

        val aliasesJson = row.getString("aliases_json") ?: "[]"
        val nationalitiesJson = row.getString("nationalities") ?: "[]"
        val programsJson = row.getString("programs") ?: "[]"

        val createdAt = row.getOffsetDateTime("created_at")?.toInstant() ?: Instant.now(clock)
        val updatedAt = row.getOffsetDateTime("updated_at")?.toInstant() ?: Instant.now(clock)

        return SanctionsEntry(
            id = UUID.fromString(idStr),
            listType = listType,
            externalId = row.getString("external_id"),
            entityType = entityType,
            primaryName = row.getString("primary_name") ?: "",
            aliases = runCatching { mapper.readValue<List<String>>(aliasesJson) }.getOrDefault(emptyList()),
            dateOfBirth = row.getString("date_of_birth"),
            nationalities = runCatching { mapper.readValue<List<String>>(nationalitiesJson) }.getOrDefault(emptyList()),
            programs = runCatching { mapper.readValue<List<String>>(programsJson) }.getOrDefault(emptyList()),
            searchText = "", // not needed after fetch, only used on insert
            active = row.getBoolean("active") ?: true,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }
}
