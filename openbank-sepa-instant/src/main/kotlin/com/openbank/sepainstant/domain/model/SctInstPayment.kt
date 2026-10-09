// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepainstant.domain.model

import com.openbank.libs.domain.money.Money
import java.time.OffsetDateTime
import java.util.UUID

enum class SctInstStatus {
    PENDING,
    PROCESSING,
    SETTLED,
    REJECTED,
    TIMEOUT,
    RECALLED,
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
    /** Always a valid kernel [Money]: built at the inbound boundary, never rounded (#11604). */
    val amount: Money,
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
    val requestHash: String? = null,
    val initiatingPrincipal: String? = null,
    val initiatingPartyId: UUID? = null,
    val initiatingActorPartyId: UUID? = null,
    /** True only after a durable screening/scheme decision; a claimed key alone is not a receipt. */
    val receiptReady: Boolean = false,
) {
    /** ISO 4217 code of [amount], the spelling every outbound contract carries. */
    val currency: String get() = amount.currency.code
}
