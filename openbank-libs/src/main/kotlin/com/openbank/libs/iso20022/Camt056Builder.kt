// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.libs.iso20022

import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * A payment cancellation request (recall), in scheme-neutral terms. [cancellationReasonCode] is an
 * ISO 20022 `ExternalCancellationReason1Code` (e.g. `DUPL` duplicate, `FRAD` fraudulent, `CUST`
 * requested by customer) tying the recall back to the original `pacs.008`.
 */
data class PaymentCancellationRequest(
    val assignmentId: String,
    val creationDateTime: OffsetDateTime,
    val cancellationId: String?,
    val originalEndToEndId: String?,
    val originalTransactionId: String?,
    val cancellationReasonCode: String?,
    val additionalInfo: String? = null,
)

/**
 * Builds a real, namespace-qualified ISO 20022 `camt.056.001.08` (FI-to-FI payment cancellation
 * request / recall) from a [PaymentCancellationRequest].
 *
 * ADR-0104: the recall leg — a rail asks the scheme to cancel a previously sent `pacs.008` (the
 * scheme answers with a `camt.029` resolution, a later increment). The produced XML is validated
 * against the vendored XSD ([SCHEMA_RESOURCE]). JDK DOM only.
 */
class Camt056Builder {
    fun build(request: PaymentCancellationRequest): String {
        val doc = XmlDoc.create(NAMESPACE)
        val document = doc.root("Document")
        val cxlReq = doc.child(document, "FIToFIPmtCxlReq")

        val assgnmt = doc.child(cxlReq, "Assgnmt")
        doc.text(assgnmt, "Id", request.assignmentId)
        doc.text(assgnmt, "CreDtTm", request.creationDateTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))

        val txInf = doc.child(doc.child(cxlReq, "Undrlyg"), "TxInf")
        request.cancellationId?.let { doc.text(txInf, "CxlId", it) }
        request.originalEndToEndId?.let { doc.text(txInf, "OrgnlEndToEndId", it) }
        request.originalTransactionId?.let { doc.text(txInf, "OrgnlTxId", it) }
        request.cancellationReasonCode?.let { code ->
            val cxlRsnInf = doc.child(txInf, "CxlRsnInf")
            doc.child(cxlRsnInf, "Rsn").also { doc.text(it, "Cd", code) }
            request.additionalInfo?.let { doc.text(cxlRsnInf, "AddtlInf", it) }
        }
        return doc.serialize()
    }

    companion object {
        const val NAMESPACE: String = "urn:iso:std:iso:20022:tech:xsd:camt.056.001.08"
        const val SCHEMA_RESOURCE: String = "camt.056.001.08.xsd"
    }
}
