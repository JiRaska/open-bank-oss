// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.sepainstant.infrastructure.rest.dto

import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

data class SubmitSctInstRequest(
    val idempotencyKey: String,
    val debtorAccountId: UUID,
    val debtorIban: String,
    val debtorName: String,
    val creditorIban: String,
    val creditorName: String,
    val creditorBic: String?,
    val amount: BigDecimal,
    val currency: String = "EUR",
    val remittanceInfo: String?,
    val endToEndId: String
)

data class RecallRequest(val reason: String)

data class SctInstPaymentResponse(
    val paymentId: UUID,
    val status: String,
    val debtorIban: String,
    val creditorIban: String,
    val amount: BigDecimal,
    val currency: String,
    val endToEndId: String,
    val executionTimeoutAt: OffsetDateTime?,
    val settledAt: OffsetDateTime?,
    val createdAt: OffsetDateTime
)
