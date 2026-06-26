// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.sepainstant.domain.model

import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

enum class SctInstStatus {
    PENDING, PROCESSING, SETTLED, REJECTED, TIMEOUT, RECALLED
}

data class SctInstPayment(
    val id: Long = 0,
    val paymentId: UUID = UUID.randomUUID(),
    val idempotencyKey: String,
    val status: SctInstStatus = SctInstStatus.PENDING,
    val debtorAccountId: UUID,
    val debtorIban: String,
    val debtorName: String,
    val creditorIban: String,
    val creditorName: String,
    val creditorBic: String?,
    val amount: BigDecimal,
    val currency: String = "EUR",
    val remittanceInfo: String?,
    val endToEndId: String,
    val executionTimeoutAt: OffsetDateTime?,
    val settledAt: OffsetDateTime?,
    val recalledAt: OffsetDateTime?,
    val recallReason: String?,
    val rejectReason: String?,
    val rejectDetail: String?,
    val submittedAt: OffsetDateTime?,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
)
