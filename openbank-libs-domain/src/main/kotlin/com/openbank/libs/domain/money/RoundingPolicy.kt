// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.money

import java.math.BigDecimal
import java.math.RoundingMode

/** interest-service InterestService.kt:138 accrued-amount scale. */
private const val INTEREST_ACCRUAL_SCALE = 6

/** interest-service InterestService.kt:137 daily-rate scale (annualRate / 360|365). */
private const val INTEREST_DAILY_RATE_SCALE = 10

/** fx-service FxRate.INVERSE_SCALE / CnbFixing per-unit rate / transaction IMPLIED_FX_RATE_SCALE. */
private const val FX_RATE_SCALE = 8

/** interest-service WithholdingTaxPolicy.TAX_SCALE: whole currency units. */
private const val TAX_SCALE = 0

/** statement-service renderers format with a literal 2 decimals, whatever the currency. */
private const val STATEMENT_DISPLAY_SCALE = 2

/**
 * The closed, named set of rounding rules the fleet uses (ADR-0318). Each policy is a
 * (scale, mode) pair: [fixedScale] when the rule has its own scale, otherwise the currency's
 * `defaultFractionDigits`.
 *
 * Each value is what the named call sites do TODAY on `main` (measured 2026-09-27); where sites
 * disagree they are separate policies, never averaged into one. This registry moves where the
 * rule lives, it does not change a posted amount. Changing a policy's mode or scale is a
 * customer-visible change and needs its own PR with a money-path review.
 *
 * `MoneyRoundingPolicyCallSiteTest` pins every policy to the literal expression of a real call
 * site, so a policy that drifts from the code it claims to describe fails the build.
 */
enum class RoundingPolicy(val fixedScale: Int?, val mode: RoundingMode, val rationale: String) {
    /**
     * [Money.scale] and the persistence mappers that rehydrate a stored amount into a [Money]:
     * ledger PanacheJournalRepository, transaction PanacheTransactionRepository, delegation
     * DelegationGrantEntity / SpendReservationEntity. NOT the rule used when an amount is posted.
     */
    MONEY_SCALE(
        null,
        RoundingMode.HALF_EVEN,
        "Currency scale, HALF_EVEN — Money.scale() and the entity-to-Money rehydration mappers.",
    ),

    /**
     * Normalising an amount to the currency minor unit before it is booked: transaction
     * TransactionService (ingest, sell-side and derived FX settlement leg), interest
     * InterestService (gross and net capitalisation), sdd SddCollectionDebitConsumer, domestic
     * SettlementAdapter, and ledger FxRevaluationPosting (CZK, literal scale 2).
     */
    LEDGER_POSTING(
        null,
        RoundingMode.HALF_UP,
        "Currency scale, HALF_UP — amounts normalised for booking (transaction, interest, sdd, domestic, ledger).",
    ),

    /** Daily rate = annualRate / dayCount divisor, before it is applied to a balance. */
    INTEREST_DAILY_RATE(
        INTEREST_DAILY_RATE_SCALE,
        RoundingMode.HALF_UP,
        "Intermediate daily rate at scale 10, HALF_UP — interest-service InterestService daily-rate division.",
    ),

    /**
     * Sub-cent accrued amount = balance x [INTEREST_DAILY_RATE]-rounded rate. The accrual is two
     * roundings; use both policies in that order, a single (scale, mode) cannot express it.
     */
    INTEREST_ACCRUAL(
        INTEREST_ACCRUAL_SCALE,
        RoundingMode.HALF_UP,
        "Sub-cent daily accrual at scale 6, HALF_UP — applied after INTEREST_DAILY_RATE.",
    ),
    FX_RATE(
        FX_RATE_SCALE,
        RoundingMode.HALF_UP,
        "Rate arithmetic at scale 8, HALF_UP — fx FxRate inverse, CnbFixing per-unit, transaction implied rate.",
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
        TAX_SCALE,
        RoundingMode.DOWN,
        "Withholding tax base and amount in whole units, DOWN — interest-service WithholdingTaxPolicy.TAX_SCALE.",
    ),

    /**
     * Statement output (PDF, camt.053, MT940). Today these format at a literal scale 2 for every
     * currency, so a JPY or KWD statement is rendered at the wrong scale; recorded as-is rather
     * than silently corrected here.
     */
    DISPLAY(
        STATEMENT_DISPLAY_SCALE,
        RoundingMode.HALF_UP,
        "Statement rendering at a fixed scale 2, HALF_UP — statement Pdf/Camt053/Mt940 renderers.",
    ),
    ;

    /** The scale this policy rounds to for [currency]. */
    fun scaleFor(currency: CurrencyCode): Int = fixedScale ?: currency.defaultFractionDigits

    /** Rounds a raw [value] under this policy. Use for intermediate (e.g. sub-cent) arithmetic. */
    fun round(value: BigDecimal, currency: CurrencyCode): BigDecimal = value.setScale(scaleFor(currency), mode)
}
