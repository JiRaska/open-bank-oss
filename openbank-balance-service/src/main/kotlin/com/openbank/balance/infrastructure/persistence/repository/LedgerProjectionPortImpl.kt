// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.infrastructure.persistence.repository

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.balance.application.port.out.BalanceOutboxRepository
import com.openbank.balance.application.port.out.LedgerProjectionPort
import com.openbank.balance.domain.model.Balance
import com.openbank.balance.domain.model.BalanceEvent
import com.openbank.balance.domain.model.BalanceEventType
import com.openbank.balance.infrastructure.persistence.entity.BalanceEntity
import com.openbank.balance.infrastructure.persistence.entity.LedgerProjectionEventEntity
import com.openbank.balance.infrastructure.persistence.entity.LedgerProjectionEventId
import com.openbank.libs.domain.event.EventActor
import com.openbank.libs.domain.identifiers.Ids
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepositoryBase
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
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
    private val outboxRepo: BalanceOutboxRepository,
    private val mapper: ObjectMapper,
    private val clock: Clock,
) : LedgerProjectionPort {

    @Inject
    constructor(
        dedupRepo: LedgerProjectionEventPanacheRepo,
        balanceRepo: BalancePanacheRepo,
        outboxRepo: BalanceOutboxRepository,
        mapper: ObjectMapper,
    ) : this(dedupRepo, balanceRepo, outboxRepo, mapper, Clock.systemUTC())

    // Dedup-marker write, balance mutation AND the BALANCE_UPDATED outbox row share ONE
    // transaction: either all land or none does (#8510 — before it, the event went out through a
    // bare emitter after this transaction committed, so a crash between the commit and the emit
    // lost the event with no record, and an emit before a rollback announced a movement that never
    // happened). A redelivery then sees the marker and is skipped (returns null) — booked is
    // applied exactly once and announced exactly once.
    override suspend fun applyBookedDelta(
        journalEntryId: UUID,
        accountId: UUID,
        currency: String,
        delta: BigDecimal,
        transactionId: UUID,
        entryDate: LocalDate,
        actorId: String,
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
                        .flatMap { applied ->
                            val event = BalanceEvent(
                                // UUIDv7 (ADR-0106): a durable, index-ordered event id.
                                eventId = Ids.newId(),
                                eventType = BalanceEventType.BALANCE_UPDATED,
                                accountId = accountId,
                                currency = currency,
                                amount = delta,
                                bookedAmount = applied.bookedAmount,
                                availableAmount = applied.availableAmount,
                                reservedAmount = applied.reservedAmount,
                                occurredAt = OffsetDateTime.now(clock),
                                actorId = actorId,
                                actorType = EventActor.TYPE_SYSTEM,
                                sourceService = "balance-service",
                            )
                            outboxRepo.persistInTransaction(event.toOutboxMessage(mapper))
                                .map { applied }
                        }
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
