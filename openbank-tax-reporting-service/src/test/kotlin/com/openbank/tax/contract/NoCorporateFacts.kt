// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.contract

import com.openbank.tax.application.port.out.CorporateFactsPort
import com.openbank.tax.domain.corporate.CorporateFact
import java.math.BigDecimal
import java.time.LocalDate

/** The pact interactions exercise the providers only; no return they assemble reads the corporate register. */
internal object NoCorporateFacts : CorporateFactsPort {
    override suspend fun effective(
        entityId: String,
        facts: Collection<CorporateFact>,
        periodStart: LocalDate,
        periodEnd: LocalDate,
    ): Map<CorporateFact, BigDecimal> = emptyMap()
}

/** No pact interaction reads the pension company's ledger. */
internal val NoCompanyBooks = com.openbank.tax.infrastructure.returns.pension.CompanyBooksPort {
    error("no pact interaction reads the pension company's books")
}

/** No pension-service / pension-fund-service pact interaction reads the pension company's portfolio. */
internal val NoCompanyPortfolio = com.openbank.tax.application.port.out.CompanyPortfolioPort {
    error("no pact interaction for these providers reads the pension company's portfolio")
}
