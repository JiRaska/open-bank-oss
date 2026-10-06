// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.application.usecase

import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.risk.application.port.`in`.MaintenancePeriodNotFoundException
import com.openbank.risk.application.port.`in`.MinReservesPeriodAnalysis
import com.openbank.risk.application.port.`in`.MinReservesPeriodUseCase
import com.openbank.risk.application.port.`in`.SnapshotUseCase
import com.openbank.risk.application.port.out.CnbPolicyRateFactRepository
import com.openbank.risk.domain.reserves.DailyHolding
import com.openbank.risk.domain.reserves.MaintenanceCalendar
import com.openbank.risk.domain.reserves.MaintenancePeriod
import com.openbank.risk.domain.reserves.MinReserveParameters
import com.openbank.risk.domain.reserves.MinimumReserves
import com.openbank.risk.domain.reserves.ReserveAveraging
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * Maintenance-period averaging of ČNB minimum reserves (ADR-0315 D8), derived on request from the
 * stored TIED_OUT snapshots and never stored (ADR-0314 D6).
 *
 *  - Requirement: [MinimumReserves] on the TIED_OUT run of the period's base reference date, at the
 *    ČNB reserve ratio in effect on that date; not stated (with the reason) when none is.
 *  - Holdings: the ČNB current-account balance of each TIED_OUT run inside the period, one per day
 *    (the latest recorded), up to the evaluation date.
 */
class MinReservesPeriodService(
    private val snapshots: SnapshotUseCase,
    private val parameters: MinReserveParameters,
    private val calendar: MaintenanceCalendar,
    private val clock: Clock,
    private val facts: CnbPolicyRateFactRepository,
) : MinReservesPeriodUseCase {

    override fun calendar(): MaintenanceCalendar = calendar

    override suspend fun analysePeriod(periodId: String, asOf: LocalDate?): MinReservesPeriodAnalysis {
        val period = calendar.find(periodId) ?: throw MaintenancePeriodNotFoundException(periodId)
        // "Today" is the accounting day in Europe/Prague (ADR-0207), not the UTC calendar date.
        val evaluationDate = asOf ?: AccountingClock(clock).today()
        // Holdings are stated or not by the classification alone, not by any day's balances.
        val holdingsNotStated = MinimumReserves.compute(emptyList(), parameters).holdingsNotStated
        val base = requirement(period)
        val days = if (evaluationDate.isBefore(period.start)) {
            emptyList()
        } else {
            snapshots.listTiedOutBetween(period.start, minOf(evaluationDate, period.end)).map { run ->
                val holdings = if (holdingsNotStated == null) {
                    MinimumReserves.compute(snapshots.getPositions(run.id), parameters).totalHoldings
                } else {
                    null
                }
                DailyHolding(run.asOf, run.id, holdings)
            }
        }
        val averaging = ReserveAveraging.compute(
            period = period,
            evaluationDate = evaluationDate,
            requirement = base.requirement,
            requirementNotStated = base.notStated,
            holdingsNotStated = holdingsNotStated,
            snapshots = days,
        )
        return MinReservesPeriodAnalysis(calendar, parameters, base.runId, averaging)
    }

    private class Base(val runId: UUID?, val requirement: BigDecimal?, val notStated: String?)

    private suspend fun requirement(period: MaintenancePeriod): Base {
        val ref = period.baseReferenceDate
        val run = snapshots.listTiedOutBetween(ref, ref).singleOrNull()
            ?: return Base(
                null,
                null,
                "No TIED_OUT snapshot on the base reference date $ref, so the requirement is not stated.",
            )
        val result = MinimumReserves.compute(
            snapshots.getPositions(run.id),
            ReserveFacts.resolve(parameters, facts, ref),
        )
        val holdingCurrencyBook = result.currencies.singleOrNull { it.currency == parameters.holdingCurrency }
        return when {
            result.totalCurrency != parameters.holdingCurrency -> Base(run.id, null, MinimumReserves.CURRENCY_NOTE)
            result.requirement == null -> Base(
                run.id,
                null,
                holdingCurrencyBook?.requirementNotStated ?: MinimumReserves.UNCLASSIFIED_LIABILITY,
            )
            else -> Base(run.id, result.requirement, null)
        }
    }
}
