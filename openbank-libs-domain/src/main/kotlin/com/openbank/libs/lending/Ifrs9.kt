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
 */
data class EclInputs(
    val pd12Month: BigDecimal,
    val pdLifetime: BigDecimal,
    val lgd: BigDecimal,
    val exposureAtDefault: Money,
) {
    init {
        requireProbability(pd12Month, "pd12Month")
        requireProbability(pdLifetime, "pdLifetime")
        requireProbability(lgd, "lgd")
        require(exposureAtDefault.isNonNegative()) { "EAD cannot be negative: $exposureAtDefault" }
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
}
