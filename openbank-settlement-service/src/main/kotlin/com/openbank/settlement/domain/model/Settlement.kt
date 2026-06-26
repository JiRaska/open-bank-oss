// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.settlement.domain.model

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

enum class SettlementStatus {
    PENDING,
    DEBITED,
    CREDITED,
    BOOKED,
    REJECTED,
    REVERSED,
    CREDITED_REVERSED,
    LEDGER_REVERSED,
}

data class Settlement(
    val id: UUID,
    val payerAccountId: UUID,
    val payeeAccountId: UUID,
    val amount: BigDecimal,
    val currency: String,
    val status: SettlementStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
)
