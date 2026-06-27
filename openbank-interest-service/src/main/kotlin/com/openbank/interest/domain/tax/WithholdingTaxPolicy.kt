// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.domain.tax

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/**
 * Pure, framework-free withholding-tax gate for credit interest at capitalization (ADR-0033 §B).
 *
 * Implements the Czech final withholding (srážková daň) under zákona č. 586/1992 Sb.:
 * - **§36** — special tax rate of **15 %** for resident individuals (final withholding at source),
 *   **35 %** for non-residents in a non-cooperating / no-treaty state, or an applicable
 *   double-tax-treaty rate.
 * - **§38d** — withheld on the credit date (here: capitalization).
 * - Legal entities are **not** withheld (interest enters the CIT base gross).
 * - Tax base and tax amount are rounded **down to whole CZK** (daňový řád).
 *
 * The rule and its statutory rounding live here, in one tested place, so they cannot drift across
 * call sites — the same shape as ADR-0032's screening policy.
 */
object WithholdingTaxPolicy {

    /** Currency in which Czech withholding is assessed (ADR-0033 §E). */
    const val ASSESSMENT_CURRENCY: String = "CZK"

    /** §36 special rate for resident individuals — also the non-resident default and fail-safe. */
    val RESIDENT_INDIVIDUAL_RATE: BigDecimal = BigDecimal("0.15")

    /** §36 odst. 1 písm. c) rate for non-cooperating / no-treaty states. */
    val NON_COOPERATING_RATE: BigDecimal = BigDecimal("0.35")

    /** Whole-CZK scale for the tax base and tax amount (rounded down per daňový řád). */
    private const val TAX_SCALE: Int = 0

    /**
     * Decide the withholding treatment of [grossInterest] credited in [currency] to the beneficiary
     * described by [profile], as of [asOf] (the credit date — reserved for future rate-schedule
     * lookups; the v1 rates are time-invariant).
     */
    fun compute(
        grossInterest: BigDecimal,
        currency: String,
        profile: TaxProfile,
        @Suppress("UNUSED_PARAMETER") asOf: LocalDate
    ): WithholdingResult {
        // §E: only CZK-denominated interest is withheld in v1; foreign interest is flagged.
        if (!currency.equals(ASSESSMENT_CURRENCY, ignoreCase = true)) {
            return passThrough(grossInterest, WithholdingTreatment.DEFERRED_FX, profile.exemptCode)
        }

        // A statutory/treaty exemption with evidence on file — credit gross, record the reason.
        if (profile.exemptCode != null) {
            return passThrough(grossInterest, WithholdingTreatment.EXEMPT, profile.exemptCode)
        }

        // Legal-entity beneficiary — interest enters the CIT base gross (§36 scope), no withholding.
        if (profile.taxpayerType == TaxpayerType.LEGAL_ENTITY) {
            return passThrough(grossInterest, WithholdingTreatment.NOT_WITHHELD, exemptCode = null)
        }

        // Resident/non-resident individual — withhold at source.
        val rate = rateFor(profile)
        val taxableBase = grossInterest.max(BigDecimal.ZERO).setScale(TAX_SCALE, RoundingMode.DOWN)
        val taxAmount = taxableBase.multiply(rate).setScale(TAX_SCALE, RoundingMode.DOWN)
        val netAmount = grossInterest.subtract(taxAmount)
        return WithholdingResult(
            taxableBase = taxableBase,
            rate = rate,
            taxAmount = taxAmount,
            netAmount = netAmount,
            treatment = WithholdingTreatment.WITHHELD,
            exemptCode = null
        )
    }

    /** Statutory rate for an individual: residents 15 %; non-residents treaty / 35 % / 15 %. */
    private fun rateFor(profile: TaxProfile): BigDecimal = when (profile.residency) {
        TaxResidency.RESIDENT -> RESIDENT_INDIVIDUAL_RATE
        TaxResidency.NON_RESIDENT ->
            profile.treatyRate
                ?: if (profile.nonCooperatingState) NON_COOPERATING_RATE else RESIDENT_INDIVIDUAL_RATE
    }

    /** A no-withholding outcome: zero tax, credit gross, treatment + reason recorded. */
    private fun passThrough(
        grossInterest: BigDecimal,
        treatment: WithholdingTreatment,
        exemptCode: String?
    ) = WithholdingResult(
        taxableBase = BigDecimal.ZERO.setScale(TAX_SCALE),
        rate = BigDecimal.ZERO.setScale(2),
        taxAmount = BigDecimal.ZERO.setScale(TAX_SCALE),
        netAmount = grossInterest,
        treatment = treatment,
        exemptCode = exemptCode
    )
}
