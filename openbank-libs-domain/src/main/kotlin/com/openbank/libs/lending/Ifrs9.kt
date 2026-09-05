// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.lending

import com.openbank.libs.domain.money.Money
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * IFRS 9 expected-credit-loss (ECL) impairment for the lending bounded context (ADR-0028).
 *
 * Implements the three-stage general approach (IFRS 9 §5.5) that drives loan-loss provisioning and
 * feeds FINREP F 18/F 12 and BCBS 239 risk aggregation:
 *
 *  - **Stage 1** — performing; recognise 12-month ECL.
 *  - **Stage 2** — significant increase in credit risk (SICR) since origination; recognise lifetime ECL.
 *  - **Stage 3** — credit-impaired (in default); recognise lifetime ECL on a (typically) PD = 1 basis.
 *
 * ECL is the discounted product PD · LGD · EAD; discounting to the effective interest rate is left to
 * the caller (pass an already-discounted EAD when material). Pure domain math — no persistence.
 */
enum class Ifrs9Stage(val horizon: EclHorizon) {
    STAGE_1(EclHorizon.TWELVE_MONTH),
    STAGE_2(EclHorizon.LIFETIME),
    STAGE_3(EclHorizon.LIFETIME),
}

enum class EclHorizon { TWELVE_MONTH, LIFETIME }

/**
 * Risk parameters for one exposure.
 *
 * @param pd12Month  12-month probability of default, in [0,1] (used in Stage 1).
 * @param pdLifetime lifetime probability of default, in [0,1] (used in Stages 2/3; 1.0 for defaulted).
 * @param lgd        loss given default, in [0,1].
 * @param exposureAtDefault  EAD — outstanding exposure expected at the point of default.
 * @param modelVersion identifies the risk-parameter model these values came from (issue #8364 —
 *        PD/LGD calibration governance). Persisted onto every provisioning record so an ECL figure
 *        can always be traced back to the exact parameter set that produced it; a change to the
 *        underlying values MUST ship with a new version string (see
 *        `ConservativeRiskParameterSource.MODEL_VERSION` for the convention), which is what makes
 *        a parameter change a reviewed, logged event rather than a silent edit.
 */
data class EclInputs(
    val pd12Month: BigDecimal,
    val pdLifetime: BigDecimal,
    val lgd: BigDecimal,
    val exposureAtDefault: Money,
    val modelVersion: String,
) {
    init {
        requireProbability(pd12Month, "pd12Month")
        requireProbability(pdLifetime, "pdLifetime")
        requireProbability(lgd, "lgd")
        require(exposureAtDefault.isNonNegative()) { "EAD cannot be negative: $exposureAtDefault" }
        require(modelVersion.isNotBlank()) { "modelVersion cannot be blank" }
    }

    private fun requireProbability(v: BigDecimal, name: String) =
        require(v.signum() >= 0 && v <= BigDecimal.ONE) { "$name must be within [0,1]: $v" }
}

/** The impairment outcome for one exposure. */
data class EclResult(val stage: Ifrs9Stage, val horizon: EclHorizon, val expectedCreditLoss: Money)

object Ifrs9 {

    private val MC = MathContext.DECIMAL128

    /**
     * Determine the IFRS 9 stage.
     *
     * @param daysPastDue        current DPD (see [Delinquency]).
     * @param sicr               an explicit significant-increase-in-credit-risk signal (e.g. rating
     *                           downgrade, watchlist) independent of arrears.
     * @param creditImpaired     an explicit impairment / forbearance / unlikely-to-pay signal.
     * @param defaultThresholdDpd DPD beyond which the exposure is credit-impaired (Stage 3); default 90.
     * @param sicrThresholdDpd   DPD beyond which the rebuttable SICR presumption applies (Stage 2); default 30.
     */
    fun stage(
        daysPastDue: Int,
        sicr: Boolean = false,
        creditImpaired: Boolean = false,
        defaultThresholdDpd: Int = 90,
        sicrThresholdDpd: Int = 30,
    ): Ifrs9Stage = when {
        creditImpaired || Delinquency.isDefaulted(daysPastDue, defaultThresholdDpd) -> Ifrs9Stage.STAGE_3
        sicr || daysPastDue > sicrThresholdDpd -> Ifrs9Stage.STAGE_2
        else -> Ifrs9Stage.STAGE_1
    }

    /** ECL = PD · LGD · EAD, with PD selected by stage horizon and the result rounded to the currency. */
    fun ecl(stage: Ifrs9Stage, inputs: EclInputs): EclResult {
        val pd = if (stage.horizon == EclHorizon.TWELVE_MONTH) inputs.pd12Month else inputs.pdLifetime
        val raw = pd.multiply(inputs.lgd, MC).multiply(inputs.exposureAtDefault.amount, MC)
        val scale = inputs.exposureAtDefault.currency.defaultFractionDigits
        val ecl = Money(raw.setScale(scale, RoundingMode.HALF_EVEN), inputs.exposureAtDefault.currency)
        return EclResult(stage, stage.horizon, ecl)
    }

    /** Convenience: derive the stage from risk signals and compute the ECL in one call. */
    fun assess(
        daysPastDue: Int,
        inputs: EclInputs,
        sicr: Boolean = false,
        creditImpaired: Boolean = false,
        defaultThresholdDpd: Int = 90,
        sicrThresholdDpd: Int = 30,
    ): EclResult = ecl(stage(daysPastDue, sicr, creditImpaired, defaultThresholdDpd, sicrThresholdDpd), inputs)

    /**
     * Collateral-adjusted LGD (ADR-0028 D1, first increment — see the lending service's
     * `RiskParameterSource`/collateral wiring for the call site).
     *
     * Reduces the flat/unsecured [lgd] by the loan's haircut-adjusted collateral cover relative to its
     * exposure at default:
     *
     * ```
     * effectiveLgd = max(0, lgd - (haircutAdjustedCollateralValue / exposureAtDefault))
     * ```
     *
     * `haircutAdjustedCollateralValue` is the caller-supplied **sum, across every collateral item
     * registered against the loan, of `marketValue * (1 - haircut)`** — summing before this call keeps
     * this function a single-collateral-blind, pure ratio (it does not know or care how many items or
     * what types back the loan).
     *
     * Deliberately **floored at zero, never negative**: over-collateralization (haircut-adjusted value
     * exceeding EAD) means the loss given default approaches zero, not a negative loss — a negative LGD
     * has no economic meaning and would invert the ECL sign. Symmetrically the result never *exceeds*
     * the input [lgd] — collateral can only reduce loss severity in this model, never increase it (an
     * absent or zero-value collateral registration is a strict no-op, identical to the pre-collateral
     * flat-LGD behaviour).
     *
     * This is a **first-pass, data-modeling increment**, not a calibrated risk model:
     *  - No real-time revaluation/mark-to-market — [haircutAdjustedCollateralValue] is only as fresh as
     *    the last declared/revalued collateral entry (staleness risk owned by the caller).
     *  - No legal perfection-of-security-interest verification — registering collateral here records a
     *    claim, it does not establish or confirm the bank's enforceable legal priority over it.
     *  - Haircut percentages themselves are a first-pass placeholder table set by the caller (e.g.
     *    `CollateralType`-keyed constants) — reasonable starting assumptions, not actuarially or
     *    regulator-calibrated figures.
     *
     * @param lgd the unsecured/flat loss-given-default, in [0,1].
     * @param haircutAdjustedCollateralValue the pre-summed, haircut-adjusted collateral value backing
     *        the loan, in the same currency as [exposureAtDefault]; must be non-negative.
     * @param exposureAtDefault EAD, must be positive (a zero/non-positive EAD returns [lgd] unchanged —
     *        there is no exposure to cover, so the ratio is undefined rather than infinite).
     */
    fun collateralAdjustedLgd(
        lgd: BigDecimal,
        haircutAdjustedCollateralValue: BigDecimal,
        exposureAtDefault: BigDecimal,
    ): BigDecimal {
        require(lgd.signum() >= 0 && lgd <= BigDecimal.ONE) { "lgd must be within [0,1]: $lgd" }
        require(haircutAdjustedCollateralValue.signum() >= 0) {
            "haircutAdjustedCollateralValue cannot be negative: $haircutAdjustedCollateralValue"
        }
        if (exposureAtDefault.signum() <= 0) return lgd
        val coverageRatio = haircutAdjustedCollateralValue.divide(exposureAtDefault, MC)
        val reduced = lgd.subtract(coverageRatio, MC)
        return reduced.coerceIn(BigDecimal.ZERO, lgd)
    }
}
