// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.application.usecase

import com.openbank.pension.application.onboarding.OnboardingRulesRegistry
import com.openbank.pension.domain.model.ProductLine
import com.openbank.pension.domain.pack.JurisdictionPack
import com.openbank.pension.domain.pack.JurisdictionPackRegistry
import com.openbank.pension.domain.simulation.ProjectionRequest
import com.openbank.pension.domain.simulation.RetirementProjection
import com.openbank.pension.domain.simulation.StrategyAssumption
import com.openbank.pension.domain.simulation.StrategyProjection
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate

data class SimulationCommand(
    val jurisdiction: String,
    val productLine: ProductLine,
    val strategyCode: String?,
    val monthlyContribution: BigDecimal,
    val employerMonthlyContribution: BigDecimal,
    val horizonYears: Int,
)

data class SimulationResult(
    val pack: JurisdictionPack,
    val requestedStrategy: String?,
    val projections: List<StrategyProjection>,
)

/**
 * Retirement simulation (ADR-0334 S8). Uses the pack IN FORCE TODAY for the requested
 * jurisdiction and product line, the strategies that pack's onboarding extension offers, and the
 * provider's configured assumed annual return per strategy. A strategy with no configured return
 * is left out rather than projected at an invented rate. Read-only: nothing is stored.
 */
class SimulationService(
    private val packs: JurisdictionPackRegistry,
    private val rules: OnboardingRulesRegistry,
    private val assumedReturns: Map<String, BigDecimal>,
    private val clock: Clock,
) {
    fun simulate(command: SimulationCommand): SimulationResult {
        val pack = packs.resolve(command.jurisdiction, command.productLine, LocalDate.now(clock))
        val offered = rules.rules(pack.jurisdiction, pack.productLine, pack.version).strategies
        command.strategyCode?.let { code ->
            require(offered.any { it.code == code }) { "strategy $code is not offered under this pack" }
        }
        val strategies = offered.mapNotNull { option ->
            assumedReturns[option.code]?.let { StrategyAssumption(option.code, option.riskClass, it) }
        }
        check(strategies.isNotEmpty()) { "no strategy of this pack has an assumed return configured" }
        val projections = RetirementProjection.project(
            pack,
            strategies,
            ProjectionRequest(command.monthlyContribution, command.employerMonthlyContribution, command.horizonYears),
        )
        return SimulationResult(pack, command.strategyCode, projections)
    }
}
