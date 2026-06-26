// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.ledger.infrastructure.persistence.repository

import com.openbank.ledger.application.port.out.GlAccountRepository
import com.openbank.ledger.domain.model.GlAccount
import com.openbank.ledger.domain.model.GlAccountType
import com.openbank.ledger.infrastructure.persistence.entity.GlAccountEntity
import com.openbank.libs.domain.money.CurrencyCode
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
class PanacheGlAccountRepository :
    GlAccountRepository,
    PanacheRepository<GlAccountEntity> {

    override suspend fun findById(id: UUID): GlAccount? =
        Panache.withSession { find("id", id).firstResult() }.awaitSuspending()?.toDomain()

    override suspend fun findByCode(code: String): GlAccount? =
        Panache.withSession { find("code", code).firstResult() }.awaitSuspending()?.toDomain()

    override suspend fun save(account: GlAccount): GlAccount {
        val entity = account.toEntity()
        Panache.withTransaction { persist(entity) }.awaitSuspending()
        return entity.toDomain()
    }

    private fun GlAccountEntity.toDomain() = GlAccount(
        id = id,
        code = code,
        name = name,
        type = GlAccountType.valueOf(type),
        currency = CurrencyCode.of(currencyCode),
        parentId = parentId,
        isLeaf = isLeaf,
        isEnabled = isEnabled,
        createdAt = createdAt,
    )

    private fun GlAccount.toEntity() = GlAccountEntity().also {
        it.id = id
        it.code = code
        it.name = name
        it.type = type.name
        it.currencyCode = currency.code
        it.parentId = parentId
        it.isLeaf = isLeaf
        it.isEnabled = isEnabled
        it.createdAt = createdAt
    }
}
