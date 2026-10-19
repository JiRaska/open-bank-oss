// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.iso20022

import com.openbank.libs.xml.SecureXml
import org.w3c.dom.Element
import org.xml.sax.SAXException
import java.io.IOException
import java.math.BigDecimal

/** The fields of an inbound `pacs.009` that the scheme simulator needs to decide and answer. */
data class ReceivedInstitutionCreditTransfer(
    val messageId: String,
    val endToEndId: String,
    val transactionId: String?,
    val uetr: String?,
    val amount: BigDecimal,
    val currency: String,
    val instructingAgentBic: String,
    val instructedAgentBic: String,
    val creditorBic: String,
    val settlementMethod: String,
    val clearingSystem: String?,
)

/** Thrown when a `pacs.009` cannot be parsed into a [ReceivedInstitutionCreditTransfer]. */
class Pacs009ParseException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Reads the key fields out of an inbound `pacs.009.001.08` (ADR-0104 D2). Mirrors
 * [Pacs008Reader]: namespace-aware, parsed only through the hardened [SecureXml] factory (wire
 * input), and every malformed document surfaces as the typed [Pacs009ParseException].
 */
class Pacs009Reader {
    fun read(xml: String): ReceivedInstitutionCreditTransfer {
        val doc = parseDocument(xml)
        doc.documentElement.normalize()
        val root = doc.documentElement
        val sttlmInf = root.requireChild("SttlmInf")
        val tx = root.requireChild("CdtTrfTxInf")
        val amountEl = tx.requireChild("IntrBkSttlmAmt")
        val clrSys = sttlmInf.firstByLocal("ClrSys")
        return ReceivedInstitutionCreditTransfer(
            messageId = root.requireText("MsgId"),
            endToEndId = tx.requireText("EndToEndId"),
            transactionId = tx.firstByLocal("TxId")?.textContent?.trim(),
            uetr = tx.firstByLocal("UETR")?.textContent?.trim(),
            amount = amountEl.textContent.trim().toBigDecimalOrNull()
                ?: fail("IntrBkSttlmAmt is not a valid amount"),
            currency = amountEl.getAttribute("Ccy").ifBlank { fail("IntrBkSttlmAmt missing Ccy attribute") },
            instructingAgentBic = tx.requireChild("InstgAgt").requireText("BICFI"),
            instructedAgentBic = tx.requireChild("InstdAgt").requireText("BICFI"),
            creditorBic = tx.requireChild("Cdtr").requireText("BICFI"),
            settlementMethod = sttlmInf.requireText("SttlmMtd"),
            clearingSystem = clrSys?.let { (it.firstByLocal("Cd") ?: it.firstByLocal("Prtry"))?.textContent?.trim() },
        )
    }

    private fun parseDocument(xml: String) = try {
        SecureXml.parse(xml)
    } catch (e: SAXException) {
        throw Pacs009ParseException("pacs.009 is not well-formed XML: ${e.message}", e)
    } catch (e: IOException) {
        throw Pacs009ParseException("pacs.009 could not be read: ${e.message}", e)
    }

    private fun fail(detail: String): Nothing = throw Pacs009ParseException("pacs.009 $detail")

    private fun missing(local: String): Nothing = fail("missing required element: $local")

    private fun Element.firstByLocal(local: String): Element? = getElementsByTagNameNS(NS, local).item(0) as? Element

    private fun Element.requireChild(local: String): Element = firstByLocal(local) ?: missing(local)

    private fun Element.requireText(local: String): String = requireChild(local).textContent.trim()

    private companion object {
        const val NS = Pacs009Builder.NAMESPACE
    }
}
