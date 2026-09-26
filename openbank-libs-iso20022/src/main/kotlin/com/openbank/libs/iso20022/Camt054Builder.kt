// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.iso20022

import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/** ISO 20022 `CreditDebitCode` — direction of a booked entry. */
enum class CreditDebitIndicator { CRDT, DBIT }

/**
 * A single booked debit/credit notification entry, in scheme-neutral terms.
 *
 * Models the one-entry `camt.054` the scheme simulator pushes to the beneficiary on settlement
 * (ADR-0104): a booked credit on the creditor's account, carrying the original `endToEndId` so
 * the rail's reconciliation can tie it back to the `pacs.008` it sent.
 */
data class DebitCreditNotification(
    val messageId: String,
    val creationDateTime: OffsetDateTime,
    val notificationId: String,
    val accountIban: String,
    val entryReference: String?,
    val amount: BigDecimal,
    val currency: String,
    val direction: CreditDebitIndicator,
    val bookingDate: LocalDate,
    val endToEndId: String,
)

/**
 * Builds a real, namespace-qualified ISO 20022 `camt.054.001.08` (bank-to-customer debit/credit
 * notification) from a [DebitCreditNotification].
 *
 * ADR-0104 D2: the scheme simulator emits this on the beneficiary side when a transfer settles,
 * driving the rail's reconciliation loop. The entry is always booked (`Sts/Cd = BOOK`). The
 * produced XML is validated against the vendored XSD ([SCHEMA_RESOURCE]). JDK DOM only.
 */
class Camt054Builder {
    fun build(notification: DebitCreditNotification): String {
        val doc = XmlDoc.create(NAMESPACE)
        val document = doc.root("Document")
        val ntfctn = doc.child(document, "BkToCstmrDbtCdtNtfctn")

        val grpHdr = doc.child(ntfctn, "GrpHdr")
        doc.text(grpHdr, "MsgId", notification.messageId)
        doc.text(grpHdr, "CreDtTm", notification.creationDateTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))

        val ntf = doc.child(ntfctn, "Ntfctn")
        doc.text(ntf, "Id", notification.notificationId)
        doc.child(ntf, "Acct").also { acct ->
            doc.child(acct, "Id").also { doc.text(it, "IBAN", notification.accountIban) }
        }

        val entry = doc.child(ntf, "Ntry")
        notification.entryReference?.let { doc.text(entry, "NtryRef", it) }
        doc.text(entry, "Amt", notification.amount.toPlainString()).setAttribute("Ccy", notification.currency)
        doc.text(entry, "CdtDbtInd", notification.direction.name)
        doc.child(entry, "Sts").also { doc.text(it, "Cd", ENTRY_STATUS_BOOKED) }
        doc.child(entry, "BookgDt").also {
            doc.text(it, "Dt", notification.bookingDate.format(DateTimeFormatter.ISO_LOCAL_DATE))
        }
        doc.child(entry, "NtryDtls").also { dtls ->
            doc.child(dtls, "TxDtls").also { tx ->
                doc.child(tx, "Refs").also { doc.text(it, "EndToEndId", notification.endToEndId) }
            }
        }
        return doc.serialize()
    }

    companion object {
        const val NAMESPACE: String = "urn:iso:std:iso:20022:tech:xsd:camt.054.001.08"
        const val SCHEMA_RESOURCE: String = "camt.054.001.08.xsd"

        /** ISO 20022 `ExternalEntryStatus1Code` for a booked entry. */
        const val ENTRY_STATUS_BOOKED: String = "BOOK"
    }
}
