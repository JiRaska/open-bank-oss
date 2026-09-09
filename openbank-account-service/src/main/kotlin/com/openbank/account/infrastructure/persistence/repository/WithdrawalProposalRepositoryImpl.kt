// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.persistence.repository

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.account.application.port.out.AccountOutboxRepository
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

    override suspend fun recordDecision(
        proposalId: UUID,
        actorPartyId: UUID,
        approved: Boolean,
        scaSessionId: UUID,
        decidedAt: OffsetDateTime,
        approvedEvent: DomainEvent,
    ): WithdrawalDecisionResult = Panache.withTransaction {
        Panache.getSession().flatMap { session ->
            session.find(WithdrawalProposalEntity::class.java, proposalId, LockModeType.PESSIMISTIC_WRITE)
                .flatMap { proposal ->
                    requireNotNull(proposal) { "withdrawal proposal $proposalId not found" }
                    require(proposal.status == WithdrawalProposalStatus.PENDING) {
                        "only a PENDING proposal can be decided (is ${proposal.status})"
                    }
                    require(decidedAt.isBefore(proposal.expiresAt)) { "proposal expired at ${proposal.expiresAt}" }
                    require(actorPartyId in proposal.eligibleApproverIds) {
                        "party $actorPartyId is not in the proposal's immutable approval roster"
                    }
                    session.find(
                        WithdrawalApprovalDecisionEntity::class.java,
                        WithdrawalApprovalDecisionId(proposalId, actorPartyId),
                    ).flatMap { existing ->
                        if (existing != null) {
                            require(existing.scaSessionId == scaSessionId && existing.approved == approved) {
                                "party $actorPartyId has already decided proposal $proposalId"
                            }
                            countApprovals(session, proposalId).map { count ->
                                WithdrawalDecisionResult(proposal.toDomain(), count, replayed = true)
                            }
                        } else {
                            val decision = WithdrawalApprovalDecisionEntity().apply {
                                this.proposalId = proposalId
                                partyId = actorPartyId
                                this.approved = approved
                                this.scaSessionId = scaSessionId
                                this.decidedAt = decidedAt
                            }
                            session.persist(decision).flatMap {
                                if (!approved) {
                                    proposal.status = WithdrawalProposalStatus.REJECTED
                                    proposal.decidedBy = actorPartyId
                                    proposal.decidedAt = decidedAt
                                    Uni.createFrom().item(WithdrawalDecisionResult(proposal.toDomain(), 0, false))
                                } else {
                                    session.flush().flatMap { countApprovals(session, proposalId) }.flatMap { count ->
                                        if (count >= proposal.requiredApprovals) {
                                            proposal.status = WithdrawalProposalStatus.APPROVED
                                            proposal.decidedBy = actorPartyId
                                            proposal.decidedAt = decidedAt
                                            proposal.scaSessionId = scaSessionId
                                            outboxRepository.persistInTransaction(
                                                OutboxMessage(
                                                    aggregateId = approvedEvent.aggregateId,
                                                    eventType = approvedEvent.eventType,
                                                    payload = objectMapper.writeValueAsString(approvedEvent),
                                                    createdAt = approvedEvent.occurredAt,
                                                ),
                                            ).replaceWith(WithdrawalDecisionResult(proposal.toDomain(), count, false))
                                        } else {
                                            Uni.createFrom().item(
                                                WithdrawalDecisionResult(proposal.toDomain(), count, false),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
        }
    }.awaitSuspending()

    private fun countApprovals(session: org.hibernate.reactive.mutiny.Mutiny.Session, proposalId: UUID): Uni<Int> =
        session.createSelectionQuery(
            "select count(d) from WithdrawalApprovalDecisionEntity d " +
                "where d.proposalId = :proposalId and d.approved = true",
            java.lang.Long::class.java,
        ).setParameter("proposalId", proposalId)
            .singleResult
            .map { it.toInt() }
}
