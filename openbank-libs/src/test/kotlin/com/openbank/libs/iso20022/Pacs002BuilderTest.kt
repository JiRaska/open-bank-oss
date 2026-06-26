// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

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
}
