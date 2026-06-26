// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.balance.application.port.`in`

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * ADR-0039 Phase D: the balance read-model as a projection of the ledger.
 *
 * One [AccountBookedChange] is the application-layer view of a ledger `AccountBookedChanged` event —
 * a signed booked movement ([delta]: + on a credit, − on a debit) the ledger has already posted for
 * one (account, currency). The projection applies each exactly once, keyed by
 * ([journalEntryId], [accountId], [currency]); Kafka is at-least-once, so redeliveries must be
 * idempotent. [transactionId] lets the projection release the matching cover hold as it applies the
 * delta, closing the overspend window during the saga-debit cutover (Phase D-2).
 */
data class AccountBookedChange(
    val accountId: UUID,
    val currency: String,
    val delta: BigDecimal,
    val journalEntryId: UUID,
    val transactionId: UUID,
    val entryDate: LocalDate,
    val version: Long,
)

interface LedgerProjectionUseCase {

    /** Apply one booked movement to the projected balance, exactly once. Idempotent on redelivery. */
    suspend fun apply(change: AccountBookedChange)
}
