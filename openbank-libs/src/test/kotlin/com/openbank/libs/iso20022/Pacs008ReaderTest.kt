// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.libs.iso20022

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.ZoneOffset

class Pacs008ReaderTest {
    private val builder = Pacs008Builder()
    private val reader = Pacs008Reader()

    private val instruction = CreditTransferInstruction(
        messageId = "OB-MSG-0001",
        creationDateTime = OffsetDateTime.of(2026, 6, 22, 10, 15, 30, 0, ZoneOffset.UTC),
        endToEndId = "E2E-0001",
        transactionId = "TX-0001",
        amount = BigDecimal("12.34"),
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
    )

    @Test
    fun `round-trips a built pacs_008 back into its key fields`() {
        val received = reader.read(builder.build(instruction))

        assertThat(received.messageId).isEqualTo("OB-MSG-0001")
        assertThat(received.endToEndId).isEqualTo("E2E-0001")
        assertThat(received.transactionId).isEqualTo("TX-0001")
        assertThat(received.amount).isEqualByComparingTo(BigDecimal("12.34"))
        assertThat(received.currency).isEqualTo("EUR")
        assertThat(received.creditorName).isEqualTo("Bob Creditor")
        assertThat(received.creditorIban).isEqualTo("FR1420041010050500013M02606")
        assertThat(received.creditorAgentBic).isEqualTo("BNPAFRPPXXX")
        assertThat(received.debtorIban).isEqualTo("DE89370400440532013000")
    }

    @Test
    fun `reads a transfer with no transaction id as null`() {
        val received = reader.read(builder.build(instruction.copy(transactionId = null)))
        assertThat(received.transactionId).isNull()
    }

    @Test
    fun `rejects a message missing a required element`() {
        val noCreditor = """
            <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08">
              <FIToFICstmrCdtTrf>
                <GrpHdr><MsgId>M1</MsgId></GrpHdr>
                <CdtTrfTxInf>
                  <PmtId><EndToEndId>E2E-0001</EndToEndId></PmtId>
                  <IntrBkSttlmAmt Ccy="EUR">12.34</IntrBkSttlmAmt>
                </CdtTrfTxInf>
              </FIToFICstmrCdtTrf>
            </Document>
        """.trimIndent()

        assertThatThrownBy { reader.read(noCreditor) }
            .isInstanceOf(Pacs008ParseException::class.java)
    }

    @Test
    fun `rejects a message whose amount has no currency`() {
        val noCcy = """
            <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08">
              <FIToFICstmrCdtTrf>
                <GrpHdr><MsgId>M1</MsgId></GrpHdr>
                <CdtTrfTxInf>
                  <PmtId><EndToEndId>E2E-0001</EndToEndId></PmtId>
                  <IntrBkSttlmAmt>12.34</IntrBkSttlmAmt>
                  <Cdtr><Nm>Bob</Nm></Cdtr>
                  <CdtrAcct><Id><IBAN>FR1420041010050500013M02606</IBAN></Id></CdtrAcct>
                  <CdtrAgt><FinInstnId><BICFI>BNPAFRPPXXX</BICFI></FinInstnId></CdtrAgt>
                  <DbtrAcct><Id><IBAN>DE89370400440532013000</IBAN></Id></DbtrAcct>
                </CdtTrfTxInf>
              </FIToFICstmrCdtTrf>
            </Document>
        """.trimIndent()

        assertThatThrownBy { reader.read(noCcy) }
            .isInstanceOf(Pacs008ParseException::class.java)
    }
}
