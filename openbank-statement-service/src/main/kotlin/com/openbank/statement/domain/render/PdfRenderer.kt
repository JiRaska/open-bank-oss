// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.statement.domain.render

import com.openbank.statement.domain.model.CreditDebit
import com.openbank.statement.domain.model.StatementModel
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.format.DateTimeFormatter

/**
 * Renders a [StatementModel] (or a consolidated envelope of several pockets) to a deterministic,
 * human-readable text document — the canonical content of the customer PDF.
 *
 * In v1 this is the textual layout; a styled/eIDAS-sealed PDF is a downstream enhancement (ADR-0035
 * §F, Compliance) that takes *this same text* as input — it gates nothing. The output is a pure,
 * deterministic projection of the model(s): no clock, no IO, byte-identical on re-render.
 *
 * The consolidated view stacks the per-pocket statements and adds an **informational** reference-
 * currency grand total — explicitly labelled non-accounting, because pockets are never netted
 * (ADR-0024 / ADR-0035 §B).
 */
object PdfRenderer {

    private val DATE = DateTimeFormatter.ISO_LOCAL_DATE
    private const val WIDTH = 72

    /** Single-pocket statement document. */
    fun render(model: StatementModel): String = buildString { appendPocket(this, model) }

    /**
     * Consolidated, human-facing envelope across a customer's pockets. [referenceTotal] and
     * [referenceCurrency] are an **informational** grand total at the statement-date ČNB rate —
     * not an accounting figure; pockets remain un-netted.
     */
    fun renderConsolidated(
        holderName: String,
        iban: String,
        pockets: List<StatementModel>,
        referenceCurrency: String,
        referenceTotal: BigDecimal,
    ): String = buildString {
        line(this, "CONSOLIDATED ACCOUNT STATEMENT")
        line(this, "Account holder: $holderName")
        line(this, "IBAN: $iban")
        line(this, "Pockets: ${pockets.size}")
        rule(this)
        for ((idx, p) in pockets.withIndex()) {
            line(this, "POCKET ${idx + 1} — ${p.currency}")
            appendPocket(this, p)
            rule(this)
        }
        line(this, "INFORMATIONAL TOTAL (NOT AN ACCOUNTING FIGURE)")
        line(this, "Pockets are not netted (ADR-0024). The figure below is a non-binding")
        line(this, "reference-currency conversion at the statement-date CNB rate.")
        line(this, "Grand total ($referenceCurrency): ${money(referenceTotal)}")
    }

    private fun appendPocket(sb: StringBuilder, m: StatementModel) {
        line(sb, "ACCOUNT STATEMENT — ${m.currency}")
        line(sb, "Holder: ${m.holderName}")
        line(sb, "IBAN: ${m.iban}")
        line(sb, "Period: ${DATE.format(m.periodFrom)} .. ${DATE.format(m.periodTo)}")
        line(sb, "Statement no. (legal): ${m.legalSequenceNumber}   electronic: ${m.electronicSequenceNumber}")
        m.supersedesSequence?.let { line(sb, "Supersedes statement no.: $it") }
        rule(sb)
        line(sb, "Opening balance: ${money(m.openingBalance.amount)} ${m.currency} (${DATE.format(m.periodFrom)})")
        for (e in m.entries) {
            val sign = if (e.creditDebit == CreditDebit.DBIT) "-" else "+"
            line(
                sb,
                "${DATE.format(e.bookingDate)} (val ${DATE.format(e.valueDate)})  " +
                    "$sign${money(e.amount)} ${e.currency}  ${e.entryRef}  ${e.description}",
            )
        }
        line(sb, "Closing balance: ${money(m.closingBalance.amount)} ${m.currency} (${DATE.format(m.periodTo)})")
    }

    private fun line(sb: StringBuilder, s: String) = sb.append(s).append('\n')
    private fun rule(sb: StringBuilder) = sb.append("-".repeat(WIDTH)).append('\n')
    private fun money(v: BigDecimal): String = v.setScale(2, RoundingMode.HALF_UP).toPlainString()
}
