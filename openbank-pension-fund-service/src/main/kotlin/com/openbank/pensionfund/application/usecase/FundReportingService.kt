// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.application.usecase

import com.openbank.pensionfund.application.port.NotFoundException
import com.openbank.pensionfund.application.port.PensionFundStore
import com.openbank.pensionfund.domain.model.FundPeriodCalculator
import com.openbank.pensionfund.domain.model.FundPeriodReport
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
        val fund = store.fund(fundId) ?: throw NotFoundException("fund $fundId not found")
        require(!periodEnd.isBefore(periodStart)) { "periodEnd must not be before periodStart" }
        val navs = store.publishedNavsUpTo(fundId, periodEnd)
        val transactions = store.transactionsPricedAtAny(navs.map { it.id })
        val closing = navs.lastOrNull { !it.valuationDate.isBefore(periodStart) }
        val positions = closing?.takeIf { it.positionsRecorded }?.let { store.navPositions(it.id) }
        return FundPeriodCalculator.calculate(fund, periodStart, periodEnd, navs, transactions, positions)
    }
}
