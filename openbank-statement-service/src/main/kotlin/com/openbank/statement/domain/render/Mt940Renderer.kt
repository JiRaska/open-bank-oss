// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.statement.domain.render

import com.openbank.statement.domain.model.CreditDebit
import com.openbank.statement.domain.model.StatementModel
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Renders a [StatementModel] to a **SWIFT MT940** customer statement message.
 *
 * Pure projection (ADR-0035 §C), deterministic (ADR-0035 §F): the `:20:` reference and statement
 * number derive only from the model. MT940 amounts use a **comma** decimal separator and carry the
 * sign as a C|D mark (the amount itself is always non-negative). One `:25:`/`:28C:` block per pocket
 * with an independent legal sequence (ADR-0035 §B).
 */
object Mt940Renderer {

    private val YYMMDD = DateTimeFormatter.ofPattern("yyMMdd")
    private val MMDD = DateTimeFormatter.ofPattern("MMdd")

    fun render(model: StatementModel): String {
        val lines = mutableListOf<String>()
        lines += ":20:${transactionRef(model)}"
        lines += ":25:${model.iban}"
        lines += ":28C:${model.legalSequenceNumber}/1"
        lines += ":60F:${balanceField(model.openingBalance.amount, model.currency, model.periodFrom)}"
        for (e in model.entries) {
            lines += ":61:${statementLine(e)}"
            lines += ":86:${truncate(e.description, 65)}"
        }
        lines += ":62F:${balanceField(model.closingBalance.amount, model.currency, model.periodTo)}"
        // SWIFT messages are CRLF-delimited and terminated by '-'.
        return lines.joinToString("\r\n") + "\r\n-"
    }

    /** Deterministic reference: pocket + legal sequence, capped at the 16x field width. */
    private fun transactionRef(model: StatementModel): String =
        truncate("STMT${model.currency}${model.legalSequenceNumber}", 16)

    /** `:60F:`/`:62F:` — C|D mark, YYMMDD date, 3-char currency, amount with comma decimal. */
    private fun balanceField(amount: BigDecimal, currency: String, date: LocalDate): String {
        val mark = if (amount.signum() < 0) "D" else "C"
        return "$mark${YYMMDD.format(date)}$currency${amount(amount.abs())}"
    }

    /** `:61:` statement line — value date, entry date, C|D mark, amount, NTRF type, reference. */
    private fun statementLine(e: com.openbank.statement.domain.model.StatementEntry): String {
        val mark = if (e.creditDebit == CreditDebit.DBIT) "D" else "C"
        return "${YYMMDD.format(
            e.valueDate,
        )}${MMDD.format(e.bookingDate)}$mark${amount(e.amount.abs())}NTRF${truncate(e.entryRef, 16)}"
    }

    /** Non-negative amount, comma decimal separator, exactly two fraction digits. */
    private fun amount(v: BigDecimal): String = v.setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',')

    private fun truncate(s: String, max: Int): String = if (s.length <= max) s else s.substring(0, max)
}
