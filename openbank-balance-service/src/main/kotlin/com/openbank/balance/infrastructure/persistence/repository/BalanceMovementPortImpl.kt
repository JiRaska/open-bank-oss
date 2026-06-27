// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.infrastructure.persistence.repository

import com.openbank.balance.application.port.out.BalanceMovementPort
import com.openbank.balance.application.port.out.MovementOutcome
import com.openbank.balance.application.usecase.BalanceNotFoundException
import com.openbank.balance.domain.model.Balance
import com.openbank.balance.infrastructure.persistence.entity.BalanceEntity
import com.openbank.balance.infrastructure.persistence.entity.BalanceMovementEntity
import com.openbank.balance.infrastructure.persistence.entity.BalanceMovementId
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
    private val clock: Clock,
) : BalanceMovementPort {

    @Inject
    constructor(
        dedupRepo: BalanceMovementPanacheRepo,
        balanceRepo: BalancePanacheRepo,
    ) : this(dedupRepo, balanceRepo, Clock.systemUTC())

    override suspend fun applyCredit(
        accountId: UUID,
        currency: String,
        referenceId: String,
        amount: BigDecimal,
    ): MovementOutcome = apply(accountId, currency, referenceId, "CREDIT", amount) { it.applyCredit(amount) }

    override suspend fun applyDebit(
        accountId: UUID,
        currency: String,
        referenceId: String,
        amount: BigDecimal,
    ): MovementOutcome = apply(accountId, currency, referenceId, "DEBIT", amount) { it.applyDebit(amount) }

    // Dedup-marker check, balance mutation, and marker write share ONE transaction: either all land or
    // none do. A redelivery that finds the marker present returns the current balance with applied=false
    // and mutates nothing — booked/available move exactly once. [mutate] applies the domain rule
    // (applyCredit/applyDebit); if applyDebit's overdraft guard throws, the lambda failure rolls the
    // transaction back (no marker, no movement) and propagates to the caller.
    @Suppress("LongParameterList")
    private suspend fun apply(
        accountId: UUID,
        currency: String,
        referenceId: String,
        operation: String,
        delta: BigDecimal,
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
                                // Duplicate movement — nothing applied; report the current balance.
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
                                dedupRepo.persist(marker)
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
