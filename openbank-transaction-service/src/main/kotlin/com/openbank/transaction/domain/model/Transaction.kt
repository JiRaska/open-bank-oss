// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.domain.model

import com.openbank.libs.domain.money.Money
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class Transaction(
    val id: UUID,
    val referenceNumber: String,
    val type: TransactionType,
    val sourceAccountId: UUID?,
    val targetAccountId: UUID?,
    val amount: Money,
    val fxRate: java.math.BigDecimal?,
    val baseAmount: Money,
    val status: TransactionStatus,
    val description: String?,
    val valueDate: LocalDate,
    val bookingDate: LocalDate,
    val initiatedAt: Instant,
    val completedAt: Instant?,
    val failedAt: Instant?,
    val failureReason: String?,
    val idempotencyKey: String,
    val version: Long,
    /** Customer party that expressed the will to move money (null for bank/system postings). */
    val initiatedByPartyId: UUID? = null,
    /** Consumed SCA challenge that authorised this movement (device-signed, ADR-0021). */
    val scaChallengeId: UUID? = null,
    /** Documented SCA exemption when no challenge applies (e.g. PSD2 RTS Art. 15 own-account). */
    val scaExemption: String? = null,
    /** How the money moved / which scheme (ADR-0103). Null until stamped at origination (D2). */
    val rail: com.openbank.libs.domain.payment.PaymentRail? = null,
    /** How the movement was instructed (ADR-0103) — orthogonal to [rail]. */
    val instructionType: com.openbank.libs.domain.payment.InstructionType? = null,
    /** Optional MCC-derived merchant category for card spend (ADR-0103). */
    val merchantCategory: String? = null,
    /** Rail payment that triggered this transaction; null for operator/system postings (ADR-0108). */
    val originatingPaymentId: UUID? = null,
    /**
     * ISO 20022 creditor/debtor name. Persisted since V3 and searched on; surfaced on the domain
     * model because it is the only stable handle on the counterparty of a NON-card transaction,
     * and so the key a customer's own categorisation hangs off. See [CounterpartyKey].
     */
    val counterpartyName: String? = null,
) {
    fun complete(clock: Clock): Transaction {
        check(status == TransactionStatus.PENDING || status == TransactionStatus.PROCESSING) {
            "Cannot complete transaction in status $status"
        }
        return copy(status = TransactionStatus.COMPLETED, completedAt = Instant.now(clock))
    }

    fun fail(reason: String, clock: Clock): Transaction {
        check(status != TransactionStatus.COMPLETED) { "Cannot fail completed transaction" }
        return copy(status = TransactionStatus.FAILED, failedAt = Instant.now(clock), failureReason = reason)
    }

    fun startProcessing(): Transaction {
        check(status == TransactionStatus.PENDING) { "Cannot process transaction in status $status" }
        return copy(status = TransactionStatus.PROCESSING)
    }

    fun reverse(): Transaction {
        check(status == TransactionStatus.COMPLETED) {
            "Cannot reverse transaction in status $status"
        }
        return copy(status = TransactionStatus.REVERSED)
    }
}

enum class TransactionType {
    DEBIT,
    CREDIT,
    TRANSFER,
    FEE,
    INTEREST,
    REVERSAL,
    ADJUSTMENT,
}

enum class TransactionStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED,
    REVERSED,
}
