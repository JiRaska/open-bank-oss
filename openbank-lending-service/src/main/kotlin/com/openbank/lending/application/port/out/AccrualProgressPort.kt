// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.application.port.out

import com.openbank.lending.domain.model.LoanInstallment
import io.smallrye.mutiny.Uni
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/** Full immutable outbound request, prepared without a network call and persisted before posting. */
data class PreparedAccrualPosting(
    val reference: String,
    val transactionId: UUID,
    val accountingDate: LocalDate,
    val requestJson: String,
)

/** A POSTED ledger response bound to the persisted request, including its actual entry date. */
data class AccrualPostingReceipt(val journalId: UUID, val transactionId: UUID, val accountingDate: LocalDate)

data class AccrualIntent(
    val installmentId: UUID,
    val businessDate: LocalDate,
    val posting: PreparedAccrualPosting?,
    val completed: Boolean,
)

/** Durable per-installment hand-off; no financial call is allowed before [begin] commits. */
interface AccrualProgressPort {
    /** The supplier runs only when no prior intent exists; retries reuse its complete dated request. */
    fun begin(
        installment: LoanInstallment,
        businessDate: LocalDate,
        prepare: () -> PreparedAccrualPosting?,
    ): Uni<AccrualIntent>

    /**
     * One actual local transaction: confirm receipt once, mark the installment accrued, write its
     * existing outbox event, and increment work-day items / receipt-day journals. Any failure rolls
     * all four back, leaving the previously committed intent visible and unresolved.
     */
    fun complete(intent: AccrualIntent, receipt: AccrualPostingReceipt?, at: OffsetDateTime): Uni<Unit>
}
