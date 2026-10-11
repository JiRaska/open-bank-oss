// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.domain.pack

import com.openbank.pension.domain.exit.ExitRules
import com.openbank.pension.domain.model.PayoutForm
import com.openbank.pension.domain.model.ProductLine
import java.math.BigDecimal
import java.time.LocalDate

/**
 * A pension jurisdiction pack (ADR-0334 §3): the statutory rules for one `(jurisdiction, product
 * line)` pair as versioned, effective-dated DATA, in the shape ADR-0212 established for credit.
 *
 * The domain only ever evaluates a pack; it never names a country. A new jurisdiction is a new
 * pack file, not a code change. Every statutory value in a pack is legal content and carries the
 * pack's [legalReview] status — a pack still marked [LegalReviewStatus.REQUIRES_LEGAL_REVIEW] is a
 * reference implementation, not advice.
 *
 * The constructor validates the closed schema: a pack that cannot be evaluated unambiguously is
 * refused at load, never coerced into a guessed rule (ADR-0212 D2).
 */
data class JurisdictionPack(
    val jurisdiction: String,
    val productLine: ProductLine,
    val version: Int,
    val effectiveFrom: LocalDate,
    val effectiveTo: LocalDate? = null,
    val currency: String,
    val legalReview: LegalReview,
    val eligibility: Eligibility,
    val permittedProviderTypes: Set<ProviderType>,
    val incentives: List<IncentiveRule> = emptyList(),
    val payout: PayoutConditions,
    val transfer: TransferRules,
    /** Termination, payout and death rules (slice S5); absent = exits fail closed. */
    val exit: ExitRules? = null,
    /** Participant-settable schedule bounds (#12376); absent = schedule changes fail closed. */
    val contributionLimits: ContributionLimits? = null,
) {
    init {
        require(jurisdiction.isNotBlank()) { "pack jurisdiction must not be blank" }
        require(version >= 1) { "pack version must be >= 1" }
        require(currency.length == ISO_CURRENCY_LENGTH) { "pack currency must be an ISO 4217 code" }
        require(effectiveTo == null || !effectiveTo.isBefore(effectiveFrom)) {
            "pack effectiveTo must not be before effectiveFrom"
        }
        require(permittedProviderTypes.isNotEmpty()) { "pack must permit at least one provider type" }
        require(incentives.map { it.id }.toSet().size == incentives.size) { "incentive ids must be unique" }
        incentives.forEach { rule ->
            rule.clawback?.let { claw ->
                require(claw.recaptureYears == null || claw.recaptureYears > 0) {
                    "incentive ${rule.id}: clawback recaptureYears must be > 0"
                }
            }
        }
    }

    val key: PackKey get() = PackKey(jurisdiction, productLine)

    /** True when [date] falls inside this version's validity window; [effectiveTo] is INCLUSIVE (the last day). */
    fun isEffectiveOn(date: LocalDate): Boolean =
        !date.isBefore(effectiveFrom) && (effectiveTo == null || !date.isAfter(effectiveTo))

    private companion object {
        const val ISO_CURRENCY_LENGTH = 3
    }
}

data class PackKey(val jurisdiction: String, val productLine: ProductLine)

enum class LegalReviewStatus { REQUIRES_LEGAL_REVIEW, REVIEWED }

/** [sources]: where each modelled value comes from (public source + retrieval date), for the reviewer. */
data class LegalReview(val status: LegalReviewStatus, val note: String? = null, val sources: List<String>? = null)

/** Which legal entity type may act as provider of the product line (ADR-0334 §2a). */
enum class ProviderType { PENSION_COMPANY, BANK, INVESTMENT_FIRM, MANAGEMENT_COMPANY, INSURANCE_COMPANY }

data class Eligibility(
    val minAge: Int,
    val maxAge: Int? = null,
    val residency: ResidencyRule,
    /** Whether a minor may hold a contract through a legal guardian. */
    val minorsWithGuardian: Boolean = false,
) {
    init {
        require(minAge >= 0) { "eligibility minAge must be >= 0" }
        require(maxAge == null || maxAge >= minAge) { "eligibility maxAge must be >= minAge" }
    }
}

/**
 * Residency is satisfied by residing in one of [allowedCountries], or — where the pack allows it —
 * by one of [alternativeEvidence] (e.g. participation in a public health-insurance scheme).
 */
data class ResidencyRule(
    val required: Boolean,
    val allowedCountries: Set<String> = emptySet(),
    val alternativeEvidence: Set<String> = emptySet(),
) {
    init {
        require(!required || allowedCountries.isNotEmpty() || alternativeEvidence.isNotEmpty()) {
            "a required residency rule must name allowed countries or alternative evidence"
        }
    }
}

enum class IncentiveType { MATCHING, FLAT, TAX_RELIEF, EMPLOYER_EXEMPTION }

@Suppress("MagicNumber") // calendar facts, not tunables
enum class IncentivePeriod(val periodsPerYear: Int) {
    MONTH(12),
    QUARTER(4),
    YEAR(1),
}

enum class ClaimChannel { STATE_AGENCY_BATCH, TAX_RETURN, PAYROLL, NONE }

/**
 * One incentive rule of a pack, as a closed generic model (ADR-0334 §3). Which fields a rule needs
 * depends on its [type]; [init] refuses a rule missing one, so evaluation never has to guess.
 *
 * - `MATCHING` — `rate` × contribution, once the contribution reaches `minContribution`, capped at
 *   `amountCap` per [period].
 * - `FLAT` — `flatAmount` per [period] once the contribution reaches `minContribution`.
 * - `TAX_RELIEF` — the part of the ANNUAL contribution above `threshold` is deductible up to
 *   `annualCap`; a cap shared across products ([sharedCapGroup]) is reduced by what the participant
 *   already used elsewhere. `indicativeTaxRate` turns the deduction into an indicative saving.
 * - `EMPLOYER_EXEMPTION` — employer contributions are exempt up to `annualCap`, shared by group.
 */
data class IncentiveRule(
    val id: String,
    val type: IncentiveType,
    val period: IncentivePeriod = IncentivePeriod.YEAR,
    val rate: BigDecimal? = null,
    val minContribution: BigDecimal? = null,
    val amountCap: BigDecimal? = null,
    val flatAmount: BigDecimal? = null,
    val threshold: BigDecimal? = null,
    val annualCap: BigDecimal? = null,
    val sharedCapGroup: String? = null,
    val indicativeTaxRate: BigDecimal? = null,
    val claimChannel: ClaimChannel = ClaimChannel.NONE,
    val clawback: ClawbackRule? = null,
    /** Progressive matching bands (ADR-0334 S3); when present they replace the single [rate]. */
    val bands: List<IncentiveBand>? = null,
    /** Wire format of the claim channel adapter that files this incentive (ADR-0334 S3). */
    val claimFormat: String? = null,
    /**
     * When set, a periodic incentive is rounded DOWN to a whole multiple of this unit after the
     * cap (CZ: whole crowns, Act 427/2011 §14(4); docs/research/cz-state-pension-contribution.md A5).
     */
    val roundDownToUnit: BigDecimal? = null,
) {
    init {
        require(id.isNotBlank()) { "incentive id must not be blank" }
        require(roundDownToUnit == null || roundDownToUnit.signum() > 0) {
            "incentive $id: roundDownToUnit must be positive"
        }
        when (type) {
            IncentiveType.MATCHING -> {
                if (bands == null) requireField(rate, "rate") else IncentiveBand.validate(id, bands)
                requireField(amountCap, "amountCap")
            }
            IncentiveType.FLAT -> requireField(flatAmount, "flatAmount")
            IncentiveType.TAX_RELIEF -> requireField(annualCap, "annualCap")
            IncentiveType.EMPLOYER_EXEMPTION -> requireField(annualCap, "annualCap")
        }
        listOfNotNull(rate, minContribution, amountCap, flatAmount, threshold, annualCap, indicativeTaxRate)
            .forEach { require(it.signum() >= 0) { "incentive $id: amounts and rates must not be negative" } }
    }

    /** [amount] rounded down to [roundDownToUnit], or unchanged when the rule sets none. */
    fun roundDown(amount: BigDecimal): BigDecimal = roundDownToUnit?.let { unit ->
        amount.divide(unit, 0, java.math.RoundingMode.DOWN).multiply(unit)
    } ?: amount

    private fun requireField(value: Any?, name: String) =
        require(value != null) { "incentive $id of type $type requires '$name'" }
}

enum class ClawbackMode {
    /** Every incentive received under the contract is returned. */
    RETURN_ALL,

    /** Incentives of the last `recaptureYears` years are recaptured. */
    RECAPTURE_YEARS,
}

/** What happens to an incentive when the contract ends before the payout conditions are met. */
data class ClawbackRule(val mode: ClawbackMode, val recaptureYears: Int? = null) {
    init {
        require(mode != ClawbackMode.RECAPTURE_YEARS || recaptureYears != null) {
            "RECAPTURE_YEARS clawback requires recaptureYears"
        }
    }
}

data class PayoutConditions(
    val minAge: Int,
    val minDurationMonths: Int,
    val allowedForms: Set<PayoutForm>,
    val earlyWithdrawalAllowed: Boolean,
    /** Proportional fee on the surrendered value for an early exit, if the pack charges one. */
    val earlyExitFeeRate: BigDecimal? = null,
) {
    init {
        require(minAge >= 0) { "payout minAge must be >= 0" }
        require(minDurationMonths >= 0) { "payout minDurationMonths must be >= 0" }
        require(allowedForms.isNotEmpty()) { "payout must allow at least one form" }
    }
}

data class TransferRules(
    val allowed: Boolean,
    val freeAfterMonths: Int? = null,
    val maxFee: BigDecimal? = null,
    val deadlineDays: Int? = null,
    val carriesIncentiveHistory: Boolean = true,
)
