// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.iso20022

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** The status a rail needs from an inbound `pacs.002`: the verdict and (on reject) why. */
data class ReceivedStatusReport(val originalEndToEndId: String?, val status: PaymentStatus, val reasonCode: String?)

/** Thrown when a `pacs.002` cannot be parsed into a [ReceivedStatusReport]. */
class Pacs002ParseException(message: String) : RuntimeException(message)

/**
 * Reads the verdict out of an inbound `pacs.002.001.10` status report (ADR-0104 D3/D4).
 *
 * The payment rail submits a `pacs.008` to the scheme gateway and gets back a `pacs.002`; this
 * reader turns that response into the [PaymentStatus] (`ACSC` settled / `RJCT` rejected / …) and
 * the optional `ExternalStatusReason1Code` so the rail can advance or reject the payment. Mirrors
 * [Pacs008Reader]: namespace-aware and XXE-hardened (it parses content from the wire). Stateless.
 */
class Pacs002Reader {
    fun read(xml: String): ReceivedStatusReport {
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
        val txSts = root.firstByLocal("TxInfAndSts") ?: fail("missing TxInfAndSts")
        val statusCode = (txSts.firstByLocal("TxSts") ?: fail("missing TxSts")).textContent.trim()
        val status = runCatching { PaymentStatus.valueOf(statusCode) }
            .getOrElse { fail("unknown TxSts: $statusCode") }

        return ReceivedStatusReport(
            originalEndToEndId = txSts.firstByLocal("OrgnlEndToEndId")?.textContent?.trim(),
            status = status,
            reasonCode = txSts.firstByLocal("StsRsnInf")?.firstByLocal("Cd")?.textContent?.trim(),
        )
    }

    private fun fail(detail: String): Nothing = throw Pacs002ParseException("pacs.002 $detail")

    private fun Element.firstByLocal(local: String): Element? = getElementsByTagNameNS(NS, local).item(0) as? Element

    private companion object {
        const val NS = Pacs002Builder.NAMESPACE
    }
}
