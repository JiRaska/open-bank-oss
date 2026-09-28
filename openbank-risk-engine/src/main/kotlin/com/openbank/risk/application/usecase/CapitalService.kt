// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.application.usecase

import com.openbank.risk.application.port.`in`.CapitalAnalysis
import com.openbank.risk.application.port.`in`.CapitalUseCase
import com.openbank.risk.application.port.`in`.SnapshotUseCase
import com.openbank.risk.application.port.out.FxFixingRepository
import com.openbank.risk.domain.capital.CapitalParameters
import com.openbank.risk.domain.capital.CreditRiskCapital
import com.openbank.risk.domain.capital.FxRateUsed
import com.openbank.risk.domain.capital.ReportingCurrencyTotal
import java.time.ZoneId
import java.util.UUID

/**
 * Pillar 1 credit-risk capital of a TIED_OUT run, derived on request and never stored (ADR-0313
 * phase 2, ADR-0314 D6). Same gate as every other read: 404 unknown, 409 UNTIED.
 *
 * The CZK total reads, for each non-CZK currency in the book, the ČNB fixing in effect at 00:00
 * Prague of the run's as-of date — exactly the instant fx-service's `getCnbRate(asOf)` evaluates
 * for the ledger's FX revaluation, so the two agree on which fixing a day is marked at.
 */
class CapitalService(
    private val snapshots: SnapshotUseCase,
    private val parameters: CapitalParameters,
    private val fixings: FxFixingRepository,
) : CapitalUseCase {

    override suspend fun analyse(runId: UUID): CapitalAnalysis {
        val positions = snapshots.getPositions(runId) // 404 / 409 (UNTIED) before anything else
        val instruments = snapshots.getInstruments(runId)
        val run = snapshots.getRun(runId)
        val at = run.asOf.atStartOfDay(CNB_ZONE).toInstant()
        val rates = positions.map { it.currency }.distinct()
            .filter { it != ReportingCurrencyTotal.REPORTING_CURRENCY }
            .mapNotNull { ccy ->
                fixings.inEffect(SOURCE, ccy, ReportingCurrencyTotal.REPORTING_CURRENCY, at)
                    ?.let { ccy to FxRateUsed(ccy, it.ratePerUnit, it.fixingDate, it.source) }
            }.toMap()
        val result = CreditRiskCapital.compute(positions, instruments, parameters, rates, run.asOf)
        return CapitalAnalysis(run, parameters, result)
    }

    private companion object {
        const val SOURCE = "CNB"

        /** The ČNB publication calendar, as in fx-service's CnbRateIngestionService (not the accounting zone). */
        val CNB_ZONE: ZoneId = ZoneId.of("Europe/Prague")
    }
}
