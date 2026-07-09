// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.anacredit.application.port.`in`

import com.openbank.anacredit.domain.model.CounterpartyType
import com.openbank.anacredit.domain.model.CreditExposure
import com.openbank.anacredit.domain.model.InstrumentType
import com.openbank.anacredit.domain.report.AnaCreditReturn
import java.math.BigDecimal
import java.time.LocalDate

/** Command to register/replace a credit exposure the feed should consider. */
data class RegisterExposureCommand(
    val instrumentId: String,
    val debtorId: String,
    val debtorType: CounterpartyType,
    val instrumentType: InstrumentType,
    val currency: String,
    val committedAmount: BigDecimal,
    val drawnAmount: BigDecimal,
    val committedAmountEur: BigDecimal,
    val arrearsAmount: BigDecimal,
    val defaulted: Boolean,
    val originationDate: LocalDate,
)

interface RegisterExposureUseCase {
    suspend fun register(command: RegisterExposureCommand): CreditExposure
}

interface ListExposuresUseCase {
    suspend fun list(): List<CreditExposure>
}

/** Renders the AnaCredit credit-dataset return for a monthly reference date. */
interface BuildAnaCreditReturnUseCase {
    suspend fun build(referenceDate: LocalDate): AnaCreditReturn
}
