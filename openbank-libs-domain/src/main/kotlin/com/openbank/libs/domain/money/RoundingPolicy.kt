// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.money

import java.math.BigDecimal
import java.math.RoundingMode

/** interest-service InterestService daily accrual scale. */
private const val INTEREST_ACCRUAL_SCALE = 6

/** fx-service FxRate.INVERSE_SCALE / CnbFixing per-unit rate scale. */
private const val FX_RATE_SCALE = 8

/**
 * The closed, named set of rounding rules the fleet uses (ADR-0318). Each policy is a
 * (scale, mode) pair: [fixedScale] when the rule has its own scale, otherwise the currency's
 * `defaultFractionDigits`.
 *
 * The initial values mirror what the code does TODAY at the call sites named in each
 * rationale — this registry moves where the rule lives, it does not change a posted amount.
 * Changing a policy's mode or scale is a customer-visible change and needs its own PR with a
 * money-path review.
 */
enum class RoundingPolicy(val fixedScale: Int?, val mode: RoundingMode, val rationale: String) {
    LEDGER_POSTING(
        null,
        RoundingMode.HALF_EVEN,
        "Currency scale, HALF_EVEN — the behaviour of Money.scale().",
    ),
    INTEREST_ACCRUAL(
        INTEREST_ACCRUAL_SCALE,
        RoundingMode.HALF_UP,
        "Sub-cent daily accrual — interest-service InterestService accrual (setScale(6, HALF_UP)).",
    ),
    FX_RATE(
        FX_RATE_SCALE,
        RoundingMode.HALF_UP,
        "Rate arithmetic — fx-service FxRate.INVERSE_SCALE = 8 and CnbFixing per-unit rate, HALF_UP.",
    ),
    FX_AMOUNT(
        null,
        RoundingMode.HALF_UP,
        "Converted amount to the currency minor unit — fx-service FxRate.convert, HALF_UP.",
    ),
    FEE(
        null,
        RoundingMode.HALF_UP,
        "Percentage fee to the currency minor unit — fx-service FxRate fee, HALF_UP.",
    ),
    TAX_WITHHOLDING(
        0,
        RoundingMode.DOWN,
        "Withholding tax base and amount in whole units, DOWN — interest-service WithholdingTaxPolicy.TAX_SCALE.",
    ),
    DISPLAY(
        null,
        RoundingMode.HALF_EVEN,
        "Presentation at the currency scale; same rule as LEDGER_POSTING so a shown amount equals the posted one.",
    ),
    ;

    /** The scale this policy rounds to for [currency]. */
    fun scaleFor(currency: CurrencyCode): Int = fixedScale ?: currency.defaultFractionDigits

    /** Rounds a raw [value] under this policy. Use for intermediate (e.g. sub-cent) arithmetic. */
    fun round(value: BigDecimal, currency: CurrencyCode): BigDecimal = value.setScale(scaleFor(currency), mode)
}
