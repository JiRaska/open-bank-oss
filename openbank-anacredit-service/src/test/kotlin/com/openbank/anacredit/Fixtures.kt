// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.
package com.openbank.anacredit

import com.openbank.anacredit.domain.model.CounterpartyType
import com.openbank.anacredit.domain.model.CreditExposure
import com.openbank.anacredit.domain.model.InstrumentType
import java.math.BigDecimal
import java.time.LocalDate

object Fixtures {

    fun exposure(
        instrumentId: String = "OD-0001",
        debtorId: String = "LE-ACME",
        debtorType: CounterpartyType = CounterpartyType.LEGAL_ENTITY,
        instrumentType: InstrumentType = InstrumentType.OVERDRAFT,
        currency: String = "EUR",
        committedAmount: BigDecimal = BigDecimal("40000.00"),
        drawnAmount: BigDecimal = BigDecimal("12000.00"),
        committedAmountEur: BigDecimal = BigDecimal("40000.00"),
        arrearsAmount: BigDecimal = BigDecimal.ZERO,
        defaulted: Boolean = false,
        originationDate: LocalDate = LocalDate.parse("2025-06-01"),
    ) = CreditExposure(
        instrumentId = instrumentId,
        debtorId = debtorId,
        debtorType = debtorType,
        instrumentType = instrumentType,
        currency = currency,
        committedAmount = committedAmount,
        drawnAmount = drawnAmount,
        committedAmountEur = committedAmountEur,
        arrearsAmount = arrearsAmount,
        defaulted = defaulted,
        originationDate = originationDate,
    )
}
