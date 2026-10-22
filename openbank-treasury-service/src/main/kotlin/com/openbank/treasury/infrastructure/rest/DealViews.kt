// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.rest

import com.openbank.treasury.domain.model.Deal
import com.openbank.treasury.domain.model.DealKind
import com.openbank.treasury.domain.model.DealState
import com.openbank.treasury.domain.model.ProductType
import java.time.LocalDate

/** The blotter filters (ADR-0344 catalogue); every field optional and ANDed. */
data class DealFilter(
    val state: DealState? = null,
    val instrument: ProductType? = null,
    val bookId: String? = null,
    val counterpartyId: String? = null,
    val currency: String? = null,
    val kind: DealKind? = null,
    val hideMatured: Boolean = false,
    val hideAuthorised: Boolean = false,
    val text: String? = null,
    val tradeDateFrom: LocalDate? = null,
    val tradeDateTo: LocalDate? = null,
    val maturityFrom: LocalDate? = null,
    val maturityTo: LocalDate? = null,
) {
    init {
        require(text == null || text.length <= MAX_TEXT) { "text has at most $MAX_TEXT characters" }
    }

    @Suppress("CyclomaticComplexMethod") // thirteen independent optional filters, ANDed
    fun matches(d: Deal): Boolean {
        val needle = text?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        return (state == null || d.state == state) &&
            (instrument == null || d.product == instrument) &&
            (bookId == null || d.bookId == bookId) &&
            (counterpartyId == null || d.counterpartyId == counterpartyId) &&
            (currency == null || d.currency == currency || d.legs.any { it.currency == currency }) &&
            (kind == null || d.kind == kind) &&
            (!hideMatured || d.state != DealState.MATURED) &&
            (!hideAuthorised || d.state !in AUTHORISED) &&
            (tradeDateFrom == null || !d.tradeDate.isBefore(tradeDateFrom)) &&
            (tradeDateTo == null || !d.tradeDate.isAfter(tradeDateTo)) &&
            (maturityFrom == null || !d.maturityDate.isBefore(maturityFrom)) &&
            (maturityTo == null || !d.maturityDate.isAfter(maturityTo)) &&
            (
                needle == null ||
                    listOfNotNull(d.id.toString(), d.counterpartyId, d.header.counterpartyReference, d.header.note)
                        .any { it.lowercase().contains(needle) }
                )
    }

    private companion object {
        const val MAX_TEXT = 100
        val AUTHORISED = setOf(DealState.BOOKED, DealState.CONFIRMED, DealState.SETTLED, DealState.MATURED)
    }
}

/** Deal export: one flat row per deal; REGULATORY adds the trade-reporting and liquidity fields. */
object DealExport {
    enum class Layout { STANDARD, REGULATORY }

    fun rows(deals: List<Deal>, layout: Layout): List<Map<String, Any?>> = deals.map { d ->
        val base = linkedMapOf<String, Any?>(
            "dealId" to d.id,
            "product" to d.product.name,
            "kind" to d.kind.name,
            "state" to d.state.name,
            "bookId" to d.bookId,
            "counterpartyId" to d.counterpartyId,
            "currency" to d.currency,
            "notional" to (d.legs.firstOrNull()?.notional ?: d.principal),
            "rate" to d.rate,
            "tradeDate" to d.tradeDate,
            "valueDate" to d.valueDate,
            "maturityDate" to d.maturityDate,
            "originalDealId" to d.header.originalDealId,
            "lastChangedAt" to d.updatedAt,
        )
        if (layout == Layout.REGULATORY) {
            val r = d.header.regulatory
            base += mapOf(
                "uti" to r?.tradeReporting?.uti,
                "counterpartyLei" to r?.tradeReporting?.counterpartyLei,
                "clearingObligation" to r?.tradeReporting?.clearingObligation,
                "collateralisation" to r?.tradeReporting?.collateralisation?.name,
                "centralBankCodes" to r?.centralBankCodes?.joinToString(";"),
                "lcrCategory" to r?.lcrCategory,
                "nsfrCategory" to r?.nsfrCategory,
                "jurisdiction" to d.header.jurisdiction,
            )
        }
        base
    }

    /** RFC 4180: quote every field, double embedded quotes; a leading formula character is neutralised. */
    fun csv(rows: List<Map<String, Any?>>): String {
        if (rows.isEmpty()) return ""
        val header = rows.first().keys.toList()
        fun cell(v: Any?): String {
            var s = v?.toString() ?: ""
            // A spreadsheet ignores leading blanks and control characters before reading a formula.
            val firstMeaningful = s.trimStart { it.isWhitespace() || it.isISOControl() }.firstOrNull()
            if (s.firstOrNull() in FORMULA_CHARS || firstMeaningful in FORMULA_CHARS) s = "'$s"
            return "\"" + s.replace("\"", "\"\"") + "\""
        }
        return (
            listOf(header.joinToString(",") { cell(it) }) +
                rows.map { row -> header.joinToString(",") { cell(row[it]) } }
            )
            .joinToString("\r\n", postfix = "\r\n")
    }

    private val FORMULA_CHARS = setOf('=', '+', '-', '@', '\t', '\r', '\n')
}

data class SendConfirmationRequest(val documentId: String? = null, val channel: String? = null)
