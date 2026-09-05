// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.infrastructure.persistence.repository

import com.openbank.balance.application.port.out.*
import com.openbank.balance.domain.model.*
import com.openbank.balance.infrastructure.persistence.entity.*
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Tuple
import java.math.BigDecimal
import java.util.UUID

// The balances table has a BIGSERIAL surrogate PK and no UUID business key, so the
// domain id is derived deterministically from the natural key (accountId, currency):
// stable across reads and never null. The previous mapping parsed the surrogate id as
// a UUID, which threw "Invalid UUID string" and surfaced as a 400 on balance writes.
internal fun balanceDomainId(accountId: UUID, currency: String): UUID =
    UUID.nameUUIDFromBytes("$accountId:$currency".toByteArray())

@ApplicationScoped
class BalancePanacheRepo : PanacheRepository<BalanceEntity>

@ApplicationScoped
class HoldPanacheRepo : PanacheRepository<BalanceHoldEntity>

@ApplicationScoped
class BalanceRepositoryImpl(private val repo: BalancePanacheRepo) : BalanceRepository {

    override suspend fun findByAccountIdAndCurrency(accountId: UUID, currency: String): Balance? = Panache.withSession {
        repo.find("accountId = ?1 and currency = ?2", accountId, currency).firstResult()
    }.awaitSuspending()?.toDomain()

    override suspend fun findAllByAccountId(accountId: UUID): List<Balance> = Panache.withSession {
        repo.find("accountId", accountId).list()
    }.awaitSuspending().map { it.toDomain() }

    override suspend fun save(balance: Balance): Balance = Panache.withTransaction {
        val entity = balance.toEntity()
        repo.persist(entity).replaceWith(entity.toDomain())
    }.awaitSuspending()

    override suspend fun update(balance: Balance): Balance = Panache.withTransaction {
        repo.find("accountId = ?1 and currency = ?2", balance.accountId, balance.currency)
            .firstResult()
            .invoke { entity ->
                if (entity != null) {
                    entity.bookedAmount = balance.bookedAmount
                    entity.availableAmount = balance.availableAmount
                    entity.reservedAmount = balance.reservedAmount
                    entity.pendingAmount = balance.pendingAmount
                    entity.arrangedOverdraftLimit = balance.arrangedOverdraftLimit
                    entity.updatedAt = balance.updatedAt
                }
            }
            .map { entity -> entity?.toDomain() ?: throw IllegalStateException("Balance not found for update") }
    }.awaitSuspending()

    override suspend fun sumBookedDeltaAfter(accountId: UUID, currency: String, asOf: java.time.LocalDate): BigDecimal =
        Panache.withSession {
            Panache.getSession().flatMap { session ->
                session.createQuery(
                    "select coalesce(sum(e.delta), 0) from LedgerProjectionEventEntity e " +
                        "where e.accountId = :acct and e.currency = :ccy and e.entryDate > :asOf",
                    BigDecimal::class.java,
                )
                    .setParameter("acct", accountId)
                    .setParameter("ccy", currency)
                    .setParameter("asOf", asOf)
                    .singleResult
            }
        }.awaitSuspending() ?: BigDecimal.ZERO

    // Strictly positive deltas only (`e.delta > 0`), strictly after asOf. Netting the tail would add
    // future-dated debits back into the spendable figure — see Balance.effectiveAvailable (#1745).
    override suspend fun sumNotYetEffectiveCredit(
        accountId: UUID,
        currency: String,
        asOf: java.time.LocalDate,
    ): BigDecimal = Panache.withSession {
        Panache.getSession().flatMap { session ->
            session.createQuery(
                "select coalesce(sum(e.delta), 0) from LedgerProjectionEventEntity e " +
                    "where e.accountId = :acct and e.currency = :ccy and e.entryDate > :asOf and e.delta > 0",
                BigDecimal::class.java,
            )
                .setParameter("acct", accountId)
                .setParameter("ccy", currency)
                .setParameter("asOf", asOf)
                .singleResult
        }
    }.awaitSuspending() ?: BigDecimal.ZERO

    override suspend fun findCreditsMaturingOn(date: java.time.LocalDate): List<AccountCurrency> = Panache.withSession {
        Panache.getSession().flatMap { session ->
            session.createQuery(
                "select distinct e.accountId as acct, e.currency as ccy " +
                    "from LedgerProjectionEventEntity e where e.entryDate = :date and e.delta > 0",
                Tuple::class.java,
            ).setParameter("date", date).resultList
        }
    }.awaitSuspending().map { row ->
        AccountCurrency(row.get("acct", UUID::class.java), row.get("ccy", String::class.java))
    }

    override suspend fun sumBookedByCurrency(): Map<String, BigDecimal> = Panache.withSession {
        Panache.getSession().flatMap { session ->
            session.createQuery(
                "select b.currency as ccy, sum(b.bookedAmount) as total " +
                    "from BalanceEntity b group by b.currency",
                Tuple::class.java,
            ).resultList
        }
    }.awaitSuspending().associate { row ->
        row.get("ccy", String::class.java) to
            (row.get("total", BigDecimal::class.java) ?: BigDecimal.ZERO)
    }

    override suspend fun sumBookedByCurrencyAsOf(asOf: java.time.LocalDate): Map<String, BigDecimal> {
        // Anchor on the MATERIALIZED booked balance (the authoritative customer state), then rewind the
        // future-value-dated tail read from the projection audit — bookedAsOf = current − Σ(delta with
        // entry_date > asOf). This mirrors the ledger trial balance's value-date basis (ADR-0178) AND
        // preserves the integrity coverage of the old aggregate tie-out: the base is `balances`, not the
        // audit, so a write-path bug that desynchronized `balances` from `ledger_projection_event` still
        // surfaces as drift instead of being hidden by summing the audit alone.
        val current = sumBookedByCurrency()
        val futureDated = sumFutureValueDatedByCurrency(asOf)
        return (current.keys + futureDated.keys).associateWith { ccy ->
            (current[ccy] ?: BigDecimal.ZERO).subtract(futureDated[ccy] ?: BigDecimal.ZERO)
        }
    }

    /** Σ of projected booked deltas whose value date is strictly after [asOf], grouped by currency. */
    override suspend fun sumFutureValueDatedByCurrency(asOf: java.time.LocalDate): Map<String, BigDecimal> =
        Panache.withSession {
            Panache.getSession().flatMap { session ->
                session.createQuery(
                    "select e.currency as ccy, coalesce(sum(e.delta), 0) as total " +
                        "from LedgerProjectionEventEntity e where e.entryDate > :asOf group by e.currency",
                    Tuple::class.java,
                ).setParameter("asOf", asOf).resultList
            }
        }.awaitSuspending().associate { row ->
            row.get("ccy", String::class.java) to
                (row.get("total", BigDecimal::class.java) ?: BigDecimal.ZERO)
        }

    private fun BalanceEntity.toDomain() = Balance(
        id = balanceDomainId(accountId, currency),
        accountId = accountId,
        currency = currency,
        bookedAmount = bookedAmount,
        availableAmount = availableAmount,
        reservedAmount = reservedAmount,
        pendingAmount = pendingAmount,
        updatedAt = updatedAt,
        version = version,
        arrangedOverdraftLimit = arrangedOverdraftLimit,
    )

    private fun Balance.toEntity() = BalanceEntity().apply {
        accountId = this@toEntity.accountId
        currency = this@toEntity.currency
        bookedAmount = this@toEntity.bookedAmount
        availableAmount = this@toEntity.availableAmount
        reservedAmount = this@toEntity.reservedAmount
        pendingAmount = this@toEntity.pendingAmount
        arrangedOverdraftLimit = this@toEntity.arrangedOverdraftLimit
        updatedAt = this@toEntity.updatedAt
        version = this@toEntity.version
    }
}

@ApplicationScoped
class HoldRepositoryImpl(
    private val repo: HoldPanacheRepo,
    private val balanceRepo: BalancePanacheRepo,
    private val outboxRepo: BalanceOutboxRepository,
    private val mapper: com.fasterxml.jackson.databind.ObjectMapper,
) : HoldRepository {

    override suspend fun findById(holdId: UUID): BalanceHold? = Panache.withSession {
        repo.find("holdId", holdId).firstResult()
    }.awaitSuspending()?.toDomain()

    override suspend fun findActiveByAccountId(accountId: UUID): List<BalanceHold> = Panache.withSession {
        repo.find("accountId = ?1 and releasedAt is null", accountId).list()
    }.awaitSuspending().map { it.toDomain() }

    override suspend fun findActiveByReferenceId(referenceId: String): List<BalanceHold> = Panache.withSession {
        repo.find("referenceId = ?1 and releasedAt is null", referenceId).list()
    }.awaitSuspending().map { it.toDomain() }

    override suspend fun save(hold: BalanceHold): BalanceHold = Panache.withTransaction {
        val entity = hold.toEntity()
        repo.persist(entity).replaceWith(entity.toDomain())
    }.awaitSuspending()

    override suspend fun update(hold: BalanceHold): BalanceHold = Panache.withTransaction {
        repo.find("holdId", hold.id).firstResult()
            .invoke { entity ->
                if (entity != null) {
                    entity.releasedAt = hold.releasedAt
                }
            }
            .map { entity -> entity?.toDomain() ?: throw IllegalStateException("Hold not found for update") }
    }.awaitSuspending()

    // Transactional outbox (#8510): hold insert + balance reservation + HOLD_PLACED outbox row in
    // ONE transaction. Before it, the use case committed the balance and hold first and then
    // published through a bare emitter — a dual write that could lose the event after the commit
    // or announce a hold whose transaction rolled back.
    override suspend fun saveWithEvent(hold: BalanceHold, balance: Balance, event: BalanceEvent): BalanceHold =
        Panache.withTransaction {
            applyBalance(balance)
                .flatMap { repo.persist(hold.toEntity()) }
                .flatMap { outboxRepo.persistInTransaction(event.toOutboxMessage(mapper)) }
                .replaceWith(hold)
        }.awaitSuspending()

    // Transactional outbox (#8510): hold release + balance + HOLD_RELEASED outbox row, one
    // transaction — the mirror of saveWithEvent.
    override suspend fun releaseWithEvent(hold: BalanceHold, balance: Balance, event: BalanceEvent): BalanceHold =
        Panache.withTransaction {
            applyBalance(balance)
                .flatMap {
                    repo.find("holdId", hold.id).firstResult().invoke { entity ->
                        entity?.releasedAt = hold.releasedAt
                    }
                }
                .flatMap { outboxRepo.persistInTransaction(event.toOutboxMessage(mapper)) }
                .replaceWith(hold)
        }.awaitSuspending()

    private fun applyBalance(balance: Balance): io.smallrye.mutiny.Uni<*> =
        balanceRepo.find("accountId = ?1 and currency = ?2", balance.accountId, balance.currency)
            .firstResult()
            .invoke { entity ->
                if (entity != null) {
                    entity.bookedAmount = balance.bookedAmount
                    entity.availableAmount = balance.availableAmount
                    entity.reservedAmount = balance.reservedAmount
                    entity.pendingAmount = balance.pendingAmount
                    entity.arrangedOverdraftLimit = balance.arrangedOverdraftLimit
                    entity.updatedAt = balance.updatedAt
                }
            }

    private fun BalanceHoldEntity.toDomain() = BalanceHold(
        id = holdId,
        accountId = accountId,
        amount = amount,
        currency = currency,
        reason = reason,
        referenceId = referenceId,
        expiresAt = expiresAt,
        createdAt = createdAt,
        releasedAt = releasedAt,
    )

    private fun BalanceHold.toEntity() = BalanceHoldEntity().apply {
        holdId = this@toEntity.id
        accountId = this@toEntity.accountId
        amount = this@toEntity.amount
        currency = this@toEntity.currency
        reason = this@toEntity.reason
        referenceId = this@toEntity.referenceId
        expiresAt = this@toEntity.expiresAt
        createdAt = this@toEntity.createdAt
        releasedAt = this@toEntity.releasedAt
    }
}
