// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.persistence.repository

import com.openbank.account.application.port.out.AccountRepository
import com.openbank.account.application.usecase.AccountUpdateConflictException
import com.openbank.account.domain.model.Account
import com.openbank.account.domain.model.AccountStatus
import com.openbank.account.domain.model.AccountType
import com.openbank.account.domain.model.CurrencyPocket
import com.openbank.account.domain.model.SigningRule
import com.openbank.account.infrastructure.persistence.entity.AccountEntity
import com.openbank.account.infrastructure.persistence.entity.AccountIdempotencyEntity
import com.openbank.libs.domain.account.Iban
import com.openbank.libs.domain.money.CurrencyCode
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepositoryBase
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class AccountIdempotencyRepository(private val clock: Clock) :
    PanacheRepositoryBase<AccountIdempotencyEntity, String> {
    fun persistInTransaction(key: String, accountId: UUID): io.smallrye.mutiny.Uni<Void> {
        val e = AccountIdempotencyEntity().also {
            it.idempotencyKey = key
            it.accountId = accountId
            it.createdAt = Instant.now(clock)
        }
        return persist(e).replaceWithVoid()
    }
}

@ApplicationScoped
@Suppress("TooManyFunctions") // 1:1 impl of AccountRepository — see the port's own suppression rationale
class AccountRepositoryImpl(
    // internal, not private: the file-scope versionMatchedUpdate extension below (#465,
    // outside the class body) stamps updatedAt from this clock — Kotlin's `private` on a
    // class member is invisible even to same-file top-level declarations.
    internal val clock: Clock,
    private val idempotencyRepository: AccountIdempotencyRepository,
    private val pocketRepository: CurrencyPocketRepositoryImpl,
) : AccountRepository,
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

    override suspend fun findActive(limit: Int, afterId: UUID?): List<Account> = Panache.withSession {
        // Keyset pagination on the primary key, same stability contract as findByPartyId /
        // searchByIban — a sweep that pages while accounts open/close never skips or repeats
        // a row it has already passed (ADR-0143 billing discovery).
        val query = if (afterId != null) {
            find("status = ?1 AND id > ?2 ORDER BY id", AccountStatus.ACTIVE.name, afterId)
        } else {
            find("status = ?1 ORDER BY id", AccountStatus.ACTIVE.name)
        }
        query.page(0, limit).list()
    }.awaitSuspending().map { it.toDomain() }

    override suspend fun save(account: Account): Account {
        val entity = account.toEntity()
        // Audit timestamps come from the injected Clock here, not the entity (ADR-0100): the
        // column DEFAULT never applies because Hibernate writes every insertable column.
        val now = clock.instant()
        entity.createdAt = now
        entity.updatedAt = now
        return Panache.withTransaction { persist(entity).replaceWith(entity) }.awaitSuspending().toDomain()
    }

    override suspend fun saveNewAccount(
        account: Account,
        primaryPocket: CurrencyPocket,
        idempotencyKey: String,
    ): Account {
        val entity = account.toEntity()
        // Audit timestamps come from the injected Clock here, same as save() (ADR-0100 / #540):
        // the column DEFAULT never applies because Hibernate writes every insertable column.
        val now = clock.instant()
        entity.createdAt = now
        entity.updatedAt = now
        // One transaction for account + primary pocket + idempotency key (#465): previously the
        // three writes were separate transactions, so a crash could leave an account without a
        // pocket, and two concurrent opens with one Idempotency-Key both committed (the Redis
        // record in the REST layer is a check-then-act cache). The PK on account_idempotency
        // makes the concurrent loser's whole transaction roll back; the use case recovers it.
        return Panache.withTransaction {
            persist(entity)
                .flatMap { pocketRepository.persistInTransaction(primaryPocket) }
                .flatMap { idempotencyRepository.persistInTransaction(idempotencyKey, entity.id) }
                .replaceWith(entity)
        }.awaitSuspending().toDomain()
    }

    override suspend fun findByIdempotencyKey(idempotencyKey: String): Account? {
        val accountId = Panache.withSession {
            idempotencyRepository.find("idempotencyKey", idempotencyKey).firstResult()
        }.awaitSuspending()?.accountId ?: return null
        return findById(accountId)
    }

    override suspend fun update(account: Account): Account {
        val entity = account.toEntity()
        return try {
            versionMatchedUpdate(entity).awaitSuspending().toDomain()
        } catch (e: jakarta.persistence.OptimisticLockException) {
            // Truly simultaneous writers both pass the version-matched read (in
            // versionMatchedUpdate) before either commits; the loser's flush then fails
            // Hibernate's @Version check. Same conflict, same 409 (#465).
            throw AccountUpdateConflictException(
                "Account ${entity.id} was modified concurrently (flush-time version check)",
                e,
            )
        } catch (e: org.hibernate.StaleObjectStateException) {
            throw AccountUpdateConflictException(
                "Account ${entity.id} was modified concurrently (flush-time version check)",
                e,
            )
        }
    }

    override suspend fun existsByIban(iban: Iban): Boolean =
        Panache.withSession { count("accountNumber", iban.value) }.awaitSuspending() > 0

    // GDPR Art. 17: nulls legalName, the goal fields (ADR-0153 — goal_name is customer-authored
    // free text, PII-adjacent like legalName) AND nickname (same reasoning: free text the
    // customer chose, which can carry a name) for every account owned by the erased party.
    override suspend fun anonymizeByPartyId(partyId: UUID): Int = Panache.withTransaction {
        update(
            "legalName = null, goalName = null, goalTargetMinorUnits = null, goalTargetDate = null, " +
                "nickname = null, updatedAt = ?2 WHERE partyId = ?1",
            partyId,
            clock.instant(),
        )
    }.awaitSuspending()
}

// File-scope extension (pure Uni assembly for update() above): keeps AccountRepositoryImpl
// within detekt's per-class function budget, same pattern as the entity<->domain mappers below.
private fun AccountRepositoryImpl.versionMatchedUpdate(entity: AccountEntity) = Panache.withTransaction {
    // Optimistic guard (#465): match on the version the caller's domain object was READ
    // at, not just the id. The previous re-read-and-copy silently applied stale domain
    // state over a row another transaction had just changed (Hibernate's @Version check
    // passed because the re-read was fresh) — e.g. a freeze racing a close resurrected
    // the CLOSED account as FROZEN. 0 rows here = concurrent modification -> 409.
    find("id = ?1 and version = ?2", entity.id, entity.version).firstResult().flatMap { existing ->
        if (existing == null) {
            throw AccountUpdateConflictException(
                "Account ${entity.id} was modified concurrently (expected version ${entity.version})",
            )
        }
        existing.accountType = entity.accountType
        existing.partyId = entity.partyId
        existing.productId = entity.productId
        existing.currencyCode = entity.currencyCode
        existing.status = entity.status
        existing.openedAt = entity.openedAt
        existing.closedAt = entity.closedAt
        // Savings goal (ADR-0153) round-trips through the same generic update() the
        // freeze/unfreeze/close use cases already call — those pass through a domain
        // object whose goal fields are unchanged from the read, so this is a no-op for
        // them and the only path that actually changes the goal is updateSavingsGoal/
        // clearSavingsGoal in AccountService.
        existing.goalName = entity.goalName
        existing.goalTargetMinorUnits = entity.goalTargetMinorUnits
        existing.goalTargetDate = entity.goalTargetDate
        // Same round-trip-through-update() story as the goal fields above: only
        // renameAccount in AccountService actually changes this.
        existing.nickname = entity.nickname
        // Audit timestamp comes from the injected Clock, not the entity default (ADR-0100 /
        // #540): the @PreUpdate callback's EPOCH default is never overwritten by the DB
        // DEFAULT since Hibernate writes every insertable/updatable column explicitly.
        existing.updatedAt = clock.instant()
        io.smallrye.mutiny.Uni.createFrom().item(existing)
    }
}

// Entity<->domain mappers kept at file scope (pure functions) so AccountRepositoryImpl stays
// within detekt's per-class function budget — same pattern as the ledger repository helpers.
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
    nickname = nickname,
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
    it.nickname = nickname
}
