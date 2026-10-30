// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.fund

import java.math.BigDecimal
import java.util.UUID

/** Immutable instructions saved before any leg is sent to the register. */
data class FundOrderPlan(
    val operationKey: String,
    val contractId: UUID,
    val kind: String,
    val amount: BigDecimal,
    val currency: String,
    val legs: List<OrderRequestDto>,
) {
    fun requireRequest(contractId: UUID, kind: String, amount: BigDecimal, currency: String) {
        check(
            this.contractId == contractId &&
                this.kind == kind &&
                this.amount.compareTo(amount) == 0 &&
                this.currency == currency,
        ) {
            "fund operation key reused for a different request"
        }
    }
}

interface FundOrderPlans {
    suspend fun find(operationKey: String): FundOrderPlan?

    /** The first complete plan wins; a concurrent caller must use the stored plan. */
    suspend fun saveIfAbsent(plan: FundOrderPlan): FundOrderPlan
}
