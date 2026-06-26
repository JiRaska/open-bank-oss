// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.balance.infrastructure.persistence.repository

import com.openbank.balance.application.port.out.LedgerProjectionPort
import com.openbank.balance.domain.model.Balance
import com.openbank.balance.infrastructure.persistence.entity.BalanceEntity
import com.openbank.balance.infrastructure.persistence.entity.LedgerProjectionEventEntity
import com.openbank.balance.infrastructure.persistence.entity.LedgerProjectionEventId
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepositoryBase
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

@ApplicationScoped
class LedgerProjectionEventPanacheRepo : PanacheRepositoryBase<LedgerProjectionEventEntity, LedgerProjectionEventId>

@ApplicationScoped
class LedgerProjectionPortImpl(
    private val dedupRepo: LedgerProjectionEventPanacheRepo,
    private val balanceRepo: BalancePanacheRepo,
    private val clock: Clock,
) : LedgerProjectionPort {

    // Dedup-marker write and balance mutation share ONE transaction: either both land or neither
    // does, so a crash can never leave a marker without its balance movement (or vice versa). A
    // redelivery then sees the marker and is skipped (returns null) — booked is applied exactly once.
    override suspend fun applyBookedDelta(
        journalEntryId: UUID,
        accountId: UUID,
        currency: String,
        delta: BigDecimal,
        transactionId: UUID,
        entryDate: LocalDate,
    ): Balance? = Panache.withTransaction {
        dedupRepo.findById(LedgerProjectionEventId(journalEntryId, accountId, currency))
            .flatMap { existing ->
                if (existing != null) {
                    Uni.createFrom().nullItem<Balance>()
                } else {
                    val marker = LedgerProjectionEventEntity().apply {
                        this.journalEntryId = journalEntryId
                        this.accountId = accountId
                        this.currency = currency
                        this.delta = delta
                        this.transactionId = transactionId
                        this.entryDate = entryDate
                        this.appliedAt = OffsetDateTime.now(clock)
                    }
                    dedupRepo.persist(marker)
                        .flatMap { applyToBalance(accountId, currency, delta) }
                }
            }
    }.awaitSuspending()

    private fun applyToBalance(accountId: UUID, currency: String, delta: BigDecimal): Uni<Balance> =
        balanceRepo.find("accountId = ?1 and currency = ?2", accountId, currency).firstResult()
            .flatMap { existing ->
                if (existing == null) {
                    // First movement the read-model has seen for this pocket: open it at the delta.
                    val entity = BalanceEntity().apply {
                        this.accountId = accountId
                        this.currency = currency
                        bookedAmount = delta
                        availableAmount = delta
                        reservedAmount = BigDecimal.ZERO
                        pendingAmount = BigDecimal.ZERO
                        arrangedOverdraftLimit = BigDecimal.ZERO
                        updatedAt = OffsetDateTime.now(clock)
                    }
                    balanceRepo.persist(entity).replaceWith(Uni.createFrom().item { entity.toDomain() })
                } else {
                    existing.bookedAmount = existing.bookedAmount + delta
                    existing.availableAmount = existing.availableAmount + delta
                    existing.updatedAt = OffsetDateTime.now(clock)
                    Uni.createFrom().item(existing.toDomain())
                }
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
}
