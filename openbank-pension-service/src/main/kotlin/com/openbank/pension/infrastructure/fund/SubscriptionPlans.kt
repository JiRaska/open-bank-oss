// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.fund

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.pension.application.port.out.ContributionOrigin
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.mutiny.sqlclient.Pool
import io.vertx.mutiny.sqlclient.Tuple
import jakarta.inject.Singleton
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Fixed before the first external order, so a retry after an election change sends the same legs. */
data class SubscriptionLeg(val fundId: UUID, val amount: BigDecimal, val orderKey: String? = null)

/** The contract election actually selected when the first placement was prepared. */
data class SubscriptionElection(
    val providerEntityId: UUID,
    val packVersion: Int,
    val contractCurrency: String,
    val strategyCode: String,
    val effectiveFrom: LocalDate,
    val electedAt: Instant,
    val selectedOn: LocalDate,
)

/** Evidence of the exact rule and rounding decision, persisted before contacting the register. */
data class SubscriptionDecision(
    val origin: ContributionOrigin?,
    val providerEntityId: UUID,
    val packVersion: Int,
    val selectedOn: LocalDate,
    val electionEffectiveFrom: LocalDate,
    val electionRecordedAt: Instant,
    val strategyCode: String,
    val strategyId: UUID,
    val strategyVersion: Int,
    val sourceCurrency: String,
    val targetCurrency: String,
    val roundingMode: String,
    val roundingResidual: BigDecimal,
)

enum class SubscriptionPlacementMode { LEGACY, BATCH }

data class SubscriptionPlan(
    val contractId: UUID,
    val idempotencyKey: String,
    val amount: BigDecimal,
    val currency: String,
    val legs: List<SubscriptionLeg>,
    val placementMode: SubscriptionPlacementMode = SubscriptionPlacementMode.LEGACY,
    val decision: SubscriptionDecision? = null,
) {
    fun requireSameInstruction(
        amount: BigDecimal,
        currency: String,
        origin: ContributionOrigin? = null,
    ): SubscriptionPlan {
        check(this.amount.compareTo(amount) == 0 && this.currency == currency) {
            "Idempotency-Key reused for a different subscription"
        }
        check(decision == null || decision.origin == origin) {
            "Idempotency-Key reused for a different contribution"
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
        """SELECT contract_id, idempotency_key, amount, currency, legs::text AS legs, placement_mode,
                  decision_snapshot::text AS decision_snapshot
           FROM pension_subscription_plans WHERE contract_id = $1 AND idempotency_key = $2""",
    ).execute(Tuple.of(contractId, idempotencyKey)).awaitSuspending().firstOrNull()?.let { row ->
        SubscriptionPlan(
            row.getUUID("contract_id"),
            row.getString("idempotency_key"),
            row.getBigDecimal("amount"),
            row.getString("currency").trim(),
            mapper.readValue(row.getString("legs")),
            SubscriptionPlacementMode.valueOf(row.getString("placement_mode")),
            row.getString("decision_snapshot")?.let { mapper.readValue(it) },
        )
    }

    override suspend fun insertIfAbsent(plan: SubscriptionPlan): SubscriptionPlan {
        pool.preparedQuery(
            """INSERT INTO pension_subscription_plans
               (contract_id, idempotency_key, amount, currency, legs, placement_mode, decision_snapshot)
               VALUES ($1, $2, $3, $4, $5::text::jsonb, $6, $7::text::jsonb)
               ON CONFLICT (contract_id, idempotency_key) DO NOTHING""",
        ).execute(
            Tuple.tuple(
                listOf(
                    plan.contractId,
                    plan.idempotencyKey,
                    plan.amount,
                    plan.currency,
                    mapper.writeValueAsString(plan.legs),
                    plan.placementMode.name,
                    plan.decision?.let(mapper::writeValueAsString),
                ),
            ),
        ).awaitSuspending()
        return checkNotNull(find(plan.contractId, plan.idempotencyKey)) {
            "subscription plan was not visible after insert"
        }
    }
}
