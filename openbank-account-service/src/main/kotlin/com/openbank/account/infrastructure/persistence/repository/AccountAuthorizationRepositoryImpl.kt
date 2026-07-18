// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.persistence.repository

import com.openbank.account.application.port.out.AccountAuthorizationRepository
import com.openbank.account.domain.model.AccountAuthorization
import com.openbank.account.infrastructure.persistence.entity.AccountAuthorizationEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

@ApplicationScoped
class AccountAuthorizationRepositoryImpl(private val clock: Clock) :
    AccountAuthorizationRepository,
    PanacheRepository<AccountAuthorizationEntity> {

    override suspend fun save(auth: AccountAuthorization): AccountAuthorization {
        val entity = AccountAuthorizationEntity.fromDomain(auth)
        // merge, not persist: AccountAuthorization has an application-assigned @Id, so persist()
        // schedules an INSERT for every write and every revoke/suspend/reinstate (re-saving the same
        // id) fails on the PK. withTransaction also supplies the reactive session the bare persist
        // lacked (grant returned 422 "No current Mutiny.Session found"). See #1600.
        return Panache.withTransaction {
            Panache.getSession().flatMap { session -> session.merge(entity) }
        }.awaitSuspending().toDomain()
    }

    override suspend fun findById(id: UUID): AccountAuthorization? =
        Panache.withSession { find("id", id).firstResult() }.awaitSuspending()?.toDomain()

    override suspend fun findByAccountId(accountId: UUID): List<AccountAuthorization> =
        Panache.withSession { find("accountId", accountId).list() }.awaitSuspending().map { it.toDomain() }

    override suspend fun findActiveByAccountAndParty(accountId: UUID, partyId: UUID): List<AccountAuthorization> =
        Panache.withSession {
            find(
                "accountId = ?1 and partyId = ?2 and status = 'ACTIVE' and validFrom <= ?3 " +
                    "and (validTo is null or validTo >= ?3)",
                accountId,
                partyId,
                LocalDate.now(clock),
            ).list()
        }.awaitSuspending().map { it.toDomain() }
}
