// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.model

import java.math.BigDecimal
import java.util.UUID

/**
 * Composite key for one assessed fee: `(cycleId, accountId, feeId, currency)` — deliberately the
 * same four dimensions as `com.openbank.billing.domain.AssessedFee.idempotencyKey`
 * (`fee-{cycleId}-{accountId}-{feeId}-{currency}`, ADR-0143 step 3). Keeping the dimensions
 * identical here is what lets [BillingFeeLedger.conserves] assert the DST invariant precisely at
 * the same granularity billing-service posts at — a multi-fee product's charges are tracked
 * (and must reconcile) individually, not summed into one per-account total.
 */
data class BillingFeeKey(val cycleId: String, val accountId: UUID, val feeId: String, val currency: String)

/**
 * Simulated billing state (ADR-0143 phase 2d): every fee assessed for a cycle and every fee
 * journal posted, keyed identically to [BillingFeeKey] so [conserves] can assert the ADR's DST
 * invariant directly — *Σ fees assessed == Σ fee journals posted* per cycle/account/fee/currency
 * (a waived or zero-amount fee is assessed with amount `0` and never posts, which the equality
 * already covers without a separate "skip" case).
 */
class BillingFeeLedger {
    private val assessed = mutableMapOf<BillingFeeKey, BigDecimal>()
    private val posted = mutableMapOf<BillingFeeKey, BigDecimal>()

    /** Record one assessed fee (chargeable amount; `0` for a waived fee — never posts either). */
    fun recordAssessed(key: BillingFeeKey, amount: BigDecimal) {
        assessed[key] = assessed.getOrDefault(key, BigDecimal.ZERO) + amount
    }

    /** Record the ledger journal actually posted for one assessed fee. */
    fun recordPosted(key: BillingFeeKey, amount: BigDecimal) {
        posted[key] = posted.getOrDefault(key, BigDecimal.ZERO) + amount
    }

    /** Every key seen on either side — the invariant checks the full union, not just one side. */
    fun keys(): Set<BillingFeeKey> = assessed.keys + posted.keys

    fun assessedAmount(key: BillingFeeKey): BigDecimal = assessed.getOrDefault(key, BigDecimal.ZERO)

    fun postedAmount(key: BillingFeeKey): BigDecimal = posted.getOrDefault(key, BigDecimal.ZERO)
}
