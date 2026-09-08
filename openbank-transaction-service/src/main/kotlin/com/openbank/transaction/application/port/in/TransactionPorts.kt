// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.application.port.`in`

import com.openbank.libs.api.pagination.CursorPage
import com.openbank.libs.domain.payment.InstructionType
import com.openbank.libs.domain.payment.PaymentRail
import com.openbank.transaction.domain.model.Transaction
import com.openbank.transaction.domain.model.TransactionType
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

data class InitiateTransactionCommand(
    val idempotencyKey: String,
    val type: TransactionType,
    val sourceAccountId: UUID?,
    val targetAccountId: UUID?,
    val amount: BigDecimal,
    val currencyCode: String,
    val settlementCurrencyCode: String? = null,
    // Authoritative sell (settlement) amount for a sell-specified cross-currency move
    // (ADR-0107 pocket sweep). When set, it is used as the baseAmount verbatim instead
    // of deriving baseAmount = amount × rate — so the source pocket debits exactly and zeroes.
    val settlementAmount: BigDecimal? = null,
    val description: String?,
    val valueDate: LocalDate,
    val initiatedBy: UUID,
    val initiatedByPartyId: UUID? = null,
    val scaChallengeId: UUID? = null,
    val scaExemption: String? = null,
    /** Rail payment that triggered this booking (ADR-0108); null for operator/system commands. */
    val originatingPaymentId: UUID? = null,
    /**
     * The transaction this booking reverses (#8841). Set only by `reverseTransaction`, which
     * already holds the original's id — without this parameter the id was available at the call
     * site and dropped one line later, leaving the V2 compliance column `reversal_of` NULL on
     * every row ever written. Deliberately no ledger-style unique index: `transactions` is
     * PARTITION BY RANGE (booking_date), and Postgres requires every partitioning column in a
     * unique constraint — the COMPLETED→REVERSED status transition stays the sole guard against
     * a double reversal, and the decision is recorded here rather than left by default.
     */
    val reversalOf: UUID? = null,
    /** Which scheme carried the money (ADR-0103 D2). Null until stamped at origination. */
    val rail: PaymentRail? = null,
    /** How the movement was instructed (ADR-0103 D2) — orthogonal to [rail]. */
    val instructionType: InstructionType? = null,
)

data class GetTransactionQuery(val transactionId: UUID)
data class ListTransactionsQuery(val accountId: UUID, val limit: Int = 50, val afterCursor: String? = null)

data class ReverseTransactionCommand(val originalTransactionId: UUID, val idempotencyKey: String, val reason: String)

interface TransactionUseCase {
    suspend fun initiateTransaction(command: InitiateTransactionCommand): Transaction
    suspend fun getTransaction(query: GetTransactionQuery): Transaction
    suspend fun listTransactions(query: ListTransactionsQuery): CursorPage<Transaction>
    suspend fun reverseTransaction(command: ReverseTransactionCommand): Transaction
}
