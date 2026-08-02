// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.persistence.repository

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.account.application.port.out.AccountOutboxRepository
import com.openbank.account.application.port.out.WithdrawalProposalRepository
import com.openbank.account.domain.model.WithdrawalProposal
import com.openbank.account.domain.model.WithdrawalProposalStatus
import com.openbank.account.infrastructure.persistence.entity.WithdrawalProposalEntity
import com.openbank.libs.domain.event.DomainEvent
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
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
}
