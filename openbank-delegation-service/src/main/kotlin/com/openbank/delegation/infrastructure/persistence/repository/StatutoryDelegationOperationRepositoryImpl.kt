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
import com.openbank.delegation.domain.event.DelegationOffered
import com.openbank.delegation.domain.model.DelegationGrant
import com.openbank.delegation.domain.model.StatutoryDecisionVerdict
import com.openbank.delegation.domain.model.StatutoryDelegationDecision
import com.openbank.delegation.domain.model.StatutoryDelegationOperation
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
import java.time.Instant
import java.util.UUID

/** The unique principal/request key is the concurrent replay serialisation point. */
@ApplicationScoped
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
            ruleSnapshotJson == other.ruleSnapshotJson

    private companion object {
        const val LOCK_OPERATION_SQL = """
            SELECT * FROM delegation_statutory_operations
            WHERE operation_id = :operation AND principal_party_id = :principal
            FOR UPDATE
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
                 rule_snapshot_json, state, created_at, expires_at)
            VALUES (:id, :principal, :initiator, :key, :requestHash,
                    :payload, :policyId, :policyRevision, :sourceCase, :ruleHash,
                    :ruleSnapshot, 'PENDING', :createdAt, :expiresAt)
            ON CONFLICT (principal_party_id, request_key) DO NOTHING
        """
    }
}
