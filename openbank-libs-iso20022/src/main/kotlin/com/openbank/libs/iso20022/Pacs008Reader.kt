// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.iso20022

import org.w3c.dom.Element
import org.xml.sax.SAXException
import java.io.ByteArrayInputStream
import java.io.IOException
import java.math.BigDecimal
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** The fields of an inbound `pacs.008` that the scheme simulator needs to decide and to notify. */
data class ReceivedCreditTransfer(
    val messageId: String,
    val endToEndId: String,
    val transactionId: String?,
    val amount: BigDecimal,
    val currency: String,
    val creditorName: String,
    val creditorIban: String,
    val creditorAgentBic: String,
    val debtorIban: String,
)

/** Thrown when a `pacs.008` cannot be parsed into a [ReceivedCreditTransfer] (missing/malformed field). */
class Pacs008ParseException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Reads the key fields out of an inbound `pacs.008.001.08` XML document (ADR-0104 D2).
 *
 * The scheme simulator validates the message against the XSD ([Iso20022Validator]) first, then
 * uses this reader to pull the references/amount/parties needed to build the `pacs.002` status
 * and the `camt.054` notification. Namespace-aware and XXE-hardened (no DTD, no external
 * entities) — this parses content that arrives over the wire, so the parser must be locked down.
 * Stateless and thread-safe.
 */
class Pacs008Reader {
    fun read(xml: String): ReceivedCreditTransfer {
        // XXE hardening as plain imperative calls (not `.apply {}`) so static analysis can trace
        // the sanitizing calls straight to the factory that builds the parser below.
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        factory.isXIncludeAware = false
        factory.isExpandEntityReferences = false
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        val doc = parseDocument(factory, xml)
        doc.documentElement.normalize()

        val root = doc.documentElement
        val tx = root.firstByLocal("CdtTrfTxInf") ?: missing("CdtTrfTxInf")
        val amountEl = tx.firstByLocal("IntrBkSttlmAmt") ?: missing("IntrBkSttlmAmt")

        return ReceivedCreditTransfer(
            messageId = root.requireText("MsgId"),
            endToEndId = tx.requireText("EndToEndId"),
            transactionId = tx.firstByLocal("TxId")?.trimmedText(),
            amount = parseAmount(amountEl.trimmedText()),
            currency = amountEl.getAttribute("Ccy").ifBlank {
                throw Pacs008ParseException("pacs.008 IntrBkSttlmAmt missing Ccy attribute")
            },
            creditorName = tx.requireChild("Cdtr").requireText("Nm"),
            creditorIban = tx.requireChild("CdtrAcct").requireText("IBAN"),
            creditorAgentBic = tx.requireChild("CdtrAgt").requireText("BICFI"),
            debtorIban = tx.requireChild("DbtrAcct").requireText("IBAN"),
        )
    }

    // The input is XML from OUTSIDE the trust boundary (inbound clearing). A malformed document
    // must surface as the typed Pacs008ParseException, not a raw SAXParseException/IOException —
    // callers catch the former to return a clean 4xx; an unwrapped throwable becomes a 500.
    // (Found by fuzzing: empty/'<'/truncated input leaked SAXParseException.)
    private fun parseDocument(factory: DocumentBuilderFactory, xml: String) = try {
        factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
    } catch (e: SAXException) {
        throw Pacs008ParseException("pacs.008 is not well-formed XML: ${e.message}", e)
    } catch (e: IOException) {
        throw Pacs008ParseException("pacs.008 could not be read: ${e.message}", e)
    }

    // A non-numeric IntrBkSttlmAmt must also be a typed parse error, not a raw
    // NumberFormatException (same fuzzing finding class as the SAX wrap above).
    private fun parseAmount(raw: String): BigDecimal = try {
        BigDecimal(raw)
    } catch (e: NumberFormatException) {
        throw Pacs008ParseException("pacs.008 IntrBkSttlmAmt is not a valid amount: '$raw'", e)
    }

    private fun missing(local: String): Nothing =
        throw Pacs008ParseException("pacs.008 missing required element: $local")

    private fun Element.firstByLocal(local: String): Element? = getElementsByTagNameNS(NS, local).item(0) as? Element

    private fun Element.trimmedText(): String = textContent.trim()

    private fun Element.requireText(local: String): String = (firstByLocal(local) ?: missing(local)).trimmedText()

    private fun Element.requireChild(local: String): Element = firstByLocal(local) ?: missing(local)

    private companion object {
        const val NS = Pacs008Builder.NAMESPACE
    }
}
