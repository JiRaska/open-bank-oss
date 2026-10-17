// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.infrastructure.persistence.repository

import com.openbank.ledger.application.port.out.AccountingClassMapRepository
import com.openbank.ledger.domain.model.AccountingClassMapping
import com.openbank.ledger.domain.model.AccountingRole
import com.openbank.ledger.infrastructure.persistence.entity.AccountingClassGlMapEntity
import com.openbank.ledger.infrastructure.persistence.entity.GlAccountEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class PanacheAccountingClassMapRepository :
    AccountingClassMapRepository,
    PanacheRepository<AccountingClassGlMapEntity> {

    override suspend fun find(
        accountingClass: String,
        role: AccountingRole,
        currencyCode: String,
    ): AccountingClassMapping? = Panache.withSession {
        find("accountingClass = ?1 and role = ?2 and currencyCode = ?3", accountingClass, role.name, currencyCode)
            .firstResult()
            .flatMap { row -> if (row == null) Uni.createFrom().nullItem() else withCode(row).map { it } }
    }.awaitSuspending()

    override suspend fun listAll(): List<AccountingClassMapping> = Panache.withSession {
        list("order by accountingClass, role, currencyCode").flatMap { rows ->
            rows.fold(Uni.createFrom().item(emptyList<AccountingClassMapping>())) { acc, row ->
                acc.flatMap { done -> withCode(row).map { done + it } }
            }
        }
    }.awaitSuspending()

    private fun withCode(row: AccountingClassGlMapEntity): Uni<AccountingClassMapping> =
        Panache.getSession().flatMap { session -> session.find(GlAccountEntity::class.java, row.glAccountId) }
            .map { account ->
                AccountingClassMapping(
                    accountingClass = row.accountingClass,
                    role = AccountingRole.valueOf(row.role),
                    currencyCode = row.currencyCode.trim(),
                    glAccountId = row.glAccountId,
                    glCode = checkNotNull(account) { "map row ${row.id} names a missing GL account" }.code,
                )
            }
}
