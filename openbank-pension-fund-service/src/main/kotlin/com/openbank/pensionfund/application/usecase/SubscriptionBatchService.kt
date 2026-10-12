// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.application.usecase

import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal
import java.util.UUID

data class SubscriptionBatchLeg(val fundId: UUID, val amount: BigDecimal, val idempotencyKey: String)

data class SubscriptionBatchOrder(val id: UUID, val fundId: UUID, val amount: BigDecimal)

interface SubscriptionBatchStore {
    /** Accept all new legs in one transaction, or accept none. Existing batches replay verbatim. */
    suspend fun place(
        contractId: UUID,
        batchKey: String,
        amount: BigDecimal,
        currency: String,
        legs: List<SubscriptionBatchLeg>,
    ): List<SubscriptionBatchOrder>
}

@ApplicationScoped
class SubscriptionBatchService(private val store: SubscriptionBatchStore) {
    private companion object {
        const val MAX_BATCH_KEY = 512
        const val MAX_LEGS = 32
        const val MAX_LEG_KEY = 128
    }

    suspend fun place(
        contractId: UUID,
        batchKey: String,
        amount: BigDecimal,
        currency: String,
        legs: List<SubscriptionBatchLeg>,
    ): List<SubscriptionBatchOrder> {
        require(batchKey.length in 1..MAX_BATCH_KEY) { "batch Idempotency-Key must be 1..512 characters" }
        require(currency.matches(Regex("[A-Z]{3}"))) { "currency must be an ISO 4217 code" }
        require(amount.signum() > 0 && amount.scale() <= 2) { "amount must be positive with at most two decimals" }
        require(legs.isNotEmpty() && legs.size <= MAX_LEGS) { "batch needs 1..32 legs" }
        require(legs.map { it.fundId }.distinct().size == legs.size) { "duplicate fund in batch" }
        require(legs.map { it.idempotencyKey }.distinct().size == legs.size) { "duplicate leg Idempotency-Key" }
        legs.forEach {
            require(it.amount.signum() > 0 && it.amount.scale() <= 2) {
                "leg amount must be positive with at most two decimals"
            }
            require(it.idempotencyKey.length in 1..MAX_LEG_KEY) { "leg Idempotency-Key must be 1..128 characters" }
        }
        check(legs.fold(BigDecimal.ZERO) { sum, leg -> sum + leg.amount }.compareTo(amount) == 0) {
            "subscription legs do not sum to the batch amount"
        }
        return store.place(contractId, batchKey, amount, currency, legs)
    }
}
