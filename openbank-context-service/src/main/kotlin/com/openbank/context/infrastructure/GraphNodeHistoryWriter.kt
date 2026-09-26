// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.infrastructure

import io.smallrye.mutiny.Uni
import org.hibernate.reactive.mutiny.Mutiny
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

internal data class GraphNodeObservation(
    val key: String,
    val type: String,
    val source: String,
    val sourceRef: String,
    val label: String,
    val occurredAt: Instant,
    val version: Long,
    val authoritative: Boolean,
)

/** Called before current-state deduplication in the same event transaction. */
internal object GraphNodeHistoryWriter {
    private const val MAX_EVENT_NODES = 10

    @Suppress("LongParameterList") // Complete scoped event provenance is required for the atomic write.
    fun append(
        session: Mutiny.Session,
        bankScope: String,
        generation: Long,
        eventKey: String,
        aggregateRef: String,
        nodes: List<GraphNodeObservation>,
        recordedAt: Instant,
    ): Uni<Void> {
        require(nodes.size in 1..MAX_EVENT_NODES && nodes.map { it.key }.distinct().size == nodes.size)
        var chain = session.createNativeQuery(
            "select set_config('openbank.bank_scope', :bank, true)",
            String::class.java,
        ).setParameter("bank", bankScope).singleResult.flatMap {
            val hash = digest(
                listOf(bankScope, generation.toString(), eventKey, aggregateRef) +
                    nodes.sortedBy { it.key }.flatMap { it.digestInput() },
            )
            appendEvent(session, bankScope, generation, eventKey, aggregateRef, recordedAt, hash)
        }
        nodes.forEach { node ->
            chain = chain.flatMap {
                appendNode(session, bankScope, generation, eventKey, aggregateRef, node, recordedAt)
            }
        }
        return chain
    }

    @Suppress("LongParameterList") // Scoped event identity and digest commit with all node observations.
    private fun appendEvent(
        session: Mutiny.Session,
        bank: String,
        generation: Long,
        evidence: String,
        aggregate: String,
        recordedAt: Instant,
        hash: String,
    ): Uni<Void> = session.createNativeMutationQuery(
        """INSERT INTO context_graph_event_revisions
           (bank_scope, projection_generation, evidence_ref, aggregate_ref, recorded_at, content_hash)
           VALUES (:bank, :generation, :evidence, :aggregate, :recorded, :hash)
           ON CONFLICT (bank_scope, projection_generation, evidence_ref) DO NOTHING
        """.trimIndent(),
    ).setParameter("bank", bank).setParameter("generation", generation).setParameter("evidence", evidence)
        .setParameter("aggregate", aggregate).setParameter("recorded", recordedAt).setParameter("hash", hash)
        .executeUpdate().flatMap {
            session.createNativeQuery(
                """SELECT content_hash FROM context_graph_event_revisions
                   WHERE bank_scope = :bank AND projection_generation = :generation AND evidence_ref = :evidence
                """.trimIndent(),
                String::class.java,
            ).setParameter("bank", bank).setParameter("generation", generation).setParameter("evidence", evidence)
                .singleResult.invoke { stored -> check(stored == hash) { "conflicting graph node revision" } }
                .replaceWithVoid()
        }

    private fun GraphNodeObservation.digestInput(): List<String> = listOf(
        key,
        type,
        source,
        sourceRef,
        label,
        occurredAt.toString(),
        version.toString(),
        authoritative.toString(),
    )

    private fun digest(values: List<String>): String = values.joinToString("") { "${it.length}:$it" }
        .toByteArray(StandardCharsets.UTF_8).let {
            MessageDigest.getInstance("SHA-256").digest(it).joinToString("") { byte -> "%02x".format(byte) }
        }

    @Suppress("LongParameterList") // Complete scoped event provenance is required for each fact.
    private fun appendNode(
        session: Mutiny.Session,
        bank: String,
        generation: Long,
        evidence: String,
        aggregate: String,
        node: GraphNodeObservation,
        recordedAt: Instant,
    ): Uni<Void> {
        val id = UUID.nameUUIDFromBytes("$bank|$generation|$evidence|${node.key}".toByteArray(StandardCharsets.UTF_8))
        val hash = digest(listOf(bank, generation.toString(), evidence, aggregate) + node.digestInput())
        return session.createNativeMutationQuery(
            """INSERT INTO context_graph_node_revisions
               (node_revision_id, bank_scope, projection_generation, namespace, node_key, node_type,
                source_system, source_ref, aggregate_ref, evidence_ref, display_label, classification,
                valid_from, recorded_at, source_version, authoritative, content_hash)
               VALUES (:id, :bank, :generation, 'COMPLAINT', :key, :type, :source, :sourceRef,
                       :aggregate, :evidence, :label, 'RESTRICTED', :at, :recorded, :version, :owner, :hash)
               ON CONFLICT (bank_scope, projection_generation, evidence_ref, node_key) DO NOTHING
            """.trimIndent(),
        ).setParameter("id", id).setParameter("bank", bank).setParameter("generation", generation)
            .setParameter("key", node.key).setParameter("type", node.type).setParameter("source", node.source)
            .setParameter("sourceRef", node.sourceRef).setParameter("aggregate", aggregate)
            .setParameter("evidence", evidence).setParameter("label", node.label).setParameter("at", node.occurredAt)
            .setParameter("recorded", recordedAt).setParameter("version", node.version)
            .setParameter("owner", node.authoritative).setParameter("hash", hash).executeUpdate().flatMap {
                session.createNativeQuery(
                    "SELECT content_hash FROM context_graph_node_revisions WHERE node_revision_id = :id",
                    String::class.java,
                ).setParameter("id", id).singleResult.invoke { stored ->
                    check(stored == hash) { "conflicting graph node revision" }
                }.replaceWithVoid()
            }
    }
}
