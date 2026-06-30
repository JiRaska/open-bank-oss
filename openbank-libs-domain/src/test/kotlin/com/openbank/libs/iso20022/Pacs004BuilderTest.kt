// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.iso20022

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.ZoneOffset

class Pacs004BuilderTest {
    private val builder = Pacs004Builder()
    private val validator = Iso20022Validator.forSchema(Pacs004Builder.SCHEMA_RESOURCE)

    private fun paymentReturn(currency: String = "EUR", reason: String? = "AC04") = PaymentReturn(
        messageId = "OB-RTR-0001",
        creationDateTime = OffsetDateTime.of(2026, 6, 22, 10, 20, 0, 0, ZoneOffset.UTC),
        settlementMethod = SettlementMethod.CLRG,
        returnId = "RTR-0001",
        originalEndToEndId = "E2E-0001",
        originalTransactionId = "TX-0001",
        returnedAmount = BigDecimal("12.34"),
        currency = currency,
        returnReasonCode = reason,
        additionalInfo = reason?.let { "returned: $it" },
    )

    @Test
    fun `a payment return validates against the vendored XSD`() {
        val xml = builder.build(paymentReturn())
        assertThat(xml).contains("urn:iso:std:iso:20022:tech:xsd:pacs.004.001.09")
        assertThat(xml).contains("<OrgnlEndToEndId>E2E-0001</OrgnlEndToEndId>")
        assertThat(xml).contains("Ccy=\"EUR\"")
        assertThat(xml).contains("<Cd>AC04</Cd>")
        assertThat(validator.validate(xml)).isEqualTo(Iso20022ValidationResult.Valid)
    }

    @Test
    fun `a return without a reason still validates`() {
        val xml = builder.build(paymentReturn(reason = null))
        assertThat(xml).doesNotContain("RtrRsnInf")
        assertThat(validator.validate(xml)).isEqualTo(Iso20022ValidationResult.Valid)
    }

    @Test
    fun `a non-ISO currency is rejected by XSD validation`() {
        val result = validator.validate(builder.build(paymentReturn(currency = "EU")))
        assertThat(result).isInstanceOf(Iso20022ValidationResult.Invalid::class.java)
    }
}
