// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.iso20022

import com.openbank.treasury.domain.model.NostroStatement
import com.openbank.treasury.domain.model.StatementDirection
import com.openbank.treasury.domain.model.StatementEntry
import org.w3c.dom.Element
import org.xml.sax.SAXException
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reads ONE `Stmt` of an ISO 20022 camt.053 (any `camt.053.001.xx` namespace — elements are matched
 * by local name). The fleet had only a camt.053 RENDERER (statement-service, customer statements);
 * nothing parsed one, so this is the first reader.
 *
 * Hardened against XXE: DOCTYPE is refused outright, external entities and XInclude are off. The
 * input is an upload from a person, so every malformed shape is an [IllegalArgumentException]
 * (400 fleet-wide), never a 500.
 *
 * Only booked entries (`Sts` BOOK, or absent) are read; a PDNG entry is not a movement yet.
 */
object Camt053Parser {

    /** A malformed number is a `NumberFormatException`, itself an `IllegalArgumentException` (400). */
    fun parse(xml: ByteArray): NostroStatement = try {
        val doc = builderFactory().newDocumentBuilder().parse(ByteArrayInputStream(xml))
        val stmts = descendants(doc.documentElement, "Stmt")
        require(stmts.size == 1) { "expected exactly one Stmt, found ${stmts.size}" }
        read(stmts.single())
    } catch (e: SAXException) {
        throw IllegalArgumentException("statement is not well-formed XML: ${e.message}", e)
    } catch (e: DateTimeParseException) {
        throw IllegalArgumentException("statement carries an invalid date: ${e.parsedString}", e)
    }

    private fun read(stmt: Element): NostroStatement {
        val statementId = text(child(stmt, "Id"), "Stmt/Id")
        val acct = requireNotNull(child(stmt, "Acct")) { "Stmt/Acct is required" }
        val iban = text(child(requireNotNull(child(acct, "Id")) { "Acct/Id is required" }, "IBAN"), "Acct/Id/IBAN")
        val balances = children(stmt, "Bal").associateBy { bal ->
            val tp = requireNotNull(child(bal, "Tp")) { "Bal/Tp is required" }
            requireNotNull(descendants(tp, "Cd").firstOrNull()?.textContent?.trim()) {
                "Bal/Tp/CdOrPrtry/Cd is required"
            }
        }
        val opening = requireNotNull(balances["OPBD"]) { "an OPBD (opening booked) balance is required" }
        val closing = requireNotNull(balances["CLBD"]) { "a CLBD (closing booked) balance is required" }
        val currency = requireNotNull(child(closing, "Amt")?.getAttribute("Ccy")?.takeIf { it.isNotBlank() }) {
            "CLBD/Amt@Ccy is required"
        }
        val statementDate = Camt053Values.date(child(closing, "Dt"), "CLBD/Dt")
        val entries = children(stmt, "Ntry")
            // camt.053.001.02 has `<Sts>BOOK</Sts>`; .08+ has `<Sts><Cd>BOOK</Cd></Sts>`.
            .filter { ntry ->
                val sts = child(ntry, "Sts")
                (sts?.let { (descendants(it, "Cd").firstOrNull() ?: it).textContent.trim() } ?: "BOOK") == "BOOK"
            }
            .mapIndexed { i, ntry -> entry(i + 1, ntry) }
        return NostroStatement(
            statementId = statementId,
            iban = iban.replace(" ", "").uppercase(),
            currency = currency,
            statementDate = statementDate,
            openingBalance = signed(opening),
            closingBalance = signed(closing),
            entries = entries,
        )
    }

    private fun entry(sequence: Int, ntry: Element): StatementEntry {
        val amt = requireNotNull(child(ntry, "Amt")) { "Ntry[$sequence]/Amt is required" }
        val txDtls = descendants(ntry, "TxDtls").firstOrNull()
        val endToEnd = txDtls?.let { descendants(it, "EndToEndId").firstOrNull()?.textContent?.trim() }
            ?.takeUnless { it.isEmpty() || it == "NOTPROVIDED" }
        val reference = endToEnd
            ?: child(ntry, "AcctSvcrRef")?.textContent?.trim()?.takeIf { it.isNotEmpty() }
            ?: descendants(ntry, "Ustrd").firstOrNull()?.textContent?.trim()?.takeIf { it.isNotEmpty() }
        return StatementEntry(
            sequence = sequence,
            amount = Camt053Values.amount(amt.textContent.trim(), "Ntry[$sequence]/Amt"),
            currency = amt.getAttribute("Ccy"),
            direction = direction(ntry),
            bookingDate = Camt053Values.date(child(ntry, "BookgDt"), "Ntry[$sequence]/BookgDt"),
            reference = reference,
        )
    }

    private fun signed(bal: Element): BigDecimal {
        val amount = Camt053Values.amount(text(child(bal, "Amt"), "Bal/Amt"), "Bal/Amt")
        return if (direction(bal) == StatementDirection.CRDT) amount else amount.negate()
    }

    private fun direction(el: Element): StatementDirection {
        val ind = text(child(el, "CdtDbtInd"), "CdtDbtInd")
        return StatementDirection.entries.firstOrNull { it.name == ind }
            ?: throw IllegalArgumentException("CdtDbtInd must be CRDT or DBIT, was '$ind'")
    }

    internal fun text(el: Element?, path: String): String =
        requireNotNull(el?.textContent?.trim()?.takeIf { it.isNotEmpty() }) { "$path is required" }

    private fun children(parent: Element, name: String): List<Element> {
        val nodes = parent.childNodes
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }.filter { it.localName == name }
    }

    internal fun child(parent: Element?, name: String): Element? = parent?.let { children(it, name).firstOrNull() }

    private fun descendants(parent: Element, name: String): List<Element> {
        val nodes = parent.getElementsByTagNameNS("*", name)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun builderFactory(): DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isXIncludeAware = false
        isExpandEntityReferences = false
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
    }
}

/** Amount and date values of a camt.053, held to what the store and the ledger can represent. */
internal object Camt053Values {
    /**
     * An amount the store can hold EXACTLY (NUMERIC(19,4)): at most 4 decimal places and 15 integer
     * digits. Rounding would change a figure the correspondent stated; refusing it keeps every
     * accepted statement reloadable byte-for-value.
     */
    fun amount(raw: String, path: String): BigDecimal {
        val value = BigDecimal(raw)
        require(value.scale() <= MAX_SCALE) { "$path has more than $MAX_SCALE decimal places: $raw" }
        require(value.precision() - value.scale() <= MAX_INTEGER_DIGITS) {
            "$path has more than $MAX_INTEGER_DIGITS integer digits: $raw"
        }
        return value
    }

    /**
     * `Dt` (ISODate) or `DtTm` (ISODateTime, camt.053.001.08 allows either for balances and
     * booking dates). A DtTm contributes its DATE AS WRITTEN — the calendar date in the offset the
     * correspondent stated, never shifted to UTC or to our zone — because that is the booking day
     * the correspondent means, and the ledger's entry date is a plain calendar date too.
     */
    fun date(choice: Element?, path: String): LocalDate {
        Camt053Parser.child(choice, "Dt")?.let { return LocalDate.parse(Camt053Parser.text(it, "$path/Dt")) }
        val dtTm = requireNotNull(Camt053Parser.child(choice, "DtTm")) { "$path/Dt or $path/DtTm is required" }
        val raw = Camt053Parser.text(dtTm, "$path/DtTm")
        // ISODateTime may carry an offset or not; either way the date part is the one written.
        return runCatching {
            OffsetDateTime.parse(raw).toLocalDate()
        }.getOrElse { LocalDateTime.parse(raw).toLocalDate() }
    }

    private const val MAX_SCALE = 4
    private const val MAX_INTEGER_DIGITS = 15
}
