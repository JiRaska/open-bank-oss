// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.infrastructure.rest

import com.openbank.libs.synthetic.SyntheticTransferDirection
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

data class InitiateSyntheticTransferRequest(
    val partyId: UUID,
    val sourceAccountId: UUID,
    val targetAccountId: UUID,
    val runDate: LocalDate,
    val direction: SyntheticTransferDirection,
) {
    fun toInitiation(): InitiateTransactionRequest {
        val (source, target) = if (direction == SyntheticTransferDirection.OUTBOUND) {
            sourceAccountId to targetAccountId
        } else {
            targetAccountId to sourceAccountId
        }
        return InitiateTransactionRequest(
            idempotencyKey = direction.idempotencyKey(partyId, runDate),
            type = "TRANSFER",
            sourceAccountId = source,
            targetAccountId = target,
            amount = BigDecimal("0.01"),
            currencyCode = "CZK",
            description = "Synthetic transfer canary",
            valueDate = runDate.toString(),
            initiatedByPartyId = partyId,
            scaExemption = "PSD2_RTS_ART15_OWN_ACCOUNT",
        )
    }
}
