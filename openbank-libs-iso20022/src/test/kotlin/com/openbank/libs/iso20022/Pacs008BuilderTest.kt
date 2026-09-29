// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.iso20022

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.ZoneOffset

class Pacs008BuilderTest {
    private val builder = Pacs008Builder()
    private val validator = Iso20022Validator.forSchema(Pacs008Builder.SCHEMA_RESOURCE)

    private fun sepaSct(
        debtorIban: String = "DE89370400440532013000",
        creditorIban: String = "FR1420041010050500013M02606",
        debtorAgentBic: String = "COBADEFFXXX",
        creditorAgentBic: String = "BNPAFRPPXXX",
        amount: BigDecimal = BigDecimal("12.34"),
        currency: String = "EUR",
        remittanceInfo: String? = "Invoice 2026-0042",
    ) = CreditTransferInstruction(
        messageId = "OB-MSG-0001",
        creationDateTime = OffsetDateTime.of(2026, 6, 22, 10, 15, 30, 0, ZoneOffset.UTC),
        interbankSettlementDate = OffsetDateTime.of(2026, 6, 22, 10, 15, 30, 0, ZoneOffset.UTC),
        endToEndId = "E2E-0001",
        transactionId = "TX-0001",
        amount = amount,
        currency = currency,
        chargeBearer = ChargeBearer.SLEV,
        settlementMethod = SettlementMethod.CLRG,
        debtorName = "Alice Debtor",
        debtorIban = debtorIban,
        debtorAgentBic = debtorAgentBic,
        creditorAgentBic = creditorAgentBic,
        creditorName = "Bob Creditor",
        creditorIban = creditorIban,
        remittanceInfo = remittanceInfo,
    )

    @Test
    fun `builds a namespace-qualified pacs_008 document with the expected fields`() {
        val xml = builder.build(sepaSct())

        assertThat(xml).contains("urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08")
        assertThat(xml).contains("<FIToFICstmrCdtTrf>")
        assertThat(xml).contains("<EndToEndId>E2E-0001</EndToEndId>")
        assertThat(xml).contains("Ccy=\"EUR\"")
        assertThat(xml).contains(">12.34<")
        assertThat(xml).contains("<IntrBkSttlmDt>2026-06-22</IntrBkSttlmDt>")
        assertThat(xml).contains("<BICFI>COBADEFFXXX</BICFI>")
        assertThat(xml).contains("<IBAN>FR1420041010050500013M02606</IBAN>")
        assertThat(xml).contains("<Ustrd>Invoice 2026-0042</Ustrd>")
    }

    @Test
    fun `a well-formed SEPA SCT validates against the vendored XSD`() {
        val result = validator.validate(builder.build(sepaSct()))
        assertThat(result).isEqualTo(Iso20022ValidationResult.Valid)
    }

    @Test
    fun `a transfer without remittance info still validates (RmtInf is optional)`() {
        val result = validator.validate(builder.build(sepaSct(remittanceInfo = null)))
        assertThat(result).isEqualTo(Iso20022ValidationResult.Valid)
    }

    @Test
    fun `a malformed BIC is rejected by XSD validation`() {
        val result = validator.validate(builder.build(sepaSct(debtorAgentBic = "NOT-A-BIC")))
        assertThat(result).isInstanceOf(Iso20022ValidationResult.Invalid::class.java)
        assertThat((result as Iso20022ValidationResult.Invalid).errors).isNotEmpty()
    }

    @Test
    fun `a malformed IBAN is rejected by XSD validation`() {
        val result = validator.validate(builder.build(sepaSct(creditorIban = "12-not-an-iban")))
        assertThat(result).isInstanceOf(Iso20022ValidationResult.Invalid::class.java)
    }

    @Test
    fun `a non-ISO currency code is rejected by XSD validation`() {
        val result = validator.validate(builder.build(sepaSct(currency = "EU")))
        assertThat(result).isInstanceOf(Iso20022ValidationResult.Invalid::class.java)
    }

    @Test
    fun `the validator fails loudly when the schema resource is missing`() {
        val ex = runCatching { Iso20022Validator.forSchema("does.not.exist.xsd") }.exceptionOrNull()
        assertThat(ex).isInstanceOf(IllegalStateException::class.java)
    }
}
