// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.domain.pack

import com.openbank.pension.domain.model.Limits
import com.openbank.pension.domain.model.PensionContract
import com.openbank.pension.domain.model.ProductLine
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.Period

/** No pack covers the requested (jurisdiction, product line, date) — the request fails closed. */
class PackNotFoundException(message: String) : IllegalArgumentException(message)

/**
 * The set of known pack versions. Lookup is effective-dated: a new contract resolves the version in
 * force on its date, and an existing contract always re-reads the exact version it pinned
 * (ADR-0212 D3) — a newer version never rewrites a contract's history.
 */
class JurisdictionPackRegistry(packs: List<JurisdictionPack>) {

    private val byKey: Map<PackKey, List<JurisdictionPack>> =
        packs.groupBy { it.key }.mapValues { (key, versions) ->
            require(versions.map { it.version }.toSet().size == versions.size) {
                "duplicate pack version for ${key.jurisdiction}/${key.productLine}"
            }
            versions.sortedBy { it.version }
        }

    fun all(): List<JurisdictionPack> = byKey.values.flatten()

    /** The highest version effective on [date]; fails closed when none is. */
    fun resolve(jurisdiction: String, productLine: ProductLine, date: LocalDate): JurisdictionPack =
        byKey[PackKey(jurisdiction, productLine)].orEmpty()
            .lastOrNull { it.isEffectiveOn(date) }
            ?: throw PackNotFoundException("no jurisdiction pack for $jurisdiction/$productLine effective on $date")

    /** The exact version [contract] pinned at creation. */
    fun pinnedFor(contract: PensionContract): JurisdictionPack =
        pinned(contract.jurisdiction, contract.productLine, contract.packVersion)

    /** The exact version a contract pinned. */
    fun pinned(jurisdiction: String, productLine: ProductLine, version: Int): JurisdictionPack =
        byKey[PackKey(jurisdiction, productLine)].orEmpty().firstOrNull { it.version == version }
            ?: throw PackNotFoundException("pinned pack $jurisdiction/$productLine v$version is not loaded")
}

data class EligibilityResult(val eligible: Boolean, val reasons: List<String>)

data class IncentiveResult(
    val incentiveId: String,
    val type: IncentiveType,
    val period: IncentivePeriod,
    /** The incentive amount for the evaluated period (MATCHING/FLAT), or the deductible/exempt base. */
    val amount: BigDecimal,
    /** Indicative tax saving for TAX_RELIEF, when the pack states an indicative rate. */
    val indicativeSaving: BigDecimal?,
    val claimChannel: ClaimChannel,
    val explanation: String,
)

/** Pure interpretation of a pack — no state, no framework, no country literal. */
object PackEvaluator {

    private const val MONEY_SCALE = 2

    fun checkEligibility(
        pack: JurisdictionPack,
        birthDate: LocalDate,
        residencyCountry: String?,
        residencyEvidence: Set<String>,
        hasGuardian: Boolean,
        on: LocalDate,
    ): EligibilityResult {
        val reasons = mutableListOf<String>()
        val age = Period.between(birthDate, on).years
        val rule = pack.eligibility
        if (age < rule.minAge && !(rule.minorsWithGuardian && hasGuardian)) {
            reasons += "participant age $age is below the minimum ${rule.minAge}"
        }
        if (rule.maxAge != null && age > rule.maxAge) {
            reasons += "participant age $age is above the maximum ${rule.maxAge}"
        }
        val residency = rule.residency
        if (residency.required) {
            val byCountry = residencyCountry != null && residencyCountry in residency.allowedCountries
            val byEvidence = residencyEvidence.any { it in residency.alternativeEvidence }
            if (!byCountry && !byEvidence) {
                reasons += "residency requirement not met"
            }
        }
        return EligibilityResult(reasons.isEmpty(), reasons)
    }

    /**
     * Evaluates every incentive rule of [pack] for one contribution.
     *
     * @param contribution the participant's contribution for one [period]
     * @param employerContributionAnnual employer contributions in the year, for EMPLOYER_EXEMPTION
     * @param sharedCapUsed annual cap already consumed by other products, per shared-cap group
     */
    fun evaluateIncentives(
        pack: JurisdictionPack,
        contribution: BigDecimal,
        period: IncentivePeriod,
        employerContributionAnnual: BigDecimal = BigDecimal.ZERO,
        sharedCapUsed: Map<String, BigDecimal> = emptyMap(),
    ): List<IncentiveResult> {
        Limits.requireAmount(contribution, "contribution")
        Limits.requireAmount(employerContributionAnnual, "employerContributionAnnual")
        require(sharedCapUsed.size <= Limits.MAX_ENTRIES) { "too many shared-cap entries" }
        sharedCapUsed.forEach { (group, used) -> Limits.requireAmount(used, "sharedCapUsed[$group]") }
        val annual = contribution.multiply(BigDecimal(period.periodsPerYear))
        return pack.incentives.map { rule ->
            when (rule.type) {
                IncentiveType.MATCHING -> matching(rule, toPeriod(annual, rule.period))
                IncentiveType.FLAT -> flat(rule, toPeriod(annual, rule.period))
                IncentiveType.TAX_RELIEF -> taxRelief(rule, annual, sharedCapUsed)
                IncentiveType.EMPLOYER_EXEMPTION -> employerExemption(rule, employerContributionAnnual, sharedCapUsed)
            }
        }
    }

    private fun toPeriod(annual: BigDecimal, period: IncentivePeriod): BigDecimal =
        annual.divide(BigDecimal(period.periodsPerYear), MONEY_SCALE, RoundingMode.HALF_EVEN)

    private fun matching(rule: IncentiveRule, perPeriod: BigDecimal): IncentiveResult {
        val min = rule.minContribution ?: BigDecimal.ZERO
        val amount = if (perPeriod < min) {
            BigDecimal.ZERO
        } else {
            perPeriod.multiply(rule.rate!!).min(rule.amountCap!!)
        }
        return IncentiveResult(
            rule.id,
            rule.type,
            rule.period,
            money(amount),
            null,
            rule.claimChannel,
            if (perPeriod < min) {
                "contribution below the minimum $min per ${rule.period}"
            } else {
                "${rule.rate} of the contribution, capped at ${rule.amountCap} per ${rule.period}"
            },
        )
    }

    private fun flat(rule: IncentiveRule, perPeriod: BigDecimal): IncentiveResult {
        val min = rule.minContribution ?: BigDecimal.ZERO
        val amount = if (perPeriod < min) BigDecimal.ZERO else rule.flatAmount!!
        return IncentiveResult(
            rule.id,
            rule.type,
            rule.period,
            money(amount),
            null,
            rule.claimChannel,
            "flat ${rule.flatAmount} per ${rule.period} from a contribution of $min",
        )
    }

    private fun remainingCap(rule: IncentiveRule, sharedCapUsed: Map<String, BigDecimal>): BigDecimal {
        val used = rule.sharedCapGroup?.let { sharedCapUsed[it] } ?: BigDecimal.ZERO
        return (rule.annualCap!! - used).max(BigDecimal.ZERO)
    }

    private fun taxRelief(
        rule: IncentiveRule,
        annual: BigDecimal,
        sharedCapUsed: Map<String, BigDecimal>,
    ): IncentiveResult {
        val above = (annual - (rule.threshold ?: BigDecimal.ZERO)).max(BigDecimal.ZERO)
        val deductible = above.min(remainingCap(rule, sharedCapUsed))
        val saving = rule.indicativeTaxRate?.let { money(deductible.multiply(it)) }
        return IncentiveResult(
            rule.id,
            rule.type,
            IncentivePeriod.YEAR,
            money(deductible),
            saving,
            rule.claimChannel,
            "annual contribution above ${rule.threshold ?: BigDecimal.ZERO}, up to ${rule.annualCap}" +
                (rule.sharedCapGroup?.let { " shared across group '$it'" } ?: ""),
        )
    }

    private fun employerExemption(
        rule: IncentiveRule,
        employerAnnual: BigDecimal,
        sharedCapUsed: Map<String, BigDecimal>,
    ): IncentiveResult = IncentiveResult(
        rule.id,
        rule.type,
        IncentivePeriod.YEAR,
        money(employerAnnual.min(remainingCap(rule, sharedCapUsed))),
        null,
        rule.claimChannel,
        "employer contributions exempt up to ${rule.annualCap}" +
            (rule.sharedCapGroup?.let { " shared across group '$it'" } ?: ""),
    )

    internal fun money(value: BigDecimal): BigDecimal = value.setScale(MONEY_SCALE, RoundingMode.HALF_EVEN)
}
