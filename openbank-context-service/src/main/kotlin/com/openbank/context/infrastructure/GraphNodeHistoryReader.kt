// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import org.hibernate.reactive.mutiny.Mutiny
import java.time.Instant
import java.util.UUID

/** Two indexed top-one candidates per requested key, not a sort over the whole history. */
internal class GraphNodeHistoryReader(
    private val sessions: Mutiny.SessionFactory,
    private val bank: String,
    private val generation: Long,
    private val timeoutMs: Int,
    private val mapper: ObjectMapper,
) {
    suspend fun find(keys: List<String>, asOf: Instant): List<ContextNodeEntity> {
        require(keys.size <= MAX_READ_NODES)
        if (keys.isEmpty()) return emptyList()
        val (retained, owners) = sessions.boundedGraphRead(timeoutMs) { session ->
            session.createNativeQuery("select set_config('openbank.bank_scope', :bank, true)", String::class.java)
                .setParameter("bank", bank).singleResult.flatMap {
                    session.createNativeQuery(SQL, ContextNodeEntity::class.java)
                        .setParameter("bank", bank).setParameter("generation", generation)
                        .setParameter("keys", mapper.writeValueAsString(keys)).setParameter("asOf", asOf).resultList
                }.flatMap { rows ->
                    if (rows.isEmpty()) {
                        Uni.createFrom().item(rows to emptySet<UUID>())
                    } else {
                        session.createNativeQuery(
                            "SELECT node_revision_id FROM context_graph_node_revisions " +
                                "WHERE node_revision_id IN (:ids) AND authoritative",
                            UUID::class.java,
                        ).setParameter("ids", rows.map { it.id }).resultList.map { rows to it.toSet() }
                    }
                }
        }.awaitSuspending()
        // A current row is an eligible baseline only at/after its retained source event time.
        // It cannot reconstruct an older overwritten revision or replace a newer retained owner fact.
        val baseline = sessions.boundedGraphRead(timeoutMs) { session ->
            session.createNativeQuery("select set_config('openbank.bank_scope', :bank, true)", String::class.java)
                .setParameter("bank", bank).singleResult.flatMap {
                    session.createQuery(
                        "from ContextNodeEntity where bankScope = :bank and projectionGeneration = :generation and " +
                            "namespace = 'COMPLAINT' and key in (:keys) and validFrom <= :asOf and " +
                            "(validTo is null or validTo > :asOf)",
                        ContextNodeEntity::class.java,
                    ).setParameter("bank", bank).setParameter("generation", generation)
                        .setParameter("keys", keys).setParameter("asOf", asOf).setMaxResults(MAX_READ_NODES).resultList
                }
        }.awaitSuspending()
        val historicalByKey = retained.associateBy { it.key }
        val baselineByKey = baseline.associateBy { it.key }
        return keys.mapNotNull { key ->
            val historical = historicalByKey[key]
            val current = baselineByKey[key]
            when {
                historical != null && historical.id in owners -> {
                    if (current?.supersedes(historical) == true) current else historical
                }
                current?.sourceOwnedBaseline() == true -> current
                else -> historical ?: current
            }
        }
    }

    private fun ContextNodeEntity.supersedes(historical: ContextNodeEntity): Boolean =
        sourceSystem == historical.sourceSystem && sourceVersion > historical.sourceVersion && sourceOwnedBaseline()

    private fun ContextNodeEntity.sourceOwnedBaseline(): Boolean = when (sourceSystem) {
        "domestic-payment" -> nodeType in setOf("PAYMENT", "PAYMENT_STAGE", "RAIL_EVIDENCE")
        "transaction-service" -> displayLabel in setOf("Booking transaction initiated", "Reversal booking initiated")
        "ledger-service" -> nodeType == "LEDGER_BOOKING"
        "clearing-service" -> nodeType in setOf("CLEARING_ITEM", "CLEARING_EVIDENCE")
        "sepa-payment" -> nodeType == "RETURN_EVIDENCE"
        else -> false
    }

    private companion object {
        const val MAX_READ_NODES = 100
        val SQL = """
            SELECT h.node_revision_id AS node_row_id, h.node_key, h.bank_scope, h.projection_generation,
                   h.namespace, h.node_type, h.source_system, h.source_ref, h.display_label, h.classification,
                   h.valid_from, NULL::timestamptz AS valid_to, h.recorded_at, h.source_version
            FROM jsonb_array_elements_text(CAST(CAST(:keys AS text) AS jsonb)) AS requested(node_key)
            CROSS JOIN LATERAL (
                SELECT candidate.* FROM (
                    (SELECT n.* FROM context_graph_node_revisions n
                     WHERE n.bank_scope = :bank AND n.projection_generation = :generation
                       AND n.namespace = 'COMPLAINT' AND n.node_key = requested.node_key
                       AND n.authoritative AND n.valid_from <= :asOf
                     ORDER BY n.source_version DESC LIMIT 1)
                    UNION ALL
                    (SELECT n.* FROM context_graph_node_revisions n
                     WHERE n.bank_scope = :bank AND n.projection_generation = :generation
                       AND n.namespace = 'COMPLAINT' AND n.node_key = requested.node_key
                       AND NOT n.authoritative AND n.valid_from <= :asOf
                     ORDER BY n.valid_from DESC, n.source_version DESC, n.source_system, n.aggregate_ref LIMIT 1)
                ) candidate ORDER BY candidate.authoritative DESC LIMIT 1
            ) h
        """.trimIndent()
    }
}
