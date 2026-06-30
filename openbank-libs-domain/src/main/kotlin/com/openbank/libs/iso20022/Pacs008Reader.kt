// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.iso20022

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
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

/** Thrown when a `pacs.008` cannot be parsed into a [ReceivedCreditTransfer] (missing field). */
class Pacs008ParseException(message: String) : RuntimeException(message)

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
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }
        val doc = factory.newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        doc.documentElement.normalize()

        val root = doc.documentElement
        val tx = root.firstByLocal("CdtTrfTxInf") ?: missing("CdtTrfTxInf")
        val amountEl = tx.firstByLocal("IntrBkSttlmAmt") ?: missing("IntrBkSttlmAmt")

        return ReceivedCreditTransfer(
            messageId = root.requireText("MsgId"),
            endToEndId = tx.requireText("EndToEndId"),
            transactionId = tx.firstByLocal("TxId")?.trimmedText(),
            amount = BigDecimal(amountEl.trimmedText()),
            currency = amountEl.getAttribute("Ccy").ifBlank {
                throw Pacs008ParseException("pacs.008 IntrBkSttlmAmt missing Ccy attribute")
            },
            creditorName = tx.requireChild("Cdtr").requireText("Nm"),
            creditorIban = tx.requireChild("CdtrAcct").requireText("IBAN"),
            creditorAgentBic = tx.requireChild("CdtrAgt").requireText("BICFI"),
            debtorIban = tx.requireChild("DbtrAcct").requireText("IBAN"),
        )
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
