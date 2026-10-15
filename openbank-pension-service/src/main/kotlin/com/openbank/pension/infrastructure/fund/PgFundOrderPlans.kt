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

@Singleton
class PgFundOrderPlans(private val client: Pool, private val mapper: ObjectMapper) : FundOrderPlans {
    override suspend fun find(operationKey: String): FundOrderPlan? = client.preparedQuery(
        "SELECT contract_id, operation_kind, amount, currency, legs::text AS legs FROM pension_fund_order_plans WHERE operation_key = $1",
    ).execute(Tuple.of(operationKey)).awaitSuspending().firstOrNull()?.let { row ->
        FundOrderPlan(
            operationKey,
            row.getUUID("contract_id"),
            row.getString("operation_kind"),
            row.getBigDecimal("amount"),
            row.getString("currency"),
            mapper.readValue(row.getString("legs")),
        )
    }

    override suspend fun saveIfAbsent(plan: FundOrderPlan): FundOrderPlan {
        client.preparedQuery(
            "INSERT INTO pension_fund_order_plans " +
                "(operation_key, contract_id, operation_kind, amount, currency, legs) " +
                "VALUES ($1, $2, $3, $4, $5, $6::text::jsonb) ON CONFLICT (operation_key) DO NOTHING",
        ).execute(
            Tuple.tuple(
                listOf(
                    plan.operationKey,
                    plan.contractId,
                    plan.kind,
                    plan.amount,
                    plan.currency,
                    mapper.writeValueAsString(plan.legs),
                ),
            ),
        ).awaitSuspending()
        return requireNotNull(find(plan.operationKey)) { "persisted fund order plan is missing" }
    }
}
