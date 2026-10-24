// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/mpL/2.0/ for details.

package com.openbank.clearingsimulator

import com.openbank.clearingsimulator.application.ClearingSimulatorService
import com.openbank.clearingsimulator.application.dto.ReturnRequest
import com.openbank.libs.iso20022.ChargeBearer
import com.openbank.libs.iso20022.CreditTransferInstruction
import com.openbank.libs.iso20022.Pacs008Builder
import com.openbank.libs.iso20022.Pacs008Reader
import com.openbank.libs.iso20022.PaymentTypeInformation
import com.openbank.libs.iso20022.SettlementMethod
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

class ClearingSimulatorServiceTest {
    private val fixedClock: Clock = Clock.fixed(Instant.parse("2026-06-22T10:18:00Z"), ZoneOffset.UTC)
    private val service = ClearingSimulatorService().apply { clock = fixedClock }
    private val pacs008 = Pacs008Builder()
    private val now = Instant.parse("2026-06-22T10:18:00Z")

    private fun pacs008Xml(amount: String, paymentType: PaymentTypeInformation? = null) = pacs008.build(
        CreditTransferInstruction(
            messageId = "OB-MSG-0001",
            creationDateTime = OffsetDateTime.of(2026, 6, 22, 10, 15, 30, 0, ZoneOffset.UTC),
            interbankSettlementDate = OffsetDateTime.of(2026, 6, 22, 10, 15, 30, 0, ZoneOffset.UTC),
            endToEndId = "E2E-0001",
            transactionId = "TX-0001",
            amount = BigDecimal(amount),
            currency = "EUR",
            chargeBearer = ChargeBearer.SLEV,
            settlementMethod = SettlementMethod.CLRG,
            debtorName = "Alice Debtor",
            debtorIban = "DE89370400440532013000",
            debtorAgentBic = "COBADEFFXXX",
            creditorAgentBic = "BNPAFRPPXXX",
            creditorName = "Bob Creditor",
            creditorIban = "FR1420041010050500013M02606",
            remittanceInfo = "Invoice 2026-0042",
            paymentTypeInformation = paymentType,
        ),
    )

    @Test
    fun `a settling transfer yields an ACSC pacs_002 and a camt_054 notification`() {
        val result = service.clear(pacs008Xml("12.34"), now)

        assertThat(result.settled).isTrue()
        assertThat(result.statusReportXml).contains("<TxSts>ACSC</TxSts>")
        assertThat(result.statusReportXml).contains("<OrgnlEndToEndId>E2E-0001</OrgnlEndToEndId>")
        assertThat(result.creditNotificationXml).isNotNull()
        assertThat(result.creditNotificationXml!!).contains("<CdtDbtInd>CRDT</CdtDbtInd>")
        assertThat(result.creditNotificationXml!!).contains("<IBAN>FR1420041010050500013M02606</IBAN>")
        assertThat(result.creditNotificationXml!!).contains("<EndToEndId>E2E-0001</EndToEndId>")
    }

    // #13663: sepa-payment and sepa-instant now send PmtTpInf. The simulator validates against the
    // shared XSD before it reads anything, so a schema that did not admit the block would turn every
    // SEPA payment into an FF01 reject. Both shapes must clear, and the reader must see the type.
    @Test
    fun `an SCT with PmtTpInf SvcLvl SEPA validates, settles and is read as non-instant`() {
        val xml = pacs008Xml("12.34", PaymentTypeInformation.SEPA_CREDIT_TRANSFER)
        val result = service.clear(xml, now)

        assertThat(result.settled).isTrue()
        assertThat(result.statusReportXml).contains("<TxSts>ACSC</TxSts>")
        val read = Pacs008Reader().read(xml)
        assertThat(read.serviceLevelCode).isEqualTo("SEPA")
        assertThat(read.isInstant).isFalse()
    }

    @Test
    fun `an SCT Inst with PmtTpInf LclInstrm INST validates, settles and is read as instant`() {
        val xml = pacs008Xml("12.34", PaymentTypeInformation.SEPA_INSTANT)
        val result = service.clear(xml, now)

        assertThat(result.settled).isTrue()
        assertThat(result.statusReportXml).contains("<TxSts>ACSC</TxSts>")
        assertThat(result.creditNotificationXml!!).contains("<EndToEndId>E2E-0001</EndToEndId>")
        val read = Pacs008Reader().read(xml)
        assertThat(read.serviceLevelCode).isEqualTo("SEPA")
        assertThat(read.localInstrumentCode).isEqualTo("INST")
        assertThat(read.isInstant).isTrue()
    }

    @Test
    fun `a triggered reject yields RJCT with reason and no notification`() {
        val result = service.clear(pacs008Xml("10.01"), now)

        assertThat(result.settled).isFalse()
        assertThat(result.statusReportXml).contains("<TxSts>RJCT</TxSts>")
        assertThat(result.statusReportXml).contains("<Cd>AC04</Cd>")
        assertThat(result.creditNotificationXml).isNull()
    }

    @Test
    fun `a message that fails XSD validation is rejected with FF01`() {
        val result = service.clear("<not-a-pacs008/>", now)

        assertThat(result.settled).isFalse()
        assertThat(result.statusReportXml).contains("<TxSts>RJCT</TxSts>")
        assertThat(result.statusReportXml).contains("<Cd>FF01</Cd>")
        assertThat(result.creditNotificationXml).isNull()
    }

    @Test
    fun `output is deterministic for the same input and instant`() {
        assertThat(service.clear(pacs008Xml("12.34"), now))
            .isEqualTo(service.clear(pacs008Xml("12.34"), now))
    }

    @Test
    fun `generateReturn produces valid pacs_004 XML with correct fields`() {
        val request = ReturnRequest(
            originalEndToEndId = "E2E-0001",
            originalTransactionId = "TX-0001",
            amount = BigDecimal("12.34"),
            currency = "EUR",
            returnReasonCode = "AC04",
            additionalInfo = "Account closed",
        )

        val xml = service.generateReturn(request, now)

        assertThat(xml).contains("urn:iso:std:iso:20022:tech:xsd:pacs.004.001.09")
        assertThat(xml).contains("<OrgnlEndToEndId>E2E-0001</OrgnlEndToEndId>")
        assertThat(xml).contains("<OrgnlTxId>TX-0001</OrgnlTxId>")
        assertThat(xml).contains("12.34")
        assertThat(xml).contains("Ccy=\"EUR\"")
        assertThat(xml).contains("<Cd>AC04</Cd>")
        assertThat(xml).contains("Account closed")
    }

    @Test
    fun `generateReturn produces valid pacs_004 without optional fields`() {
        val request = ReturnRequest(
            originalEndToEndId = "E2E-0042",
            originalTransactionId = null,
            amount = BigDecimal("99.00"),
            currency = "EUR",
            returnReasonCode = null,
        )

        val xml = service.generateReturn(request, now)

        assertThat(xml).contains("urn:iso:std:iso:20022:tech:xsd:pacs.004.001.09")
        assertThat(xml).contains("<OrgnlEndToEndId>E2E-0042</OrgnlEndToEndId>")
        assertThat(xml).contains("99.00")
        assertThat(xml).doesNotContain("OrgnlTxId")
        assertThat(xml).doesNotContain("RtrRsnInf")
    }
}
