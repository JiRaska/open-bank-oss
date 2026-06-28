// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.application.port.out

import java.math.BigDecimal
import java.util.UUID

data class FxConversionResult(
    val id: UUID,
    val fromCurrency: String,
    val toCurrency: String,
    val fromAmountMinorUnits: Long,
    val toAmountMinorUnits: Long,
    val appliedRate: BigDecimal,
)

interface FxConversionPort {
    suspend fun convert(
        idempotencyKey: String,
        accountId: UUID,
        partyId: UUID,
        partyName: String,
        fromCurrency: String,
        toCurrency: String,
        fromAmountMinorUnits: Long,
    ): FxConversionResult
}
