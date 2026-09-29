// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.application.usecase

import com.openbank.risk.application.port.out.FxFixingRepository
import com.openbank.risk.domain.capital.FxRateUsed
import com.openbank.risk.domain.capital.ReportingCurrencyTotal
import java.time.LocalDate
import java.time.ZoneId

/**
 * The single path by which a result is converted to the reporting currency (CZK): for each non-CZK
 * currency, the ČNB fixing in effect at 00:00 Prague of the run's as-of date — exactly the instant
 * fx-service's `getCnbRate(asOf)` evaluates for the ledger's FX revaluation, so every view (capital,
 * liquidity) and the ledger agree on which fixing a day is marked at. A currency with no fixing in
 * effect is simply absent from the map; the caller states why no total is given.
 */
class ReportingFixings(private val fixings: FxFixingRepository) {

    suspend fun inEffect(currencies: Collection<String>, asOf: LocalDate): Map<String, FxRateUsed> {
        val at = asOf.atStartOfDay(CNB_ZONE).toInstant()
        return currencies.distinct()
            .filter { it != ReportingCurrencyTotal.REPORTING_CURRENCY }
            .mapNotNull { ccy ->
                fixings.inEffect(SOURCE, ccy, ReportingCurrencyTotal.REPORTING_CURRENCY, at)
                    ?.let { ccy to FxRateUsed(ccy, it.ratePerUnit, it.fixingDate, it.source) }
            }.toMap()
    }

    private companion object {
        const val SOURCE = "CNB"

        /** The ČNB publication calendar, as in fx-service's CnbRateIngestionService (not the accounting zone). */
        val CNB_ZONE: ZoneId = ZoneId.of("Europe/Prague")
    }
}
