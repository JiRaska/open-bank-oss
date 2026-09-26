// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.context.application.CaseAssignmentPort
import com.openbank.context.application.ContextDisclosureAudit
import com.openbank.context.application.ContextGraphPort
import com.openbank.context.application.ContextReadAudit
import com.openbank.context.application.ContextReadAuditPort
import com.openbank.context.domain.ContextEdge
import com.openbank.context.domain.ContextNamespace
import com.openbank.context.domain.ContextNeighborhood
import com.openbank.context.domain.ContextNode
import com.openbank.context.domain.DataClassification
import com.openbank.libs.domain.identifiers.Ids
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.hibernate.reactive.mutiny.Mutiny
import java.io.Serializable
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Entity
@Table(name = "context_nodes")
class ContextNodeEntity : PanacheEntityBase() {
    @Id
    @Column(name = "node_row_id")
    lateinit var id: UUID

    @Column(name = "node_key")
    lateinit var key: String

    @Column(name = "bank_scope")
    lateinit var bankScope: String

    @Column(name = "projection_generation")
    var projectionGeneration: Long = 1

    @Column(name = "namespace")
    lateinit var namespace: String

    @Column(name = "node_type")
    lateinit var nodeType: String

    @Column(name = "source_system")
    lateinit var sourceSystem: String

    @Column(name = "source_ref")
    lateinit var sourceRef: String

    @Column(name = "display_label")
    lateinit var displayLabel: String

    @Column(name = "classification")
    lateinit var classification: String

    @Column(name = "valid_from")
    lateinit var validFrom: Instant

    @Column(name = "valid_to")
    var validTo: Instant? = null

    @Column(name = "recorded_at")
    lateinit var recordedAt: Instant

    @Column(name = "source_version")
    var sourceVersion: Long = 0
}

data class ContextEdgeIdentity(
    var id: UUID? = null,
    var bankScope: String? = null,
    var projectionGeneration: Long = 1,
) : Serializable {
    private companion object {
        const val serialVersionUID: Long = 1L
    }
}

@Entity
@IdClass(ContextEdgeIdentity::class)
@Table(name = "context_edges")
class ContextEdgeEntity : PanacheEntityBase() {
    @Id
    @Column(name = "edge_id")
    lateinit var id: UUID

    @Id
    @Column(name = "bank_scope")
    lateinit var bankScope: String

    @Id
    @Column(name = "projection_generation")
    var projectionGeneration: Long = 1

    @Column(name = "namespace")
    lateinit var namespace: String

    @Column(name = "from_key")
    lateinit var fromKey: String

    @Column(name = "to_key")
    lateinit var toKey: String

    @Column(name = "relation_type")
    lateinit var relationType: String

    @Column(name = "source_system")
    lateinit var sourceSystem: String

    @Column(name = "evidence_ref")
    lateinit var evidenceRef: String

    @Column(name = "valid_from")
    lateinit var validFrom: Instant

    @Column(name = "valid_to")
    var validTo: Instant? = null

    @Column(name = "recorded_at")
    lateinit var recordedAt: Instant

    @Column(name = "source_version")
    var sourceVersion: Long = 0
}

@Entity
@Table(name = "context_case_assignments")
class CaseAssignmentEntity : PanacheEntityBase() {
    @Id
    @Column(name = "assignment_id")
    lateinit var id: UUID

    @Column(name = "bank_scope")
    lateinit var bankScope: String

    @Column(name = "principal_id")
    lateinit var principalId: String

    @Column(name = "case_id")
    lateinit var caseId: String

    @Column(name = "purpose")
    lateinit var purpose: String

    @Column(name = "root_ref")
    var rootRef: String? = null

    @Column(name = "valid_from")
    lateinit var validFrom: Instant

    @Column(name = "valid_to")
    lateinit var validTo: Instant

    @Column(name = "created_at")
    lateinit var createdAt: Instant
}

@Entity
@Table(name = "context_read_audit")
class ContextReadAuditEntity : PanacheEntityBase() {
    @Id
    @Column(name = "audit_id")
    lateinit var id: UUID

    @Column(name = "bank_scope")
    lateinit var bankScope: String

    @Column(name = "principal_id")
    lateinit var principalId: String

    @Column(name = "case_id")
    lateinit var caseId: String

    @Column(name = "purpose")
    lateinit var purpose: String

    @Column(name = "action")
    lateinit var action: String

    @Column(name = "root_ref")
    lateinit var rootRef: String

    @Column(name = "decision")
    lateinit var decision: String

    @Column(name = "policy_version")
    var policyVersion: String? = null

    @Column(name = "reason_code")
    lateinit var reasonCode: String

    @Column(name = "occurred_at")
    lateinit var occurredAt: Instant

    @Column(name = "effective_at")
    var effectiveAt: Instant? = null

    @Column(name = "known_at")
    var knownAt: Instant? = null
}

@Entity
@Table(name = "context_disclosure_audit")
class ContextDisclosureAuditEntity : PanacheEntityBase() {
    @Id
    @Column(name = "disclosure_id")
    lateinit var id: UUID

    @Column(name = "decision_audit_id")
    lateinit var decisionAuditId: UUID

    @Column(name = "bank_scope")
    lateinit var bankScope: String

    @Column(name = "query_hash")
    lateinit var queryHash: String

    @Column(name = "projection_generation")
    var projectionGeneration: Long? = null

    @Column(name = "evidence_refs_json")
    lateinit var evidenceRefsJson: String

    @Column(name = "evidence_count")
    var evidenceCount: Int = 0

    @Column(name = "truncated")
    var truncated: Boolean = false

    @Column(name = "occurred_at")
    lateinit var occurredAt: Instant
}

@Entity
@Table(name = "context_disclosure_commitment_outbox")
class ContextDisclosureCommitmentOutboxEntity : PanacheEntityBase() {
    @Id
    @Column(name = "disclosure_id")
    lateinit var disclosureId: UUID

    @Column(name = "bank_scope")
    lateinit var bankScope: String

    @Column(name = "commitment")
    lateinit var commitment: String

    @Column(name = "occurred_at")
    lateinit var occurredAt: Instant

    @Column(name = "status")
    lateinit var status: String

    @Column(name = "attempt_count")
    var attemptCount: Int = 0

    @Column(name = "claimed_at")
    var claimedAt: Instant? = null

    @Column(name = "sent_at")
    var sentAt: Instant? = null

    @Column(name = "updated_at")
    lateinit var updatedAt: Instant
}

@Entity
@Table(name = "context_audit_commitment_outbox")
class ContextAuditCommitmentOutboxEntity : PanacheEntityBase() {
    @Id
    @Column(name = "audit_id")
    lateinit var auditId: UUID

    @Column(name = "bank_scope")
    lateinit var bankScope: String

    @Column(name = "commitment")
    lateinit var commitment: String

    @Column(name = "occurred_at")
    lateinit var occurredAt: Instant

    @Column(name = "status")
    lateinit var status: String

    @Column(name = "attempt_count")
    var attemptCount: Int = 0

    @Column(name = "claimed_at")
    var claimedAt: Instant? = null

    @Column(name = "sent_at")
    var sentAt: Instant? = null

    @Column(name = "updated_at")
    lateinit var updatedAt: Instant
}

/** The reactive timeout bounds the caller; PostgreSQL must stop the query as well. */
internal fun <T> Mutiny.SessionFactory.boundedGraphRead(timeoutMs: Int, block: (Mutiny.Session) -> Uni<T>): Uni<T> =
    withTransaction { session, _ ->
        session.createNativeQuery("select set_config('statement_timeout', :timeout, true)", String::class.java)
            .setParameter("timeout", "${timeoutMs}ms").singleResult.flatMap { block(session) }
    }.ifNoItem().after(Duration.ofMillis(timeoutMs.toLong())).fail()

@ApplicationScoped
class ContextGraphRepository(
    private val sessions: Mutiny.SessionFactory,
    private val objectMapper: ObjectMapper,
    @ConfigProperty(name = "openbank.context.bank-scope") private val bankScope: String,
    @ConfigProperty(name = "openbank.context.projection-generation") private val projectionGeneration: Long,
    @ConfigProperty(name = "openbank.context.query-timeout-ms") private val queryTimeoutMs: Int,
) : ContextGraphPort {
    @Suppress("LongMethod") // Every evidence hop has its own remaining-edge bound.
    override suspend fun neighborhood(
        namespace: ContextNamespace,
        root: String,
        asOf: Instant,
        maxNodes: Int,
        maxEdges: Int,
    ): ContextNeighborhood? {
        require(maxNodes in 1..MAX_GRAPH_NODES && maxEdges in 1..MAX_GRAPH_EDGES) {
            "Graph read limits exceed the approved bounded-query policy"
        }
        val graphRoot = resolveGraphRoot(namespace, root, asOf, maxEdges + 1) ?: return null
        val firstHop = graphRoot.edges
        val paymentEvidence = if (namespace == ContextNamespace.COMPLAINT && firstHop.size <= maxEdges) {
            val transactionKeys = firstHop.filter {
                it.fromKey == root && it.relationType == CONCERNS_TRANSACTION
            }.map(ContextEdgeEntity::toKey)
            findComplaintPaymentEvidenceEdges(transactionKeys, asOf, maxEdges - firstHop.size + 1)
        } else {
            emptyList()
        }
        val bookingTransactionKeys = paymentEvidence.filter {
            it.relationType == BOOKING_REQUESTED
        }.map(ContextEdgeEntity::toKey)
        val reversalEvidence = if (firstHop.size + paymentEvidence.size <= maxEdges) {
            findComplaintBookingEvidenceEdges(
                bookingTransactionKeys,
                TRANSACTION_SOURCE,
                BOOKING_TRANSACTION_PREFIX,
                REVERSED_BY,
                asOf,
                maxEdges - firstHop.size - paymentEvidence.size + 1,
            )
        } else {
            emptyList()
        }
        val ledgerEvidence = if (firstHop.size + paymentEvidence.size + reversalEvidence.size <= maxEdges) {
            findComplaintBookingEvidenceEdges(
                bookingTransactionKeys + reversalEvidence.map(ContextEdgeEntity::toKey),
                LEDGER_SOURCE,
                LEDGER_BOOKING_PREFIX,
                BOOKED_AS,
                asOf,
                maxEdges - firstHop.size - paymentEvidence.size - reversalEvidence.size + 1,
            )
        } else {
            emptyList()
        }
        val clearingEvidence = if (
            firstHop.size + paymentEvidence.size + reversalEvidence.size + ledgerEvidence.size <= maxEdges
        ) {
            val clearingItemKeys = paymentEvidence.filter {
                it.relationType == SUBMITTED_TO && it.sourceSystem == CLEARING_SOURCE
            }.map(ContextEdgeEntity::toKey)
            findComplaintClearingEvidenceEdges(
                clearingItemKeys,
                asOf,
                maxEdges - firstHop.size - paymentEvidence.size - reversalEvidence.size - ledgerEvidence.size + 1,
            )
        } else {
            emptyList()
        }
        val edges = firstHop + paymentEvidence + reversalEvidence + ledgerEvidence + clearingEvidence
        val boundedEdges = edges.take(maxEdges)
        val keys = graphRoot.boundedKeys(boundedEdges, maxNodes)
        val nodes = graphRoot.mergeNodes(keys, findNodes(namespace, keys, asOf))
        val visibleKeys = nodes.map { it.key }.toSet()
        return ContextNeighborhood(
            root,
            nodes.map { it.domain() },
            boundedEdges.filter { it.fromKey in visibleKeys && it.toKey in visibleKeys }.map { it.domain() },
            edges.size > maxEdges || nodes.size >= maxNodes || visibleKeys.size < keys.size,
        )
    }

    private suspend fun resolveGraphRoot(
        namespace: ContextNamespace,
        root: String,
        asOf: Instant,
        edgeLimit: Int,
    ): GraphNeighborhoodRoot? {
        if (namespace == ContextNamespace.COMPLAINT) {
            val snapshot = ComplaintSnapshotReader(sessions, bankScope, projectionGeneration, queryTimeoutMs)
                .find(root, asOf) ?: return null
            return GraphNeighborhoodRoot(snapshot.root.key, snapshot.edges, snapshot)
        }
        val rootNode = findRoot(namespace, root, asOf) ?: return null
        return GraphNeighborhoodRoot(rootNode.key, findRootEdges(namespace, root, asOf, edgeLimit))
    }

    private suspend fun findRoot(namespace: ContextNamespace, root: String, asOf: Instant): ContextNodeEntity? =
        sessions.boundedGraphRead(queryTimeoutMs) { session ->
            session.createQuery(
                "from ContextNodeEntity where key = :root and bankScope = :bankScope and " +
                    "projectionGeneration = :generation and namespace = :namespace and validFrom <= :asOf and " +
                    "(validTo is null or validTo > :asOf)",
                ContextNodeEntity::class.java,
            ).setParameter("bankScope", bankScope).setParameter("generation", projectionGeneration)
                .setParameter("namespace", namespace.name).setParameter("asOf", asOf)
                .setParameter("root", root).singleResultOrNull
        }.awaitSuspending()

    private suspend fun findRootEdges(
        namespace: ContextNamespace,
        root: String,
        asOf: Instant,
        limit: Int,
    ): List<ContextEdgeEntity> = sessions.boundedGraphRead(queryTimeoutMs) { session ->
        session.createNativeQuery(
            ROOT_EDGES_SQL,
            ContextEdgeEntity::class.java,
        ).setParameter("bankScope", bankScope).setParameter("generation", projectionGeneration)
            .setParameter("namespace", namespace.name).setParameter("asOf", asOf)
            .setParameter("root", root).setParameter("limit", limit).resultList
    }.awaitSuspending()

    private suspend fun findComplaintPaymentEvidenceEdges(
        transactionKeys: List<String>,
        asOf: Instant,
        limit: Int,
    ): List<ContextEdgeEntity> {
        if (transactionKeys.isEmpty() || limit <= 0) return emptyList()
        return sessions.boundedGraphRead(queryTimeoutMs) { session ->
            session.createQuery(
                "from ContextEdgeEntity where bankScope = :bankScope and projectionGeneration = :generation and " +
                    "namespace = 'COMPLAINT' and fromKey in (:keys) and " +
                    "((sourceSystem = :domesticSource and toKey like :stagePrefix and " +
                    "relationType in (:lifecycleRelations)) or " +
                    "(sourceSystem = :transactionSource and toKey like :transactionPrefix and " +
                    "relationType = :bookingRequested) or " +
                    "(sourceSystem = :clearingSource and toKey like :clearingItemPrefix and " +
                    "relationType = :submittedTo) or " +
                    "(sourceSystem = :sepaSource and toKey like :returnPrefix and " +
                    "relationType = :returnedBy) or " +
                    "(sourceSystem = :sepaSource and toKey like :reversalPrefix and " +
                    "relationType = :reversedBy)) and " +
                    "validFrom <= :asOf and (validTo is null or validTo > :asOf) order by validFrom asc",
                ContextEdgeEntity::class.java,
            ).setParameter("bankScope", bankScope).setParameter("generation", projectionGeneration)
                .setParameter("domesticSource", DOMESTIC_PAYMENT_SOURCE)
                .setParameter("transactionSource", TRANSACTION_SOURCE)
                .setParameter("clearingSource", CLEARING_SOURCE)
                .setParameter("sepaSource", SEPA_SOURCE)
                .setParameter("stagePrefix", PAYMENT_STAGE_PREFIX)
                .setParameter("transactionPrefix", BOOKING_TRANSACTION_PREFIX)
                .setParameter("bookingRequested", BOOKING_REQUESTED)
                .setParameter("clearingItemPrefix", CLEARING_ITEM_PREFIX)
                .setParameter("submittedTo", SUBMITTED_TO)
                .setParameter("returnPrefix", RETURN_EVIDENCE_PREFIX)
                .setParameter("returnedBy", RETURNED_BY)
                .setParameter("reversalPrefix", REVERSAL_TRANSACTION_PREFIX)
                .setParameter("reversedBy", REVERSED_BY)
                .setParameter("keys", transactionKeys)
                .setParameter("lifecycleRelations", COMPLAINT_LIFECYCLE_RELATIONS)
                .setParameter("asOf", asOf).setMaxResults(limit).resultList
        }.awaitSuspending()
    }

    private suspend fun findComplaintBookingEvidenceEdges(
        bookingTransactionKeys: List<String>,
        source: String,
        toPrefix: String,
        relation: String,
        asOf: Instant,
        limit: Int,
    ): List<ContextEdgeEntity> {
        if (bookingTransactionKeys.isEmpty() || limit <= 0) return emptyList()
        return sessions.boundedGraphRead(queryTimeoutMs) { session ->
            session.createQuery(
                "from ContextEdgeEntity where bankScope = :bankScope and projectionGeneration = :generation and " +
                    "namespace = 'COMPLAINT' and sourceSystem = :source and fromKey in (:keys) and " +
                    "toKey like :bookingPrefix and relationType = :relation and validFrom <= :asOf and " +
                    "(validTo is null or validTo > :asOf) order by validFrom asc",
                ContextEdgeEntity::class.java,
            ).setParameter("bankScope", bankScope).setParameter("generation", projectionGeneration)
                .setParameter("source", source).setParameter("keys", bookingTransactionKeys)
                .setParameter("bookingPrefix", toPrefix).setParameter("relation", relation)
                .setParameter("asOf", asOf).setMaxResults(limit).resultList
        }.awaitSuspending()
    }

    private suspend fun findComplaintClearingEvidenceEdges(
        clearingItemKeys: List<String>,
        asOf: Instant,
        limit: Int,
    ): List<ContextEdgeEntity> {
        if (clearingItemKeys.isEmpty() || limit <= 0) return emptyList()
        return sessions.boundedGraphRead(queryTimeoutMs) { session ->
            session.createQuery(
                "from ContextEdgeEntity where bankScope = :bankScope and projectionGeneration = :generation and " +
                    "namespace = 'COMPLAINT' and sourceSystem = :source and fromKey in (:keys) and " +
                    "toKey like :evidencePrefix and relationType = :relation and validFrom <= :asOf and " +
                    "(validTo is null or validTo > :asOf) order by validFrom asc",
                ContextEdgeEntity::class.java,
            ).setParameter("bankScope", bankScope).setParameter("generation", projectionGeneration)
                .setParameter("source", CLEARING_SOURCE).setParameter("keys", clearingItemKeys)
                .setParameter("evidencePrefix", CLEARING_EVIDENCE_PREFIX).setParameter("relation", SETTLED)
                .setParameter("asOf", asOf).setMaxResults(limit).resultList
        }.awaitSuspending()
    }

    private suspend fun findNodes(
        namespace: ContextNamespace,
        keys: List<String>,
        asOf: Instant,
    ): List<ContextNodeEntity> {
        if (namespace == ContextNamespace.COMPLAINT) {
            return GraphNodeHistoryReader(sessions, bankScope, projectionGeneration, queryTimeoutMs, objectMapper)
                .find(keys, asOf)
        }
        return sessions.boundedGraphRead(queryTimeoutMs) { session ->
            session.createQuery(
                "from ContextNodeEntity where bankScope = :bankScope and projectionGeneration = :generation and " +
                    "namespace = :namespace and key in (:keys) and validFrom <= :asOf and " +
                    "(validTo is null or validTo > :asOf)",
                ContextNodeEntity::class.java,
            ).setParameter("bankScope", bankScope).setParameter("generation", projectionGeneration)
                .setParameter("namespace", namespace.name).setParameter("asOf", asOf)
                .setParameter("keys", keys).resultList
        }.awaitSuspending()
    }

    private companion object {
        // Keep each direction index-ordered and bounded before merging. The previous OR predicate
        // scanned and sorted every edge of a high-degree root before applying the result limit.
        val ROOT_EDGES_SQL = """
            SELECT * FROM (
                (SELECT e.* FROM context_edges e
                 WHERE e.bank_scope = :bankScope AND e.projection_generation = :generation
                   AND e.namespace = :namespace AND e.from_key = :root
                   AND e.valid_from <= :asOf AND (e.valid_to IS NULL OR e.valid_to > :asOf)
                 ORDER BY e.recorded_at DESC, e.edge_id LIMIT :limit)
                UNION ALL
                (SELECT e.* FROM context_edges e
                 WHERE e.bank_scope = :bankScope AND e.projection_generation = :generation
                   AND e.namespace = :namespace AND e.to_key = :root AND e.from_key <> :root
                   AND e.valid_from <= :asOf AND (e.valid_to IS NULL OR e.valid_to > :asOf)
                 ORDER BY e.recorded_at DESC, e.edge_id LIMIT :limit)
            ) candidates ORDER BY recorded_at DESC, edge_id LIMIT :limit
        """.trimIndent()
        const val MAX_GRAPH_NODES = 100
        const val MAX_GRAPH_EDGES = 200
        const val CONCERNS_TRANSACTION = "CONCERNS_TRANSACTION"
        const val DOMESTIC_PAYMENT_SOURCE = "domestic-payment"
        const val TRANSACTION_SOURCE = "transaction-service"
        const val LEDGER_SOURCE = "ledger-service"
        const val CLEARING_SOURCE = "clearing-service"
        const val SEPA_SOURCE = "sepa-payment"
        const val PAYMENT_STAGE_PREFIX = "payment-stage:domestic:%"
        const val BOOKING_TRANSACTION_PREFIX = "booking-transaction:%"
        const val LEDGER_BOOKING_PREFIX = "ledger-booking:%"
        const val CLEARING_ITEM_PREFIX = "clearing-item:%"
        const val CLEARING_EVIDENCE_PREFIX = "clearing-evidence:%"
        const val RETURN_EVIDENCE_PREFIX = "return-evidence:sepa:%"
        const val REVERSAL_TRANSACTION_PREFIX = "reversal-transaction:%"
        const val BOOKING_REQUESTED = "BOOKING_REQUESTED"
        const val BOOKED_AS = "BOOKED_AS"
        const val SUBMITTED_TO = "SUBMITTED_TO"
        const val RETURNED_BY = "RETURNED_BY"
        const val REVERSED_BY = "REVERSED_BY"
        const val SETTLED = "SETTLED"
        val COMPLAINT_LIFECYCLE_RELATIONS = setOf(
            "CREATED",
            "VALIDATED",
            "SUBMITTED_TO",
            "SETTLED",
            "REJECTED",
            "RETURNED_BY",
            "REVERSED_BY",
            "CANCELLED",
        )
    }

    private fun ContextNodeEntity.domain() = ContextNode(
        key,
        ContextNamespace.valueOf(
            namespace,
        ),
        nodeType, sourceSystem, sourceRef, displayLabel,
        DataClassification.valueOf(
            classification,
        ),
        validFrom, validTo, recordedAt, sourceVersion,
    )
    private fun ContextEdgeEntity.domain() = ContextEdge(
        id.toString(),
        ContextNamespace.valueOf(
            namespace,
        ),
        fromKey, toKey, relationType, evidenceRef, validFrom, validTo, recordedAt, sourceVersion,
    )
}

@ApplicationScoped
@Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
class CaseAssignmentRepository(
    private val sessions: Mutiny.SessionFactory,
    @ConfigProperty(name = "openbank.context.bank-scope") private val bankScope: String,
    @ConfigProperty(name = "openbank.context.query-timeout-ms") private val queryTimeoutMs: Int,
) : CaseAssignmentPort {
    override suspend fun isAssignedToRoot(
        principalId: String,
        caseId: String,
        purpose: String,
        root: String,
        at: Instant,
    ): Boolean = sessions.withSession { session ->
        session.createQuery(
            "select count(a) from CaseAssignmentEntity a where bankScope = :bankScope and principalId = :principal " +
                "and caseId = :caseId and purpose = :purpose and rootRef = :root and validFrom <= :at and validTo > :at",
            java.lang.Long::class.java,
        ).setParameter("bankScope", bankScope).setParameter("principal", principalId)
            .setParameter("caseId", caseId).setParameter("purpose", purpose).setParameter("root", root)
            .setParameter("at", at).singleResult
    }.ifNoItem().after(Duration.ofMillis(queryTimeoutMs.toLong())).fail().awaitSuspending() > 0
}

@ApplicationScoped
class ContextReadAuditRepository(
    private val sessions: Mutiny.SessionFactory,
    private val mapper: ObjectMapper,
    @ConfigProperty(name = "openbank.context.bank-scope") private val bankScope: String,
    @ConfigProperty(name = "openbank.context.query-timeout-ms") private val queryTimeoutMs: Int,
) : ContextReadAuditPort {
    override suspend fun record(entry: ContextReadAudit) {
        val entity = ContextReadAuditEntity().apply {
            id = entry.id
            this.bankScope = this@ContextReadAuditRepository.bankScope
            principalId = entry.principalId
            caseId = entry.caseId
            purpose = entry.purpose
            action = entry.action
            rootRef = entry.rootRef
            decision = entry.decision
            policyVersion =
                entry.policyVersion
            reasonCode = entry.reasonCode
            // PostgreSQL timestamptz stores microseconds. Commit only the value it can retain.
            occurredAt = entry.occurredAt.truncatedTo(ChronoUnit.MICROS)
            effectiveAt = entry.effectiveAt?.truncatedTo(ChronoUnit.MICROS)
            knownAt = entry.knownAt?.truncatedTo(ChronoUnit.MICROS)
        }
        val commitment = ContextAuditCommitmentOutboxEntity().apply {
            auditId = entity.id
            this.bankScope = entity.bankScope
            this.commitment = ContextAuditCommitment.of(entity)
            occurredAt = entity.occurredAt
            status = "PENDING"
            updatedAt = entity.occurredAt
        }
        sessions.withTransaction { session, _ ->
            session.createNativeQuery("select set_config('openbank.bank_scope', :bank, true)", String::class.java)
                .setParameter("bank", bankScope).singleResult
                .flatMap { session.persist(entity) }
                .flatMap { session.persist(commitment) }
        }
            .ifNoItem().after(Duration.ofMillis(queryTimeoutMs.toLong())).fail().awaitSuspending()
    }

    override suspend fun recordDisclosure(entry: ContextDisclosureAudit) {
        val entity = ContextDisclosureAuditEntity().apply {
            id = Ids.newId()
            decisionAuditId = entry.decisionAuditId
            bankScope = this@ContextReadAuditRepository.bankScope
            queryHash = entry.queryHash
            projectionGeneration = entry.disclosure.projectionGeneration
            evidenceRefsJson = mapper.writeValueAsString(entry.disclosure.evidenceRefs)
            evidenceCount = entry.disclosure.evidenceCount
            truncated = entry.disclosure.truncated
            occurredAt = entry.occurredAt.truncatedTo(ChronoUnit.MICROS)
        }
        val commitment = ContextDisclosureCommitmentOutboxEntity().apply {
            disclosureId = entity.id
            bankScope = entity.bankScope
            this.commitment = ContextDisclosureCommitment.of(entity)
            occurredAt = entity.occurredAt
            status = "PENDING"
            updatedAt = entity.occurredAt
        }
        sessions.withTransaction { session, _ ->
            session.createNativeQuery("select set_config('openbank.bank_scope', :bank, true)", String::class.java)
                .setParameter("bank", bankScope).singleResult
                .flatMap { session.persist(entity) }
                .flatMap { session.persist(commitment) }
        }.ifNoItem().after(Duration.ofMillis(queryTimeoutMs.toLong())).fail().awaitSuspending()
    }
}
