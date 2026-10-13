// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.fund

import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class TestSubscriptionPlans(private val newMode: SubscriptionPlacementMode = SubscriptionPlacementMode.LEGACY) :
    SubscriptionPlanRepository {
    private val plans = ConcurrentHashMap<Pair<UUID, String>, SubscriptionPlan>()

    override suspend fun find(contractId: UUID, idempotencyKey: String): SubscriptionPlan? =
        plans[contractId to idempotencyKey]

    override suspend fun insertIfAbsent(plan: SubscriptionPlan): SubscriptionPlan =
        plans.putIfAbsent(plan.contractId to plan.idempotencyKey, plan.copy(placementMode = newMode))
            ?: plan.copy(placementMode = newMode)
}

fun testElection(strategyCode: String, selectedOn: LocalDate): SubscriptionElection = SubscriptionElection(
    UUID.fromString("00000000-0000-4000-8000-000000000001"),
    1,
    "CZK",
    strategyCode,
    selectedOn,
    Instant.parse("2026-10-01T00:00:00Z"),
    selectedOn,
)
