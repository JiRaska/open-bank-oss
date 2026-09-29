// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.iso20022

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.ZoneOffset

class Pacs004ReaderTest {
    private val builder = Pacs004Builder()
    private val reader = Pacs004Reader()

    private fun fullReturn() = PaymentReturn(
        messageId = "OB-RTR-0001",
        creationDateTime = OffsetDateTime.of(2026, 6, 22, 10, 20, 0, 0, ZoneOffset.UTC),
        settlementMethod = SettlementMethod.CLRG,
        returnId = "RTR-0001",
        originalEndToEndId = "E2E-0001",
        originalTransactionId = "TX-0001",
        returnedAmount = BigDecimal("12.34"),
        currency = "EUR",
        returnReasonCode = "AC04",
        additionalInfo = "returned: AC04",
    )

    @Test
    fun `round-trip with all fields set`() {
        val original = fullReturn()
        val xml = builder.build(original)
        val parsed = reader.read(xml)

        assertThat(parsed.messageId).isEqualTo(original.messageId)
        assertThat(parsed.creationDateTime).isEqualTo(original.creationDateTime)
        assertThat(parsed.settlementMethod).isEqualTo(original.settlementMethod)
        assertThat(parsed.returnId).isEqualTo(original.returnId)
        assertThat(parsed.originalEndToEndId).isEqualTo(original.originalEndToEndId)
        assertThat(parsed.originalTransactionId).isEqualTo(original.originalTransactionId)
        assertThat(parsed.returnedAmount).isEqualByComparingTo(original.returnedAmount)
        assertThat(parsed.currency).isEqualTo(original.currency)
        assertThat(parsed.returnReasonCode).isEqualTo(original.returnReasonCode)
        assertThat(parsed.additionalInfo).isEqualTo(original.additionalInfo)
    }

    @Test
    fun `round-trip without optional returnReasonCode`() {
        val original = fullReturn().copy(returnReasonCode = null, additionalInfo = null)
        val parsed = reader.read(builder.build(original))

        assertThat(parsed.returnReasonCode).isNull()
        assertThat(parsed.additionalInfo).isNull()
        assertThat(parsed.messageId).isEqualTo(original.messageId)
        assertThat(parsed.returnedAmount).isEqualByComparingTo(original.returnedAmount)
    }

    @Test
    fun `round-trip without optional returnId and originalIds`() {
        val original = fullReturn().copy(returnId = null, originalEndToEndId = null, originalTransactionId = null)
        val parsed = reader.read(builder.build(original))

        assertThat(parsed.returnId).isNull()
        assertThat(parsed.originalEndToEndId).isNull()
        assertThat(parsed.originalTransactionId).isNull()
        assertThat(parsed.messageId).isEqualTo(original.messageId)
    }

    @Test
    fun `throws Pacs004ParseException for garbage XML`() {
        assertThatThrownBy { reader.read("this is not xml") }
            .isInstanceOf(Pacs004ParseException::class.java)
    }
}
