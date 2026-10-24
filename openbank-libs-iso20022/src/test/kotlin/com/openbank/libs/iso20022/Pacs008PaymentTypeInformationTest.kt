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

/**
 * #13663: `CdtTrfTxInf/PmtTpInf` on the outbound pacs.008.
 *
 * The goldens are exact bytes. `pacs008-without-payment-type.xml` was captured from the builder
 * BEFORE `paymentTypeInformation` existed, so it pins that every caller which does not set the field
 * still emits a byte-identical document. The SCT and SCT Inst goldens are that document with the
 * EPC-mandated block inserted after `PmtId`, written by hand rather than captured.
 */
class Pacs008PaymentTypeInformationTest {
    private val builder = Pacs008Builder()
    private val validator = Iso20022Validator.forSchema(Pacs008Builder.SCHEMA_RESOURCE)
    private val reader = Pacs008Reader()

    private val at = OffsetDateTime.of(2026, 6, 22, 10, 15, 30, 0, ZoneOffset.UTC)

    // Deliberately the pre-#13663 16-argument constructor: the shape every existing caller used.
    private fun legacy() = CreditTransferInstruction(
        "OB-MSG-0001", at, at, "E2E-0001", "TX-0001", BigDecimal("12.34"), "EUR",
        ChargeBearer.SLEV, SettlementMethod.CLRG, "Alice Debtor", "DE89370400440532013000",
        "COBADEFFXXX", "BNPAFRPPXXX", "Bob Creditor", "FR1420041010050500013M02606", "Invoice 2026-0042",
    )

    private fun golden(name: String): String =
        requireNotNull(javaClass.getResource("/iso20022/golden/$name")) { "missing golden $name" }.readText()

    @Test
    fun `a caller that sets no payment type emits the byte-identical pre-13663 document`() {
        val viaLegacyConstructor = builder.build(legacy())
        val viaNamedDefault = builder.build(legacy().copy(paymentTypeInformation = null))

        assertThat(viaLegacyConstructor).isEqualTo(golden("pacs008-without-payment-type.xml"))
        assertThat(viaNamedDefault).isEqualTo(viaLegacyConstructor)
        assertThat(viaLegacyConstructor).doesNotContain("PmtTpInf")
    }

    @Test
    fun `an SCT carries SvcLvl SEPA and no local instrument, byte for byte`() {
        val xml = builder.build(legacy().copy(paymentTypeInformation = PaymentTypeInformation.SEPA_CREDIT_TRANSFER))

        assertThat(xml).isEqualTo(golden("pacs008-sct.xml"))
        assertThat(xml).doesNotContain("LclInstrm")
    }

    @Test
    fun `an SCT Inst carries SvcLvl SEPA and LclInstrm INST, byte for byte`() {
        val xml = builder.build(legacy().copy(paymentTypeInformation = PaymentTypeInformation.SEPA_INSTANT))

        assertThat(xml).isEqualTo(golden("pacs008-sct-inst.xml"))
    }

    @Test
    fun `all three shapes validate against the vendored XSD`() {
        listOf(null, PaymentTypeInformation.SEPA_CREDIT_TRANSFER, PaymentTypeInformation.SEPA_INSTANT).forEach {
            val xml = builder.build(legacy().copy(paymentTypeInformation = it))
            assertThat(validator.validate(xml)).describedAs("PmtTpInf=%s", it).isEqualTo(Iso20022ValidationResult.Valid)
        }
    }

    @Test
    fun `the XSD rejects PmtTpInf in the wrong position`() {
        // Official CreditTransferTransaction39 order: PmtId, PmtTpInf, IntrBkSttlmAmt. A block moved
        // after the amount must not validate, so the schema is actually checking the new element.
        val misplaced = golden("pacs008-sct.xml")
            .replace(Regex("\\s*<PmtTpInf>.*?</PmtTpInf>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(
                "<IntrBkSttlmDt>",
                "<PmtTpInf><SvcLvl><Cd>SEPA</Cd></SvcLvl></PmtTpInf><IntrBkSttlmDt>",
            )

        assertThat(validator.validate(misplaced)).isInstanceOf(Iso20022ValidationResult.Invalid::class.java)
    }

    @Test
    fun `the XSD rejects a service level code longer than ExternalServiceLevel1Code allows`() {
        val tooLong = golden("pacs008-sct.xml").replace("<Cd>SEPA</Cd>", "<Cd>SEPAX</Cd>")

        assertThat(validator.validate(tooLong)).isInstanceOf(Iso20022ValidationResult.Invalid::class.java)
    }

    @Test
    fun `the reader recovers the payment type of each shape`() {
        val none = reader.read(golden("pacs008-without-payment-type.xml"))
        val sct = reader.read(golden("pacs008-sct.xml"))
        val inst = reader.read(golden("pacs008-sct-inst.xml"))

        assertThat(none.serviceLevelCode).isNull()
        assertThat(none.localInstrumentCode).isNull()
        assertThat(none.isInstant).isFalse()
        assertThat(sct.serviceLevelCode).isEqualTo("SEPA")
        assertThat(sct.localInstrumentCode).isNull()
        assertThat(sct.isInstant).isFalse()
        assertThat(inst.serviceLevelCode).isEqualTo("SEPA")
        assertThat(inst.localInstrumentCode).isEqualTo("INST")
        assertThat(inst.isInstant).isTrue()
        // The other recovered fields do not depend on the block.
        assertThat(sct.copy(serviceLevelCode = null)).isEqualTo(none)
    }

    @Test
    fun `the legacy constructors and copies keep their meaning`() {
        val received = ReceivedCreditTransfer("M", "E", null, BigDecimal.ONE, "EUR", "C", "IBAN", "BIC", "DIBAN")
        assertThat(received.serviceLevelCode).isNull()
        assertThat(received.localInstrumentCode).isNull()

        val inst = received.copy(localInstrumentCode = "INST")
        val legacyCopied = inst.copy("M2", "E", null, BigDecimal.ONE, "EUR", "C", "IBAN", "BIC", "DIBAN")
        assertThat(legacyCopied.messageId).isEqualTo("M2")
        assertThat(legacyCopied.localInstrumentCode).isEqualTo("INST")

        val withType = legacy().copy(paymentTypeInformation = PaymentTypeInformation.SEPA_INSTANT)
        val legacyInstructionCopy = withType.copy(
            "OB-MSG-0002", at, at, "E2E-0001", "TX-0001", BigDecimal("12.34"), "EUR",
            ChargeBearer.SLEV, SettlementMethod.CLRG, "Alice Debtor", "DE89370400440532013000",
            "COBADEFFXXX", "BNPAFRPPXXX", "Bob Creditor", "FR1420041010050500013M02606", "Invoice 2026-0042",
        )
        assertThat(legacyInstructionCopy.messageId).isEqualTo("OB-MSG-0002")
        assertThat(legacyInstructionCopy.paymentTypeInformation).isEqualTo(PaymentTypeInformation.SEPA_INSTANT)
    }

    @Test
    fun `payment type codes outside the ISO lengths are refused at construction`() {
        assertThatThrownBy { PaymentTypeInformation("") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { PaymentTypeInformation("SEPAX") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { PaymentTypeInformation("SEPA", "") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { PaymentTypeInformation("SEPA", "X".repeat(36)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(PaymentTypeInformation("SEPA", "X".repeat(35)).localInstrumentCode).hasSize(35)
    }
}
