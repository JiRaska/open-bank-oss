// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.domain.incentive

import com.openbank.pension.domain.contribution.Contribution
import com.openbank.pension.domain.contribution.ContributionSource
import com.openbank.pension.domain.pack.ClaimChannel
import com.openbank.pension.domain.pack.ClawbackMode
import com.openbank.pension.domain.pack.IncentiveBand
import com.openbank.pension.domain.pack.IncentivePeriod
import com.openbank.pension.domain.pack.IncentiveRule
import com.openbank.pension.domain.pack.IncentiveType
import com.openbank.pension.domain.pack.JurisdictionPack
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.YearMonth
import java.util.UUID

/** A claim the engine says is due, before it is persisted as an [IncentiveClaim]. */
data class ClaimDraft(val incentiveId: String, val period: YearMonth, val basis: BigDecimal, val amount: BigDecimal)

/** One contract's input to the shared-cap allocation of a tax year. */
data class ContractYearInput(
    val contractId: UUID,
    val pack: JurisdictionPack,
    val participantAnnual: BigDecimal,
    /** Employer contributions of the year in value-date order — exemption is consumed first come, first served. */
    val employerContributions: List<Contribution>,
)

/** The engine's tax-side answer for one contract and year. */
data class TaxYearAllocation(
    val deductible: BigDecimal,
    val indicativeSaving: BigDecimal?,
    val usedElsewhere: Map<String, BigDecimal>,
    val employerExemptions: List<EmployerExemption>,
)

/**
 * The generic incentive engine (ADR-0334 §3, S3). It interprets the rules of a jurisdiction pack
 * against ACTUAL contributions — where S1's `PackEvaluator` projects a hypothetical one — and
 * never names a country: every number and every channel comes from the pack.
 *
 * Pure: no clock, no I/O. Everything it needs is an argument, which is what lets the property
 * tests drive it with thousands of generated inputs.
 */
@Suppress("TooManyFunctions")
object IncentiveEngine {

    private const val MONEY_SCALE = 2

    /** First month of the incentive period [month] belongs to. */
    fun periodStart(month: YearMonth, period: IncentivePeriod): YearMonth = when (period) {
        IncentivePeriod.MONTH -> month
        IncentivePeriod.QUARTER -> month.withMonth(((month.monthValue - 1) / QUARTER_MONTHS) * QUARTER_MONTHS + 1)
        IncentivePeriod.YEAR -> month.withMonth(1)
    }

    /** Rules of [pack] that are claimed per period through a state agency (MATCHING / FLAT). */
    fun claimableRules(pack: JurisdictionPack): List<IncentiveRule> = pack.incentives.filter {
        it.claimChannel == ClaimChannel.STATE_AGENCY_BATCH && it.type in PERIODIC_TYPES
    }

    /**
     * The incentive one period earns from [contributions] (only PARTICIPANT money counts; state and
     * employer money never earns a state match).
     */
    fun periodAmount(rule: IncentiveRule, contributions: List<Contribution>): BigDecimal {
        require(rule.type in PERIODIC_TYPES) { "rule ${rule.id} is not a periodic incentive" }
        val basis = participantSum(contributions)
        val min = rule.minContribution ?: BigDecimal.ZERO
        if (basis.signum() == 0 || basis < min) return money(BigDecimal.ZERO)
        val raw = when (rule.type) {
            IncentiveType.MATCHING -> IncentiveBand.matched(rule, basis).min(rule.amountCap!!)
            else -> rule.flatAmount!!
        }
        return money(rule.roundDown(raw))
    }

    /**
     * Claim drafts for the incentive period starting at [periodStart], one per claimable rule whose
     * period starts there and that earns a positive amount.
     */
    fun claimsFor(pack: JurisdictionPack, periodStart: YearMonth, contributions: List<Contribution>): List<ClaimDraft> =
        claimableRules(pack).filter { periodStart(periodStart, it.period) == periodStart }.mapNotNull { rule ->
            val inPeriod = contributions.filter { periodStart(it.period, rule.period) == periodStart }
            val amount = periodAmount(rule, inPeriod)
            if (amount.signum() > 0) ClaimDraft(rule.id, periodStart, participantSum(inPeriod), amount) else null
        }

    /**
     * Allocates the SHARED annual caps of one participant's tax year across all their contracts
     * (ADR-0334 §3: one deduction cap across products and providers). [external] is what the
     * participant declared as used at other providers; the remaining cap is then consumed by the
     * contracts in the order given, so the result is deterministic for a fixed order.
     *
     * Invariants the property tests hold it to: per shared group, the total allocated never exceeds
     * the cap less [external]; no contract is allocated more than its own rule would give it alone.
     */
    fun allocateTaxYear(
        contracts: List<ContractYearInput>,
        external: Map<String, BigDecimal>,
    ): Map<UUID, TaxYearAllocation> {
        val used = external.mapValues { (_, v) -> v.max(BigDecimal.ZERO) }.toMutableMap()
        return contracts.associate { input ->
            val before = used.toMap()
            var deductible = BigDecimal.ZERO
            var saving: BigDecimal? = null
            var exemptions = emptyList<EmployerExemption>()
            input.pack.incentives.forEach { rule ->
                when (rule.type) {
                    IncentiveType.TAX_RELIEF -> {
                        val above = (input.participantAnnual - (rule.threshold ?: BigDecimal.ZERO)).max(BigDecimal.ZERO)
                        val granted = above.min(remaining(rule, used))
                        consume(rule, used, granted)
                        deductible += granted
                        rule.indicativeTaxRate?.let { saving = (saving ?: BigDecimal.ZERO) + granted.multiply(it) }
                    }
                    IncentiveType.EMPLOYER_EXEMPTION -> {
                        exemptions = allocateEmployer(rule, input.employerContributions, used)
                    }
                    else -> Unit
                }
            }
            input.contractId to TaxYearAllocation(
                deductible = money(deductible),
                indicativeSaving = saving?.let(::money),
                usedElsewhere = before.mapValues { (_, v) -> money(v) },
                employerExemptions = exemptions,
            )
        }
    }

    private fun allocateEmployer(
        rule: IncentiveRule,
        contributions: List<Contribution>,
        used: MutableMap<String, BigDecimal>,
    ): List<EmployerExemption> {
        val byEmployer = linkedMapOf<UUID, Pair<BigDecimal, BigDecimal>>()
        contributions.filter { it.source == ContributionSource.EMPLOYER }.sortedBy { it.valueDate }.forEach { c ->
            val exempt = c.amount.min(remaining(rule, used))
            consume(rule, used, exempt)
            val employer = c.employerPartyId!!
            val (sum, ex) = byEmployer[employer] ?: (BigDecimal.ZERO to BigDecimal.ZERO)
            byEmployer[employer] = (sum + c.amount) to (ex + exempt)
        }
        return byEmployer.map { (employer, totals) ->
            EmployerExemption(employer, money(totals.first), money(totals.second), money(totals.first - totals.second))
        }
    }

    /**
     * What must go back if the contract ended on [onYear] before its payout conditions were met
     * (ADR-0334 §3 clawback; consumed by S5). State money comes from the [ledger] net balance;
     * tax relief is a recapture of the deductions the participant claimed, per [deductibleByYear].
     */
    fun clawback(
        pack: JurisdictionPack,
        ledger: List<IncentiveLedgerEntry>,
        deductibleByYear: Map<Int, BigDecimal>,
        onYear: Int,
    ): List<ClawbackItem> = pack.incentives.mapNotNull { rule ->
        val claw = rule.clawback ?: return@mapNotNull null
        val inWindow: (Int) -> Boolean = when (claw.mode) {
            ClawbackMode.RETURN_ALL -> { _ -> true }
            ClawbackMode.RECAPTURE_YEARS -> { year -> year > onYear - claw.recaptureYears!! }
        }
        when (rule.type) {
            IncentiveType.TAX_RELIEF -> {
                val years = deductibleByYear.filter { (y, v) -> inWindow(y) && v.signum() > 0 }
                val total = years.values.fold(BigDecimal.ZERO, BigDecimal::add)
                if (total.signum() >
                    0
                ) {
                    ClawbackItem(rule.id, ClawbackKind.TAX_RECAPTURE, money(total), years.keys)
                } else {
                    null
                }
            }
            IncentiveType.EMPLOYER_EXEMPTION -> null
            else -> {
                val entries = ledger.filter { it.incentiveId == rule.id && inWindow(it.taxYear) }
                val net = entries.fold(BigDecimal.ZERO) { a, e -> a + e.signed }
                if (net.signum() > 0) {
                    ClawbackItem(rule.id, ClawbackKind.RETURN_TO_AGENCY, money(net), entries.map { it.taxYear }.toSet())
                } else {
                    null
                }
            }
        }
    }

    private fun remaining(rule: IncentiveRule, used: Map<String, BigDecimal>): BigDecimal {
        val spent = rule.sharedCapGroup?.let { used[it] } ?: BigDecimal.ZERO
        return (rule.annualCap!! - spent).max(BigDecimal.ZERO)
    }

    private fun consume(rule: IncentiveRule, used: MutableMap<String, BigDecimal>, amount: BigDecimal) {
        rule.sharedCapGroup?.let { used[it] = (used[it] ?: BigDecimal.ZERO) + amount }
    }

    private fun participantSum(contributions: List<Contribution>): BigDecimal =
        contributions.filter { it.source == ContributionSource.PARTICIPANT }.fold(BigDecimal.ZERO) { a, c ->
            a +
                c.amount
        }

    fun money(value: BigDecimal): BigDecimal = value.setScale(MONEY_SCALE, RoundingMode.HALF_EVEN)

    private const val QUARTER_MONTHS = 3
    private val PERIODIC_TYPES = setOf(IncentiveType.MATCHING, IncentiveType.FLAT)
}
