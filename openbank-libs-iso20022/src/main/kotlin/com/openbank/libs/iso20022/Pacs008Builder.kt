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
 * This is the rail's input to [Pacs008Builder]. Amount is a [BigDecimal] in major units
 * (e.g. `12.34` EUR); the builder renders it with the scheme's decimal rules. Agents are
 * BICs; accounts are IBANs. [interbankSettlementDate] maps to `IntrBkSttlmDt`: same-day for
 * clearing-settled rails (SEPA/domestic, which have no explicit value date of their own —
 * pass `creationDateTime`'s date), or the scheme-supplied value date for rails that carry one
 * (e.g. SWIFT MT103 field 32A).
 *
 * [paymentTypeInformation] maps to `CdtTrfTxInf/PmtTpInf`. It is optional and defaults to
 * `null`, in which case no `PmtTpInf` is emitted and the document is byte-identical to the one
 * produced before the field existed (#13663). A SEPA rail must set it: the EPC SCT rulebook
 * requires `SvcLvl/Cd=SEPA` and the SCT Inst rulebook additionally `LclInstrm/Cd=INST`
 * ([PaymentTypeInformation.SEPA_CREDIT_TRANSFER], [PaymentTypeInformation.SEPA_INSTANT]).
 */
data class CreditTransferInstruction(
    val messageId: String,
    val creationDateTime: OffsetDateTime,
    val interbankSettlementDate: OffsetDateTime,
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
    val paymentTypeInformation: PaymentTypeInformation? = null,
) {
    /**
     * The pre-#13663 constructor, retained so bytecode compiled against the 16-argument shape keeps
     * linking (libs ABI rule: an addition must not remove an existing signature).
     */
    @Suppress("LongParameterList")
    constructor(
        messageId: String,
        creationDateTime: OffsetDateTime,
        interbankSettlementDate: OffsetDateTime,
        endToEndId: String,
        transactionId: String?,
        amount: BigDecimal,
        currency: String,
        chargeBearer: ChargeBearer,
        settlementMethod: SettlementMethod,
        debtorName: String,
        debtorIban: String,
        debtorAgentBic: String,
        creditorAgentBic: String,
        creditorName: String,
        creditorIban: String,
        remittanceInfo: String?,
    ) : this(
        messageId, creationDateTime, interbankSettlementDate, endToEndId, transactionId, amount, currency,
        chargeBearer, settlementMethod, debtorName, debtorIban, debtorAgentBic, creditorAgentBic,
        creditorName, creditorIban, remittanceInfo, null,
    )

    /**
     * The pre-#13663 `copy`, retained for binary compatibility; keeps [paymentTypeInformation].
     * Defaults also retain its old JVM `copy$default` descriptor for already-compiled Kotlin callers.
     */
    @Suppress("LongParameterList")
    fun copy(
        messageId: String = this.messageId,
        creationDateTime: OffsetDateTime = this.creationDateTime,
        interbankSettlementDate: OffsetDateTime = this.interbankSettlementDate,
        endToEndId: String = this.endToEndId,
        transactionId: String? = this.transactionId,
        amount: BigDecimal = this.amount,
        currency: String = this.currency,
        chargeBearer: ChargeBearer = this.chargeBearer,
        settlementMethod: SettlementMethod = this.settlementMethod,
        debtorName: String = this.debtorName,
        debtorIban: String = this.debtorIban,
        debtorAgentBic: String = this.debtorAgentBic,
        creditorAgentBic: String = this.creditorAgentBic,
        creditorName: String = this.creditorName,
        creditorIban: String = this.creditorIban,
        remittanceInfo: String? = this.remittanceInfo,
    ): CreditTransferInstruction = CreditTransferInstruction(
        messageId, creationDateTime, interbankSettlementDate, endToEndId, transactionId, amount, currency,
        chargeBearer, settlementMethod, debtorName, debtorIban, debtorAgentBic, creditorAgentBic,
        creditorName, creditorIban, remittanceInfo, paymentTypeInformation,
    )
}

/**
 * ISO 20022 `PaymentTypeInformation28` as the rail populates it: one service level code
 * (`SvcLvl/Cd`, `ExternalServiceLevel1Code`, 1–4 characters) and an optional local instrument
 * code (`LclInstrm/Cd`, `ExternalLocalInstrument1Code`, 1–35 characters).
 *
 * Only the coded forms are modelled; `Prtry`, `InstrPrty`, `ClrChanl` and `CtgyPurp` are not
 * needed by any rail today.
 */
data class PaymentTypeInformation(val serviceLevelCode: String, val localInstrumentCode: String? = null) {
    init {
        require(serviceLevelCode.length in 1..MAX_SERVICE_LEVEL) {
            "PmtTpInf/SvcLvl/Cd must be 1-$MAX_SERVICE_LEVEL characters"
        }
        require(localInstrumentCode == null || localInstrumentCode.length in 1..MAX_LOCAL_INSTRUMENT) {
            "PmtTpInf/LclInstrm/Cd must be 1-$MAX_LOCAL_INSTRUMENT characters"
        }
    }

    companion object {
        private const val MAX_SERVICE_LEVEL = 4
        private const val MAX_LOCAL_INSTRUMENT = 35

        /** EPC SCT rulebook: `SvcLvl/Cd=SEPA`, no local instrument. */
        @JvmField
        val SEPA_CREDIT_TRANSFER: PaymentTypeInformation = PaymentTypeInformation("SEPA")

        /** EPC SCT Inst rulebook: `SvcLvl/Cd=SEPA` and `LclInstrm/Cd=INST`. */
        @JvmField
        val SEPA_INSTANT: PaymentTypeInformation = PaymentTypeInformation("SEPA", "INST")
    }
}

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

        buildIdentificationAndType(doc, tx, i)

        doc.text(tx, "IntrBkSttlmAmt", i.amount.toPlainString()).setAttribute("Ccy", i.currency)
        doc.text(tx, "IntrBkSttlmDt", i.interbankSettlementDate.format(DateTimeFormatter.ISO_LOCAL_DATE))
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

    private fun buildIdentificationAndType(doc: XmlDoc, tx: Element, i: CreditTransferInstruction) {
        val pmtId = doc.child(tx, "PmtId")
        doc.text(pmtId, "EndToEndId", i.endToEndId)
        i.transactionId?.let { doc.text(pmtId, "TxId", it) }

        // PmtTpInf sits between PmtId and IntrBkSttlmAmt in CreditTransferTransaction39. Absent
        // when the caller set none, so pre-#13663 output stays byte-identical.
        i.paymentTypeInformation?.let { buildPaymentTypeInformation(doc, tx, it) }
    }

    private fun buildPaymentTypeInformation(doc: XmlDoc, tx: Element, pti: PaymentTypeInformation) {
        val pmtTpInf = doc.child(tx, "PmtTpInf")
        doc.child(pmtTpInf, "SvcLvl").also { doc.text(it, "Cd", pti.serviceLevelCode) }
        pti.localInstrumentCode?.let { code ->
            doc.child(pmtTpInf, "LclInstrm").also { doc.text(it, "Cd", code) }
        }
    }

    companion object {
        const val NAMESPACE: String = "urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08"
        const val SCHEMA_RESOURCE: String = "pacs.008.001.08.xsd"
    }
}
