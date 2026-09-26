// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.infrastructure

import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import org.hibernate.reactive.mutiny.Mutiny
import java.io.Serializable
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

data class ComplaintRevisionKey(
    var bankScope: String = "",
    var projectionGeneration: Long = 1,
    var complaintId: UUID = UUID(0, 0),
    var sourceVersion: Long = 0,
) : Serializable {
    private companion object {
        const val serialVersionUID = 1L
    }
}

@Entity
@IdClass(ComplaintRevisionKey::class)
@Table(name = "context_complaint_revisions")
class ComplaintRevisionEntity {
    @Id
    @Column(name = "bank_scope")
    lateinit var bankScope: String

    @Id
    @Column(name = "projection_generation")
    var projectionGeneration: Long = 1

    @Id
    @Column(name = "complaint_id")
    lateinit var complaintId: UUID

    @Id
    @Column(name = "source_version")
    var sourceVersion: Long = 0

    @Column(name = "reference")
    lateinit var reference: String

    @Column(name = "event_key")
    lateinit var eventKey: String

    @Column(name = "status")
    lateinit var status: String

    @Column(name = "account_id")
    var accountId: UUID? = null

    @Column(name = "transaction_id")
    var transactionId: UUID? = null

    @Column(name = "dispute_id")
    var disputeId: UUID? = null

    @Column(name = "occurred_at")
    lateinit var occurredAt: Instant

    @Column(name = "recorded_at")
    lateinit var recordedAt: Instant
}

/** Source event-time snapshots; receipt time does not decide which revision is effective. */
internal class ComplaintSnapshotReader(
    private val sessions: Mutiny.SessionFactory,
    private val bankScope: String,
    private val generation: Long,
    private val timeoutMs: Int,
) {
    suspend fun find(root: String, asOf: Instant): ComplaintSnapshot? {
        if (!root.startsWith("complaint:")) return null
        val revision = ContextSqlOperation.execute(sessions, timeoutMs) { operation ->
            operation.sql { session ->
                session.createNativeQuery("select set_config('openbank.bank_scope', :bank, true)", String::class.java)
                    .setParameter("bank", bankScope).singleResult
            }.flatMap {
                operation.sql { session ->
                    session.createQuery(
                        "from ComplaintRevisionEntity where bankScope = :bank and projectionGeneration = :generation " +
                            "and reference = :reference and occurredAt <= :asOf order by sourceVersion desc",
                        ComplaintRevisionEntity::class.java,
                    ).setParameter("bank", bankScope).setParameter("generation", generation)
                        .setParameter("reference", root.removePrefix("complaint:"))
                        .setParameter("asOf", asOf).setMaxResults(1).singleResultOrNull
                }
            }
        }.awaitSuspending() ?: return null
        return ComplaintSnapshot(revision)
    }
}

/** Direct references come exclusively from the selected source graph-input revision. */
internal class ComplaintSnapshot(private val revision: ComplaintRevisionEntity) {
    val root = node(
        "complaint:${revision.reference}",
        "COMPLAINT",
        revision.complaintId,
        "Complaint ${revision.reference} · ${revision.status}",
    )
    private val references = listOfNotNull(
        revision.accountId?.let { node("account:$it", "ACCOUNT", it, "Account reference") to "CONCERNS_ACCOUNT" },
        revision.transactionId?.let {
            node("booking-transaction:$it", "TRANSACTION", it, "Transaction reference") to "CONCERNS_TRANSACTION"
        },
        revision.disputeId?.let { node("dispute:$it", "DISPUTE", it, "Dispute reference") to "RELATED_DISPUTE" },
    )
    val nodes = listOf(root) + references.map { it.first }
    val edges = references.map { (target, relation) ->
        ContextEdgeEntity().apply {
            val projectedId = stableId("COMPLAINT|${root.key}|${target.key}|$relation")
            id = stableId("${revision.bankScope}|${revision.projectionGeneration}|$projectedId")
            bankScope = revision.bankScope
            projectionGeneration = revision.projectionGeneration
            namespace = "COMPLAINT"
            fromKey = root.key
            toKey = target.key
            relationType = relation
            sourceSystem = "dispute-service"
            evidenceRef = revision.eventKey
            validFrom = revision.occurredAt
            recordedAt = revision.recordedAt
            sourceVersion = revision.sourceVersion
        }
    }

    private fun node(key: String, type: String, sourceId: UUID, label: String) = ContextNodeEntity().apply {
        id = stableId("${revision.bankScope}|${revision.projectionGeneration}|$key")
        this.key = key
        bankScope = revision.bankScope
        projectionGeneration = revision.projectionGeneration
        namespace = "COMPLAINT"
        nodeType = type
        sourceSystem = "dispute-service"
        sourceRef = sourceId.toString()
        displayLabel = label
        classification = "RESTRICTED"
        validFrom = revision.occurredAt
        recordedAt = revision.recordedAt
        sourceVersion = revision.sourceVersion
    }

    private fun stableId(value: String): UUID = UUID.nameUUIDFromBytes(value.toByteArray(StandardCharsets.UTF_8))
}

/** Keeps snapshot-specific selection out of the common bounded evidence traversal. */
internal class GraphNeighborhoodRoot(
    private val root: String,
    val edges: List<ContextEdgeEntity>,
    private val snapshot: ComplaintSnapshot? = null,
) {
    fun boundedKeys(boundedEdges: List<ContextEdgeEntity>, maxNodes: Int): List<String> {
        val edgeKeys = boundedEdges.flatMap { listOf(it.fromKey, it.toKey) }
        val keys = if (snapshot == null) edgeKeys + root else listOf(root) + edgeKeys
        return keys.distinct().take(maxNodes)
    }

    fun mergeNodes(keys: List<String>, projectedNodes: List<ContextNodeEntity>): List<ContextNodeEntity> {
        val historicalNodes = snapshot?.nodes?.associateBy(ContextNodeEntity::key) ?: return projectedNodes
        val projectedByKey = projectedNodes.associateBy(ContextNodeEntity::key)
        return keys.mapNotNull { key ->
            selectNode(key, historicalNodes[key], projectedByKey[key])
        }
    }

    // Mutable complaint references never replace the selected revision's evidence.
    // Independent payment projections retain their richer reference nodes.
    private fun selectNode(
        key: String,
        historical: ContextNodeEntity?,
        projected: ContextNodeEntity?,
    ): ContextNodeEntity? {
        if (historical == null) return projected
        if (key == root) return historical
        if (projected?.sourceSystem == "dispute-service") return historical
        return projected ?: historical
    }
}
