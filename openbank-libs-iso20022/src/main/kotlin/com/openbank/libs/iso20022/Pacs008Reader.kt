// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.iso20022

import com.openbank.libs.xml.SecureXml
import org.w3c.dom.Element
import org.xml.sax.SAXException
import java.io.IOException
import java.math.BigDecimal

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
    /**
     * Every `PmtTpInf/SvcLvl/Cd` of the transaction, in document order. `SvcLvl` is 0..n in
     * `PaymentTypeInformation28`, so a message may carry `SEPA` after another code; read the whole
     * list (or [isSepa]), never just the first. Empty when the message carries none.
     */
    val serviceLevelCodes: List<String> = emptyList(),
    /** `PmtTpInf/LclInstrm/Cd`, or `null` when the message carries none. */
    val localInstrumentCode: String? = null,
) {
    /** The pre-#13663 constructor, retained for binary compatibility (libs ABI rule). */
    @Suppress("LongParameterList")
    constructor(
        messageId: String,
        endToEndId: String,
        transactionId: String?,
        amount: BigDecimal,
        currency: String,
        creditorName: String,
        creditorIban: String,
        creditorAgentBic: String,
        debtorIban: String,
    ) : this(
        messageId, endToEndId, transactionId, amount, currency, creditorName, creditorIban,
        creditorAgentBic, debtorIban, emptyList(), null,
    )

    /**
     * The pre-#13663 `copy`, retained for binary compatibility; keeps the payment type.
     * Defaults also retain its old JVM `copy$default` descriptor for already-compiled Kotlin callers.
     */
    @Suppress("LongParameterList")
    fun copy(
        messageId: String = this.messageId,
        endToEndId: String = this.endToEndId,
        transactionId: String? = this.transactionId,
        amount: BigDecimal = this.amount,
        currency: String = this.currency,
        creditorName: String = this.creditorName,
        creditorIban: String = this.creditorIban,
        creditorAgentBic: String = this.creditorAgentBic,
        debtorIban: String = this.debtorIban,
    ): ReceivedCreditTransfer = ReceivedCreditTransfer(
        messageId, endToEndId, transactionId, amount, currency, creditorName, creditorIban,
        creditorAgentBic, debtorIban, serviceLevelCodes, localInstrumentCode,
    )

    /** True when any `SvcLvl/Cd` is `SEPA`. */
    val isSepa: Boolean get() = SEPA in serviceLevelCodes

    /**
     * True only for an SCT Inst as the EPC rulebook defines it: a `SEPA` service level AND
     * `LclInstrm/Cd=INST`. `INST` without `SEPA` is some other scheme's instrument, not SCT Inst.
     */
    val isInstant: Boolean get() = isSepa && localInstrumentCode == INST

    private companion object {
        const val SEPA = "SEPA"
        const val INST = "INST"
    }
}

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
        // Untrusted wire input: parsed only through the fleet-wide hardened factory (SecureXml).
        val doc = parseDocument(xml)
        doc.documentElement.normalize()

        val root = doc.documentElement
        val tx = root.firstByLocal("CdtTrfTxInf") ?: missing("CdtTrfTxInf")
        val amountEl = tx.firstByLocal("IntrBkSttlmAmt") ?: missing("IntrBkSttlmAmt")
        // Scoped to this transaction's own PmtTpInf by direct-child steps, never a descendant
        // search: a SvcLvl/LclInstrm/Cd anywhere else in the document must not be read as the type.
        val pmtTpInf = tx.directChild("PmtTpInf")

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
            serviceLevelCodes = pmtTpInf?.directChildren("SvcLvl")
                ?.mapNotNull { it.directChild("Cd")?.trimmedText() }
                .orEmpty(),
            localInstrumentCode = pmtTpInf?.directChild("LclInstrm")?.directChild("Cd")?.trimmedText(),
        )
    }

    // The input is XML from OUTSIDE the trust boundary (inbound clearing). A malformed document
    // must surface as the typed Pacs008ParseException, not a raw SAXParseException/IOException —
    // callers catch the former to return a clean 4xx; an unwrapped throwable becomes a 500.
    // (Found by fuzzing: empty/'<'/truncated input leaked SAXParseException.)
    private fun parseDocument(xml: String) = try {
        SecureXml.parse(xml)
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

    private fun Element.directChildren(local: String): List<Element> {
        val out = mutableListOf<Element>()
        var n = firstChild
        while (n != null) {
            if (n is Element && n.namespaceURI == NS && n.localName == local) out += n
            n = n.nextSibling
        }
        return out
    }

    private fun Element.directChild(local: String): Element? = directChildren(local).firstOrNull()

    private fun Element.trimmedText(): String = textContent.trim()

    private fun Element.requireText(local: String): String = (firstByLocal(local) ?: missing(local)).trimmedText()

    private fun Element.requireChild(local: String): Element = firstByLocal(local) ?: missing(local)

    private companion object {
        const val NS = Pacs008Builder.NAMESPACE
    }
}
