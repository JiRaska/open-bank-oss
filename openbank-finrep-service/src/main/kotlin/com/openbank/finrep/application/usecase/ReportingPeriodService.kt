// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.finrep.application.usecase

import com.openbank.finrep.application.port.inbound.ReportingPeriodUseCase
import com.openbank.finrep.application.port.inbound.ReportingPeriods
import com.openbank.finrep.application.port.out.LedgerPort
import jakarta.enterprise.context.ApplicationScoped

/** Exposes only immutable month-end evidence that is safe to render into a regulatory return. */
@ApplicationScoped
class ReportingPeriodService(private val ledgerPort: LedgerPort) : ReportingPeriodUseCase {
    override suspend fun listAvailable(): ReportingPeriods {
        val periods = ledgerPort.listClosedPeriods()
            .asSequence()
            .filter { it.periodType == "MONTH" && it.status == "FROZEN" && it.evidenceState == "LINES_V1" }
            .map { it.to }
            .distinct()
            .sortedDescending()
            .toList()
        return ReportingPeriods(latest = periods.firstOrNull(), periods = periods)
    }
}
