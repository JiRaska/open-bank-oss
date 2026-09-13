// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.infrastructure

import com.openbank.context.application.CaseAssignmentPort
import com.openbank.context.application.ContextGraphPort
import com.openbank.context.application.ContextReadAudit
import com.openbank.context.application.ContextReadAuditPort
import com.openbank.context.domain.ContextEdge
import com.openbank.context.domain.ContextNamespace
import com.openbank.context.domain.ContextNeighborhood
import com.openbank.context.domain.ContextNode
import com.openbank.context.domain.DataClassification
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.hibernate.reactive.mutiny.Mutiny
import java.time.Duration
import java.time.Instant
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

@Entity
@Table(name = "context_edges")
class ContextEdgeEntity : PanacheEntityBase() {
    @Id
    @Column(name = "edge_id")
    lateinit var id: UUID

    @Column(name = "bank_scope")
    lateinit var bankScope: String

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
}

@ApplicationScoped
class ContextGraphRepository(
    private val sessions: Mutiny.SessionFactory,
    @ConfigProperty(name = "openbank.context.bank-scope") private val bankScope: String,
    @ConfigProperty(name = "openbank.context.projection-generation") private val projectionGeneration: Long,
    @ConfigProperty(name = "openbank.context.query-timeout-ms") private val queryTimeoutMs: Int,
) : ContextGraphPort {
    override suspend fun neighborhood(
        namespace: ContextNamespace,
        root: String,
        asOf: Instant,
        maxNodes: Int,
        maxEdges: Int,
    ): ContextNeighborhood? {
        val rootNode = sessions.withSession { session ->
            session.createQuery(
                "from ContextNodeEntity where key = :root and bankScope = :bankScope and projectionGeneration = :generation and namespace = :namespace and validFrom <= :asOf and (validTo is null or validTo > :asOf)",
                ContextNodeEntity::class.java,
            ).setParameter(
                "root",
                root,
            ).setParameter("bankScope", bankScope).setParameter("generation", projectionGeneration)
                .setParameter("namespace", namespace.name).setParameter("asOf", asOf).singleResultOrNull
        }.ifNoItem().after(Duration.ofMillis(queryTimeoutMs.toLong())).fail().awaitSuspending() ?: return null
        val edges = sessions.withSession { session ->
            session.createQuery(
                "from ContextEdgeEntity where bankScope = :bankScope and projectionGeneration = :generation and namespace = :namespace and (fromKey = :root or toKey = :root) and validFrom <= :asOf and (validTo is null or validTo > :asOf) order by recordedAt desc",
                ContextEdgeEntity::class.java,
            ).setParameter(
                "namespace",
                namespace.name,
            ).setParameter("bankScope", bankScope).setParameter("generation", projectionGeneration)
                .setParameter("root", root).setParameter("asOf", asOf).setMaxResults(
                    maxEdges + 1,
                ).resultList
        }.ifNoItem().after(Duration.ofMillis(queryTimeoutMs.toLong())).fail().awaitSuspending()
        val boundedEdges = edges.take(maxEdges)
        val keys = (boundedEdges.flatMap { listOf(it.fromKey, it.toKey) } + rootNode.key).distinct().take(maxNodes)
        val nodes = sessions.withSession { session ->
            session.createQuery(
                "from ContextNodeEntity where bankScope = :bankScope and projectionGeneration = :generation and namespace = :namespace and key in (:keys) and validFrom <= :asOf and (validTo is null or validTo > :asOf)",
                ContextNodeEntity::class.java,
            ).setParameter(
                "namespace",
                namespace.name,
            ).setParameter("bankScope", bankScope).setParameter("generation", projectionGeneration)
                .setParameter("keys", keys).setParameter("asOf", asOf).resultList
        }.ifNoItem().after(Duration.ofMillis(queryTimeoutMs.toLong())).fail().awaitSuspending()
        return ContextNeighborhood(
            root,
            nodes.map { it.domain() },
            boundedEdges.filter { it.fromKey in keys && it.toKey in keys }.map { it.domain() },
            edges.size > maxEdges || nodes.size >= maxNodes,
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
    override suspend fun isAssigned(principalId: String, caseId: String, purpose: String, at: Instant): Boolean =
        sessions.withSession { session ->
            session.createQuery(
                "select count(a) from CaseAssignmentEntity a where bankScope = :bankScope and principalId = :principal and caseId = :caseId and purpose = :purpose and validFrom <= :at and validTo > :at",
                java.lang.Long::class.java,
            )
                .setParameter(
                    "principal",
                    principalId,
                ).setParameter("bankScope", bankScope).setParameter("caseId", caseId)
                .setParameter("purpose", purpose).setParameter("at", at).singleResult
        }.ifNoItem().after(Duration.ofMillis(queryTimeoutMs.toLong())).fail().awaitSuspending() > 0
}

@ApplicationScoped
class ContextReadAuditRepository(
    private val sessions: Mutiny.SessionFactory,
    @ConfigProperty(name = "openbank.context.bank-scope") private val bankScope: String,
    @ConfigProperty(name = "openbank.context.query-timeout-ms") private val queryTimeoutMs: Int,
) : ContextReadAuditPort {
    override suspend fun record(entry: ContextReadAudit) {
        val entity = ContextReadAuditEntity().apply {
            id = UUID.randomUUID()
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
            occurredAt = entry.occurredAt
        }
        sessions.withTransaction { session, _ -> session.persist(entity) }
            .ifNoItem().after(Duration.ofMillis(queryTimeoutMs.toLong())).fail().awaitSuspending()
    }
}
