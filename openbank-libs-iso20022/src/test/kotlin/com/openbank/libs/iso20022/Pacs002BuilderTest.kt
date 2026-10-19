// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.iso20022

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.time.ZoneOffset

class Pacs002BuilderTest {
    private val builder = Pacs002Builder()
    private val validator = Iso20022Validator.forSchema(Pacs002Builder.SCHEMA_RESOURCE)

    private fun report(status: PaymentStatus, reasonCode: String? = null, additionalInfo: String? = null) =
        PaymentStatusReport(
            messageId = "OB-STS-0001",
            creationDateTime = OffsetDateTime.of(2026, 6, 22, 10, 16, 0, 0, ZoneOffset.UTC),
            originalEndToEndId = "E2E-0001",
            originalTransactionId = "TX-0001",
            status = status,
            reasonCode = reasonCode,
            additionalInfo = additionalInfo,
        )

    @Test
    fun `an ACSC settlement ack validates against the vendored XSD`() {
        val xml = builder.build(report(PaymentStatus.ACSC))
        assertThat(xml).contains("urn:iso:std:iso:20022:tech:xsd:pacs.002.001.10")
        assertThat(xml).contains("<TxSts>ACSC</TxSts>")
        assertThat(xml).contains("<OrgnlEndToEndId>E2E-0001</OrgnlEndToEndId>")
        assertThat(validator.validate(xml)).isEqualTo(Iso20022ValidationResult.Valid)
    }

    @Test
    fun `an RJCT reject with a reason code validates and carries the code`() {
        val xml = builder.build(report(PaymentStatus.RJCT, reasonCode = "AC04", additionalInfo = "closed account"))
        assertThat(xml).contains("<TxSts>RJCT</TxSts>")
        assertThat(xml).contains("<Cd>AC04</Cd>")
        assertThat(xml).contains("<AddtlInf>closed account</AddtlInf>")
        assertThat(validator.validate(xml)).isEqualTo(Iso20022ValidationResult.Valid)
    }

    @Test
    fun `a status report without a reason omits StsRsnInf and still validates`() {
        val xml = builder.build(report(PaymentStatus.ACSP))
        assertThat(xml).doesNotContain("StsRsnInf")
        assertThat(validator.validate(xml)).isEqualTo(Iso20022ValidationResult.Valid)
    }

    @Test
    fun `a report naming the original message and UETR validates and reads back`() {
        val xml = Pacs002Builder().build(
            PaymentStatusReport(
                messageId = "SIM-STS-1",
                creationDateTime = java.time.OffsetDateTime.parse("2026-10-19T09:00:00Z"),
                originalEndToEndId = "E2E",
                originalTransactionId = null,
                status = PaymentStatus.ACSC,
                originalMessageId = "FI-1B4E28BA2FA14D2E",
                originalMessageNameId = "pacs.009.001.08",
                originalUetr = "1b4e28ba-2fa1-4d2e-883f-0016d3cca427",
            ),
        )
        assertThat(
            Iso20022Validator.forSchema(Pacs002Builder.SCHEMA_RESOURCE).validate(xml),
        ).isEqualTo(Iso20022ValidationResult.Valid)
        val read = Pacs002Reader().read(xml)
        assertThat(read.originalMessageId).isEqualTo("FI-1B4E28BA2FA14D2E")
        assertThat(read.originalUetr).isEqualTo("1b4e28ba-2fa1-4d2e-883f-0016d3cca427")
    }
}
