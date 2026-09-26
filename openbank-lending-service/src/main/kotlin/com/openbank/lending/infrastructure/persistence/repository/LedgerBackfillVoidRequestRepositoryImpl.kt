// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.persistence.repository

import com.openbank.lending.application.port.out.LedgerBackfillVoidRequestRepository
import com.openbank.lending.infrastructure.persistence.entity.LedgerBackfillVoidRequestEntity
import com.openbank.libs.governance.ProposalState
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase
import io.quarkus.hibernate.reactive.panache.common.WithSession
import io.quarkus.hibernate.reactive.panache.common.WithTransaction
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import java.time.OffsetDateTime
import java.util.UUID

@ApplicationScoped
class LedgerBackfillVoidRequestRepositoryImpl :
    LedgerBackfillVoidRequestRepository,
    PanacheRepositoryBase<LedgerBackfillVoidRequestEntity, UUID> {

    /** `merge`, not `persist`: the id is application-assigned (see JpaCompliancePackActivationRepository). */
    @WithTransaction
    override fun save(entity: LedgerBackfillVoidRequestEntity): Uni<LedgerBackfillVoidRequestEntity> =
        Panache.getSession().flatMap { it.merge(entity) }

    @WithSession
    override fun findById(id: UUID): Uni<LedgerBackfillVoidRequestEntity?> = find("id", id).firstResult()

    @WithSession
    override fun listRecent(limit: Int): Uni<List<LedgerBackfillVoidRequestEntity>> = find(
        "order by proposedAt desc, id",
    ).page<LedgerBackfillVoidRequestEntity>(0, limit).list<LedgerBackfillVoidRequestEntity>()

    @WithSession
    override fun findProposedByHash(planHash: String): Uni<LedgerBackfillVoidRequestEntity?> =
        find("planHash = ?1 and state = ?2", planHash, ProposalState.PROPOSED).firstResult()

    @WithSession
    override fun findSignedOffByHash(planHash: String): Uni<List<LedgerBackfillVoidRequestEntity>> = find(
        "planHash = ?1 and state in ?2 order by proposedAt desc",
        planHash,
        listOf(ProposalState.APPROVED, ProposalState.EXECUTED),
    ).list()

    @WithTransaction
    override fun compareAndSetDecision(entity: LedgerBackfillVoidRequestEntity): Uni<Int> =
        Panache.getSession().flatMap { session ->
            session.createMutationQuery(DECIDE_HQL)
                .setParameter("state", entity.state)
                .setParameter("decidedBy", entity.decidedBy)
                .setParameter("decidedAt", entity.decidedAt)
                .setParameter("reason", entity.decisionReason)
                .setParameter("updatedAt", entity.updatedAt)
                .setParameter("id", entity.id)
                .setParameter("from", ProposalState.PROPOSED)
                .executeUpdate()
        }

    @WithTransaction
    override fun claimExecution(id: UUID, executor: String, at: OffsetDateTime, staleBefore: OffsetDateTime): Uni<Int> =
        Panache.getSession().flatMap { session ->
            session.createMutationQuery(CLAIM_HQL)
                .setParameter("by", executor)
                .setParameter("at", at)
                .setParameter("id", id)
                .setParameter("approved", ProposalState.APPROVED)
                .setParameter("stale", staleBefore)
                .executeUpdate()
        }

    @WithTransaction
    override fun recordExecution(id: UUID, resultJson: String, complete: Boolean, at: OffsetDateTime): Uni<Int> =
        Panache.getSession().flatMap { session ->
            session.createMutationQuery(if (complete) COMPLETE_HQL else RELEASE_HQL)
                .setParameter("result", resultJson)
                .setParameter("at", at)
                .setParameter("id", id)
                .also { if (complete) it.setParameter("executed", ProposalState.EXECUTED) }
                .executeUpdate()
        }

    private companion object {
        const val DECIDE_HQL =
            "update LedgerBackfillVoidRequestEntity " +
                "set state = :state, decidedBy = :decidedBy, decidedAt = :decidedAt, " +
                "decisionReason = :reason, updatedAt = :updatedAt " +
                "where id = :id and state = :from"
        const val CLAIM_HQL =
            "update LedgerBackfillVoidRequestEntity set executedBy = :by, executedAt = :at, updatedAt = :at " +
                "where id = :id and state = :approved and (executedAt is null or executedAt < :stale)"
        const val COMPLETE_HQL =
            "update LedgerBackfillVoidRequestEntity set state = :executed, lastResult = :result, updatedAt = :at " +
                "where id = :id"
        const val RELEASE_HQL =
            "update LedgerBackfillVoidRequestEntity set executedAt = null, lastResult = :result, updatedAt = :at " +
                "where id = :id"
    }
}
