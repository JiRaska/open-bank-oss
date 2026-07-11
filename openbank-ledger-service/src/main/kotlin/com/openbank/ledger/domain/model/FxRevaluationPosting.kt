// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.domain.model

import com.openbank.libs.domain.money.Money
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/**
 * One currency's input to the daily FX revaluation (ADR-0046). All amounts are signed.
 *
 * @param currency          the foreign currency being revalued (e.g. "EUR").
 * @param counterValueGlId  the CZK counter-value GL account (199x-CV, e.g. 1995) for this currency.
 * @param positionForeign   the bank's signed economic position in foreign units (+ = long the
 *                          currency). Derived from the 199x FX-position trial balance as
 *                          `credit − debit` (the position account is credited when the bank acquires
 *                          the currency), so a credit balance means a long position.
 * @param cnbRate           the ČNB central-bank fixing, CZK per 1 unit of [currency]. Must be > 0.
 * @param carryCzk          the CZK currently carried in the counter-value account, as its trial
 *                          balance net (`debit − credit`); 0 before the first revaluation.
 */
data class FxRevaluationInput(
    val currency: String,
    val counterValueGlId: UUID,
    val positionForeign: BigDecimal,
    val cnbRate: BigDecimal,
    val carryCzk: BigDecimal,
)

/**
 * Builds the journal lines for the daily mark-to-ČNB revaluation (ADR-0046, mirrors
 * [FxConversionPosting]). For each currency `X`:
 *
 * ```
 * targetX = round(positionForeignX * cnbRateX, 2 CZK)   // the CZK the position is now worth
 * dX      = targetX − carryCzkX                          // the unrealized movement since last mark
 * ```
 *
 * For each `dX ≠ 0` it emits a **pure CZK** pair against the exchange-rate-differences account
 * (5900): on a gain `DEBIT counter-value / CREDIT 5900`, on a loss `CREDIT counter-value / DEBIT
 * 5900`. The whole entry self-balances in CZK and never references a foreign currency, so the
 * per-currency invariant ([JournalEntry.validateBalance]) holds and the foreign 199x positions are
 * untouched. Currencies whose mark is unchanged contribute no lines; an all-unchanged day yields an
 * empty list (the caller must then post nothing).
 */
object FxRevaluationPosting {

    private const val CZK = "CZK"

    fun build(journalId: UUID, exchangeDiffGlId: UUID, inputs: List<FxRevaluationInput>): List<JournalLine> {
        val lines = mutableListOf<JournalLine>()
        var seq = 0
        for (input in inputs) {
            requireValid(input.cnbRate.signum() > 0) {
                "ČNB rate must be positive for ${input.currency}, was ${input.cnbRate}"
            }
            val delta = movement(input)
            if (delta.signum() == 0) continue
            val amount = Money.of(delta.abs(), CZK)
            if (delta.signum() > 0) {
                // Unrealized gain: the CZK mark of the position rose. Increase the ASSET
                // counter-value account and book the gain to exchange-rate differences (5900).
                lines +=
                    JournalLine(
                        UUID.randomUUID(),
                        journalId,
                        input.counterValueGlId,
                        JournalSide.DEBIT,
                        amount,
                        input.cnbRate,
                        amount,
                        ++seq,
                    )
                lines +=
                    JournalLine(
                        UUID.randomUUID(),
                        journalId,
                        exchangeDiffGlId,
                        JournalSide.CREDIT,
                        amount,
                        input.cnbRate,
                        amount,
                        ++seq,
                    )
            } else {
                // Unrealized loss: the mark fell. Decrease the counter-value account, book the loss.
                lines +=
                    JournalLine(
                        UUID.randomUUID(),
                        journalId,
                        input.counterValueGlId,
                        JournalSide.CREDIT,
                        amount,
                        input.cnbRate,
                        amount,
                        ++seq,
                    )
                lines +=
                    JournalLine(
                        UUID.randomUUID(),
                        journalId,
                        exchangeDiffGlId,
                        JournalSide.DEBIT,
                        amount,
                        input.cnbRate,
                        amount,
                        ++seq,
                    )
            }
        }
        return lines
    }

    /** The signed CZK movement (`targetX − carryCzkX`) for one currency, rounded to CZK minor units. */
    fun movement(input: FxRevaluationInput): BigDecimal {
        val target = input.positionForeign.multiply(input.cnbRate).setScale(2, RoundingMode.HALF_UP)
        return target.subtract(input.carryCzk.setScale(2, RoundingMode.HALF_UP))
    }
}
