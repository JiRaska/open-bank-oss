// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.domain

import com.openbank.libs.product.FeeContext
import com.openbank.libs.product.WaiveReason
import com.openbank.libs.product.WaiverEvaluator
import java.math.BigDecimal

/** The outcome of evaluating one [Fee] against a [FeeContext]. */
data class FeeAssessment(
    val feeId: String,
    val feeName: String,
    val waivable: Boolean,
    val waived: Boolean,
    val effectiveAmount: BigDecimal,
    val currency: String,
    val reason: WaiveReason,
)

/**
 * Fee-typed adapter over the shared [WaiverEvaluator] (ADR-0138). Maps a catalog [Fee] and
 * an account [FeeContext] onto a [FeeAssessment]: a non-waivable fee is always charged; an
 * evaluable, satisfied condition waives it; everything else fails closed (charged) with the
 * evaluator's [WaiveReason]. The shared waiver engine lives in `openbank-libs` so interest
 * and eligibility (later phases) reuse it.
 */
object FeeRuleEvaluator {

    fun assess(fee: Fee, context: FeeContext): FeeAssessment {
        val full = fee.amount.toBigDecimal()
        if (!fee.waivable) return assessment(fee, false, full, WaiveReason.NOT_WAIVABLE)
        val reason = WaiverEvaluator.evaluate(fee.waiveCondition, context)
        val waived = reason == WaiveReason.WAIVED_BY_CONDITION
        return assessment(fee, waived, if (waived) BigDecimal.ZERO else full, reason)
    }

    fun assessAll(fees: List<Fee>, context: FeeContext): List<FeeAssessment> = fees.map { assess(it, context) }

    private fun assessment(fee: Fee, waived: Boolean, amount: BigDecimal, reason: WaiveReason): FeeAssessment =
        FeeAssessment(
            feeId = fee.id,
            feeName = fee.name,
            waivable = fee.waivable,
            waived = waived,
            effectiveAmount = amount,
            currency = fee.currency,
            reason = reason,
        )
}
