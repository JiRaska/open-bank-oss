// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.anacredit.application

import com.openbank.anacredit.application.port.`in`.BuildAnaCreditReturnUseCase
import com.openbank.anacredit.application.port.`in`.ListExposuresUseCase
import com.openbank.anacredit.application.port.`in`.RegisterExposureCommand
import com.openbank.anacredit.application.port.`in`.RegisterExposureUseCase
import com.openbank.anacredit.application.port.out.CreditExposureRepository
import com.openbank.anacredit.domain.model.CreditExposure
import com.openbank.anacredit.domain.report.AnaCreditReturn
import com.openbank.anacredit.domain.report.AnaCreditReturnBuilder
import jakarta.enterprise.context.ApplicationScoped
import java.time.LocalDate

/**
 * Application service for the AnaCredit credit-exposure feed: it owns exposure intake and delegates
 * the regulatory return assembly to the pure [AnaCreditReturnBuilder]. Derive-only — it posts no
 * money and emits no events (ADR-0037), so it stays off the money-path gate.
 */
@ApplicationScoped
class AnaCreditService(
    private val exposures: CreditExposureRepository,
) : RegisterExposureUseCase, ListExposuresUseCase, BuildAnaCreditReturnUseCase {

    override fun register(command: RegisterExposureCommand): CreditExposure =
        exposures.upsert(
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

    override fun list(): List<CreditExposure> = exposures.listAll()

    override fun build(referenceDate: LocalDate): AnaCreditReturn =
        AnaCreditReturnBuilder.build(exposures.listAll(), referenceDate)
}
