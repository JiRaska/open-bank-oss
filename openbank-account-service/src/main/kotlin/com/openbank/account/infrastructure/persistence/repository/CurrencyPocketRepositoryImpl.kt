// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

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
import java.time.Clock
import java.util.UUID

@ApplicationScoped
class CurrencyPocketRepositoryImpl(private val clock: Clock) :
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
        // Audit timestamps come from the injected Clock here, not the entity (ADR-0100): the
        // column DEFAULT never applies because Hibernate writes every insertable column.
        val now = clock.instant()
        entity.createdAt = now
        entity.updatedAt = now
        return Panache.withTransaction { persist(entity).replaceWith(entity) }.awaitSuspending().toDomain()
    }

    /** Persist within the caller's transaction (#465: account + pocket + idempotency commit atomically). */
    fun persistInTransaction(pocket: CurrencyPocket): io.smallrye.mutiny.Uni<Void> {
        val entity = pocket.toEntity()
        // Same Clock stamping as save() (ADR-0100 / #540) — this path bypasses save() entirely.
        val now = clock.instant()
        entity.createdAt = now
        entity.updatedAt = now
        return persist(entity).replaceWithVoid()
    }

    override suspend fun update(pocket: CurrencyPocket): CurrencyPocket = Panache.withTransaction {
        find("id", pocket.id).firstResult().flatMap { existing ->
            if (existing == null) throw IllegalStateException("Pocket ${pocket.id} not found for update")
            existing.status = pocket.status.name
            existing.closedAt = pocket.closedAt
            existing.version = pocket.version
            existing.updatedAt = clock.instant()
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
