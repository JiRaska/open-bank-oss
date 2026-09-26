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
    ): List<ContextEdgeEntity> {
        if (keys.isEmpty() || limit <= 0) return emptyList()
        require(keys.size <= MAX_KEYS && rules.isNotEmpty() && limit <= MAX_RESULTS)
        val allowlist = rules.indices.joinToString(" OR ") { index ->
            "(source_system = :source$index AND to_key LIKE :prefix$index AND relation_type IN (:relations$index))"
        }
        val columns = "edge_id, bank_scope, projection_generation, namespace, from_key, to_key, relation_type, " +
            "source_system, evidence_ref, valid_from, valid_to, recorded_at, source_version"
        val sql = """
            WITH eligible_history AS (
                SELECT $columns FROM context_graph_edge_revisions h
                WHERE bank_scope = :bank AND projection_generation = :generation AND namespace = 'COMPLAINT'
                  AND from_key IN (SELECT jsonb_array_elements_text(CAST(CAST(:keys AS text) AS jsonb)))
                  AND valid_from <= :asOf AND ($allowlist)
                  AND NOT EXISTS (
                      SELECT 1 FROM context_graph_edge_revisions newer
                      WHERE newer.bank_scope = h.bank_scope AND newer.projection_generation = h.projection_generation
                        AND newer.edge_id = h.edge_id AND newer.valid_from <= :asOf
                        AND (newer.valid_from, newer.source_version, newer.evidence_ref) >
                            (h.valid_from, h.source_version, h.evidence_ref)
                  )
            ), eligible_baseline AS (
                SELECT $columns FROM context_edges baseline
                WHERE bank_scope = :bank AND projection_generation = :generation AND namespace = 'COMPLAINT'
                  AND from_key IN (SELECT jsonb_array_elements_text(CAST(CAST(:keys AS text) AS jsonb)))
                  AND valid_from <= :asOf AND (valid_to IS NULL OR valid_to > :asOf) AND ($allowlist)
                  AND NOT EXISTS (
                      SELECT 1 FROM context_graph_edge_revisions retained
                      WHERE retained.bank_scope = baseline.bank_scope
                        AND retained.projection_generation = baseline.projection_generation
                        AND retained.from_key = baseline.from_key AND retained.to_key = baseline.to_key
                        AND retained.relation_type = baseline.relation_type
                        AND retained.source_system = baseline.source_system AND retained.valid_from <= :asOf
                  )
            )
            SELECT * FROM (SELECT * FROM eligible_history UNION ALL SELECT * FROM eligible_baseline) selected
            ORDER BY valid_from ASC, edge_id LIMIT :limit
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
        }.awaitSuspending()
    }

    private companion object {
        const val MAX_KEYS = 201
        const val MAX_RESULTS = 201
    }
}
