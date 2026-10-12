// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.fund

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.mutiny.sqlclient.Pool
import io.vertx.mutiny.sqlclient.Tuple
import jakarta.inject.Singleton
import java.math.BigDecimal
import java.util.UUID

/** Fixed before the first external order, so a retry after an election change sends the same legs. */
data class SubscriptionLeg(val fundId: UUID, val amount: BigDecimal)

enum class SubscriptionPlacementMode { LEGACY, BATCH }

data class SubscriptionPlan(
    val contractId: UUID,
    val idempotencyKey: String,
    val amount: BigDecimal,
    val currency: String,
    val legs: List<SubscriptionLeg>,
    val placementMode: SubscriptionPlacementMode = SubscriptionPlacementMode.LEGACY,
) {
    fun requireSameInstruction(amount: BigDecimal, currency: String): SubscriptionPlan {
        check(this.amount.compareTo(amount) == 0 && this.currency == currency) {
            "Idempotency-Key reused for a different subscription"
        }
        return this
    }
}

interface SubscriptionPlanRepository {
    suspend fun find(contractId: UUID, idempotencyKey: String): SubscriptionPlan?

    /** Unique (contract,key) insert wins across instances; return the committed winner. */
    suspend fun insertIfAbsent(plan: SubscriptionPlan): SubscriptionPlan
}

@Singleton
class PgSubscriptionPlans(private val pool: Pool, private val mapper: ObjectMapper) : SubscriptionPlanRepository {
    override suspend fun find(contractId: UUID, idempotencyKey: String): SubscriptionPlan? = pool.preparedQuery(
        """SELECT contract_id, idempotency_key, amount, currency, legs::text AS legs, placement_mode
           FROM pension_subscription_plans WHERE contract_id = $1 AND idempotency_key = $2""",
    ).execute(Tuple.of(contractId, idempotencyKey)).awaitSuspending().firstOrNull()?.let { row ->
        SubscriptionPlan(
            row.getUUID("contract_id"),
            row.getString("idempotency_key"),
            row.getBigDecimal("amount"),
            row.getString("currency").trim(),
            mapper.readValue(row.getString("legs")),
            SubscriptionPlacementMode.valueOf(row.getString("placement_mode")),
        )
    }

    override suspend fun insertIfAbsent(plan: SubscriptionPlan): SubscriptionPlan {
        pool.preparedQuery(
            """INSERT INTO pension_subscription_plans
               (contract_id, idempotency_key, amount, currency, legs, placement_mode)
               VALUES ($1, $2, $3, $4, $5::text::jsonb, $6) ON CONFLICT (contract_id, idempotency_key) DO NOTHING""",
        ).execute(
            Tuple.of(
                plan.contractId,
                plan.idempotencyKey,
                plan.amount,
                plan.currency,
                mapper.writeValueAsString(plan.legs),
                plan.placementMode.name,
            ),
        ).awaitSuspending()
        return checkNotNull(find(plan.contractId, plan.idempotencyKey)) {
            "subscription plan was not visible after insert"
        }
    }
}
