// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.persistence.repository

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.account.application.port.out.AccountOutboxRepository
import com.openbank.account.application.port.out.WithdrawalApprovalDecision
import com.openbank.account.application.port.out.WithdrawalDecisionResult
import com.openbank.account.application.port.out.WithdrawalProposalRepository
import com.openbank.account.domain.model.WithdrawalProposal
import com.openbank.account.domain.model.WithdrawalProposalStatus
import com.openbank.account.infrastructure.persistence.entity.WithdrawalApprovalDecisionEntity
import com.openbank.account.infrastructure.persistence.entity.WithdrawalApprovalDecisionId
import com.openbank.account.infrastructure.persistence.entity.WithdrawalProposalEntity
import com.openbank.libs.domain.event.DomainEvent
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import org.hibernate.reactive.mutiny.Mutiny.Session
import java.time.OffsetDateTime
import java.util.UUID

@ApplicationScoped
class WithdrawalProposalRepositoryImpl(
    private val outboxRepository: AccountOutboxRepository,
    private val objectMapper: ObjectMapper,
) : WithdrawalProposalRepository,
    PanacheRepository<WithdrawalProposalEntity> {

    override suspend fun save(proposal: WithdrawalProposal): WithdrawalProposal =
        Panache.withTransaction { mergeProposal(proposal) }.awaitSuspending().toDomain()

    override suspend fun save(proposal: WithdrawalProposal, event: DomainEvent): WithdrawalProposal =
        Panache.withTransaction {
            mergeProposal(proposal).flatMap { merged ->
                outboxRepository.persistInTransaction(
                    OutboxMessage(
                        aggregateId = event.aggregateId,
                        eventType = event.eventType,
                        payload = objectMapper.writeValueAsString(event),
                        createdAt = event.occurredAt,
                    ),
                ).replaceWith(merged)
            }
        }.awaitSuspending().toDomain()

    private fun mergeProposal(proposal: WithdrawalProposal): Uni<WithdrawalProposalEntity> =
        Panache.getSession().flatMap { session -> session.merge(WithdrawalProposalEntity.fromDomain(proposal)) }

    override suspend fun findById(id: UUID): WithdrawalProposal? =
        Panache.withSession { find("id", id).firstResult<WithdrawalProposalEntity>() }
            .awaitSuspending()?.toDomain()

    override suspend fun findByAccountAndStatus(
        accountId: UUID,
        status: WithdrawalProposalStatus?,
    ): List<WithdrawalProposal> = Panache.withSession {
        if (status == null) {
            find("accountId", accountId).list<WithdrawalProposalEntity>()
        } else {
            find("accountId = ?1 and status = ?2", accountId, status).list<WithdrawalProposalEntity>()
        }
    }.awaitSuspending().map { it.toDomain() }

    override suspend fun findExpirable(now: OffsetDateTime, limit: Int): List<WithdrawalProposal> =
        Panache.withSession {
            find("status = ?1 and expiresAt <= ?2 order by expiresAt asc", WithdrawalProposalStatus.PENDING, now)
                .page<WithdrawalProposalEntity>(0, limit)
                .list<WithdrawalProposalEntity>()
        }.awaitSuspending().map { it.toDomain() }

    override suspend fun findDecisions(proposalIds: Set<UUID>): List<WithdrawalApprovalDecision> {
        if (proposalIds.isEmpty()) return emptyList()
        return Panache.withSession {
            Panache.getSession().flatMap { session ->
                session.createSelectionQuery(
                    "from WithdrawalApprovalDecisionEntity d where d.proposalId in :proposalIds",
                    WithdrawalApprovalDecisionEntity::class.java,
                ).setParameter("proposalIds", proposalIds).resultList
            }
        }.awaitSuspending().map { decision ->
            WithdrawalApprovalDecision(
                proposalId = decision.proposalId,
                partyId = decision.partyId,
                approved = decision.approved,
                decidedAt = decision.decidedAt,
            )
        }
    }

    override suspend fun recordDecision(
        proposalId: UUID,
        actorPartyId: UUID,
        approved: Boolean,
        scaSessionId: UUID,
        decidedAt: OffsetDateTime,
        approvedEvent: DomainEvent,
    ): WithdrawalDecisionResult = Panache.withTransaction {
        val command = DecisionWriteCommand(actorPartyId, approved, scaSessionId, decidedAt, approvedEvent)
        Panache.getSession().flatMap { session ->
            session.find(WithdrawalProposalEntity::class.java, proposalId, LockModeType.PESSIMISTIC_WRITE)
                .flatMap { proposal ->
                    applyDecision(
                        session,
                        requireNotNull(proposal),
                        command,
                        outboxRepository,
                        objectMapper,
                    )
                }
        }
    }.awaitSuspending()
}

private data class DecisionWriteCommand(
    val actor: UUID,
    val approved: Boolean,
    val scaSessionId: UUID,
    val decidedAt: OffsetDateTime,
    val event: DomainEvent,
)

private fun applyDecision(
    session: Session,
    proposal: WithdrawalProposalEntity,
    command: DecisionWriteCommand,
    outboxRepository: AccountOutboxRepository,
    objectMapper: ObjectMapper,
): Uni<WithdrawalDecisionResult> {
    require(proposal.status == WithdrawalProposalStatus.PENDING) { "only a PENDING proposal can be decided" }
    require(command.decidedAt.isBefore(proposal.expiresAt)) { "proposal expired at ${proposal.expiresAt}" }
    // N-of-M eligibility is frozen on the operation. SOLO proposals deliberately authorize the
    // owner or an exact currently-active SOLE/1 representative in the application service, so a
    // later mandate revocation takes effect immediately instead of being defeated by a snapshot.
    require(proposal.approvalGroupId == null || command.actor in proposal.eligibleApproverIds) {
        "party ${command.actor} is not in the approval roster"
    }
    return session.find(
        WithdrawalApprovalDecisionEntity::class.java,
        WithdrawalApprovalDecisionId(proposal.id, command.actor),
    ).flatMap { existing ->
        if (existing != null) {
            replayDecision(session, proposal, existing, command.approved, command.scaSessionId)
        } else {
            persistDecision(session, proposal, command, outboxRepository, objectMapper)
        }
    }
}

private fun replayDecision(
    session: Session,
    proposal: WithdrawalProposalEntity,
    existing: WithdrawalApprovalDecisionEntity,
    approved: Boolean,
    scaSessionId: UUID,
): Uni<WithdrawalDecisionResult> {
    require(existing.scaSessionId == scaSessionId && existing.approved == approved) {
        "party ${existing.partyId} has already decided proposal ${proposal.id}"
    }
    return countApprovals(session, proposal.id).map { WithdrawalDecisionResult(proposal.toDomain(), it, true) }
}

private fun persistDecision(
    session: Session,
    proposal: WithdrawalProposalEntity,
    command: DecisionWriteCommand,
    outboxRepository: AccountOutboxRepository,
    objectMapper: ObjectMapper,
): Uni<WithdrawalDecisionResult> {
    val decision = WithdrawalApprovalDecisionEntity().apply {
        proposalId = proposal.id
        partyId = command.actor
        approved = command.approved
        scaSessionId = command.scaSessionId
        decidedAt = command.decidedAt
    }
    return session.persist(decision).flatMap {
        if (!command.approved) {
            reject(proposal, command.actor, command.decidedAt)
        } else {
            session.flush().flatMap { countApprovals(session, proposal.id) }
                .flatMap {
                    approveWhenQuorum(
                        proposal,
                        command.actor,
                        command.scaSessionId,
                        command.decidedAt,
                        command.event,
                        it,
                        outboxRepository,
                        objectMapper,
                    )
                }
        }
    }
}

private fun reject(
    proposal: WithdrawalProposalEntity,
    actorPartyId: UUID,
    decidedAt: OffsetDateTime,
): Uni<WithdrawalDecisionResult> {
    proposal.status = WithdrawalProposalStatus.REJECTED
    proposal.decidedBy = actorPartyId
    proposal.decidedAt = decidedAt
    return Uni.createFrom().item(WithdrawalDecisionResult(proposal.toDomain(), 0, false))
}

private fun approveWhenQuorum(
    proposal: WithdrawalProposalEntity,
    actorPartyId: UUID,
    scaSessionId: UUID,
    decidedAt: OffsetDateTime,
    event: DomainEvent,
    count: Int,
    outboxRepository: AccountOutboxRepository,
    objectMapper: ObjectMapper,
): Uni<WithdrawalDecisionResult> {
    val result = WithdrawalDecisionResult(proposal.toDomain(), count, false)
    if (count < proposal.requiredApprovals) return Uni.createFrom().item(result)
    proposal.status = WithdrawalProposalStatus.APPROVED
    proposal.decidedBy = actorPartyId
    proposal.decidedAt = decidedAt
    proposal.scaSessionId = scaSessionId
    val message = OutboxMessage(
        aggregateId = event.aggregateId,
        eventType = event.eventType,
        payload = objectMapper.writeValueAsString(event),
        createdAt = event.occurredAt,
    )
    return outboxRepository.persistInTransaction(message)
        .replaceWith(WithdrawalDecisionResult(proposal.toDomain(), count, false))
}

private fun countApprovals(session: Session, proposalId: UUID): Uni<Int> = session.createSelectionQuery(
    "select count(d) from WithdrawalApprovalDecisionEntity d " +
        "where d.proposalId = :proposalId and d.approved = true",
    java.lang.Long::class.java,
).setParameter("proposalId", proposalId)
    .singleResult
    .map { it.toInt() }
