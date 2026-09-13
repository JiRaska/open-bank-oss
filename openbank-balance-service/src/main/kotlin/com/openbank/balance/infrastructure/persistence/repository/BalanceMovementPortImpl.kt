// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.infrastructure.persistence.repository

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.balance.application.port.out.BalanceMovementPort
import com.openbank.balance.application.port.out.BalanceOutboxRepository
import com.openbank.balance.application.port.out.MovementOutcome
import com.openbank.balance.application.usecase.BalanceNotFoundException
import com.openbank.balance.domain.model.Balance
import com.openbank.balance.domain.model.BalanceEvent
import com.openbank.balance.domain.model.BalanceEventType
import com.openbank.balance.infrastructure.persistence.entity.BalanceEntity
import com.openbank.balance.infrastructure.persistence.entity.BalanceMovementEntity
import com.openbank.balance.infrastructure.persistence.entity.BalanceMovementId
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
import java.time.OffsetDateTime
import java.util.UUID

@ApplicationScoped
class BalanceMovementPanacheRepo : PanacheRepositoryBase<BalanceMovementEntity, BalanceMovementId>

@ApplicationScoped
class BalanceMovementPortImpl(
    private val dedupRepo: BalanceMovementPanacheRepo,
    private val balanceRepo: BalancePanacheRepo,
    private val outboxRepo: BalanceOutboxRepository,
    private val mapper: ObjectMapper,
    private val clock: Clock,
) : BalanceMovementPort {

    @Inject
    constructor(
        dedupRepo: BalanceMovementPanacheRepo,
        balanceRepo: BalancePanacheRepo,
        outboxRepo: BalanceOutboxRepository,
        mapper: ObjectMapper,
    ) : this(dedupRepo, balanceRepo, outboxRepo, mapper, Clock.systemUTC())

    override suspend fun applyCredit(
        accountId: UUID,
        currency: String,
        referenceId: String,
        amount: BigDecimal,
        actorId: String,
    ): MovementOutcome = apply(accountId, currency, referenceId, "CREDIT", amount, actorId) { it.applyCredit(amount) }

    override suspend fun applyDebit(
        accountId: UUID,
        currency: String,
        referenceId: String,
        amount: BigDecimal,
        actorId: String,
    ): MovementOutcome = apply(accountId, currency, referenceId, "DEBIT", amount, actorId) { it.applyDebit(amount) }

    // Dedup-marker check, balance mutation, marker write AND the BALANCE_UPDATED outbox row share
    // ONE transaction: either all land or none do (#8510 — before it, the event went out through a
    // bare emitter AFTER this transaction committed, a dual write that could lose the event or
    // announce a movement that rolled back). A redelivery that finds the marker present returns the
    // current balance with applied=false and mutates nothing — booked/available move exactly once
    // and no second event is written. [mutate] applies the domain rule (applyCredit/applyDebit); if
    // applyDebit's overdraft guard throws, the lambda failure rolls the transaction back (no marker,
    // no movement, no event) and propagates to the caller.
    @Suppress("LongParameterList")
    private suspend fun apply(
        accountId: UUID,
        currency: String,
        referenceId: String,
        operation: String,
        delta: BigDecimal,
        actorId: String,
        mutate: (Balance) -> Balance,
    ): MovementOutcome = Panache.withTransaction {
        dedupRepo.findById(BalanceMovementId(accountId, currency, referenceId, operation))
            .flatMap { existing ->
                balanceRepo.find("accountId = ?1 and currency = ?2", accountId, currency).firstResult()
                    .flatMap { entity ->
                        when {
                            entity == null ->
                                Uni.createFrom().failure(
                                    BalanceNotFoundException(
                                        "Balance not found for account=$accountId currency=$currency",
                                    ),
                                )
                            existing != null ->
                                // Duplicate movement — nothing applied, no event; report the balance.
                                Uni.createFrom().item(MovementOutcome(entity.toDomain(), applied = false))
                            else -> {
                                val updated = mutate(entity.toDomain())
                                entity.bookedAmount = updated.bookedAmount
                                entity.availableAmount = updated.availableAmount
                                entity.updatedAt = OffsetDateTime.now(clock)
                                val marker = BalanceMovementEntity().apply {
                                    this.accountId = accountId
                                    this.currency = currency
                                    this.referenceId = referenceId
                                    this.operation = operation
                                    this.delta = delta
                                    this.appliedAt = OffsetDateTime.now(clock)
                                }
                                val event = BalanceEvent(
                                    // UUIDv7 (ADR-0106): a durable, index-ordered event id.
                                    eventId = Ids.newId(),
                                    eventType = BalanceEventType.BALANCE_UPDATED,
                                    accountId = accountId,
                                    currency = currency,
                                    // The debit event carried the NEGATED amount on the wire before
                                    // #8510; keep the shape so no consumer sees a difference.
                                    amount = if (operation == "DEBIT") delta.negate() else delta,
                                    bookedAmount = updated.bookedAmount,
                                    availableAmount = updated.availableAmount,
                                    reservedAmount = updated.reservedAmount,
                                    occurredAt = OffsetDateTime.now(clock),
                                    actorId = actorId,
                                    actorType = EventActor.TYPE_SYSTEM,
                                    sourceService = "balance-service",
                                )
                                dedupRepo.persist(marker)
                                    .flatMap {
                                        outboxRepo.persistInTransaction(event.toOutboxMessage(mapper))
                                    }
                                    .map { MovementOutcome(entity.toDomain(), applied = true) }
                            }
                        }
                    }
            }
    }.awaitSuspending()

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
