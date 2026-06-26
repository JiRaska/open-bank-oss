// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.libs.iso20022

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.time.OffsetDateTime
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** Thrown when a `pacs.004` cannot be parsed into a [PaymentReturn]. */
class Pacs004ParseException(message: String) : RuntimeException(message)

/**
 * Reads an inbound `pacs.004.001.09` (FI-to-FI payment return) into a [PaymentReturn].
 *
 * Used by the SEPA payment return handler (ADR-0109) when a scheme gateway or counterparty
 * returns funds for a previously settled `pacs.008`. Namespace-aware and XXE-hardened (it
 * parses content from the wire). Mirrors [Pacs002Reader]. Stateless.
 */
class Pacs004Reader {
    fun read(xml: String): PaymentReturn {
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
        val grpHdr = root.firstByLocal("GrpHdr") ?: fail("missing GrpHdr")
        val txInf = root.firstByLocal("TxInf") ?: fail("missing TxInf")

        val messageId = (grpHdr.firstByLocal("MsgId") ?: fail("missing MsgId")).textContent.trim()
        val creDtTmText = (grpHdr.firstByLocal("CreDtTm") ?: fail("missing CreDtTm")).textContent.trim()
        val creationDateTime = runCatching { OffsetDateTime.parse(creDtTmText) }
            .getOrElse { fail("invalid CreDtTm: $creDtTmText") }

        val sttlmMtdText = (
            grpHdr.firstByLocal("SttlmInf")?.firstByLocal("SttlmMtd")
                ?: fail("missing SttlmInf/SttlmMtd")
            ).textContent.trim()
        val settlementMethod = runCatching { SettlementMethod.valueOf(sttlmMtdText) }
            .getOrElse { fail("unknown SttlmMtd: $sttlmMtdText") }

        val amtEl = txInf.firstByLocal("RtrdIntrBkSttlmAmt") ?: fail("missing RtrdIntrBkSttlmAmt")
        val amtText = amtEl.textContent.trim()
        val returnedAmount = runCatching { BigDecimal(amtText) }
            .getOrElse { fail("invalid RtrdIntrBkSttlmAmt: $amtText") }
        val currency = amtEl.getAttribute("Ccy").trim().ifEmpty { fail("missing Ccy attribute") }

        val rtrRsnInf = txInf.firstByLocal("RtrRsnInf")
        val returnReasonCode = rtrRsnInf?.firstByLocal("Rsn")?.firstByLocal("Cd")?.textContent?.trim()
        val additionalInfo = rtrRsnInf?.firstByLocal("AddtlInf")?.textContent?.trim()

        return PaymentReturn(
            messageId = messageId,
            creationDateTime = creationDateTime,
            settlementMethod = settlementMethod,
            returnId = txInf.firstByLocal("RtrId")?.textContent?.trim(),
            originalEndToEndId = txInf.firstByLocal("OrgnlEndToEndId")?.textContent?.trim(),
            originalTransactionId = txInf.firstByLocal("OrgnlTxId")?.textContent?.trim(),
            returnedAmount = returnedAmount,
            currency = currency,
            returnReasonCode = returnReasonCode,
            additionalInfo = additionalInfo,
        )
    }

    private fun fail(detail: String): Nothing = throw Pacs004ParseException("pacs.004 $detail")

    private fun Element.firstByLocal(local: String): Element? = getElementsByTagNameNS(NS, local).item(0) as? Element

    private companion object {
        const val NS = Pacs004Builder.NAMESPACE
    }
}
