// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.account.infrastructure.persistence.repository

import com.openbank.account.application.port.out.AccountAuthorizationRepository
import com.openbank.account.domain.model.AccountAuthorization
import com.openbank.account.infrastructure.persistence.entity.AccountAuthorizationEntity
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
        persistAndFlush(entity).awaitSuspending()
        return entity.toDomain()
    }

    override suspend fun findById(id: UUID): AccountAuthorization? =
        find("id", id).firstResult().awaitSuspending()?.toDomain()

    override suspend fun findByAccountId(accountId: UUID): List<AccountAuthorization> =
        find("accountId", accountId).list().awaitSuspending().map { it.toDomain() }

    override suspend fun findActiveByAccountAndParty(accountId: UUID, partyId: UUID): List<AccountAuthorization> = find(
        "accountId = ?1 and partyId = ?2 and status = 'ACTIVE' and validFrom <= ?3 and (validTo is null or validTo >= ?3)",
        accountId,
        partyId,
        LocalDate.now(clock),
    ).list().awaitSuspending().map { it.toDomain() }
}
