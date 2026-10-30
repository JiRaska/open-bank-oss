// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.portfolio

/** Closed vocabulary of the custodian-portfolio reporting contract, not a universal instrument taxonomy. */
enum class PortfolioReportingClass {
    GOVERNMENT_BOND,
    CORPORATE_BOND,
    EQUITY,
    FUND_UNITS,
    MONEY_MARKET,
    DEPOSIT,
}
