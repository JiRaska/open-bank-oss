// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.
package com.openbank.notification.infrastructure.persistence.repository

import com.openbank.libs.governance.Proposal
import com.openbank.libs.governance.ProposalState
import com.openbank.notification.application.port.out.DispatchControlStore
import com.openbank.notification.domain.ops.DispatchControlSnapshot
import com.openbank.notification.domain.ops.DispatchState
import com.openbank.notification.domain.ops.ResumeAction
import com.openbank.notification.infrastructure.persistence.entity.DispatchControlLogEntity
import com.openbank.notification.infrastructure.persistence.entity.DispatchResumeProposalEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Clock
import java.time.Instant

@ApplicationScoped
class DispatchControlStoreImpl(private val proposals: DispatchResumeProposalRepository) :
    DispatchControlStore,
    PanacheRepository<DispatchControlLogEntity> {
    @Inject lateinit var clock: Clock


    override suspend fun current(controlKey: String): DispatchControlSnapshot? = Panache.withSession {
        find("controlKey = ?1 order by versionNo desc", controlKey).firstResult()
    }.awaitSuspending()?.toSnapshot()

    override suspend fun append(snapshot: DispatchControlSnapshot) {
        Panache.withTransaction {
            persist(snapshot.toEntity())
        }.awaitSuspending()
    }

    override suspend fun history(controlKey: String, limit: Int): List<DispatchControlSnapshot> = Panache.withSession {
        find("controlKey = ?1 order by versionNo desc", controlKey)
            .range(0, limit.coerceAtLeast(1) - 1).list()
    }.awaitSuspending().map { it.toSnapshot() }

    override suspend fun saveProposal(proposal: Proposal<ResumeAction>) {
        Panache.withTransaction {
            proposals.find("proposalId", proposal.id).firstResult().flatMap { existing ->
                if (existing == null) {
                    proposals.persist(DispatchResumeProposalEntity().apply { applyFrom(proposal) }).replaceWith(Unit)
                } else {
                    existing.applyFrom(proposal)
                    io.smallrye.mutiny.Uni.createFrom().item(Unit)
                }
            }
        }.awaitSuspending()
    }

    override suspend fun findProposal(id: String): Proposal<ResumeAction>? = Panache.withSession {
        proposals.find("proposalId", id).firstResult()
    }.awaitSuspending()?.toProposal()

    private fun DispatchControlLogEntity.toSnapshot() = DispatchControlSnapshot(
        controlKey = controlKey,
        state = DispatchState.valueOf(state),
        version = versionNo,
        reason = reason,
        actor = actor,
        effectiveFrom = effectiveFrom,
        deferredReviewRequired = deferredReviewRequired,
    )

    private fun DispatchControlSnapshot.toEntity() = DispatchControlLogEntity().also {
        it.controlKey = controlKey
        it.state = state.name
        it.versionNo = version
        it.reason = reason
        it.actor = actor
        it.effectiveFrom = effectiveFrom
        it.deferredReviewRequired = deferredReviewRequired
        it.createdAt = Instant.now(clock)
    }

    private fun DispatchResumeProposalEntity.applyFrom(p: Proposal<ResumeAction>) {
        proposalId = p.id
        controlKey = p.action.controlKey
        reason = p.action.reason
        proposedBy = p.proposedBy
        proposedAt = p.proposedAt
        state = p.state.name
        decidedBy = p.decidedBy
        decidedAt = p.decidedAt
        decisionReason = p.decisionReason
        executedAt = p.executedAt
    }

    private fun DispatchResumeProposalEntity.toProposal() = Proposal(
        id = proposalId,
        action = ResumeAction(controlKey, reason ?: ""),
        proposedBy = proposedBy,
        proposedAt = proposedAt,
        state = ProposalState.valueOf(state),
        decidedBy = decidedBy,
        decidedAt = decidedAt,
        decisionReason = decisionReason,
        executedAt = executedAt,
    )
}
