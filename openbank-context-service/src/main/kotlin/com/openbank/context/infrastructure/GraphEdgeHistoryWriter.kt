// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.infrastructure

import io.smallrye.mutiny.Uni
import org.hibernate.reactive.mutiny.Mutiny
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

internal data class GraphEdgeObservation(
    val from: String,
    val to: String,
    val relation: String,
    val source: String,
    val validFrom: Instant,
    val version: Long,
)

/** Called before current-state deduplication in the same event transaction. */
internal object GraphEdgeHistoryWriter {
    private const val MAX_EVENT_EDGES = 10

    @Suppress("LongParameterList") // Complete scoped event provenance is required for the atomic write.
    fun append(
        session: Mutiny.Session,
        bankScope: String,
        generation: Long,
        eventKey: String,
        aggregateRef: String,
        observations: List<GraphEdgeObservation>,
        recordedAt: Instant,
    ): Uni<Void> {
        require(observations.size <= MAX_EVENT_EDGES)
        require(observations.all { it.version >= 0 })
        val sorted = observations.sortedBy { digest(it.identityInput()) }
        require(sorted.map { it.identityInput() }.distinct().size == sorted.size)
        val hash = digest(
            listOf(bankScope, generation.toString(), eventKey, aggregateRef, sorted.size.toString()) +
                sorted.flatMap { it.digestInput() },
        )
        var chain = session.createNativeQuery(
            "select set_config('openbank.bank_scope', :bank, true)",
            String::class.java,
        ).setParameter("bank", bankScope).singleResult.flatMap {
            appendEvent(session, bankScope, generation, eventKey, aggregateRef, recordedAt, hash)
        }
        sorted.forEach { edge ->
            chain = chain.flatMap {
                appendEdge(session, bankScope, generation, eventKey, aggregateRef, edge, recordedAt)
            }
        }
        return chain
    }

    @Suppress("LongParameterList") // The digest covers the complete edge set, including an empty set.
    private fun appendEvent(
        session: Mutiny.Session,
        bank: String,
        generation: Long,
        evidence: String,
        aggregate: String,
        recordedAt: Instant,
        hash: String,
    ): Uni<Void> = session.createNativeMutationQuery(
        """INSERT INTO context_graph_edge_events
           (bank_scope, projection_generation, evidence_ref, aggregate_ref, recorded_at, content_hash)
           VALUES (:bank, :generation, :evidence, :aggregate, :recorded, :hash)
           ON CONFLICT (bank_scope, projection_generation, evidence_ref) DO NOTHING
        """.trimIndent(),
    ).setParameter("bank", bank).setParameter("generation", generation).setParameter("evidence", evidence)
        .setParameter("aggregate", aggregate).setParameter("recorded", recordedAt).setParameter("hash", hash)
        .executeUpdate().flatMap {
            session.createNativeQuery(
                "SELECT content_hash FROM context_graph_edge_events " +
                    "WHERE bank_scope = :bank AND projection_generation = :generation AND evidence_ref = :evidence",
                String::class.java,
            ).setParameter("bank", bank).setParameter("generation", generation).setParameter("evidence", evidence)
                .singleResult.invoke { stored -> check(stored == hash) { "conflicting graph edge event" } }
                .replaceWithVoid()
        }

    @Suppress("LongParameterList") // Each observation retains complete scoped source provenance.
    private fun appendEdge(
        session: Mutiny.Session,
        bank: String,
        generation: Long,
        evidence: String,
        aggregate: String,
        edge: GraphEdgeObservation,
        recordedAt: Instant,
    ): Uni<Void> {
        val identity = listOf(bank, generation.toString(), "COMPLAINT") + edge.identityInput()
        val edgeId = stableId(identity)
        val observationId = stableId(identity + evidence)
        val hash = digest(listOf(bank, generation.toString(), evidence, aggregate) + edge.digestInput())
        return session.createNativeMutationQuery(
            """INSERT INTO context_graph_edge_revisions
               (edge_revision_id, edge_id, bank_scope, projection_generation, namespace, from_key, to_key,
                relation_type, source_system, aggregate_ref, evidence_ref, valid_from, recorded_at,
                source_version, content_hash)
               VALUES (:id, :edgeId, :bank, :generation, 'COMPLAINT', :fromKey, :toKey, :relation,
                       :source, :aggregate, :evidence, :at, :recorded, :version, :hash)
               ON CONFLICT (bank_scope, projection_generation, evidence_ref, edge_id) DO NOTHING
            """.trimIndent(),
        ).setParameter("id", observationId).setParameter("edgeId", edgeId).setParameter("bank", bank)
            .setParameter("generation", generation).setParameter("fromKey", edge.from).setParameter("toKey", edge.to)
            .setParameter("relation", edge.relation).setParameter("source", edge.source)
            .setParameter("aggregate", aggregate).setParameter("evidence", evidence).setParameter("at", edge.validFrom)
            .setParameter("recorded", recordedAt).setParameter("version", edge.version).setParameter("hash", hash)
            .executeUpdate().flatMap {
                session.createNativeQuery(
                    "SELECT content_hash FROM context_graph_edge_revisions WHERE edge_revision_id = :id",
                    String::class.java,
                ).setParameter("id", observationId).singleResult.invoke { stored ->
                    check(stored == hash) { "conflicting graph edge revision" }
                }.flatMap {
                    session.createNativeMutationQuery(
                        """UPDATE context_edges SET retained_history_from = LEAST(retained_history_from, :at)
                           WHERE bank_scope = :bank AND projection_generation = :generation
                             AND namespace = 'COMPLAINT' AND from_key = :fromKey AND to_key = :toKey
                             AND relation_type = :relation AND source_system = :source
                        """.trimIndent(),
                    ).setParameter("at", edge.validFrom).setParameter("bank", bank)
                        .setParameter("generation", generation).setParameter("fromKey", edge.from)
                        .setParameter("toKey", edge.to).setParameter("relation", edge.relation)
                        .setParameter("source", edge.source).executeUpdate().replaceWithVoid()
                }
            }
    }

    private fun GraphEdgeObservation.identityInput(): List<String> = listOf(source, from, to, relation)

    private fun GraphEdgeObservation.digestInput(): List<String> = identityInput() +
        listOf(validFrom.toString(), version.toString())

    private fun encoded(values: List<String>): ByteArray = values.joinToString("") { "${it.length}:$it" }
        .toByteArray(StandardCharsets.UTF_8)

    private fun stableId(values: List<String>): UUID = UUID.nameUUIDFromBytes(encoded(values))

    private fun digest(values: List<String>): String = MessageDigest.getInstance("SHA-256").digest(encoded(values))
        .joinToString("") { byte -> "%02x".format(byte) }
}
