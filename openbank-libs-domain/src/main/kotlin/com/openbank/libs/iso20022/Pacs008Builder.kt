// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.iso20022

import org.w3c.dom.Element
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * A single FI-to-FI customer credit transfer instruction, in scheme-neutral domain terms.
 *
 * This is the rail's input to [Pacs008Builder]; it carries exactly the fields the SEPA SCT
 * pilot populates (ADR-0104). Amount is a [BigDecimal] in major units (e.g. `12.34` EUR);
 * the builder renders it with the scheme's decimal rules. Agents are BICs; accounts are IBANs.
 */
data class CreditTransferInstruction(
    val messageId: String,
    val creationDateTime: OffsetDateTime,
    val endToEndId: String,
    val transactionId: String?,
    val amount: BigDecimal,
    val currency: String,
    val chargeBearer: ChargeBearer,
    val settlementMethod: SettlementMethod,
    val debtorName: String,
    val debtorIban: String,
    val debtorAgentBic: String,
    val creditorAgentBic: String,
    val creditorName: String,
    val creditorIban: String,
    val remittanceInfo: String?,
)

/** ISO 20022 `ChargeBearerType1Code`. SEPA SCT mandates `SLEV` (following service level). */
enum class ChargeBearer { DEBT, CRED, SHAR, SLEV }

/** ISO 20022 `SettlementMethod1Code`. SEPA SCT settles via a clearing system (`CLRG`). */
enum class SettlementMethod { INDA, INGA, COVE, CLRG }

/**
 * Builds a real, namespace-qualified ISO 20022 `pacs.008.001.08` (FI-to-FI customer credit
 * transfer) XML document from a [CreditTransferInstruction].
 *
 * ADR-0104 D1: the rail builds the actual scheme message rather than emitting an event and
 * marking it settled. The produced XML is meant to be handed straight to [Iso20022Validator]
 * (which the rail does before submitting to the scheme gateway) and then to the
 * `SchemeGatewayPort`. Built with the JDK's DOM/JAXP only — no XML-binding dependency.
 *
 * The number of transactions (`NbOfTxs`) is fixed at `1` for the single-instruction pilot;
 * batching multiple `CdtTrfTxInf` under one `GrpHdr` is a later increment.
 */
class Pacs008Builder {
    fun build(instruction: CreditTransferInstruction): String {
        val doc = XmlDoc.create(NAMESPACE)
        val document = doc.root("Document")
        val cdtTrf = doc.child(document, "FIToFICstmrCdtTrf")
        buildGroupHeader(doc, cdtTrf, instruction)
        buildTransaction(doc, cdtTrf, instruction)
        return doc.serialize()
    }

    private fun buildGroupHeader(doc: XmlDoc, parent: Element, i: CreditTransferInstruction) {
        val grpHdr = doc.child(parent, "GrpHdr")
        doc.text(grpHdr, "MsgId", i.messageId)
        doc.text(grpHdr, "CreDtTm", i.creationDateTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
        doc.text(grpHdr, "NbOfTxs", "1")
        val sttlmInf = doc.child(grpHdr, "SttlmInf")
        doc.text(sttlmInf, "SttlmMtd", i.settlementMethod.name)
    }

    private fun buildTransaction(doc: XmlDoc, parent: Element, i: CreditTransferInstruction) {
        val tx = doc.child(parent, "CdtTrfTxInf")

        val pmtId = doc.child(tx, "PmtId")
        doc.text(pmtId, "EndToEndId", i.endToEndId)
        i.transactionId?.let { doc.text(pmtId, "TxId", it) }

        doc.text(tx, "IntrBkSttlmAmt", i.amount.toPlainString()).setAttribute("Ccy", i.currency)
        doc.text(tx, "ChrgBr", i.chargeBearer.name)

        doc.child(tx, "Dbtr").also { doc.text(it, "Nm", i.debtorName) }
        doc.child(tx, "DbtrAcct").also { acct ->
            doc.child(acct, "Id").also { doc.text(it, "IBAN", i.debtorIban) }
        }
        doc.child(tx, "DbtrAgt").also { agt ->
            doc.child(agt, "FinInstnId").also { doc.text(it, "BICFI", i.debtorAgentBic) }
        }
        doc.child(tx, "CdtrAgt").also { agt ->
            doc.child(agt, "FinInstnId").also { doc.text(it, "BICFI", i.creditorAgentBic) }
        }
        doc.child(tx, "Cdtr").also { doc.text(it, "Nm", i.creditorName) }
        doc.child(tx, "CdtrAcct").also { acct ->
            doc.child(acct, "Id").also { doc.text(it, "IBAN", i.creditorIban) }
        }
        i.remittanceInfo?.let { info ->
            doc.child(tx, "RmtInf").also { doc.text(it, "Ustrd", info) }
        }
    }

    companion object {
        const val NAMESPACE: String = "urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08"
        const val SCHEMA_RESOURCE: String = "pacs.008.001.08.xsd"
    }
}
