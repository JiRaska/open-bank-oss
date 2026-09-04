// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.persistence.repository

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.delegation.application.port.out.DelegationConcurrentTransitionException
import com.openbank.delegation.application.port.out.DelegationLifecycleApprovalRepository
import com.openbank.delegation.application.port.out.DelegationOutboxRepository
import com.openbank.delegation.application.port.out.LifecycleApprovalCreateOutcome
import com.openbank.delegation.application.port.out.LifecycleApprovalDecision
import com.openbank.delegation.domain.model.DelegationGrant
import com.openbank.delegation.domain.model.DelegationLifecycleApproval
import com.openbank.delegation.infrastructure.persistence.entity.DelegationGrantEntity
import com.openbank.delegation.infrastructure.persistence.entity.DelegationLifecycleApprovalEntity
import com.openbank.libs.domain.event.DomainEvent
import com.openbank.libs.governance.ProposalState
import com.openbank.libs.persistence.outbox.OutboxMessage
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
 * evidence across replicas. Executed decisions use the authoritative lifecycle CAS and outbox in
 * this same transaction; no detached grant entity is merged here.
 */
@ApplicationScoped
class DelegationLifecycleApprovalRepositoryImpl(
    private val outboxRepository: DelegationOutboxRepository,
    private val objectMapper: ObjectMapper,
) : DelegationLifecycleApprovalRepository,
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
                    .setParameter("expectedLifecycleRevision", candidate.expectedLifecycleRevision)
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

    override suspend fun list(state: ProposalState?, limit: Int): List<DelegationLifecycleApproval> {
        val rows = Panache.withSession {
            Panache.getSession().map { session ->
                val query = session.createQuery(
                    if (state == null) {
                        "from DelegationLifecycleApprovalEntity order by proposedAt asc"
                    } else {
                        "from DelegationLifecycleApprovalEntity where state = :state order by proposedAt asc"
                    },
                    DelegationLifecycleApprovalEntity::class.java,
                ).setMaxResults(limit.coerceAtLeast(1))
                if (state == null) query else query.setParameter("state", state)
            }.flatMap { query -> query.resultList }
        }.awaitSuspending()
        return rows.map { it.toDomain() }
    }

    override suspend fun decideAtomically(
        id: UUID,
        decide: (DelegationLifecycleApproval, DelegationGrant?) -> LifecycleApprovalDecision,
    ): DelegationLifecycleApproval? = Panache.withTransaction {
        Panache.getSession().flatMap { session ->
            lockApproval(session, id).flatMap { approvalRows ->
                val approvalEntity = approvalRows.firstOrNull()
                    ?: return@flatMap Uni.createFrom().nullItem<DelegationLifecycleApproval>()
                lockGrant(session, approvalEntity.delegationId).flatMap { grantRows ->
                    persistDecision(
                        session,
                        approvalEntity,
                        decide(approvalEntity.toDomain(), grantRows.firstOrNull()?.toDomain()),
                    )
                }
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

        is LifecycleApprovalDecision.Executed -> updateLifecycle(session, decision.grant)
            .flatMap { outboxRepository.persistInTransaction(outboxMessage(decision.event)) }
            .flatMap {
                entity.applyDecision(decision.approval)
                session.merge(entity).replaceWith(decision.approval)
            }
    }

    private fun updateLifecycle(session: Mutiny.Session, grant: DelegationGrant): Uni<Int> {
        val expectedRevision = grant.lifecycleRevision - 1
        if (expectedRevision < 0) {
            return Uni.createFrom().failure(
                IllegalArgumentException("lifecycle transition must advance revision from zero"),
            )
        }
        return session.createNativeQuery<Any>(UPDATE_LIFECYCLE_SQL)
            .setParameter("status", grant.status.name)
            .setParameter("lifecycleRevision", grant.lifecycleRevision)
            .setParameter("acceptScaSessionId", grant.acceptScaSessionId)
            .setParameter("updatedAt", grant.updatedAt)
            .setParameter("closedAt", grant.closedAt)
            .setParameter("closedBy", grant.closedBy)
            .setParameter("closedReason", grant.closedReason)
            .setParameter("id", grant.id)
            .setParameter("expectedRevision", expectedRevision)
            .executeUpdate()
            .flatMap { count ->
                if (count == 1) {
                    Uni.createFrom().item(count)
                } else {
                    Uni.createFrom().failure(DelegationConcurrentTransitionException(grant.id, expectedRevision))
                }
            }
    }

    private fun outboxMessage(event: DomainEvent): OutboxMessage = OutboxMessage(
        aggregateId = event.aggregateId,
        eventType = event.eventType,
        payload = objectMapper.writeValueAsString(event),
        createdAt = event.occurredAt,
    )

    private fun lockApproval(session: Mutiny.Session, id: UUID): Uni<List<DelegationLifecycleApprovalEntity>> = session
        .createNativeQuery(LOCK_APPROVAL_SQL, DelegationLifecycleApprovalEntity::class.java)
        .setParameter("id", id)
        .resultList

    private fun lockGrant(session: Mutiny.Session, id: UUID): Uni<List<DelegationGrantEntity>> = session
        .createNativeQuery(LOCK_GRANT_SQL, DelegationGrantEntity::class.java)
        .setParameter("id", id)
        .resultList

    private companion object {
        const val INSERT_SQL = """
            INSERT INTO delegation_lifecycle_approvals
                (id, delegation_id, operation, requested_reason, request_key,
                 proposed_by, proposed_at, state, expected_lifecycle_revision)
            VALUES (:id, :delegationId, :operation, :requestedReason, :requestKey,
                    :proposedBy, :proposedAt, :state, :expectedLifecycleRevision)
            ON CONFLICT (request_key) DO NOTHING
        """

        const val LOCK_APPROVAL_SQL =
            "select * from delegation_lifecycle_approvals where id = :id for update"

        const val LOCK_GRANT_SQL = "select * from delegation_grants where id = :id for update"

        const val UPDATE_LIFECYCLE_SQL = """
            UPDATE delegation_grants
            SET status = :status, lifecycle_revision = :lifecycleRevision,
                accept_sca_session_id = :acceptScaSessionId, updated_at = :updatedAt,
                closed_at = :closedAt, closed_by = :closedBy, closed_reason = :closedReason
            WHERE id = :id AND lifecycle_revision = :expectedRevision
        """
    }
}
