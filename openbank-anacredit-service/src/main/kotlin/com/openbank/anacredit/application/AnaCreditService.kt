// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.anacredit.application

import com.openbank.anacredit.application.port.`in`.BuildAnaCreditReturnUseCase
import com.openbank.anacredit.application.port.`in`.ListExposuresUseCase
import com.openbank.anacredit.application.port.`in`.RegisterExposureCommand
import com.openbank.anacredit.application.port.`in`.RegisterExposureUseCase
import com.openbank.anacredit.application.port.out.AnaCreditMetricsPort
import com.openbank.anacredit.application.port.out.CreditExposureRepository
import com.openbank.anacredit.domain.model.CreditExposure
import com.openbank.anacredit.domain.report.AnaCreditReturn
import com.openbank.anacredit.domain.report.AnaCreditReturnBuilder
import jakarta.enterprise.context.ApplicationScoped
import java.time.Duration
import java.time.LocalDate

/**
 * Application service for the AnaCredit credit-exposure feed: it owns exposure intake and delegates
 * the regulatory return assembly to the pure [AnaCreditReturnBuilder]. Derive-only — it posts no
 * money and emits no events (ADR-0037), so it stays off the money-path gate.
 *
 * Both paths are instrumented through [AnaCreditMetricsPort]: derive-only means no downstream
 * consumer notices when the feed goes quiet or starts under-reporting, so the meters are the only
 * evidence that the return submitted for a reference date was assembled from a live book.
 */
@ApplicationScoped
class AnaCreditService(private val exposures: CreditExposureRepository, private val metrics: AnaCreditMetricsPort) :
    RegisterExposureUseCase,
    ListExposuresUseCase,
    BuildAnaCreditReturnUseCase {

    override suspend fun register(command: RegisterExposureCommand): CreditExposure {
        val stored = exposures.upsert(
            CreditExposure(
                instrumentId = command.instrumentId,
                debtorId = command.debtorId,
                debtorType = command.debtorType,
                instrumentType = command.instrumentType,
                currency = command.currency,
                committedAmount = command.committedAmount,
                drawnAmount = command.drawnAmount,
                committedAmountEur = command.committedAmountEur,
                arrearsAmount = command.arrearsAmount,
                defaulted = command.defaulted,
                originationDate = command.originationDate,
            ),
        )
        metrics.exposureRegistered(stored.instrumentType, stored.currency, stored.defaulted)
        return stored
    }

    override suspend fun list(): List<CreditExposure> = exposures.listAll()

    override suspend fun build(referenceDate: LocalDate): AnaCreditReturn {
        val startedAt = System.nanoTime()
        val report = AnaCreditReturnBuilder.build(exposures.listAll(), referenceDate)
        metrics.returnBuilt(
            recordCount = report.records.size,
            exclusionCount = report.exclusions.size,
            duration = Duration.ofNanos(System.nanoTime() - startedAt),
        )
        return report
    }
}
