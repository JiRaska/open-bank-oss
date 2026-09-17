// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.persistence.repository

import com.openbank.delegation.application.port.out.StatutoryDelegationOperationRepository
import com.openbank.delegation.application.port.out.StatutoryOperationCreateOutcome
import com.openbank.delegation.domain.model.StatutoryDelegationOperation
import com.openbank.delegation.domain.model.StatutoryOperationState
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
                                require(persisted.sameEvidenceAs(operation)) {
                                    "request key already belongs to different statutory evidence"
                                }
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
