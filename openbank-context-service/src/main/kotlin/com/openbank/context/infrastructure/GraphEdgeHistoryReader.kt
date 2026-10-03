// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import io.smallrye.mutiny.coroutines.awaitSuspending
import org.hibernate.reactive.mutiny.Mutiny
import java.time.Instant

internal data class GraphEdgeRule(val source: String, val targetPrefix: String, val relations: Set<String>)

/** Select complete eligible evidence rows before applying the result bound. */
internal class GraphEdgeHistoryReader(
    private val sessions: Mutiny.SessionFactory,
    private val mapper: ObjectMapper,
    private val bank: String,
    private val generation: Long,
    private val timeoutMs: Int,
) {
    suspend fun find(
        keys: List<String>,
        rules: List<GraphEdgeRule>,
        asOf: Instant,
        limit: Int,
        incoming: Boolean = false,
    ): List<ContextEdgeEntity> {
        if (keys.isEmpty() || limit <= 0) return emptyList()
        require(keys.size <= MAX_KEYS && rules.isNotEmpty() && limit <= MAX_RESULTS)
        val requestedColumn = if (incoming) "to_key" else "from_key"
        val adjacentColumn = if (incoming) "from_key" else "to_key"
        val allowlist = rules.indices.joinToString(" OR ") { index ->
            "(source_system = :source$index AND $adjacentColumn LIKE :prefix$index AND relation_type IN (:relations$index))"
        }
        val columns = "edge_id, bank_scope, projection_generation, namespace, from_key, to_key, relation_type, " +
            "source_system, evidence_ref, valid_from, valid_to, recorded_at, source_version"
        val sql = """
            WITH requested AS (
                SELECT DISTINCT jsonb_array_elements_text(CAST(CAST(:keys AS text) AS jsonb)) AS key
            ), eligible_history AS (
                SELECT candidate.* FROM requested CROSS JOIN LATERAL (
                SELECT $columns FROM context_graph_edge_revisions h
                WHERE bank_scope = :bank AND projection_generation = :generation AND namespace = 'COMPLAINT'
                  AND $requestedColumn = requested.key
                  AND valid_from <= :asOf AND ($allowlist)
                  AND NOT EXISTS (
                      SELECT 1 FROM context_graph_edge_revisions newer
                      WHERE newer.bank_scope = h.bank_scope AND newer.projection_generation = h.projection_generation
                        AND newer.edge_id = h.edge_id AND newer.valid_from <= :asOf
                        AND (newer.valid_from, newer.source_version, newer.evidence_ref) >
                            (h.valid_from, h.source_version, h.evidence_ref)
                  )
                ORDER BY valid_from DESC, edge_id DESC LIMIT :limit
                ) candidate
            ), eligible_baseline AS (
                SELECT candidate.* FROM requested CROSS JOIN LATERAL (
                SELECT $columns FROM context_edges baseline
                WHERE bank_scope = :bank AND projection_generation = :generation AND namespace = 'COMPLAINT'
                  AND $requestedColumn = requested.key
                  AND valid_from <= :asOf AND (valid_to IS NULL OR valid_to > :asOf) AND ($allowlist)
                  AND (retained_history_from IS NULL OR retained_history_from > :asOf)
                ORDER BY valid_from DESC, edge_id DESC LIMIT :limit
                ) candidate
            )
            SELECT * FROM (SELECT * FROM eligible_history UNION ALL SELECT * FROM eligible_baseline) selected
            ORDER BY valid_from DESC, edge_id DESC LIMIT :limit
        """.trimIndent()
        return sessions.boundedGraphRead(timeoutMs) { session ->
            session.createNativeQuery("SELECT set_config('openbank.bank_scope', :bank, true)", String::class.java)
                .setParameter("bank", bank).singleResult.flatMap {
                    val query = session.createNativeQuery(sql, ContextEdgeEntity::class.java)
                        .setParameter("bank", bank).setParameter("generation", generation)
                        .setParameter("keys", mapper.writeValueAsString(keys.distinct()))
                        .setParameter("asOf", asOf).setParameter("limit", limit)
                    rules.forEachIndexed { index, rule ->
                        query.setParameter("source$index", rule.source)
                            .setParameter("prefix$index", rule.targetPrefix)
                            .setParameter("relations$index", rule.relations)
                    }
                    query.resultList
                }
        }.awaitSuspending().sortedWith(compareBy<ContextEdgeEntity> { it.validFrom }.thenBy { it.id })
    }

    private companion object {
        const val MAX_KEYS = 201
        const val MAX_RESULTS = 201
    }
}
