// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.iso20022

import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * A payment return (R-transaction), in scheme-neutral terms. [returnReasonCode] is an ISO 20022
 * `ExternalReturnReason1Code` (e.g. `AC04` closed account, `AM05` duplicate, `MD06` refund by the
 * debtor) tying the return back to the original `pacs.008` via the original references.
 */
data class PaymentReturn(
    val messageId: String,
    val creationDateTime: OffsetDateTime,
    val settlementMethod: SettlementMethod,
    val returnId: String?,
    val originalEndToEndId: String?,
    val originalTransactionId: String?,
    val returnedAmount: BigDecimal,
    val currency: String,
    val returnReasonCode: String?,
    val additionalInfo: String? = null,
)

/**
 * Builds a real, namespace-qualified ISO 20022 `pacs.004.001.09` (FI-to-FI payment return) from a
 * [PaymentReturn].
 *
 * ADR-0104: the return/recall leg — a rail or the scheme simulator returns funds for a previously
 * settled `pacs.008` (`pacs.004` mirrors the `pacs.002` reject path but moves money back). The
 * produced XML is validated against the vendored XSD ([SCHEMA_RESOURCE]). JDK DOM only.
 */
class Pacs004Builder {
    fun build(returnTx: PaymentReturn): String {
        val doc = XmlDoc.create(NAMESPACE)
        val document = doc.root("Document")
        val pmtRtr = doc.child(document, "PmtRtr")

        val grpHdr = doc.child(pmtRtr, "GrpHdr")
        doc.text(grpHdr, "MsgId", returnTx.messageId)
        doc.text(grpHdr, "CreDtTm", returnTx.creationDateTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
        doc.text(grpHdr, "NbOfTxs", "1")
        doc.child(grpHdr, "SttlmInf").also { doc.text(it, "SttlmMtd", returnTx.settlementMethod.name) }

        val txInf = doc.child(pmtRtr, "TxInf")
        returnTx.returnId?.let { doc.text(txInf, "RtrId", it) }
        returnTx.originalEndToEndId?.let { doc.text(txInf, "OrgnlEndToEndId", it) }
        returnTx.originalTransactionId?.let { doc.text(txInf, "OrgnlTxId", it) }
        doc.text(txInf, "RtrdIntrBkSttlmAmt", returnTx.returnedAmount.toPlainString())
            .setAttribute("Ccy", returnTx.currency)
        returnTx.returnReasonCode?.let { code ->
            val rtrRsnInf = doc.child(txInf, "RtrRsnInf")
            doc.child(rtrRsnInf, "Rsn").also { doc.text(it, "Cd", code) }
            returnTx.additionalInfo?.let { doc.text(rtrRsnInf, "AddtlInf", it) }
        }
        return doc.serialize()
    }

    companion object {
        const val NAMESPACE: String = "urn:iso:std:iso:20022:tech:xsd:pacs.004.001.09"
        const val SCHEMA_RESOURCE: String = "pacs.004.001.09.xsd"
    }
}
