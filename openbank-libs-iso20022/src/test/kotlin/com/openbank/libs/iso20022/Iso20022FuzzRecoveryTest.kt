// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.iso20022

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.Random

/** Reproducible mutation/round-trip cases run in every build, alongside coverage-guided Jazzer. */
@Timeout(30)
class Iso20022FuzzRecoveryTest {
    private val builder = Pacs008Builder()
    private val reader = Pacs008Reader()
    private val validator = Iso20022Validator.forSchema("pacs.008.001.08.xsd")
    private val instruction = CreditTransferInstruction(
        messageId = "FUZZ-1",
        creationDateTime = OffsetDateTime.parse("2026-01-01T12:00:00Z"),
        interbankSettlementDate = OffsetDateTime.parse("2026-01-02T12:00:00Z"),
        endToEndId = "E2E-1",
        transactionId = "TX-1",
        amount = BigDecimal("12.34"),
        currency = "EUR",
        chargeBearer = ChargeBearer.SLEV,
        settlementMethod = SettlementMethod.CLRG,
        debtorName = "Test Debtor",
        debtorIban = "DE89370400440532013000",
        debtorAgentBic = "COBADEFFXXX",
        creditorAgentBic = "BNPAFRPPXXX",
        creditorName = "Test Creditor",
        creditorIban = "FR1420041010050500013M02606",
        remittanceInfo = "Test transfer",
    )

    @Test
    fun `Jazzer SAX scanner regression is invalid and the same instances recover`() {
        val input = checkNotNull(
            javaClass.getResourceAsStream("/iso20022/regressions/validator-sax-without-coordinates.xml"),
        ).use { it.readBytes().toString(Charsets.UTF_8) }
        assertThat(validator.validate(input)).isInstanceOf(Iso20022ValidationResult.Invalid::class.java)
        assertThatThrownBy { reader.read(input) }.isInstanceOf(Pacs008ParseException::class.java)
        val valid = builder.build(instruction)
        assertThat(validator.validate(valid)).isEqualTo(Iso20022ValidationResult.Valid)
        assertThat(reader.read(valid).messageId).isEqualTo(instruction.messageId)
    }

    @Test
    fun `seeded escaped values and amounts survive independent message round trips`() {
        val random = Random(0x20022)
        val fragments = listOf("&", "<", ">", "\"", "'", "Č", "€", "中文", "plain")
        repeat(128) { index ->
            val name = "Test " + List(8) { fragments[random.nextInt(fragments.size)] }.joinToString("")
            val expected = instruction.copy(
                messageId = "FUZZ-$index",
                endToEndId = "E2E-$index",
                creditorName = name,
                amount = BigDecimal.valueOf(1L + random.nextInt(10_000_000), 2),
            )
            val xml = builder.build(expected)
            assertThat(validator.validate(xml)).isEqualTo(Iso20022ValidationResult.Valid)
            val received = reader.read(xml)
            assertThat(received.messageId).isEqualTo(expected.messageId)
            assertThat(received.endToEndId).isEqualTo(expected.endToEndId)
            assertThat(received.creditorName).isEqualTo(name)
            assertThat(received.amount).isEqualByComparingTo(expected.amount)
        }
    }

    @Test
    fun `seeded wire mutations never leak raw errors and do not poison subsequent messages`() {
        val random = Random(0x008)
        val valid = builder.build(instruction)
        repeat(256) {
            val offset = random.nextInt(valid.length)
            val input = when (it % 4) {
                0 -> valid.take(offset)
                1 -> valid.substring(0, offset) + "\u0000" + valid.substring(offset)
                2 -> valid.replace("12.34", "invalid-$it")
                else -> valid.replace("FIToFICstmrCdtTrf", "Unknown$it")
            }
            assertThat(validator.validate(input)).isInstanceOf(Iso20022ValidationResult.Invalid::class.java)
            try {
                reader.read(input)
            } catch (_: Pacs008ParseException) {
                // A structurally readable document may still fail its XSD; only typed rejection is allowed.
            }
            assertThat(validator.validate(valid)).isEqualTo(Iso20022ValidationResult.Valid)
            assertThat(reader.read(valid).messageId).isEqualTo(instruction.messageId)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "<", "not xml", "<a></b>", "<!DOCTYPE r><r/>"])
    fun `malformed status reports are typed rejections and a subsequent valid report recovers`(invalid: String) {
        val statusReader = Pacs002Reader()
        assertThatThrownBy { statusReader.read(invalid) }.isInstanceOf(Pacs002ParseException::class.java)
        val valid = Pacs002Builder().build(
            PaymentStatusReport(
                messageId = "RECOVERY-1",
                creationDateTime = instruction.creationDateTime,
                originalEndToEndId = "E2E-RECOVERED",
                originalTransactionId = "TX-RECOVERED",
                status = PaymentStatus.ACSC,
            ),
        )
        assertThat(statusReader.read(valid).originalEndToEndId).isEqualTo("E2E-RECOVERED")
        assertThat(statusReader.read(valid).status).isEqualTo(PaymentStatus.ACSC)
    }
}
