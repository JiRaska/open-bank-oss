// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.application.port.out

import java.time.LocalDate
import java.util.UUID

interface FxSettlementPort {
    /** Book the DEBIT+CREDIT pair in transaction-service for a same-account FX exchange. */
    suspend fun settleFxExchange(
        idempotencyKey: String,
        accountId: UUID,
        fromCurrency: String,
        fromAmountMinorUnits: Long,
        toCurrency: String,
        toAmountMinorUnits: Long,
        valueDate: LocalDate,
    )
}
