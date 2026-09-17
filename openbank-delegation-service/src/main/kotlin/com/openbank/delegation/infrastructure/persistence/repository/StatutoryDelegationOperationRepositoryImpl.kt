// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.persistence.repository

import com.openbank.delegation.application.port.out.StatutoryDecisionClosed
import com.openbank.delegation.application.port.out.StatutoryDecisionConflict
import com.openbank.delegation.application.port.out.StatutoryDelegationOperationRepository
import com.openbank.delegation.application.port.out.StatutoryOperationCreateConflict
import com.openbank.delegation.application.port.out.StatutoryOperationCreateOutcome
import com.openbank.delegation.domain.model.StatutoryDelegationDecision
import com.openbank.delegation.domain.model.StatutoryDelegationOperation
import com.openbank.delegation.domain.model.StatutoryOperationState
import com.openbank.delegation.infrastructure.persistence.entity.StatutoryDelegationDecisionEntity
import com.openbank.delegation.infrastructure.persistence.entity.StatutoryDelegationOperationEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

/** The unique principal/request key is the concurrent replay serialisation point. */
@ApplicationScoped
class StatutoryDelegationOperationRepositoryImpl :
    StatutoryDelegationOperationRepository,
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
        const val INSERT_DECISION_SQL = """
            INSERT INTO delegation_statutory_decisions
                (operation_id, actor_party_id, sca_session_id, decision, decided_at)
            SELECT operation_id, :actor, :sca, :verdict, :decidedAt
            FROM delegation_statutory_operations
            WHERE operation_id = :operation AND state = 'PENDING' AND expires_at > :decidedAt
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
