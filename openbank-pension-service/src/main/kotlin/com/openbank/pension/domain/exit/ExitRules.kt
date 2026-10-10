// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.domain.exit

import com.openbank.pension.domain.model.PayoutForm
import java.math.BigDecimal

/**
 * The exit half of a jurisdiction pack (ADR-0334 §3/§4 steps 6–8, slice S5): early termination,
 * regular payout, partial withdrawal and death. Additive to the pack schema — a pack without an
 * `exit` block cannot be terminated, paid out or settled on death, and says so (fails closed)
 * instead of guessing a statutory value.
 *
 * Every number here is LEGAL CONTENT carried by the pack and reviewed per pack version; nothing in
 * Kotlin names a country or a rate.
 */
data class ExitRules(
    val termination: TerminationRules,
    val tax: TaxRules,
    val partialWithdrawal: PartialWithdrawalRules? = null,
    val phasedWithdrawal: InstallmentRules? = null,
    val fixedPeriodPension: InstallmentRules? = null,
    val death: DeathRules,
    /** Annuity purchase from a partner insurer (#12383); absent = the premium returns to the contract. */
    val annuity: com.openbank.pension.domain.annuity.AnnuityPackRules? = null,
)

/** Early termination on client notice. */
data class TerminationRules(
    /** Days between the signed notice and the payout. */
    val noticePeriodDays: Int,
    /** How long a binding preview (quote) can be signed. */
    val quoteValidityDays: Int,
    /** Proportional surrender fee on the redemption value. */
    val feeRate: BigDecimal = BigDecimal.ZERO,
    /** Fixed surrender fee, added to the proportional one. */
    val fixedFee: BigDecimal = BigDecimal.ZERO,
    /** Cap on the total surrender fee, if the pack caps it. */
    val maxFee: BigDecimal? = null,
) {
    init {
        require(noticePeriodDays >= 0) { "exit.termination.noticePeriodDays must be >= 0" }
        require(quoteValidityDays >= 1) { "exit.termination.quoteValidityDays must be >= 1" }
        listOfNotNull(feeRate, fixedFee, maxFee).forEach {
            require(it.signum() >= 0) { "exit.termination fees must not be negative" }
        }
        require(feeRate <= BigDecimal.ONE) { "exit.termination.feeRate must be <= 1" }
    }
}

/** What the taxable base of a payout is. */
enum class TaxBase {
    /** Nothing is withheld. */
    NONE,

    /** The payout less the participant's own non-deducted contributions and the state incentives. */
    GAINS,

    /** The whole payout. */
    GROSS,
}

data class FormTax(val base: TaxBase, val withholdingRate: BigDecimal = BigDecimal.ZERO) {
    init {
        require(withholdingRate.signum() >= 0 && withholdingRate <= BigDecimal.ONE) {
            "withholdingRate must be in [0, 1]"
        }
    }
}

/**
 * Recapture on early exit of a tax advantage already enjoyed: [rate] × the amounts of the last
 * [years] calendar years (the exit year included).
 */
data class RecaptureRule(val rate: BigDecimal, val years: Int) {
    init {
        require(rate.signum() >= 0 && rate <= BigDecimal.ONE) { "recapture rate must be in [0, 1]" }
        require(years >= 1) { "recapture years must be >= 1" }
    }
}

data class TaxRules(
    /** Withholding per payout form; a form missing here is withheld at nothing ONLY if listed as NONE. */
    val byForm: Map<PayoutForm, FormTax>,
    /** Previously deducted participant contributions, recaptured on early termination. */
    val deductionRecapture: RecaptureRule? = null,
    /** Employer contributions that were exempt, recaptured on early termination. */
    val employerExemptRecapture: RecaptureRule? = null,
) {
    fun forForm(form: PayoutForm): FormTax =
        requireNotNull(byForm[form]) { "the pack states no tax treatment for payout form $form" }
}

data class PartialWithdrawalRules(
    val allowed: Boolean,
    /** Whether the regular payout conditions (age, duration) must be met first. */
    val requiresPayoutConditions: Boolean,
    val minAmount: BigDecimal,
    /** Largest share of the current value one withdrawal may take. */
    val maxShareOfValue: BigDecimal,
    val maxPerCalendarYear: Int,
) {
    init {
        require(minAmount.signum() >= 0) { "partialWithdrawal.minAmount must not be negative" }
        require(maxShareOfValue.signum() > 0 && maxShareOfValue <= BigDecimal.ONE) {
            "partialWithdrawal.maxShareOfValue must be in (0, 1]"
        }
        require(maxPerCalendarYear >= 1) { "partialWithdrawal.maxPerCalendarYear must be >= 1" }
    }
}

/** Bounds on a phased withdrawal or a fixed-period pension, in months. */
data class InstallmentRules(val minMonths: Int, val maxMonths: Int) {
    init {
        require(minMonths >= 1 && maxMonths >= minMonths) { "installment months must satisfy 1 <= min <= max" }
    }
}

data class DeathRules(
    /** Without valid designations the whole value goes to the estate. */
    val estateWhenNoBeneficiary: Boolean,
    /** Whether incentives are clawed back on death (normally not). */
    val clawbackOnDeath: Boolean = false,
    /** Withholding on a beneficiary payout. */
    val beneficiaryTax: FormTax = FormTax(TaxBase.NONE),
)
