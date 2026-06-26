// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.
package com.openbank.statement.domain.model

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** The machine/human formats a closed period can be projected into (ADR-0035 §C). */
enum class StatementFormat { CAMT_053, MT940, PDF }

/** ISO 20022 credit/debit indicator; the entry [StatementEntry.amount] is always non-negative. */
enum class CreditDebit { CRDT, DBIT }

/** Lifecycle of a period-close record. A correction supersedes the prior close (ADR-0035 §D). */
enum class PeriodCloseStatus { CLOSED, SUPERSEDED }

/**
 * A single booked entry as it appears on a statement — a projection of a transaction-service
 * `Transaction` (booked only, `status = COMPLETED`). [amount] is always non-negative; the sign is
 * carried by [creditDebit] (camt `<CdtDbtInd>` / MT940 C|D), per ADR-0035 §E.
 */
data class StatementEntry(
    val entryRef: String,
    val amount: BigDecimal,
    val currency: String,
    val creditDebit: CreditDebit,
    val bookingDate: LocalDate,
    val valueDate: LocalDate,
    val description: String,
    val counterparty: String? = null,
)

/** Opening/closing balance anchor for a pocket on a given date. */
data class BalanceAnchor(val amount: BigDecimal, val currency: String, val date: LocalDate)

/**
 * The canonical, immutable statement aggregate for ONE pocket (IBAN + currency) over ONE period.
 *
 * Every rendered format (camt.053, MT940, PDF) is a *pure projection* of this aggregate — no renderer
 * re-derives balances or re-queries source data (ADR-0035 §C). It is built once at period-close and
 * replayed deterministically on demand; all timestamps a renderer needs are carried here ([closedAt])
 * so a re-render is byte-identical (ADR-0035 §D/§F).
 */
data class StatementModel(
    val accountId: UUID,
    val iban: String,
    val currency: String,
    val holderName: String,
    val periodFrom: LocalDate,
    val periodTo: LocalDate,
    val openingBalance: BalanceAnchor,
    val closingBalance: BalanceAnchor,
    val entries: List<StatementEntry>,
    val legalSequenceNumber: Long,
    val electronicSequenceNumber: Long,
    val closedAt: Instant,
    val supersedesSequence: Long? = null,
) {
    /** Sum of credits minus debits over the period — the booked net movement (ADR-0035 §E). */
    val netMovement: BigDecimal
        get() = entries.fold(BigDecimal.ZERO) { acc, e ->
            when (e.creditDebit) {
                CreditDebit.CRDT -> acc + e.amount
                CreditDebit.DBIT -> acc - e.amount
            }
        }
}
