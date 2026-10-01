// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.reserves

import com.openbank.risk.domain.curve.BigMath
import java.math.BigDecimal
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Whether a maintenance-period calendar was checked against the ČNB's published calendar. A
 * [SAMPLE_UNVERIFIED] calendar is a stand-in that makes the averaging computable; every result
 * computed on it says so, and it is never presented as the official calendar (ADR-0097).
 */
enum class CalendarStatus(val wire: String) {
    VERIFIED("verified"),
    SAMPLE_UNVERIFIED("sample-unverified"),
    ;

    companion object {
        fun parse(raw: String): CalendarStatus =
            entries.firstOrNull { it.wire == raw.trim().lowercase() || it.name == raw.trim() }
                ?: throw IllegalArgumentException(
                    "unknown maintenance-calendar status '$raw'; one of ${entries.joinToString { it.wire }}",
                )
    }
}

/**
 * One ČNB maintenance period: minimum reserves are met on the AVERAGE of end-of-day holdings over
 * [start]..[end] (both inclusive, calendar days). The requirement is computed from the reserve base
 * as of [baseReferenceDate].
 */
data class MaintenancePeriod(
    val id: String,
    val start: LocalDate,
    val end: LocalDate,
    val baseReferenceDate: LocalDate,
) {
    init {
        require(id.isNotBlank()) { "maintenance period needs an id" }
        require(!end.isBefore(start)) { "maintenance period $id ends ($end) before it starts ($start)" }
        require(!baseReferenceDate.isAfter(end)) {
            "maintenance period $id: base reference date $baseReferenceDate is after the period end $end"
        }
    }

    val days: Int get() = ChronoUnit.DAYS.between(start, end).toInt() + 1

    operator fun contains(date: LocalDate): Boolean = !date.isBefore(start) && !date.isAfter(end)
}

/** A versioned maintenance-period calendar (`openbank.risk.min-reserves.maintenance-calendar.*`). */
data class MaintenanceCalendar(
    val id: String,
    val version: String,
    val status: CalendarStatus,
    val source: String,
    val periods: List<MaintenancePeriod>,
) {
    init {
        require(id.isNotBlank() && version.isNotBlank()) { "maintenance calendar needs an id and a version" }
        require(periods.map { it.id }.toSet().size == periods.size) { "maintenance period ids must be unique" }
        periods.sortedBy { it.start }.zipWithNext().forEach { (a, b) ->
            require(b.start.isAfter(a.end)) { "maintenance periods ${a.id} and ${b.id} overlap" }
        }
    }

    fun find(periodId: String): MaintenancePeriod? = periods.firstOrNull { it.id == periodId }

    fun resolve(date: LocalDate): MaintenancePeriod? = periods.firstOrNull { date in it }
}

/**
 * One TIED_OUT snapshot day inside the period: the ČNB current-account holding that day, or null
 * when holdings are not stated (no GL account mapped as the ČNB current account).
 */
data class DailyHolding(val date: LocalDate, val runId: UUID, val holdings: BigDecimal?)

data class HoldingProposal(val date: LocalDate, val amount: BigDecimal)

/**
 * The averaging position of one period, evaluated on [evaluationDate]. Every stated number has a
 * `…NotStated` twin that says why it is absent; none is ever a stand-in zero (ADR-0097).
 */
data class PeriodAveraging(
    val period: MaintenancePeriod,
    val evaluationDate: LocalDate,
    val daysInPeriod: Int,
    /** Calendar days of the period up to and including [evaluationDate] (capped at the period end). */
    val daysElapsed: Int,
    val daysRemaining: Int,
    val days: List<DailyHolding>,
    /** Elapsed calendar days with no TIED_OUT snapshot. */
    val missingDays: List<LocalDate>,
    val requirement: BigDecimal?,
    val requirementNotStated: String?,
    val averageHoldings: BigDecimal?,
    val averageNotStated: String?,
    /** The average holding the REMAINING days must carry for the whole-period average to meet the requirement. */
    val remainingRequiredAverage: BigDecimal?,
    val proposal: List<HoldingProposal>?,
    val proposalNotStated: String?,
    /** Only once the period is over with every day covered and both sides stated; else null. */
    val requirementMet: Boolean?,
) {
    val daysWithData: Int get() = days.size

    /** days with data / days elapsed; null before the period starts (nothing is due yet). */
    val coverage: BigDecimal? get() =
        daysElapsed.takeIf { it > 0 }?.let { BigDecimal(daysWithData).divide(BigDecimal(it), BigMath.MC) }
}

/**
 * Maintenance-period averaging of ČNB minimum reserves (ADR-0315 D8).
 *
 * The requirement is met on the average of END-OF-DAY holdings over every CALENDAR day of the
 * period. A day with no TIED_OUT snapshot has no measured holding, so the running average is taken
 * over the days WITH a snapshot and [PeriodAveraging.coverage] says how many that is. The proposal
 * needs the holdings of EVERY elapsed day: with a gap, the sum already held is unknown, and a
 * proposal computed from a partial sum is a number nobody can stand behind, so it is not stated.
 *
 * Proposal: hold the same amount on each remaining day, namely
 * `(requirement × daysInPeriod − Σ held so far) / daysRemaining`, floored at zero (a negative
 * holding does not exist; a negative [PeriodAveraging.remainingRequiredAverage] means the
 * requirement is already met on average whatever is held from now on). This engine proposes; it
 * never moves money (ADR-0315 D8).
 */
object ReserveAveraging {

    const val NO_SNAPSHOT_YET =
        "No TIED_OUT snapshot falls in the elapsed part of the period, so there is no holding to average."

    const val PERIOD_NOT_STARTED = "The period has not started on the evaluation date; nothing is held yet."

    const val GAPS_IN_COVERAGE =
        "Some elapsed days have no TIED_OUT snapshot (see missingDays), so the sum held so far is unknown and " +
            "no proposal is computed from a partial sum."

    const val PERIOD_OVER = "The period is over on the evaluation date; there are no remaining days to propose for."

    @Suppress("LongParameterList") // each input is a distinct, independently not-stated fact
    fun compute(
        period: MaintenancePeriod,
        evaluationDate: LocalDate,
        requirement: BigDecimal?,
        requirementNotStated: String?,
        holdingsNotStated: String?,
        snapshots: List<DailyHolding>,
    ): PeriodAveraging {
        require((requirement == null) != (requirementNotStated == null)) {
            "exactly one of requirement and requirementNotStated must be given"
        }
        val lastElapsed = minOf(evaluationDate, period.end)
        val daysElapsed = elapsed(period, evaluationDate)
        val days = oneRunPerDay(snapshots.filter { it.date in period && !it.date.isAfter(lastElapsed) })
        val covered = days.map { it.date }.toSet()
        val missing = (0 until daysElapsed).map { period.start.plusDays(it.toLong()) }.filterNot { it in covered }
        val daysRemaining = period.days - daysElapsed

        val averageNotStated = averageNotStated(daysElapsed, holdingsNotStated, days.isEmpty())
        val held = if (averageNotStated == null) days.sumOf { requireNotNull(it.holdings) } else null
        val average = held?.divide(BigDecimal(days.size), BigMath.MC)
        val proposalNotStated = requirementNotStated ?: holdingsNotStated ?: when {
            daysRemaining == 0 -> PERIOD_OVER
            missing.isNotEmpty() -> GAPS_IN_COVERAGE
            else -> null
        }
        val remainingRequired = if (proposalNotStated != null) {
            null
        } else {
            requireNotNull(requirement).multiply(BigDecimal(period.days), BigMath.MC)
                .subtract(held ?: BigDecimal.ZERO)
                .divide(BigDecimal(daysRemaining), BigMath.MC)
        }
        return PeriodAveraging(
            period = period,
            evaluationDate = evaluationDate,
            daysInPeriod = period.days,
            daysElapsed = daysElapsed,
            daysRemaining = daysRemaining,
            days = days,
            missingDays = missing,
            requirement = requirement,
            requirementNotStated = requirementNotStated,
            averageHoldings = average,
            averageNotStated = averageNotStated,
            remainingRequiredAverage = remainingRequired,
            proposal = remainingRequired?.let { proposal(period, daysElapsed, it) },
            proposalNotStated = proposalNotStated,
            requirementMet = verdict(daysRemaining == 0 && missing.isEmpty(), requirement, average),
        )
    }

    private fun elapsed(period: MaintenancePeriod, evaluationDate: LocalDate): Int =
        if (evaluationDate.isBefore(period.start)) {
            0
        } else {
            ChronoUnit.DAYS.between(period.start, minOf(evaluationDate, period.end)).toInt() + 1
        }

    private fun averageNotStated(daysElapsed: Int, holdingsNotStated: String?, noSnapshot: Boolean): String? = when {
        daysElapsed == 0 -> PERIOD_NOT_STARTED
        holdingsNotStated != null -> holdingsNotStated
        noSnapshot -> NO_SNAPSHOT_YET
        else -> null
    }

    private fun oneRunPerDay(days: List<DailyHolding>): List<DailyHolding> = days.groupBy { it.date }
        .map { (date, sameDay) ->
            sameDay.singleOrNull()
                ?: throw IllegalArgumentException("more than one snapshot for $date")
        }
        .sortedBy { it.date }

    /** The same holding on every remaining day; a negative requirement (already met) floors at zero. */
    private fun proposal(period: MaintenancePeriod, daysElapsed: Int, remainingRequired: BigDecimal) =
        remainingRequired.max(BigDecimal.ZERO).let { each ->
            (daysElapsed until period.days).map { HoldingProposal(period.start.plusDays(it.toLong()), each) }
        }

    /** A verdict only for a finished, fully covered period with both sides stated. */
    private fun verdict(finishedAndCovered: Boolean, requirement: BigDecimal?, average: BigDecimal?): Boolean? =
        if (finishedAndCovered && requirement != null && average != null) average >= requirement else null
}
