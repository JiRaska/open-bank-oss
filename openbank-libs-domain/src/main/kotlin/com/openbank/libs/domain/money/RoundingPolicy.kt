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

/** fx-service FxRate inverse and CnbFixing per-unit rate, transaction implied rate; matches numeric(18,8). */
private const val FX_RATE_SCALE = 8

/** interest-service WithholdingTaxPolicy.TAX_SCALE: whole currency units. */
private const val TAX_SCALE = 0

/** treasury CounterpartyExposure.utilisationPercent: two decimals of a percentage. */
private const val RATIO_PERCENT_SCALE = 2

/** treasury quoted money-market rates (Quote.RATE_SCALE) and FX-deal rate deviation (Deal.DEVIATION_SCALE). */
private const val RATE_PERCENT_SCALE = 4

/** treasury amounts on its CZK chart: a literal two decimals (Deal/Postings MONEY_SCALE). */
private const val TREASURY_AMOUNT_SCALE = 2

/** treasury ACT/360 interest intermediate quotient (Deal.WORK_SCALE). */
private const val TREASURY_INTEREST_WORK_SCALE = 12

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
 * Call sites use these policies instead of an inline `setScale`/`RoundingMode` — enforced for
 * money-path services by the `money-rounding-inline-ratchet` gate — and
 * `MoneyRoundingPolicyCallSiteTest` pins each policy's (scale, mode), so editing one fails the build.
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
     * InterestService.kt:394 (gross) and :406 (net capitalisation), sdd SddCollectionDebitConsumer,
     * domestic SettlementAdapter.
     *
     * NOT ledger FxRevaluationPosting.kt:119-120: that site is a literal `setScale(2, HALF_UP)`,
     * equivalent to this policy only because it posts CZK (2 minor digits). Like treasury's fixed
     * scale-2 sites it stays outside the registry until it migrates with its own decision.
     */
    LEDGER_POSTING(
        null,
        RoundingMode.HALF_UP,
        "Currency scale, HALF_UP — amounts normalised for booking (transaction, interest, sdd, domestic).",
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
     * Statement output (PDF, camt.053, MT940): the currency's minor units (ISO 4217 default fraction
     * digits; a code with none, e.g. XAU, falls back to 2 in the renderers), HALF_UP. The renderers
     * move from a literal scale 2 to this in #11081; the policy records the intended rule so the
     * registry does not codify the JPY/KWD mis-rendering that PR fixes.
     */
    DISPLAY(
        null,
        RoundingMode.HALF_UP,
        "Statement rendering at the currency's minor units, HALF_UP — statement Pdf/Camt053/Mt940 renderers.",
    ),

    /**
     * A DIMENSIONLESS ratio or percentage reported for risk and limit monitoring — never a monetary
     * amount, so it has no currency and a fixed scale: treasury CounterpartyExposure
     * .utilisationPercent (exposure x 100 / limit). HALF_UP because that is what the site does and
     * because a utilisation figure is read against a threshold by a human: the conventional
     * "round half away from zero" is what a risk officer expects, and HALF_EVEN's banker's bias
     * exists to cancel out over sums of amounts, which a single reported ratio never is. Do not use
     * it to round money; a percentage FEE amount is [FEE].
     */
    RATIO_PERCENT(
        RATIO_PERCENT_SCALE,
        RoundingMode.HALF_UP,
        "Dimensionless ratio/percentage at scale 2, HALF_UP — treasury counterparty-limit utilisation; never money.",
    ),

    /**
     * A rate or deviation expressed in PERCENT at four decimals, HALF_UP: treasury simulated
     * counterparty quotes (Quote.midRate / bid / ask) and the FX-deal dealer-rate deviation against
     * the fx-service mid (Deal.checkRate). Dimensionless, never money.
     */
    RATE_PERCENT(
        RATE_PERCENT_SCALE,
        RoundingMode.HALF_UP,
        "Percent rate or rate deviation at scale 4, HALF_UP — treasury quotes and FX-deal tolerance check.",
    ),

    /**
     * Treasury amounts at a FIXED two decimals, HALF_UP: FX-spot CZK counter-amount, ACT/360
     * deposit interest and its daily accrual. Fixed rather than currency-scaled because that is what
     * the sites do today; for CZK/EUR/USD it equals [LEDGER_POSTING], for a 0- or 3-digit currency
     * it does not — moving treasury to the currency scale is a behaviour change and its own decision.
     */
    TREASURY_AMOUNT(
        TREASURY_AMOUNT_SCALE,
        RoundingMode.HALF_UP,
        "Treasury amount at a fixed scale 2, HALF_UP — FX counter-amount, ACT/360 interest and accrual.",
    ),

    /** Intermediate ACT/360 interest quotient at scale 12, HALF_UP, before [TREASURY_AMOUNT]. */
    TREASURY_INTEREST_WORK(
        TREASURY_INTEREST_WORK_SCALE,
        RoundingMode.HALF_UP,
        "Intermediate ACT/360 interest quotient at scale 12, HALF_UP — treasury Deal.act360Interest.",
    ),
    ;

    /**
     * Rounds [value] to this policy's [fixedScale]. Only for a policy with a fixed scale; a
     * currency-scaled policy needs [round] with a currency.
     */
    fun round(value: BigDecimal): BigDecimal {
        val scale = requireNotNull(fixedScale) { "$this has no fixed scale; it needs a currency" }
        return value.setScale(scale, mode)
    }

    /**
     * Divides [dividend] by [divisor] with a single rounding under this policy — exactly
     * `dividend.divide(divisor, scale, mode)`, so a non-terminating quotient is never rounded twice.
     * Only for a policy with a [fixedScale] (a currency-less ratio); a currency-scaled policy has no
     * scale without a currency.
     */
    fun divide(dividend: BigDecimal, divisor: BigDecimal): BigDecimal {
        val scale = requireNotNull(fixedScale) { "$this has no fixed scale; it needs a currency" }
        return dividend.divide(divisor, scale, mode)
    }

    /** The scale this policy rounds to for [currency]. */
    fun scaleFor(currency: CurrencyCode): Int = fixedScale ?: currency.defaultFractionDigits

    /** Rounds a raw [value] under this policy. Use for intermediate (e.g. sub-cent) arithmetic. */
    fun round(value: BigDecimal, currency: CurrencyCode): BigDecimal = value.setScale(scaleFor(currency), mode)
}
