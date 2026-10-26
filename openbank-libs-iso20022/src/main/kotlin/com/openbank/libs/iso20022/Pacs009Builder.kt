// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.iso20022

import org.w3c.dom.Element
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * The clearing system a `pacs.009` settles through, as `GrpHdr/SttlmInf/ClrSys`. Exactly one of
 * [code] (an `ExternalCashClearingSystem1Code`, e.g. `TGT`) or [proprietary] is set.
 */
data class ClearingSystem(val code: String? = null, val proprietary: String? = null) {
    init {
        require((code == null) != (proprietary == null)) { "exactly one of code / proprietary must be set" }
    }
}

/**
 * One FI-to-FI (bank-own) credit transfer in scheme-neutral terms — the input to
 * [Pacs009Builder]. Every party is a financial institution identified by BIC. Accounts are
 * rendered as `IBAN` when they are one and as `Othr/Id` otherwise (a domestic account number).
 * [uetr] must be a lower-case UUID v4 when set (the SWIFT gpi tracking reference).
 */
data class InstitutionCreditTransferInstruction(
    val messageId: String,
    val creationDateTime: OffsetDateTime,
    val interbankSettlementDate: LocalDate,
    val instructionId: String?,
    val endToEndId: String,
    val transactionId: String?,
    val uetr: String?,
    val amount: BigDecimal,
    val currency: String,
    val settlementMethod: SettlementMethod,
    val clearingSystem: ClearingSystem?,
    val instructingAgentBic: String,
    val instructedAgentBic: String,
    val intermediaryAgentBic: String?,
    val debtorBic: String,
    val debtorAccount: String?,
    val creditorBic: String,
    val creditorName: String?,
    val creditorAccount: String?,
    val remittanceInfo: String?,
)

/**
 * Builds a namespace-qualified ISO 20022 `pacs.009.001.08` (FinancialInstitutionCreditTransfer,
 * CORE variant — no underlying customer credit transfer) from an
 * [InstitutionCreditTransferInstruction]. JDK DOM only, like [Pacs008Builder]; the caller
 * validates the result against [SCHEMA_RESOURCE] before it leaves the rail (ADR-0104 D1).
 * One transaction per message (`NbOfTxs = 1`).
 */
class Pacs009Builder {
    fun build(instruction: InstitutionCreditTransferInstruction): String {
        val doc = XmlDoc.create(NAMESPACE)
        val document = doc.root("Document")
        val fiCdtTrf = doc.child(document, "FICdtTrf")
        buildGroupHeader(doc, fiCdtTrf, instruction)
        buildTransaction(doc, fiCdtTrf, instruction)
        return doc.serialize()
    }

    private fun buildGroupHeader(doc: XmlDoc, parent: Element, i: InstitutionCreditTransferInstruction) {
        val grpHdr = doc.child(parent, "GrpHdr")
        doc.text(grpHdr, "MsgId", i.messageId)
        doc.text(grpHdr, "CreDtTm", i.creationDateTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
        doc.text(grpHdr, "NbOfTxs", "1")
        val sttlmInf = doc.child(grpHdr, "SttlmInf")
        doc.text(sttlmInf, "SttlmMtd", i.settlementMethod.name)
        i.clearingSystem?.let { cs ->
            val clrSys = doc.child(sttlmInf, "ClrSys")
            cs.code?.let { doc.text(clrSys, "Cd", it) }
            cs.proprietary?.let { doc.text(clrSys, "Prtry", it) }
        }
    }

    private fun buildTransaction(doc: XmlDoc, parent: Element, i: InstitutionCreditTransferInstruction) {
        val tx = doc.child(parent, "CdtTrfTxInf")
        val pmtId = doc.child(tx, "PmtId")
        i.instructionId?.let { doc.text(pmtId, "InstrId", it) }
        doc.text(pmtId, "EndToEndId", i.endToEndId)
        i.transactionId?.let { doc.text(pmtId, "TxId", it) }
        i.uetr?.let { doc.text(pmtId, "UETR", it) }

        doc.text(tx, "IntrBkSttlmAmt", i.amount.toPlainString()).setAttribute("Ccy", i.currency)
        doc.text(tx, "IntrBkSttlmDt", i.interbankSettlementDate.format(DateTimeFormatter.ISO_LOCAL_DATE))

        agent(doc, tx, "InstgAgt", i.instructingAgentBic, null)
        agent(doc, tx, "InstdAgt", i.instructedAgentBic, null)
        i.intermediaryAgentBic?.let { agent(doc, tx, "IntrmyAgt1", it, null) }
        agent(doc, tx, "Dbtr", i.debtorBic, null)
        i.debtorAccount?.let { account(doc, tx, "DbtrAcct", it) }
        agent(doc, tx, "Cdtr", i.creditorBic, i.creditorName)
        i.creditorAccount?.let { account(doc, tx, "CdtrAcct", it) }
        i.remittanceInfo?.let { info -> doc.child(tx, "RmtInf").also { doc.text(it, "Ustrd", info) } }
    }

    private fun agent(doc: XmlDoc, parent: Element, name: String, bic: String, institutionName: String?) {
        val finInstnId = doc.child(doc.child(parent, name), "FinInstnId")
        doc.text(finInstnId, "BICFI", bic)
        institutionName?.let { doc.text(finInstnId, "Nm", it) }
    }

    private fun account(doc: XmlDoc, parent: Element, name: String, account: String) {
        val id = doc.child(doc.child(parent, name), "Id")
        if (IBAN.matches(account)) {
            doc.text(id, "IBAN", account)
        } else {
            doc.text(doc.child(id, "Othr"), "Id", account)
        }
    }

    companion object {
        const val NAMESPACE: String = "urn:iso:std:iso:20022:tech:xsd:pacs.009.001.08"
        const val SCHEMA_RESOURCE: String = "pacs.009.001.08.xsd"

        /** The XSD's `IBAN2007Identifier` pattern: an account matching it is rendered as `IBAN`. */
        val IBAN: Regex = Regex("[A-Z]{2}[0-9]{2}[a-zA-Z0-9]{1,30}")
    }
}
