// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.lending

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * The customer's own view of their finances (ADR-0269 / APP-ADR-0001 rule 5).
 *
 * ## What this is NOT
 *
 * It is not a score, not a rating, and not an input to a credit decision. There is no single
 * number here on purpose: a grade invites the customer to optimise it and invites the bank to act
 * on it, and neither is what this exists for. The credit decision is the deterministic engine's
 * (ADR-0213), and keeping the two apart is what lets a threshold move without a customer's
 * "score" moving underneath them.
 *
 * ## Why four pillars and not one
 *
 * Reserve, cashflow, obligations and habits fail independently. Someone with a strong income and
 * no buffer is in a different situation from someone with a thin income and six months of cover,
 * and a single number would rate them the same. Four answers also give the customer something to
 * act on, which one number never does.
 *
 * ## Why every pillar can answer "unknown"
 *
 * A pillar with no data is [PillarZone.UNKNOWN], never a middling or a healthy value. The bank not
 * having seen something is a fact about the bank, not about the customer, and rendering it as a
 * verdict would tell someone their finances are fine — or shaky — on the strength of nothing.
 */
enum class PillarZone {
    /** Comfortable. */
    HEALTHY,

    /** Works, with no margin. Worth saying out loud; not a warning. */
    STRETCHED,

    /** The pillar is where trouble starts. */
    AT_RISK,

    /** Not enough data. Never rendered as a verdict. */
    UNKNOWN,
}

/**
 * One pillar. [value] and [target] are for the customer to see the working; both are null when the
 * zone is [PillarZone.UNKNOWN], so nothing invented can be displayed.
 */
data class HealthPillar(
    val code: String,
    val zone: PillarZone,
    val value: BigDecimal? = null,
    val target: BigDecimal? = null,
)

/** The whole view. Deliberately has no aggregate field — see the class docs. */
data class FinancialHealthView(val pillars: List<HealthPillar>) {
    init {
        require(pillars.map { it.code }.toSet().size == pillars.size) { "duplicate pillar code" }
    }

    fun pillar(code: String): HealthPillar? = pillars.firstOrNull { it.code == code }
}

/**
 * Inputs, each independently nullable.
 *
 * Nullability is the point: three of these come from different services, and a partial answer is
 * the normal case rather than an error. One unavailable upstream must grey out ONE pillar, not the
 * screen — a customer who opens this to check their reserve should not be told nothing is known
 * because the loan book happened to be slow.
 */
data class FinancialHealthInputs(
    val monthlyIncome: BigDecimal?,
    val monthlyOutflow: BigDecimal?,
    val monthlyNet: BigDecimal?,
    val volatilityRatio: BigDecimal?,
    val liquidBalance: BigDecimal?,
    val monthlyDebtService: BigDecimal?,
    val hasArrears: Boolean?,
    val monthsObserved: Int?,
)

object FinancialHealth {

    const val PILLAR_RESERVE = "RESERVE"
    const val PILLAR_CASHFLOW = "CASHFLOW"
    const val PILLAR_OBLIGATIONS = "OBLIGATIONS"
    const val PILLAR_HABITS = "HABITS"

    /** Three months of outgoings is the reserve target this view holds the customer's cover against. */
    private val RESERVE_TARGET_MONTHS = BigDecimal("3")
    private val RESERVE_STRETCHED_MONTHS = BigDecimal("1")

    /** Above this share of income going to debt service, the pillar is at risk (the DSTI shape). */
    private val DSTI_AT_RISK = BigDecimal("0.45")
    private val DSTI_STRETCHED = BigDecimal("0.35")

    /** A month-to-month swing beyond this share of income is what makes planning hard. */
    private val VOLATILITY_AT_RISK = BigDecimal("0.60")
    private val VOLATILITY_STRETCHED = BigDecimal("0.30")

    /** Below this, a median is an anecdote rather than a pattern — the same floor the offer gate uses. */
    private const val MIN_MONTHS = 3

    private val MC = MathContext.DECIMAL64

    /** A ratio disclosed to four places; money to the minor unit; cover to one decimal month. */
    private const val DSTI_SCALE = 4
    private const val MONEY_SCALE = 2
    private const val MONTHS_SCALE = 1

    fun assess(inputs: FinancialHealthInputs): FinancialHealthView = FinancialHealthView(
        listOf(
            reserve(inputs),
            cashflow(inputs),
            obligations(inputs),
            habits(inputs),
        ),
    )

    /** Months of outgoings the liquid balance covers. */
    private fun reserve(i: FinancialHealthInputs): HealthPillar {
        val balance = i.liquidBalance
        val outflow = i.monthlyOutflow
        if (balance == null || outflow == null || outflow <= BigDecimal.ZERO) {
            return HealthPillar(PILLAR_RESERVE, PillarZone.UNKNOWN)
        }
        val months = balance.divide(outflow, MC)
        val zone = when {
            months >= RESERVE_TARGET_MONTHS -> PillarZone.HEALTHY
            months >= RESERVE_STRETCHED_MONTHS -> PillarZone.STRETCHED
            else -> PillarZone.AT_RISK
        }
        return HealthPillar(
            PILLAR_RESERVE,
            zone,
            months.setScale(MONTHS_SCALE, RoundingMode.DOWN),
            RESERVE_TARGET_MONTHS,
        )
    }

    /**
     * What is left each month, and how steady it is.
     *
     * A negative net is at risk regardless of volatility — a stable shortfall is still a shortfall,
     * and calling it "steady" would be the most misleading reading available.
     */
    private fun cashflow(i: FinancialHealthInputs): HealthPillar {
        val net = i.monthlyNet
        val months = i.monthsObserved
        if (net == null || months == null || months < MIN_MONTHS) {
            return HealthPillar(PILLAR_CASHFLOW, PillarZone.UNKNOWN)
        }
        val volatility = i.volatilityRatio
        val zone = when {
            net <= BigDecimal.ZERO -> PillarZone.AT_RISK
            volatility == null -> PillarZone.STRETCHED
            volatility >= VOLATILITY_AT_RISK -> PillarZone.AT_RISK
            volatility >= VOLATILITY_STRETCHED -> PillarZone.STRETCHED
            else -> PillarZone.HEALTHY
        }
        return HealthPillar(PILLAR_CASHFLOW, zone, net.setScale(MONEY_SCALE, RoundingMode.HALF_UP), null)
    }

    /** Debt service as a share of income. */
    private fun obligations(i: FinancialHealthInputs): HealthPillar {
        val income = i.monthlyIncome
        val debt = i.monthlyDebtService
        if (income == null || income <= BigDecimal.ZERO || debt == null) {
            return HealthPillar(PILLAR_OBLIGATIONS, PillarZone.UNKNOWN)
        }
        val dsti = debt.divide(income, MC)
        val zone = when {
            dsti >= DSTI_AT_RISK -> PillarZone.AT_RISK
            dsti >= DSTI_STRETCHED -> PillarZone.STRETCHED
            else -> PillarZone.HEALTHY
        }
        return HealthPillar(PILLAR_OBLIGATIONS, zone, dsti.setScale(DSTI_SCALE, RoundingMode.HALF_UP), DSTI_STRETCHED)
    }

    /**
     * Whether repayments are being met.
     *
     * Only two honest answers exist from this input: in arrears, or not. There is deliberately no
     * "excellent" tier — a customer who has simply paid their bills has done the expected thing,
     * and grading them for it is the scoring behaviour this whole view refuses.
     */
    private fun habits(i: FinancialHealthInputs): HealthPillar {
        val arrears = i.hasArrears ?: return HealthPillar(PILLAR_HABITS, PillarZone.UNKNOWN)
        return HealthPillar(PILLAR_HABITS, if (arrears) PillarZone.AT_RISK else PillarZone.HEALTHY)
    }
}
