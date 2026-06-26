// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.libs.iso20022

import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * ISO 20022 `ExternalPaymentTransactionStatus1Code` — the subset a clearing system returns on a
 * credit transfer (ADR-0104 scheme simulator). `ACSC` = settled, `RJCT` = rejected, `ACSP` =
 * accepted/in-process, `RCVD` = received.
 */
enum class PaymentStatus { RCVD, ACSP, ACSC, RJCT }

/**
 * A status report for one original credit transfer, in scheme-neutral terms.
 *
 * [reasonCode] is an ISO 20022 `ExternalStatusReason1Code` (e.g. `AC04` closed account, `AM05`
 * duplicate, `RR04` regulatory) and is only meaningful for [PaymentStatus.RJCT]; [additionalInfo]
 * is optional free text. The original references tie the report back to the `pacs.008`.
 */
data class PaymentStatusReport(
    val messageId: String,
    val creationDateTime: OffsetDateTime,
    val originalEndToEndId: String?,
    val originalTransactionId: String?,
    val status: PaymentStatus,
    val reasonCode: String? = null,
    val additionalInfo: String? = null,
)

/**
 * Builds a real, namespace-qualified ISO 20022 `pacs.002.001.10` (FI-to-FI payment status report)
 * from a [PaymentStatusReport].
 *
 * ADR-0104 D2: this is the scheme simulator's response to an inbound `pacs.008` — a realistic
 * `ACSC` settlement ack or an `RJCT` reject carrying a reason code. The produced XML is validated
 * against the vendored XSD ([SCHEMA_RESOURCE]) before it leaves the simulator. JDK DOM only.
 */
class Pacs002Builder {
    fun build(report: PaymentStatusReport): String {
        val doc = XmlDoc.create(NAMESPACE)
        val document = doc.root("Document")
        val rpt = doc.child(document, "FIToFIPmtStsRpt")

        val grpHdr = doc.child(rpt, "GrpHdr")
        doc.text(grpHdr, "MsgId", report.messageId)
        doc.text(grpHdr, "CreDtTm", report.creationDateTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))

        val txSts = doc.child(rpt, "TxInfAndSts")
        report.originalEndToEndId?.let { doc.text(txSts, "OrgnlEndToEndId", it) }
        report.originalTransactionId?.let { doc.text(txSts, "OrgnlTxId", it) }
        doc.text(txSts, "TxSts", report.status.name)

        report.reasonCode?.let { code ->
            val stsRsnInf = doc.child(txSts, "StsRsnInf")
            doc.child(stsRsnInf, "Rsn").also { doc.text(it, "Cd", code) }
            report.additionalInfo?.let { doc.text(stsRsnInf, "AddtlInf", it) }
        }
        return doc.serialize()
    }

    companion object {
        const val NAMESPACE: String = "urn:iso:std:iso:20022:tech:xsd:pacs.002.001.10"
        const val SCHEMA_RESOURCE: String = "pacs.002.001.10.xsd"
    }
}
