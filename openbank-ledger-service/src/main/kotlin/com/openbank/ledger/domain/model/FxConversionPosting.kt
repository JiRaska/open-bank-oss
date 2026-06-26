// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.ledger.domain.model

import com.openbank.libs.domain.money.Money
import java.math.BigDecimal
import java.util.UUID

/**
 * Builds the journal lines for a customer FX conversion (ADR-0025). The conversion routes
 * through per-currency FX position accounts so the entry self-balances WITHIN each currency:
 *
 *   sell currency:  DEBIT  customer sell pocket   sellAmount
 *                   CREDIT FX position (sell)     sellAmount
 *   buy  currency:  DEBIT  FX position (buy)      grossBuyAmount
 *                   CREDIT customer buy pocket    customerBuyAmount
 *                   CREDIT FX margin income       (grossBuyAmount - customerBuyAmount)
 *
 * The bank's margin is the difference between the gross converted amount and what the customer
 * receives. A zero margin omits the margin line. The resulting lines, fed to a [JournalEntry],
 * are validated to balance per currency by [JournalEntry.validateBalance].
 */
object FxConversionPosting {

    fun build(
        journalId: UUID,
        customerSellPocketGl: UUID,
        fxPositionSellGl: UUID,
        fxPositionBuyGl: UUID,
        customerBuyPocketGl: UUID,
        marginIncomeGl: UUID,
        sellAmount: Money,
        grossBuyAmount: Money,
        customerBuyAmount: Money,
        fxRate: BigDecimal,
    ): List<JournalLine> {
        require(sellAmount.isPositive()) { "sellAmount must be positive" }
        require(grossBuyAmount.isPositive()) { "grossBuyAmount must be positive" }
        require(sellAmount.currency != grossBuyAmount.currency) {
            "FX conversion must cross currencies: both legs are ${sellAmount.currency.code}"
        }
        require(customerBuyAmount.currency == grossBuyAmount.currency) {
            "customerBuyAmount currency ${customerBuyAmount.currency.code} must match buy currency ${grossBuyAmount.currency.code}"
        }
        val margin = grossBuyAmount - customerBuyAmount
        require(margin.isNonNegative()) {
            "Customer cannot receive more than the gross converted amount (negative margin: $margin)"
        }

        val lines = mutableListOf(
            JournalLine(
                UUID.randomUUID(),
                journalId,
                customerSellPocketGl,
                JournalSide.DEBIT,
                sellAmount,
                fxRate,
                sellAmount,
                1,
            ),
            JournalLine(
                UUID.randomUUID(),
                journalId,
                fxPositionSellGl,
                JournalSide.CREDIT,
                sellAmount,
                fxRate,
                sellAmount,
                2,
            ),
            JournalLine(
                UUID.randomUUID(),
                journalId,
                fxPositionBuyGl,
                JournalSide.DEBIT,
                grossBuyAmount,
                fxRate,
                grossBuyAmount,
                3,
            ),
            JournalLine(
                UUID.randomUUID(),
                journalId,
                customerBuyPocketGl,
                JournalSide.CREDIT,
                customerBuyAmount,
                fxRate,
                customerBuyAmount,
                4,
            ),
        )
        if (!margin.isZero()) {
            lines +=
                JournalLine(UUID.randomUUID(), journalId, marginIncomeGl, JournalSide.CREDIT, margin, fxRate, margin, 5)
        }
        return lines
    }
}
