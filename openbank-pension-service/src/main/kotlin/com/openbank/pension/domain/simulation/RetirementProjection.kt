// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.domain.simulation

import com.openbank.pension.domain.pack.IncentivePeriod
import com.openbank.pension.domain.pack.IncentiveType
import com.openbank.pension.domain.pack.JurisdictionPack
import com.openbank.pension.domain.pack.PackEvaluator
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/** One strategy's projected outcome. Every figure is ILLUSTRATIVE — see [RetirementProjection]. */
data class StrategyProjection(
    val strategyCode: String,
    val riskClass: Int,
    val assumedAnnualReturn: BigDecimal,
    val ownContributions: BigDecimal,
    val employerContributions: BigDecimal,
    val stateIncentives: BigDecimal,
    val projectedValue: BigDecimal,
)

data class ProjectionRequest(
    val monthlyContribution: BigDecimal,
    val employerMonthlyContribution: BigDecimal,
    val horizonYears: Int,
)

/** A strategy the pack offers, with the return the provider ASSUMES for it (configuration, not a promise). */
data class StrategyAssumption(val strategyCode: String, val riskClass: Int, val assumedAnnualReturn: BigDecimal)

/**
 * Retirement projection per strategy (ADR-0334 S8, the customer simulation). Pure arithmetic, no
 * framework, no country literal: the incentive the pack grants on the monthly contribution comes
 * from [PackEvaluator] (MATCHING and FLAT only — tax relief is a saving outside the contract, not
 * money in it), and each strategy compounds monthly at its assumed annual return.
 *
 * ILLUSTRATIVE ONLY. The assumed returns are provider configuration, not a forecast; no fee,
 * inflation, contribution change or pack change is modelled; past or assumed performance is no
 * guide to the future. The response carries that label and [DISCLAIMER] verbatim, and the edge
 * must show it.
 */
object RetirementProjection {
    const val DISCLAIMER =
        "Illustrative projection only, not advice and not a forecast. It assumes a constant annual " +
            "return per strategy set by the provider, constant contributions and today's incentive rules, " +
            "and ignores fees, inflation and taxation. Actual results will differ and can be lower than " +
            "the amounts paid in."

    private const val MONTHS_PER_YEAR = 12
    private const val MONEY_SCALE = 2
    const val MAX_HORIZON_YEARS = 60
    private val MATH = MathContext.DECIMAL64

    fun project(
        pack: JurisdictionPack,
        strategies: List<StrategyAssumption>,
        request: ProjectionRequest,
    ): List<StrategyProjection> {
        require(request.horizonYears in 1..MAX_HORIZON_YEARS) { "horizonYears must be in 1..$MAX_HORIZON_YEARS" }
        require(request.monthlyContribution.signum() > 0) { "monthlyContribution must be positive" }
        require(request.employerMonthlyContribution.signum() >= 0) {
            "employerMonthlyContribution must not be negative"
        }
        val months = request.horizonYears * MONTHS_PER_YEAR
        val incentivePerMonth = monthlyIncentive(pack, request)
        val inflowPerMonth = request.monthlyContribution + request.employerMonthlyContribution + incentivePerMonth
        return strategies.map { s ->
            val monthlyRate = monthlyRate(s.assumedAnnualReturn)
            var balance = BigDecimal.ZERO
            repeat(months) { balance = balance.multiply(BigDecimal.ONE + monthlyRate, MATH) + inflowPerMonth }
            StrategyProjection(
                strategyCode = s.strategyCode,
                riskClass = s.riskClass,
                assumedAnnualReturn = s.assumedAnnualReturn,
                ownContributions = money(request.monthlyContribution.multiply(BigDecimal(months))),
                employerContributions = money(request.employerMonthlyContribution.multiply(BigDecimal(months))),
                stateIncentives = money(incentivePerMonth.multiply(BigDecimal(months))),
                projectedValue = money(balance),
            )
        }
    }

    /** MATCHING + FLAT incentive the pack grants on this contribution, expressed per month. */
    private fun monthlyIncentive(pack: JurisdictionPack, request: ProjectionRequest): BigDecimal =
        PackEvaluator.evaluateIncentives(
            pack,
            request.monthlyContribution,
            IncentivePeriod.MONTH,
            request.employerMonthlyContribution.multiply(BigDecimal(MONTHS_PER_YEAR)),
        )
            .filter { it.type == IncentiveType.MATCHING || it.type == IncentiveType.FLAT }
            .fold(BigDecimal.ZERO) { acc, r ->
                acc + r.amount.multiply(BigDecimal(r.period.periodsPerYear))
                    .divide(BigDecimal(MONTHS_PER_YEAR), MATH)
            }

    /** (1 + annual)^(1/12) - 1, to DECIMAL64 precision. */
    private fun monthlyRate(annual: BigDecimal): BigDecimal {
        require(annual > BigDecimal("-1")) { "assumed annual return must exceed -100 %" }
        val rate = Math.pow(BigDecimal.ONE.add(annual).toDouble(), 1.0 / MONTHS_PER_YEAR) - 1.0
        return BigDecimal(rate, MATH)
    }

    private fun money(v: BigDecimal) = v.setScale(MONEY_SCALE, RoundingMode.HALF_EVEN)
}
