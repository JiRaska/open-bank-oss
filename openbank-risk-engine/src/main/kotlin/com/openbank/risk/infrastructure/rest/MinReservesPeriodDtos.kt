// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.infrastructure.rest

import com.openbank.risk.application.port.`in`.MinReservesPeriodAnalysis
import com.openbank.risk.domain.reserves.CalendarStatus
import com.openbank.risk.domain.reserves.MaintenanceCalendar
import com.openbank.risk.domain.reserves.MaintenancePeriod
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

private const val MONEY_SCALE = 2
private const val RATIO_SCALE = 4

private fun BigDecimal.money(): BigDecimal = setScale(MONEY_SCALE, RoundingMode.HALF_EVEN)

/** Rounded UP: a proposal rounded down would, summed over the remaining days, fall short. */
private fun BigDecimal.moneyUp(): BigDecimal = setScale(MONEY_SCALE, RoundingMode.CEILING)

const val SAMPLE_CALENDAR_NOTE =
    "SAMPLE / UNVERIFIED maintenance-period calendar: the period boundaries and base reference dates are a " +
        "stand-in, NOT the official ČNB calendar. Every date-dependent figure here is illustrative until the " +
        "calendar is replaced with the published one (tracked in #11107)."

const val PROPOSAL_NOTE =
    "The proposal is the same holding on every remaining day that brings the period average to the requirement. " +
        "The engine proposes only; moving money is a CNB_DEPOSIT_FACILITY or transfer deal (ADR-0315 D8)."

const val CALENDAR_DAYS_NOTE =
    "The requirement is met on the average of end-of-day holdings over every CALENDAR day of the period; the " +
        "running average here is over the days that have a TIED_OUT snapshot only (see coverage)."

data class MaintenancePeriodDto(val id: String, val start: String, val end: String, val baseReferenceDate: String)

data class MaintenanceCalendarResponse(
    val calendarId: String,
    val calendarVersion: String,
    val calendarStatus: String,
    val calendarSource: String,
    val periods: List<MaintenancePeriodDto>,
    val notes: List<String>,
)

data class DailyHoldingDto(val date: String, val runId: UUID, val holdings: BigDecimal?)

data class HoldingProposalDto(val date: String, val amount: BigDecimal)

data class MinReservesPeriodResponse(
    val calendarId: String,
    val calendarVersion: String,
    val calendarStatus: String,
    val calendarSource: String,
    val period: MaintenancePeriodDto,
    val evaluationDate: String,
    val parameterSetId: String,
    val parameterSetVersion: String,
    val holdingCurrency: String,
    val baseRunId: UUID?,
    val requirement: BigDecimal?,
    val requirementNotStated: String?,
    val daysInPeriod: Int,
    val daysElapsed: Int,
    val daysRemaining: Int,
    val daysWithData: Int,
    /** daysWithData / daysElapsed; null before the period starts. */
    val coverage: BigDecimal?,
    val missingDays: List<String>,
    val days: List<DailyHoldingDto>,
    val averageHoldings: BigDecimal?,
    val averageNotStated: String?,
    val remainingRequiredAverage: BigDecimal?,
    val dailyHoldingProposal: BigDecimal?,
    val proposal: List<HoldingProposalDto>?,
    val proposalNotStated: String?,
    val requirementMet: Boolean?,
    val notes: List<String>,
)

fun MaintenancePeriod.toDto() = MaintenancePeriodDto(id, start.toString(), end.toString(), baseReferenceDate.toString())

private fun MaintenanceCalendar.notes() = listOfNotNull(
    SAMPLE_CALENDAR_NOTE.takeIf {
        status ==
            CalendarStatus.SAMPLE_UNVERIFIED
    },
)

fun MaintenanceCalendar.toResponse(periods: List<MaintenancePeriod>) = MaintenanceCalendarResponse(
    calendarId = id,
    calendarVersion = version,
    calendarStatus = status.wire,
    calendarSource = source,
    periods = periods.map { it.toDto() },
    notes = notes(),
)

fun MinReservesPeriodAnalysis.toResponse(): MinReservesPeriodResponse {
    val a = averaging
    return MinReservesPeriodResponse(
        calendarId = calendar.id,
        calendarVersion = calendar.version,
        calendarStatus = calendar.status.wire,
        calendarSource = calendar.source,
        period = a.period.toDto(),
        evaluationDate = a.evaluationDate.toString(),
        parameterSetId = parameters.id,
        parameterSetVersion = parameters.version,
        holdingCurrency = parameters.holdingCurrency,
        baseRunId = baseRunId,
        requirement = a.requirement?.money(),
        requirementNotStated = a.requirementNotStated,
        daysInPeriod = a.daysInPeriod,
        daysElapsed = a.daysElapsed,
        daysRemaining = a.daysRemaining,
        daysWithData = a.daysWithData,
        coverage = a.coverage?.setScale(RATIO_SCALE, RoundingMode.HALF_EVEN),
        missingDays = a.missingDays.map { it.toString() },
        days = a.days.map { DailyHoldingDto(it.date.toString(), it.runId, it.holdings?.money()) },
        averageHoldings = a.averageHoldings?.money(),
        averageNotStated = a.averageNotStated,
        remainingRequiredAverage = a.remainingRequiredAverage?.money(),
        dailyHoldingProposal = a.proposal?.firstOrNull()?.amount?.moneyUp(),
        proposal = a.proposal?.map { HoldingProposalDto(it.date.toString(), it.amount.moneyUp()) },
        proposalNotStated = a.proposalNotStated,
        requirementMet = a.requirementMet,
        notes = calendar.notes() + CALENDAR_DAYS_NOTE + PROPOSAL_NOTE,
    )
}
