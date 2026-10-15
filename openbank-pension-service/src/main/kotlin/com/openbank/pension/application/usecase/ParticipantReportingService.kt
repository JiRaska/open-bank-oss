// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.application.usecase

import com.openbank.pension.application.port.out.ParticipantReportingQueries
import com.openbank.pension.domain.reporting.MixedCurrencyException
import com.openbank.pension.domain.reporting.ParticipantBanding
import com.openbank.pension.domain.reporting.ParticipantPeriodAggregates
import com.openbank.pension.domain.reporting.UnknownSettlementTimeException
import java.time.LocalDate

/**
 * Participant aggregates per period for the statutory returns tax-reporting-service assembles
 * (#12425, ADR-0336 D4): counts by product line, age band and status; contributions by source;
 * state contributions; payouts by form; transfers in and out. Year-to-date figures run from
 * 1 January of [periodEnd]'s year, which is how the PSP 31-04 return states them.
 */
class ParticipantReportingService(private val queries: ParticipantReportingQueries, private val baseCurrency: String) {
    suspend fun aggregates(periodStart: LocalDate, periodEnd: LocalDate): ParticipantPeriodAggregates {
        require(!periodEnd.isBefore(periodStart)) { "periodEnd must not be before periodStart" }
        val yearStart = LocalDate.of(periodEnd.year, 1, 1)
        val ytdFrom = if (periodStart.isBefore(yearStart)) periodStart else yearStart
        if (queries.hasUnknownSettlementTime(periodEnd)) {
            throw UnknownSettlementTimeException(
                "a settled payout lacks its domestic transition time; refusing incomplete aggregates",
            )
        }
        val currencies = queries.currencies(ytdFrom, periodEnd)
        if (currencies.any { it != baseCurrency }) {
            throw MixedCurrencyException(
                "period $periodStart..$periodEnd books ${currencies.sorted()}, not only $baseCurrency — refusing to sum across currencies",
            )
        }
        val counts = ParticipantBanding.countsFrom(
            groups = queries.inForce(periodEnd),
            newInPeriod = queries.startedBetween(periodStart, periodEnd),
            exitedInPeriod = queries.exitedBetween(periodStart, periodEnd),
            contributing = queries.contributingBetween(periodStart, periodEnd),
            pensioners = queries.pensionersBetween(periodStart, periodEnd),
        )
        return ParticipantPeriodAggregates(
            periodStart = periodStart,
            periodEnd = periodEnd,
            currency = baseCurrency,
            participants = counts,
            contributions = queries.contributions(periodStart, periodEnd),
            contributionsYtd = queries.contributions(yearStart, periodEnd),
            stateContributions = queries.stateContributions(periodStart, periodEnd),
            payouts = queries.payouts(periodStart, periodEnd),
            payoutsYtd = queries.payouts(yearStart, periodEnd),
            transfers = queries.transfers(periodStart, periodEnd),
        )
    }
}
