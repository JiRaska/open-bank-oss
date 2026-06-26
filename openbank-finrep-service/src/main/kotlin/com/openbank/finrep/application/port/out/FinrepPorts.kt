// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.finrep.application.port.out

import java.math.BigDecimal
import java.time.LocalDate

data class TrialBalanceLineDto(val code: String, val accountType: String, val net: BigDecimal)

interface LedgerPort {
    suspend fun getTrialBalance(asOf: LocalDate): List<TrialBalanceLineDto>
}
