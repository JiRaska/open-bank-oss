// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.scenario

import com.openbank.libs.domain.money.Money
import com.openbank.libs.lending.EclInputs
import com.openbank.libs.lending.Ifrs9
import com.openbank.libs.lending.Ifrs9Stage
import java.math.BigDecimal
import kotlin.random.Random

/**
 * Issue #8364 (PD/LGD calibration governance): a **calibration replay** harness for the IFRS 9
 * expected-credit-loss path — re-runs a deterministic synthetic loan portfolio through the REAL
 * `openbank-libs-domain` [Ifrs9] math under two parameter sets (the currently-bound model vs a
 * candidate calibration) and reports the per-exposure and portfolio-level ECL delta, so a
 * parameter change is reviewed against its quantified balance-sheet impact BEFORE it ships, not
 * discovered in the next provisioning cycle.
 *
 * Unlike the money-path scenarios in this package this is NOT interleaved into
 * [com.openbank.simulation.runner.SimulationRunner]: it posts no journals and touches no
 * balances, so the invariant sweep has nothing to check — its output is a [CalibrationReport]
 * for a human (credit-risk) reviewer, not an invariant verdict. It lives in this module for the
 * same reason the other scenarios do: it must drive the real domain types, and this is the
 * module wired to do that from a pure JVM.
 *
 * The replay mirrors production `LendingService.snapshotFor`/`applyCollateral` semantics exactly:
 * staging from days-past-due (with the same rebuttable-presumption thresholds via [Ifrs9.assess]
 * defaults), collateral adjustment via [Ifrs9.collateralAdjustedLgd] applied to the flat LGD
 * before staging (PD untouched — the documented first-pass limitation), and the ECL product
 * PD · LGD · EAD computed by [Ifrs9] itself, never re-modelled here.
 *
 * Determinism: [syntheticPortfolio] is fully seeded — the same seed reproduces the same
 * portfolio, so a calibration report is diffable across runs and a candidate parameter set can
 * be regression-tested in CI.
 */
object LendingEclCalibrationScenario {

    /** One synthetic exposure: the staging inputs plus the haircut-adjusted collateral cover. */
    data class SyntheticExposure(
        val id: String,
        val daysPastDue: Int,
        val exposureAtDefault: Money,
        val haircutAdjustedCollateralValue: BigDecimal = BigDecimal.ZERO,
    )

    /**
     * One versioned risk-parameter set — the simulation-side mirror of what a
     * `RiskParameterSource` binds. [modelVersion] is carried into every [EclInputs] so the
     * report attributes every figure to the parameter set that produced it (issue #8364).
     */
    data class ParameterSet(
        val modelVersion: String,
        val pd12Month: BigDecimal,
        val pdLifetime: BigDecimal,
        val lgd: BigDecimal,
    ) {
        fun inputsFor(exposureAtDefault: Money): EclInputs =
            EclInputs(pd12Month, pdLifetime, lgd, exposureAtDefault, modelVersion)
    }

    /** The ECL movement for one exposure between the two parameter sets. */
    data class ExposureDelta(
        val exposureId: String,
        val stage: Ifrs9Stage,
        val currentEcl: Money,
        val candidateEcl: Money,
        val delta: Money,
    )

    /**
     * The portfolio-level outcome of a replay: totals plus the full per-exposure breakdown, so a
     * reviewer sees both the balance-sheet number and WHERE it moves (a stage-3-heavy book reacts
     * to an LGD change very differently from a clean one).
     */
    data class CalibrationReport(
        val currentModelVersion: String,
        val candidateModelVersion: String,
        val exposureCount: Int,
        val totalCurrentEcl: Money,
        val totalCandidateEcl: Money,
        val totalDelta: Money,
        val perExposure: List<ExposureDelta>,
    )

    // Bucket mix of the synthetic book (percent draws): ~60% clean, ~15% watch, ~15% stage 2,
    // ~10% default.
    private const val BUCKET_DRAW_RANGE = 100
    private const val CLEAN_BUCKET_MAX = 59
    private const val WATCH_BUCKET_MAX = 74
    private const val STAGE2_BUCKET_MAX = 89
    private const val WATCH_DPD_MIN = 1
    private const val STAGE2_DPD_MIN = 31
    private const val DEFAULT_DPD_MIN = 91
    private const val DEFAULT_DPD_MAX_EXCLUSIVE = 360
    private const val EAD_MIN = 10_000
    private const val EAD_MAX_EXCLUSIVE = 5_000_001
    private const val COLLATERALISED_PERCENT = 30
    private const val COVER_PERCENT_MIN = 10
    private const val COVER_PERCENT_MAX_EXCLUSIVE = 121

    /**
     * A seeded synthetic loan book spanning every stage bucket: current (0 DPD), watch
     * (1–30 DPD), SICR territory (31–90 DPD → Stage 2), and default (> 90 DPD → Stage 3), with a
     * seeded subset carrying haircut-adjusted collateral cover so the LGD-reduction path is
     * exercised rather than vacuously passing on an all-unsecured book.
     */
    fun syntheticPortfolio(seed: Long, size: Int = 100, currency: String = "CZK"): List<SyntheticExposure> {
        require(size > 0) { "portfolio size must be positive: $size" }
        val random = Random(seed)
        return (1..size).map { ordinal ->
            // Bucket the book deterministically per the mix declared above.
            val dpd = when (random.nextInt(BUCKET_DRAW_RANGE)) {
                in 0..CLEAN_BUCKET_MAX -> 0
                in (CLEAN_BUCKET_MAX + 1)..WATCH_BUCKET_MAX -> random.nextInt(WATCH_DPD_MIN, STAGE2_DPD_MIN)
                in (WATCH_BUCKET_MAX + 1)..STAGE2_BUCKET_MAX -> random.nextInt(STAGE2_DPD_MIN, DEFAULT_DPD_MIN)
                else -> random.nextInt(DEFAULT_DPD_MIN, DEFAULT_DPD_MAX_EXCLUSIVE)
            }
            val ead = BigDecimal(random.nextInt(EAD_MIN, EAD_MAX_EXCLUSIVE)).setScale(2)
            val collateralised = random.nextInt(BUCKET_DRAW_RANGE) < COLLATERALISED_PERCENT
            val cover = if (collateralised) {
                // Cover 10–120% of the exposure so over-collateralization flooring is exercised too.
                ead.multiply(
                    BigDecimal(random.nextInt(COVER_PERCENT_MIN, COVER_PERCENT_MAX_EXCLUSIVE)).movePointLeft(2),
                )
            } else {
                BigDecimal.ZERO
            }
            SyntheticExposure(
                id = "sim-loan-$ordinal",
                daysPastDue = dpd,
                exposureAtDefault = Money.of(ead, currency),
                haircutAdjustedCollateralValue = cover,
            )
        }
    }

    /**
     * Re-run [portfolio] under [current] and [candidate] through the real [Ifrs9] math and
     * report the movement. Staging depends only on DPD and is therefore identical across the two
     * sets — what moves is the PD/LGD the stage's horizon multiplies, which is exactly the
     * calibration question this answers.
     */
    fun replay(portfolio: List<SyntheticExposure>, current: ParameterSet, candidate: ParameterSet): CalibrationReport {
        require(portfolio.isNotEmpty()) { "portfolio cannot be empty" }
        require(current.modelVersion != candidate.modelVersion) {
            "a calibration replay compares two DIFFERENT model versions " +
                "(both were '${current.modelVersion}') — a parameter change ships with a new version"
        }
        val deltas = portfolio.map { exposure ->
            val currentEcl = eclFor(exposure, current)
            val candidateEcl = eclFor(exposure, candidate)
            ExposureDelta(
                exposureId = exposure.id,
                stage = Ifrs9.stage(daysPastDue = exposure.daysPastDue),
                currentEcl = currentEcl,
                candidateEcl = candidateEcl,
                delta = candidateEcl - currentEcl,
            )
        }
        val currency = portfolio.first().exposureAtDefault.currency.code
        fun total(of: (ExposureDelta) -> Money): Money = deltas.fold(Money.zero(currency)) { acc, d -> acc + of(d) }
        return CalibrationReport(
            currentModelVersion = current.modelVersion,
            candidateModelVersion = candidate.modelVersion,
            exposureCount = portfolio.size,
            totalCurrentEcl = total { it.currentEcl },
            totalCandidateEcl = total { it.candidateEcl },
            totalDelta = total { it.delta },
            perExposure = deltas,
        )
    }

    /**
     * `LendingService.applyCollateral` + `Ifrs9.assess` for one exposure: adjust the flat LGD by
     * the (already haircut-adjusted) cover, then stage and compute. PD is untouched by
     * collateral, mirroring production.
     */
    private fun eclFor(exposure: SyntheticExposure, params: ParameterSet): Money {
        val inputs = params.inputsFor(exposure.exposureAtDefault)
        val adjusted = if (exposure.haircutAdjustedCollateralValue.signum() > 0) {
            inputs.copy(
                lgd = Ifrs9.collateralAdjustedLgd(
                    lgd = inputs.lgd,
                    haircutAdjustedCollateralValue = exposure.haircutAdjustedCollateralValue,
                    exposureAtDefault = exposure.exposureAtDefault.amount,
                ),
            )
        } else {
            inputs
        }
        return Ifrs9.assess(daysPastDue = exposure.daysPastDue, inputs = adjusted).expectedCreditLoss
    }
}
