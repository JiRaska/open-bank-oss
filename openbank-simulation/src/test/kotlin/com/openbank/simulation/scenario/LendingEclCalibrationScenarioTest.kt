// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.scenario

import com.openbank.libs.domain.money.Money
import com.openbank.libs.lending.Ifrs9Stage
import com.openbank.simulation.scenario.LendingEclCalibrationScenario.ParameterSet
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Issue #8364 (PD/LGD calibration governance): proves the calibration replay is deterministic,
 * spans every IFRS 9 stage bucket, exercises the collateral-adjusted LGD path, and reports a
 * delta that actually discriminates between two parameter sets — a replay whose totals never
 * move would be a review artifact that always says "no impact".
 */
class LendingEclCalibrationScenarioTest {

    private val current = ParameterSet(
        modelVersion = "noop-flat-v1",
        pd12Month = BigDecimal("0.02"),
        pdLifetime = BigDecimal("0.20"),
        lgd = BigDecimal("0.45"),
    )

    private val candidate = ParameterSet(
        modelVersion = "calibrated-2026-q3",
        pd12Month = BigDecimal("0.025"),
        pdLifetime = BigDecimal("0.24"),
        lgd = BigDecimal("0.40"),
    )

    @Test
    fun `the synthetic portfolio is seeded-deterministic and spans every stage`() {
        val first = LendingEclCalibrationScenario.syntheticPortfolio(seed = 42L)
        val second = LendingEclCalibrationScenario.syntheticPortfolio(seed = 42L)
        assertThat(first).isEqualTo(second)

        val stages = first.map { it.daysPastDue }
        assertThat(stages.count { it == 0 }).isGreaterThan(0)
        assertThat(stages.count { it in 1..30 }).isGreaterThan(0)
        assertThat(stages.count { it in 31..90 }).isGreaterThan(0)
        assertThat(stages.count { it > 90 }).isGreaterThan(0)
        // And the collateral path is exercised, not vacuously skipped.
        assertThat(first.count { it.haircutAdjustedCollateralValue.signum() > 0 }).isGreaterThan(0)
    }

    @Test
    fun `a replay attributes every figure to its model version and the totals reconcile`() {
        val portfolio = LendingEclCalibrationScenario.syntheticPortfolio(seed = 7L)
        val report = LendingEclCalibrationScenario.replay(portfolio, current, candidate)

        assertThat(report.currentModelVersion).isEqualTo("noop-flat-v1")
        assertThat(report.candidateModelVersion).isEqualTo("calibrated-2026-q3")
        assertThat(report.exposureCount).isEqualTo(portfolio.size)
        assertThat(report.perExposure).hasSize(portfolio.size)

        // Totals reconcile with the per-exposure breakdown to the haléř.
        val sumCurrent = report.perExposure.fold(Money.zero("CZK")) { acc, d -> acc + d.currentEcl }
        val sumCandidate = report.perExposure.fold(Money.zero("CZK")) { acc, d -> acc + d.candidateEcl }
        assertThat(report.totalCurrentEcl).isEqualTo(sumCurrent)
        assertThat(report.totalCandidateEcl).isEqualTo(sumCandidate)
        assertThat(report.totalDelta).isEqualTo(report.totalCandidateEcl - report.totalCurrentEcl)
    }

    @Test
    fun `a candidate parameter set actually moves the portfolio ECL`() {
        val report = LendingEclCalibrationScenario.replay(
            LendingEclCalibrationScenario.syntheticPortfolio(seed = 7L),
            current,
            candidate,
        )
        assertThat(report.totalDelta.amount.signum()).isNotEqualTo(0)
        assertThat(report.perExposure.count { it.delta.amount.signum() != 0 }).isGreaterThan(0)
    }

    @Test
    fun `stage 3 exposures use the lifetime PD the parameter set supplies, mirroring production`() {
        // Ifrs9.assess does NOT force PD = 1 for defaulted exposures — the risk-parameter source
        // owns that convention, and today's flat source supplies the same pdLifetime for every
        // stage (a documented first-pass limitation, see 07-risk-model-calibration). The replay
        // must mirror production, not the ideal: current 0.20·0.45·10000 = 900, candidate
        // 0.24·0.40·10000 = 960.
        val defaulted = LendingEclCalibrationScenario.SyntheticExposure(
            id = "sim-loan-defaulted",
            daysPastDue = 120,
            exposureAtDefault = Money.of("10000.00", "CZK"),
        )
        val report = LendingEclCalibrationScenario.replay(listOf(defaulted), current, candidate)
        val delta = report.perExposure.single()
        assertThat(delta.stage).isEqualTo(Ifrs9Stage.STAGE_3)
        assertThat(delta.currentEcl).isEqualTo(Money.of("900.00", "CZK"))
        assertThat(delta.candidateEcl).isEqualTo(Money.of("960.00", "CZK"))
        assertThat(delta.delta).isEqualTo(Money.of("60.00", "CZK"))
    }

    @Test
    fun `collateral cover lowers the candidate ECL through the adjusted LGD`() {
        val secured = LendingEclCalibrationScenario.SyntheticExposure(
            id = "sim-loan-secured",
            daysPastDue = 0,
            exposureAtDefault = Money.of("10000.00", "CZK"),
            haircutAdjustedCollateralValue = BigDecimal("10000.00"), // full cover floors LGD at 0
        )
        val report = LendingEclCalibrationScenario.replay(listOf(secured), current, candidate)
        assertThat(report.perExposure.single().currentEcl).isEqualTo(Money.zero("CZK"))
        assertThat(report.totalCandidateEcl).isEqualTo(Money.zero("CZK"))
    }

    @Test
    fun `a replay between identical model versions is rejected`() {
        assertThatThrownBy {
            LendingEclCalibrationScenario.replay(
                LendingEclCalibrationScenario.syntheticPortfolio(seed = 1L, size = 5),
                current,
                current,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `an empty portfolio is rejected rather than dividing by zero downstream`() {
        assertThatThrownBy {
            LendingEclCalibrationScenario.replay(emptyList(), current, candidate)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
