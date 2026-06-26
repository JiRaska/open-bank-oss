// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.account.infrastructure.persistence.repository

import com.openbank.account.application.port.out.CurrencyPocketRepository
import com.openbank.account.domain.model.CurrencyPocket
import com.openbank.account.domain.model.PocketStatus
import com.openbank.account.infrastructure.persistence.entity.AccountPocketEntity
import com.openbank.libs.domain.money.CurrencyCode
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
class CurrencyPocketRepositoryImpl :
    CurrencyPocketRepository,
    PanacheRepository<AccountPocketEntity> {

    override suspend fun findByAccountId(accountId: UUID): List<CurrencyPocket> =
        Panache.withSession { find("accountId", accountId).list() }
            .awaitSuspending().map { it.toDomain() }

    override suspend fun findByAccountIdAndCurrency(accountId: UUID, currency: String): CurrencyPocket? =
        Panache.withSession {
            find("accountId = ?1 and currencyCode = ?2", accountId, currency).firstResult()
        }.awaitSuspending()?.toDomain()

    override suspend fun save(pocket: CurrencyPocket): CurrencyPocket {
        val entity = pocket.toEntity()
        return Panache.withTransaction { persist(entity).replaceWith(entity) }.awaitSuspending().toDomain()
    }

    override suspend fun update(pocket: CurrencyPocket): CurrencyPocket = Panache.withTransaction {
        find("id", pocket.id).firstResult().flatMap { existing ->
            if (existing == null) throw IllegalStateException("Pocket ${pocket.id} not found for update")
            existing.status = pocket.status.name
            existing.closedAt = pocket.closedAt
            existing.version = pocket.version
            io.smallrye.mutiny.Uni.createFrom().item(existing)
        }
    }.awaitSuspending().toDomain()

    private fun AccountPocketEntity.toDomain() = CurrencyPocket(
        id = id,
        accountId = accountId,
        currency = CurrencyCode.of(currencyCode),
        isPrimary = isPrimary,
        status = PocketStatus.valueOf(status),
        openedAt = openedAt,
        closedAt = closedAt,
        version = version,
    )

    private fun CurrencyPocket.toEntity() = AccountPocketEntity().also {
        it.id = id
        it.accountId = accountId
        it.currencyCode = currency.code
        it.isPrimary = isPrimary
        it.status = status.name
        it.openedAt = openedAt
        it.closedAt = closedAt
        it.version = version
    }
}
