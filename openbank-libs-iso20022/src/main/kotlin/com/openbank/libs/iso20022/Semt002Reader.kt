// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.iso20022

import com.openbank.libs.xml.SecureXml
import org.w3c.dom.Element
import org.xml.sax.SAXException
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** How a custodian states a holding's quantity (semt.002 `FinancialInstrumentQuantity33Choice`). */
enum class HoldingQuantityType {
    /** `Unit`: a number of units (shares, fund units). */
    UNIT,

    /** `FaceAmt`: the nominal of a debt instrument. */
    FACE_AMOUNT,

    /** `AmtsdVal`: the amortised value of a debt instrument. */
    AMORTISED_VALUE,
}

/** One `BalForAcct` of a statement of holdings: one instrument in the safekeeping account. */
data class SecuritiesHolding(
    val isin: String,
    /** ISO 10962 CFI (`ClssfctnFinInstrm`), or null when the custodian did not state one. */
    val cfi: String?,
    val quantity: BigDecimal,
    val quantityType: HoldingQuantityType,
    /** `AcctBaseCcyAmts/HldgVal/Amt`, SIGNED (`Sgn` false = negative); null when not stated. */
    val holdingValue: BigDecimal?,
    val holdingValueCurrency: String?,
)

/** A complete, single-page semt.002 statement of holdings for one safekeeping account. */
data class SecuritiesHoldingsStatement(
    val statementId: String?,
    /** The date the holdings are stated at (`StmtDtTm`, its date AS WRITTEN). */
    val statementDate: LocalDate,
    val safekeepingAccount: String,
    val holdings: List<SecuritiesHolding>,
)

/**
 * Thrown when a semt.002 cannot be read into a [SecuritiesHoldingsStatement]. An
 * [IllegalArgumentException], so a malformed upload is a 400 fleet-wide, never a 500.
 */
class Semt002ParseException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/**
 * Reads a custodian's ISO 20022 `semt.002` (SecuritiesBalanceCustodyReport) statement of holdings
 * (ADR-0337 amendment). Elements are matched by LOCAL name, so any `semt.002.001.xx` namespace
 * reads; the vendored `semt.002.001.11.xsd` is a working subset and is held to the fixtures, not to
 * inbound files (a real custodian file carries optional elements the subset does not model).
 *
 * Refuses, rather than half-reads, every shape that would make the result something other than the
 * whole holding at the statement date:
 * - a page of a multi-page report (`Pgntn` other than page 1 and last page) — one page is not the
 *   portfolio;
 * - a delta report (`UpdTp` other than `COMP`) — changes are not a snapshot;
 * - a balance without an ISIN — the return reports per ISIN, and a description is not an identity;
 * - the same ISIN twice — two lines for one instrument cannot both be the holding.
 *
 * XXE-hardened through [SecureXml] (the input is an upload). Stateless.
 */
class Semt002Reader {
    fun read(xml: ByteArray): SecuritiesHoldingsStatement = try {
        readDocument(SecureXml.parse(xml).documentElement)
    } catch (e: SAXException) {
        throw Semt002ParseException("semt.002 is not well-formed XML: ${e.message}", e)
    } catch (e: DateTimeParseException) {
        throw Semt002ParseException("semt.002 carries an invalid date: ${e.parsedString}", e)
    } catch (e: NumberFormatException) {
        throw Semt002ParseException("semt.002 carries an invalid number: ${e.message}", e)
    }

    fun read(xml: String): SecuritiesHoldingsStatement = read(xml.toByteArray(Charsets.UTF_8))

    private fun readDocument(root: Element): SecuritiesHoldingsStatement {
        val report = root.child("SctiesBalCtdyRpt") ?: fail("missing SctiesBalCtdyRpt")
        report.child("Pgntn")?.let { pg ->
            val page = pg.child("PgNb")?.text()
            val last = pg.child("LastPgInd")?.text()
            if (page != "1" || last != "true") {
                fail("is page $page (last=$last) of a paginated report; upload the complete statement as one page")
            }
        }
        val general = report.child("StmtGnlDtls") ?: fail("missing StmtGnlDtls")
        val updateType = general.child("UpdTp")?.let { (it.child("Cd") ?: it.child("Prtry"))?.text() }
        if (updateType != "COMP") fail("UpdTp is '$updateType'; only a complete (COMP) statement is a snapshot")
        val statementDate = date(general.child("StmtDtTm") ?: fail("missing StmtGnlDtls/StmtDtTm"))
        val account = report.child("SfkpgAcct")?.child("Id")?.text() ?: fail("missing SfkpgAcct/Id")
        val holdings = report.children("BalForAcct").mapIndexed { i, bal -> holding(i + 1, bal) }
        holdings.groupBy { it.isin }.filterValues { it.size > 1 }.keys.firstOrNull()?.let {
            fail("states ISIN $it more than once")
        }
        return SecuritiesHoldingsStatement(
            statementId = general.child("StmtId")?.text(),
            statementDate = statementDate,
            safekeepingAccount = account,
            holdings = holdings,
        )
    }

    private fun holding(n: Int, bal: Element): SecuritiesHolding {
        val isin = bal.child("FinInstrmId")?.child("ISIN")?.text() ?: fail("BalForAcct[$n] has no FinInstrmId/ISIN")
        val cfi = bal.child("FinInstrmAttrbts")?.child("ClssfctnTp")?.child("ClssfctnFinInstrm")?.text()
        val qtyChoice = bal.child("AggtBal")?.child("Qty")?.child("Qty")
            ?: fail("BalForAcct[$n] ($isin) has no AggtBal/Qty/Qty")
        val (type, quantityEl) = QUANTITY_ELEMENTS.firstNotNullOfOrNull { (name, type) ->
            qtyChoice.child(name)?.let { type to it }
        } ?: fail("BalForAcct[$n] ($isin) states no Unit, FaceAmt or AmtsdVal quantity")
        val holdingValue = bal.child("AcctBaseCcyAmts")?.child("HldgVal")
        val amount = holdingValue?.child("Amt")
        val positive = holdingValue?.child("Sgn")?.text()?.let { it == "true" } ?: true
        return SecuritiesHolding(
            isin = isin,
            cfi = cfi,
            quantity = BigDecimal(quantityEl.text() ?: fail("BalForAcct[$n] ($isin) quantity is empty")),
            quantityType = type,
            holdingValue = amount?.text()?.let { BigDecimal(it) }?.let { if (positive) it else it.negate() },
            holdingValueCurrency = amount?.getAttribute("Ccy")?.takeIf { it.isNotBlank() },
        )
    }

    /** `Dt`, or the date part of `DtTm` as written (the custodian's own calendar day, never shifted). */
    private fun date(choice: Element): LocalDate {
        choice.child("Dt")?.text()?.let { return LocalDate.parse(it) }
        val raw = choice.child("DtTm")?.text() ?: fail("StmtDtTm states neither Dt nor DtTm")
        return runCatching {
            OffsetDateTime.parse(raw).toLocalDate()
        }.getOrElse { LocalDateTime.parse(raw).toLocalDate() }
    }

    private fun fail(detail: String): Nothing = throw Semt002ParseException("semt.002 $detail")

    private fun Element.children(local: String): List<Element> {
        val nodes = childNodes
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }.filter { it.localName == local }
    }

    private fun Element.child(local: String): Element? = children(local).firstOrNull()

    private fun Element.text(): String? = textContent?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        const val SCHEMA = "semt.002.001.11.xsd"

        private val QUANTITY_ELEMENTS = listOf(
            "Unit" to HoldingQuantityType.UNIT,
            "FaceAmt" to HoldingQuantityType.FACE_AMOUNT,
            "AmtsdVal" to HoldingQuantityType.AMORTISED_VALUE,
        )
    }
}
