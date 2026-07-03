// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.persistence.repository

import com.openbank.account.application.port.out.AccountRepository
import com.openbank.account.domain.model.Account
import com.openbank.account.domain.model.AccountStatus
import com.openbank.account.domain.model.AccountType
import com.openbank.account.domain.model.SigningRule
import com.openbank.account.infrastructure.persistence.entity.AccountEntity
import com.openbank.libs.domain.account.Iban
import com.openbank.libs.domain.money.CurrencyCode
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
class AccountRepositoryImpl :
    AccountRepository,
    PanacheRepository<AccountEntity> {

    override suspend fun findById(id: UUID): Account? =
        Panache.withSession { find("id", id).firstResult() }.awaitSuspending()?.toDomain()

    override suspend fun findByIban(iban: Iban): Account? =
        Panache.withSession { find("accountNumber", iban.value).firstResult() }.awaitSuspending()?.toDomain()

    override suspend fun findByPartyId(partyId: UUID, limit: Int, afterId: UUID?): List<Account> = Panache.withSession {
        val query = if (afterId != null) {
            find(
                "partyId = ?1 AND id > ?2 ORDER BY CASE accountType WHEN 'CURRENT' THEN 0 WHEN 'SAVINGS' THEN 1 ELSE 2 END, id",
                partyId,
                afterId,
            )
        } else {
            find(
                "partyId = ?1 ORDER BY CASE accountType WHEN 'CURRENT' THEN 0 WHEN 'SAVINGS' THEN 1 ELSE 2 END, id",
                partyId,
            )
        }
        query.page(0, limit).list()
    }.awaitSuspending().map { it.toDomain() }

    override suspend fun searchByIban(normalizedFragment: String, limit: Int, afterId: UUID?): List<Account> =
        Panache.withSession {
            // Escape LIKE wildcards in user input so a typed `%`/`_` is matched literally
            // (custom ESCAPE char '!' avoids backslash ambiguity). The fragment is already
            // upper-cased by the caller and IBANs are stored upper-case, so a case-sensitive
            // LIKE is correct and lets Postgres use the trigram GIN index.
            val escaped = normalizedFragment
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_")
            val pattern = "%$escaped%"
            val query = if (afterId != null) {
                find("accountNumber LIKE ?1 ESCAPE '!' AND id > ?2 ORDER BY id", pattern, afterId)
            } else {
                find("accountNumber LIKE ?1 ESCAPE '!' ORDER BY id", pattern)
            }
            query.page(0, limit).list()
        }.awaitSuspending().map { it.toDomain() }

    override suspend fun save(account: Account): Account {
        val entity = account.toEntity()
        return Panache.withTransaction { persist(entity).replaceWith(entity) }.awaitSuspending().toDomain()
    }

    override suspend fun update(account: Account): Account {
        val entity = account.toEntity()
        return Panache.withTransaction {
            find("id", entity.id).firstResult().flatMap { existing ->
                if (existing == null) throw IllegalStateException("Account ${entity.id} not found for update")
                existing.accountType = entity.accountType
                existing.partyId = entity.partyId
                existing.productId = entity.productId
                existing.currencyCode = entity.currencyCode
                existing.status = entity.status
                existing.openedAt = entity.openedAt
                existing.closedAt = entity.closedAt
                existing.version = entity.version
                // Savings goal (ADR-0153) round-trips through the same generic update() the
                // freeze/unfreeze/close use cases already call — those pass through a domain
                // object whose goal fields are unchanged from the read, so this is a no-op for
                // them and the only path that actually changes the goal is updateSavingsGoal/
                // clearSavingsGoal in AccountService.
                existing.goalName = entity.goalName
                existing.goalTargetMinorUnits = entity.goalTargetMinorUnits
                existing.goalTargetDate = entity.goalTargetDate
                io.smallrye.mutiny.Uni.createFrom().item(existing)
            }
        }.awaitSuspending().toDomain()
    }

    override suspend fun existsByIban(iban: Iban): Boolean =
        Panache.withSession { count("accountNumber", iban.value) }.awaitSuspending() > 0

    // GDPR Art. 17: nulls legalName AND the goal fields (ADR-0153 — goal_name is
    // customer-authored free text, PII-adjacent like legalName) for every account owned by
    // the erased party.
    override suspend fun anonymizeByPartyId(partyId: UUID): Int = Panache.withTransaction {
        update(
            "legalName = null, goalName = null, goalTargetMinorUnits = null, goalTargetDate = null " +
                "WHERE partyId = ?1",
            partyId,
        )
    }.awaitSuspending()

    private fun AccountEntity.toDomain() = Account(
        id = id,
        accountNumber = Iban.of(accountNumber),
        accountType = AccountType.valueOf(accountType),
        partyId = partyId,
        productId = productId,
        currency = CurrencyCode.of(currencyCode),
        status = AccountStatus.valueOf(status),
        signingRule = SigningRule.valueOf(signingRule),
        openedAt = openedAt,
        closedAt = closedAt,
        version = version,
        sanctionsScreenedAt = sanctionsScreenedAt,
        sanctionsStatus = sanctionsStatus,
        legalName = legalName,
        goalName = goalName,
        goalTargetMinorUnits = goalTargetMinorUnits,
        goalTargetDate = goalTargetDate,
    )

    private fun Account.toEntity() = AccountEntity().also {
        it.id = id
        it.accountNumber = accountNumber.value
        it.accountType = accountType.name
        it.partyId = partyId
        it.productId = productId
        it.currencyCode = currency.code
        it.status = status.name
        it.signingRule = signingRule.name
        it.openedAt = openedAt
        it.closedAt = closedAt
        it.version = version
        it.sanctionsScreenedAt = sanctionsScreenedAt
        it.sanctionsStatus = sanctionsStatus
        it.legalName = legalName
        it.goalName = goalName
        it.goalTargetMinorUnits = goalTargetMinorUnits
        it.goalTargetDate = goalTargetDate
    }
}
