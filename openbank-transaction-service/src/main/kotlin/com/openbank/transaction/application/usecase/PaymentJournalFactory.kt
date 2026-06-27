// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.application.usecase

import com.openbank.transaction.domain.model.Transaction
import com.openbank.transaction.infrastructure.client.JournalLineRequest
import java.util.UUID

/**
 * Builds the ledger journal lines for a payment (ADR-0025 / N5 / ADR-0039).
 *
 *  - Same currency (`amount.currency == baseAmount.currency`): a two-legged entry that MUST branch on
 *    payment direction, because the deposit-control leg is what the balance projection turns into a
 *    booked delta (`JournalEntry.bookedDeltas`, credit-positive: deposit-control is a credit-normal
 *    customer liability, so CREDIT = +balance, DEBIT = −balance):
 *
 *        outbound (source, no target): DEBIT  deposit-control(sub=source)   CREDIT cash-clearing
 *        incoming (target, no source): DEBIT  cash-clearing                 CREDIT deposit-control(sub=target)
 *        internal transfer (both):     DEBIT  deposit-control(sub=source)   CREDIT deposit-control(sub=target)
 *
 *    Internal transfer uses two deposit-control legs (no bank cash-clearing leg): the control account
 *    nets to zero while the sub-ledger moves `amount` from source to target. Posting an unconditional
 *    `DEBIT cash-clearing / CREDIT deposit-control(source)` (the pre-ADR-0039 shape) credited the payer
 *    on an outbound payment — money creation in the booked balance once the projection is live.
 *
 *  - Cross currency (`amount.currency != baseAmount.currency`): a four-legged conversion routed
 *    through per-currency FX position accounts so the entry self-balances WITHIN each currency
 *    (devizová pozice, ADR-0025). The customer gives up `baseAmount` in their account (sell)
 *    currency and the payment leaves in `amount`'s (buy) currency:
 *
 *        sell ccy:  DEBIT  deposit control (sell)   baseAmount
 *                   CREDIT FX position (sell)       baseAmount
 *        buy  ccy:  DEBIT  FX position (buy)        amount
 *                   CREDIT deposit control (buy)    amount
 *
 *    The ledger's per-currency [JournalEntry.validateBalance] then sees debits == credits in each
 *    currency. `fxRate` rides on every line for audit; each line's base amount is its own leg
 *    amount so the ledger groups it under its own currency.
 */
object PaymentJournalFactory {

    // Well-known leaf GL accounts seeded by the ledger service.
    // V3__ledger_governance.sql: 1100 Customer Cash Clearing (ASSET, CZK), 2100 Customer Deposit
    // Control (LIABILITY, CZK). V5__fx_position_accounts.sql: per-currency deposit control and
    // FX position accounts. Stable UUIDs let the saga reference them deterministically.
    private val CUSTOMER_CASH_CLEARING_CZK = UUID.fromString("a0000000-0000-0000-0000-000000000001")

    private val DEPOSIT_CONTROL: Map<String, UUID> = mapOf(
        "CZK" to UUID.fromString("a0000000-0000-0000-0000-000000000002"),
        "EUR" to UUID.fromString("a0000000-0000-0000-0000-000000002101"),
        "USD" to UUID.fromString("a0000000-0000-0000-0000-000000002102"),
        "GBP" to UUID.fromString("a0000000-0000-0000-0000-000000002103"),
    )

    private val FX_POSITION: Map<String, UUID> = mapOf(
        "CZK" to UUID.fromString("a0000000-0000-0000-0000-000000001990"),
        "EUR" to UUID.fromString("a0000000-0000-0000-0000-000000001991"),
        "USD" to UUID.fromString("a0000000-0000-0000-0000-000000001992"),
        "GBP" to UUID.fromString("a0000000-0000-0000-0000-000000001993"),
    )

    fun buildLines(transaction: Transaction): List<JournalLineRequest> {
        val payCcy = transaction.amount.currency.code
        val baseCcy = transaction.baseAmount.currency.code
        return if (payCcy == baseCcy) {
            sameCurrencyLines(transaction)
        } // Cross-currency is FX-routed and (today) always an outbound sell, so its sub-account is the
        // source pocket; single IBAN means a same-customer pocket move has source == target anyway.
        else {
            crossCurrencyLines(transaction, transaction.sourceAccountId ?: transaction.targetAccountId)
        }
    }

    private fun sameCurrencyLines(transaction: Transaction): List<JournalLineRequest> {
        val ccy = transaction.amount.currency.code
        val source = transaction.sourceAccountId
        val target = transaction.targetAccountId
        return when {
            // Internal transfer: money moves between two customer pockets in the same currency. Both
            // legs are deposit-control (no bank cash-clearing leg) so the control account nets to zero
            // while the sub-ledger moves `amount` from source (−) to target (+).
            source != null && target != null -> listOf(
                depositLeg(transaction, ccy, "DEBIT", source),
                depositLeg(transaction, ccy, "CREDIT", target),
            )
            // Outbound: the customer pays out. DEBIT their deposit-control (booked −amount).
            source != null -> listOf(
                depositLeg(transaction, ccy, "DEBIT", source),
                cashClearingLeg(transaction, ccy, "CREDIT"),
            )
            // Incoming credit: the customer receives. CREDIT their deposit-control (booked +amount).
            target != null -> listOf(
                cashClearingLeg(transaction, ccy, "DEBIT"),
                depositLeg(transaction, ccy, "CREDIT", target),
            )
            else -> error("Payment ${transaction.id} has neither a source nor a target account")
        }
    }

    private fun depositLeg(transaction: Transaction, ccy: String, side: String, subAccountId: UUID) =
        JournalLineRequest(
            glAccountId = depositControl(ccy),
            side = side,
            amount = transaction.amount.amount,
            currencyCode = ccy,
            fxRate = transaction.fxRate,
            baseAmount = transaction.baseAmount.amount,
            baseCurrencyCode = transaction.baseAmount.currency.code,
            subAccountId = subAccountId,
        )

    private fun cashClearingLeg(transaction: Transaction, ccy: String, side: String) = JournalLineRequest(
        glAccountId = CUSTOMER_CASH_CLEARING_CZK,
        side = side,
        amount = transaction.amount.amount,
        currencyCode = ccy,
        fxRate = transaction.fxRate,
        baseAmount = transaction.baseAmount.amount,
        baseCurrencyCode = transaction.baseAmount.currency.code,
    )

    private fun crossCurrencyLines(transaction: Transaction, subAccountId: UUID?): List<JournalLineRequest> {
        val sell = transaction.baseAmount
        val buy = transaction.amount
        val sellCcy = sell.currency.code
        val buyCcy = buy.currency.code
        val fxRate = transaction.fxRate
        return listOf(
            JournalLineRequest(
                depositControl(sellCcy),
                "DEBIT",
                sell.amount,
                sellCcy,
                fxRate,
                sell.amount,
                sellCcy,
                subAccountId,
            ),
            JournalLineRequest(fxPosition(sellCcy), "CREDIT", sell.amount, sellCcy, fxRate, sell.amount, sellCcy),
            JournalLineRequest(fxPosition(buyCcy), "DEBIT", buy.amount, buyCcy, fxRate, buy.amount, buyCcy),
            JournalLineRequest(
                depositControl(buyCcy),
                "CREDIT",
                buy.amount,
                buyCcy,
                fxRate,
                buy.amount,
                buyCcy,
                subAccountId,
            ),
        )
    }

    private fun depositControl(currency: String): UUID = DEPOSIT_CONTROL[currency]
        ?: error("No deposit-control GL account seeded for currency $currency")

    private fun fxPosition(currency: String): UUID = FX_POSITION[currency]
        ?: error("No FX-position GL account seeded for currency $currency")
}
