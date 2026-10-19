// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.application.port.out

import com.openbank.treasury.domain.cashflow.CashFlow
import com.openbank.treasury.domain.payment.Payment
import com.openbank.treasury.domain.payment.PaymentStatus
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class PaymentNotFoundException(id: UUID) : NoSuchElementException("payment $id not found")

/** ADR-0344 D6: own-account payments and the flows they settle. */
interface PaymentRepository {
    /**
     * Insert a PROPOSED payment linking its flows ([prior] null; a flow already linked to a payment
     * refuses the whole insert), or store a status change (row locked, must still hold [prior]); with
     * the outbox [event] and, for SETTLED, the flows moved to SETTLED — all in ONE transaction.
     */
    suspend fun save(payment: Payment, prior: PaymentStatus?, event: DealEvent?, settleFlows: Boolean = false)

    suspend fun find(paymentId: UUID): Payment?

    suspend fun findByKey(idempotencyKey: String): Payment?

    suspend fun list(status: PaymentStatus?, from: LocalDate, to: LocalDate, limit: Int): List<Payment>

    /**
     * PROPOSED -> RELEASED under the funding check, serialised per funding bucket: inside ONE
     * transaction it takes a transaction-scoped advisory lock on [fundingKey] (account, currency,
     * value date), recomputes the released-but-unsettled outgoing total of that bucket, calls
     * [fundingCheck] with it (which throws to refuse), then stores the release and its [event].
     */
    suspend fun saveRelease(
        payment: Payment,
        event: DealEvent,
        fundingKey: String,
        fundingCheck: (pendingOut: BigDecimal) -> Unit,
    )

    /** Every payment that settles a flow of [dealId]. */
    suspend fun forDeal(dealId: UUID): List<Payment>

    /** Σ of released-but-unsettled (RELEASED, SENT, RAIL_NOT_SUPPORTED) outgoing payments in [currency] on [valueDate]. */
    suspend fun pendingOut(currency: String, valueDate: LocalDate): BigDecimal

    /** Expected receipts (IN, PROPOSED) of exactly [amount] in [currency] on [valueDate]. */
    suspend fun expectedIn(currency: String, amount: BigDecimal, valueDate: LocalDate): List<Payment>

    /** Latest-schedule FIXED flows of live ACTUAL deals paid in [from]..[to] and not yet on a payment. */
    suspend fun unpaidFixedFlows(from: LocalDate, to: LocalDate): List<CashFlow>
}

/** What a rail answered to a hand-off. */
sealed interface RailHandOff {
    data class Accepted(val reference: String) : RailHandOff

    /** The rail has no path for a bank-own (non-customer) debit; nothing was sent. */
    data class NotSupported(val reason: String) : RailHandOff
}

/** ADR-0344 D6: hands a RELEASED payment to the rail of its SSI (CERTIS, SEPA, TARGET2, SWIFT). */
fun interface PaymentRailPort {
    suspend fun handOff(payment: Payment): RailHandOff
}
