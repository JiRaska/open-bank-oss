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
import jakarta.persistence.LockModeType
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
    private val holds: HoldPanacheRepo,
) : LedgerProjectionPort {

    @Inject
    constructor(
        dedupRepo: LedgerProjectionEventPanacheRepo,
        balanceRepo: BalancePanacheRepo,
        outboxRepo: BalanceOutboxRepository,
        mapper: ObjectMapper,
        holds: HoldPanacheRepo,
    ) : this(dedupRepo, balanceRepo, outboxRepo, mapper, Clock.systemUTC(), holds)

    // The pocket lock serializes booked movement and cover consumption. The marker, both
    // mutations and every outbox event commit together; a payee event cannot release payer cover.
    override suspend fun applyBookedDelta(
        journalEntryId: UUID,
        accountId: UUID,
        currency: String,
        delta: BigDecimal,
        transactionId: UUID,
        entryDate: LocalDate,
        actorId: String,
    ): Balance? = Panache.withTransaction {
        val change = Projection(accountId, currency, transactionId, actorId)
        lockedPocket(change).flatMap { pocket ->
            dedupRepo.findById(LedgerProjectionEventId(journalEntryId, accountId, currency)).flatMap { existing ->
                val firstApplication = existing == null
                val marker = if (firstApplication) {
                    pocket.bookedAmount += delta
                    pocket.availableAmount += delta
                    pocket.updatedAt = OffsetDateTime.now(clock)
                    dedupRepo.persist(
                        LedgerProjectionEventEntity().apply {
                            this.journalEntryId = journalEntryId
                            this.accountId = accountId
                            this.currency = currency
                            this.delta = delta
                            this.transactionId = transactionId
                            this.entryDate = entryDate
                            this.appliedAt = OffsetDateTime.now(clock)
                        },
                    ).replaceWithVoid()
                } else {
                    Uni.createFrom().voidItem()
                }
                marker.flatMap { consumeCover(pocket, change) }.flatMap { releaseEvents ->
                    val events = if (firstApplication) {
                        listOf(event(pocket, change, BalanceEventType.BALANCE_UPDATED, delta)) + releaseEvents
                    } else {
                        releaseEvents
                    }
                    persistEvents(events).map { if (firstApplication) pocket.toDomain() else null }
                }
            }
        }
    }.awaitSuspending()

    private data class Projection(
        val accountId: UUID,
        val currency: String,
        val transactionId: UUID,
        val actorId: String,
    )

    private fun lockedPocket(change: Projection): Uni<BalanceEntity> =
        balanceRepo.find("accountId = ?1 and currency = ?2", change.accountId, change.currency)
            .withLock(LockModeType.PESSIMISTIC_WRITE).firstResult().flatMap { existing ->
                if (existing != null) {
                    Uni.createFrom().item(existing)
                } else {
                    val pocket = BalanceEntity().apply {
                        accountId = change.accountId
                        currency = change.currency
                        bookedAmount = BigDecimal.ZERO
                        availableAmount = BigDecimal.ZERO
                        reservedAmount = BigDecimal.ZERO
                        pendingAmount = BigDecimal.ZERO
                        updatedAt = OffsetDateTime.now(clock)
                    }
                    balanceRepo.persist(pocket).map { pocket }
                }
            }

    private fun consumeCover(pocket: BalanceEntity, change: Projection): Uni<List<BalanceEvent>> = holds.find(
        "accountId = ?1 and currency = ?2 and referenceId = ?3 and releasedAt is null",
        change.accountId,
        change.currency,
        change.transactionId.toString(),
    ).withLock(LockModeType.PESSIMISTIC_WRITE).list().map { matching ->
        val amount = matching.fold(BigDecimal.ZERO) { sum, hold -> sum + hold.amount }
        check(amount <= pocket.reservedAmount) { "Cover exceeds the pocket's reserved amount" }
        pocket.reservedAmount -= amount
        pocket.availableAmount += amount
        if (matching.isNotEmpty()) pocket.updatedAt = OffsetDateTime.now(clock)
        matching.map { hold ->
            hold.releasedAt = OffsetDateTime.now(clock)
            event(pocket, change, BalanceEventType.HOLD_RELEASED, hold.amount)
        }
    }

    private fun persistEvents(events: List<BalanceEvent>): Uni<Void> =
        events.fold(Uni.createFrom().voidItem()) { pending, event ->
            pending.flatMap { outboxRepo.persistInTransaction(event.toOutboxMessage(mapper)) }
        }

    private fun event(pocket: BalanceEntity, change: Projection, type: BalanceEventType, amount: BigDecimal) =
        BalanceEvent(
            eventId = Ids.newId(),
            eventType = type,
            accountId = change.accountId,
            currency = change.currency,
            amount = amount,
            bookedAmount = pocket.bookedAmount,
            availableAmount = pocket.availableAmount,
            reservedAmount = pocket.reservedAmount,
            occurredAt = OffsetDateTime.now(clock),
            actorId = change.actorId,
            actorType = EventActor.TYPE_SYSTEM,
            sourceService = "balance-service",
        )

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
