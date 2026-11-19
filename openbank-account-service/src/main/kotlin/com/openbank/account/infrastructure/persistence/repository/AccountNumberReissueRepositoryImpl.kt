// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.persistence.repository

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.account.application.port.out.AccountNumberReissue
import com.openbank.account.application.port.out.AccountNumberReissueRepository
import com.openbank.account.application.port.out.AccountOutboxRepository
import com.openbank.account.application.port.out.StoredAccountNumber
import com.openbank.account.infrastructure.persistence.entity.AccountEntity
import com.openbank.account.infrastructure.persistence.entity.AccountNumberReissueEntity
import com.openbank.libs.domain.event.DomainEvent
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.quarkus.hibernate.reactive.panache.Panache
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.util.UUID

@ApplicationScoped
class AccountNumberReissueRepositoryImpl(
    private val outboxRepository: AccountOutboxRepository,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) : AccountNumberReissueRepository {

    override suspend fun pageAccountNumbers(afterId: UUID?, limit: Int): List<StoredAccountNumber> =
        Panache.withSession {
            Panache.getSession().flatMap { session ->
                val hql = "select a.id, a.accountNumber from AccountEntity a" +
                    (if (afterId == null) "" else " where a.id > :after") + " order by a.id"
                val query = session.createSelectionQuery(hql, Array<Any>::class.java).setMaxResults(limit)
                if (afterId != null) query.setParameter("after", afterId)
                query.resultList
            }
        }.awaitSuspending().map { StoredAccountNumber(it[0] as UUID, it[1] as String) }

    override suspend fun accountNumberTaken(accountNumber: String): Boolean = Panache.withSession {
        Panache.getSession().flatMap { session ->
            session.createSelectionQuery(
                "select count(a) from AccountEntity a where a.accountNumber = :n",
                java.lang.Long::class.java,
            ).setParameter("n", accountNumber).singleResult
        }
    }.awaitSuspending().toLong() > 0

    override suspend fun reissue(reissue: AccountNumberReissue, event: (newVersion: Long) -> DomainEvent): Boolean =
        Panache.withTransaction {
            Panache.getSession().flatMap { session ->
                session.find(AccountEntity::class.java, reissue.accountId).flatMap { account ->
                    if (account == null || account.accountNumber != reissue.oldAccountNumber) {
                        Uni.createFrom().item(false)
                    } else {
                        account.accountNumber = reissue.newAccountNumber
                        account.updatedAt = clock.instant()
                        session.persist(auditRow(reissue))
                            // Flush so the @Version bump is real before the event quotes it.
                            .flatMap { session.flush() }
                            .flatMap { outboxRepository.persistInTransaction(outboxMessage(event(account.version))) }
                            .replaceWith(true)
                    }
                }
            }
        }.awaitSuspending()

    private fun auditRow(reissue: AccountNumberReissue) = AccountNumberReissueEntity().also {
        it.id = Ids.newId()
        it.accountId = reissue.accountId
        it.oldAccountNumber = reissue.oldAccountNumber
        it.newAccountNumber = reissue.newAccountNumber
        it.reason = reissue.reason
        it.reissuedAt = reissue.reissuedAt
    }

    private fun outboxMessage(event: DomainEvent) = OutboxMessage(
        aggregateId = event.aggregateId,
        eventType = event.eventType,
        payload = objectMapper.writeValueAsString(event),
        createdAt = clock.instant(),
    )
}
