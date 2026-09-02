// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.persistence.repository

import com.openbank.delegation.application.port.out.DelegationLifecycleApprovalRepository
import com.openbank.delegation.application.port.out.LifecycleApprovalCreateOutcome
import com.openbank.delegation.application.port.out.LifecycleApprovalDecision
import com.openbank.delegation.domain.model.DelegationLifecycleApproval
import com.openbank.delegation.infrastructure.persistence.entity.DelegationLifecycleApprovalEntity
import com.openbank.libs.governance.ProposalState
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import org.hibernate.reactive.mutiny.Mutiny
import java.util.UUID

/**
 * Postgres-backed lifecycle approval store.
 *
 * The proposal row is the serialisation point, so concurrent rejections cannot rewrite terminal
 * evidence across replicas. Execution is absent until this repository can use the authoritative
 * lifecycle revision/CAS seam; no detached grant entity is merged here.
 */
@ApplicationScoped
class DelegationLifecycleApprovalRepositoryImpl : DelegationLifecycleApprovalRepository,
    PanacheRepository<DelegationLifecycleApprovalEntity> {

    override suspend fun create(candidate: DelegationLifecycleApproval): LifecycleApprovalCreateOutcome =
        Panache.withTransaction {
            Panache.getSession().flatMap { session ->
                session.createNativeQuery<Any>(INSERT_SQL)
                    .setParameter("id", candidate.id)
                    .setParameter("delegationId", candidate.action.delegationId)
                    .setParameter("operation", candidate.action.operation.name)
                    .setParameter("requestedReason", candidate.action.reason)
                    .setParameter("requestKey", candidate.requestKey)
                    .setParameter("proposedBy", candidate.proposedBy)
                    .setParameter("proposedAt", candidate.proposedAt)
                    .setParameter("state", candidate.state.name)
                    .executeUpdate()
                    .flatMap { inserted ->
                        find("requestKey", candidate.requestKey)
                            .firstResult<DelegationLifecycleApprovalEntity>()
                            .map { persisted ->
                                val approval = requireNotNull(persisted) {
                                    "Lifecycle approval insert/replay produced no row"
                                }.toDomain()
                                if (inserted == 1) {
                                    LifecycleApprovalCreateOutcome.Created(approval)
                                } else {
                                    LifecycleApprovalCreateOutcome.Replayed(approval)
                                }
                            }
                    }
            }
        }.awaitSuspending()

    override suspend fun findApproval(id: UUID): DelegationLifecycleApproval? = Panache.withSession {
        find("id", id).firstResult<DelegationLifecycleApprovalEntity>()
    }.awaitSuspending()?.toDomain()

    override suspend fun findByRequestKey(requestKey: String): DelegationLifecycleApproval? = Panache.withSession {
        find("requestKey", requestKey).firstResult<DelegationLifecycleApprovalEntity>()
    }.awaitSuspending()?.toDomain()

    override suspend fun list(state: ProposalState?, limit: Int): List<DelegationLifecycleApproval> =
        Panache.withSession {
            val query = if (state == null) {
                find("order by proposedAt asc")
            } else {
                find("state = ?1 order by proposedAt asc", state)
            }
            query.range(0, limit.coerceAtLeast(1) - 1).list<DelegationLifecycleApprovalEntity>()
        }.awaitSuspending().map { it.toDomain() }

    override suspend fun decideAtomically(
        id: UUID,
        decide: (DelegationLifecycleApproval) -> LifecycleApprovalDecision,
    ): DelegationLifecycleApproval? = Panache.withTransaction {
        Panache.getSession().flatMap { session ->
            lockApproval(session, id).flatMap { approvalRows ->
                val approvalEntity = approvalRows.firstOrNull()
                    ?: return@flatMap Uni.createFrom().nullItem<DelegationLifecycleApproval>()
                persistDecision(session, approvalEntity, decide(approvalEntity.toDomain()))
            }
        }
    }.awaitSuspending()

    private fun persistDecision(
        session: Mutiny.Session,
        entity: DelegationLifecycleApprovalEntity,
        decision: LifecycleApprovalDecision,
    ): Uni<DelegationLifecycleApproval> = when (decision) {
        is LifecycleApprovalDecision.Replayed -> Uni.createFrom().item(decision.approval)

        is LifecycleApprovalDecision.Rejected -> {
            entity.applyDecision(decision.approval)
            session.merge(entity).replaceWith(decision.approval)
        }
    }

    private fun lockApproval(
        session: Mutiny.Session,
        id: UUID,
    ): Uni<List<DelegationLifecycleApprovalEntity>> = session
        .createNativeQuery(LOCK_APPROVAL_SQL, DelegationLifecycleApprovalEntity::class.java)
        .setParameter("id", id)
        .resultList

    private companion object {
        const val INSERT_SQL = """
            INSERT INTO delegation_lifecycle_approvals
                (id, delegation_id, operation, requested_reason, request_key,
                 proposed_by, proposed_at, state)
            VALUES (:id, :delegationId, :operation, :requestedReason, :requestKey,
                    :proposedBy, :proposedAt, :state)
            ON CONFLICT (request_key) DO NOTHING
        """

        const val LOCK_APPROVAL_SQL =
            "select * from delegation_lifecycle_approvals where id = :id for update"
    }
}
