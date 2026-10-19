// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.iso20022

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

class Pacs009BuilderTest {
    private val builder = Pacs009Builder()
    private val validator = Iso20022Validator.forSchema(Pacs009Builder.SCHEMA_RESOURCE)
    private val reader = Pacs009Reader()

    private fun instruction(
        clearingSystem: ClearingSystem? = ClearingSystem(code = "TGT"),
        settlementMethod: SettlementMethod = SettlementMethod.CLRG,
        debtorAccount: String? = "DE89370400440532013000",
        creditorAccount: String? = "123456789/0800",
        intermediary: String? = null,
        creditorBic: String = "BNPAFRPPXXX",
        remittance: String? = "deal 42",
        uetr: String? = "1b4e28ba-2fa1-4d2e-883f-0016d3cca427",
    ) = InstitutionCreditTransferInstruction(
        messageId = "OB-FI-0001",
        creationDateTime = OffsetDateTime.of(2026, 10, 19, 9, 0, 0, 0, ZoneOffset.UTC),
        interbankSettlementDate = LocalDate.of(2026, 10, 19),
        instructionId = "INSTR-1",
        endToEndId = "E2E-1",
        transactionId = "TX-1",
        uetr = uetr,
        amount = BigDecimal("1000000.00"),
        currency = "EUR",
        settlementMethod = settlementMethod,
        clearingSystem = clearingSystem,
        instructingAgentBic = "OPENCZPPXXX",
        instructedAgentBic = creditorBic,
        intermediaryAgentBic = intermediary,
        debtorBic = "OPENCZPPXXX",
        debtorAccount = debtorAccount,
        creditorBic = creditorBic,
        creditorName = "Counterparty Bank",
        creditorAccount = creditorAccount,
        remittanceInfo = remittance,
    )

    @Test
    fun `an RTGS pacs_009 with a clearing-system code validates and carries the agents`() {
        val xml = builder.build(instruction())
        assertThat(validator.validate(xml)).isEqualTo(Iso20022ValidationResult.Valid)
        assertThat(xml).contains("<FICdtTrf>", "<Cd>TGT</Cd>", "<SttlmMtd>CLRG</SttlmMtd>")
        assertThat(xml).contains("<IBAN>DE89370400440532013000</IBAN>", "<Id>123456789/0800</Id>")
        assertThat(xml).contains("<UETR>1b4e28ba-2fa1-4d2e-883f-0016d3cca427</UETR>")
    }

    @Test
    fun `a proprietary clearing system and an intermediary agent validate`() {
        val xml = builder.build(
            instruction(clearingSystem = ClearingSystem(proprietary = "CERTIS"), intermediary = "COBADEFFXXX"),
        )
        assertThat(validator.validate(xml)).isEqualTo(Iso20022ValidationResult.Valid)
        assertThat(xml).contains("<Prtry>CERTIS</Prtry>", "<IntrmyAgt1>")
    }

    @Test
    fun `a correspondent pacs_009 with no clearing system and no optional parts validates`() {
        val xml = builder.build(
            instruction(
                clearingSystem = null,
                settlementMethod = SettlementMethod.INDA,
                debtorAccount = null,
                creditorAccount = null,
                remittance = null,
                uetr = null,
            ),
        )
        assertThat(validator.validate(xml)).isEqualTo(Iso20022ValidationResult.Valid)
        assertThat(xml).doesNotContain("ClrSys", "DbtrAcct", "CdtrAcct", "RmtInf", "UETR")
    }

    @Test
    fun `a malformed BIC or a non-v4 UETR fails XSD validation`() {
        assertThat(validator.validate(builder.build(instruction(creditorBic = "NOT-A-BIC"))))
            .isInstanceOf(Iso20022ValidationResult.Invalid::class.java)
        assertThat(validator.validate(builder.build(instruction(uetr = "00000000-0000-1000-8000-000000000000"))))
            .isInstanceOf(Iso20022ValidationResult.Invalid::class.java)
    }

    @Test
    fun `a pacs_008 does not validate against the pacs_009 schema`() {
        val pacs008 = "<Document xmlns=\"${Pacs008Builder.NAMESPACE}\"><FIToFICstmrCdtTrf/></Document>"
        assertThat(validator.validate(pacs008)).isInstanceOf(Iso20022ValidationResult.Invalid::class.java)
    }

    @Test
    fun `a clearing system needs exactly one of code and proprietary`() {
        assertThatThrownBy { ClearingSystem() }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ClearingSystem("TGT", "X") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `the reader round-trips the built message`() {
        val read = reader.read(builder.build(instruction(clearingSystem = ClearingSystem(proprietary = "CERTIS"))))
        assertThat(read.messageId).isEqualTo("OB-FI-0001")
        assertThat(read.endToEndId).isEqualTo("E2E-1")
        assertThat(read.transactionId).isEqualTo("TX-1")
        assertThat(read.uetr).isEqualTo("1b4e28ba-2fa1-4d2e-883f-0016d3cca427")
        assertThat(read.amount).isEqualByComparingTo("1000000.00")
        assertThat(read.currency).isEqualTo("EUR")
        assertThat(read.instructingAgentBic).isEqualTo("OPENCZPPXXX")
        assertThat(read.instructedAgentBic).isEqualTo("BNPAFRPPXXX")
        assertThat(read.creditorBic).isEqualTo("BNPAFRPPXXX")
        assertThat(read.settlementMethod).isEqualTo("CLRG")
        assertThat(read.clearingSystem).isEqualTo("CERTIS")
    }

    @Test
    fun `the reader reports no clearing system when none is present`() {
        val read = reader.read(builder.build(instruction(clearingSystem = null, uetr = null)))
        assertThat(read.clearingSystem).isNull()
        assertThat(read.uetr).isNull()
    }

    @Test
    fun `malformed input surfaces as the typed parse exception`() {
        for (bad in listOf("", "<", "<Document xmlns=\"${Pacs009Builder.NAMESPACE}\"/>")) {
            assertThatThrownBy { reader.read(bad) }.isInstanceOf(Pacs009ParseException::class.java)
        }
        val noCcy = builder.build(instruction()).replace(" Ccy=\"EUR\"", "")
        assertThatThrownBy { reader.read(noCcy) }.isInstanceOf(Pacs009ParseException::class.java)
        val badAmount = builder.build(instruction()).replace(">1000000.00<", ">abc<")
        assertThatThrownBy { reader.read(badAmount) }.isInstanceOf(Pacs009ParseException::class.java)
    }
}
