// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.domain.exit

import com.openbank.pension.domain.model.PayoutForm
import com.openbank.pension.domain.model.PensionContract
import com.openbank.pension.domain.pack.JurisdictionPack
import java.math.BigDecimal
import java.time.LocalDate
import java.time.Period
import java.time.temporal.ChronoUnit

/**
 * The incentive and tax history of one contract, as the contribution/incentive ledger (slice S3)
 * reports it through `IncentiveClawbackPort`. Amounts per calendar year where a recapture rule
 * needs the year.
 */
data class IncentiveBalance(
    /** State incentives that must be returned on an early exit (the S3 clawback balance). */
    val stateIncentivesToReturn: BigDecimal,
    /** State incentives received in total; part of the non-taxable base of a regular payout. */
    val stateIncentivesReceived: BigDecimal,
    /** Participant contributions claimed as a tax deduction, per calendar year. */
    val deductedContributionsByYear: Map<Int, BigDecimal>,
    /** Employer contributions that were tax-exempt, per calendar year. */
    val employerExemptByYear: Map<Int, BigDecimal>,
    /** The participant's own contributions NOT deducted — already taxed, so never taxed again. */
    val ownContributionsNotDeducted: BigDecimal,
) {
    init {
        val all = listOf(stateIncentivesToReturn, stateIncentivesReceived, ownContributionsNotDeducted) +
            deductedContributionsByYear.values + employerExemptByYear.values
        require(all.all { it.signum() >= 0 }) { "incentive balance amounts must not be negative" }
    }

    companion object {
        val EMPTY = IncentiveBalance(BigDecimal.ZERO, BigDecimal.ZERO, emptyMap(), emptyMap(), BigDecimal.ZERO)
    }
}

data class PayoutEligibility(
    val conditionsMet: Boolean,
    val ageYears: Int,
    val durationMonths: Long,
    val reasons: List<String>,
)

/**
 * The binding amounts of an early termination. Every component is rounded once ([ExitMoney]); the
 * net is their exact difference, floored at zero — a [shortfall] is what the pot could not cover
 * and stays owed outside this payout.
 */
data class TerminationQuote(
    val redemptionValue: BigDecimal,
    val surrenderFee: BigDecimal,
    val incentiveReturn: BigDecimal,
    val deductionRecapture: BigDecimal,
    val employerExemptRecapture: BigDecimal,
    val netPayout: BigDecimal,
    val shortfall: BigDecimal,
    val currency: String,
    val packVersion: Int,
) {
    val totalDeductions: BigDecimal
        get() = surrenderFee + incentiveReturn + deductionRecapture + employerExemptRecapture

    /** Canonical text the participant signs (SCA dynamic linking); stable field order. */
    fun canonical(): String = listOf(
        redemptionValue,
        surrenderFee,
        incentiveReturn,
        deductionRecapture,
        employerExemptRecapture,
        netPayout,
        shortfall,
    ).joinToString("|") { it.toPlainString() } + "|$currency|v$packVersion"
}

/** The binding amounts of a regular payout or partial withdrawal. */
data class PayoutQuote(
    val form: PayoutForm,
    val currentValue: BigDecimal,
    val grossAmount: BigDecimal,
    val taxBase: TaxBase,
    val taxableAmount: BigDecimal,
    val taxWithheld: BigDecimal,
    val netAmount: BigDecimal,
    val currency: String,
    val packVersion: Int,
    /** Months of the schedule for phased withdrawal / fixed-period pension; null otherwise. */
    val months: Int? = null,
) {
    fun canonical(): String = listOf(currentValue, grossAmount, taxableAmount, taxWithheld, netAmount)
        .joinToString("|") { it.toPlainString() } + "|$form|$currency|v$packVersion|${months ?: 0}"
}

fun JurisdictionPack.exitRules(): ExitRules = exit ?: error(
    "pack $jurisdiction/$productLine v$version defines no exit rules; termination and payout are unavailable",
)

object ExitCalculator {

    fun eligibility(contract: PensionContract, pack: JurisdictionPack, on: LocalDate): PayoutEligibility {
        val age = Period.between(contract.participantBirthDate, on).years
        val months = contract.startDate?.let { ChronoUnit.MONTHS.between(it, on) } ?: 0L
        val reasons = buildList {
            if (age < pack.payout.minAge) add("age $age is below the payout minimum ${pack.payout.minAge}")
            if (months < pack.payout.minDurationMonths) {
                add("contract duration $months months is below the minimum ${pack.payout.minDurationMonths}")
            }
        }
        return PayoutEligibility(reasons.isEmpty(), age, months, reasons)
    }

    fun terminationQuote(
        pack: JurisdictionPack,
        redemptionValue: BigDecimal,
        balance: IncentiveBalance,
        exitYear: Int,
    ): TerminationQuote {
        require(redemptionValue.signum() >= 0) { "redemption value must not be negative" }
        val rules = pack.exitRules()
        val value = ExitMoney.round(redemptionValue)
        val proportional = value.multiply(rules.termination.feeRate)
        val rawFee = proportional + rules.termination.fixedFee
        val fee = ExitMoney.round(rules.termination.maxFee?.let { rawFee.min(it) } ?: rawFee)
        val incentiveReturn = ExitMoney.round(balance.stateIncentivesToReturn)
        val deductionRecapture = recapture(rules.tax.deductionRecapture, balance.deductedContributionsByYear, exitYear)
        val employerRecapture = recapture(rules.tax.employerExemptRecapture, balance.employerExemptByYear, exitYear)
        val deductions = fee + incentiveReturn + deductionRecapture + employerRecapture
        val net = (value - deductions).max(BigDecimal.ZERO).setScale(ExitMoney.SCALE)
        val shortfall = (deductions - value).max(BigDecimal.ZERO).setScale(ExitMoney.SCALE)
        return TerminationQuote(
            redemptionValue = value,
            surrenderFee = fee,
            incentiveReturn = incentiveReturn,
            deductionRecapture = deductionRecapture,
            employerExemptRecapture = employerRecapture,
            netPayout = net,
            shortfall = shortfall,
            currency = pack.currency,
            packVersion = pack.version,
        )
    }

    /**
     * Withholding on [gross] taken from a pot worth [currentValue]. The GAINS base is computed for
     * the whole pot and prorated by the share withdrawn, so a partial withdrawal is taxed on its
     * share of the gains and never on the participant's already-taxed contributions.
     */
    fun payoutQuote(
        pack: JurisdictionPack,
        form: PayoutForm,
        currentValue: BigDecimal,
        gross: BigDecimal,
        balance: IncentiveBalance,
        months: Int? = null,
    ): PayoutQuote = taxed(form, rulesTax(pack, form), currentValue, gross, balance, pack, months)

    fun beneficiaryQuote(
        pack: JurisdictionPack,
        currentValue: BigDecimal,
        gross: BigDecimal,
        balance: IncentiveBalance,
    ): PayoutQuote =
        taxed(PayoutForm.LUMP_SUM, pack.exitRules().death.beneficiaryTax, currentValue, gross, balance, pack, null)

    private fun rulesTax(pack: JurisdictionPack, form: PayoutForm): FormTax {
        require(form in pack.payout.allowedForms) {
            "payout form $form is not allowed under ${pack.jurisdiction}/${pack.productLine} v${pack.version}"
        }
        return pack.exitRules().tax.forForm(form)
    }

    @Suppress("LongParameterList")
    private fun taxed(
        form: PayoutForm,
        tax: FormTax,
        currentValue: BigDecimal,
        gross: BigDecimal,
        balance: IncentiveBalance,
        pack: JurisdictionPack,
        months: Int?,
    ): PayoutQuote {
        val value = ExitMoney.round(currentValue)
        val amount = ExitMoney.round(gross)
        require(amount.signum() > 0) { "payout amount must be positive" }
        require(amount <= value) { "payout amount $amount exceeds the current value $value" }
        val potBase = when (tax.base) {
            TaxBase.NONE -> BigDecimal.ZERO
            TaxBase.GROSS -> value
            TaxBase.GAINS -> (value - balance.ownContributionsNotDeducted - balance.stateIncentivesReceived)
                .max(BigDecimal.ZERO)
        }
        val taxable = if (value.signum() == 0) {
            BigDecimal.ZERO.setScale(ExitMoney.SCALE)
        } else {
            ExitMoney.round(potBase.multiply(amount).divide(value, MathContextHolder.CONTEXT))
        }
        val withheld = ExitMoney.round(taxable.multiply(tax.withholdingRate))
        return PayoutQuote(
            form = form,
            currentValue = value,
            grossAmount = amount,
            taxBase = tax.base,
            taxableAmount = taxable,
            taxWithheld = withheld,
            netAmount = amount - withheld,
            currency = pack.currency,
            packVersion = pack.version,
            months = months,
        )
    }

    private fun recapture(rule: RecaptureRule?, byYear: Map<Int, BigDecimal>, exitYear: Int): BigDecimal {
        if (rule == null) return BigDecimal.ZERO.setScale(ExitMoney.SCALE)
        val window = byYear.filterKeys { it > exitYear - rule.years && it <= exitYear }.values
        return ExitMoney.round(window.fold(BigDecimal.ZERO, BigDecimal::add).multiply(rule.rate))
    }
}

/** Intermediate precision for prorating; the result is rounded once by [ExitMoney]. */
internal object MathContextHolder {
    val CONTEXT: java.math.MathContext = java.math.MathContext.DECIMAL128
}
