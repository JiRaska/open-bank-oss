// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.domain.maintenance

import com.openbank.pension.domain.model.ContractStatus
import com.openbank.pension.domain.model.ContributionFrequency
import com.openbank.pension.domain.model.Limits
import com.openbank.pension.domain.model.PensionContract
import com.openbank.pension.domain.pack.ContributionLimits
import com.openbank.pension.domain.pack.IncentivePeriod
import com.openbank.pension.domain.pack.IncentiveType
import com.openbank.pension.domain.pack.JurisdictionPack
import com.openbank.pension.domain.pack.PackEvaluator
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class ScheduleVersionStatus {
    /** Agreed; in force from its effective date. */
    SCHEDULED,

    /** Replaced by a later change before its effective date; kept as history, never in force. */
    SUPERSEDED,
}

/** The participant's request: amount, frequency, collection day, optional earliest start (#12376). */
data class ScheduleChangeRequest(
    val amount: BigDecimal,
    val frequency: ContributionFrequency,
    val dayOfMonth: Int,
    val startDate: LocalDate? = null,
    /** Must be true when the change lowers the state incentives the participant would receive. */
    val acknowledgeIncentiveReduction: Boolean = false,
)

/** Annual MATCHING/FLAT incentive before and after a change, and why it moved. */
data class IncentiveImpact(val annualBefore: BigDecimal, val annualAfter: BigDecimal, val warnings: List<String>) {
    val reduces: Boolean get() = annualAfter < annualBefore
}

/**
 * A fully validated change, not yet agreed. [documentSha256] is what the participant signs under
 * SCA (dynamic linking): it covers every value that becomes binding AND the history version it was
 * computed against, so a stale preview cannot be signed into a newer history.
 */
data class PlannedScheduleChange(
    val contractId: UUID,
    val baseSeq: Int,
    val amount: BigDecimal,
    val employerAmount: BigDecimal,
    val currency: String,
    val frequency: ContributionFrequency,
    val dayOfMonth: Int,
    val effectiveFrom: LocalDate,
    val impact: IncentiveImpact,
) {
    val documentSha256: String
        get() = Sha256.hex(
            listOf(
                "pension-contribution-schedule-change/v1",
                contractId,
                baseSeq,
                amount.stripTrailingZeros().toPlainString(),
                employerAmount.stripTrailingZeros().toPlainString(),
                currency,
                frequency,
                dayOfMonth,
                effectiveFrom,
            ).joinToString("|"),
        )
}

data class ScheduleVersion(
    val seq: Int,
    val amount: BigDecimal,
    val employerAmount: BigDecimal,
    val currency: String,
    val frequency: ContributionFrequency,
    val dayOfMonth: Int,
    val effectiveFrom: LocalDate,
    val status: ScheduleVersionStatus,
    val documentSha256: String,
    val scaChallengeId: String,
    val idempotencyKey: String,
    val changedAt: Instant,
) {
    init {
        require(seq >= 1) { "schedule version seq starts at 1" }
        Limits.requireAmount(amount, "contribution amount")
        require(dayOfMonth in 1..ContributionLimits.LAST_SAFE_DAY) { "dayOfMonth must be 1..28" }
    }
}

/**
 * The append-only history of a contract's contribution schedule (ADR-0334, #12376). Every
 * invariant of a change lives here, not in the service:
 *
 * - only a live contract (PENDING_ACTIVATION, ACTIVE, SUSPENDED) can change its schedule;
 * - the pinned pack must declare [ContributionLimits] (fail closed), the frequency must be allowed,
 *   the monthly equivalent must lie within the pack's bounds, the day within its window;
 * - a change takes effect from the NEXT collection cycle — never the one already in progress — so
 *   [PlannedScheduleChange.effectiveFrom] is the first collection day at least `changeNoticeDays`
 *   ahead (and not before a requested start date);
 * - a change that lowers the state incentives must be acknowledged;
 * - a newer change SUPERSEDES a not-yet-effective one; nothing is ever deleted or rewritten
 *   except that status, so the history shows every agreement the participant signed.
 */
data class ContributionScheduleHistory(val contractId: UUID, val versions: List<ScheduleVersion>) {
    init {
        require(versions.map { it.seq } == (1..versions.size).toList()) { "schedule versions must be 1..n in order" }
        require(versions.map { it.idempotencyKey }.toSet().size == versions.size) { "idempotency keys are unique" }
    }

    val latestSeq: Int get() = versions.size

    fun byIdempotencyKey(key: String): ScheduleVersion? = versions.firstOrNull { it.idempotencyKey == key }

    /** The agreed version in force on [date], or null while the contract's original schedule applies. */
    fun inForceOn(date: LocalDate): ScheduleVersion? =
        versions.filter { it.status == ScheduleVersionStatus.SCHEDULED && !it.effectiveFrom.isAfter(date) }
            .maxByOrNull { it.seq }

    /** The agreed change still waiting for its effective date, if any (at most one). */
    fun pendingAfter(date: LocalDate): ScheduleVersion? =
        versions.filter { it.status == ScheduleVersionStatus.SCHEDULED && it.effectiveFrom.isAfter(date) }
            .maxByOrNull { it.seq }

    fun plan(
        contract: PensionContract,
        pack: JurisdictionPack,
        request: ScheduleChangeRequest,
        today: LocalDate,
    ): PlannedScheduleChange {
        require(contract.id == contractId) { "history belongs to another contract" }
        check(contract.status in CHANGEABLE) {
            "the contribution schedule cannot change on a ${contract.status} contract"
        }
        val limits = checkNotNull(pack.contributionLimits) {
            "pack ${pack.jurisdiction}/${pack.productLine} v${pack.version} declares no contribution limits"
        }
        Limits.requireAmount(request.amount, "amount")
        require(request.frequency in limits.allowedFrequencies) {
            "frequency ${request.frequency} is not allowed; allowed: ${limits.allowedFrequencies.sorted()}"
        }
        require(request.dayOfMonth in limits.minDayOfMonth..limits.maxDayOfMonth) {
            "dayOfMonth must be ${limits.minDayOfMonth}..${limits.maxDayOfMonth}"
        }
        val monthly = monthlyEquivalent(request.amount, request.frequency)
        require(monthly >= limits.minMonthly) {
            "the contribution is below the pack minimum of ${limits.minMonthly} ${pack.currency} per month"
        }
        require(limits.maxMonthly == null || monthly <= limits.maxMonthly) {
            "the contribution exceeds the pack maximum of ${limits.maxMonthly} ${pack.currency} per month"
        }
        request.startDate?.let { start ->
            require(!start.isBefore(today)) { "startDate cannot be in the past" }
            require(!start.isAfter(today.plusDays(limits.maxStartAheadDays.toLong()))) {
                "startDate may be at most ${limits.maxStartAheadDays} days ahead"
            }
        }
        val current = pendingAfter(today) ?: inForceOn(today)
        val currentAmount = current?.amount ?: contract.schedule.amount
        val currentFrequency = current?.frequency ?: contract.schedule.frequency
        require(
            current == null ||
                current.amount.compareTo(request.amount) != 0 ||
                current.frequency != request.frequency ||
                current.dayOfMonth != request.dayOfMonth,
        ) { "the requested schedule is the one already agreed" }
        val impact = incentiveImpact(pack, currentAmount, currentFrequency, request.amount, request.frequency)
        require(!impact.reduces || request.acknowledgeIncentiveReduction) {
            "this change lowers the annual state incentive from ${impact.annualBefore} to ${impact.annualAfter}; " +
                "set acknowledgeIncentiveReduction to proceed"
        }
        return PlannedScheduleChange(
            contractId = contractId,
            baseSeq = latestSeq,
            amount = request.amount,
            employerAmount = current?.employerAmount ?: contract.schedule.employerAmount,
            currency = contract.schedule.currency,
            frequency = request.frequency,
            dayOfMonth = request.dayOfMonth,
            effectiveFrom = nextCycleStart(today, request.dayOfMonth, request.startDate, limits.changeNoticeDays),
            impact = impact,
        )
    }

    /**
     * Agrees [plan] (already SCA-verified by the caller). Re-checks, on THIS history, that nothing
     * was agreed since the plan was computed and that its effective date is still ahead.
     */
    fun record(
        plan: PlannedScheduleChange,
        scaChallengeId: String,
        idempotencyKey: String,
        today: LocalDate,
        now: Instant,
    ): ContributionScheduleHistory {
        require(plan.contractId == contractId) { "plan belongs to another contract" }
        check(plan.baseSeq == latestSeq) { "the schedule changed since this change was previewed; preview again" }
        check(plan.effectiveFrom.isAfter(today)) { "this change would take effect in a cycle already started" }
        require(scaChallengeId.isNotBlank()) { "scaChallengeId is required" }
        val superseded = versions.map {
            if (it.status == ScheduleVersionStatus.SCHEDULED && it.effectiveFrom.isAfter(today)) {
                it.copy(status = ScheduleVersionStatus.SUPERSEDED)
            } else {
                it
            }
        }
        val version = ScheduleVersion(
            seq = latestSeq + 1,
            amount = plan.amount,
            employerAmount = plan.employerAmount,
            currency = plan.currency,
            frequency = plan.frequency,
            dayOfMonth = plan.dayOfMonth,
            effectiveFrom = plan.effectiveFrom,
            status = ScheduleVersionStatus.SCHEDULED,
            documentSha256 = plan.documentSha256,
            scaChallengeId = scaChallengeId,
            idempotencyKey = idempotencyKey,
            changedAt = now,
        )
        return copy(versions = superseded + version)
    }

    companion object {
        val CHANGEABLE = setOf(ContractStatus.PENDING_ACTIVATION, ContractStatus.ACTIVE, ContractStatus.SUSPENDED)
        private const val MONTHS = 12
        private const val SCALE = 4

        fun empty(contractId: UUID) = ContributionScheduleHistory(contractId, emptyList())

        fun monthlyEquivalent(amount: BigDecimal, frequency: ContributionFrequency): BigDecimal =
            amount.multiply(BigDecimal(periodOf(frequency).periodsPerYear))
                .divide(BigDecimal(MONTHS), SCALE, RoundingMode.HALF_EVEN)

        fun periodOf(frequency: ContributionFrequency): IncentivePeriod = when (frequency) {
            ContributionFrequency.MONTHLY -> IncentivePeriod.MONTH
            ContributionFrequency.QUARTERLY -> IncentivePeriod.QUARTER
            ContributionFrequency.ANNUALLY -> IncentivePeriod.YEAR
        }

        /** The first `dayOfMonth` on or after `max(today + notice, startDate)`: always a future cycle. */
        fun nextCycleStart(today: LocalDate, dayOfMonth: Int, startDate: LocalDate?, noticeDays: Int): LocalDate {
            val earliest = listOfNotNull(today.plusDays(maxOf(noticeDays, 1).toLong()), startDate).max()
            val candidate = earliest.withDayOfMonth(dayOfMonth)
            return if (candidate.isBefore(earliest)) candidate.plusMonths(1) else candidate
        }

        /** Annual MATCHING + FLAT incentive of each schedule under the pack's own (banded) rules. */
        fun incentiveImpact(
            pack: JurisdictionPack,
            beforeAmount: BigDecimal,
            beforeFrequency: ContributionFrequency,
            afterAmount: BigDecimal,
            afterFrequency: ContributionFrequency,
        ): IncentiveImpact {
            fun annual(amount: BigDecimal, frequency: ContributionFrequency) =
                PackEvaluator.evaluateIncentives(pack, amount, periodOf(frequency))
                    .filter { it.type == IncentiveType.MATCHING || it.type == IncentiveType.FLAT }
            val before = annual(beforeAmount, beforeFrequency)
            val after = annual(afterAmount, afterFrequency)
            fun total(results: List<com.openbank.pension.domain.pack.IncentiveResult>) =
                results.fold(BigDecimal.ZERO) { acc, r -> acc + r.amount.multiply(BigDecimal(r.period.periodsPerYear)) }
            val warnings = after.filter { it.amount.signum() == 0 }.map { "${it.incentiveId}: ${it.explanation}" }
            return IncentiveImpact(total(before), total(after), warnings)
        }
    }
}

internal object Sha256 {
    fun hex(text: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
