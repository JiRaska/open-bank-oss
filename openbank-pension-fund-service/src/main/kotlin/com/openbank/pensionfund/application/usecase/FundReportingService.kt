// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.application.usecase

import com.openbank.pensionfund.application.port.NotFoundException
import com.openbank.pensionfund.application.port.PensionFundStore
import com.openbank.pensionfund.domain.model.FundPeriodCalculator
import com.openbank.pensionfund.domain.model.FundPeriodReport
import com.openbank.pensionfund.domain.model.effectiveClasses
import jakarta.enterprise.context.ApplicationScoped
import java.time.LocalDate
import java.util.UUID

/**
 * Period-end read model for the statutory returns tax-reporting-service assembles (#12425,
 * ADR-0336 D4). Read-only, fund-level: it sums the register, it never lists it.
 */
@ApplicationScoped
class FundReportingService(private val store: PensionFundStore) {
    suspend fun periodReport(fundId: UUID, periodStart: LocalDate, periodEnd: LocalDate): FundPeriodReport {
        require(!periodEnd.isBefore(periodStart)) { "periodEnd must not be before periodStart" }
        val source = store.reportingSnapshot(fundId, periodStart, periodEnd)
        val fund = source.fund ?: throw NotFoundException("fund $fundId not found")
        val positions = effectiveClasses(source.positions, source.corrections)
        val byNav = source.positionNavIds.associateWith { navId -> positions.filter { it.navId == navId } }
        return FundPeriodCalculator.calculate(fund, periodStart, periodEnd, source.navs, source.transactions, byNav)
    }
}
