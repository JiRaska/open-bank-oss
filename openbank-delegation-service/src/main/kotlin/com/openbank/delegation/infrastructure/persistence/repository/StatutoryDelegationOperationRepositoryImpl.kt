// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.persistence.repository

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.delegation.application.port.out.DelegationOutboxRepository
import com.openbank.delegation.application.port.out.StatutoryDecisionClosed
import com.openbank.delegation.application.port.out.StatutoryDecisionConflict
import com.openbank.delegation.application.port.out.StatutoryDelegationOperationRepository
import com.openbank.delegation.application.port.out.StatutoryOperationCreateConflict
import com.openbank.delegation.application.port.out.StatutoryOperationCreateOutcome
import com.openbank.delegation.application.port.out.StatutoryQuorumIncomplete
import com.openbank.delegation.domain.event.DelegationActivated
import com.openbank.delegation.domain.event.DelegationOffered
import com.openbank.delegation.domain.model.DelegationGrant
import com.openbank.delegation.domain.model.DelegationStatus
import com.openbank.delegation.domain.model.StatutoryDecisionVerdict
import com.openbank.delegation.domain.model.StatutoryDelegationDecision
import com.openbank.delegation.domain.model.StatutoryDelegationOperation
import com.openbank.delegation.domain.model.StatutoryOperationKind
import com.openbank.delegation.domain.model.StatutoryOperationState
import com.openbank.delegation.domain.model.StatutoryRepresentationRule
import com.openbank.delegation.infrastructure.persistence.entity.DelegationGrantEntity
import com.openbank.delegation.infrastructure.persistence.entity.StatutoryDelegationDecisionEntity
import com.openbank.delegation.infrastructure.persistence.entity.StatutoryDelegationOperationEntity
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** The unique principal/request key is the concurrent replay serialisation point. */
@ApplicationScoped
@Suppress("TooManyFunctions") // One ledger adapter owns both operation kinds' row locks and atomic outbox commits.
class StatutoryDelegationOperationRepositoryImpl(
    private val outboxRepository: DelegationOutboxRepository,
    private val mapper: ObjectMapper,
) : StatutoryDelegationOperationRepository,
    PanacheRepository<StatutoryDelegationOperationEntity> {
    override suspend fun create(operation: StatutoryDelegationOperation): StatutoryOperationCreateOutcome {
        require(operation.state == StatutoryOperationState.PENDING) { "only pending operations can be proposed" }
        return Panache.withTransaction {
            Panache.getSession().flatMap { session ->
                session.createNativeQuery<Any>(INSERT_SQL)
                    .setParameter("id", operation.id)
                    .setParameter("principal", operation.principalPartyId)
                    .setParameter("initiator", operation.initiatorPartyId)
                    .setParameter("key", operation.requestKey)
                    .setParameter("requestHash", operation.requestHash)
                    .setParameter("payload", operation.payloadJson)
                    .setParameter("policyId", operation.policyId)
                    .setParameter("policyRevision", operation.policyRevision)
                    .setParameter("sourceCase", operation.sourceCaseId)
                    .setParameter("ruleHash", operation.ruleHash)
                    .setParameter("ruleSnapshot", operation.ruleSnapshotJson)
                    .setParameter("createdAt", operation.createdAt)
                    .setParameter("expiresAt", operation.expiresAt)
                    .setParameter("kind", operation.operationKind.name)
                    .setParameter("targetGrant", operation.targetGrantId)
                    .setParameter("expectedRevision", operation.expectedLifecycleRevision)
                    .executeUpdate()
                    .flatMap { inserted ->
                        find(
                            "principalPartyId = ?1 and requestKey = ?2",
                            operation.principalPartyId,
                            operation.requestKey,
                        )
                            .firstResult<StatutoryDelegationOperationEntity>()
                            .map { entity ->
                                val persisted = requireNotNull(entity) {
                                    "statutory operation insert/replay produced no row"
                                }
                                    .toDomain()
                                if (!persisted.sameEvidenceAs(operation)) throw StatutoryOperationCreateConflict()
                                if (inserted == 1) {
                                    StatutoryOperationCreateOutcome.Created(persisted)
                                } else {
                                    StatutoryOperationCreateOutcome.Replayed(persisted)
                                }
                            }
                    }
            }
        }.awaitSuspending()
    }

    override suspend fun find(id: UUID, principalPartyId: UUID): StatutoryDelegationOperation? = Panache.withSession {
        find("id = ?1 and principalPartyId = ?2", id, principalPartyId)
            .firstResult<StatutoryDelegationOperationEntity>()
    }.awaitSuspending()?.toDomain()

    override suspend fun pending(
        principalPartyId: UUID,
        ruleHash: String,
        after: Instant,
        limit: Int,
        beforeCreatedAt: Instant?,
        beforeId: UUID?,
        kind: StatutoryOperationKind,
    ): List<StatutoryDelegationOperation> = Panache.withSession {
        Panache.getSession().flatMap { session ->
            require((beforeCreatedAt == null) == (beforeId == null)) { "incomplete statutory inbox cursor" }
            val cursorPredicate = if (beforeCreatedAt == null) {
                ""
            } else {
                "and (created_at < :beforeCreatedAt or " +
                    "(created_at = :beforeCreatedAt and operation_id < :beforeId)) "
            }
            // Keep PENDING literal so PostgreSQL can reliably use V30's partial index even
            // after the driver switches from custom to generic prepared-statement plans.
            val query = session.createNativeQuery(
                "SELECT * FROM delegation_statutory_operations " +
                    "WHERE principal_party_id = :principal AND rule_hash = :ruleHash " +
                    "AND state = 'PENDING' AND operation_kind = '${kind.name}' " +
                    "AND expires_at > :after $cursorPredicate" +
                    "ORDER BY created_at DESC, operation_id DESC",
                StatutoryDelegationOperationEntity::class.java,
            )
                .setParameter("principal", principalPartyId)
                .setParameter("ruleHash", ruleHash)
                .setParameter("after", after)
            if (beforeCreatedAt != null && beforeId != null) {
                query.setParameter("beforeCreatedAt", beforeCreatedAt).setParameter("beforeId", beforeId)
            }
            query.setMaxResults(limit.coerceIn(1, MAX_PENDING_RESULTS + 1)).resultList
        }
    }.awaitSuspending().map { it.toDomain() }

    override suspend fun decisions(operationId: UUID): List<StatutoryDelegationDecision> = Panache.withSession {
        Panache.getSession().flatMap { session ->
            session.createQuery(
                "from StatutoryDelegationDecisionEntity where operationId = :operation order by decidedAt asc, actorPartyId asc",
                StatutoryDelegationDecisionEntity::class.java,
            ).setParameter("operation", operationId).resultList
        }
    }.awaitSuspending().map { it.toDomain() }

    override suspend fun findDecision(operationId: UUID, actorPartyId: UUID): StatutoryDelegationDecision? =
        Panache.withSession {
            Panache.getSession().flatMap { session ->
                session.createQuery(
                    "from StatutoryDelegationDecisionEntity where operationId = :operation and actorPartyId = :actor",
                    StatutoryDelegationDecisionEntity::class.java,
                )
                    .setParameter("operation", operationId)
                    .setParameter("actor", actorPartyId)
                    .singleResultOrNull
            }
        }.awaitSuspending()?.toDomain()

    override suspend fun recordDecision(decision: StatutoryDelegationDecision): StatutoryDelegationDecision =
        Panache.withTransaction {
            Panache.getSession().flatMap { session ->
                session.createNativeQuery<Any>(INSERT_DECISION_SQL)
                    .setParameter("operation", decision.operationId)
                    .setParameter("actor", decision.actorPartyId)
                    .setParameter("sca", decision.scaSessionId)
                    .setParameter("verdict", decision.verdict.name)
                    .setParameter("decidedAt", decision.decidedAt)
                    .executeUpdate()
                    .flatMap { inserted ->
                        session.createQuery(
                            "from StatutoryDelegationDecisionEntity where operationId = :operation and actorPartyId = :actor",
                            StatutoryDelegationDecisionEntity::class.java,
                        )
                            .setParameter("operation", decision.operationId)
                            .setParameter("actor", decision.actorPartyId)
                            .singleResultOrNull
                            .map { found ->
                                val persisted = found?.toDomain()
                                if (persisted == null) throw StatutoryDecisionClosed()
                                if (persisted.verdict != decision.verdict ||
                                    persisted.scaSessionId != decision.scaSessionId
                                ) {
                                    throw StatutoryDecisionConflict()
                                }
                                require(inserted in 0..1) { "unexpected statutory decision insert count" }
                                persisted
                            }
                    }
            }
        }.awaitSuspending()

    override suspend fun execute(
        operationId: UUID,
        principalPartyId: UUID,
        rule: StatutoryRepresentationRule,
        expectedRuleHash: String,
        grant: DelegationGrant,
        event: DelegationOffered,
        at: Instant,
    ): DelegationGrant = Panache.withTransaction {
        Panache.getSession().flatMap { session ->
            session.createNativeQuery(LOCK_OPERATION_SQL, StatutoryDelegationOperationEntity::class.java)
                .setParameter("operation", operationId)
                .setParameter("principal", principalPartyId)
                .singleResultOrNull
                .flatMap { operation ->
                    when {
                        operation == null -> Uni.createFrom().failure(StatutoryDecisionClosed())
                        operation.operationKind != StatutoryOperationKind.ISSUE ->
                            Uni.createFrom().failure(StatutoryDecisionClosed())
                        operation.state == StatutoryOperationState.EXECUTED -> {
                            session.find(DelegationGrantEntity::class.java, operation.grantId)
                                .map { persisted ->
                                    requireNotNull(persisted) { "executed grant is missing" }.toDomain()
                                }
                        }
                        operation.state != StatutoryOperationState.PENDING ||
                            !operation.expiresAt.isAfter(at) ||
                            operation.ruleHash.trim() != expectedRuleHash ||
                            operation.policyId != rule.policyId ||
                            operation.policyRevision != rule.revision -> {
                            Uni.createFrom().failure(StatutoryDecisionClosed())
                        }
                        else -> executePending(session, operationId, rule, grant, event, at)
                    }
                }
        }
    }.awaitSuspending()

    override suspend fun executeAcceptance(
        operationId: UUID,
        principalPartyId: UUID,
        rule: StatutoryRepresentationRule,
        expectedRuleHash: String,
        expectedPayloadJson: String,
        offered: DelegationGrant,
        event: DelegationActivated,
        at: Instant,
    ): DelegationGrant = Panache.withTransaction {
        Panache.getSession().flatMap { session ->
            session.createNativeQuery(LOCK_OPERATION_SQL, StatutoryDelegationOperationEntity::class.java)
                .setParameter("operation", operationId)
                .setParameter("principal", principalPartyId)
                .singleResultOrNull
                .flatMap { operation ->
                    when {
                        operation == null || operation.operationKind != StatutoryOperationKind.ACCEPT ->
                            Uni.createFrom().failure(StatutoryDecisionClosed())
                        operation.state == StatutoryOperationState.EXECUTED ->
                            session.find(DelegationGrantEntity::class.java, operation.grantId)
                                .map { requireNotNull(it) { "executed acceptance grant is missing" }.toDomain() }
                        !validAcceptanceOperation(
                            operation,
                            rule,
                            expectedRuleHash,
                            expectedPayloadJson,
                            offered,
                            at,
                        ) -> Uni.createFrom().failure(StatutoryDecisionClosed())
                        else -> executePendingAcceptance(session, operationId, rule, offered, event, at)
                    }
                }
        }
    }.awaitSuspending()

    private fun validAcceptanceOperation(
        operation: StatutoryDelegationOperationEntity,
        rule: StatutoryRepresentationRule,
        expectedRuleHash: String,
        expectedPayloadJson: String,
        offered: DelegationGrant,
        at: Instant,
    ): Boolean = operation.state == StatutoryOperationState.PENDING &&
        operation.expiresAt.isAfter(at) &&
        operation.principalPartyId == offered.granteePartyId &&
        operation.targetGrantId == offered.id &&
        operation.expectedLifecycleRevision == offered.lifecycleRevision &&
        offered.status == DelegationStatus.OFFERED &&
        operation.payloadJson == expectedPayloadJson &&
        operation.requestHash == sha256(expectedPayloadJson) &&
        operation.ruleHash == expectedRuleHash &&
        operation.policyId == rule.policyId &&
        operation.policyRevision == rule.revision

    private fun executePendingAcceptance(
        session: org.hibernate.reactive.mutiny.Mutiny.Session,
        operationId: UUID,
        rule: StatutoryRepresentationRule,
        offered: DelegationGrant,
        event: DelegationActivated,
        at: Instant,
    ): Uni<DelegationGrant> = session.createNativeQuery(LOCK_GRANT_SQL, DelegationGrantEntity::class.java)
        .setParameter("grant", offered.id)
        .singleResultOrNull
        .flatMap { locked ->
            if (locked == null || locked.toDomain() != offered) {
                Uni.createFrom().failure(StatutoryDecisionClosed())
            } else {
                executeLockedAcceptance(session, operationId, rule, offered, event, at)
            }
        }

    private fun executeLockedAcceptance(
        session: org.hibernate.reactive.mutiny.Mutiny.Session,
        operationId: UUID,
        rule: StatutoryRepresentationRule,
        offered: DelegationGrant,
        event: DelegationActivated,
        at: Instant,
    ): Uni<DelegationGrant> = session.createQuery(
        "from StatutoryDelegationDecisionEntity where operationId = :operation",
        StatutoryDelegationDecisionEntity::class.java,
    )
        .setParameter("operation", operationId)
        .resultList
        .flatMap { rows ->
            val approvers = rows.filter { it.verdict == StatutoryDecisionVerdict.APPROVE }
                .map { it.actorPartyId }.toSet()
            if (!rule.satisfiedBy(approvers)) {
                Uni.createFrom().failure(StatutoryQuorumIncomplete())
            } else {
                val activated = offered.acceptJoint(operationId, at.atOffset(java.time.ZoneOffset.UTC))
                session.createNativeQuery<Any>(ACTIVATE_ACCEPTED_GRANT_SQL)
                    .setParameter("grant", offered.id)
                    .setParameter("revision", offered.lifecycleRevision)
                    .setParameter("nextRevision", activated.lifecycleRevision)
                    .setParameter("operation", operationId)
                    .setParameter("at", at)
                    .executeUpdate()
                    .flatMap { changed ->
                        if (changed != 1) return@flatMap Uni.createFrom().failure(StatutoryDecisionClosed())
                        outboxRepository.persistInTransaction(
                            OutboxMessage(
                                aggregateId = event.aggregateId,
                                eventType = event.eventType,
                                payload = mapper.writeValueAsString(event),
                                createdAt = event.occurredAt,
                            ),
                        )
                    }
                    .flatMap {
                        session.createNativeQuery<Any>(MARK_EXECUTED_SQL)
                            .setParameter("operation", operationId)
                            .setParameter("grant", offered.id)
                            .setParameter("at", at)
                            .executeUpdate()
                    }
                    .map { changed ->
                        check(changed == 1) { "statutory acceptance execution lost its row lock" }
                        activated
                    }
            }
        }

    private fun executePending(
        session: org.hibernate.reactive.mutiny.Mutiny.Session,
        operationId: UUID,
        rule: StatutoryRepresentationRule,
        grant: DelegationGrant,
        event: DelegationOffered,
        at: Instant,
    ): Uni<DelegationGrant> = session.createQuery(
        "from StatutoryDelegationDecisionEntity where operationId = :operation",
        StatutoryDelegationDecisionEntity::class.java,
    )
        .setParameter("operation", operationId)
        .resultList
        .flatMap { rows ->
            val approvers = rows.filter { it.verdict == StatutoryDecisionVerdict.APPROVE }
                .map { it.actorPartyId }.toSet()
            if (!rule.satisfiedBy(approvers)) {
                Uni.createFrom().failure(StatutoryQuorumIncomplete())
            } else {
                session.persist(DelegationGrantEntity.fromDomain(grant))
                    .flatMap {
                        outboxRepository.persistInTransaction(
                            OutboxMessage(
                                aggregateId = event.aggregateId,
                                eventType = event.eventType,
                                payload = mapper.writeValueAsString(event),
                                createdAt = event.occurredAt,
                            ),
                        )
                    }
                    .flatMap {
                        session.createNativeQuery<Any>(MARK_EXECUTED_SQL)
                            .setParameter("operation", operationId)
                            .setParameter("grant", grant.id)
                            .setParameter("at", at)
                            .executeUpdate()
                    }
                    .map { changed ->
                        check(changed == 1) { "statutory operation execution lost its row lock" }
                        grant
                    }
            }
        }

    private fun StatutoryDelegationOperation.sameEvidenceAs(other: StatutoryDelegationOperation): Boolean =
        principalPartyId == other.principalPartyId &&
            initiatorPartyId == other.initiatorPartyId &&
            requestKey == other.requestKey &&
            requestHash == other.requestHash &&
            payloadJson == other.payloadJson &&
            policyId == other.policyId &&
            policyRevision == other.policyRevision &&
            sourceCaseId == other.sourceCaseId &&
            ruleHash == other.ruleHash &&
            ruleSnapshotJson == other.ruleSnapshotJson &&
            operationKind == other.operationKind &&
            targetGrantId == other.targetGrantId &&
            expectedLifecycleRevision == other.expectedLifecycleRevision

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_PENDING_RESULTS = 50
        const val LOCK_OPERATION_SQL = """
            SELECT * FROM delegation_statutory_operations
            WHERE operation_id = :operation AND principal_party_id = :principal
            FOR UPDATE
        """
        const val LOCK_GRANT_SQL = """
            SELECT * FROM delegation_grants WHERE id = :grant FOR UPDATE
        """
        const val ACTIVATE_ACCEPTED_GRANT_SQL = """
            UPDATE delegation_grants
            SET status = 'ACTIVE', lifecycle_revision = :nextRevision,
                accept_statutory_operation_id = :operation, updated_at = :at
            WHERE id = :grant AND status = 'OFFERED' AND lifecycle_revision = :revision
                AND accept_sca_session_id IS NULL AND accept_statutory_operation_id IS NULL
        """
        const val MARK_EXECUTED_SQL = """
            UPDATE delegation_statutory_operations
            SET state = 'EXECUTED', grant_id = :grant, executed_at = :at
            WHERE operation_id = :operation AND state = 'PENDING'
        """
        const val INSERT_DECISION_SQL = """
            WITH eligible_operation AS (
                SELECT operation_id
                FROM delegation_statutory_operations
                WHERE operation_id = :operation AND state = 'PENDING' AND expires_at > :decidedAt
                FOR UPDATE
            )
            INSERT INTO delegation_statutory_decisions
                (operation_id, actor_party_id, sca_session_id, decision, decided_at)
            SELECT operation_id, :actor, :sca, :verdict, :decidedAt
            FROM eligible_operation
            WHERE true
            ON CONFLICT DO NOTHING
        """
        const val INSERT_SQL = """
            INSERT INTO delegation_statutory_operations
                (operation_id, principal_party_id, initiator_party_id, request_key, request_hash,
                 payload_json, policy_id, policy_revision, source_case_id, rule_hash,
                 rule_snapshot_json, state, created_at, expires_at,
                 operation_kind, target_grant_id, expected_lifecycle_revision)
            VALUES (:id, :principal, :initiator, :key, :requestHash,
                    :payload, :policyId, :policyRevision, :sourceCase, :ruleHash,
                    :ruleSnapshot, 'PENDING', :createdAt, :expiresAt,
                    :kind, :targetGrant, :expectedRevision)
            ON CONFLICT (principal_party_id, request_key) DO NOTHING
        """
    }
}
